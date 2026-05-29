package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.core.StereoState;
import net.coderbot.iris.uniforms.CapturedRenderingState;
import net.coderbot.iris.uniforms.SystemTimeUniforms;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Port of sbs2 commit ed335bff. Skips setTickDelta and TIMER.beginFrame on the RIGHT
 * eye so they freeze to the LEFT eye's snapshot. COUNTER (in Angelica's shaders mixin
 * alongside these two calls) ticks unconditionally — not redirected.
 *
 * <p>Both calls live inside Angelica's own {@code iris$beginRender} @Inject at HEAD of
 * {@code renderWorld(FJ)V}. After Mixin merge they become regular INVOKEs in the target
 * method body, and our @Redirect lands on them regardless of which mixin emitted them.
 */
@Mixin(value = EntityRenderer.class, priority = 1050)
public abstract class MixinEntityRenderer_StereoTimerFreeze {

    @Redirect(
        method = "renderWorld(FJ)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/coderbot/iris/uniforms/CapturedRenderingState;setTickDelta(F)V",
            remap = false
        )
    )
    private void stereoscopic$freezeTickDeltaOnRightEye(CapturedRenderingState state, float partialTicks) {
        if (StereoState.INSTANCE.getCurrentEye() == StereoState.Eye.RIGHT) return;
        state.setTickDelta(partialTicks);
    }

    @Redirect(
        method = "renderWorld(FJ)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/coderbot/iris/uniforms/SystemTimeUniforms$Timer;beginFrame(J)V",
            remap = false
        )
    )
    private void stereoscopic$freezeTimerOnRightEye(SystemTimeUniforms.Timer timer, long nanoTime) {
        if (StereoState.INSTANCE.getCurrentEye() == StereoState.Eye.RIGHT) return;
        timer.beginFrame(nanoTime);
    }
}
