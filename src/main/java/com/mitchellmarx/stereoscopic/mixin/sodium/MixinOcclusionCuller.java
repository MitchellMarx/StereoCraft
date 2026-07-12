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
 * (this port skips {@code setupTerrain} on the RIGHT eye; the RIGHT eye reuses
 * LEFT's visible-section list; see {@link MixinSodiumWorldRenderer}), so a
 * single cull list must serve both eyes. The strict gate is
 * {@code OcclusionCuller.isWithinFrustum}, which calls
 * {@code Viewport.isBoxVisible(centerX, centerY, centerZ, 9.125, 9.125, 9.125)}
 * — an 8-block section half-extent plus a 1.125-block pad. That pad cannot
 * absorb the per-eye divergence (see below), so edge sections only one eye can
 * see get culled and pop as the camera turns.
 *
 * <p>Fix: wrap the strict-gate {@code isBoxVisible} call and grow the three
 * half-extents by {@link StereoMath#cullMargin} — the exact worst-case lateral
 * divergence of the reused eye's frustum from the baked one at that section's
 * camera-relative horizontal distance. {@code isBoxVisible} subtracts the baked
 * {@link CameraTransform} to form its own camera-relative center, so we
 * reconstruct that same center here to size the distance-dependent margin. The
 * looser nearby-section path ({@code isWithinNearbySectionFrustum}, 10.125-block
 * radius) is a different call site and stays untouched — it only runs at short
 * range where the divergence is far below its margin.
 *
 * <p><b>Why the margin uses the FULL IPD (and full tilt), not the half.</b> The
 * correct constant depends on which camera the cull frustum is baked from, and
 * on this branch it is baked from ONE eye — the LEFT — not from mono. Traced in
 * bytecode: {@code GameRenderer.renderLevel} builds the cull frustum via
 * {@code prepareCullFrustum(camera.getPosition(), modelView,
 * getProjectionMatrix(...))} while (a) the camera is offset to the LEFT eye and
 * (b) our convergence-shear {@code ModifyReturnValue} on
 * {@code getProjectionMatrix} is live ({@code isInWorldPass}, eye = LEFT) — so
 * both the frustum's ORIGIN and its PLANE TILT are LEFT's. Sodium's
 * {@code FrustumMixin.sodium$createViewport} copies that vanilla frustum's
 * {@code camX/Y/Z} + {@code intersection} into the {@code Viewport} BEFORE
 * {@code setupTerrain} runs, and {@code RenderSectionManager.createTerrainRenderList}
 * passes that same {@code Viewport} straight to {@code OcclusionCuller.findVisible}.
 * The RIGHT eye reuses the list, so it diverges from the LEFT-baked frustum by
 * the FULL eye separation: {@code ipd} of origin shift plus a tilt-slope
 * difference of {@code 2s = ipd/convergence} (LEFT's {@code +s} vs RIGHT's
 * {@code −s}). Hence {@link StereoMath#cullMargin} uses {@code ipd +
 * (ipd/convergence)·dist}, not the half-separation form.
 *
 * <p>The mono-position restore in {@link MixinSodiumWorldRenderer} does NOT
 * change this: it only reaches {@code RenderSectionManager.updateCameraState}
 * (the section-manager's stored camera, used for BFS start / incremental graph
 * state), not the {@code Viewport} that {@code findVisible} tests against — that
 * {@code Viewport} was already built from the LEFT-baked vanilla frustum. (The
 * half-separation form would apply only to a MONO-baked cull frustum, e.g. the
 * 26.2/CameraRenderState port where extraction runs once from the mono state
 * outside the per-eye loop.)
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
