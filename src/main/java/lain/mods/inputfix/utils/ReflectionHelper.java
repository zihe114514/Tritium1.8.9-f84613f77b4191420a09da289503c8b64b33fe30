package lain.mods.inputfix.utils;

import java.lang.reflect.Method;

/**
 * 原样移植：按候选名（SRG + MCP）反射查找方法，兼容混淆/开发两套运行时命名。
 */
public class ReflectionHelper {

    public static Method findMethod(Class<?> clazz, String[] names, Class<?>[] params) {
        Exception lastException = null;
        for (String name : names) {
            try {
                Method method = clazz.getDeclaredMethod(name, params);
                method.setAccessible(true);
                return method;
            } catch (Exception e) {
                lastException = e;
            }
        }
        throw new RuntimeException(lastException);
    }
}
