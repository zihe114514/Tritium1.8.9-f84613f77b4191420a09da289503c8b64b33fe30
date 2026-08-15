package lain.mods.inputfix.utils;

/**
 * 原样移植：依据 os.name 判定当前操作系统。
 */
public class OSDetector {

    String osName;

    public static OS detectOS() {
        OSDetector detector = new OSDetector();
        if (detector.isWindows()) {
            return OS.Windows;
        }
        if (detector.isLinux()) {
            return OS.Linux;
        }
        if (detector.isMac()) {
            return OS.Mac;
        }
        return OS.Unknown;
    }

    private OSDetector() {
        this.osName = System.getProperty("os.name");
    }

    boolean isLinux() {
        return osName.startsWith("Linux") || osName.startsWith("FreeBSD")
                || osName.startsWith("SunOS") || osName.startsWith("Unix");
    }

    boolean isMac() {
        return osName.startsWith("Mac OS X") || osName.startsWith("Darwin");
    }

    boolean isWindows() {
        return osName.startsWith("Windows");
    }

    public enum OS {
        Windows, Linux, Mac, Unknown
    }
}
