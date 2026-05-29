package com.mitchellmarx.stereoscopic.cursor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Windows-specific {@link CursorBackend} implementation. Ported from sbs2's
 * {@code CursorPresentThread} Win32 sections (arrow capture, ClipCursor trap, HWND
 * resolution via wglGetCurrentDC + WindowFromDC).
 *
 * <p>All Win32 calls go through LWJGL 3's {@code org.lwjgl.system.JNI} reflectively, since
 * lwjgl3ify-3.0.17 rewrites org/lwjgl bytecode refs to org/lwjglx — we hit real LWJGL 3 via
 * {@code Class.forName} string-literal classloading. lwjgl3ify-3.0.17 has no GLFW (it ships
 * SDL3), so HWND comes from {@code wglGetCurrentDC()} → {@code WindowFromDC(hdc)} rather
 * than {@code glfwGetWGLContext}.
 */
public final class WindowsCursorBackend implements CursorBackend {

    private static final Logger LOGGER = LogManager.getLogger("StereoscopicCursorBackend");

    // Win32 IDC_ARROW resource ID — passed to LoadCursorW as MAKEINTRESOURCE-style integer pointer
    private static final long IDC_ARROW = 32512L;

    // Win32 function pointers
    private long pfnLoadCursorW;
    private long pfnGetIconInfo;
    private long pfnCreateCompatibleDC;
    private long pfnDeleteDC;
    private long pfnDeleteObject;
    private long pfnGetObject;
    private long pfnSelectObject;
    private long pfnGetPixel;
    private long pfnClipCursor;
    private long pfnGetClientRect;
    private long pfnClientToScreen;
    private long pfnGetForegroundWindow;
    private long pfnWglGetCurrentDC;
    private long pfnWindowFromDC;

    // LWJGL 3 JNI invoke variants. The arity-overloaded names follow LWJGL 3's signature pattern:
    //   invokeP(funcPtr) -> long   — zero-arg returning pointer (GetForegroundWindow, wglGetCurrentDC)
    //   invokePP(arg, funcPtr) -> long      — one pointer arg returning pointer (WindowFromDC, CreateCompatibleDC)
    //   invokePI(arg, funcPtr) -> int       — one pointer arg returning int (ClipCursor)
    //   invokePPI(p1, p2, funcPtr) -> int   — two pointers returning int (GetClientRect, ClientToScreen)
    //   invokePPP(p1, p2, funcPtr) -> long  — two pointers returning pointer (LoadCursorW, SelectObject)
    //   invokePPI_PIP(p1, i, p2, funcPtr) -> int — pointer, int, pointer (GetObjectW)
    //   invokePI_PII(p1, x, y, funcPtr) -> int   — pointer, int, int (GetPixel)
    private Method jniInvokeP;
    private Method jniInvokePI;
    private Method jniInvokePP;
    private Method jniInvokePPI;
    private Method jniInvokePPP;
    private Method jniInvokePPI_PIP;
    private Method jniInvokePI_PII;
    private Method memUtilMemAddress;

    private volatile boolean initialized = false;
    private volatile boolean ok = false;

    // ClipCursor reusable buffers (avoid per-iter alloc). RECT = 4 LONGs = 16 bytes,
    // POINT = 2 LONGs = 8 bytes.
    private final ByteBuffer clipRect = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
    private final ByteBuffer clipPt = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());
    private long clipRectAddr = 0L;
    private long clipPtAddr = 0L;
    private boolean lastClipApplied = false;

    private long cachedHwnd = 0L;

    // RFB's launchClassLoader is FML's LaunchClassLoader — the same loader that successfully
    // resolves LWJGL3 references from {@code Lwjgl3GLRenderBackend} (also in this jar) at
    // bytecode-link time. {@code compatClassLoader} (RfbSystemClassLoader) reports "Class bytes
    // are null" for these names; {@code originalSystemClassLoader} (JDK AppClassLoader) doesn't
    // have the LWJGL3 URLs.
    private static Class<?> loadLwjgl3(String name) throws ClassNotFoundException {
        return Class.forName(name, false,
            (ClassLoader) com.gtnewhorizons.retrofuturabootstrap.api.RetroFuturaBootstrap.API.launchClassLoader());
    }

    private synchronized void ensureInitialized() {
        if (initialized) return;
        initialized = true;
        try {
            final Class<?> winLibCls = loadLwjgl3("org.lwjgl.system.windows.WindowsLibrary");
            final Class<?> funcProvCls = loadLwjgl3("org.lwjgl.system.FunctionProvider");
            final Class<?> jniCls = loadLwjgl3("org.lwjgl.system.JNI");
            final Class<?> memUtilCls = loadLwjgl3("org.lwjgl.system.MemoryUtil");

            final Constructor<?> winLibCtor = winLibCls.getConstructor(String.class);
            final Method getFnAddr = funcProvCls.getMethod("getFunctionAddress", CharSequence.class);

            final Object user32 = winLibCtor.newInstance("user32");
            final Object gdi32 = winLibCtor.newInstance("gdi32");
            final Object opengl32 = winLibCtor.newInstance("opengl32");

            // LoadCursorW(NULL, IDC_ARROW) reliably returns the standard arrow regardless of
            // what the system happens to be showing at the moment of capture. GetCursor() would
            // return whatever's currently displayed — e.g., the wait spinner if a background
            // process briefly took over — and we'd be stuck with that for the session.
            pfnLoadCursorW = (Long) getFnAddr.invoke(user32, "LoadCursorW");
            pfnGetIconInfo = (Long) getFnAddr.invoke(user32, "GetIconInfo");
            pfnCreateCompatibleDC = (Long) getFnAddr.invoke(gdi32, "CreateCompatibleDC");
            pfnDeleteDC = (Long) getFnAddr.invoke(gdi32, "DeleteDC");
            pfnDeleteObject = (Long) getFnAddr.invoke(gdi32, "DeleteObject");
            pfnGetObject = (Long) getFnAddr.invoke(gdi32, "GetObjectW");
            pfnSelectObject = (Long) getFnAddr.invoke(gdi32, "SelectObject");
            pfnGetPixel = (Long) getFnAddr.invoke(gdi32, "GetPixel");

            // ClipCursor confines the cursor to MC's client rect while MC has focus, so
            // motion can't accidentally drag the cursor into another window/monitor.
            pfnClipCursor = (Long) getFnAddr.invoke(user32, "ClipCursor");
            pfnGetClientRect = (Long) getFnAddr.invoke(user32, "GetClientRect");
            pfnClientToScreen = (Long) getFnAddr.invoke(user32, "ClientToScreen");
            pfnGetForegroundWindow = (Long) getFnAddr.invoke(user32, "GetForegroundWindow");

            // HWND resolution avoids GLFW (not present in lwjgl3ify-3.0.17, which ships SDL3).
            // wglGetCurrentDC returns main's HDC; WindowFromDC(hdc) yields the HWND.
            pfnWglGetCurrentDC = (Long) getFnAddr.invoke(opengl32, "wglGetCurrentDC");
            pfnWindowFromDC = (Long) getFnAddr.invoke(user32, "WindowFromDC");

            jniInvokeP = jniCls.getMethod("invokeP", long.class);
            jniInvokePI = jniCls.getMethod("invokePI", long.class, long.class);
            jniInvokePP = jniCls.getMethod("invokePP", long.class, long.class);
            jniInvokePPI = jniCls.getMethod("invokePPI", long.class, long.class, long.class);
            jniInvokePPP = jniCls.getMethod("invokePPP", long.class, long.class, long.class);
            jniInvokePPI_PIP = jniCls.getMethod("invokePPI", long.class, int.class, long.class, long.class);
            jniInvokePI_PII = jniCls.getMethod("invokePI", long.class, int.class, int.class, long.class);
            memUtilMemAddress = memUtilCls.getMethod("memAddress", ByteBuffer.class);

            if (pfnLoadCursorW == 0L || pfnGetIconInfo == 0L || pfnCreateCompatibleDC == 0L
                || pfnDeleteDC == 0L || pfnDeleteObject == 0L || pfnGetObject == 0L
                || pfnSelectObject == 0L || pfnGetPixel == 0L
                || pfnClipCursor == 0L || pfnGetClientRect == 0L
                || pfnClientToScreen == 0L || pfnGetForegroundWindow == 0L
                || pfnWglGetCurrentDC == 0L || pfnWindowFromDC == 0L) {
                LOGGER.warn("One or more Win32 cursor function pointers failed to resolve.");
                return;
            }

            clipRectAddr = ((Number) memUtilMemAddress.invoke(null, clipRect)).longValue();
            clipPtAddr = ((Number) memUtilMemAddress.invoke(null, clipPt)).longValue();

            ok = true;
            LOGGER.info("WindowsCursorBackend initialized.");
        } catch (Throwable t) {
            LOGGER.warn("WindowsCursorBackend initialization failed; falling back to no-op.", t);
        }
    }

    /** Resolve the main window's HWND via wglGetCurrentDC + WindowFromDC. Cached after first success. */
    public long resolveHwnd() {
        ensureInitialized();
        if (!ok) return 0L;
        if (cachedHwnd != 0L) return cachedHwnd;
        try {
            final long hdc = ((Number) jniInvokeP.invoke(null, pfnWglGetCurrentDC)).longValue();
            if (hdc == 0L) return 0L;
            final long hwnd = ((Number) jniInvokePP.invoke(null, hdc, pfnWindowFromDC)).longValue();
            if (hwnd == 0L) return 0L;
            cachedHwnd = hwnd;
            return hwnd;
        } catch (Throwable t) {
            LOGGER.warn("HWND resolution via wglGetCurrentDC+WindowFromDC failed.", t);
            return 0L;
        }
    }

    @Override
    public Sprite captureArrowBitmap() {
        ensureInitialized();
        if (!ok) return null;
        try {
            // LoadCursorW(hInstance=NULL, lpCursorName=MAKEINTRESOURCE(IDC_ARROW)). Win32 detects
            // resource-ID-as-pointer by zero high bits.
            final long hCursor = ((Number) jniInvokePPP.invoke(null, 0L, IDC_ARROW, pfnLoadCursorW)).longValue();
            if (hCursor == 0L) {
                LOGGER.warn("LoadCursorW(IDC_ARROW) returned NULL; cursor capture aborted.");
                return null;
            }
            // ICONINFO on x64 (32 bytes total):
            //   off  0: BOOL  fIcon       (4)
            //   off  4: DWORD xHotspot    (4)
            //   off  8: DWORD yHotspot    (4)
            //   off 12: pad for HBITMAP alignment (4)
            //   off 16: HBITMAP hbmMask   (8)
            //   off 24: HBITMAP hbmColor  (8)
            final ByteBuffer iconBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
            final long iconAddr = ((Number) memUtilMemAddress.invoke(null, iconBuf)).longValue();
            final int rc = ((Number) jniInvokePPI.invoke(null, hCursor, iconAddr, pfnGetIconInfo)).intValue();
            if (rc == 0) {
                LOGGER.warn("GetIconInfo failed; cursor capture aborted.");
                return null;
            }
            final int xHot = iconBuf.getInt(4);
            final int yHot = iconBuf.getInt(8);
            final long hbmMask = iconBuf.getLong(16);
            final long hbmColor = iconBuf.getLong(24);
            try {
                return captureToSprite(hbmColor, hbmMask, xHot, yHot);
            } finally {
                // GetIconInfo docs: caller owns hbmColor and hbmMask — must DeleteObject them.
                if (hbmColor != 0L) {
                    try {
                        jniInvokePI.invoke(null, hbmColor, pfnDeleteObject);
                    } catch (Throwable t) {
                        LOGGER.warn("DeleteObject failed on hbmColor; GDI handle leak.", t);
                    }
                }
                if (hbmMask != 0L) {
                    try {
                        jniInvokePI.invoke(null, hbmMask, pfnDeleteObject);
                    } catch (Throwable t) {
                        LOGGER.warn("DeleteObject failed on hbmMask; GDI handle leak.", t);
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("OS cursor capture failed.", t);
            return null;
        }
    }

    private Sprite captureToSprite(long hbmColor, long hbmMask, int xHot, int yHot) throws Throwable {
        final boolean useColor = hbmColor != 0L;
        final long primary = useColor ? hbmColor : hbmMask;
        if (primary == 0L) return null;

        // BITMAP on x64 (32 bytes): bmType (4), bmWidth (4), bmHeight (4), bmWidthBytes (4),
        // bmPlanes (2), bmBitsPixel (2), pad (4), bmBits (8).
        final ByteBuffer bmpBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
        final long bmpAddr = ((Number) memUtilMemAddress.invoke(null, bmpBuf)).longValue();
        final int got = ((Number) jniInvokePPI_PIP.invoke(null, primary, 32, bmpAddr, pfnGetObject)).intValue();
        if (got == 0) {
            LOGGER.warn("GetObjectW on cursor bitmap returned 0.");
            return null;
        }
        final int w = bmpBuf.getInt(4);
        final int rawH = bmpBuf.getInt(8);
        final int absH = Math.abs(rawH);
        if (w <= 0 || absH == 0) {
            LOGGER.warn("Cursor bitmap has invalid dimensions {}x{}.", w, rawH);
            return null;
        }
        if (!useColor && (absH % 2 != 0)) {
            LOGGER.warn("Mono cursor mask has odd height {}; cannot split AND/XOR.", absH);
            return null;
        }
        final int finalH = useColor ? absH : (absH / 2);

        final long memDC = ((Number) jniInvokePP.invoke(null, 0L, pfnCreateCompatibleDC)).longValue();
        if (memDC == 0L) {
            LOGGER.warn("CreateCompatibleDC failed; cursor capture aborted.");
            return null;
        }

        final byte[] colorRGB = new byte[w * finalH * 3];
        final byte[] alpha = new byte[w * finalH];

        try {
            final long colorSrc = useColor ? hbmColor : hbmMask;
            final long prevColor = ((Number) jniInvokePPP.invoke(null, memDC, colorSrc, pfnSelectObject)).longValue();
            if (prevColor == 0L) {
                LOGGER.warn("SelectObject(color) failed.");
                return null;
            }
            try {
                for (int y = 0; y < finalH; y++) {
                    final int srcY = useColor ? y : (y + finalH);
                    for (int x = 0; x < w; x++) {
                        final int c = ((Number) jniInvokePI_PII.invoke(null, memDC, x, srcY, pfnGetPixel)).intValue();
                        final int o = (y * w + x) * 3;
                        colorRGB[o] = (byte) (c & 0xFF);
                        colorRGB[o + 1] = (byte) ((c >> 8) & 0xFF);
                        colorRGB[o + 2] = (byte) ((c >> 16) & 0xFF);
                    }
                }
            } finally {
                jniInvokePPP.invoke(null, memDC, prevColor, pfnSelectObject);
            }

            if (hbmMask != 0L) {
                final long prevMask = ((Number) jniInvokePPP.invoke(null, memDC, hbmMask, pfnSelectObject)).longValue();
                if (prevMask == 0L) {
                    LOGGER.warn("SelectObject(mask) failed; assuming fully opaque.");
                    for (int i = 0; i < alpha.length; i++) alpha[i] = (byte) 0xFF;
                } else {
                    try {
                        for (int y = 0; y < finalH; y++) {
                            for (int x = 0; x < w; x++) {
                                final int m = ((Number) jniInvokePI_PII.invoke(null, memDC, x, y, pfnGetPixel)).intValue();
                                alpha[y * w + x] = (m == 0) ? (byte) 0xFF : 0;
                            }
                        }
                    } finally {
                        jniInvokePPP.invoke(null, memDC, prevMask, pfnSelectObject);
                    }
                }
            } else {
                for (int i = 0; i < alpha.length; i++) alpha[i] = (byte) 0xFF;
            }

            // Build BGRA byte array. Win32 GetPixel returns COLORREF = 0x00BBGGRR (R=low byte),
            // so colorRGB[0]=R, [1]=G, [2]=B. Repack as BGRA.
            final byte[] bgra = new byte[w * finalH * 4];
            if (useColor) {
                for (int i = 0; i < w * finalH; i++) {
                    final int co = i * 3;
                    final int ro = i * 4;
                    // BGRA: B, G, R, A
                    bgra[ro]     = colorRGB[co + 2]; // B
                    bgra[ro + 1] = colorRGB[co + 1]; // G
                    bgra[ro + 2] = colorRGB[co];     // R
                    bgra[ro + 3] = alpha[i];
                }
            } else {
                for (int i = 0; i < w * finalH; i++) {
                    final boolean opaque = alpha[i] != 0;
                    final boolean xorWhite = (colorRGB[i * 3] & 0xFF) != 0;
                    final byte v, a;
                    if (!opaque && !xorWhite) { v = 0;           a = 0; }
                    else if (!opaque)         { v = (byte) 0xFF; a = (byte) 0xFF; }
                    else if (!xorWhite)       { v = 0;           a = (byte) 0xFF; }
                    else                      { v = (byte) 0xFF; a = (byte) 0xFF; }
                    final int ro = i * 4;
                    bgra[ro]     = v; // B
                    bgra[ro + 1] = v; // G
                    bgra[ro + 2] = v; // R
                    bgra[ro + 3] = a;
                }
            }

            LOGGER.info("OS cursor captured: {}x{}, hotspot=({},{}), color={}",
                        w, finalH, xHot, yHot, useColor);
            return new Sprite(w, finalH, xHot, yHot, bgra);
        } finally {
            try {
                jniInvokePI.invoke(null, memDC, pfnDeleteDC);
            } catch (Throwable t) {
                LOGGER.warn("DeleteDC failed; GDI handle leak.", t);
            }
        }
    }

    /**
     * If MC's main window is the foreground window, confine the cursor to its client rect via
     * {@code ClipCursor}. If MC isn't focused, do nothing — Windows auto-releases the clip when
     * the owning window loses focus, so the user retains free control over their other windows.
     * Re-applying every iter is necessary because Windows clears the clip on focus loss, and we
     * need to re-establish it on focus regain.
     *
     * @return true if the clip was applied this call (focused + applied successfully), false otherwise.
     */
    public synchronized boolean maintainClip() {
        ensureInitialized();
        if (!ok) return false;
        final long hwnd = resolveHwnd();
        if (hwnd == 0L) return false;
        try {
            final long fg = ((Number) jniInvokeP.invoke(null, pfnGetForegroundWindow)).longValue();
            if (fg != hwnd) {
                // Lost focus: Windows already cleared the clip.
                lastClipApplied = false;
                return false;
            }
            // GetClientRect returns (0, 0, width, height) in client coords.
            final int gcrRc = ((Number) jniInvokePPI.invoke(null, hwnd, clipRectAddr, pfnGetClientRect)).intValue();
            if (gcrRc == 0) return false;
            final int clientW = clipRect.getInt(8);
            final int clientH = clipRect.getInt(12);
            // ClientToScreen translates (0,0) → screen coords of the client area's top-left.
            clipPt.putInt(0, 0);
            clipPt.putInt(4, 0);
            final int ctsRc = ((Number) jniInvokePPI.invoke(null, hwnd, clipPtAddr, pfnClientToScreen)).intValue();
            if (ctsRc == 0) return false;
            final int sx = clipPt.getInt(0);
            final int sy = clipPt.getInt(4);
            // Build the screen-space RECT and clip.
            clipRect.putInt(0, sx);
            clipRect.putInt(4, sy);
            clipRect.putInt(8, sx + clientW);
            clipRect.putInt(12, sy + clientH);
            jniInvokePI.invoke(null, clipRectAddr, pfnClipCursor);
            lastClipApplied = true;
            return true;
        } catch (Throwable t) {
            LOGGER.warn("ClipCursor maintenance failed; disabling clip for this session.", t);
            pfnClipCursor = 0L;
            return false;
        }
    }

    /** Release the ClipCursor confinement. */
    public synchronized void releaseClip() {
        ensureInitialized();
        if (!ok || pfnClipCursor == 0L) return;
        try {
            jniInvokePI.invoke(null, 0L, pfnClipCursor);
            lastClipApplied = false;
        } catch (Throwable t) {
            LOGGER.warn("ClipCursor release failed.", t);
        }
    }

    /** True if the most recent {@link #maintainClip()} call actually applied the clip. */
    public boolean isClipApplied() {
        return lastClipApplied;
    }

    /**
     * Read the OS cursor's screen-space position and the client-area screen origin so a caller
     * can compute the cursor's position relative to the client area. Used by the present thread
     * to drive the virtual cursor in stereo+GUI mode.
     *
     * <p>Returns a 6-int array: {@code [cursorScreenX, cursorScreenY, clientOriginX, clientOriginY,
     * clientW, clientH]}, or null if the read failed.
     */
    public synchronized int[] readClientCursor(long pfnGetCursorPos) {
        ensureInitialized();
        if (!ok) return null;
        final long hwnd = resolveHwnd();
        if (hwnd == 0L) return null;
        try {
            // Client rect (size).
            final int gcrRc = ((Number) jniInvokePPI.invoke(null, hwnd, clipRectAddr, pfnGetClientRect)).intValue();
            if (gcrRc == 0) return null;
            final int clientW = clipRect.getInt(8);
            final int clientH = clipRect.getInt(12);
            // Client origin in screen coords.
            clipPt.putInt(0, 0);
            clipPt.putInt(4, 0);
            final int ctsRc = ((Number) jniInvokePPI.invoke(null, hwnd, clipPtAddr, pfnClientToScreen)).intValue();
            if (ctsRc == 0) return null;
            final int sx = clipPt.getInt(0);
            final int sy = clipPt.getInt(4);
            // Cursor screen position.
            clipPt.putInt(0, 0);
            clipPt.putInt(4, 0);
            final int gcpRc = ((Number) jniInvokePI.invoke(null, clipPtAddr, pfnGetCursorPos)).intValue();
            if (gcpRc == 0) return null;
            final int cx = clipPt.getInt(0);
            final int cy = clipPt.getInt(4);
            return new int[] { cx, cy, sx, sy, clientW, clientH };
        } catch (Throwable t) {
            LOGGER.warn("readClientCursor failed.", t);
            return null;
        }
    }

    @Override
    public void trapCursor(boolean clip) {
        if (clip) maintainClip();
        else releaseClip();
    }

    @Override
    public void release() {
        releaseClip();
    }

    @Override
    public boolean isSupported() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) return false;
        ensureInitialized();
        return ok;
    }
}
