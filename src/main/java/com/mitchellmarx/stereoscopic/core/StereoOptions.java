package com.mitchellmarx.stereoscopic.core;

public final class StereoOptions {
    public static final StereoOptions INSTANCE = new StereoOptions();
    StereoOptions() {} // package-private for tests; production code uses INSTANCE
    public void load() {}
    public void save() {}
}
