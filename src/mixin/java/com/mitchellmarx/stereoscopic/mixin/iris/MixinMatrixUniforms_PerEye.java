package com.mitchellmarx.stereoscopic.mixin.iris;

import com.mitchellmarx.stereoscopic.core.StereoState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.function.Supplier;

/**
 * Per-eye history slot for {@code MatrixUniforms.Previous} (private static inner class wrapping
 * a {@code Supplier<Matrix4fc>}). With one shared slot the LEFT eye would read RIGHT eye N-1's
 * matrix (wrong IPD offset, stale by a frame) and RIGHT eye would read LEFT eye N's matrix
 * (wrong IPD, same frame) — surfacing as eye-asymmetric artifacts in any shader that reprojects
 * against {@code gbufferPrevious*}.
 *
 * <p>Targeted by string since the class is private.
 */
@Mixin(targets = "net.coderbot.iris.uniforms.MatrixUniforms$Previous", remap = false)
public abstract class MixinMatrixUniforms_PerEye {

    @Shadow @Final private Supplier<Matrix4fc> parent;

    @Unique private final Matrix4f[] stereoscopic$previousPerEye = new Matrix4f[] { new Matrix4f(), new Matrix4f() };

    /**
     * @author stereoscopic
     * @reason Per-eye history slot read/write (sbs2 rewrite).
     */
    @Overwrite
    public Matrix4f get() {
        final int eye = StereoState.INSTANCE.currentEyeIndex();
        final Matrix4f copy = new Matrix4f(parent.get());
        final Matrix4f prev = new Matrix4f(stereoscopic$previousPerEye[eye]);
        stereoscopic$previousPerEye[eye] = copy;
        return prev;
    }
}
