package com.mitchellmarx.stereoscopic.compat.voxy;

import com.mitchellmarx.stereoscopic.Stereoscopic;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import net.irisshaders.iris.targets.RenderTarget;
import net.neoforged.fml.ModList;

import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL45C.glNamedFramebufferTexture;

/**
 * Per-eye rebind for Voxy's Iris-mode framebuffers. Mirrors the Fabric port's
 * {@code compat/voxy/VoxyEyeRebindHooks}: Voxy's {@code IrisVoxyRenderPipeline}
 * snapshots Iris colortex texture IDs into its own two framebuffers at pipeline
 * build, and those FBs aren't in Iris's {@code ownedFramebuffers}, so
 * {@code MixinRenderTargets.stereoscopic$setActiveEye}'s per-eye attachment walk
 * misses them — Voxy then writes LOD pixels into the LEFT-bank colortex for both
 * eyes and the RIGHT-eye composite reads an empty gbuffer.
 *
 * <p><b>DISABLED on NeoForge.</b> The only NeoForge-native Voxy build
 * ({@code voxy-neoforge}, 0.2.9-alpha, MC 1.21.1) <em>excludes its entire Iris
 * integration</em> from compilation (its {@code build.gradle} drops
 * {@code me/cortex/voxy/client/iris/**}, {@code IrisVoxyRenderPipeline}, and
 * {@code mixin/iris/**} — comment: "not all available for NeoForge 1.21.1").
 * With Voxy's Iris-mode pipeline absent, there is no
 * {@code IrisVoxyRenderPipeline} to rebind, so this hook has nothing to act on,
 * and the Voxy-internal classes it would reference aren't present at runtime.
 *
 * <p>{@link #IRIS_INTEGRATION_AVAILABLE} is therefore {@code false}, and
 * {@link #rebindForEye} returns before any Voxy class is touched. Because
 * {@link #doRebind} is never invoked, the JVM never links its Voxy references
 * (lazy resolution), so the missing {@code me.cortex.voxy.client.iris.*} classes
 * cause no {@code NoClassDefFoundError}. The code is kept verbatim against the
 * Fabric reference so that, if a future {@code voxy-neoforge} ships its Iris
 * pipeline, re-enabling is a one-line flag flip (plus confirming the Voxy field
 * layout matches).
 */
public final class VoxyEyeRebindHooks {

    private VoxyEyeRebindHooks() {}

    /**
     * Master switch. {@code false} until a NeoForge Voxy build actually ships the
     * Iris-mode pipeline (see class javadoc). Keep it {@code false} so this
     * compat is inert on NeoForge — it must NOT reach {@link #doRebind}, whose
     * {@code me.cortex.voxy.client.iris.*} references aren't on the runtime
     * classpath.
     */
    private static final boolean IRIS_INTEGRATION_AVAILABLE = false;

    public static final boolean VOXY_PRESENT =
        ModList.get() != null && ModList.get().isLoaded("voxy");

    public static void rebindForEye(RenderTarget[] sourceBank, Int2IntMap reverseMap) {
        // Disabled on NeoForge: voxy-neoforge excludes its Iris pipeline, so there is
        // nothing to rebind and the Voxy iris classes are absent at runtime. This guard
        // MUST come first so doRebind is never linked. See class javadoc.
        if (!IRIS_INTEGRATION_AVAILABLE) return;
        if (!VOXY_PRESENT) return;
        try {
            doRebind(sourceBank, reverseMap);
        } catch (Throwable t) {
            Stereoscopic.LOG.warn("Voxy FB rebind for active eye failed; LODs may render one-eyed this frame", t);
        }
    }

    private static void doRebind(RenderTarget[] sourceBank, Int2IntMap reverseMap) {
        net.irisshaders.iris.pipeline.WorldRenderingPipeline wp =
            net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
        if (!(wp instanceof me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData getter)) return;
        me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData data = getter.voxy$getPipelineData();
        if (data == null) return;
        me.cortex.voxy.client.core.IrisVoxyRenderPipeline pipe = data.thePipeline;
        if (pipe == null) return;

        rebindOne(pipe.fb.framebuffer.id,            data.opaqueDrawTargets,     sourceBank, reverseMap);
        rebindOne(pipe.fbTranslucent.framebuffer.id, data.translucentDrawTargets, sourceBank, reverseMap);
    }

    /**
     * {@code capturedOriginalTexIds} holds the LEFT-bank texture IDs voxy
     * captured at pipeline build, by attachment-slot index. Those originals
     * stay in the array even after we glNamedFramebufferTexture the live FB
     * to a different bank — they're our lookup key into {@code reverseMap},
     * which packs {@code (colortexSlot << 1) | (isAlt ? 1 : 0)}.
     */
    private static void rebindOne(int fbId, int[] capturedOriginalTexIds,
                                   RenderTarget[] bank, Int2IntMap reverseMap) {
        for (int i = 0; i < capturedOriginalTexIds.length; i++) {
            int meta = reverseMap.get(capturedOriginalTexIds[i]);
            if (meta < 0) continue;
            int slot = meta >>> 1;
            boolean isAlt = (meta & 1) == 1;
            if (slot >= bank.length) continue;
            RenderTarget rt = bank[slot];
            if (rt == null) continue;
            int newTex = isAlt ? rt.getAltTexture() : rt.getMainTexture();
            glNamedFramebufferTexture(fbId, GL_COLOR_ATTACHMENT0 + i, newTex, 0);
        }
    }
}
