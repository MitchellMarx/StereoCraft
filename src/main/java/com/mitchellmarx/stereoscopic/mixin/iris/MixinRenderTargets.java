package com.mitchellmarx.stereoscopic.mixin.iris;

import com.mitchellmarx.stereoscopic.Stereoscopic;
import com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks;
import com.mitchellmarx.stereoscopic.compat.voxy.VoxyEyeRebindHooks;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import com.mojang.blaze3d.opengl.GlTexture;
import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

/**
 * Per-eye Iris {@link RenderTargets}. For each colortex slot we maintain a
 * sibling {@link RenderTarget}; for each Iris-owned intermediate depth texture
 * ({@code noTranslucents}, {@code noHand}) we maintain a sibling
 * {@link GpuTexture}. On eye switch we walk every Iris-owned
 * {@link GlFramebuffer} and rebind color attachments to the active eye's
 * texture IDs (via {@code addColorAttachment} → {@code glFramebufferTexture2D}
 * directly, DSA-style; no bind needed) and swap the depth fields to the
 * active eye's depth sibling.
 *
 * <p>Allocation failure downgrades to single-bank operation; the mixin config
 * also sets {@code required: false} as a final safety net.
 */
@Mixin(value = RenderTargets.class, remap = false)
public abstract class MixinRenderTargets implements PerEyeRenderTargetHooks.EyeAwareRenderTargets {

    @Shadow @Final private RenderTarget[] targets;
    @Shadow @Final private Map<Integer, PackRenderTargetDirectives.RenderTargetSettings> targetSettingsMap;
    @Shadow @Final private PackDirectives packDirectives;
    @Shadow @Final private List<GlFramebuffer> ownedFramebuffers;
    @Shadow @Final private GlFramebuffer noTranslucentsDestFb;
    @Shadow @Final private GlFramebuffer noHandDestFb;
    @Shadow private GpuTexture noTranslucents;
    @Shadow private GpuTexture noHand;
    @Shadow private GpuTexture currentDepthTexture;
    @Shadow private DepthBufferFormat currentDepthFormat;
    @Shadow private int cachedWidth;
    @Shadow private int cachedHeight;
    @Shadow private int cachedDepthBufferVersion;

    @Unique private RenderTarget[] stereoscopic$rightTargets;
    // Originals stay tracked so Iris's destroy/resize calls hit the LEFT-bank
    // texture regardless of which eye was active when the lifecycle fired.
    @Unique private GpuTexture stereoscopic$leftNoTranslucents;
    @Unique private GpuTexture stereoscopic$leftNoHand;
    @Unique private GpuTexture stereoscopic$rightNoTranslucents;
    @Unique private GpuTexture stereoscopic$rightNoHand;
    @Unique private Int2IntMap stereoscopic$reverseMap; // texId -> (slot<<1 | mainOrAlt)
    @Unique private int stereoscopic$activeEye = 0;
    @Unique private boolean stereoscopic$enabled = false;
    // Snapshot of the eye state on resizeIfNeeded entry, restored in RETURN.
    @Unique private int stereoscopic$resizeSavedEye = 0;
    @Unique private boolean stereoscopic$resizeFieldWasOnRight = false;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void stereoscopic$allocSiblingBank(int width, int height, GpuTexture depthTexture,
                                                int depthBufferVersion,
                                                DepthBufferFormat depthFormat,
                                                Map<Integer, PackRenderTargetDirectives.RenderTargetSettings> renderTargets,
                                                PackDirectives packDirectives, CallbackInfo ci) {
        if (PerEyeRenderTargetHooks.wantedEyeCount() != 2) return;
        try {
            stereoscopic$rightTargets = new RenderTarget[targets.length];
            stereoscopic$reverseMap = new Int2IntOpenHashMap();
            stereoscopic$reverseMap.defaultReturnValue(-1);

            // Iris's ctor calls createFramebufferWritingToMain({0}) before our
            // RETURN inject, which populates targets[0] via getOrCreate(0).
            // Mirror any slots already allocated by then.
            for (int i = 0; i < targets.length; i++) {
                if (targets[i] != null) stereoscopic$mirrorSlot(i);
            }

            stereoscopic$leftNoTranslucents = noTranslucents;
            stereoscopic$leftNoHand          = noHand;
            stereoscopic$rightNoTranslucents = stereoscopic$createDepthSibling("Depth / Opaque (eye R)");
            stereoscopic$rightNoHand          = stereoscopic$createDepthSibling("Depth / Before Hand (eye R)");

            stereoscopic$enabled = true;
            PerEyeRenderTargetHooks.registerActiveTargets(this);
            Stereoscopic.LOG.info("Iris stereo bank allocated ({} colortex slots, +noTrans+noHand), cached={}x{}", targets.length, cachedWidth, cachedHeight);
        } catch (Throwable t) {
            stereoscopic$dropSiblingBank("alloc failed", t);
        }
    }

    // Primary bank slots populate lazily on first getOrCreate; mirror after
    // create() so the two stay in lockstep.
    @Inject(method = "create", at = @At("RETURN"))
    private void stereoscopic$mirrorCreate(int index, CallbackInfo ci) {
        if (!stereoscopic$enabled) return;
        if (index < 0 || index >= stereoscopic$rightTargets.length) return;
        try {
            stereoscopic$mirrorSlot(index);
        } catch (Throwable t) {
            Stereoscopic.LOG.warn("Stereoscopic: mirror of colortex {} failed; eye sync may be wrong on RIGHT eye", index, t);
        }
    }

    @Unique
    private void stereoscopic$mirrorSlot(int i) {
        if (stereoscopic$rightTargets[i] != null) return;
        PackRenderTargetDirectives.RenderTargetSettings settings = targetSettingsMap.get(i);
        if (settings == null || targets[i] == null) return;
        Vector2i dim = packDirectives.getTextureScaleOverride(i, cachedWidth, cachedHeight);
        RenderTarget mirror = RenderTarget.builder()
            .setDimensions(dim.x, dim.y)
            .setName("colortex" + i + "_stereo_R")
            .setInternalFormat(settings.getInternalFormat())
            .setPixelFormat(settings.getInternalFormat().getPixelFormat())
            .build();
        stereoscopic$rightTargets[i] = mirror;
        int packed = i << 1;
        stereoscopic$reverseMap.put(targets[i].getMainTexture(), packed);
        stereoscopic$reverseMap.put(targets[i].getAltTexture(),  packed | 1);
        stereoscopic$reverseMap.put(mirror.getMainTexture(),     packed);
        stereoscopic$reverseMap.put(mirror.getAltTexture(),      packed | 1);
    }

    @Unique
    private GpuTexture stereoscopic$createDepthSibling(String name) {
        TextureFormat fmt = IrisPlatformHelpers.getInstance().mojangDepthFormat(currentDepthFormat);
        // Match Iris's constructor: usage=5, mipLevels=1, layers=1.
        return RenderSystem.getDevice().createTexture(name, 5, fmt, cachedWidth, cachedHeight, 1, 1);
    }

    @Override
    public int stereoscopic$getActiveEye() {
        return stereoscopic$activeEye;
    }

    /**
     * Resolve a captured colortex texture id (LEFT or RIGHT bank, MAIN or ALT) to
     * the currently-active eye's equivalent. Used by
     * {@link com.mitchellmarx.stereoscopic.mixin.iris.MixinFinalPassRenderer} to
     * remap {@code FinalPassRenderer$SwapPass.targetTexture} — a raw int field
     * captured at pipeline build time pointing to LEFT-bank MAIN, never updated
     * by {@link #stereoscopic$setActiveEye(int)}'s framebuffer-attachment walk.
     * Without this remap the per-frame ALT→MAIN copy that backs Complementary's
     * {@code colortex7} reflection cache (and {@code colortex2} TAA history)
     * writes RIGHT-eye ALT data into LEFT-bank MAIN every frame, leaving
     * RIGHT-bank MAIN never written.
     */
    @Override
    public int stereoscopic$resolveActiveEyeTexId(int referenceTexId) {
        if (!stereoscopic$enabled) return referenceTexId;
        int meta = stereoscopic$reverseMap.get(referenceTexId);
        if (meta < 0) return referenceTexId;
        int colortexIdx = meta >>> 1;
        boolean isAlt = (meta & 1) == 1;
        if (colortexIdx < 0 || colortexIdx >= targets.length) return referenceTexId;
        RenderTarget rt = (stereoscopic$activeEye == 0)
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

        RenderTarget[] sourceBank = (eyeIndex == 0) ? targets : stereoscopic$rightTargets;

        for (GlFramebuffer fb : ownedFramebuffers) {
            Int2IntMap attMap = ((MixinGlFramebuffer)(Object) fb).stereoscopic$getAttachments();
            int[] slots = attMap.keySet().toIntArray();
            for (int slot : slots) {
                int currentTexId = attMap.get(slot);
                int meta = stereoscopic$reverseMap.get(currentTexId);
                if (meta < 0) continue;
                int colortexIdx = meta >>> 1;
                boolean isAlt = (meta & 1) == 1;
                if (colortexIdx >= sourceBank.length) continue;
                RenderTarget rt = sourceBank[colortexIdx];
                if (rt == null) continue;
                int newTexId = isAlt ? rt.getAltTexture() : rt.getMainTexture();
                if (newTexId != currentTexId) {
                    fb.addColorAttachment(slot, newTexId);
                }
            }
        }

        // Voxy's IrisVoxyRenderPipeline owns two framebuffers outside of
        // ownedFramebuffers, with color attachments snapshotted to LEFT-bank
        // texture IDs at pipeline build. Without this rebind, voxy's LOD
        // pixels land in LEFT's gbuffer on both eyes; RIGHT's deferred
        // composite then reads empty gbuffer and paints sky color in their
        // place. Compat facade is a no-op when voxy isn't installed.
        VoxyEyeRebindHooks.rebindForEye(sourceBank, stereoscopic$reverseMap);

        // depthSourceFb reads MC's main-FB depth which is shared across eyes.
        noTranslucentsDestFb.addDepthAttachment(eyeIndex == 0 ? stereoscopic$leftNoTranslucents : stereoscopic$rightNoTranslucents);
        noHandDestFb.addDepthAttachment(eyeIndex == 0 ? stereoscopic$leftNoHand : stereoscopic$rightNoHand);

        // Field swap so:
        //  - copyPreTranslucentDepth() / copyPreHandDepth() bind the active eye's
        //    texture as the GL write target (they read the field directly, not
        //    the destFb framebuffer).
        //  - getDepthTextureNoTranslucents/NoHand return the active eye's bank
        //    — used by IrisSamplers' depthtex1/depthtex2 supplier lambdas at
        //    sampler-resolve time, which is what makes RIGHT-eye composite
        //    passes sample their own eye's depth.
        // Originals stay in stereoscopic$left* and get restored before destroy/
        // resize so Iris's in-place lifecycle calls hit the LEFT-bank texture.
        noTranslucents = (eyeIndex == 0) ? stereoscopic$leftNoTranslucents : stereoscopic$rightNoTranslucents;
        noHand          = (eyeIndex == 0) ? stereoscopic$leftNoHand          : stereoscopic$rightNoHand;

        stereoscopic$activeEye = eyeIndex;
    }

    // Direct get()/getOrCreate() reads (Iris sampler binding paths that bypass
    // framebuffer attachments) — route to the active eye's bank.
    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private void stereoscopic$routeGet(int index, CallbackInfoReturnable<RenderTarget> cir) {
        if (!stereoscopic$enabled || stereoscopic$activeEye != 1) return;
        if (index < 0 || index >= stereoscopic$rightTargets.length) return;
        RenderTarget rt = stereoscopic$rightTargets[index];
        if (rt != null) cir.setReturnValue(rt);
    }

    @Inject(method = "getOrCreate", at = @At("HEAD"), cancellable = true)
    private void stereoscopic$routeGetOrCreate(int index, CallbackInfoReturnable<RenderTarget> cir) {
        if (!stereoscopic$enabled || stereoscopic$activeEye != 1) return;
        if (index < 0 || index >= stereoscopic$rightTargets.length) return;
        RenderTarget rt = stereoscopic$rightTargets[index];
        if (rt != null) cir.setReturnValue(rt);
        // If the sibling isn't populated, fall through — Iris's create() then
        // our @Inject(create, RETURN) mirror catches it for the next caller.
    }

    /**
     * resizeIfNeeded body close()s the current noTranslucents/noHand and
     * reassigns fresh textures on a real resize. Restore fields to LEFT before
     * the body runs — otherwise it closes the RIGHT-bank texture and leaks the
     * LEFT one. Do NOT mutate {@link #stereoscopic$activeEye} here; this is
     * called from beginLevelRendering inside both eye iterations, so a clobber
     * would mis-route the eye==1 gate in {@link #stereoscopic$routeGet} for
     * the rest of that eye's pass and the never-written RIGHT-half of the LEFT
     * bank becomes the visible black-world symptom.
     */
    @Inject(method = "resizeIfNeeded", at = @At("HEAD"))
    private void stereoscopic$resizeHead(int newDepthBufferVersion,
                                          GpuTexture newDepthTextureId,
                                          int newWidth, int newHeight,
                                          DepthBufferFormat newDepthFormat,
                                          PackDirectives packDirectives,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (!stereoscopic$enabled) return;
        stereoscopic$resizeSavedEye = stereoscopic$activeEye;
        stereoscopic$resizeFieldWasOnRight =
            (noTranslucents == stereoscopic$rightNoTranslucents)
            || (noHand == stereoscopic$rightNoHand);
        if (stereoscopic$leftNoTranslucents != null) noTranslucents = stereoscopic$leftNoTranslucents;
        if (stereoscopic$leftNoHand          != null) noHand          = stereoscopic$leftNoHand;

        // Force a main-depth rebind when the incoming depth texture differs
        // from currentDepthTexture by GL ID. Iris's body rebinds only when
        // newDepthBufferVersion != cachedDepthBufferVersion — but
        // iris$depthBufferVersion is a per-Framebuffer increment counter, not
        // a texture-identity check. Two distinct GpuTextures can carry the
        // same counter (scratch starts at 1; real main is 1 after a startup
        // resize), so currentDepthTexture can stay pointed at a stale texture
        // when the bound MC FB switches. Symptom: depthtex0 samples stale
        // depth, Complementary's volumetric clouds (deferred1.glsl) read z=1
        // for every pixel and the cloud march overdraws solid geometry.
        try {
            int incomingGl = stereoscopic$glId(newDepthTextureId);
            int currentGl = stereoscopic$glId(currentDepthTexture);
            if (incomingGl != 0 && incomingGl != currentGl) {
                cachedDepthBufferVersion = newDepthBufferVersion ^ 0x7FFFFFFF;
            }
        } catch (Throwable t) {
            Stereoscopic.LOG.warn("[stereo] depth-rebind guard failed", t);
        }
    }

    @Unique
    private static int stereoscopic$glId(GpuTexture tex) {
        if (tex == null) return 0;
        // Direct cast — survives Loom intermediary remap. Reflection by yarn
        // name would silently fail in production.
        if (tex instanceof GlTexture gl) return gl.glId();
        Stereoscopic.LOG.warn("[stereo] glId extraction failed for non-GlTexture {}; depth-rebind guard skipped",
            tex.getClass().getName());
        return 0;
    }

    @Inject(method = "resizeIfNeeded", at = @At("RETURN"))
    private void stereoscopic$onResize(int newDepthBufferVersion,
                                        GpuTexture newDepthTextureId,
                                        int newWidth, int newHeight,
                                        DepthBufferFormat newDepthFormat,
                                        PackDirectives packDirectives,
                                        CallbackInfoReturnable<Boolean> cir) {
        if (!stereoscopic$enabled) return;
        boolean sizeChanged = Boolean.TRUE.equals(cir.getReturnValue());
        if (!sizeChanged) {
            // Common case: no resize. Restore field state from HEAD entry.
            if (stereoscopic$resizeFieldWasOnRight) {
                if (stereoscopic$rightNoTranslucents != null) noTranslucents = stereoscopic$rightNoTranslucents;
                if (stereoscopic$rightNoHand          != null) noHand          = stereoscopic$rightNoHand;
            }
            return;
        }
        try {
            // Body close()'d the LEFT-bank texture (HEAD restored the field) and
            // assigned fresh ones — re-snapshot before destroying RIGHT siblings.
            stereoscopic$leftNoTranslucents = noTranslucents;
            stereoscopic$leftNoHand          = noHand;

            for (int i = 0; i < stereoscopic$rightTargets.length; i++) {
                RenderTarget rt = stereoscopic$rightTargets[i];
                if (rt != null) {
                    try { rt.destroy(); }
                    catch (Throwable t) { Stereoscopic.LOG.warn("[stereo] right-bank colortex{} destroy on resize failed; GL texture leaked", i, t); }
                    stereoscopic$rightTargets[i] = null;
                }
            }
            stereoscopic$reverseMap.clear();
            stereoscopic$reverseMap.defaultReturnValue(-1);
            for (int i = 0; i < targets.length; i++) {
                if (targets[i] != null) stereoscopic$mirrorSlot(i);
            }

            stereoscopic$closeQuietly(stereoscopic$rightNoTranslucents);
            stereoscopic$closeQuietly(stereoscopic$rightNoHand);
            stereoscopic$rightNoTranslucents = stereoscopic$createDepthSibling("Depth / Opaque (eye R)");
            stereoscopic$rightNoHand          = stereoscopic$createDepthSibling("Depth / Before Hand (eye R)");

            // FBs' color attachments currently point at the resized LEFT bank;
            // sentinel forces the next setActiveEye to walk + rebind regardless
            // of whether N matches the saved activeEye.
            stereoscopic$activeEye = -1;
        } catch (Throwable t) {
            stereoscopic$dropSiblingBank("resize failed", t);
        }
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void stereoscopic$destroySiblingBank(CallbackInfo ci) {
        PerEyeRenderTargetHooks.unregisterActiveTargets(this);
        if (!stereoscopic$enabled) return;
        // Iris's destroy() calls noTranslucents.destroy()/noHand.destroy() on
        // the field directly; restore to LEFT so it closes the LEFT bank. We
        // close RIGHT siblings ourselves below.
        if (stereoscopic$leftNoTranslucents != null) noTranslucents = stereoscopic$leftNoTranslucents;
        if (stereoscopic$leftNoHand          != null) noHand          = stereoscopic$leftNoHand;
        try {
            for (RenderTarget rt : stereoscopic$rightTargets) {
                if (rt != null) {
                    try { rt.destroy(); }
                    catch (Throwable t) { Stereoscopic.LOG.warn("[stereo] right-bank colortex destroy on shutdown failed; GL texture leaked", t); }
                }
            }
        } finally {
            stereoscopic$rightTargets = null;
        }
        stereoscopic$closeQuietly(stereoscopic$rightNoTranslucents);
        stereoscopic$closeQuietly(stereoscopic$rightNoHand);
        stereoscopic$leftNoTranslucents = null;
        stereoscopic$leftNoHand = null;
        stereoscopic$rightNoTranslucents = null;
        stereoscopic$rightNoHand = null;
        stereoscopic$reverseMap = null;
        stereoscopic$enabled = false;
    }

    @Unique
    private void stereoscopic$dropSiblingBank(String why, Throwable t) {
        Stereoscopic.LOG.warn("Stereoscopic: Iris sibling bank dropped ({}); falling back to single-bank", why, t);
        PerEyeRenderTargetHooks.unregisterActiveTargets(this);
        if (stereoscopic$leftNoTranslucents != null) noTranslucents = stereoscopic$leftNoTranslucents;
        if (stereoscopic$leftNoHand          != null) noHand          = stereoscopic$leftNoHand;
        if (stereoscopic$rightTargets != null) {
            for (RenderTarget rt : stereoscopic$rightTargets) {
                if (rt != null) {
                    try { rt.destroy(); }
                    catch (Throwable dt) { Stereoscopic.LOG.warn("[stereo] right-bank colortex destroy during drop failed; GL texture leaked", dt); }
                }
            }
        }
        stereoscopic$rightTargets = null;
        stereoscopic$closeQuietly(stereoscopic$rightNoTranslucents);
        stereoscopic$closeQuietly(stereoscopic$rightNoHand);
        stereoscopic$leftNoTranslucents = null;
        stereoscopic$leftNoHand = null;
        stereoscopic$rightNoTranslucents = null;
        stereoscopic$rightNoHand = null;
        stereoscopic$reverseMap = null;
        stereoscopic$enabled = false;
    }

    @Unique
    private static void stereoscopic$closeQuietly(GpuTexture tex) {
        if (tex == null) return;
        try { tex.close(); }
        catch (Throwable t) { Stereoscopic.LOG.warn("[stereo] depth-sibling close failed; GPU texture leaked", t); }
    }
}
