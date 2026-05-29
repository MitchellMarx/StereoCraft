package com.mitchellmarx.stereoscopic.compat.xaero;

import cpw.mods.fml.common.Loader;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Xaero's Minimap has a {@code XaeroMinimapCore.beforeIngameGuiRender(float)} hook that base
 * Angelica's {@code HUDCaching.renderCachedHud} calls at the top of the cached HUD path. When
 * stereo is active we drive {@code renderCachedHud} twice per frame (once per eye region), but
 * the Xaero hook is per-frame state setup — it must fire only once.
 *
 * <p>{@code MixinHUDCaching_Stereo}'s {@code @Redirect} on that call site routes through this
 * shim's frame-token gate so the hook is invoked once per frame in stereo while still passing
 * through unchanged in non-stereo. Reflective so we don't need Xaero on our compileOnly
 * classpath — mirrors {@link com.mitchellmarx.stereoscopic.compat.chromatictooltips.ChromaticTooltipsCompat}.
 *
 * <p>The handle lookup runs once at mod init and surfaces API drift loudly via
 * {@link ReflectiveOperationException}; the runtime call path is exception-free after
 * {@code findStatic} succeeded at init.
 */
public final class XaeroCompat {

    // Verified against Angelica-sbs2/src/main/java/com/gtnewhorizons/angelica/compat/ModStatus.java
    // line 41: Loader.isModLoaded("XaeroMinimap").
    private static final String MOD_ID = "XaeroMinimap";
    private static final String XAERO_FQCN = "xaero.common.core.XaeroMinimapCore";
    private static final String METHOD_NAME = "beforeIngameGuiRender";

    private static MethodHandle handle;

    private XaeroCompat() {}

    /**
     * Resolves and caches the reflective handle. Throws if Xaero is loaded but its API surface
     * has drifted — caller should fail mod init rather than silently degrade.
     */
    public static void init() throws ReflectiveOperationException {
        if (!Loader.isModLoaded(MOD_ID)) return;
        final Class<?> cls = Class.forName(XAERO_FQCN);
        handle = MethodHandles.lookup().findStatic(
            cls, METHOD_NAME, MethodType.methodType(void.class, float.class));
    }

    /** Invoke Xaero's per-frame pre-render hook. No-op when Xaero isn't loaded. */
    public static void beforeIngameGuiRender(float partialTicks) {
        if (handle == null) return;
        try {
            handle.invokeExact(partialTicks);
        } catch (Throwable t) {
            // findStatic at init() verified the signature; reaching this implies the method
            // body itself threw. Surface to the caller — Xaero's own error path is the right
            // place to surface Xaero failures, but the stack will identify us as the redirect
            // and the underlying cause as Xaero.
            throw new AssertionError(t);
        }
    }

    public static boolean isLoaded() { return handle != null; }
}
