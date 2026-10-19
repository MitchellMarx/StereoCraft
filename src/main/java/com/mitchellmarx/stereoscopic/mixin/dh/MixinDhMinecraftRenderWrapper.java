package com.mitchellmarx.stereoscopic.mixin.dh;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mitchellmarx.stereoscopic.core.StereoState;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Per-eye camera position for Distant Horizons (issue #5). DH builds its LOD
 * model-view from {@code getCameraExactPosition()}, which reads the live
 * {@code Camera} — mono, since the per-eye loop only moves
 * {@code CameraRenderState.pos}. DH's projection does carry the convergence
 * shear, so LOD terrain got the shear but no eye separation: zero parallax,
 * the whole DH landscape flattened onto the convergence plane while entities
 * keep their depth ("entities pop out way more than the environment").
 * Applies to DH's own renderer and to its Iris shader-pack programs.
 */
@Mixin(value = MinecraftRenderWrapper.class, remap = false)
public abstract class MixinDhMinecraftRenderWrapper {

    @ModifyReturnValue(method = "getCameraExactPosition", at = @At("RETURN"))
    private DhVec3d stereoscopic$eyePosition(DhVec3d pos) {
        Vec3 offset = StereoState.INSTANCE.getEyeCameraOffset();
        if (pos == null || offset == Vec3.ZERO) return pos;
        return new DhVec3d(pos.x + offset.x, pos.y + offset.y, pos.z + offset.z);
    }
}
