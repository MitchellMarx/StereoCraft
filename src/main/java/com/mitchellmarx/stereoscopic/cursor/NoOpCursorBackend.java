package com.mitchellmarx.stereoscopic.cursor;

/** No-op {@link CursorBackend} for non-Windows platforms. Vanilla GLFW cursor still works. */
public final class NoOpCursorBackend implements CursorBackend {

    @Override public Sprite captureArrowBitmap() { return null; }

    @Override public void trapCursor(boolean clip) {}

    @Override public void release() {}

    @Override public boolean isSupported() { return false; }
}
