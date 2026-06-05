package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.core.StereoMode;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Halve in-game look X delta under SBS_HALF. GLFW mouse delta is raw
 * window-pixel units; with the framebuffer split horizontally and each eye
 * showing a compressed half, a sweep that "feels" full-screen in eye-view
 * space corresponds to 2× the camera yaw it should. Y stays — vertical isn't
 * compressed.
 *
 * <p>{@code @ModifyArg} after sensitivity scaling + {@code invertMouseX}
 * negation, so a simple 0.5 multiply preserves sign.
 *
 * <p>Mojang names for 1.21.1: {@code MouseHandler.turnPlayer(double)} is
 * private, called from {@code handleAccumulatedMovement()} with the elapsed
 * time delta; inside, {@code accumulatedDX}/{@code accumulatedDY} are scaled
 * by sensitivity and {@code minecraft.player.turn(dx, dy)} is called.
 * (Yarn: Mouse.updateMouse(D) calling ClientPlayerEntity.changeLookDirection(DD).)
 */
@Mixin(MouseHandler.class)
public abstract class MixinMouse {

    @ModifyArg(
        method = "turnPlayer(D)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"),
        index = 0
    )
    private double stereoscopic$halveDxForSbsHalf(double dx) {
        if (StereoState.INSTANCE.getFrameMode() == StereoMode.SBS_HALF) {
            return dx * 0.5;
        }
        return dx;
    }
}
