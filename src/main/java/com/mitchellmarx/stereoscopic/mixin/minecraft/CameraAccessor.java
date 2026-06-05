package com.mitchellmarx.stereoscopic.mixin.minecraft;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invoker for {@link Camera}'s protected {@code setPos}. Shifting Camera.pos
 * is the architecturally correct fix for 1.21 stereo — propagates to every
 * downstream consumer that reads camera position (Sodium chunk transforms,
 * frustum culling, fog distance, dynamic lighting, Iris's cameraPosition
 * uniform). Shifting the modelview matrix downstream misses those consumers.
 */
@Mixin(Camera.class)
public interface CameraAccessor {
    @Invoker("setPosition") void stereoscopic$setPos(Vec3 pos);
}
