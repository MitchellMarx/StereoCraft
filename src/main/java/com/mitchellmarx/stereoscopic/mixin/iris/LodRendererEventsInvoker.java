package com.mitchellmarx.stereoscopic.mixin.iris;

import net.irisshaders.iris.compat.dh.DHCompatInternal;
import net.irisshaders.iris.compat.dh.LodRendererEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code LodRendererEvents.getInstance()} (the active {@link DHCompatInternal})
 * is private; the post-resize DH viewport-uniform refresh in
 * {@link com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks}
 * needs it to reach the live DH render programs.
 */
@Mixin(value = LodRendererEvents.class, remap = false)
public interface LodRendererEventsInvoker {
    @Invoker("getInstance")
    static DHCompatInternal stereoscopic$getInstance() {
        throw new AssertionError("mixin invoker not applied");
    }
}
