package com.mitchellmarx.stereoscopic.mixin.iris;

import com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.coderbot.iris.gl.framebuffer.GlFramebuffer;
import net.coderbot.iris.gl.texture.DepthBufferFormat;
import net.coderbot.iris.gl.texture.InternalTextureFormat;
import net.coderbot.iris.rendertarget.DepthTexture;
import net.coderbot.iris.rendertarget.RenderTarget;
import net.coderbot.iris.rendertarget.RenderTargets;
import net.coderbot.iris.shaderpack.PackDirectives;
import net.coderbot.iris.shaderpack.PackRenderTargetDirectives;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

/**
 * Per-eye Iris {@link RenderTargets}. For each colortex slot we maintain a sibling
 * {@link RenderTarget}; for the two Iris-owned intermediate depth textures
 * ({@code noTranslucents}, {@code noHand}) we maintain a sibling {@link DepthTexture}.
 *
 * <p>On eye switch we walk every Iris-owned {@link GlFramebuffer} and rebind color attachments
 * to the active eye's texture ids via {@link GlFramebuffer#addColorAttachment(int, int)} (which
 * does {@code glFramebufferTexture2D} directly — no bind dance), swap the depth-tex field
 * pointers to the active eye's siblings, and re-attach {@code noTranslucentsDestFb} /
 * {@code noHandDestFb} depth to the active eye's depth sibling.
 *
 * <p>Allocation failure downgrades to single-bank operation; non-stereo paths see the original
 * LEFT-bank state and the {@code targets[]} array stays valid for direct field reads.
 *
 * <p>Why this matters: Iris's temporal slots (Complementary's colortex7 PBR reflections cache,
 * colortex2 TAA history, BSL's gbuffer history) persist across frames inside the colortex bank.
 * Running the Iris pipeline twice per frame against a SINGLE bank means RIGHT-eye writes
 * overwrite LEFT-eye's frame-N state before frame-N+1 LEFT reads it as {@code prevRef} —
 * cross-eye contamination, surfaced as wrong-eye reflections + smeared TAA when the camera
 * stops moving. Sibling banks give each eye its own temporal storage.
 */
@Mixin(value = RenderTargets.class, remap = false)
public abstract class MixinRenderTargets implements PerEyeRenderTargetHooks.EyeAwareRenderTargets {

    private static final Logger LOGGER = LogManager.getLogger("StereoscopicRT");

    @Shadow @Final private RenderTarget[] targets;
    @Shadow @Final private List<GlFramebuffer> ownedFramebuffers;
    @Shadow @Final private GlFramebuffer noTranslucentsDestFb;
    @Shadow @Final private GlFramebuffer noHandDestFb;
    @Mutable @Shadow @Final private DepthTexture noTranslucents;
    @Mutable @Shadow @Final private DepthTexture noHand;
    @Shadow private int cachedWidth;
    @Shadow private int cachedHeight;
    @Shadow private DepthBufferFormat currentDepthFormat;

    @Unique private RenderTarget[] stereoscopic$rightTargets;
    @Unique private DepthTexture stereoscopic$leftNoTranslucents;
    @Unique private DepthTexture stereoscopic$leftNoHand;
    @Unique private DepthTexture stereoscopic$rightNoTranslucents;
    @Unique private DepthTexture stereoscopic$rightNoHand;

    /** Reverse map from texture-id (LEFT or RIGHT bank, MAIN or ALT) to packed (slot<<1 | mainOrAlt). */
    @Unique private Int2IntMap stereoscopic$reverseMap;

    @Unique private int stereoscopic$activeEye = 0;
    @Unique private boolean stereoscopic$enabled = false;

    // Snapshot of the eye state on resizeIfNeeded HEAD, restored at RETURN. The original body
    // calls noTranslucents.resize() / noHand.resize() on the FIELDS directly — must be LEFT-bank
    // pointing during the body so RIGHT-bank doesn't get clobbered.
    @Unique private boolean stereoscopic$resizeFieldWasOnRight = false;

    /**
     * Route direct {@code get(int)} reads to the active eye's bank. Iris paths that bypass the
     * framebuffer-attachment walk — IrisSamplers binding the sampler texture for {@code colortexN},
     * IrisImages binding image-load/store, CompositeRenderer.setupMipmapping calling
     * {@code getMainTexture()/getAltTexture()} to {@code glGenerateMipmap} + update
     * {@code MIN_FILTER}, FinalPassRenderer's swap-pass build — all go through this method to
     * resolve the {@code RenderTarget}. Without this route they'd read LEFT-bank IDs regardless
     * of active eye: the RIGHT eye's composite would sample LEFT eye's previous-frame data
     * (cross-eye temporal contamination on slots like Complementary {@code colortex7} reflections,
     * BSL {@code colortex9} screen-space colored blocklight) and the mipmap chain for
     * mipmap-enabled slots ({@code colortex9MipmapEnabled = true} in BSL) would only get
     * regenerated on LEFT-bank textures, leaving RIGHT-bank LOD reads as undefined/zero —
     * surfaces as a hard ring around bright lights where the per-eye temporal accumulation falls
     * back to the unaccumulated source pixel and the screen-space spread is gone.
     */
    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private void stereoscopic$routeGet(int index, CallbackInfoReturnable<RenderTarget> cir) {
        if (!stereoscopic$enabled || stereoscopic$activeEye != 1) return;
        if (index < 0 || index >= stereoscopic$rightTargets.length) return;
        final RenderTarget rt = stereoscopic$rightTargets[index];
        if (rt != null) cir.setReturnValue(rt);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void stereoscopic$allocSiblingBank(int width, int height, int depthTexture, int depthBufferVersion,
                                                DepthBufferFormat depthFormat,
                                                Map<Integer, PackRenderTargetDirectives.RenderTargetSettings> renderTargets,
                                                PackDirectives packDirectives, CallbackInfo ci) {
        try {
            stereoscopic$rightTargets = new RenderTarget[targets.length];
            stereoscopic$reverseMap = new Int2IntOpenHashMap();
            stereoscopic$reverseMap.defaultReturnValue(-1);

            // Mirror every populated colortex slot. Angelica allocates all targets in the ctor
            // body before our RETURN runs, so we expect every slot to be non-null here.
            for (int i = 0; i < targets.length; i++) {
                if (targets[i] != null) {
                    stereoscopic$mirrorSlot(i, width, height, renderTargets, packDirectives);
                }
            }

            // Snapshot LEFT-bank depth textures (the original ctor populated noTranslucents/noHand
            // as LEFT before our inject runs) and allocate RIGHT-bank siblings.
            stereoscopic$leftNoTranslucents = noTranslucents;
            stereoscopic$leftNoHand          = noHand;
            stereoscopic$rightNoTranslucents = new DepthTexture(width, height, currentDepthFormat);
            stereoscopic$rightNoHand          = new DepthTexture(width, height, currentDepthFormat);

            stereoscopic$enabled = true;
            PerEyeRenderTargetHooks.registerActiveTargets(this);
            LOGGER.info("Stereoscopic: Iris sibling bank allocated ({} colortex slots, +noTrans+noHand), cached={}x{}",
                targets.length, cachedWidth, cachedHeight);
        } catch (Throwable t) {
            stereoscopic$dropSiblingBank("alloc failed", t);
        }
    }

    @Unique
    private void stereoscopic$mirrorSlot(int i, int width, int height,
                                          Map<Integer, PackRenderTargetDirectives.RenderTargetSettings> renderTargets,
                                          PackDirectives packDirectives) {
        if (stereoscopic$rightTargets[i] != null) return;
        final PackRenderTargetDirectives.RenderTargetSettings settings = renderTargets.get(i);
        if (settings == null || targets[i] == null) return;
        final Vector2i dim = packDirectives.getTextureScaleOverride(i, width, height);
        final InternalTextureFormat requestedFormat = settings.getInternalFormat();
        final InternalTextureFormat actualFormat = requestedFormat.getColorRenderableFallback();
        final RenderTarget mirror = RenderTarget.builder()
            .setDimensions(dim.x, dim.y)
            .setInternalFormat(actualFormat)
            .setPixelFormat(actualFormat.getPixelFormat())
            .build();
        stereoscopic$rightTargets[i] = mirror;
        final int packed = i << 1;
        stereoscopic$reverseMap.put(targets[i].getMainTexture(), packed);
        stereoscopic$reverseMap.put(targets[i].getAltTexture(),  packed | 1);
        stereoscopic$reverseMap.put(mirror.getMainTexture(),     packed);
        stereoscopic$reverseMap.put(mirror.getAltTexture(),      packed | 1);
    }

    @Override
    public int stereoscopic$getActiveEye() {
        return stereoscopic$activeEye;
    }

    /**
     * Resolve a captured colortex texture id (LEFT or RIGHT bank, MAIN or ALT) to the
     * currently-active eye's equivalent. Used by {@link MixinFinalPassRenderer} to remap
     * {@code FinalPassRenderer$SwapPass.targetTexture} — a raw int field captured at pipeline
     * build pointing to LEFT-bank MAIN, never updated by
     * {@link #stereoscopic$setActiveEye(int)}'s framebuffer-attachment walk. Without this remap
     * the per-frame ALT→MAIN copy that backs Complementary's colortex7 reflection cache (and
     * colortex2 TAA history) writes RIGHT-eye ALT data into LEFT-bank MAIN every frame, leaving
     * RIGHT-bank MAIN never written.
     */
    @Override
    public int stereoscopic$resolveActiveEyeTexId(int referenceTexId) {
        if (!stereoscopic$enabled) return referenceTexId;
        final int meta = stereoscopic$reverseMap.get(referenceTexId);
        if (meta < 0) return referenceTexId;
        final int colortexIdx = meta >>> 1;
        final boolean isAlt = (meta & 1) == 1;
        if (colortexIdx < 0 || colortexIdx >= targets.length) return referenceTexId;
        final RenderTarget rt = (stereoscopic$activeEye == 0)
            ? targets[colortexIdx]
            : (stereoscopic$rightTargets != null ? stereoscopic$rightTargets[colortexIdx] : null);
        if (rt == null) return referenceTexId;
        return isAlt ? rt.getAltTexture() : rt.getMainTexture();
    }

    @Override
    public void stereoscopic$setActiveEye(int eyeIndex) {
        if (!stereoscopic$enabled) return;
        if (eyeIndex < 0) eyeIndex = 0; else if (eyeIndex > 1) eyeIndex = 1;
        if (eyeIndex == stereoscopic$activeEye) return;

        final RenderTarget[] sourceBank = (eyeIndex == 0) ? targets : stereoscopic$rightTargets;

        for (GlFramebuffer fb : ownedFramebuffers) {
            final Int2IntMap attMap = ((AccessorGlFramebuffer) (Object) fb).stereoscopic$getAttachments();
            // Iterate via toIntArray to avoid CME (addColorAttachment writes back into the map).
            final int[] slots = attMap.keySet().toIntArray();
            for (int slot : slots) {
                final int currentTexId = attMap.get(slot);
                final int meta = stereoscopic$reverseMap.get(currentTexId);
                if (meta < 0) continue;
                final int colortexIdx = meta >>> 1;
                final boolean isAlt = (meta & 1) == 1;
                if (colortexIdx >= sourceBank.length) continue;
                final RenderTarget rt = sourceBank[colortexIdx];
                if (rt == null) continue;
                final int newTexId = isAlt ? rt.getAltTexture() : rt.getMainTexture();
                if (newTexId != currentTexId) {
                    fb.addColorAttachment(slot, newTexId);
                }
            }
        }

        // Re-attach depth on the noTranslucentsDestFb / noHandDestFb to the active eye's depth
        // sibling. Iris's depthSourceFb reads MC's main depth which is shared across eyes (the
        // scratch FB carries one depth texture used by both eye passes).
        noTranslucentsDestFb.addDepthAttachment(
            eyeIndex == 0 ? stereoscopic$leftNoTranslucents.getTextureId()
                          : stereoscopic$rightNoTranslucents.getTextureId());
        noHandDestFb.addDepthAttachment(
            eyeIndex == 0 ? stereoscopic$leftNoHand.getTextureId()
                          : stereoscopic$rightNoHand.getTextureId());

        // Field swap so:
        //  - copyPreTranslucentDepth() / copyPreHandDepth() bind the active eye's texture as the
        //    GL target (they read the field directly, not via destFb).
        //  - getDepthTextureNoTranslucents() / getDepthTextureNoHand() return the active eye's
        //    sibling — used by Iris samplers (depthtex1/2) at sampler-resolve time, which is
        //    what makes RIGHT-eye composites sample their own eye's depth.
        // Originals stay in stereoscopic$left* and are restored before destroy/resize.
        noTranslucents = (eyeIndex == 0) ? stereoscopic$leftNoTranslucents : stereoscopic$rightNoTranslucents;
        noHand          = (eyeIndex == 0) ? stereoscopic$leftNoHand          : stereoscopic$rightNoHand;

        stereoscopic$activeEye = eyeIndex;
    }

    /**
     * resizeIfNeeded body calls noTranslucents.resize() / noHand.resize() on whichever object the
     * fields currently point to. Restore fields to LEFT before the body runs so RIGHT siblings
     * don't get clobbered. Do NOT mutate stereoscopic$activeEye here; this fires inside the
     * frame's render loop and the per-frame eye dispatcher will set it again next eye-pass entry,
     * but mutating it now would skip the rebind on the next setActiveEye call.
     */
    @Inject(method = "resizeIfNeeded", at = @At("HEAD"))
    private void stereoscopic$resizeHead(int newDepthBufferVersion, int newDepthTextureId,
                                          int newWidth, int newHeight, DepthBufferFormat newDepthFormat,
                                          PackDirectives packDirectives,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (!stereoscopic$enabled) return;
        stereoscopic$resizeFieldWasOnRight =
            (noTranslucents == stereoscopic$rightNoTranslucents)
            || (noHand == stereoscopic$rightNoHand);
        if (stereoscopic$leftNoTranslucents != null) noTranslucents = stereoscopic$leftNoTranslucents;
        if (stereoscopic$leftNoHand          != null) noHand          = stereoscopic$leftNoHand;
    }

    @Inject(method = "resizeIfNeeded", at = @At("RETURN"))
    private void stereoscopic$resizeTail(int newDepthBufferVersion, int newDepthTextureId,
                                          int newWidth, int newHeight, DepthBufferFormat newDepthFormat,
                                          PackDirectives packDirectives,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (!stereoscopic$enabled) return;
        final boolean sizeChanged = Boolean.TRUE.equals(cir.getReturnValue());
        if (!sizeChanged) {
            // Common case — no size change. Original body resized depths in place (if format
            // changed); restore field pointers to whichever eye was active going in.
            if (stereoscopic$resizeFieldWasOnRight) {
                if (stereoscopic$rightNoTranslucents != null) noTranslucents = stereoscopic$rightNoTranslucents;
                if (stereoscopic$rightNoHand          != null) noHand          = stereoscopic$rightNoHand;
            }
            return;
        }
        try {
            // Body resized targets[] (LEFT-bank) and re-snapped noTranslucents/noHand. Resize
            // RIGHT-bank siblings to match.
            stereoscopic$leftNoTranslucents = noTranslucents;
            stereoscopic$leftNoHand          = noHand;

            for (int i = 0; i < stereoscopic$rightTargets.length; i++) {
                final RenderTarget rt = stereoscopic$rightTargets[i];
                if (rt != null) {
                    ((AccessorRenderTarget) (Object) rt).stereoscopic$resize(
                        packDirectives.getTextureScaleOverride(i, newWidth, newHeight));
                }
            }

            // Resize depth siblings to match new dimensions / format.
            stereoscopic$rightNoTranslucents.destroy();
            stereoscopic$rightNoHand.destroy();
            stereoscopic$rightNoTranslucents = new DepthTexture(newWidth, newHeight, newDepthFormat);
            stereoscopic$rightNoHand          = new DepthTexture(newWidth, newHeight, newDepthFormat);

            // Rebuild reverseMap — texture ids changed (resize allocates fresh GL textures).
            stereoscopic$reverseMap.clear();
            stereoscopic$reverseMap.defaultReturnValue(-1);
            for (int i = 0; i < targets.length; i++) {
                if (targets[i] != null && stereoscopic$rightTargets[i] != null) {
                    final int packed = i << 1;
                    stereoscopic$reverseMap.put(targets[i].getMainTexture(), packed);
                    stereoscopic$reverseMap.put(targets[i].getAltTexture(),  packed | 1);
                    stereoscopic$reverseMap.put(stereoscopic$rightTargets[i].getMainTexture(), packed);
                    stereoscopic$reverseMap.put(stereoscopic$rightTargets[i].getAltTexture(),  packed | 1);
                }
            }

            // FBs' color attachments currently point at the resized LEFT bank; sentinel forces
            // the next setActiveEye to walk + rebind regardless of stored eye.
            stereoscopic$activeEye = -1;
        } catch (Throwable t) {
            stereoscopic$dropSiblingBank("resize failed", t);
        }
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void stereoscopic$destroySiblingBank(CallbackInfo ci) {
        PerEyeRenderTargetHooks.unregisterActiveTargets(this);
        if (!stereoscopic$enabled) return;
        // Iris's destroy() calls noTranslucents.destroy() / noHand.destroy() on the field
        // directly; restore to LEFT so it closes the LEFT bank. We close RIGHT siblings below.
        if (stereoscopic$leftNoTranslucents != null) noTranslucents = stereoscopic$leftNoTranslucents;
        if (stereoscopic$leftNoHand          != null) noHand          = stereoscopic$leftNoHand;
        try {
            for (RenderTarget rt : stereoscopic$rightTargets) {
                if (rt != null) {
                    try { rt.destroy(); }
                    catch (Throwable t) { LOGGER.warn("[stereo] right-bank colortex destroy on shutdown failed; GL texture leaked", t); }
                }
            }
        } finally {
            stereoscopic$rightTargets = null;
        }
        try { if (stereoscopic$rightNoTranslucents != null) stereoscopic$rightNoTranslucents.destroy(); }
        catch (Throwable t) { LOGGER.warn("[stereo] right noTranslucents destroy failed; GL texture leaked", t); }
        try { if (stereoscopic$rightNoHand != null) stereoscopic$rightNoHand.destroy(); }
        catch (Throwable t) { LOGGER.warn("[stereo] right noHand destroy failed; GL texture leaked", t); }
        stereoscopic$leftNoTranslucents = null;
        stereoscopic$leftNoHand = null;
        stereoscopic$rightNoTranslucents = null;
        stereoscopic$rightNoHand = null;
        stereoscopic$reverseMap = null;
        stereoscopic$enabled = false;
    }

    @Unique
    private void stereoscopic$dropSiblingBank(String why, Throwable t) {
        LOGGER.warn("Stereoscopic: Iris sibling bank dropped ({}); falling back to single-bank", why, t);
        PerEyeRenderTargetHooks.unregisterActiveTargets(this);
        if (stereoscopic$leftNoTranslucents != null) noTranslucents = stereoscopic$leftNoTranslucents;
        if (stereoscopic$leftNoHand          != null) noHand          = stereoscopic$leftNoHand;
        if (stereoscopic$rightTargets != null) {
            for (RenderTarget rt : stereoscopic$rightTargets) {
                if (rt != null) {
                    try { rt.destroy(); }
                    catch (Throwable dt) { LOGGER.warn("[stereo] right-bank colortex destroy during drop failed; GL texture leaked", dt); }
                }
            }
        }
        stereoscopic$rightTargets = null;
        try { if (stereoscopic$rightNoTranslucents != null) stereoscopic$rightNoTranslucents.destroy(); }
        catch (Throwable dt) { LOGGER.warn("[stereo] right noTranslucents destroy during drop failed; GL texture leaked", dt); }
        try { if (stereoscopic$rightNoHand != null) stereoscopic$rightNoHand.destroy(); }
        catch (Throwable dt) { LOGGER.warn("[stereo] right noHand destroy during drop failed; GL texture leaked", dt); }
        stereoscopic$leftNoTranslucents = null;
        stereoscopic$leftNoHand = null;
        stereoscopic$rightNoTranslucents = null;
        stereoscopic$rightNoHand = null;
        stereoscopic$reverseMap = null;
        stereoscopic$enabled = false;
    }
}
