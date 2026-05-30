package com.mitchellmarx.stereoscopic.cursor;

import com.mitchellmarx.stereoscopic.Stereoscopic;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;

/**
 * Main-thread facade for the stereo cursor system. Owns the
 * {@link CursorBackend}, manages virtual cursor position, brings the
 * {@link CursorPresentThread} up/down with stereo, and decides ClipCursor
 * trap intent. The cursor thread polls {@link #isActive()} / {@link #virtualX()}
 * / {@link #virtualY()} and writes back via {@link #setVirtualPos}.
 */
public final class StereoCursor {

    private StereoCursor() {}

    private static volatile boolean active = false;
    private static volatile int virtualX = 0;
    private static volatile int virtualY = 0;

    public static boolean isActive() { return active; }
    /** Virtual cursor X in left-eye-half client coords (0..fullW/2 - 1). */
    public static int virtualX() { return virtualX; }
    /** Virtual cursor Y in client coords (0..fullH - 1, GL bottom-up). */
    public static int virtualY() { return virtualY; }
    public static void setVirtualPos(int x, int y) { virtualX = x; virtualY = y; }

    private static volatile boolean running = false;
    private static volatile CursorBackend backend;
    private static volatile boolean shouldTrap = false;

    /**
     * Cursor thread reads this to decide maintainClipCursor vs releaseClipCursor.
     * The thread's own GetForegroundWindow check covers focus loss, so we can
     * set this true on "trap when focused" intent without worrying about
     * alt-tab leaving the cursor confined.
     */
    public static boolean shouldTrap() { return shouldTrap; }

    private static volatile boolean weAreHidingCursor = false;

    /**
     * Per-frame tick from {@code MixinGameRenderer @Inject(HEAD, render)}.
     * Lifecycle: start the present thread on first stereo frame, stop on
     * first mono frame after running. Per-frame: refresh overlay active
     * flag, hide/restore the OS cursor in stereo+GUI, publish trap intent.
     */
    public static void tick() {
        boolean stereoOn = StereoState.INSTANCE.isActive();
        if (stereoOn && !running) {
            start();
        } else if (!stereoOn && running) {
            stop();
        }
        if (!running) {
            active = false;
            shouldTrap = false;
            restoreOsCursorIfHidden();
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        boolean screenOpen = mc != null && mc.screen != null;
        boolean gameplayFocus = mc != null && mc.mouseHandler != null && mc.mouseHandler.isMouseGrabbed();

        // Overlay only when stereo + screen open. In gameplay, vanilla
        // CURSOR_DISABLED hides the cursor and there's no virtual cursor.
        active = screenOpen;
        // Trap while focused, in either screen or gameplay. Cursor thread
        // re-checks GetForegroundWindow before applying.
        shouldTrap = screenOpen || gameplayFocus;

        // Hide the OS arrow during stereo+GUI so the user doesn't see two
        // cursors (OS arrow + our sprite). HIDDEN, not DISABLED — cursor
        // thread keeps reading GetCursorPos for the virtual cursor position.
        if (screenOpen) {
            hideOsCursor(mc);
        } else {
            restoreOsCursorIfHidden();
        }
    }

    private static void hideOsCursor(Minecraft mc) {
        if (weAreHidingCursor) return;
        if (mc == null || mc.getWindow() == null) return;
        long handle = mc.getWindow().getWindow();
        if (handle == 0L) return;
        try {
            GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
            weAreHidingCursor = true;
        } catch (Throwable t) {
            Stereoscopic.LOG.warn("glfwSetInputMode CURSOR_HIDDEN failed", t);
        }
    }

    private static void restoreOsCursorIfHidden() {
        if (!weAreHidingCursor) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) {
            weAreHidingCursor = false;
            return;
        }
        long handle = mc.getWindow().getWindow();
        if (handle == 0L) {
            weAreHidingCursor = false;
            return;
        }
        // Restore to MC's preferred mode for the current state:
        //   gameplay (no screen) → DISABLED (look-deltas need the recenter)
        //   screen still open → NORMAL
        // MC's own setScreen(null) → mouse.lockCursor() ran during input
        // processing before our tick, so the mode we restore to matches.
        // Hard-coding NORMAL would clobber MC's correct DISABLED setting.
        int mode = (mc.screen == null)
            ? GLFW.GLFW_CURSOR_DISABLED
            : GLFW.GLFW_CURSOR_NORMAL;
        try {
            GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR, mode);
        } catch (Throwable t) {
            Stereoscopic.LOG.warn("glfwSetInputMode restore failed", t);
        }
        weAreHidingCursor = false;
    }

    public static synchronized void start() {
        if (running) return;
        if (!CursorPresentThread.isSupportedPlatform()) {
            Stereoscopic.LOG.info("Stereo cursor: only Windows supported in v0.1.0 — async cursor disabled.");
            running = true; // skip retry every frame
            return;
        }
        backend = createBackend();
        if (backend == null || !backend.isSupported()) {
            Stereoscopic.LOG.warn("Stereo cursor: no usable backend; async cursor disabled.");
            backend = new NoOpCursorBackend();
            running = true;
            return;
        }
        CursorPresentThread.ensureStarted(backend);
        running = true;
    }

    public static synchronized void stop() {
        if (!running) return;
        try { CursorPresentThread.stop(); }
        catch (Throwable t) { Stereoscopic.LOG.warn("CursorPresentThread.stop() failed", t); }
        if (backend != null) {
            try { backend.release(); }
            catch (Throwable t) { Stereoscopic.LOG.warn("Cursor backend release failed", t); }
            backend = null;
        }
        active = false;
        shouldTrap = false;
        restoreOsCursorIfHidden();
        running = false;
    }

    private static CursorBackend createBackend() {
        try {
            long mainGlfw = GLFW.glfwGetCurrentContext();
            if (mainGlfw == 0L) return null;
            long hwnd = GLFWNativeWin32.glfwGetWin32Window(mainGlfw);
            if (hwnd == 0L) return null;
            return new WindowsCursorBackend(hwnd);
        } catch (Throwable t) {
            Stereoscopic.LOG.warn("Cursor backend HWND resolution failed", t);
            return null;
        }
    }
}
