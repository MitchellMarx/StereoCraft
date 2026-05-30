package com.mitchellmarx.stereoscopic.cursor;

/**
 * Pluggable OS-level cursor backend. GLFW's cursor presents once per render
 * frame — in stereo, render hitches translate directly into cursor stutter.
 * A native backend captures the OS arrow and lets the present thread render
 * it independently of the main render loop's framerate.
 */
public interface CursorBackend {

    /** BGRA pixels of the OS arrow + hotspot, or null if capture failed. */
    Sprite captureArrowBitmap();

    /** Clip the OS cursor to MC's client rect (true) or release (false). */
    void trapCursor(boolean clip);

    /** Free native resources. Called on mod shutdown. */
    void release();

    /** Whether this backend is usable on the current platform. */
    boolean isSupported();

    final class Sprite {
        public final int width;
        public final int height;
        public final int hotspotX;
        public final int hotspotY;
        public final byte[] bgra;

        public Sprite(int width, int height, int hotspotX, int hotspotY, byte[] bgra) {
            this.width = width;
            this.height = height;
            this.hotspotX = hotspotX;
            this.hotspotY = hotspotY;
            this.bgra = bgra;
        }
    }
}
