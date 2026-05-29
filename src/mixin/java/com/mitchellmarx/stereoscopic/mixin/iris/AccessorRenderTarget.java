package com.mitchellmarx.stereoscopic.mixin.iris;

import net.coderbot.iris.rendertarget.RenderTarget;
import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invoker for {@link RenderTarget}'s package-private {@code resize(Vector2i)} method, so
 * {@link MixinRenderTargets} can resize the RIGHT-bank sibling colortex textures during
 * {@code resizeIfNeeded} from outside Iris's package.
 */
@Mixin(value = RenderTarget.class, remap = false)
public interface AccessorRenderTarget {
    @Invoker("resize")
    void stereoscopic$resize(Vector2i textureScaleOverride);
}
