package com.mitchellmarx.stereoscopic.mixin.sodium;

import com.mitchellmarx.stereoscopic.config.StereoConfig;
import com.mitchellmarx.stereoscopic.core.StereoState;
import org.embeddedt.embeddium.impl.render.viewport.CameraTransform;
import org.embeddedt.embeddium.impl.render.viewport.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Widen Embeddium's per-section frustum test to cover BOTH eye frusta
 * (fixes SBS world-block flickering — the {@code project_sodium_chunk_skip}
 * handoff bug).
 *
 * <p>On this branch chunk visibility is computed ONCE per frame: Angelica's
 * {@code MixinRenderGlobal.clipRenderersByFrustum} {@code @Overwrite} runs the
 * Embeddium occlusion BFS, and {@link
 * com.mitchellmarx.stereoscopic.mixin.sodium.MixinRenderGlobal_StereoChunkSkip}
 * cancels it on the RIGHT eye so RIGHT reuses the LEFT eye's visible-section
 * list. The cull frustum is therefore the LEFT eye's: {@code
 * MixinFrustrum.sodium$createViewport} bakes the {@code FrustumIntersection}
 * from the live GL projection*modelview, which already carry this frame's
 * per-eye IPD offset ({@code MixinEntityRenderer_StereoCamera}'s modelview
 * {@code glTranslatef(dx,0,0)}) and convergence shear. Sections that only the
 * RIGHT eye's frustum can see (its left edge extends one full IPD — plus the
 * shear tilt at distance — past the LEFT eye's left edge) get culled by the
 * LEFT-eye test and pop in/out as the camera turns: the reported flicker.
 *
 * <p>Fix: at the strict section gate — {@code OcclusionCuller.isWithinFrustum}
 * → {@code Viewport.isBoxVisible(int,int,int,float)} (the 9.125-block
 * {@code CHUNK_SECTION_SIZE} padded-radius test) — expand the tested box by an
 * extra margin equal to the worst-case lateral divergence of the RIGHT eye
 * frustum from the LEFT one: {@code ipd + (ipd/convergence)·horizontalDistance}.
 * The box is expanded symmetrically (Embeddium's {@code isBoxVisible} pads all
 * axes), which conservatively covers the RIGHT eye's left-side overhang. The
 * expansion is in camera-relative coordinates ({@code Viewport} subtracts its
 * {@code CameraTransform} before the frustum test), matching the units the
 * divergence is expressed in.
 *
 * <p>Note the margin is the FULL IPD (and full shear slope {@code ipd/conv}),
 * not the half-IPD a MONO cull frustum would use: here the single cull frustum
 * is one eye's, so it must reach across the entire LEFT→RIGHT divergence, which
 * is twice the mono→eye divergence. With convergence off (parallel-axis mode)
 * the shear term is zero and the flat IPD offset alone covers it.
 *
 * <p>Gated on {@link StereoState#isActive()} — correct here because the cull
 * runs inside the per-eye {@code renderWorld} loop, after {@code beginFrame()}
 * and {@code setEye(LEFT)} have armed the frame state. (Applying the widen
 * during a shared Iris shadow-map cull is harmless: a few extra sections in the
 * shadow pass, never a visual artifact.)
 *
 * <p>Plain Sponge {@code @Redirect} — MixinExtras is not on this branch's
 * compile classpath — wrapping the internal delegation from the 4-arg section
 * test to the 6-arg {@code isBoxVisible(int,int,int,float,float,float)} worker.
 */
@Mixin(value = Viewport.class, remap = false)
public abstract class MixinViewport {

    @Redirect(
        method = "isBoxVisible(IIIF)Z",
        at = @At(value = "INVOKE",
                 target = "Lorg/embeddedt/embeddium/impl/render/viewport/Viewport;isBoxVisible(IIIFFF)Z")
    )
    private boolean stereoscopic$widenSectionTestForStereo(Viewport self,
                                                           int intOriginX, int intOriginY, int intOriginZ,
                                                           float sizeX, float sizeY, float sizeZ) {
        if (!StereoState.INSTANCE.isActive()) {
            return self.isBoxVisible(intOriginX, intOriginY, intOriginZ, sizeX, sizeY, sizeZ);
        }
        final float ipd = StereoConfig.stereoIpd;
        float extra = ipd;
        final float convergence = StereoConfig.stereoConvergence;
        if (convergence > 0f) {
            // Camera-relative section-center coordinates (same math Viewport uses
            // internally); shear divergence is horizontal, so only X/Z contribute.
            final CameraTransform t = self.getTransform();
            final float fx = (float) (intOriginX - t.intX) - t.fracX;
            final float fz = (float) (intOriginZ - t.intZ) - t.fracZ;
            final float horizontalDist = (float) Math.sqrt(fx * (double) fx + fz * (double) fz);
            extra += (ipd / convergence) * horizontalDist;
        }
        return self.isBoxVisible(intOriginX, intOriginY, intOriginZ,
                                 sizeX + extra, sizeY + extra, sizeZ + extra);
    }
}
