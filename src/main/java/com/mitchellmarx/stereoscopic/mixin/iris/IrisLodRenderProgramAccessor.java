package com.mitchellmarx.stereoscopic.mixin.iris;

import net.irisshaders.iris.compat.dh.IrisLodRenderProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the GL program handle so
 * {@link com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks}
 * can re-upload the program-lifetime {@code viewWidth}/{@code viewHeight}
 * uniforms after a window resize (see the resize watch there). Deliberately
 * not using {@code bind()} for that — it also applies the program's blend
 * override.
 */
@Mixin(value = IrisLodRenderProgram.class, remap = false)
public interface IrisLodRenderProgramAccessor {
    @Accessor("id") int stereoscopic$getId();
}
