package com.mitchellmarx.stereoscopic.mixin.iris;

import com.mitchellmarx.stereoscopic.iris.IStereoPipeline;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds {@link IStereoPipeline} as a parent of Iris's {@code WorldRenderingPipeline} at runtime,
 * so the {@code setActiveEye(int)} no-op default becomes part of every pipeline instance. The
 * stereo-aware code in {@code MixinEntityRenderer_Stereo} casts the pipeline to
 * {@code IStereoPipeline} and calls {@code setActiveEye} between LEFT and RIGHT passes.
 *
 * <p>Plan 2 mixins override {@code setActiveEye} on concrete pipeline implementations
 * (e.g. {@code DeferredWorldRenderingPipeline}) to do the per-eye RenderTargets swap; non-stereo
 * pipelines keep the no-op default.
 */
@Mixin(WorldRenderingPipeline.class)
public interface MixinWorldRenderingPipeline_SetActiveEye extends IStereoPipeline {
    // Inherits the no-op default from IStereoPipeline; no body needed.
}
