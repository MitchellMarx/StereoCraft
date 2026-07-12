package com.mitchellmarx.stereoscopic.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mitchellmarx.stereoscopic.core.StereoMath;
import com.mitchellmarx.stereoscopic.core.StereoOptions;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Widen Sodium's strict per-section frustum test to cover BOTH eye frusta
 * (issue #4 — world blocks flickering in SBS).
 *
 * <p>On 1.21.1 Sodium (0.6.13) the chunk-graph BFS runs once per stereo frame
 * (this port skips {@code setupTerrain} on the RIGHT eye and restores the mono
 * camera position across it; see {@link MixinSodiumWorldRenderer}), so a single
 * visible-section list — built from the mono camera — must serve both eyes.
 * The strict gate is {@code OcclusionCuller.isWithinFrustum}, which calls
 * {@code Viewport.isBoxVisible(centerX, centerY, centerZ, 9.125, 9.125, 9.125)}
 * — an 8-block section half-extent plus a 1.125-block pad. That pad absorbs the
 * {@code ipd/2} origin offset (~0.03 blocks) but not the convergence shear tilt
 * at distance, so edge sections only one eye can see get culled and pop as the
 * camera turns.
 *
 * <p>Fix: wrap the strict-gate {@code isBoxVisible} call and grow the three
 * half-extents by {@link StereoMath#cullMargin} — the exact worst-case lateral
 * divergence of an eye frustum from the mono one at that section's
 * camera-relative horizontal distance. {@code isBoxVisible} subtracts the baked
 * {@link CameraTransform} to form its own camera-relative center, so we
 * reconstruct that same center here to size the distance-dependent margin. The
 * looser nearby-section path ({@code isWithinNearbySectionFrustum}, 10.125-block
 * radius) is a different call site and stays untouched — it only runs at short
 * range where the divergence is far below its margin.
 *
 * <p>This is added ALONGSIDE the mono-position restore in
 * {@link MixinSodiumWorldRenderer}, not as a replacement: the restore centers
 * the cull frustum between the eyes (handling the origin offset symmetrically),
 * and this margin covers the shear divergence the pad cannot.
 *
 * <p><b>Why the margin uses IPD/2 and not the full IPD.</b> The correct
 * constant depends on which camera the cull frustum is baked from. Sodium builds
 * the {@code Viewport}/frustum inside {@code setupTerrain}'s body, i.e. between
 * {@link MixinSodiumWorldRenderer}'s HEAD inject (which restores the MONO camera
 * position) and its RETURN inject (which restores the eye position) — so on this
 * branch the frustum is baked from the MONO camera even though extraction lives
 * inside the per-eye loop. Each eye's real render frustum then diverges from
 * that mono cull frustum by only {@code IPD/2} of origin offset (plus half the
 * shear), and the symmetric section-test expansion covers both eyes at once.
 * (If a branch instead baked the cull frustum from ONE eye's position and had
 * the other eye reuse the list — extraction inside the per-eye loop with no
 * mono restore — the reused eye would diverge by the FULL {@code IPD} and the
 * margin would need {@code IPD + (IPD/convergence)·dist}. That is NOT this
 * branch: the RIGHT-eye {@code setupTerrain} is skipped and LEFT's runs from the
 * restored mono position, so {@link StereoMath#cullMargin} uses the {@code IPD/2}
 * form.)
 *
 * <p>Gated on live {@link StereoOptions} rather than the frame snapshot so the
 * predicate never depends on when the per-frame state is armed. When stereo is
 * off the wrap is a single enum check and a straight pass-through.
 *
 * <p>{@code remap = false}: {@code OcclusionCuller} / {@code Viewport} are
 * Sodium's own classes, not Minecraft symbols — matches the convention the
 * Iris mixins on this branch use for third-party targets.
 */
@Mixin(value = net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller.class, remap = false)
public abstract class MixinOcclusionCuller {

    @WrapOperation(
        method = "isWithinFrustum(Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;)Z",
        at = @At(value = "INVOKE",
                 target = "Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;isBoxVisible(IIIFFF)Z",
                 remap = false)
    )
    private static boolean stereoscopic$widenSectionTestForStereo(
            Viewport viewport, int x, int y, int z, float w, float h, float d,
            Operation<Boolean> original) {
        StereoOptions o = StereoOptions.INSTANCE;
        if (!o.mode.isActive()) {
            return original.call(viewport, x, y, z, w, h, d);
        }
        // isBoxVisible re-derives this same camera-relative center internally
        // (section coord minus the baked CameraTransform); mirror it here to
        // size the distance-dependent shear margin.
        CameraTransform t = viewport.getTransform();
        float cx = (x - t.intX) - t.fracX;
        float cz = (z - t.intZ) - t.fracZ;
        float horizontalDist = (float) Math.sqrt(cx * (double) cx + cz * (double) cz);
        float extra = StereoMath.cullMargin(o.ipd, o.convergence, horizontalDist);
        return original.call(viewport, x, y, z, w + extra, h + extra, d + extra);
    }
}
