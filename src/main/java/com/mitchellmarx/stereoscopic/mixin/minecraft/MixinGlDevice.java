package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mitchellmarx.stereoscopic.cursor.CursorPresentThread;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Suppress MC's {@code glfwSwapBuffers} when the cursor present thread is
 * driving window presentation itself. Two swaps per frame on a
 * double-buffered chain (cursor thread's GDI {@code SwapBuffers(mainHdc)}
 * + MC's {@code glfwSwapBuffers}) flip front/back/front/back in alternation;
 * MC's swap immediately presents the other backbuffer (which nothing wrote
 * to since we also cancel {@code RenderTarget.blitToScreen}), producing the
 * fresh/stale/fresh/stale flicker the user sees.
 *
 * <p>MC 26.x moved window presentation out of {@code RenderSystem.flipFrame}
 * and into the GPU device: {@code GpuDevice.presentFrame()}, implemented by
 * the (package-private) {@code GlDevice}, is what calls {@code glfwSwapBuffers}
 * now. Only the swap is skipped; the rest of {@code presentFrame} (vsync etc.)
 * still runs. When the cursor thread isn't running (mono, non-Windows, setup
 * failure), the wrapper passes through.
 */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlDevice")
public abstract class MixinGlDevice {

    @WrapOperation(
        method = "presentFrame()V",
        at = @At(value = "INVOKE",
                 target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V")
    )
    private void stereoscopic$skipMcSwapWhenCursorThreadOwnsPresent(long window, Operation<Void> original) {
        if (CursorPresentThread.isRunning()) return;
        original.call(window);
    }
}
