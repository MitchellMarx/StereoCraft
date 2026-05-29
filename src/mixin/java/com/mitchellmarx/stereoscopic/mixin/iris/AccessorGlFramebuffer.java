package com.mitchellmarx.stereoscopic.mixin.iris;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import net.coderbot.iris.gl.framebuffer.GlFramebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only accessor for {@link GlFramebuffer}'s private attachments map. Used by
 * {@link MixinRenderTargets} during {@code stereoscopic$setActiveEye} to walk every color
 * attachment on every owned framebuffer and rebind to the active eye's bank.
 */
@Mixin(value = GlFramebuffer.class, remap = false)
public interface AccessorGlFramebuffer {
    @Accessor("attachments")
    Int2IntMap stereoscopic$getAttachments();
}
