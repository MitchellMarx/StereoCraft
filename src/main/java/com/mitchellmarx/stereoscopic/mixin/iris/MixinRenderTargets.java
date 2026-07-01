package com.mitchellmarx.stereoscopic.mixin.iris;

import com.mitchellmarx.stereoscopic.Stereoscopic;
import com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.targets.DepthTexture;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
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
 * Per-eye Iris {@link RenderTargets}. For each colortex slot we maintain a
 * sibling {@link RenderTarget}; for each Iris-owned intermediate depth
 * texture ({@code noTranslucents}, {@code noHand}) we maintain a sibling
 * {@link DepthTexture}. On eye switch we walk every Iris-owned
 * {@link GlFramebuffer} and rebind color attachments to the active eye's
 * texture IDs and swap the depth fields to the active eye's depth sibling.
 *
 * <p>Iris 1.8.12 adaptation: this class targets the 1.8.12 RenderTargets
 * shape (raw int texture IDs throughout; {@code noTranslucents}/{@code noHand}
 * are {@link DepthTexture} not {@code GpuTexture}; depth-format conversion
 * uses Iris's own {@link DepthTexture} constructor instead of the 1.21.6+
 * {@code RenderSystem.getDevice().createTexture(...)} path).
 *
 * <p>Allocation failure downgrades to single-bank operation; the mixin
 * config also sets {@code required: false} as a final safety net.
 */
@Mixin(value = RenderTargets.class, remap = false)
public abstract class MixinRenderTargets implements PerEyeRenderTargetHooks.EyeAwareRenderTargets {

    @Shadow @Final private RenderTarget[] targets;
    @Shadow @Final private Map<Integer, PackRenderTargetDirectives.RenderTargetSettings> targetSettingsMap;
    @Shadow @Final private PackDirectives packDirectives;
    @Shadow @Final private List<GlFramebuffer> ownedFramebuffers;
    @Shadow @Final private GlFramebuffer noTranslucentsDestFb;
    @Shadow @Final private GlFramebuffer noHandDestFb;
    // Iris 1.8.12 declares these final; @Mutable strips the final modifier
    // at mixin apply time so per-eye field swaps don't IllegalAccessError.
    @Mutable @Shadow private DepthTexture noTranslucents;
    @Mutable @Shadow private DepthTexture noHand;
    @Shadow private int currentDepthTexture;
    @Shadow private DepthBufferFormat currentDepthFormat;
    @Shadow private int cachedWidth;
    @Shadow private int cachedHeight;
    @Shadow private int cachedDepthBufferVersion;

    @Unique private RenderTarget[] stereoscopic$rightTargets;
    @Unique private DepthTexture stereoscopic$leftNoTranslucents;
    @Unique private DepthTexture stereoscopic$leftNoHand;
    @Unique private DepthTexture stereoscopic$rightNoTranslucents;
    @Unique private DepthTexture stereoscopic$rightNoHand;
    @Unique private Int2IntMap stereoscopic$reverseMap; // texId -> (slot<<1 | mainOrAlt)
    @Unique private int stereoscopic$activeEye = 0;
    @Unique private boolean stereoscopic$enabled = false;
    @Unique private int stereoscopic$resizeSavedEye = 0;
    @Unique private boolean stereoscopic$resizeFieldWasOnRight = false;

    /**
     * Iris 1.8.12 constructor:
     * {@code RenderTargets(int width, int height, int depthTextureId,
     *  int depthBufferVersion, DepthBufferFormat depthFormat,
     *  Map renderTargets, PackDirectives packDirectives)}
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void stereoscopic$allocSiblingBank(int width, int height, int depthTextureId,
                                                int depthBufferVersion,
                                                DepthBufferFormat depthFormat,
                                                Map<Integer, PackRenderTargetDirectives.RenderTargetSettings> renderTargets,
                                                PackDirectives packDirectives, CallbackInfo ci) {
        if (PerEyeRenderTargetHooks.wantedEyeCount() != 2) return;
        try {
            stereoscopic$rightTargets = new RenderTarget[targets.length];
            stereoscopic$reverseMap = new Int2IntOpenHashMap();
            stereoscopic$reverseMap.defaultReturnValue(-1);

            for (int i = 0; i < targets.length; i++) {
                if (targets[i] != null) stereoscopic$mirrorSlot(i);
            }

            stereoscopic$leftNoTranslucents = noTranslucents;
            stereoscopic$leftNoHand          = noHand;
            stereoscopic$rightNoTranslucents = new DepthTexture("Depth / Opaque (eye R)",
                cachedWidth, cachedHeight, currentDepthFormat);
            stereoscopic$rightNoHand          = new DepthTexture("Depth / Before Hand (eye R)",
                cachedWidth, cachedHeight, currentDepthFormat);

            stereoscopic$enabled = true;
            PerEyeRenderTargetHooks.registerActiveTargets(this);
            Stereoscopic.LOG.info("Iris stereo bank allocated ({} colortex slots, +noTrans+noHand), cached={}x{}",
                targets.length, cachedWidth, cachedHeight);
        } catch (Throwable t) {
            stereoscopic$dropSiblingBank("alloc failed", t);
        }
    }

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

    @Override
    public int stereoscopic$getActiveEye() {
        return stereoscopic$activeEye;
    }

    @Override
    public String stereoscopic$diagDumpState() {
        StringBuilder sb = new StringBuilder();
        sb.append("enabled=").append(stereoscopic$enabled)
          .append(",activeEye=").append(stereoscopic$activeEye)
          .append(",depthTex=").append(currentDepthTexture);
        if (stereoscopic$leftNoTranslucents != null) {
            sb.append(",leftNoTrans=").append(stereoscopic$leftNoTranslucents.getTextureId());
        }
        if (stereoscopic$rightNoTranslucents != null) {
            sb.append(",rightNoTrans=").append(stereoscopic$rightNoTranslucents.getTextureId());
        }
        sb.append(",curNoTrans=").append(noTranslucents != null ? noTranslucents.getTextureId() : -1);
        sb.append(",curNoTransIsRight=").append(noTranslucents == stereoscopic$rightNoTranslucents);
        // Sample colortex 0..3 main+alt for current bank vs other bank
        for (int i = 0; i < Math.min(4, targets.length); i++) {
            RenderTarget left = targets[i];
            RenderTarget right = stereoscopic$rightTargets != null ? stereoscopic$rightTargets[i] : null;
            sb.append(",c").append(i);
            if (left != null) sb.append("L=").append(left.getMainTexture()).append("/").append(left.getAltTexture());
            if (right != null) sb.append("R=").append(right.getMainTexture()).append("/").append(right.getAltTexture());
        }
        // Scan every owned framebuffer; flag any FB that holds an attachment
        // whose ID is in reverseMap but on the WRONG eye's bank for the
        // current activeEye. That's exactly the bug pattern — an Iris FB
        // that setActiveEye missed (or that something else re-attached
        // mid-frame to a stale bank).
        sb.append(",ownedFBs=").append(ownedFramebuffers.size());
        int mismatchCount = 0;
        StringBuilder mismatches = new StringBuilder();
        int fbIdx = 0;
        for (GlFramebuffer fb : ownedFramebuffers) {
            Int2IntMap attMap = ((MixinGlFramebuffer)(Object) fb).stereoscopic$getAttachments();
            int[] keys = attMap.keySet().toIntArray();
            for (int k : keys) {
                int texId = attMap.get(k);
                int meta = stereoscopic$reverseMap.get(texId);
                if (meta < 0) continue;
                int slot = meta >>> 1;
                boolean isAlt = (meta & 1) == 1;
                if (slot >= targets.length) continue;
                RenderTarget expectedRt = (stereoscopic$activeEye == 0)
                    ? targets[slot]
                    : (stereoscopic$rightTargets != null ? stereoscopic$rightTargets[slot] : null);
                if (expectedRt == null) continue;
                int expectedId = isAlt ? expectedRt.getAltTexture() : expectedRt.getMainTexture();
                if (expectedId != texId) {
                    mismatchCount++;
                    if (mismatchCount <= 5) {
                        mismatches.append(" fb").append(fbIdx)
                            .append("@slot").append(k)
                            .append("=").append(texId)
                            .append("(want=").append(expectedId)
                            .append(",cTex").append(slot).append(isAlt ? "alt" : "main")
                            .append(")");
                    }
                }
            }
            fbIdx++;
        }
        sb.append(",mismatch=").append(mismatchCount).append(mismatches);
        return sb.toString();
    }

    @Override
    public int stereoscopic$resolveActiveEyeTexId(int referenceTexId) {
        if (!stereoscopic$enabled) return referenceTexId;
        int meta = stereoscopic$reverseMap.get(referenceTexId);
        if (meta >= 0) {
            int colortexIdx = meta >>> 1;
            boolean isAlt = (meta & 1) == 1;
            if (colortexIdx < 0 || colortexIdx >= targets.length) return referenceTexId;
            RenderTarget rt = (stereoscopic$activeEye == 0)
                ? targets[colortexIdx]
                : (stereoscopic$rightTargets != null ? stereoscopic$rightTargets[colortexIdx] : null);
            if (rt == null) return referenceTexId;
            return isAlt ? rt.getAltTexture() : rt.getMainTexture();
        }
        // noTranslucents / noHand depth textures: shader-pack reflection,
        // shadow, and ambient-occlusion samplers bind to these via cached
        // IntSuppliers at pipeline init. Without remap the RIGHT eye samples
        // LEFT-bank depth and depth-test fails on view-dependent fragments,
        // surfacing as skybox-color flashes through textures.
        if (stereoscopic$leftNoTranslucents != null && stereoscopic$rightNoTranslucents != null) {
            int leftNoTransId  = stereoscopic$leftNoTranslucents.getTextureId();
            int rightNoTransId = stereoscopic$rightNoTranslucents.getTextureId();
            if (referenceTexId == leftNoTransId || referenceTexId == rightNoTransId) {
                return stereoscopic$activeEye == 0 ? leftNoTransId : rightNoTransId;
            }
        }
        if (stereoscopic$leftNoHand != null && stereoscopic$rightNoHand != null) {
            int leftNoHandId  = stereoscopic$leftNoHand.getTextureId();
            int rightNoHandId = stereoscopic$rightNoHand.getTextureId();
            if (referenceTexId == leftNoHandId || referenceTexId == rightNoHandId) {
                return stereoscopic$activeEye == 0 ? leftNoHandId : rightNoHandId;
            }
        }
        return referenceTexId;
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

        DepthTexture activeNoTrans = (eyeIndex == 0) ? stereoscopic$leftNoTranslucents : stereoscopic$rightNoTranslucents;
        DepthTexture activeNoHand  = (eyeIndex == 0) ? stereoscopic$leftNoHand          : stereoscopic$rightNoHand;
        noTranslucentsDestFb.addDepthAttachment(activeNoTrans.getTextureId());
        noHandDestFb.addDepthAttachment(activeNoHand.getTextureId());

        noTranslucents = activeNoTrans;
        noHand          = activeNoHand;

        // Rebind Voxy's Iris-mode FBs to the active eye's bank (they live outside
        // ownedFramebuffers so the walk above misses them). Inert on NeoForge —
        // voxy-neoforge excludes its Iris pipeline; see VoxyEyeRebindHooks.
        com.mitchellmarx.stereoscopic.compat.voxy.VoxyEyeRebindHooks
            .rebindForEye(sourceBank, stereoscopic$reverseMap);

        stereoscopic$activeEye = eyeIndex;
    }

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
    }

    /**
     * Iris 1.8.12 resizeIfNeeded signature:
     * {@code resizeIfNeeded(int newDepthBufferVersion, int newDepthTextureId,
     *  int newWidth, int newHeight, DepthBufferFormat newDepthFormat,
     *  PackDirectives packDirectives)}
     */
    @Inject(method = "resizeIfNeeded", at = @At("HEAD"))
    private void stereoscopic$resizeHead(int newDepthBufferVersion,
                                          int newDepthTextureId,
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

        // Force main-depth rebind when incoming GL ID differs from current.
        // Iris 1.8.12's depth texture is already a raw int — no GlTexture
        // extraction needed (unlike the 1.21.6+ GpuTexture path).
        if (newDepthTextureId != 0 && newDepthTextureId != currentDepthTexture) {
            cachedDepthBufferVersion = newDepthBufferVersion ^ 0x7FFFFFFF;
        }
    }

    @Inject(method = "resizeIfNeeded", at = @At("RETURN"))
    private void stereoscopic$onResize(int newDepthBufferVersion,
                                        int newDepthTextureId,
                                        int newWidth, int newHeight,
                                        DepthBufferFormat newDepthFormat,
                                        PackDirectives packDirectives,
                                        CallbackInfoReturnable<Boolean> cir) {
        if (!stereoscopic$enabled) return;
        boolean sizeChanged = Boolean.TRUE.equals(cir.getReturnValue());
        if (!sizeChanged) {
            if (stereoscopic$resizeFieldWasOnRight) {
                if (stereoscopic$rightNoTranslucents != null) noTranslucents = stereoscopic$rightNoTranslucents;
                if (stereoscopic$rightNoHand          != null) noHand          = stereoscopic$rightNoHand;
            }
            return;
        }
        try {
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

            stereoscopic$destroyQuietly(stereoscopic$rightNoTranslucents);
            stereoscopic$destroyQuietly(stereoscopic$rightNoHand);
            stereoscopic$rightNoTranslucents = new DepthTexture("Depth / Opaque (eye R)",
                cachedWidth, cachedHeight, currentDepthFormat);
            stereoscopic$rightNoHand          = new DepthTexture("Depth / Before Hand (eye R)",
                cachedWidth, cachedHeight, currentDepthFormat);

            stereoscopic$activeEye = -1;
        } catch (Throwable t) {
            stereoscopic$dropSiblingBank("resize failed", t);
        }
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void stereoscopic$destroySiblingBank(CallbackInfo ci) {
        PerEyeRenderTargetHooks.unregisterActiveTargets(this);
        if (!stereoscopic$enabled) return;
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
        stereoscopic$destroyQuietly(stereoscopic$rightNoTranslucents);
        stereoscopic$destroyQuietly(stereoscopic$rightNoHand);
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
        stereoscopic$destroyQuietly(stereoscopic$rightNoTranslucents);
        stereoscopic$destroyQuietly(stereoscopic$rightNoHand);
        stereoscopic$leftNoTranslucents = null;
        stereoscopic$leftNoHand = null;
        stereoscopic$rightNoTranslucents = null;
        stereoscopic$rightNoHand = null;
        stereoscopic$reverseMap = null;
        stereoscopic$enabled = false;
    }

    @Unique
    private static void stereoscopic$destroyQuietly(DepthTexture tex) {
        if (tex == null) return;
        try { tex.destroy(); }
        catch (Throwable t) { Stereoscopic.LOG.warn("[stereo] depth-sibling destroy failed; GPU texture leaked", t); }
    }
}
