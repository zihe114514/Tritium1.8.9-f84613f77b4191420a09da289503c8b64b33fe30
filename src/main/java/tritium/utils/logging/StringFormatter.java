package tritium.utils.logging;

import java.util.ArrayList;
import java.util.List;

public class StringFormatter {
    public static String format(String format, Object... args) {
        if (format == null) {
            return null;
        }
        
        if (args == null || args.length == 0) {
            return format;
        }
        
        StringBuilder newFmt = new StringBuilder();
        List<Object> newArgs = new ArrayList<>();
        
        int argIndex = 0;
        int i = 0;
        int len = format.length();
        
        while (i < len) {
            char c = format.charAt(i);
            
            if (c == '{' && i + 1 < len && format.charAt(i + 1) == '}') {
                if (argIndex < args.length) {
                    Object arg = args[argIndex];
                    String argStr = arg == null ? "null" : arg.toString();
                    newFmt.append(argStr);
                    argIndex++;
                }
                i += 2;
            }
            else if (c == '%') {
                if (i + 1 < len && format.charAt(i + 1) == '%') {
                    newFmt.append("%%");
                    i += 2;
                } else {
                    int start = i;
                    i++;
                    
                    while (i < len && "-+ 0#".indexOf(format.charAt(i)) >= 0) {
                        i++;
                    }
                    
                    while (i < len && Character.isDigit(format.charAt(i))) {
                        i++;
                    }
                    
                    if (i < len && format.charAt(i) == '.') {
                        i++;
                        while (i < len && Character.isDigit(format.charAt(i))) {
                            i++;
                        }
                    }
                    
                    if (i < len && "hlL".indexOf(format.charAt(i)) >= 0) {
                        i++;
                    }
                    
                    if (i < len && "bBhHsScCdoxXeEfgGaAtT".indexOf(format.charAt(i)) >= 0) {
                        i++;
                        String formatSpec = format.substring(start, i);
                        newFmt.append(formatSpec);
                        
                        if (argIndex < args.length) {
                            newArgs.add(args[argIndex]);
                            argIndex++;
                        }
                    } else {
                        newFmt.append(format, start, i);
                    }
                }
            }
            else {
                newFmt.append(c);
                i++;
            }
        }
        
        try {
            return String.format(newFmt.toString(), newArgs.toArray());
        } catch (Exception e) {
            return format;
        }
    }

}