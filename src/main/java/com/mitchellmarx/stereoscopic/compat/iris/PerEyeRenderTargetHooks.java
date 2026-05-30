package com.mitchellmarx.stereoscopic.compat.iris;

// STUB — full Iris-compat impl lands in Task 12. Methods here exist to
// satisfy compile-time references from mixins ported in Tasks 7-9. The
// stub bodies are no-op safe defaults; behavior comes online with Task 12.
public final class PerEyeRenderTargetHooks {
    private PerEyeRenderTargetHooks() {}

    public static final boolean IRIS_PRESENT = false;

    public static void setActiveEye(int eyeIndex) {}
    public static int getActiveEye() { return 0; }
    public static void irisStartFrameBetweenEyes() {}
    public static void rebuildPipelineForStereoToggle() {}
    public static boolean hasIrisRenderTargets() { return false; }
    public static int wantedEyeCount() { return 1; }
    public static int currentEyeIndex() { return 0; }
}
