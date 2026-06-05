package com.mitchellmarx.stereoscopic.mixin.iris;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for the package-private {@code attachments} map. MixinRenderTargets
 * walks this on eye switch to find color slots referencing known colortex
 * textures and rebinds them to the active eye's sibling bank.
 */
@Mixin(value = GlFramebuffer.class, remap = false)
public interface MixinGlFramebuffer {

    @Accessor("attachments")
    Int2IntMap stereoscopic$getAttachments();
}
