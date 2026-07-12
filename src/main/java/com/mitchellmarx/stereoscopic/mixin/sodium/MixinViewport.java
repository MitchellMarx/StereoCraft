package com.mitchellmarx.stereoscopic.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Widen Sodium's per-section frustum test to cover BOTH eye frusta
 * (issue #4 — world blocks flickering in SBS).
 *
 * <p><b>Why this is needed.</b> Each eye renders with a frustum that differs
 * from the section-cull frustum by (a) an origin shift of ±IPD/2 along the
 * camera-right axis and (b) the convergence shear, which tilts the frustum
 * horizontally by a slope of {@code (IPD/2)/convergence} (world-lateral blocks
 * per block of view depth). Sodium's strict {@code testSection} carries only a
 * ~1.125-block slack ({@code CHUNK_SECTION_PADDED_RADIUS} = 9.125 vs the
 * 8-block section half-extent). That absorbs the IPD origin shift (~0.03
 * blocks) but NOT the shear at distance: at the default 0.064 m IPD /
 * 4-block convergence the tilt is 0.008·d per eye, exceeding the slack past
 * ~140 blocks. Edge sections that only one eye's tilted frustum can see get
 * culled → they pop in and out as the camera turns = the reported flickering.
 *
 * <p><b>Reference frustum on THIS branch is ONE eye, not mono.</b> Unlike the
 * MC 26.2 port — where chunk visibility is extracted once per frame from the
 * MONO camera <em>before</em> the per-eye loop, so each eye diverges from that
 * mono cull frustum by only ±(IPD/2 + shear·d) and a half-IPD margin suffices —
 * 1.21.11 still runs {@code setupTerrain} INSIDE the per-eye {@code renderWorld}
 * loop. The vanilla {@code Frustum} handed to {@code SodiumWorldRenderer.setupTerrain}
 * (and turned into this {@code Viewport} via {@code FrustumMixin.sodium$createViewport})
 * is built from the LEFT eye's IPD-shifted position and sheared projection —
 * planes AND transform origin are the LEFT eye's. RIGHT-eye {@code setupTerrain}
 * is then skipped ({@link com.mitchellmarx.stereoscopic.mixin.sodium.MixinSodiumWorldRenderer}
 * + {@code SecondEyeSkipHooks}) and RIGHT reuses LEFT's visible-section list.
 * The mono-position restore in that mixin only rewrites {@code setupTerrain}'s
 * direct {@code Camera.getCameraPos()} reads (occlusion-BFS start, nearby-section
 * logic); it does NOT re-bake the already-constructed {@code Viewport} frustum.
 *
 * <p><b>Margin.</b> Because the cull frustum is the LEFT eye's, the sections we
 * must additionally admit are those the RIGHT eye can see, and RIGHT diverges
 * from LEFT by the <em>full</em> inter-eye separation: origin {@code IPD}
 * (from −IPD/2 to +IPD/2) plus the full shear-tilt difference {@code IPD/convergence}
 * per block of view depth. So the extra margin here is
 * {@code IPD + (IPD/convergence)·horizontalDistance} — exactly double the mono-
 * reference 26.2 margin, precisely because the reference is one eye rather than
 * the center. The symmetric {@code testSectionExpanded} also over-admits a few
 * sections just off the LEFT edge that neither eye sees; that is harmless (pure
 * conservative slack, same character as Sodium's own section padding). With
 * convergence off (parallel-axis) the shear term is zero and {@code IPD} alone
 * covers the pure origin offset. {@code x/y/z} are camera-relative here (the
 * {@code Viewport} subtracts its baked transform before this call), so
 * {@code horizontalDistance} approximates view depth (≥ it, hence conservative).
 *
 * <p><b>Gated on {@link StereoState}</b> (per-frame snapshot), NOT live
 * {@code StereoOptions}: on 1.21.11 section culling runs inside the per-eye
 * {@code renderWorld} wrap, i.e. AFTER {@code GameRenderer.render}'s HEAD inject
 * has armed the frame — so the snapshot (and its IPD/convergence, which drive
 * the actual per-eye offsets this frame) is live and authoritative here. This
 * is the opposite gate choice from the 26.2 port, where extraction runs before
 * arming and must read live options.
 *
 * <p>This ADDS the frustum-plane margin alongside the existing
 * {@link com.mitchellmarx.stereoscopic.mixin.sodium.MixinSodiumWorldRenderer}
 * skip/mono-restore pair, which stays load-bearing on this MC version — the two
 * fixes address different halves of the same symptom (direct camera reads vs
 * baked frustum planes).
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
        // Full inter-eye divergence: the cull frustum is the LEFT eye's, so the
        // RIGHT eye (which reuses this same visible list) is IPD to the right
        // plus the full shear tilt. See class javadoc for the mono-vs-one-eye
        // reference distinction from the 26.2 port.
        float ipd = s.getFrameIpd();
        float extra = ipd;
        float conv = s.getFrameConvergence();
        if (conv > 0f) {
            float horizontalDist = (float) Math.sqrt(x * (double) x + z * (double) z);
            extra += (ipd / conv) * horizontalDist;
        }
        return frustum.testSectionExpanded(x, y, z, extra);
    }
}
