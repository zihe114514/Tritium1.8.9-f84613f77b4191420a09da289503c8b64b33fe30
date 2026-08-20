package com.muoniumplayer.core.ncm.customsource;

import com.google.javascript.jscomp.CompilationLevel;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.JSError;
import com.google.javascript.jscomp.Result;
import com.google.javascript.jscomp.SourceFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Down-levels user supplied LX scripts before Rhino evaluates them. Rhino 1.7.15 runs on Java 8
 * but current LX scripts commonly use syntax it cannot parse directly. BigInt literals are kept:
 * Rhino supports them, whereas this Closure Compiler generation cannot transpile BigInt to ES5.
 */
final class LxScriptTranspiler {

    private static final Pattern BIG_INT_LITERAL = Pattern.compile("(?<![A-Za-z0-9_$])(?:0[xX][0-9a-fA-F]+|[0-9]+)n(?![A-Za-z0-9_$])");

    private LxScriptTranspiler() {
    }

    static String transpile(String source, String sourceName) {
        if (source == null || source.trim().isEmpty()) {
            throw new IllegalArgumentException("音源脚本为空");
        }
        try {
            ProtectedScript protectedScript = protectBigInts(source);
            Compiler compiler = new Compiler();
            CompilerOptions options = new CompilerOptions();
            options.setLanguageIn(CompilerOptions.LanguageMode.ECMASCRIPT_NEXT);
            options.setLanguageOut(CompilerOptions.LanguageMode.ECMASCRIPT5);
            CompilationLevel.SIMPLE_OPTIMIZATIONS.setOptionsForCompilationLevel(options);
            options.setRemoveDeadCode(false);
            options.setEmitUseStrict(false);
            compiler.disableThreads();

            Result result = compiler.compile(
                    Collections.<SourceFile>emptyList(),
                    Collections.singletonList(SourceFile.fromCode(safeName(sourceName), protectedScript.code)),
                    options);
            if (!result.success) {
                throw new IllegalArgumentException("现代 JavaScript 转换失败：" + errors(result));
            }
            String output = protectedScript.restore(compiler.toSource());
            if (output == null || output.trim().isEmpty()) {
                throw new IllegalArgumentException("现代 JavaScript 转换后为空");
            }
            return output;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Throwable throwable) {
            throw new IllegalArgumentException("JavaScript 兼容转换失败：" + messageOf(throwable), throwable);
        }
    }

    private static ProtectedScript protectBigInts(String source) {
        Matcher matcher = BIG_INT_LITERAL.matcher(source);
        StringBuffer transformed = new StringBuffer();
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            // Use a bracket string key with characters that cannot become a dot access after
            // SIMPLE optimizations, so the BigInt placeholder survives Closure variable renaming.
            String marker = "globalThis[\"@muonium_lx_bigint_" + values.size() + "@\"]";
            values.add(matcher.group());
            matcher.appendReplacement(transformed, marker);
        }
        matcher.appendTail(transformed);
        return new ProtectedScript(transformed.toString(), values);
    }

    private static String errors(Result result) {
        StringBuilder text = new StringBuilder();
        if (result != null && result.errors != null) {
            for (JSError error : result.errors) {
                if (error == null) continue;
                if (text.length() > 0) text.append("；");
                text.append(error.getDescription() == null ? "未知语法错误" : error.getDescription());
                if (text.length() > 360) break;
            }
        }
        return text.length() == 0 ? "未知语法错误" : text.toString();
    }

    private static String safeName(String name) {
        String value = name == null ? "lx-source.js" : name.trim();
        return value.isEmpty() ? "lx-source.js" : value.replaceAll("[\r\n]", " ");
    }

    private static String messageOf(Throwable throwable) {
        String message = throwable == null ? "" : throwable.getMessage();
        return message == null || message.trim().isEmpty() ? "未知错误" : message.trim();
    }

    private static final class ProtectedScript {
        private final String code;
        private final List<String> values;

        private ProtectedScript(String code, List<String> values) {
            this.code = code;
            this.values = values;
        }

        private String restore(String output) {
            String restored = output == null ? "" : output;
            for (int i = 0; i < values.size(); i++) {
                restored = restored.replace("globalThis[\"@muonium_lx_bigint_" + i + "@\"]", values.get(i));
            }
            return restored;
        }
    }
}
