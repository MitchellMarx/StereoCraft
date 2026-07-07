package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SkyRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Make SkyRenderer's render target LIVE instead of captured. 26.2's
 * SkyRenderer takes a {@code RenderTarget} in its constructor and stores it
 * in a final field — and {@code LevelRenderer.addSkyPass} (re)constructs it
 * with {@code gameRenderer.mainRenderTarget()} INSIDE the level render, i.e.
 * while our per-eye scratch substitution is active. The captured scratch
 * instance then outlives its buffers: a window resize makes
 * {@code PerEyeRenderer.ensureScratchFb} swap scratch buffers and the sky
 * pass NPEs on a null texture view ("Cannot invoke GpuTextureView.getWidth
 * ... colorTexture is null" — the resize crash). A captured target is wrong
 * in BOTH directions anyway: captured scratch breaks mono sky after stereo
 * is toggled off; captured real target would bypass the scratch during eye
 * passes and lose the sky in stereo.
 *
 * <p>Every other 26.2 consumer (CloudRenderer, CubeMap, WorldBorderRenderer,
 * OutputTarget, ...) fetches {@code mainRenderTarget()} per use — SkyRenderer
 * is the only constructor capture. Redirect all reads of the field to the
 * live accessor, which resolves through {@link MixinGameRenderer}'s
 * substitution to the correct target for the current pass.
 */
@Mixin(SkyRenderer.class)
public abstract class MixinSkyRenderer {

    @Redirect(
        method = "*",
        at = @At(value = "FIELD",
                 target = "Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;",
                 opcode = Opcodes.GETFIELD)
    )
    private RenderTarget stereoscopic$liveRenderTarget(SkyRenderer self) {
        return Minecraft.getInstance().gameRenderer.mainRenderTarget();
    }
}
