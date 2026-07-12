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
