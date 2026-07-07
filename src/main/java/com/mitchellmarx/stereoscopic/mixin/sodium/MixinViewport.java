package com.mitchellmarx.stereoscopic.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mitchellmarx.stereoscopic.core.StereoOptions;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Widen Sodium's per-section frustum test to cover BOTH eye frusta
 * (issue #4 — world blocks flickering in SBS).
 *
 * <p>In 26.2, chunk visibility is computed once per frame during
 * {@code LevelExtractor.extract()} — before the per-eye {@code renderLevel}
 * loop, from the MONO camera state — and the {@code Viewport}'s frustum
 * planes are baked upstream from the vanilla {@code Frustum}
 * ({@code FrustumMixin.sodium$createViewport}). Each eye then renders with a
 * frustum that differs from that mono cull frustum by (a) an origin shift of
 * ±IPD/2 along the camera-right axis and (b) the convergence shear, which
 * tilts the frustum horizontally by a slope of {@code (IPD/2)/convergence}
 * (world-lateral blocks per block of view depth).
 *
 * <p>Sodium's strict {@code testSection} already carries a 1.125-block slack
 * ({@code CHUNK_SECTION_PADDED_RADIUS} = 9.125 vs the 8-block section
 * half-extent), which absorbs the IPD origin shift (~0.03 blocks) — but NOT
 * the shear at distance: at the default 0.064 m IPD / 4-block convergence the
 * tilt is 0.008·d, exceeding the slack beyond ~140 blocks. Sections near the
 * screen's left/right edges that only the (say) RIGHT eye's tilted frustum
 * can see get culled by the mono test → they pop in and out as the camera
 * turns = the reported block flickering.
 *
 * <p>Fix: at the strict gate ({@code Viewport.isBoxVisible} →
 * {@code Frustum.testSection}), substitute {@code testSectionExpanded} with
 * an extra margin of {@code IPD/2 + shearSlope · horizontalDistance} — the
 * exact worst-case lateral divergence of an eye frustum from the mono one at
 * that section's depth (horizontal distance ≥ view depth, so this is
 * conservative). With convergence off (parallel-axis mode) the shear term is
 * zero and IPD/2 alone covers the offset. The looser nearby-section path
 * ({@code isBoxVisibleLooser}, +1.0 extra) only runs at short range where
 * the divergence is far below its margin — untouched.
 *
 * <p>Gated on live {@link StereoOptions} — NOT {@code StereoState.isActive()}:
 * extraction runs before {@code GameRenderer.render}'s HEAD inject arms the
 * per-frame state, so the frame snapshot still reads inactive/stale here.
 *
 * <p>Replaces the 26.1.2 {@code MixinSodiumWorldRenderer} skip/restore pair:
 * the RIGHT-eye {@code setupTerrain} cancel is moot (setupTerrain no longer
 * runs per eye) and the mono-position restore never engaged in this
 * architecture (extraction runs outside the per-eye wrap, and the live
 * {@code Camera} is never IPD-shifted in the 26.x render-state model anyway).
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
        StereoOptions o = StereoOptions.INSTANCE;
        if (!o.mode.isActive()) {
            return original.call(frustum, x, y, z);
        }
        float ipdHalf = o.ipd * 0.5f;
        float extra = ipdHalf;
        if (o.convergence > 0f) {
            // x/y/z are already camera-relative (Viewport subtracts its baked
            // CameraTransform before this call); shear is horizontal-only.
            float horizontalDist = (float) Math.sqrt(x * (double) x + z * (double) z);
            extra += (ipdHalf / o.convergence) * horizontalDist;
        }
        return frustum.testSectionExpanded(x, y, z, extra);
    }
}
