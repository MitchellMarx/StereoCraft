package com.mitchellmarx.stereoscopic.mixin.minecraft;

import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Direct field accessors for {@link Camera}'s {@code position} and
 * {@code blockPosition}. Shifting Camera.pos is the architecturally correct
 * fix for 1.21 stereo — propagates to every downstream consumer that reads
 * camera position (Sodium chunk transforms, frustum culling, fog distance,
 * dynamic lighting, Iris's {@code cameraPosition} uniform). Shifting the
 * modelview matrix downstream misses those consumers.
 *
 * <p><b>Why field write and not {@code @Invoker setPosition}.</b> Sable wraps
 * <em>both</em> {@code Camera.setPosition} overloads with {@code @WrapOperation}:
 * <ul>
 *   <li>{@code entity/entity_sublevel_collision/CameraMixin} wraps
 *       {@code setPosition(DDD)V} with a captured {@code Entity} local that
 *       calls {@code Sable.HELPER.getTrackingOrVehicleSubLevel(entity)}.</li>
 *   <li>{@code neoforge/camera_rotation/CameraMixin} wraps
 *       {@code setPosition(Vec3)V} and at the very first instruction does
 *       {@code this.entity.level()}.</li>
 * </ul>
 * Both NPE when {@code Camera.entity} is null, which happens briefly during
 * world load / dimension transition (between {@code Camera.setup} clearing
 * state and the next setup populating entity). Going through either overload's
 * invoker would crash the loading screen.
 *
 * <p>Writing {@code position} and updating {@code blockPosition} directly
 * mirrors exactly what {@code setPosition(Vec3)} does in vanilla 1.21.1 (read
 * from {@code Camera.class} bytecode: assign {@code position} field, then
 * {@code blockPosition.set(vec.x, vec.y, vec.z)}). Bypassing Sable's wraps is
 * correct here because the {@code basePos} we read back was already
 * Sable-transformed by the original {@code Camera.setup} call earlier in
 * {@code renderLevel}; we just add a small IPD offset along the camera's
 * already-transformed local right vector.
 */
@Mixin(Camera.class)
public interface CameraAccessor {
    @Accessor("position")
    void stereoscopic$setPositionField(Vec3 pos);

    /** Mutable — call {@link BlockPos.MutableBlockPos#set(double, double, double)} on the returned instance. */
    @Accessor("blockPosition")
    BlockPos.MutableBlockPos stereoscopic$getBlockPositionField();
}
