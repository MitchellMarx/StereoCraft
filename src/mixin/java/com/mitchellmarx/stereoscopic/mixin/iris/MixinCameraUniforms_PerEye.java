package com.mitchellmarx.stereoscopic.mixin.iris;

import com.mitchellmarx.stereoscopic.core.StereoState;
import net.coderbot.iris.uniforms.CameraUniforms;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

// CameraPositionTracker is package-private in net.coderbot.iris.uniforms — target by string name.

/**
 * Per-eye storage for {@code CameraUniforms.CameraPositionTracker}'s position history slots.
 *
 * <p>Without this, the RIGHT eye's {@code update()} overwrites previousCameraPosition with
 * LEFT eye's current-frame position, so RIGHT eye shaders see zero motion between previous
 * and current — temporal effects (motion blur, TAA) desync per eye.
 *
 * <p>Port of sbs2's cumulative {@code master...stereo-sbs-2} diff for this file.
 */
@Mixin(targets = "net.coderbot.iris.uniforms.CameraUniforms$CameraPositionTracker", remap = false)
public abstract class MixinCameraUniforms_PerEye {

    @Unique private final Vector3d[] stereoscopic$prev = new Vector3d[] { new Vector3d(), new Vector3d() };
    @Unique private final Vector3d[] stereoscopic$cur  = new Vector3d[] { new Vector3d(), new Vector3d() };
    @Unique private final Vector3d[] stereoscopic$prevUnshifted = new Vector3d[] { new Vector3d(), new Vector3d() };
    @Unique private final Vector3d[] stereoscopic$curUnshifted  = new Vector3d[] { new Vector3d(), new Vector3d() };
    @Unique private final Vector3d stereoscopic$shift = new Vector3d();

    /**
     * @author stereoscopic
     * @reason Per-eye previousCameraPosition (overrides Lombok @Getter from master).
     */
    @Overwrite
    public Vector3d getPreviousCameraPosition() {
        return stereoscopic$prev[StereoState.INSTANCE.currentEyeIndex()];
    }

    /**
     * @author stereoscopic
     * @reason Per-eye currentCameraPosition (overrides Lombok @Getter from master).
     */
    @Overwrite
    public Vector3d getCurrentCameraPosition() {
        return stereoscopic$cur[StereoState.INSTANCE.currentEyeIndex()];
    }

    /**
     * @author stereoscopic
     * @reason Per-eye history update (sbs2 rewrite).
     */
    @Overwrite
    private void update() {
        final int eye = StereoState.INSTANCE.currentEyeIndex();
        stereoscopic$prev[eye].set(stereoscopic$cur[eye]);
        stereoscopic$prevUnshifted[eye].set(stereoscopic$curUnshifted[eye]);
        stereoscopic$cur[eye].set(CameraUniforms.getUnshiftedCameraPosition()).add(stereoscopic$shift);
        stereoscopic$curUnshifted[eye].set(CameraUniforms.getUnshiftedCameraPosition());
        stereoscopic$updateShift();
    }

    @Unique
    private void stereoscopic$updateShift() {
        final int eye = StereoState.INSTANCE.currentEyeIndex();
        final Vector3d cur  = stereoscopic$cur[eye];
        final Vector3d prev = stereoscopic$prev[eye];
        final double dX = stereoscopic$getShift(cur.x, prev.x);
        final double dZ = stereoscopic$getShift(cur.z, prev.z);
        if (dX != 0.0 || dZ != 0.0) stereoscopic$applyShift(dX, dZ);
    }

    @Unique
    private static double stereoscopic$getShift(double value, double prevValue) {
        final double WALK_RANGE = 30000;
        final double TP_RANGE = 1000;
        if (Math.abs(value) > WALK_RANGE || Math.abs(value - prevValue) > TP_RANGE) {
            return -(value - (value % WALK_RANGE));
        }
        return 0.0;
    }

    @Unique
    private void stereoscopic$applyShift(double dX, double dZ) {
        stereoscopic$shift.x += dX;
        stereoscopic$shift.z += dZ;
        for (int e = 0; e < stereoscopic$cur.length; e++) {
            stereoscopic$cur[e].x  += dX;
            stereoscopic$prev[e].x += dX;
            stereoscopic$cur[e].z  += dZ;
            stereoscopic$prev[e].z += dZ;
        }
    }

    /**
     * @author stereoscopic
     * @reason Per-eye Y getter (sbs2 rewrite).
     */
    @Overwrite
    public double getCurrentCameraPositionY() {
        return stereoscopic$cur[StereoState.INSTANCE.currentEyeIndex()].y;
    }

    /**
     * @author stereoscopic
     * @reason Per-eye unshifted getter (sbs2 rewrite).
     */
    @Overwrite
    public Vector3d getPreviousCameraPositionUnshifted() {
        return stereoscopic$prevUnshifted[StereoState.INSTANCE.currentEyeIndex()];
    }
}
