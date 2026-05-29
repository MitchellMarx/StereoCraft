package com.mitchellmarx.stereoscopic.cursor;

/**
 * Fallback backend for non-Windows platforms (Mac/Linux in v0.1.0) and for the case where
 * Windows backend init fails. {@link #isSupported()} returns false so the cursor present
 * thread won't start; the rest of the stereo system continues to work (just without the
 * async cursor overlay).
 */
public final class NoOpCursorBackend implements CursorBackend {
    @Override public Sprite captureArrowBitmap() { return null; }
    @Override public void trapCursor(boolean clip) { /* no-op */ }
    @Override public void release() { /* no-op */ }
    @Override public boolean isSupported() { return false; }
}
