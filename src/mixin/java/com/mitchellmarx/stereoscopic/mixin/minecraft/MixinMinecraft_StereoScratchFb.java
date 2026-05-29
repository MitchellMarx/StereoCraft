package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.render.PerEyeScratchFb;
import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Per-eye world-FB substitution. HEAD inject on {@code Minecraft.getFramebuffer()}.
 * Returns the {@link PerEyeScratchFb} scratch instance whenever
 * {@code PerEyeScratchFb.isActive()} is true.
 *
 * <p>The two-pass loop in {@code MixinEntityRenderer_Stereo} sets {@code setActive(true)}
 * around each eye's {@code renderWorld} call. Inside that scope:
 * <ul>
 *   <li>Iris's pipeline allocations + main-FB references resolve to scratch.</li>
 *   <li>Embeddium/Celeritas chunk passes read the scratch as main, write to scratch.</li>
 *   <li>Distant Horizons + vanilla world rendering all hit scratch transparently.</li>
 * </ul>
 *
 * <p>Outside the per-eye window (HUD, GUI, post-frame, server thread), the inject is a
 * no-op and {@code getFramebuffer()} returns the real main FB as normal.
 *
 * <p>Why HEAD-inject on getFramebuffer rather than @Redirect at every call site: there are
 * many call sites — vanilla, Iris, Embeddium, DH, Sodium, plus mod code we don't control.
 * A single intercept point catches all of them.
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraft_StereoScratchFb {

    @Inject(method = "getFramebuffer", at = @At("HEAD"), cancellable = true)
    private void stereoscopic$redirectToScratch(CallbackInfoReturnable<Framebuffer> cir) {
        if (!PerEyeScratchFb.isActive()) return;
        final Framebuffer scratch = PerEyeScratchFb.get();
        if (scratch == null) return;
        cir.setReturnValue(scratch);
    }
}
