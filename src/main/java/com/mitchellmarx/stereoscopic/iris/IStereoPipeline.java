package com.mitchellmarx.stereoscopic.iris;

/**
 * Duck interface that {@code MixinWorldRenderingPipeline_SetActiveEye} attaches to Iris's
 * {@code WorldRenderingPipeline} at runtime, so stereo-aware code can cast and call
 * {@code setActiveEye(int)} against a known type at compile time. Mixin's `@Unique default`
 * adds the method at runtime but the Java compiler can't see it directly on
 * {@code WorldRenderingPipeline}; routing the call through this duck interface bridges the gap.
 *
 * <p>Plan 1 ships a no-op default. Plan 2 mixins override {@code setActiveEye} on concrete
 * pipeline implementations to do per-eye RenderTargets swap.
 *
 * <p>Lives in {@code src/main/java} (not {@code src/mixin/java}) because Mixin's classloader
 * refuses to load classes inside a declared-mixin-package directly. The duck interface is
 * referenced as a regular type from both the mixin and {@code MixinEntityRenderer_Stereo}'s
 * cast site, so it must NOT be inside {@code com.mitchellmarx.stereoscopic.mixin.*}.
 */
public interface IStereoPipeline {
    default void setActiveEye(int eye) {
        // No-op default. Concrete pipelines override via Plan 2 mixins.
    }
}
