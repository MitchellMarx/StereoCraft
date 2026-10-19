package com.mitchellmarx.stereoscopic.mixin.iris;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.coderbot.iris.pipeline.HandRenderer;
import org.lwjgl.BufferUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.FloatBuffer;

/**
 * Stereo-aware modelview offset for the held item. Mirrors sbs2 stereo-sbs-2's
 * cumulative edit to {@code HandRenderer.setupGlState} (commit e4345194 added a
 * two-offset version; a later sbs2 commit dropped the projection-space offset and
 * switched the modelview offset to use the world's per-eye {@code getEyeOffset}).
 *
 * <p>Why modelview-only with {@code getEyeOffset}: the projection-space anaglyph
 * value 0.07f produced hundreds of pixels of per-eye disparity at the hand's close
 * camera distance — un-convergent for SBS. {@code getEyeOffset} (ipd/2) keeps the
 * held item's disparity consistent with the world geometry around it instead of
 * vanilla anaglyph's hand-specific 0.1f exaggeration.
 */
@Mixin(value = HandRenderer.class, remap = false)
public abstract class MixinHandRenderer_StereoDepth {

    @Inject(
        method = "setupGlState",
        at = @At(
            value = "INVOKE",
            target = "Lcom/gtnewhorizons/angelica/glsm/GLStateManager;glLoadIdentity()V",
            ordinal = 1,
            shift = At.Shift.AFTER
        )
    )
    private void stereoscopic$applyHandModelviewOffset(CallbackInfo ci) {
        if (!StereoState.INSTANCE.isActive()) return;
        final float dx = StereoState.INSTANCE.getEyeOffset();
        if (dx != 0f) {
            GLStateManager.glTranslatef(dx, 0f, 0f);
        }
    }

    /**
     * SBS_FULL aspect for the shader hand pass. {@code setupGlState} calls {@code gluPerspective}
     * with the full-window aspect, same as vanilla {@code renderHand}; double m00 afterwards so the
     * held item renders at the per-eye {@code (W/2)/H} aspect. Same diag(2,1,1,1) post-multiply as
     * MixinEntityRenderer_StereoCamera's setupCameraTransform inject.
     */
    @Inject(
        method = "setupGlState",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/util/glu/Project;gluPerspective(FFFF)V",
            ordinal = 0,
            shift = At.Shift.AFTER
        )
    )
    private void stereoscopic$applyFullSbsHandAspect(CallbackInfo ci) {
        if (!StereoState.INSTANCE.isActive()) return;
        if (!StereoState.INSTANCE.getFrameMode().isFull()) return;
        final FloatBuffer aspect = BufferUtils.createFloatBuffer(16);
        aspect.put(new float[] {
            2f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        });
        aspect.flip();
        GLStateManager.glMultMatrix(aspect);
    }
}
