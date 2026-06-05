package com.mitchellmarx.stereoscopic.cursor;

import com.mitchellmarx.stereoscopic.Stereoscopic;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWGL;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.opengl.ARBCopyImage;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.WGL;
import org.lwjgl.opengl.WGLARBCreateContext;
import org.lwjgl.opengl.WGLEXTSwapControl;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.windows.GDI32;
import org.lwjgl.system.windows.User32;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cursor presentation on a separate thread with its own GL context, sharing
 * resources with MC's main context but bound to the same window so its renders
 * and swaps reach the visible surface.
 *
 * <p>GLFW shared contexts don't work for this: each GLFW window has its own
 * framebuffer and a secondary GLFW window's {@code SwapBuffers} wouldn't reach
 * MC's visible window. We bypass GLFW — grab MC's HWND + HDC + HGLRC, call
 * {@code wglCreateContextAttribsARB(mainHdc, mainHglrc, attribs)} for a shared
 * context that renders into the main window. The cursor thread
 * {@code wglMakeCurrent}s it, renders, and {@code SwapBuffers(mainHdc)}.
 *
 * <p><b>Triple-buffer handoff.</b> Three shared present textures plus a
 * one-slot atomic mailbox. At all times the three indices partition into:
 * <ul>
 *   <li>{@code mainWriteIdx} — exclusively read/written by main</li>
 *   <li>{@code cursorReadIdx} — exclusively read/written by cursor</li>
 *   <li>{@code pending} — atomic slot holding the third idx + optional fence.
 *       Non-zero fence = main published fresh content; zero = stale.</li>
 * </ul>
 * Main never overwrites a texture cursor is reading or about to read; each
 * fence has exactly one owner at any instant. A double-buffer design would
 * let main's <em>next</em> copy land in the texture cursor switched to right
 * after the fence signaled, producing torn frames.
 *
 * <p>Windows only. Non-Windows falls back to vanilla GLFW cursor handling.
 * Port of Angelica's {@code CursorPresentThread} from stereo-sbs commits
 * {@code b8928270}, {@code ed1fea00}, {@code a09179fe}.
 */
public final class CursorPresentThread {

    // Lazily-resolved user32 entry points for ClipCursor/GetCursorPos. Resolve
    // failure disables the trap but doesn't block the present loop.
    private static volatile long FN_GetCursorPos        = 0L;
    private static volatile long FN_GetForegroundWindow = 0L;
    private static volatile long FN_ClipCursor          = 0L;
    private static volatile long FN_GetClientRect       = 0L;
    private static volatile long FN_ClientToScreen      = 0L;
    private static volatile boolean cursorThreadNativesOk = false;

    private static final int WGL_CONTEXT_MAJOR_VERSION_ARB              = 0x2091;
    private static final int WGL_CONTEXT_MINOR_VERSION_ARB              = 0x2092;
    private static final int WGL_CONTEXT_PROFILE_MASK_ARB               = 0x9126;
    private static final int WGL_CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB  = 0x0002;
    private static final int GL_MAJOR_VERSION = 0x821B;
    private static final int GL_MINOR_VERSION = 0x821C;

    private static volatile CursorPresentThread INSTANCE;

    /**
     * Triple-buffer mailbox entry. {@code fence == 0} means stale (no publish
     * since cursor's last claim); non-zero is a {@code glFenceSync} handle
     * (LWJGL 3.3 represents GLsync as long, not a wrapper).
     */
    static final class Slot {
        final int  idx;
        final long fence;
        Slot(int idx, long fence) {
            this.idx = idx;
            this.fence = fence;
        }
    }

    private final Thread worker;
    private volatile boolean running = true;

    private final long mainHwnd;
    private final long mainHdc;
    private final long cursorHglrc;

    final int[] tex = new int[3];
    volatile int cachedW = 0;
    volatile int cachedH = 0;

    int mainWriteIdx = 0;    // main-thread exclusive
    int cursorReadIdx = 1;   // cursor-thread exclusive
    final AtomicReference<Slot> pending = new AtomicReference<>(new Slot(2, 0L));

    /**
     * Main's previous-frame fence — rate-limits main to "at most one frame
     * ahead of GPU". Without it the driver queues commands faster than the
     * GPU drains and input-to-display latency grows unbounded;
     * {@code glFinish} would serialise CPU/GPU instead of pipelining. Only
     * touched from the main thread inside {@link #publishFrame()}.
     */
    long previousFrameFence = 0L;

    boolean useArbFallback = false;

    private final CursorBackend cursorBackend;
    private volatile boolean cursorSpriteTried = false;
    private int cursorTexId = 0;
    private int cursorTexW = 0;
    private int cursorTexH = 0;
    private int cursorHotspotX = 0;
    private int cursorHotspotY = 0;

    // Reusable buffers — RECT is 4 LONGs (16 bytes), POINT is 2 LONGs (8 bytes).
    private final ByteBuffer clipRect = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
    private final ByteBuffer clipPt   = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());
    private final long clipRectAddr;
    private final long clipPtAddr;
    private boolean lastClipApplied = false;

    private CursorPresentThread(long mainHwnd, long mainHdc, long cursorHglrc, CursorBackend backend) {
        this.mainHwnd = mainHwnd;
        this.mainHdc = mainHdc;
        this.cursorHglrc = cursorHglrc;
        this.cursorBackend = backend;
        this.clipRectAddr = MemoryUtil.memAddress(clipRect);
        this.clipPtAddr   = MemoryUtil.memAddress(clipPt);
        this.worker = new Thread(this::runLoop, "StereoscopicCursorPresent");
        this.worker.setDaemon(true);
    }

    private static synchronized void resolveCursorThreadNatives() {
        if (cursorThreadNativesOk) return;
        if (!isSupportedPlatform()) return;
        try {
            org.lwjgl.system.SharedLibrary user32 = User32.getLibrary();
            FN_GetCursorPos        = user32.getFunctionAddress("GetCursorPos");
            FN_GetForegroundWindow = user32.getFunctionAddress("GetForegroundWindow");
            FN_ClipCursor          = user32.getFunctionAddress("ClipCursor");
            FN_GetClientRect       = user32.getFunctionAddress("GetClientRect");
            FN_ClientToScreen      = user32.getFunctionAddress("ClientToScreen");
            cursorThreadNativesOk = FN_GetCursorPos != 0 && FN_GetForegroundWindow != 0
                                 && FN_ClipCursor != 0 && FN_GetClientRect != 0
                                 && FN_ClientToScreen != 0;
            if (!cursorThreadNativesOk) {
                Stereoscopic.LOG.warn(
                    "Cursor-thread native resolve incomplete: GetCursorPos={}, GetForegroundWindow={},"
                    + " ClipCursor={}, GetClientRect={}, ClientToScreen={}",
                    FN_GetCursorPos, FN_GetForegroundWindow, FN_ClipCursor,
                    FN_GetClientRect, FN_ClientToScreen);
            }
        } catch (Throwable t) {
            Stereoscopic.LOG.warn("Cursor-thread native resolve failed; ClipCursor trap disabled", t);
        }
    }

    public static boolean isSupportedPlatform() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    public static boolean isRunning() {
        return INSTANCE != null;
    }

    static CursorPresentThread instance() {
        return INSTANCE;
    }

    public static synchronized void ensureStarted(CursorBackend backend) {
        if (INSTANCE != null) return;
        if (!isSupportedPlatform()) {
            Stereoscopic.LOG.info("Async cursor: only Windows supported in v0.1.0 — disabled.");
            return;
        }

        // Need glCopyImageSubData (GL 4.3 core or ARB_copy_image) for the
        // main-thread fb-to-texture publish. Bail rather than silently fall
        // back to a slower path.
        GLCapabilities caps = GL.getCapabilities();
        boolean hasCore = caps.OpenGL43;
        boolean hasArb  = caps.GL_ARB_copy_image;
        if (!hasCore && !hasArb) {
            Stereoscopic.LOG.warn("Async cursor disabled: glCopyImageSubData (GL 4.3 / ARB_copy_image) not available.");
            return;
        }

        resolveCursorThreadNatives();

        long mainHwnd = 0L, mainHdc = 0L, cursorHglrc = 0L;
        try {
            long mainGlfw = GLFW.glfwGetCurrentContext();
            if (mainGlfw == 0L) throw new IllegalStateException("glfwGetCurrentContext returned NULL");

            mainHwnd = GLFWNativeWin32.glfwGetWin32Window(mainGlfw);
            if (mainHwnd == 0L) throw new IllegalStateException("glfwGetWin32Window returned NULL");

            mainHdc = User32.GetDC(mainHwnd);
            if (mainHdc == 0L) throw new IllegalStateException("User32.GetDC returned NULL");

            long mainHglrc = GLFWNativeWGL.glfwGetWGLContext(mainGlfw);
            if (mainHglrc == 0L) throw new IllegalStateException("glfwGetWGLContext returned NULL");

            // Match MC's version for cleanest sharing; 3.2 compat fallback if query fails.
            int major = GL11.glGetInteger(GL_MAJOR_VERSION);
            int minor = GL11.glGetInteger(GL_MINOR_VERSION);
            if (major <= 0) { major = 3; minor = 2; }

            int[] attribs = {
                WGL_CONTEXT_MAJOR_VERSION_ARB, major,
                WGL_CONTEXT_MINOR_VERSION_ARB, minor,
                WGL_CONTEXT_PROFILE_MASK_ARB,  WGL_CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB,
                0
            };
            cursorHglrc = WGLARBCreateContext.wglCreateContextAttribsARB(mainHdc, mainHglrc, attribs);
            if (cursorHglrc == 0L) throw new IllegalStateException("wglCreateContextAttribsARB returned NULL");

            // wglCreateContextAttribsARB doesn't change the calling thread's
            // current context — main's context stays current on main.
            CursorPresentThread it = new CursorPresentThread(mainHwnd, mainHdc, cursorHglrc, backend);
            it.useArbFallback = !hasCore && hasArb;
            INSTANCE = it;
            it.worker.start();
            Stereoscopic.LOG.info("Async cursor present thread started (GL {}.{} compat, using {}).",
                                  major, minor, hasCore ? "GL4.3" : "ARB_copy_image");
        } catch (Throwable t) {
            Stereoscopic.LOG.error("Failed to set up async cursor context; feature disabled.", t);
            try { if (cursorHglrc != 0L) WGL.wglDeleteContext(cursorHglrc); }
            catch (Throwable cleanupT) { Stereoscopic.LOG.warn("wglDeleteContext during start() cleanup failed; HGLRC leaked", cleanupT); }
            try { if (mainHdc != 0L && mainHwnd != 0L) User32.ReleaseDC(mainHwnd, mainHdc); }
            catch (Throwable cleanupT) { Stereoscopic.LOG.warn("ReleaseDC during start() cleanup failed; HDC leaked", cleanupT); }
        }
    }

    public static synchronized void stop() {
        CursorPresentThread it = INSTANCE;
        if (it == null) return;
        INSTANCE = null;
        it.running = false;
        try { it.worker.join(500); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        try { WGL.wglDeleteContext(it.cursorHglrc); }
        catch (Throwable t) { Stereoscopic.LOG.warn("wglDeleteContext failed on stop", t); }
        try { User32.ReleaseDC(it.mainHwnd, it.mainHdc); }
        catch (Throwable t) { Stereoscopic.LOG.warn("User32.ReleaseDC failed on stop", t); }

        // Textures freed on the main thread (caller is on it); safe to delete
        // from any sharing context per the spec.
        try {
            for (int i = 0; i < it.tex.length; i++) {
                if (it.tex[i] != 0) { GL11.glDeleteTextures(it.tex[i]); it.tex[i] = 0; }
            }
        } catch (Throwable t) {
            Stereoscopic.LOG.warn("Error freeing present textures on stop", t);
        }
        if (it.previousFrameFence != 0L) {
            try { GL32.glDeleteSync(it.previousFrameFence); }
            catch (Throwable t) { Stereoscopic.LOG.warn("glDeleteSync(previousFrameFence) failed on stop; GL sync leaked", t); }
            it.previousFrameFence = 0L;
        }
        Slot stale = it.pending.get();
        if (stale != null && stale.fence != 0L) {
            try { GL32.glDeleteSync(stale.fence); }
            catch (Throwable t) { Stereoscopic.LOG.warn("glDeleteSync(pending slot fence) failed on stop; GL sync leaked", t); }
        }
    }

    private void runLoop() {
        // wglMakeCurrent is thread-local — main keeps its own context.
        if (!WGL.wglMakeCurrent(mainHdc, cursorHglrc)) {
            Stereoscopic.LOG.error("Cursor thread: wglMakeCurrent failed; exiting.");
            return;
        }
        try {
            GL.createCapabilities();

            // Vsync: without this, SwapBuffers is non-blocking, we queue swaps
            // faster than the display drains, input latency grows unbounded
            // and we contend GPU bandwidth with main. swap-interval 1 caps the
            // cursor rate at display refresh.
            try {
                boolean ok = WGLEXTSwapControl.wglSwapIntervalEXT(1);
                Stereoscopic.LOG.info("wglSwapIntervalEXT(1) returned {} — vsync {} active on cursor context.",
                                      ok, ok ? "IS" : "is NOT");
            } catch (Throwable t) {
                Stereoscopic.LOG.warn("wglSwapIntervalEXT(1) failed; running without vsync", t);
            }

            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);

            while (running) {
                try {
                    presentOnce();
                } catch (Throwable t) {
                    Stereoscopic.LOG.warn("Cursor present iteration failed; continuing", t);
                }
            }
        } finally {
            try { WGL.wglMakeCurrent(0L, 0L); }
            catch (Throwable t) { Stereoscopic.LOG.warn("wglMakeCurrent(0,0) failed on cursor-thread exit", t); }
        }
    }

    /**
     * Main-thread frame publish: copy MC's color attachment into our write
     * texture, fence the copy, atomically publish to the mailbox. Cursor
     * waits the fence before sampling, so it never reads a texture the GPU
     * is still writing to.
     */
    public static void publishFrame() {
        CursorPresentThread it = INSTANCE;
        if (it == null) return;
        try {
            // Wait for last frame's fence before queueing this frame's work
            // (keep queue depth at 1, preserve CPU/GPU pipelining).
            if (it.previousFrameFence != 0L) {
                GL32.glClientWaitSync(it.previousFrameFence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
                GL32.glDeleteSync(it.previousFrameFence);
                it.previousFrameFence = 0L;
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            RenderTarget fb = mc.getMainRenderTarget();
            if (fb == null) return;
            int srcTex = fb.getColorTextureId();
            if (srcTex <= 0) return;
            int w = fb.width;
            int h = fb.height;
            if (w <= 0 || h <= 0) return;

            boolean justAllocated;
            if (it.tex[0] == 0 || w != it.cachedW || h != it.cachedH) {
                Stereoscopic.LOG.info("Cursor present: (re)allocating textures at {}x{} (was {}x{})",
                                      w, h, it.cachedW, it.cachedH);
                it.allocatePresentTextures(w, h);
                justAllocated = true;
            } else {
                justAllocated = false;
            }

            int writeTex = it.tex[it.mainWriteIdx];

            // glCopyImageSubData is undefined when src is currently bound as
            // a color attachment to the active FB. Unbind to 0 first; MC
            // rebinds its FB at the top of the next iteration.
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

            copyImageSubData(it, srcTex, writeTex, w, h);

            // Seed the other two textures too on first/realloc so cursor
            // doesn't sample never-written texels (undefined per spec; many
            // drivers return white until next publish).
            if (justAllocated) {
                for (int i = 0; i < 3; i++) {
                    if (i == it.mainWriteIdx) continue;
                    copyImageSubData(it, srcTex, it.tex[i], w, h);
                }
            }

            long fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            GL11.glFlush();

            // Atomic publish. If cursor raced ahead and replaced the slot with
            // a null-fence slot, prev.fence==0 here and no delete is needed.
            // If cursor raced behind, its peek-then-CAS fails against our
            // newSlot and it never waits on the orphan fence we delete below.
            Slot newSlot = new Slot(it.mainWriteIdx, fence);
            Slot prev = it.pending.getAndSet(newSlot);
            it.mainWriteIdx = prev.idx;
            if (prev.fence != 0L) {
                // Cursor never consumed the previous publish — main outpaced
                // it. Safe to delete: no cursor thread can be mid-wait on it.
                GL32.glDeleteSync(prev.fence);
            }

            it.previousFrameFence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            GL11.glFlush();
        } catch (Throwable t) {
            Stereoscopic.LOG.warn("Failed to publish frame to cursor thread", t);
        }
    }

    private static void copyImageSubData(CursorPresentThread it, int srcTex, int dstTex, int w, int h) {
        if (it.useArbFallback) {
            ARBCopyImage.glCopyImageSubData(srcTex, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                                             dstTex, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                                             w, h, 1);
        } else {
            GL43.glCopyImageSubData(srcTex, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                                    dstTex, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                                    w, h, 1);
        }
    }

    private void allocatePresentTextures(int w, int h) {
        for (int i = 0; i < tex.length; i++) {
            if (tex[i] == 0) tex[i] = GL11.glGenTextures();
        }
        for (int i = 0; i < tex.length; i++) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex[i]);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                              GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        cachedW = w;
        cachedH = h;
    }

    private void ensureCursorSpriteTexture() {
        if (cursorSpriteTried) return;
        cursorSpriteTried = true;
        if (cursorBackend == null) return;
        try {
            CursorBackend.Sprite sprite = cursorBackend.captureArrowBitmap();
            if (sprite == null || sprite.bgra == null
             || sprite.width <= 0 || sprite.height <= 0) return;

            ByteBuffer pixels = MemoryUtil.memAlloc(sprite.bgra.length);
            try {
                pixels.put(sprite.bgra).flip();
                int tex = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
                // BGRA upload — GL12.GL_BGRA + UNSIGNED_BYTE matches our 4-byte-per-pixel layout.
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                                  sprite.width, sprite.height, 0,
                                  GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, pixels);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

                cursorTexId = tex;
                cursorTexW = sprite.width;
                cursorTexH = sprite.height;
                cursorHotspotX = sprite.hotspotX;
                cursorHotspotY = sprite.hotspotY;
                Stereoscopic.LOG.info("Cursor sprite uploaded: {}x{}, hotspot=({},{})",
                                      sprite.width, sprite.height, sprite.hotspotX, sprite.hotspotY);
            } finally {
                MemoryUtil.memFree(pixels);
            }
        } catch (Throwable t) {
            Stereoscopic.LOG.warn("Cursor sprite upload failed; falling back to drawn arrow", t);
        }
    }

    /**
     * Confine cursor to MC's client rect via {@code ClipCursor} and update
     * StereoCursor's virtual position from {@code GetCursorPos}. Re-apply per
     * iteration because Windows clears the clip on focus loss; the per-iter
     * {@code GetCursorPos} also drives the virtual cursor at the cursor
     * thread's rate (not main's framerate). Foreground check bails when MC
     * isn't focused so the trap stays released across alt-tabs.
     */
    private void maintainClipCursor() {
        if (!cursorThreadNativesOk) return;
        try {
            long fg = JNI.invokeP(FN_GetForegroundWindow);
            if (fg != mainHwnd) {
                lastClipApplied = false;
                return;
            }
            int gcrRc = JNI.invokePPI(mainHwnd, clipRectAddr, FN_GetClientRect);
            if (gcrRc == 0) return;
            int clientW = clipRect.getInt(8);
            int clientH = clipRect.getInt(12);
            clipPt.putInt(0, 0);
            clipPt.putInt(4, 0);
            int ctsRc = JNI.invokePPI(mainHwnd, clipPtAddr, FN_ClientToScreen);
            if (ctsRc == 0) return;
            int sx = clipPt.getInt(0);
            int sy = clipPt.getInt(4);
            clipRect.putInt(0, sx);
            clipRect.putInt(4, sy);
            clipRect.putInt(8, sx + clientW);
            clipRect.putInt(12, sy + clientH);
            JNI.invokePI(clipRectAddr, FN_ClipCursor);
            if (!lastClipApplied) {
                Stereoscopic.LOG.info("ClipCursor first apply: client={}x{}, screen=({},{})..({},{})",
                    clientW, clientH, sx, sy, sx + clientW, sy + clientH);
            }
            lastClipApplied = true;

            // Read OS cursor (now snapped inside the clip) and map absolutely
            // to the virtual cursor. Absolute (not delta) positioning prevents
            // drift between OS cursor and virtual when the user pushes against
            // an edge.
            clipPt.putInt(0, 0);
            clipPt.putInt(4, 0);
            int gcpRc = JNI.invokePI(clipPtAddr, FN_GetCursorPos);
            if (gcpRc != 0) {
                int cx = clipPt.getInt(0);
                int cy = clipPt.getInt(4);
                int relX = cx - sx;
                int relY = cy - sy;
                // X halved for SBS_HALF compression; Y flipped (Win32 grows
                // down, GL bottom-up).
                int vx = Math.max(0, Math.min((clientW / 2) - 1, relX / 2));
                int vy = Math.max(0, Math.min(clientH - 1, (clientH - 1) - relY));
                StereoCursor.setVirtualPos(vx, vy);
            }
        } catch (Throwable t) {
            Stereoscopic.LOG.warn("ClipCursor maintenance failed; disabling clip for this session", t);
            FN_ClipCursor = 0L;
        }
    }

    public static void releaseClipCursor() {
        CursorPresentThread it = INSTANCE;
        if (it == null) return;
        if (FN_ClipCursor == 0L) return;
        try {
            JNI.invokePI(0L, FN_ClipCursor); // ClipCursor(NULL) releases
            it.lastClipApplied = false;
        } catch (Throwable t) {
            Stereoscopic.LOG.warn("ClipCursor release failed", t);
        }
    }

    private void presentOnce() {
        if (StereoCursor.shouldTrap()) {
            maintainClipCursor();
        } else if (lastClipApplied) {
            releaseClipCursor();
        }

        // Peek-then-CAS. If main publishes between our peek and CAS, our CAS
        // fails and we pick up the new publish next iteration — main's
        // getAndSet returns our null-fence replacement so it skips the
        // fence-delete branch, so it can never delete a fence we're mid-wait
        // on.
        Slot peeked = pending.get();
        if (peeked.fence != 0L) {
            Slot replacement = new Slot(cursorReadIdx, 0L);
            if (pending.compareAndSet(peeked, replacement)) {
                int waitResult = GL32.glClientWaitSync(peeked.fence,
                                                       GL32.GL_SYNC_FLUSH_COMMANDS_BIT,
                                                       1_000_000_000L);
                GL32.glDeleteSync(peeked.fence);
                if (waitResult == GL32.GL_ALREADY_SIGNALED
                 || waitResult == GL32.GL_CONDITION_SATISFIED) {
                    cursorReadIdx = peeked.idx;
                }
                // TIMEOUT / WAIT_FAILED: keep old read idx, re-present last
                // frame rather than sample a texture main may still be writing.
            }
        }

        int presentTex = this.tex[cursorReadIdx];
        if (presentTex == 0) return;

        int w = cachedW;
        int h = cachedH;
        if (w <= 0 || h <= 0) return;

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL11.glViewport(0, 0, w, h);

        // Compat profile gives us the legacy matrix stack + immediate-mode draw.
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0, w, h, 0, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);

        // V flipped — MC's fb texture is bottom-up; we draw in top-down ortho.
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, presentTex);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(0, 0);
        GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(w, 0);
        GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(w, h);
        GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(0, h);
        GL11.glEnd();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        if (StereoCursor.isActive()) {
            drawStereoCursors(w, h);
        }

        try { GDI32.SwapBuffers(mainHdc); }
        catch (Throwable t) { Stereoscopic.LOG.warn("GDI32.SwapBuffers failed", t); }
    }

    private void drawStereoCursors(int fullW, int fullH) {
        int halfW = fullW / 2;
        int leftX = StereoCursor.virtualX();
        int leftY = fullH - StereoCursor.virtualY() - 1;
        int rightX = leftX + halfW;
        int rightY = leftY;
        ensureCursorSpriteTexture();
        if (cursorTexId != 0) {
            drawCursorTextureAt(leftX, leftY);
            drawCursorTextureAt(rightX, rightY);
        } else {
            drawArrowAt(leftX, leftY);
            drawArrowAt(rightX, rightY);
        }
    }

    private void drawCursorTextureAt(int x, int y) {
        // SBS_HALF stretches each half 2× horizontally on the display, so a
        // sprite drawn at natural width here would land 2× wide. Pre-compress
        // X (size + hotspot) so it shows at native dims after stretching.
        int halfW = Math.max(1, cursorTexW / 2);
        int halfHotX = cursorHotspotX / 2;
        int x0 = x - halfHotX;
        int y0 = y - cursorHotspotY;
        int x1 = x0 + halfW;
        int y1 = y0 + cursorTexH;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, cursorTexId);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(x0, y0);
        GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(x1, y0);
        GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(x1, y1);
        GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(x0, y1);
        GL11.glEnd();
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    /** Hand-drawn arrow fallback when OS-arrow capture fails. */
    private static void drawArrowAt(int x, int y) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(0f, 0f, 0f, 1f);
        for (int i = 0; i < 9; i++) {
            GL11.glRecti(x - 1, y + i - 1, x + i + 2, y + i);
        }
        GL11.glRecti(x - 1, y + 8, x + 9, y + 9);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        for (int i = 0; i < 8; i++) {
            GL11.glRecti(x, y + i, x + i + 1, y + i + 1);
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }
}
