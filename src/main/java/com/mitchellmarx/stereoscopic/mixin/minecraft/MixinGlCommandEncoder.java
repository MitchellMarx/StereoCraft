package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.opengl.DirectStateAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Fix vanilla MC bug in {@link GlCommandEncoder#copyTextureToTexture}: it
 * passes raw (w, h) where {@code glBlitFramebuffer} expects ENDPOINT coords
 * (X1 = X0 + width). Only works when src/dst origins are 0 (then 0+w == w).
 *
 * <p>Bytecode evidence — slots 5/6/9/10 receive w/h/w/h instead of
 * srcX+w/srcY+h/dstX+w/dstY+h:
 * <pre>
 *   416: iload 8  // w → slot 5 (srcX1)   ← should be srcX + w
 *   418: iload 9  // h → slot 6 (srcY1)   ← should be srcY + h
 *   424: iload 8  // w → slot 9 (dstX1)   ← should be dstX + w
 *   426: iload 9  // h → slot 10 (dstY1)  ← should be dstY + h
 * </pre>
 *
 * <p>Bit our stereo per-eye blur: blitting back into the right half of main
 * with {@code dstX = halfW, w = halfW} gave {@code dstX0 = halfW, dstX1 = halfW},
 * zero-width rect, no pixels written.
 */
// GlCommandEncoder is package-private in com.mojang.blaze3d.opengl — target by name.
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
public abstract class MixinGlCommandEncoder {

    @WrapOperation(
        method = "copyTextureToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/textures/GpuTexture;IIIIIII)V",
        at = @At(value = "INVOKE",
                 target = "Lcom/mojang/blaze3d/opengl/DirectStateAccess;blitFrameBuffers(IIIIIIIIIIII)V")
    )
    private void stereoscopic$fixBlitEndpoints(
            DirectStateAccess bm,
            int fb1, int fb2,
            int srcX0, int srcY0, int bogusSrcX1, int bogusSrcY1,
            int dstX0, int dstY0, int bogusDstX1, int bogusDstY1,
            int mask, int filter,
            Operation<Void> original,
            // copyTextureToTexture args: (src, dst, mip, dstX, dstY, srcX, srcY, w, h)
            @Local(argsOnly = true, ordinal = 5) int width,
            @Local(argsOnly = true, ordinal = 6) int height) {
        original.call(bm, fb1, fb2,
            srcX0, srcY0, srcX0 + width, srcY0 + height,
            dstX0, dstY0, dstX0 + width, dstY0 + height,
            mask, filter);
    }
}
