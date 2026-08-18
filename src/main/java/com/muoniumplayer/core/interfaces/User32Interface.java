package com.muoniumplayer.core.interfaces;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;

/**
 * @author IzumiiKonata
 * Date: 2025/6/8 21:48
 *
 * 原样移植（原文件位于 _removed_e/main/java/tritium/interfaces/User32Interface.java）。
 * 供 CursorUtils 通过 JNA 调用 user32 切换窗口光标。
 */
public interface User32Interface extends StdCallLibrary {
    User32Interface INSTANCE = Native.load("user32", User32Interface.class);

    int GCLP_HCURSOR = -12;

    WinDef.HCURSOR LoadCursorW(WinDef.HINSTANCE instance, int cursorId);

    Pointer SetClassLongPtrW(WinDef.HWND hWnd, int nIndex, WinNT.HANDLE dwNewLong);

    WinDef.HCURSOR SetCursor(WinDef.HCURSOR cursor);
}
