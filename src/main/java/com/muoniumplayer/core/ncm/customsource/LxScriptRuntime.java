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
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
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

// LX Desktop sources may be bundled and may complete asynchronous self-checks before inited.
    // All work still happens off Minecraft's render thread, so this improves compatibility without
    // blocking the Forge loading screen.
    private static final long INITIALIZE_TIMEOUT_MS = 35_000L;
    private static final long RESOLVE_TIMEOUT_MS = 20_000L;
    private static final long HTTP_TIMEOUT_MS = 20_000L;
    private static final int MAX_HTTP_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Set<String> SUPPORTED_SOURCE_KEYS = new LinkedHashSet<>(Arrays.asList("wy", "tx", "kw", "kg", "mg"));
    private static final ExecutorService HTTP = Executors.newCachedThreadPool(daemonFactory("LX Source HTTP"));

    private final CustomSourceInfo info;
    private final String script;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(daemonFactory("LX Source Runtime"));
    private final ContextFactory contextFactory = new ContextFactory();
    private final Set<String> declaredSources = new LinkedHashSet<>();
    private final Map<String, CompletableFuture<String>> pendingResolutions = new LinkedHashMap<>();
    private final CompletableFuture<List<String>> initialized = new CompletableFuture<>();

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
        Future<?> task = executor.submit(() -> { initializeOnRuntimeThread(); return null; });
        try {
            // Phase 1: parse the script and register lx.on(request). This must finish on the
            // runtime thread, but lx.inited may arrive asynchronously from an HTTP callback (some
            // sources fetch their supported-platform list over the network before announcing it).
            task.get(INITIALIZE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            // Phase 2: wait for the inited event. Synchronous sources complete this during phase 1;
            // network-backed sources complete it from their request callback.
            return initialized.get(INITIALIZE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
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

    private void initializeOnRuntimeThread() throws Exception {
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
                        + "if (typeof console === 'undefined') console={};"
                        + "(function(c){var n=function(){};['log','info','warn','error','debug','group','groupEnd','groupCollapsed','trace','time','timeEnd','table','dir','assert','count','countReset','clear'].forEach(function(k){if(typeof c[k]!=='function')c[k]=n;});})(console);"
                        + "if (!Object.assign) Object.assign=function(t){for(var i=1;i<arguments.length;i++){var s=arguments[i];if(s)for(var k in s)if(Object.prototype.hasOwnProperty.call(s,k))t[k]=s[k];}return t;};"
                        + "if (!Object.keys) Object.keys=function(o){var r=[];for(var k in o)if(Object.prototype.hasOwnProperty.call(o,k))r.push(k);return r;};"
                        + "if (!Array.isArray) Array.isArray=function(a){return Object.prototype.toString.call(a)==='[object Array]';};"
                        + "if (!Array.from) Array.from=function(a,f){var r=[];if(a&&a.length)for(var i=0;i<a.length;i++){var v=a[i];r.push(f?f(v,i):v);}return r;};"
                        + "if (!Array.prototype.find) Array.prototype.find=function(f){for(var i=0;i<this.length;i++)if(f(this[i],i,this))return this[i];};"
                        + "if (!Array.prototype.findIndex) Array.prototype.findIndex=function(f){for(var i=0;i<this.length;i++)if(f(this[i],i,this))return i;return -1;};"
                        + "if (!Array.prototype.includes) Array.prototype.includes=function(v){return this.indexOf(v)>=0;};"
                        + "if (!String.prototype.includes) String.prototype.includes=function(v){return this.indexOf(v)>=0;};"
                        + "if (!String.prototype.startsWith) String.prototype.startsWith=function(v){return this.indexOf(v)===0;};"
                        + "if (!String.prototype.endsWith) String.prototype.endsWith=function(v){return this.length>=v.length&&this.lastIndexOf(v)===this.length-v.length;};"
                        + "if (!String.prototype.repeat) String.prototype.repeat=function(n){var r='';for(var i=0;i<n;i++)r+=this;return r;};"
                        + "if (!String.prototype.trim) String.prototype.trim=function(){return this.replace(/^\\s+|\\s+$/g,'');};"
                        + "if (!Number.isFinite) Number.isFinite=function(v){return typeof v==='number'&&isFinite(v);};"
                        + "if (!Number.isNaN) Number.isNaN=function(v){return v!==v;};"
                        + "if (!Number.isInteger) Number.isInteger=function(v){return typeof v==='number'&&isFinite(v)&&Math.floor(v)===v;};",
                "muonium-lx-prelude", 1, null);
        String compatibleScript = LxScriptTranspiler.transpile(script, safeName(info.name));
        context.evaluateString(scope, "(function(){\n" + prepareScript(compatibleScript) + "\n}).call(this);", safeName(info.name), 1, null);
        processMicrotasks();
        if (requestHandler == null) {
            throw new IllegalStateException("脚本未注册 lx.on(request) 处理器");
        }
        // A source may send lx.inited later from an asynchronous request callback. Only settle the
        // synchronous case here; the async case is completed by registerInitializedSources().
        if (!declaredSources.isEmpty() && !initialized.isDone()) {
            ready = true;
            initialized.complete(new ArrayList<>(declaredSources));
        }
    }

    private void installLxApi() {
        NativeObject lx = new NativeObject();
        lx.setParentScope(scope);
        NativeObject eventNames = new NativeObject();
        put(eventNames, "request", "request");
        put(eventNames, "inited", "inited");
        put(eventNames, "updateAlert", "updateAlert");
        put(lx, "EVENT_NAMES", eventNames);
// Match the official desktop contract. Sources often select their desktop implementation
        // from this value before registering the inited event.
        put(lx, "version", "2.0.0-muonium");
        put(lx, "env", "desktop");
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

/**
     * Official LX Desktop compatible helpers. Values are represented as unsigned JS byte arrays,
     * which keeps Java byte buffers hidden from imported scripts while remaining compatible with
     * Buffer.from / bufToString source code patterns.
     */
    private Scriptable createUtils() {
        NativeObject utils = new NativeObject();
        NativeObject crypto = new NativeObject();
        put(crypto, "md5", nativeFunction((cx, args) -> md5(args.length == 0 ? "" : Context.toString(args[0]))));
        put(crypto, "randomBytes", nativeFunction((cx, args) -> randomBytes(cx, args)));
        put(crypto, "aesEncrypt", nativeFunction((cx, args) -> aesEncrypt(cx, args)));
        put(crypto, "rsaEncrypt", nativeFunction((cx, args) -> rsaEncrypt(cx, args)));

        NativeObject buffer = new NativeObject();
        put(buffer, "from", nativeFunction((cx, args) -> bytesFrom(cx, args)));
        put(buffer, "bufToString", nativeFunction((cx, args) -> bytesToString(args)));

        NativeObject zlib = new NativeObject();
        put(zlib, "inflate", nativeFunction((cx, args) -> zlib(cx, args, true)));
        put(zlib, "deflate", nativeFunction((cx, args) -> zlib(cx, args, false)));

        put(utils, "crypto", crypto);
        put(utils, "buffer", buffer);
        put(utils, "zlib", zlib);
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
            // LX Desktop documents the callback as (err, resp, body). Keep resp.body for existing
            // scripts and provide the third body argument for scripts that follow the full contract.
            Object body = failure == null ? parseResponseBody(response.body) : null;
            callback.call(context, scope, scope, new Object[]{error, result, body});
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
        try {
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
            if (declaredSources.isEmpty()) throw new IllegalArgumentException("lx.inited 未声明有效来源");
            ready = true;
            if (!initialized.isDone()) initialized.complete(new ArrayList<>(declaredSources));
        } catch (RuntimeException error) {
            if (!initialized.isDone()) initialized.completeExceptionally(error);
            throw error;
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
        String explicitContentType = null;
        for (Map.Entry<String, String> header : optionMap(options, "headers").entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
            if ("content-type".equalsIgnoreCase(header.getKey())) explicitContentType = header.getValue();
        }
        byte[] bodyBytes = optionBodyBytes(options);
        if (bodyBytes != null && bodyBytes.length > 0 && !"GET".equals(method) && !"HEAD".equals(method)) {
            connection.setDoOutput(true);
            if (hasFormObject(options) && explicitContentType == null) connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            try (OutputStream output = connection.getOutputStream()) { output.write(bodyBytes); }
        }
        int status = connection.getResponseCode();
        InputStream input = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream();
        String bodyText = input == null ? "" : new String(readLimited(input, MAX_HTTP_RESPONSE_BYTES), StandardCharsets.UTF_8);
        Map<String, List<String>> headers = connection.getHeaderFields();
        String statusMessage = connection.getResponseMessage();
        connection.disconnect();
        return new HttpResponse(status, statusMessage, bodyText, headers == null ? Collections.<String, List<String>>emptyMap() : headers);
    }

    private Scriptable createResponse(HttpResponse response) {
        NativeObject object = new NativeObject();
        put(object, "statusCode", response.status);
        put(object, "statusMessage", safe(response.statusMessage));
        put(object, "body", parseResponseBody(response.body));
        NativeObject headers = new NativeObject();
        for (Map.Entry<String, List<String>> entry : response.headers.entrySet()) {
            if (entry.getKey() == null) continue;
            List<String> values = entry.getValue() == null ? Collections.<String>emptyList() : entry.getValue();
            Object value = values.size() <= 1 ? (values.isEmpty() ? "" : values.get(0)) : new NativeArray(values.toArray());
            put(headers, entry.getKey(), value);
            String lower = entry.getKey().toLowerCase(Locale.ROOT);
            if (!lower.equals(entry.getKey())) put(headers, lower, value);
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
        byte[] bytes = new byte[size];
        new SecureRandom().nextBytes(bytes);
        return new NativeArray(toUnsignedArray(bytes));
    }

    private Object bytesFrom(Context cx, Object[] args) {
        if (args.length == 0) return new NativeArray(0);
        String encoding = args.length > 1 ? safe(Context.toString(args[1])).toLowerCase(Locale.ROOT) : "utf8";
        byte[] bytes = bytesOf(args[0], encoding);
        return new NativeArray(toUnsignedArray(bytes));
    }

    private Object bytesToString(Object[] args) {
        byte[] bytes = args.length == 0 ? new byte[0] : bytesOf(args[0]);
        String encoding = args.length > 1 ? safe(Context.toString(args[1])).toLowerCase(Locale.ROOT) : "utf8";
        if ("hex".equals(encoding)) return hex(bytes);
        if ("base64".equals(encoding)) return Base64.getEncoder().encodeToString(bytes);
        if ("base64url".equals(encoding)) return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private Object aesEncrypt(Context cx, Object[] args) {
        try {
            byte[] data = args.length > 0 ? bytesOf(args[0]) : new byte[0];
            String mode = args.length > 1 ? safe(Context.toString(args[1])).toLowerCase(Locale.ROOT) : "aes-128-ecb";
            byte[] key = args.length > 2 ? bytesOf(args[2]) : new byte[0];
            byte[] iv = args.length > 3 ? bytesOf(args[3]) : new byte[0];
            int keySize = mode.contains("256") ? 32 : mode.contains("192") ? 24 : 16;
            String transformation = mode.contains("cbc") ? "AES/CBC/PKCS5Padding" : "AES/ECB/PKCS5Padding";
            Cipher cipher = Cipher.getInstance(transformation);
            SecretKeySpec secret = new SecretKeySpec(Arrays.copyOf(key, keySize), "AES");
            if (transformation.contains("CBC")) {
                cipher.init(Cipher.ENCRYPT_MODE, secret, new IvParameterSpec(Arrays.copyOf(iv, 16)));
            } else {
                cipher.init(Cipher.ENCRYPT_MODE, secret);
            }
            return new NativeArray(toUnsignedArray(cipher.doFinal(data)));
        } catch (Throwable throwable) {
            throw new IllegalStateException("aesEncrypt 失败：" + messageOf(throwable));
        }
    }

    private Object rsaEncrypt(Context cx, Object[] args) {
        try {
            byte[] data = args.length > 0 ? bytesOf(args[0]) : new byte[0];
            String keyText = args.length > 1 ? safe(Context.toString(args[1])) : "";
            if (keyText.isEmpty()) throw new IllegalArgumentException("RSA 公钥为空");
            String normalized = keyText.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s+", "");
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(normalized)));
            int maxInput = Math.max(1, publicKey.getModulus().bitLength() / 8 - 11);
            if (data.length > maxInput) throw new IllegalArgumentException("RSA 输入超过单块长度限制");
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            return new NativeArray(toUnsignedArray(cipher.doFinal(data)));
        } catch (Throwable throwable) {
            throw new IllegalStateException("rsaEncrypt 失败：" + messageOf(throwable));
        }
    }

    private Object zlib(Context cx, Object[] args, final boolean inflate) {
        final byte[] input = args.length == 0 ? new byte[0] : bytesOf(args[0]);
        final Function promiseFactory = (Function) context.evaluateString(scope,
                "(function(executor) { return new Promise(executor); })", "muonium-lx-zlib-promise", 1, null);
        Function starter = nativeFunction((ignored, promiseArgs) -> {
            if (promiseArgs.length < 2 || !(promiseArgs[0] instanceof Function) || !(promiseArgs[1] instanceof Function)) {
                throw new IllegalArgumentException("Promise 初始化失败");
            }
            final Function resolve = (Function) promiseArgs[0];
            final Function reject = (Function) promiseArgs[1];
            HTTP.submit(() -> {
                try {
                    final byte[] output = inflate ? inflate(input) : deflate(input);
                    if (!closed) executor.submit(() -> {
                        if (!closed && context != null && scope != null) {
                            resolve.call(context, scope, scope, new Object[]{new NativeArray(toUnsignedArray(output))});
                            processMicrotasks();
                        }
                    });
                } catch (Throwable throwable) {
                    if (!closed) executor.submit(() -> {
                        if (!closed && context != null && scope != null) {
                            reject.call(context, scope, scope, new Object[]{Context.toObject(new RuntimeException(messageOf(throwable)), scope)});
                            processMicrotasks();
                        }
                    });
                }
            });
            return Undefined.instance;
        });
        return promiseFactory.call(context, scope, scope, new Object[]{starter});
    }

    private static byte[] inflate(byte[] input) throws Exception {
        return readLimited(new InflaterInputStream(new ByteArrayInputStream(input)), MAX_HTTP_RESPONSE_BYTES);
    }

    private static byte[] deflate(byte[] input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DeflaterOutputStream stream = new DeflaterOutputStream(output)) {
            stream.write(input == null ? new byte[0] : input);
        }
        return output.toByteArray();
    }

    private static String md5(String value) {
        try { return hex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Throwable throwable) { throw new IllegalStateException("MD5 不可用"); }
    }

private static byte[] bytesOf(Object value) {
        return bytesOf(value, "utf8");
    }

    private static byte[] bytesOf(Object value, String encoding) {
        if (value == null || value == Undefined.instance) return new byte[0];
        if (value instanceof byte[]) return (byte[]) value;
        if (value instanceof NativeArray) {
            Object[] values = ((NativeArray) value).toArray();
            byte[] bytes = new byte[values.length];
            for (int i = 0; i < values.length; i++) bytes[i] = (byte) ((int) Context.toNumber(values[i]));
            return bytes;
        }
        String text = Context.toString(value);
        try {
            if ("hex".equalsIgnoreCase(encoding)) {
                String normalized = text.replaceAll("\\s+", "");
                if ((normalized.length() & 1) != 0) throw new IllegalArgumentException("HEX 长度必须为偶数");
                byte[] bytes = new byte[normalized.length() / 2];
                for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(normalized.substring(i * 2, i * 2 + 2), 16);
                return bytes;
            }
            if ("base64".equalsIgnoreCase(encoding)) return Base64.getDecoder().decode(text);
            if ("base64url".equalsIgnoreCase(encoding)) return Base64.getUrlDecoder().decode(text);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Buffer.from 编码数据无效：" + error.getMessage());
        }
        return text.getBytes(StandardCharsets.UTF_8);
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

    private static byte[] optionBodyBytes(Scriptable options) {
        Object body = get(options, "body");
        if (body != Scriptable.NOT_FOUND && body != null && body != Undefined.instance) {
            return body instanceof NativeArray ? bytesOf(body) : Context.toString(body).getBytes(StandardCharsets.UTF_8);
        }
        String form = formBody(options);
        return form == null ? null : form.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean hasFormObject(Scriptable options) {
        return get(options, "form") instanceof Scriptable;
    }

    private static String formBody(Scriptable options) {
        Object form = get(options, "form");
        if (!(form instanceof Scriptable)) return null;
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
        private final int status; private final String statusMessage; private final String body; private final Map<String, List<String>> headers;
        private HttpResponse(int status, String statusMessage, String body, Map<String, List<String>> headers) { this.status = status; this.statusMessage = statusMessage; this.body = body; this.headers = headers; }
    }
}
