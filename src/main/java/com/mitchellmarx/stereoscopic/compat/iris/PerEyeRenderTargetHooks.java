package com.mitchellmarx.stereoscopic.compat.iris;

// STUB — full Iris-compat impl lands in Task 12. PerEyeRenderer (Task 5)
// references setActiveEye() and irisStartFrameBetweenEyes(); both no-op until
// the real Iris hooks exist. Keep the public surface here minimal — Task 12
// replaces this file wholesale.
public final class PerEyeRenderTargetHooks {
    private PerEyeRenderTargetHooks() {}

    public static void setActiveEye(int eyeIndex) {}
    public static void irisStartFrameBetweenEyes() {}
}
