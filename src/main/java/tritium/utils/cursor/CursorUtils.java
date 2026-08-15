package tritium.utils.cursor;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import org.lwjgl.opengl.Display;
import tritium.interfaces.User32Interface;

import java.lang.reflect.Field;

/**
 * @author IzumiiKonata
 * Date: 2025/6/8 21:39
 */
@UtilityClass
public class CursorUtils {

    private boolean SUPPORTED = true;

    public static final long ARROW = WinUser.IDC_ARROW;
    public static final long HAND = WinUser.IDC_HAND;
    public static final long TEXT = WinUser.IDC_IBEAM;
    public static final long NOT_ALLOWED = WinUser.IDC_NO;
    public static final long RESIZE_EW = WinUser.IDC_SIZEWE;
    public static final long RESIZE_NS = WinUser.IDC_SIZENS;
    public static final long RESIZE_NWSE = WinUser.IDC_SIZENWSE;
    public static final long RESIZE_NESW = WinUser.IDC_SIZENESW;

    private static long curCursor = ARROW;
    private static long overrideCursor = ARROW;

    public void resetOverride() {
        overrideCursor = ARROW;
    }

    public void setOverride(long cursor) {
        overrideCursor = cursor;
    }

    public void setOverride() {
        setCursor(overrideCursor);
    }

    public void setCursor(long cursor) {

        if (curCursor != cursor) {
            curCursor = cursor;

            if (!SUPPORTED)
                return;

            try {
                WinDef.HCURSOR hCursor = User32Interface.INSTANCE.LoadCursorW(null, (int) cursor);

                if (hCursor != null) {
                    User32Interface.INSTANCE.SetClassLongPtrW(new WinDef.HWND(new Pointer(getHwnd())), User32Interface.INSTANCE.GCLP_HCURSOR, hCursor);

                    User32Interface.INSTANCE.SetCursor(hCursor);
                }
            } catch (Throwable t) {
                SUPPORTED = false;
            }
        }

    }

    private long hwnd = -1;

    @SneakyThrows
    public static long getHwnd() {

        if (hwnd == -1) {
            Class<?> WindowsDisplay = Class.forName("org.lwjgl.opengl.WindowsDisplay");

            Field displayImpl = Display.class.getDeclaredField("display_impl");
            displayImpl.setAccessible(true);
            Object windowsDisplayInstance = displayImpl.get(null);

            Field fHwnd = WindowsDisplay.getDeclaredField("hwnd");
            fHwnd.setAccessible(true);
            hwnd = (long) fHwnd.get(windowsDisplayInstance);
        }

        return hwnd;
    }

}
