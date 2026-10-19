package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mitchellmarx.stereoscopic.cursor.CursorPresentThread;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Suppress MC's {@code SDL_GL_SwapWindow} when the cursor present thread is
 * driving window presentation itself. Two swaps per frame on a
 * double-buffered chain (cursor thread's GDI {@code SwapBuffers(mainHdc)}
 * + MC's {@code SDL_GL_SwapWindow}) flip front/back/front/back in alternation;
 * MC's swap immediately presents the other backbuffer (which nothing wrote
 * to since we also cancel the swapchain blit in {@link MixinMinecraftClient}),
 * producing fresh/stale/fresh/stale flicker.
 *
 * <p>MC 26.2 moved presentation from {@code GlDevice.presentFrame()} into the
 * surface abstraction: {@code GpuSurface.present()}, implemented by the
 * (package-private) {@code GlSurface}, whose body is exactly the
 * {@code SDL_GL_SwapWindow(windowHandle)} call we wrap (26.3 replaced GLFW
 * with SDL3 and moved GlSurface to {@code com.mojang.renderpearl}). When the
 * cursor thread isn't running (mono, non-Windows, setup failure), the wrapper
 * passes through.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlSurface")
public abstract class MixinGlSurface {

    @WrapOperation(
        method = "present()V",
        at = @At(value = "INVOKE",
                 target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GL_SwapWindow(J)Z")
    )
    private boolean stereoscopic$skipMcSwapWhenCursorThreadOwnsPresent(long window, Operation<Boolean> original) {
        if (CursorPresentThread.isRunning()) return true;
        return original.call(window);
    }
}
