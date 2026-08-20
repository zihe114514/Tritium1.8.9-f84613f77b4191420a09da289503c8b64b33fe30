package com.muoniumplayer.core.ncm.customsource;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A per-source, single-threaded LX bridge backed by Rhino. Java classes are hidden from scripts;
 * only the explicit lx API is injected. HTTP callbacks are marshalled back to this runtime thread
 * so Promise chains keep their expected ordering.
 */
final class LxScriptRuntime {

    private static final long INITIALIZE_TIMEOUT_MS = 5_500L;
    private static final long RESOLVE_TIMEOUT_MS = 12_000L;
    private static final long HTTP_TIMEOUT_MS = 12_000L;
    private static final int MAX_HTTP_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Set<String> SUPPORTED_SOURCE_KEYS = new LinkedHashSet<>(Arrays.asList("wy", "tx", "kw", "kg", "mg"));
    private static final ExecutorService HTTP = Executors.newCachedThreadPool(daemonFactory("LX Source HTTP"));

    private final CustomSourceInfo info;
    private final String script;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(daemonFactory("LX Source Runtime"));
    private final ContextFactory contextFactory = new ContextFactory();
    private final Set<String> declaredSources = new LinkedHashSet<>();
    private final Map<String, CompletableFuture<String>> pendingResolutions = new LinkedHashMap<>();

    private volatile boolean closed;
    private volatile boolean ready;
    private Context context;
    private Scriptable scope;
    private Function requestHandler;
    private Function promiseObserver;

    LxScriptRuntime(CustomSourceInfo info, String script) {
        this.info = info;
        this.script = script == null ? "" : script;
    }

    List<String> initialize() throws Exception {
        Future<List<String>> task = executor.submit(() -> initializeOnRuntimeThread());
        try {
            return task.get(INITIALIZE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            task.cancel(true);
            close();
            throw new IllegalStateException("音源初始化超时");
        } catch (ExecutionException failure) {
            throw unwrap(failure);
        }
    }

    boolean isReady() {
        return ready && !closed;
    }

    String resolveMusicUrl(String source, Map<String, Object> musicInfo, String quality) throws Exception {
        if (!isReady()) throw new IllegalStateException("音源尚未就绪");
        final CompletableFuture<String> result = new CompletableFuture<>();
        final String requestId = UUID.randomUUID().toString();
        Future<?> task = executor.submit(() -> invokeMusicUrlOnRuntimeThread(requestId, source, musicInfo, quality, result));
        try {
            task.get(1, TimeUnit.SECONDS);
            return result.get(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            synchronized (pendingResolutions) { pendingResolutions.remove(requestId); }
            throw new IllegalStateException("自定义音源解析超时");
        } catch (ExecutionException failure) {
            throw unwrap(failure);
        }
    }

    void close() {
        if (closed) return;
        closed = true;
        ready = false;
        executor.submit(() -> {
            synchronized (pendingResolutions) {
                for (CompletableFuture<String> future : pendingResolutions.values()) {
                    future.completeExceptionally(new IllegalStateException("音源已关闭"));
                }
                pendingResolutions.clear();
            }
            if (context != null) {
                try { Context.exit(); } catch (Throwable ignored) { }
                context = null;
            }
            scope = null;
            requestHandler = null;
        });
        executor.shutdownNow();
    }

    private List<String> initializeOnRuntimeThread() throws Exception {
        if (closed) throw new IllegalStateException("音源已关闭");
        context = contextFactory.enterContext();
        context.setOptimizationLevel(-1);
        context.setLanguageVersion(Context.VERSION_ES6);
        context.setClassShutter(new ClassShutter() {
            @Override public boolean visibleToScripts(String className) { return false; }
        });
        scope = context.initStandardObjects();
        installLxApi();
        context.evaluateString(scope,
                "var globalThis = this; var window = this;"
                        + "function __muoniumOptionalGet(base,key){return base==null?undefined:base[key];}"
                        + "function __muoniumOptionalIndex(base,key){return base==null?undefined:base[key];}"
                        + "function __muoniumOptionalCall(base,key,args){return base==null?undefined:base[key].apply(base,args);}"
                        + "if (typeof console === 'undefined') console={log:function(){},error:function(){},warn:function(){}};",
                "muonium-lx-prelude", 1, null);
        context.evaluateString(scope, prepareScript(script), safeName(info.name), 1, null);
        processMicrotasks();
        if (declaredSources.isEmpty()) {
            throw new IllegalStateException("脚本未发送有效的 lx.inited 来源声明");
        }
        if (requestHandler == null) {
            throw new IllegalStateException("脚本未注册 lx.on(request) 处理器");
        }
        ready = true;
        return new ArrayList<>(declaredSources);
    }

    private void installLxApi() {
        NativeObject lx = new NativeObject();
        lx.setParentScope(scope);
        NativeObject eventNames = new NativeObject();
        put(eventNames, "request", "request");
        put(eventNames, "inited", "inited");
        put(eventNames, "updateAlert", "updateAlert");
        put(lx, "EVENT_NAMES", eventNames);
        put(lx, "version", "2.0.0-muonium");
        put(lx, "env", "minecraft-forge");
        put(lx, "currentScriptInfo", createScriptInfo());
        put(lx, "on", nativeFunction((cx, args) -> handleOn(args)));
        put(lx, "send", nativeFunction((cx, args) -> handleSend(args)));
        put(lx, "request", nativeFunction((cx, args) -> handleHttpRequest(cx, args)));
        put(lx, "utils", createUtils());
        ScriptableObject.putProperty(scope, "lx", lx);
    }

    private Scriptable createScriptInfo() {
        NativeObject object = new NativeObject();
        put(object, "name", safe(info.name)); put(object, "description", safe(info.description));
        put(object, "version", safe(info.version)); put(object, "author", safe(info.author));
        put(object, "homepage", safe(info.homepage)); put(object, "rawScript", script);
        return object;
    }

    private Scriptable createUtils() {
        NativeObject utils = new NativeObject();
        NativeObject crypto = new NativeObject();
        put(crypto, "md5", nativeFunction((cx, args) -> md5(args.length == 0 ? "" : Context.toString(args[0]))));
        put(crypto, "randomBytes", nativeFunction((cx, args) -> randomBytes(cx, args)));
        put(crypto, "aesEncrypt", nativeFunction((cx, args) -> aesEncrypt(cx, args)));
        put(crypto, "rsaEncrypt", nativeFunction((cx, args) -> { throw new IllegalStateException("当前 Runtime 不支持 rsaEncrypt"); }));
        NativeObject buffer = new NativeObject();
        put(buffer, "from", nativeFunction((cx, args) -> bytesFrom(cx, args)));
        put(buffer, "bufToString", nativeFunction((cx, args) -> bytesToString(args)));
        put(utils, "crypto", crypto); put(utils, "buffer", buffer);
        return utils;
    }

    private Object handleOn(Object[] args) {
        if (args.length < 2 || !(args[0] instanceof String) || !(args[1] instanceof Function)) {
            throw new IllegalArgumentException("lx.on 参数无效");
        }
        if (!"request".equals(String.valueOf(args[0]))) throw new IllegalArgumentException("不支持的 lx.on 事件");
        requestHandler = (Function) args[1];
        return Boolean.TRUE;
    }

    private Object handleSend(Object[] args) {
        if (args.length < 1) throw new IllegalArgumentException("lx.send 缺少事件名");
        String event = String.valueOf(args[0]);
        Object data = args.length > 1 ? args[1] : null;
        if ("inited".equals(event)) {
            registerInitializedSources(data);
            return Boolean.TRUE;
        }
        if ("updateAlert".equals(event)) {
            System.out.println("[LX Source] " + safe(info.name) + " requested update alert.");
            return Boolean.TRUE;
        }
        throw new IllegalArgumentException("不支持的 lx.send 事件：" + event);
    }

    private Object handleHttpRequest(Context cx, Object[] args) {
        if (args.length < 3 || !(args[2] instanceof Function)) throw new IllegalArgumentException("lx.request 参数无效");
        final String address = Context.toString(args[0]);
        final Scriptable options = args[1] instanceof Scriptable ? (Scriptable) args[1] : null;
        final Function callback = (Function) args[2];
        final CompletableFuture<Void> cancelled = new CompletableFuture<>();
        HTTP.submit(() -> {
            HttpResponse response = null;
            Throwable error = null;
            try { response = executeHttp(address, options); }
            catch (Throwable throwable) { error = throwable; }
            final HttpResponse finalResponse = response;
            final Throwable finalError = error;
            if (closed || cancelled.isDone()) return;
            executor.submit(() -> invokeHttpCallback(callback, finalError, finalResponse, cancelled));
        });
        return nativeFunction((ignored, ignoredArgs) -> { cancelled.complete(null); return Boolean.TRUE; });
    }

    private void invokeHttpCallback(Function callback, Throwable failure, HttpResponse response, CompletableFuture<Void> cancelled) {
        if (closed || cancelled.isDone() || context == null || scope == null) return;
        try {
            Object error = failure == null ? null : Context.toObject(new RuntimeException(messageOf(failure)), scope);
            Object result = failure == null ? createResponse(response) : null;
            callback.call(context, scope, scope, new Object[]{error, result});
            processMicrotasks();
        } catch (Throwable ignored) {
            // A script-side callback failure becomes its own rejected music-url Promise.
        }
    }

    private void invokeMusicUrlOnRuntimeThread(String requestId, String source, Map<String, Object> musicInfo,
                                                String quality, CompletableFuture<String> result) {
        if (!isReady() || requestHandler == null) {
            result.completeExceptionally(new IllegalStateException("音源未初始化"));
            return;
        }
        synchronized (pendingResolutions) { pendingResolutions.put(requestId, result); }
        try {
            NativeObject infoObject = new NativeObject();
            put(infoObject, "type", safe(quality));
            put(infoObject, "musicInfo", toScriptObject(musicInfo));
            NativeObject request = new NativeObject();
            put(request, "source", safe(source)); put(request, "action", "musicUrl"); put(request, "info", infoObject);
            Object returned = requestHandler.call(context, scope, scope, new Object[]{request});
            observePromise(requestId, returned);
            processMicrotasks();
        } catch (Throwable throwable) {
            completeFailure(requestId, messageOf(throwable));
        }
    }

    private void observePromise(String requestId, Object returned) {
        if (promiseObserver == null) {
            promiseObserver = (Function) context.evaluateString(scope,
                    "(function(value, onSuccess, onFailure) { return Promise.resolve(value).then(onSuccess, onFailure); })",
                    "muonium-lx-promise", 1, null);
        }
        Function success = nativeFunction((cx, args) -> { completeSuccess(requestId, args.length == 0 ? "" : Context.toString(args[0])); return Undefined.instance; });
        Function failure = nativeFunction((cx, args) -> { completeFailure(requestId, args.length == 0 ? "脚本拒绝请求" : Context.toString(args[0])); return Undefined.instance; });
        promiseObserver.call(context, scope, scope, new Object[]{returned, success, failure});
    }

    private void completeSuccess(String requestId, String url) {
        CompletableFuture<String> future;
        synchronized (pendingResolutions) { future = pendingResolutions.remove(requestId); }
        if (future == null) return;
        if (url == null || url.length() > 2048 || (!url.startsWith("https://") && !url.startsWith("http://"))) {
            future.completeExceptionally(new IllegalStateException("自定义音源返回的播放 URL 无效"));
        } else future.complete(url);
    }

    private void completeFailure(String requestId, String reason) {
        CompletableFuture<String> future;
        synchronized (pendingResolutions) { future = pendingResolutions.remove(requestId); }
        if (future != null) future.completeExceptionally(new IllegalStateException(safe(reason).isEmpty() ? "音源解析失败" : safe(reason)));
    }

    private void registerInitializedSources(Object value) {
        if (!(value instanceof Scriptable)) throw new IllegalArgumentException("lx.inited 数据无效");
        Scriptable object = (Scriptable) value;
        Object status = get(object, "status");
        if (status != Scriptable.NOT_FOUND && !Context.toBoolean(status)) throw new IllegalStateException("脚本报告初始化失败");
        Object sources = get(object, "sources");
        if (!(sources instanceof Scriptable)) throw new IllegalArgumentException("lx.inited 未声明 sources");
        declaredSources.clear();
        for (String key : SUPPORTED_SOURCE_KEYS) {
            Object source = get((Scriptable) sources, key);
            if (!(source instanceof Scriptable)) continue;
            Scriptable sourceObject = (Scriptable) source;
            if (!"music".equals(Context.toString(get(sourceObject, "type")))) continue;
            if (supportsMusicUrl(sourceObject)) declaredSources.add(key);
        }
    }

    private boolean supportsMusicUrl(Scriptable source) {
        Object actions = get(source, "actions");
        if (actions instanceof NativeArray) {
            NativeArray array = (NativeArray) actions;
            for (Object item : array.toArray()) if ("musicUrl".equals(Context.toString(item))) return true;
        }
        return false;
    }

    private HttpResponse executeHttp(String address, Scriptable options) throws Exception {
        URL url = new URL(address);
        if (!"http".equalsIgnoreCase(url.getProtocol()) && !"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IllegalArgumentException("lx.request 仅允许 HTTP/HTTPS");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout((int) timeoutOption(options));
        connection.setReadTimeout((int) timeoutOption(options));
        String method = optionString(options, "method", "GET").toUpperCase(Locale.ROOT);
        connection.setRequestMethod(method);
        for (Map.Entry<String, String> header : optionMap(options, "headers").entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        String body = optionBody(options);
        if (body != null && !body.isEmpty() && !"GET".equals(method) && !"HEAD".equals(method)) {
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) { output.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int status = connection.getResponseCode();
        InputStream input = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String bodyText = input == null ? "" : new String(readLimited(input, MAX_HTTP_RESPONSE_BYTES), StandardCharsets.UTF_8);
        Map<String, List<String>> headers = connection.getHeaderFields();
        connection.disconnect();
        return new HttpResponse(status, bodyText, headers == null ? Collections.<String, List<String>>emptyMap() : headers);
    }

    private Scriptable createResponse(HttpResponse response) {
        NativeObject object = new NativeObject();
        put(object, "statusCode", response.status);
        put(object, "body", parseResponseBody(response.body));
        NativeObject headers = new NativeObject();
        for (Map.Entry<String, List<String>> entry : response.headers.entrySet()) {
            if (entry.getKey() == null) continue;
            List<String> values = entry.getValue() == null ? Collections.<String>emptyList() : entry.getValue();
            Object value = values.size() <= 1 ? (values.isEmpty() ? "" : values.get(0)) : new NativeArray(values.toArray());
            put(headers, entry.getKey().toLowerCase(Locale.ROOT), value);
        }
        put(object, "headers", headers);
        return object;
    }

    private Object parseResponseBody(String raw) {
        String text = raw == null ? "" : raw;
        String trimmed = text.trim();
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            try { return context.evaluateString(scope, "JSON.parse(" + Context.toString(text) + ")", "muonium-lx-json", 1, null); }
            catch (Throwable ignored) { }
        }
        return text;
    }

    private Scriptable toScriptObject(Map<String, Object> map) {
        NativeObject object = new NativeObject();
        if (map != null) for (Map.Entry<String, Object> entry : map.entrySet()) put(object, entry.getKey(), entry.getValue());
        return object;
    }

    private Object randomBytes(Context cx, Object[] args) {
        int size = args.length == 0 ? 0 : Math.max(0, Math.min(4096, (int) Context.toNumber(args[0])));
        byte[] bytes = new byte[size]; new SecureRandom().nextBytes(bytes);
        return new NativeArray(toUnsignedArray(bytes));
    }

    private Object bytesFrom(Context cx, Object[] args) {
        if (args.length == 0) return new NativeArray(0);
        byte[] bytes = bytesOf(args[0]);
        return new NativeArray(toUnsignedArray(bytes));
    }

    private Object bytesToString(Object[] args) {
        byte[] bytes = args.length == 0 ? new byte[0] : bytesOf(args[0]);
        String encoding = args.length > 1 ? safe(Context.toString(args[1])).toLowerCase(Locale.ROOT) : "utf8";
        if ("hex".equals(encoding)) return hex(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private Object aesEncrypt(Context cx, Object[] args) {
        try {
            byte[] data = args.length > 0 ? bytesOf(args[0]) : new byte[0];
            String mode = args.length > 1 ? safe(Context.toString(args[1])).toLowerCase(Locale.ROOT) : "aes-128-ecb";
            byte[] key = args.length > 2 ? bytesOf(args[2]) : new byte[0];
            byte[] iv = args.length > 3 ? bytesOf(args[3]) : new byte[0];
            String transformation = mode.contains("cbc") ? "AES/CBC/PKCS5Padding" : "AES/ECB/PKCS5Padding";
            Cipher cipher = Cipher.getInstance(transformation);
            SecretKeySpec secret = new SecretKeySpec(Arrays.copyOf(key, 16), "AES");
            if (transformation.contains("CBC")) cipher.init(Cipher.ENCRYPT_MODE, secret,
                    new javax.crypto.spec.IvParameterSpec(Arrays.copyOf(iv, 16)));
            else cipher.init(Cipher.ENCRYPT_MODE, secret);
            return new NativeArray(toUnsignedArray(cipher.doFinal(data)));
        } catch (Throwable throwable) {
            throw new IllegalStateException("aesEncrypt 失败：" + messageOf(throwable));
        }
    }

    private static String md5(String value) {
        try { return hex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Throwable throwable) { throw new IllegalStateException("MD5 不可用"); }
    }

    private static byte[] bytesOf(Object value) {
        if (value == null || value == Undefined.instance) return new byte[0];
        if (value instanceof byte[]) return (byte[]) value;
        if (value instanceof NativeArray) {
            Object[] values = ((NativeArray) value).toArray(); byte[] bytes = new byte[values.length];
            for (int i = 0; i < values.length; i++) bytes[i] = (byte) ((int) Context.toNumber(values[i]));
            return bytes;
        }
        return Context.toString(value).getBytes(StandardCharsets.UTF_8);
    }

    private static Object[] toUnsignedArray(byte[] bytes) {
        Object[] values = new Object[bytes.length];
        for (int i = 0; i < bytes.length; i++) values[i] = bytes[i] & 0xFF;
        return values;
    }

    private static long timeoutOption(Scriptable options) {
        try {
            Object timeout = get(options, "timeout");
            if (timeout != Scriptable.NOT_FOUND) return Math.max(1_000L, Math.min(HTTP_TIMEOUT_MS, (long) Context.toNumber(timeout)));
        } catch (Throwable ignored) { }
        return HTTP_TIMEOUT_MS;
    }

    private static String optionString(Scriptable options, String key, String fallback) {
        Object value = get(options, key);
        return value == Scriptable.NOT_FOUND || value == null ? fallback : Context.toString(value);
    }

    private static Map<String, String> optionMap(Scriptable options, String key) {
        Object object = get(options, key);
        if (!(object instanceof Scriptable)) return Collections.emptyMap();
        Map<String, String> result = new LinkedHashMap<>();
        for (Object id : ((Scriptable) object).getIds()) {
            String name = String.valueOf(id); Object value = get((Scriptable) object, name);
            if (value != Scriptable.NOT_FOUND && value != null) result.put(name, Context.toString(value));
        }
        return result;
    }

    private static String optionBody(Scriptable options) {
        Object body = get(options, "body");
        if (body != Scriptable.NOT_FOUND && body != null && body != Undefined.instance) return Context.toString(body);
        Object form = get(options, "form");
        if (form instanceof Scriptable) {
            StringBuilder encoded = new StringBuilder();
            for (Object id : ((Scriptable) form).getIds()) {
                if (encoded.length() > 0) encoded.append('&');
                try {
                    encoded.append(java.net.URLEncoder.encode(String.valueOf(id), "UTF-8")).append('=')
                            .append(java.net.URLEncoder.encode(Context.toString(get((Scriptable) form, String.valueOf(id))), "UTF-8"));
                } catch (Exception ignored) { }
            }
            return encoded.toString();
        }
        return null;
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (output.size() + read > maxBytes) throw new IllegalStateException("音源 HTTP 响应过大");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private void processMicrotasks() {
        try { context.processMicrotasks(); } catch (Throwable ignored) { }
    }

    private static Object get(Scriptable object, String key) {
        return object == null ? Scriptable.NOT_FOUND : ScriptableObject.getProperty(object, key);
    }

    /**
     * Rhino 1.7.x is the newest branch that still runs on Java 8, but it cannot parse optional
     * chaining. LX's bundled scripts use a small amount of it, so lower it before parsing while
     * preserving the short-circuit result. This intentionally handles access/call forms only;
     * unsupported future syntax is reported during initialization rather than executed loosely.
     */
    private static String prepareScript(String raw) {
        String source = raw == null ? "" : raw;
        int guard = 0;
        while (guard++ < 128) {
            int optional = source.indexOf("?.");
            if (optional < 0) break;
            int start = optionalBaseStart(source, optional - 1);
            if (start < 0 || start >= optional) break;
            int cursor = optional + 2;
            if (cursor >= source.length()) break;
            String base = source.substring(start, optional).trim();
            if (base.isEmpty()) break;
            String replacement;
            int end;
            char first = source.charAt(cursor);
            if (first == '[') {
                int closing = matching(source, cursor, '[', ']');
                if (closing < 0) break;
                replacement = "__muoniumOptionalIndex(" + base + "," + source.substring(cursor + 1, closing) + ")";
                end = closing + 1;
            } else if (Character.isJavaIdentifierStart(first) || first == '$') {
                int nameEnd = cursor + 1;
                while (nameEnd < source.length() && (Character.isJavaIdentifierPart(source.charAt(nameEnd)) || source.charAt(nameEnd) == '$')) nameEnd++;
                String key = source.substring(cursor, nameEnd);
                int callStart = skipSpaces(source, nameEnd);
                if (callStart < source.length() && source.charAt(callStart) == '(') {
                    int closing = matching(source, callStart, '(', ')');
                    if (closing < 0) break;
                    replacement = "__muoniumOptionalCall(" + base + ",\"" + key + "\",[" + source.substring(callStart + 1, closing) + "])";
                    end = closing + 1;
                } else {
                    replacement = "__muoniumOptionalGet(" + base + ",\"" + key + "\")";
                    end = nameEnd;
                }
            } else break;
            source = source.substring(0, start) + replacement + source.substring(end);
        }
        return source;
    }

    private static int optionalBaseStart(String source, int cursor) {
        int parentheses = 0, brackets = 0;
        while (cursor >= 0) {
            char c = source.charAt(cursor);
            if (c == ')') parentheses++;
            else if (c == '(') { if (parentheses-- == 0) break; }
            else if (c == ']') brackets++;
            else if (c == '[') { if (brackets-- == 0) break; }
            if (parentheses == 0 && brackets == 0 && (c == ';' || c == '=' || c == ',' || c == ':' || c == '{' || c == '}' || c == '\n' || c == '\r')) return cursor + 1;
            cursor--;
        }
        return Math.max(0, cursor + 1);
    }

    private static int matching(String source, int start, char open, char close) {
        int depth = 0;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == open) depth++;
            else if (c == close && --depth == 0) return i;
        }
        return -1;
    }

    private static int skipSpaces(String source, int index) {
        while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
        return index;
    }
    private static void put(Scriptable object, String key, Object value) { ScriptableObject.putProperty(object, key, value); }
    private static String safe(String value) { return value == null ? "" : value.trim(); }
    private static String safeName(String value) {
        String safe = safe(value).replaceAll("[\\r\\n]", " ");
        return safe.isEmpty() ? "muonium-lx-source" : safe;
    }
    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes == null ? 0 : bytes.length * 2);
        if (bytes != null) for (byte value : bytes) output.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
        return output.toString();
    }
    private static String messageOf(Throwable throwable) { String value = throwable == null ? "" : throwable.getMessage(); return safe(value).isEmpty() ? "脚本运行失败" : safe(value); }
    private static Exception unwrap(ExecutionException failure) throws Exception { Throwable cause = failure.getCause(); if (cause instanceof Exception) return (Exception) cause; return new IllegalStateException(messageOf(cause), cause); }

    private static ThreadFactory daemonFactory(final String name) {
        return new ThreadFactory() { @Override public Thread newThread(Runnable task) { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; } };
    }

    private interface NativeCall { Object apply(Context cx, Object[] args); }
    private BaseFunction nativeFunction(final NativeCall call) {
        BaseFunction function = new BaseFunction() {
            @Override public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) { return call.apply(cx, args == null ? new Object[0] : args); }
        };
        function.setParentScope(scope); return function;
    }

    private static final class HttpResponse {
        private final int status; private final String body; private final Map<String, List<String>> headers;
        private HttpResponse(int status, String body, Map<String, List<String>> headers) { this.status = status; this.body = body; this.headers = headers; }
    }
}