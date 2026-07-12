package com.mitchellmarx.stereoscopic.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Widen Sodium's per-section frustum test to cover the OTHER eye's frustum
 * (issue #4 — world blocks flickering in SBS). Works alongside
 * {@link MixinSodiumWorldRenderer}, which skips {@code setupTerrain} on the
 * RIGHT eye and mono-pos-restores the live {@code Camera} for the duration of
 * LEFT's run; this mixin fixes the one thing that machinery cannot — the
 * pre-baked {@code Viewport} frustum.
 *
 * <p><b>What the cull frustum is on THIS branch (26.1.2).</b> Unlike 26.2 —
 * where chunk visibility is computed once per frame from the MONO camera
 * before the per-eye {@code renderLevel} loop — on 26.1.2 {@code setupTerrain}
 * runs INSIDE the per-eye WORLD pass. {@code MixinSodiumWorldRenderer} cancels
 * it on the RIGHT eye, so culling happens exactly once, during the LEFT eye's
 * pass. At that point {@code MixinGameRenderer.stereoscopic$twoPassRenderLevel}
 * has already set {@code cameraRenderState.pos} to the LEFT eye
 * ({@code -IPD/2} along camera-right) and {@code .projectionMatrix} to the LEFT
 * convergence shear. Sodium's {@code Viewport} is baked from the vanilla
 * {@code Frustum} via {@code FrustumMixin.sodium$createViewport()} — it copies
 * that frustum's {@code FrustumIntersection} planes and {@code camX/camY/camZ}
 * origin, both derived from the (LEFT-eye) {@code cameraRenderState}. So the
 * cull frustum here is the LEFT eye's, NOT the mono one. (The mono-pos restore
 * in {@code MixinSodiumWorldRenderer} only rewrites the live {@code Camera}'s
 * direct {@code getPosition()} reads inside setupTerrain — nearby-section /
 * prepareFrame logic — it does not touch this already-baked Viewport.)
 *
 * <p><b>Divergence to cover, and why the margin is full-IPD (2× the 26.2
 * value).</b> The RIGHT eye renders with a frustum offset from the LEFT cull
 * frustum by (a) an origin shift of the FULL IPD along camera-right (from
 * {@code -IPD/2} to {@code +IPD/2}) and (b) the convergence shear, whose slope
 * flips sign between eyes, giving a relative horizontal tilt of
 * {@code (IPD/2)/convergence + (IPD/2)/convergence = IPD/convergence}
 * (world-lateral blocks per block of view depth). Sections that only the RIGHT
 * eye can see get culled by the LEFT test → they pop in/out as the camera
 * turns = the reported flickering. (26.2's mono cull frustum sits halfway, so
 * each eye is only {@code IPD/2} away and its margin is {@code IPD/2 +
 * (IPD/2)/convergence · d}; here the far eye is a full {@code IPD} away, hence
 * double.)
 *
 * <p><b>Fix.</b> At the strict gate ({@code Viewport.isBoxVisible(III)} →
 * {@code Frustum.testSection(FFF)}) substitute {@code testSectionExpanded} with
 * an extra margin of {@code IPD + (IPD/convergence) · horizontalDistance} — the
 * worst-case lateral divergence of the RIGHT-eye frustum from the LEFT cull
 * frustum at that section's depth (horizontal distance ≥ view depth, so this is
 * conservative; the symmetric expansion over-covers the LEFT side, which is
 * harmless). With convergence off (parallel-axis mode) the shear term is zero
 * and the IPD origin term alone covers the offset. The looser nearby-section
 * path ({@code isBoxVisibleLooser}, +1.0 extra) only runs at short range where
 * the divergence is far below its margin — untouched.
 *
 * <p><b>Gate.</b> Uses the per-frame {@link StereoState} snapshot
 * ({@code isActive} + {@code getFrameIpd}/{@code getFrameConvergence}), NOT
 * live {@link com.mitchellmarx.stereoscopic.core.StereoOptions}: culling runs
 * inside the armed frame on this branch (after {@code GameRenderer.render}'s
 * HEAD inject calls {@code beginFrame}), so the frame snapshot is populated and
 * authoritative — and the margin must be computed from the SAME ipd/convergence
 * that produced this frame's eye offsets ({@code getEyeOffset} /
 * {@code twoPassRenderLevel} both read the frame snapshot), immune to a
 * mid-frame config flip. This is the opposite choice from the 26.2 reference,
 * which must read live options because its cull runs before the frame is armed.
 */
@Mixin(value = Viewport.class, remap = false)
public abstract class MixinViewport {

    @WrapOperation(
        method = "isBoxVisible(III)Z",
        at = @At(value = "INVOKE",
                 target = "Lnet/caffeinemc/mods/sodium/client/render/viewport/frustum/Frustum;testSection(FFF)Z")
    )
    private boolean stereoscopic$widenSectionTestForStereo(Frustum frustum,
                                                           float x, float y, float z,
                                                           Operation<Boolean> original) {
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive()) {
            return original.call(frustum, x, y, z);
        }
        float ipd = s.getFrameIpd();
        float convergence = s.getFrameConvergence();
        // Full IPD (not IPD/2): the cull frustum is the LEFT eye's, so the far
        // (RIGHT) eye is a full IPD + full relative shear away — see class doc.
        float extra = ipd;
        if (convergence > 0f) {
            // x/y/z are already camera-relative (Viewport subtracts its baked
            // CameraTransform before this call); shear is horizontal-only.
            float horizontalDist = (float) Math.sqrt(x * (double) x + z * (double) z);
            extra += (ipd / convergence) * horizontalDist;
        }
        return frustum.testSectionExpanded(x, y, z, extra);
    }
}
