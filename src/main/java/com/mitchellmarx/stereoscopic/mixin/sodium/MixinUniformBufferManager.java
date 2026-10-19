package com.mitchellmarx.stereoscopic.mixin.sodium;

import com.mitchellmarx.stereoscopic.core.StereoState;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-arm Sodium 0.9's once-per-frame terrain globals upload for each eye.
 *
 * <p>{@code UniformBufferManager.update} writes the {@code u_Globals} UBO
 * (projection + model-view + fog) and then sets {@code hasUpdatedThisFrame},
 * which only {@code prepareFrame()} clears — and that runs from
 * {@code setupTerrain}, which this branch runs on the LEFT eye only
 * ({@link MixinSodiumWorldRenderer} skips it on RIGHT). The upload itself is
 * per terrain layer ({@code RenderSectionManager.renderLayer}), so the RIGHT
 * eye's terrain draws reused the LEFT eye's projection: the convergence shear
 * was identical in both eyes and cancelled, leaving terrain at parallel-camera
 * disparity while entities (vanilla projection UBO) got the shear. Measured by
 * the gametest harness on 26.2 (same Sodium 0.9 code).
 *
 * <p>Same one-shot-state-consumed-by-LEFT class as the submitFeatures drains.
 */
@Mixin(UniformBufferManager.class)
public abstract class MixinUniformBufferManager {

    @Shadow private boolean hasUpdatedThisFrame;

    @Unique private StereoState.Eye stereoscopic$uploadedFor = StereoState.Eye.MONO;

    @Inject(method = "update", at = @At("HEAD"))
    private void stereoscopic$reuploadPerEye(ChunkRenderMatrices matrices, FogParameters fog, CallbackInfo ci) {
        StereoState s = StereoState.INSTANCE;
        StereoState.Eye eye = s.isActive() ? s.getCurrentEye() : StereoState.Eye.MONO;
        if (eye != stereoscopic$uploadedFor) {
            stereoscopic$uploadedFor = eye;
            hasUpdatedThisFrame = false;
        }
    }
}
