package com.mitchellmarx.stereoscopic.core;

public enum StereoMode {
    OFF, SBS_HALF;

    public boolean isActive()      { return this != OFF; }
    public boolean isSideBySide()  { return this == SBS_HALF; }
    public boolean isHalf()        { return this == SBS_HALF; }
}
