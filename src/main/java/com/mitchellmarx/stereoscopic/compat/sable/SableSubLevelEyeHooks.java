package com.mitchellmarx.stereoscopic.compat.sable;

import net.neoforged.fml.ModList;

/**
 * Sable (Create Aeronautics sub-level physics) compat facade.
 *
 * <p>Sable's {@code dev.ryanhcode.sable.mixin.sublevel_render.LevelRendererMixin}
 * injects sub-level draws inside {@code LevelRenderer.renderLevel}. Our
 * {@link com.mitchellmarx.stereoscopic.mixin.minecraft.MixinGameRenderer}'s
 * {@code stereoscopic$twoPassRenderWorld} wraps the entire
 * {@code GameRenderer.render()} → {@code renderLevel(...)} call in a per-eye
 * loop. Because Sable's injection is INSIDE renderLevel and our wrap is
 * AROUND it, Sable's sub-level draws execute per-eye automatically — the
 * per-eye colortex bank is already active (via
 * {@link com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks#setActiveEye})
 * when Sable's draw fires, so sub-level output lands in the active eye's bank.
 *
 * <p>Mixin priority: our {@code MixinGameRenderer} declares
 * {@code priority = 2000} (vanilla mixin default is 1000) so our
 * {@code @WrapOperation} on the renderLevel call site is applied OUTSIDE
 * Sable's {@code clip_overwrite/GameRendererMixin.sable$renderLevel} wrap.
 * Result: each eye iteration runs the full Sable clip_overwrite logic; setup
 * and teardown happen per eye (correct), not once across both eyes.
 *
 * <p>This class is a documentation marker + Sable-presence flag. It does not
 * directly hook anything — all coordination is implicit through the wrap
 * ordering above.
 */
public final class SableSubLevelEyeHooks {

    private SableSubLevelEyeHooks() {}

    public static final boolean SABLE_PRESENT = ModList.get().isLoaded("sable");
}
