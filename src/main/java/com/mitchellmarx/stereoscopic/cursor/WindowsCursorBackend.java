package com.mitchellmarx.stereoscopic.cursor;

import com.mitchellmarx.stereoscopic.Stereoscopic;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.windows.GDI32;
import org.lwjgl.system.windows.POINT;
import org.lwjgl.system.windows.RECT;
import org.lwjgl.system.windows.User32;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Windows {@link CursorBackend} — captures IDC_ARROW into a sprite and clips
 * the OS cursor to MC's client rect.
 *
 * <p>LWJGL 3.3 covers some User32 functions with typed bindings but not the
 * cursor/icon/bitmap APIs we need; resolve those via
 * {@link SharedLibrary#getFunctionAddress(String)} and invoke through
 * {@link JNI}'s {@code invokeXX} family. Structs (ICONINFO, BITMAP) are
 * hand-laid into direct ByteBuffers.
 *
 * <p>Pixel extraction uses {@code SelectObject} + {@code GetPixel} (per
 * Angelica commit {@code 4966a663}); {@code GetDIBits}'s
 * {@code (P,P,I,I,P,P,I)→I} signature has no matching JNI helper overload
 * and the per-pixel loop at 32×32 is ~1ms one-shot at startup.
 *
 * <p>Uses {@code LoadCursorW(NULL, IDC_ARROW)}, NOT {@code GetCursor()} —
 * the latter can transiently return a wait spinner if a background process
 * briefly takes cursor control during capture (verified empirically in
 * Angelica on 2026-05-12).
 */
public final class WindowsCursorBackend implements CursorBackend {

    private static final boolean IS_WINDOWS =
        System.getProperty("os.name", "").toLowerCase().contains("windows");

    // MAKEINTRESOURCE-style integer-as-pointer for LoadCursorW.
    private static final long IDC_ARROW = 32512L;

    private static final long FN_LoadCursorW;
    private static final long FN_GetIconInfo;
    private static final long FN_GetClientRect;
    private static final long FN_CreateCompatibleDC;
    private static final long FN_DeleteDC;
    private static final long FN_DeleteObject;
    private static final long FN_GetObjectW;
    private static final long FN_SelectObject;
    private static final long FN_GetPixel;

    private static final boolean NATIVES_OK;

    static {
        long ldCur = 0L, gIcon = 0L, gClient = 0L;
        long cDC = 0L, delDC = 0L, delObj = 0L, gObj = 0L, selObj = 0L, gPix = 0L;
        boolean ok = false;
        if (IS_WINDOWS) {
            try {
                SharedLibrary user32 = User32.getLibrary();
                SharedLibrary gdi32  = GDI32.getLibrary();
                ldCur   = user32.getFunctionAddress("LoadCursorW");
                gIcon   = user32.getFunctionAddress("GetIconInfo");
                gClient = user32.getFunctionAddress("GetClientRect");
                cDC     = gdi32.getFunctionAddress("CreateCompatibleDC");
                delDC   = gdi32.getFunctionAddress("DeleteDC");
                delObj  = gdi32.getFunctionAddress("DeleteObject");
                gObj    = gdi32.getFunctionAddress("GetObjectW");
                selObj  = gdi32.getFunctionAddress("SelectObject");
                gPix    = gdi32.getFunctionAddress("GetPixel");
                ok = ldCur != 0 && gIcon != 0 && gClient != 0
                  && cDC != 0 && delDC != 0 && delObj != 0
                  && gObj != 0 && selObj != 0 && gPix != 0;
                if (!ok) {
                    Stereoscopic.LOG.warn(
                        "WindowsCursorBackend: failed to resolve some Win32 functions"
                        + " — LoadCursorW={}, GetIconInfo={}, GetClientRect={},"
                        + " CreateCompatibleDC={}, DeleteDC={}, DeleteObject={},"
                        + " GetObjectW={}, SelectObject={}, GetPixel={}",
                        ldCur, gIcon, gClient, cDC, delDC, delObj, gObj, selObj, gPix);
                }
            } catch (Throwable t) {
                Stereoscopic.LOG.warn("WindowsCursorBackend: native resolution failed", t);
            }
        }
        FN_LoadCursorW        = ldCur;
        FN_GetIconInfo        = gIcon;
        FN_GetClientRect      = gClient;
        FN_CreateCompatibleDC = cDC;
        FN_DeleteDC           = delDC;
        FN_DeleteObject       = delObj;
        FN_GetObjectW         = gObj;
        FN_SelectObject       = selObj;
        FN_GetPixel           = gPix;
        NATIVES_OK = ok;
    }

    private final long hwnd;
    private boolean trapped;

    public WindowsCursorBackend(long hwnd) { this.hwnd = hwnd; }

    @Override public boolean isSupported() {
        return IS_WINDOWS && NATIVES_OK && hwnd != 0L;
    }

    @Override public Sprite captureArrowBitmap() {
        if (!isSupported()) return null;
        try {
            long hCursor = JNI.invokePPP(0L, IDC_ARROW, FN_LoadCursorW);
            if (hCursor == 0L) {
                Stereoscopic.LOG.warn("LoadCursorW(IDC_ARROW) returned NULL");
                return null;
            }
            // ICONINFO on x64 (32 bytes):
            //    0: BOOL  fIcon       (4)
            //    4: DWORD xHotspot    (4)
            //    8: DWORD yHotspot    (4)
            //   12: pad for HBITMAP alignment (4)
            //   16: HBITMAP hbmMask   (8)
            //   24: HBITMAP hbmColor  (8)
            ByteBuffer iconBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
            long iconAddr = MemoryUtil.memAddress(iconBuf);
            int gii = JNI.invokePPI(hCursor, iconAddr, FN_GetIconInfo);
            if (gii == 0) {
                Stereoscopic.LOG.warn("GetIconInfo failed");
                return null;
            }
            int xHot = iconBuf.getInt(4);
            int yHot = iconBuf.getInt(8);
            long hbmMask  = iconBuf.getLong(16);
            long hbmColor = iconBuf.getLong(24);
            try {
                return captureSprite(hbmColor, hbmMask, xHot, yHot);
            } finally {
                // GetIconInfo docs: caller owns hbmColor + hbmMask; must DeleteObject them.
                if (hbmColor != 0L) try { JNI.invokePI(hbmColor, FN_DeleteObject); }
                    catch (Throwable t) { Stereoscopic.LOG.warn("DeleteObject(hbmColor) failed; GDI bitmap leaked", t); }
                if (hbmMask  != 0L) try { JNI.invokePI(hbmMask,  FN_DeleteObject); }
                    catch (Throwable t) { Stereoscopic.LOG.warn("DeleteObject(hbmMask) failed; GDI bitmap leaked", t); }
            }
        } catch (Throwable t) {
            Stereoscopic.LOG.warn("OS cursor capture failed", t);
            return null;
        }
    }

    /**
     * Extract pixel data via SelectObject + GetPixel.
     *
     * <p>Color cursor: hbmColor gives RGB; hbmMask is single-height with
     * 0=opaque, non-zero=transparent.
     * <p>Mono cursor: only hbmMask, double-height — top half AND mask, bottom
     * half XOR mask. Compose pragmatically (AND=transparent + XOR=invert →
     * opaque white).
     * <p>GetPixel returns COLORREF {@code 0x00BBGGRR}.
     */
    private static Sprite captureSprite(long hbmColor, long hbmMask, int xHot, int yHot) {
        boolean useColor = hbmColor != 0L;
        long primary = useColor ? hbmColor : hbmMask;
        if (primary == 0L) return null;

        // BITMAP on x64 (32 bytes): bmType(4), bmWidth(4), bmHeight(4),
        // bmWidthBytes(4), bmPlanes(2), bmBitsPixel(2), pad(4), bmBits(8).
        ByteBuffer bmpBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
        long bmpAddr = MemoryUtil.memAddress(bmpBuf);
        int got = JNI.invokePPI(primary, 32, bmpAddr, FN_GetObjectW);
        if (got == 0) {
            Stereoscopic.LOG.warn("GetObjectW on cursor bitmap returned 0");
            return null;
        }
        int w = bmpBuf.getInt(4);
        int rawH = bmpBuf.getInt(8);
        int absH = Math.abs(rawH);
        if (w <= 0 || absH == 0) {
            Stereoscopic.LOG.warn("Cursor bitmap has invalid dimensions {}x{}", w, rawH);
            return null;
        }
        if (!useColor && (absH % 2 != 0)) {
            Stereoscopic.LOG.warn("Mono cursor mask has odd height {}; cannot split AND/XOR", absH);
            return null;
        }
        final int finalH = useColor ? absH : (absH / 2);

        long memDC = JNI.invokePP(0L, FN_CreateCompatibleDC);
        if (memDC == 0L) {
            Stereoscopic.LOG.warn("CreateCompatibleDC failed");
            return null;
        }

        byte[] colorRGB = new byte[w * finalH * 3];
        byte[] alpha    = new byte[w * finalH];

        try {
            // Color data — color cursor: hbmColor. Mono: XOR half of hbmMask (rows finalH..2*finalH-1).
            long colorSrc = useColor ? hbmColor : hbmMask;
            long prevColor = JNI.invokePPP(memDC, colorSrc, FN_SelectObject);
            if (prevColor == 0L) {
                Stereoscopic.LOG.warn("SelectObject(color) failed");
                return null;
            }
            try {
                for (int y = 0; y < finalH; y++) {
                    int srcY = useColor ? y : (y + finalH);
                    for (int x = 0; x < w; x++) {
                        int c = JNI.invokePI(memDC, x, srcY, FN_GetPixel);
                        int o = (y * w + x) * 3;
                        colorRGB[o]     = (byte) (c & 0xFF);          // R
                        colorRGB[o + 1] = (byte) ((c >> 8) & 0xFF);   // G
                        colorRGB[o + 2] = (byte) ((c >> 16) & 0xFF);  // B
                    }
                }
            } finally {
                JNI.invokePPP(memDC, prevColor, FN_SelectObject);
            }

            // Alpha from AND mask — color: hbmMask single-height. Mono: top half of hbmMask.
            // 0 = opaque, non-zero = transparent.
            if (hbmMask != 0L) {
                long prevMask = JNI.invokePPP(memDC, hbmMask, FN_SelectObject);
                if (prevMask == 0L) {
                    Stereoscopic.LOG.warn("SelectObject(mask) failed; assuming fully opaque");
                    for (int i = 0; i < alpha.length; i++) alpha[i] = (byte) 0xFF;
                } else {
                    try {
                        for (int y = 0; y < finalH; y++) {
                            for (int x = 0; x < w; x++) {
                                int m = JNI.invokePI(memDC, x, y, FN_GetPixel);
                                alpha[y * w + x] = (m == 0) ? (byte) 0xFF : 0;
                            }
                        }
                    } finally {
                        JNI.invokePPP(memDC, prevMask, FN_SelectObject);
                    }
                }
            } else {
                for (int i = 0; i < alpha.length; i++) alpha[i] = (byte) 0xFF;
            }

            // Compose into BGRA. COLORREF gave us RGB; swap R/B for BGRA.
            byte[] bgra = new byte[w * finalH * 4];
            if (useColor) {
                for (int i = 0; i < w * finalH; i++) {
                    int co = i * 3;
                    int ro = i * 4;
                    bgra[ro]     = colorRGB[co + 2]; // B
                    bgra[ro + 1] = colorRGB[co + 1]; // G
                    bgra[ro + 2] = colorRGB[co];     // R
                    bgra[ro + 3] = alpha[i];         // A
                }
            } else {
                // Mono compose. AND=opaque means alpha != 0. XOR=white means colorRGB byte != 0.
                //   AND=transparent + XOR=black → transparent
                //   AND=transparent + XOR=white → "invert background" — fallback opaque white
                //   AND=opaque + XOR=black → opaque black
                //   AND=opaque + XOR=white → opaque white
                for (int i = 0; i < w * finalH; i++) {
                    boolean opaque = alpha[i] != 0;
                    boolean xorWhite = (colorRGB[i * 3] & 0xFF) != 0;
                    final byte v, a;
                    if (!opaque && !xorWhite) { v = 0;           a = 0; }
                    else if (!opaque)         { v = (byte) 0xFF; a = (byte) 0xFF; }
                    else if (!xorWhite)       { v = 0;           a = (byte) 0xFF; }
                    else                      { v = (byte) 0xFF; a = (byte) 0xFF; }
                    int ro = i * 4;
                    bgra[ro]     = v;
                    bgra[ro + 1] = v;
                    bgra[ro + 2] = v;
                    bgra[ro + 3] = a;
                }
            }

            Stereoscopic.LOG.info("OS cursor captured: {}x{}, hotspot=({},{}), color={}",
                                  w, finalH, xHot, yHot, useColor);
            return new Sprite(w, finalH, xHot, yHot, bgra);
        } finally {
            try { JNI.invokePI(memDC, FN_DeleteDC); }
            catch (Throwable t) { Stereoscopic.LOG.warn("DeleteDC(memDC) failed; GDI DC leaked", t); }
        }
    }

    @Override
    public void trapCursor(boolean clip) {
        if (!isSupported()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!clip) {
                User32.ClipCursor((RECT) null);
                trapped = false;
                return;
            }
            RECT rect = RECT.calloc(stack);
            int ok = JNI.invokePPI(hwnd, rect.address(), FN_GetClientRect);
            if (ok == 0) {
                Stereoscopic.LOG.warn("GetClientRect failed");
                return;
            }
            POINT topLeft  = POINT.calloc(stack).x(rect.left()).y(rect.top());
            POINT botRight = POINT.calloc(stack).x(rect.right()).y(rect.bottom());
            if (!User32.ClientToScreen(hwnd, topLeft))  return;
            if (!User32.ClientToScreen(hwnd, botRight)) return;
            rect.left(topLeft.x()).top(topLeft.y())
                .right(botRight.x()).bottom(botRight.y());
            User32.ClipCursor(rect);
            trapped = true;
        } catch (Throwable t) {
            Stereoscopic.LOG.warn("ClipCursor failed", t);
        }
    }

    @Override
    public void release() {
        if (trapped) trapCursor(false);
    }
}
