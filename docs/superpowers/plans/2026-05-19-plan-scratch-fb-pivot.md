# Plan: Scratch-FB World Rendering — Architectural Pivot

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Plan 2 Phase 3's sbs2-style per-eye Iris `RenderTargets` machinery with the Fabric port's scratch-framebuffer-and-blit-squish design. The result: world chunks split L/R per eye both with AND without a shaderpack loaded (the sbs2 design only worked with shaders).

**Architecture:** Each eye renders the world into a single owned **scratch framebuffer sized to the full main FB**. A `Minecraft.getFramebuffer()` HEAD inject returns the scratch FB transparently to every caller while a per-eye flag is set, so the world renderer, Iris pipeline, Sodium chunk paths, and Distant Horizons all naturally write into scratch at full size. After each eye's `renderWorld` returns, blit-squish scratch → main FB's eye region with `glBlitFramebuffer` + `GL_LINEAR`.

This is the canonical implementation from `C:\CODE\Stereoscopic` (the Fabric port — see `mixin/minecraft/MixinGameRenderer.java` and `render/PerEyeRenderer.java`). The sbs2 fork uses a similar pattern but only via Iris's per-eye RenderTargets, which means it only fires when shaders are loaded. The Fabric pattern owns the scratch FB itself, so it works in every mode.

**Tech Stack:** Same as Plan 1/2/3. Adds `net.minecraft.client.shader.Framebuffer` allocation + lifecycle management.

**Reference port source:** `C:\CODE\Stereoscopic` branch `main`:
- `src/main/java/com/mitchellmarx/stereoscopic/render/PerEyeRenderer.java` — scratch-FB lifecycle helper.
- `src/main/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinMinecraftClient.java` — `getFramebuffer()` HEAD inject.
- `src/main/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinGameRenderer.java` — two-pass `renderWorld` wrap + blit-squish.

**Prerequisites:** Plan 1, Plan 2 Phase 1+2+4+5, Plan 3 all shipped. Plan 2 Phase 3 (per-eye Iris RenderTargets) was shipped but is now being superseded by this plan. Plan 3's cursor work passed smoke test. The mod compiles and runs without crashes against mainline Angelica 2.1.23.

**This plan supersedes** Plan 2 Phase 3 Tasks 4, 7, 8, 9, 10, 11. Tasks 5 (`MixinCameraUniforms_PerEye`) and 6 (`MixinMatrixUniforms_PerEye`) **stay** — Iris's per-frame temporal uniforms still need per-eye history slots even under the scratch-FB design, because each eye's `update()` runs separately.

---

## Architectural rationale

**Why the pivot:** Plan 2 Phase 3 ported sbs2's per-eye RenderTargets. After the manual smoke test, the user verified that **sbs2's `remapWorldPassViewport` is a deliberate no-op for the main-FB case** (`StereoGLSMBridge.java` lines 35–46 — returns `(0, 0, displayWidth, displayHeight)`, same as input). Sbs2 only achieves per-eye world chunks when Iris's per-eye intermediate FBO chain is active, which requires a shaderpack. Sbs2's no-shaders mode also renders world chunks mono.

Fabric's port (`C:\CODE\Stereoscopic`) solves both cases with one design: own a full-size scratch FB, substitute it via `getFramebuffer()` interception during each eye's `renderWorld`, blit-squish into the main FB's eye region afterward. The pipeline (Iris or vanilla) thinks it's rendering to a normal full-size framebuffer — no special handling needed.

**Side benefits:**
- Removes 5 brittle Iris-internal mixins (`MixinRenderTargets_PerEye`, `MixinDeferredWorldRenderingPipeline_PerEye`, `MixinFinalPassRenderer_PerEye`, `MixinIrisSamplers_PerEye`, `MixinViewportUniforms_PerEye`) plus their support code (`IPerEyeRenderTargets` interface, `OwnedFb`, `AccessorDepthTexture`, `AccessorRenderTarget`).
- Eliminates the need for Plan 1 Task 1b's `MixinGLStateManager_StereoRemap` (which can't actually apply anyway due to lwjgl3ify exclusion — see architectural finding #4 in handoff).
- The scratch-FB design has been proven to work in production (Fabric port).

**Trade-off:** One extra GPU framebuffer worth of VRAM (display-resolution-sized RGBA8 + depth-stencil) is permanently allocated while stereo is on. At 1080p ≈ 8 MB color + 8 MB depth = 16 MB. Negligible vs Iris's RenderTargets allocations.

---

## File Structure

**Created:**

| Path | Purpose |
|---|---|
| `src/main/java/com/mitchellmarx/stereoscopic/render/PerEyeScratchFb.java` | Scratch FB allocation, resize, lifecycle, active-flag, blit helper. |
| `src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinMinecraft_StereoScratchFb.java` | HEAD inject on `Minecraft.getFramebuffer()` returning scratch when `PerEyeScratchFb.isActive()`. |

**Modified:**

| Path | Why |
|---|---|
| `src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinEntityRenderer_Stereo.java` | Replace the `stereoscopic$stereoRenderWorld` redirect body. Old: set eye viewport + scissor on main FB, call renderWorld, restore. New: ensure scratch FB sized, activate, set full viewport on scratch, call renderWorld, deactivate, blit-squish to main FB eye region. |
| `src/main/resources/mixins.stereoscopic.json` | Add new mixin, remove deprecated ones. |

**Deleted (reverts):**

| Commit | Files removed |
|---|---|
| `196e4eb` | `IPerEyeRenderTargets.java`, `OwnedFb.java`, `AccessorDepthTexture.java`, `AccessorRenderTarget.java`, `MixinRenderTargets_PerEye.java` |
| `8f92435` | `MixinViewportUniforms_PerEye.java` |
| `3c7c9ed` | `MixinIrisSamplers_PerEye.java` |
| `3db6e92` | `MixinDeferredWorldRenderingPipeline_PerEye.java` |
| `c0902fc` | `MixinFinalPassRenderer_PerEye.java` |

**Retained (still useful under new design):**

| Commit | File | Why kept |
|---|---|---|
| `5266c28` | `MixinCameraUniforms_PerEye.java` | Iris's `CameraPositionTracker.update()` runs once per `renderWorld` invocation (twice per frame in stereo). Without per-eye `previousCameraPosition` history slots, temporal shader effects (motion blur, TAA) desync per eye. |
| `39991bc` | `MixinMatrixUniforms_PerEye.java` | Same reasoning, for `MatrixUniforms.Previous.get()` — per-eye history slot. |
| `8194e2c` (Plan 1) | `MixinWorldRenderingPipeline_SetActiveEye.java` | The interface mixin adding `setActiveEye(int)` is unused under the new design but harmless; leave it in tree per the verbatim-port rule. |

---

## Phase 1 — Revert sbs2-style per-eye Iris mixins

### Task 1: Revert MixinRenderTargets + its support classes

**Files affected:**
- Delete: `src/main/java/com/mitchellmarx/stereoscopic/compat/iris/IPerEyeRenderTargets.java`
- Delete: `src/main/java/com/mitchellmarx/stereoscopic/compat/iris/OwnedFb.java`
- Delete: `src/mixin/java/com/mitchellmarx/stereoscopic/mixin/iris/AccessorDepthTexture.java`
- Delete: `src/mixin/java/com/mitchellmarx/stereoscopic/mixin/iris/AccessorRenderTarget.java`
- Delete: `src/mixin/java/com/mitchellmarx/stereoscopic/mixin/iris/MixinRenderTargets_PerEye.java`

- [ ] **Step 1: Revert commit `196e4eb`**

```bash
git -C /c/CODE/Stereoscopic-Angelica revert --no-edit 196e4eb
```

Expected: clean revert. Commits stay in history (showing the design exploration); the revert commit cleanly removes the files.

- [ ] **Step 2: Verify the deletion landed**

```bash
ls src/main/java/com/mitchellmarx/stereoscopic/compat/iris/ 2>&1
ls src/mixin/java/com/mitchellmarx/stereoscopic/mixin/iris/AccessorDepthTexture.java 2>&1
```

Expected: both report "No such file or directory" (or empty listing).

### Task 2: Revert MixinViewportUniforms_PerEye

**Files affected:**
- Delete: `src/mixin/java/com/mitchellmarx/stereoscopic/mixin/iris/MixinViewportUniforms_PerEye.java`

- [ ] **Step 1: Revert commit `8f92435`**

```bash
git -C /c/CODE/Stereoscopic-Angelica revert --no-edit 8f92435
```

### Task 3: Revert MixinIrisSamplers_PerEye

**Files affected:**
- Delete: `src/mixin/java/com/mitchellmarx/stereoscopic/mixin/iris/MixinIrisSamplers_PerEye.java`

- [ ] **Step 1: Revert commit `3c7c9ed`**

```bash
git -C /c/CODE/Stereoscopic-Angelica revert --no-edit 3c7c9ed
```

### Task 4: Revert MixinDeferredWorldRenderingPipeline_PerEye

**Files affected:**
- Delete: `src/mixin/java/com/mitchellmarx/stereoscopic/mixin/iris/MixinDeferredWorldRenderingPipeline_PerEye.java`

- [ ] **Step 1: Revert commit `3db6e92`**

```bash
git -C /c/CODE/Stereoscopic-Angelica revert --no-edit 3db6e92
```

### Task 5: Revert MixinFinalPassRenderer_PerEye

**Files affected:**
- Delete: `src/mixin/java/com/mitchellmarx/stereoscopic/mixin/iris/MixinFinalPassRenderer_PerEye.java`

- [ ] **Step 1: Revert commit `c0902fc`**

```bash
git -C /c/CODE/Stereoscopic-Angelica revert --no-edit c0902fc
```

### Task 6: Verify mixin config is consistent after reverts

After all 5 reverts, `src/main/resources/mixins.stereoscopic.json` should have shed the lines that registered the deleted mixins. Each revert touches the JSON too. Confirm:

- [ ] **Step 1: Inspect**

```bash
grep -E 'PerEye|AccessorDepthTexture|AccessorRenderTarget' src/main/resources/mixins.stereoscopic.json
```

Expected output (only the 2 KEPT mixins from Phase 3 should remain):
```
"iris.MixinCameraUniforms_PerEye",
"iris.MixinMatrixUniforms_PerEye",
```

If anything else PerEye-related appears, the JSON has stale entries from a partial revert. Manually delete them.

### Task 7: Build + verify mono works

- [ ] **Step 1: Clean build**

```bash
./gradlew compileMixinJava test
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 2: Manual smoke (no-shaders, baseline)**

Deploy the jar to Prism, launch, enable stereo. Confirm:
- Sky splits L/R (Plan 1).
- HUD splits L/R (Plan 1).
- World chunks render **mono** (each eye sees the same world content — known baseline, this is what we're about to fix).
- No crashes.

- [ ] **Step 3: Commit (the reverts are already individual commits; this is just a checkpoint)**

```bash
git -C /c/CODE/Stereoscopic-Angelica log --oneline -10
```

Expected: 5 "Revert" commits on top of the previous state.

---

## Phase 2 — Scratch-FB infrastructure

### Task 8: Create the PerEyeScratchFb helper

**Files affected:**
- Create: `src/main/java/com/mitchellmarx/stereoscopic/render/PerEyeScratchFb.java`

- [ ] **Step 1: Write the helper**

```java
package com.mitchellmarx.stereoscopic.render;

import com.mitchellmarx.stereoscopic.core.StereoState;
import net.minecraft.client.shader.Framebuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Owned scratch framebuffer used as the world-render target during each eye's pass.
 *
 * <p>Pattern: each eye renders the world into a full-display-sized scratch FB instead of
 * directly into the main FB's eye region. After {@code renderWorld} returns, the scratch is
 * blit-squished into the main FB at the eye's region. The substitution is wired by
 * {@code MixinMinecraft_StereoScratchFb}'s {@code @Inject} on
 * {@code Minecraft.getFramebuffer()}, which returns the scratch instance whenever
 * {@link #isActive()} is {@code true}.
 *
 * <p>Why full-size scratch instead of half-width main-FB region:
 * <ul>
 *   <li>Avoids aspect-ratio distortion. With a half-width viewport on the main FB,
 *       MC's {@code gluPerspective} uses the full-screen aspect ratio in projection,
 *       producing horizontally-squished chunk geometry.</li>
 *   <li>Iris's per-eye intermediate FBOs naturally inherit the FB size, eliminating
 *       the per-eye {@code RenderTargets} machinery sbs2 needed.</li>
 *   <li>Works identically with or without a shaderpack loaded.</li>
 * </ul>
 *
 * <p>Lifecycle: lazily allocated at first activation, resized on display dimension change,
 * destroyed on game shutdown via {@code MixinMinecraft_AsyncCursor.shutdownMinecraftApplet}
 * HEAD inject (or whatever covers MC's tear-down — see Step 5 below if shutdown path needs
 * additional wiring).
 *
 * <p>Single-threaded: only the render thread (main) writes to this. No locking needed.
 */
public final class PerEyeScratchFb {

    private static final Logger LOGGER = LogManager.getLogger("StereoscopicScratchFb");

    /** When true, {@code Minecraft.getFramebuffer()} returns the scratch instance. */
    private static volatile boolean active = false;

    /** Allocated framebuffer. Null until first {@link #ensureSized(int, int)}. */
    private static Framebuffer scratch = null;
    private static int scratchW = -1;
    private static int scratchH = -1;

    private PerEyeScratchFb() {}

    public static boolean isActive() { return active; }
    public static void setActive(boolean v) { active = v; }
    public static Framebuffer get() { return scratch; }

    /**
     * Allocate the scratch FB at the given dimensions, or resize an existing one. Idempotent
     * when dimensions match. Uses depth attachment to support depth-buffered world rendering
     * (chunks, entities, particles — they all use depth testing).
     */
    public static Framebuffer ensureSized(int width, int height) {
        if (scratch != null && scratchW == width && scratchH == height) return scratch;
        if (scratch != null) {
            try {
                scratch.deleteFramebuffer();
            } catch (Throwable t) {
                LOGGER.warn("Scratch FB delete failed during resize; GPU FB leaked.", t);
            }
            scratch = null;
        }
        // Framebuffer(width, height, useDepth) — useDepth=true so world depth testing works.
        scratch = new Framebuffer(width, height, true);
        scratchW = width;
        scratchH = height;
        LOGGER.info("Allocated stereo scratch framebuffer: {}x{} (FBO id={})", width, height,
            scratch.framebufferObject);
        return scratch;
    }

    /** Dispose on shutdown. Safe to call before any allocation (no-op then). */
    public static void dispose() {
        if (scratch != null) {
            try {
                scratch.deleteFramebuffer();
            } catch (Throwable t) {
                LOGGER.warn("Scratch FB delete failed on dispose; GPU FB leaked.", t);
            }
            scratch = null;
        }
        scratchW = -1;
        scratchH = -1;
        active = false;
    }

    /**
     * Blit the scratch FB into the main FB at the current eye's region, horizontally squished.
     * Uses raw {@code glBlitFramebuffer} so we can pick {@code GL_LINEAR} filtering and an
     * asymmetric source-vs-dest size — the higher-level MC blit helper forces equal-size +
     * {@code GL_NEAREST}, neither acceptable here.
     *
     * <p>Caller invariants:
     * <ul>
     *   <li>{@code StereoState.INSTANCE.getEyeVp{X,Y,W,H}()} are set to the current eye's
     *       region on the main FB (the two-pass loop in {@code MixinEntityRenderer_Stereo}
     *       sets these before each {@code renderWorld} call).</li>
     *   <li>{@code mainFb} is the real main framebuffer (caller passes the original, not
     *       our scratch substitute).</li>
     * </ul>
     */
    public static void blitScratchToMain(Framebuffer mainFb) {
        if (scratch == null) return;
        if (mainFb == null) return;
        final StereoState s = StereoState.INSTANCE;
        final int dstX0 = s.getEyeVpX();
        final int dstY0 = s.getEyeVpY();
        final int dstX1 = dstX0 + s.getEyeVpW();
        final int dstY1 = dstY0 + s.getEyeVpH();
        if (dstX1 <= dstX0 || dstY1 <= dstY0) return;  // not yet primed

        // Save bindings so we restore cleanly.
        final int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        final int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        final boolean wasScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (wasScissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);

        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, scratch.framebufferObject);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, mainFb.framebufferObject);
            GL30.glBlitFramebuffer(
                0, 0, scratchW, scratchH,
                dstX0, dstY0, dstX1, dstY1,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            if (wasScissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
    }
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/main/java/com/mitchellmarx/stereoscopic/render/PerEyeScratchFb.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(render): scratch framebuffer helper for per-eye world rendering"
```

### Task 9: Intercept Minecraft.getFramebuffer() to return scratch when active

**Files affected:**
- Create: `src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinMinecraft_StereoScratchFb.java`

- [ ] **Step 1: Write the mixin**

```java
package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.render.PerEyeScratchFb;
import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Per-eye world-FB substitution. HEAD inject on {@code Minecraft.getFramebuffer()}.
 * Returns the {@link PerEyeScratchFb} scratch instance whenever
 * {@code PerEyeScratchFb.isActive()} is true.
 *
 * <p>The two-pass loop in {@code MixinEntityRenderer_Stereo} sets {@code setActive(true)}
 * around each eye's {@code renderWorld} call. Inside that scope:
 * <ul>
 *   <li>Iris's pipeline allocations + main-FB references resolve to scratch.</li>
 *   <li>Embeddium/Celeritas chunk passes read the scratch as main, write to scratch.</li>
 *   <li>Distant Horizons + vanilla world rendering all hit scratch transparently.</li>
 * </ul>
 *
 * <p>Outside the per-eye window (HUD, GUI, post-frame, server thread), the inject is a
 * no-op and {@code getFramebuffer()} returns the real main FB as normal.
 *
 * <p>Why HEAD-inject on getFramebuffer rather than @Redirect at every call site: there are
 * many call sites — vanilla, Iris, Embeddium, DH, Sodium, plus mod code we don't control.
 * A single intercept point catches all of them.
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraft_StereoScratchFb {

    @Inject(method = "getFramebuffer", at = @At("HEAD"), cancellable = true)
    private void stereoscopic$redirectToScratch(CallbackInfoReturnable<Framebuffer> cir) {
        if (!PerEyeScratchFb.isActive()) return;
        final Framebuffer scratch = PerEyeScratchFb.get();
        if (scratch == null) return;
        cir.setReturnValue(scratch);
    }
}
```

- [ ] **Step 2: Register in `mixins.stereoscopic.json`**

Add `"minecraft.MixinMinecraft_StereoScratchFb"` to the `client` array. Keep alphabetical order. Result fragment (showing surrounding entries):

```json
"minecraft.MixinMinecraft_AsyncCursor",
"minecraft.MixinMinecraft_StereoAchievement",
"minecraft.MixinMinecraft_StereoScratchFb",
"sodium.MixinSodiumGameOptionPages_StereoToggle"
```

- [ ] **Step 3: Compile**

```bash
./gradlew compileMixinJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinMinecraft_StereoScratchFb.java src/main/resources/mixins.stereoscopic.json
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin): intercept Minecraft.getFramebuffer to return scratch when active"
```

### Task 10: Replace the two-pass renderWorld body in MixinEntityRenderer_Stereo

**Files affected:**
- Modify: `src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinEntityRenderer_Stereo.java`

The existing `stereoscopic$stereoRenderWorld` redirect sets per-eye viewport + scissor on the main FB and calls `renderWorld`. Replace it: per eye, ensure scratch sized, activate, bind scratch, call `renderWorld`, deactivate, blit-squish.

- [ ] **Step 1: Locate the existing method**

```bash
grep -n 'stereoscopic\$stereoRenderWorld' src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinEntityRenderer_Stereo.java
```

Expected: one match, around line 69.

- [ ] **Step 2: Replace the method body**

Find the existing `stereoscopic$stereoRenderWorld` method and replace it with:

```java
    @Redirect(
        method = "updateCameraAndRender",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/EntityRenderer;renderWorld(FJ)V"
        )
    )
    private void stereoscopic$stereoRenderWorld(EntityRenderer self, float partialTicks, long finishTimeNano) {
        final StereoMode mode = StereoState.INSTANCE.getFrameMode();
        if (mode == null || !mode.isActive()) {
            self.renderWorld(partialTicks, finishTimeNano);
            return;
        }

        final Minecraft mc = Minecraft.getMinecraft();
        final int fullW = mc.displayWidth;
        final int fullH = mc.displayHeight;

        // SBS splits horizontally; OU splits vertically. HALF gets half the split-axis
        // dimension; FULL keeps the full split-axis dimension (image squished in output).
        final boolean sbs = mode.isSideBySide();
        final boolean half = mode.isHalf();

        final int eyeW = sbs ? (half ? fullW / 2 : fullW) : fullW;
        final int eyeH = sbs ? fullH               : (half ? fullH / 2 : fullH);

        final int leftX  = 0;
        final int leftY  = sbs ? 0 : fullH - eyeH;  // OU: left eye on top (GL bottom-origin).
        final int rightX = sbs ? eyeW : 0;
        final int rightY = 0;

        // Capture the REAL main FB reference before we activate the scratch substitution.
        // After setActive(true), Minecraft.getFramebuffer() returns the scratch — we need
        // the real one for the final blit destination.
        final Framebuffer realMainFb = mc.getFramebuffer();

        // Ensure scratch is allocated at full FB size. Idempotent if size unchanged.
        PerEyeScratchFb.ensureSized(fullW, fullH);

        // LEFT eye
        StereoState.INSTANCE.setEye(StereoState.Eye.LEFT);
        // enterWorldPass values are the eye's REGION on the MAIN FB — used downstream by
        // anything that needs to know which half of the screen this eye targets (e.g., the
        // blit destination rect inside PerEyeScratchFb.blitScratchToMain).
        StereoState.INSTANCE.enterWorldPass(leftX, leftY, eyeW, eyeH);
        stereoscopic$setIrisActiveEye(0);
        PerEyeScratchFb.setActive(true);
        try {
            PerEyeScratchFb.get().bindFramebuffer(true);
            self.renderWorld(partialTicks, finishTimeNano);
        } finally {
            PerEyeScratchFb.setActive(false);
        }
        PerEyeScratchFb.blitScratchToMain(realMainFb);
        StereoState.INSTANCE.exitWorldPass();

        // RIGHT eye
        StereoState.INSTANCE.setEye(StereoState.Eye.RIGHT);
        StereoState.INSTANCE.enterWorldPass(rightX, rightY, eyeW, eyeH);
        stereoscopic$setIrisActiveEye(1);
        PerEyeScratchFb.setActive(true);
        try {
            PerEyeScratchFb.get().bindFramebuffer(true);
            self.renderWorld(partialTicks, finishTimeNano);
        } finally {
            PerEyeScratchFb.setActive(false);
        }
        PerEyeScratchFb.blitScratchToMain(realMainFb);
        StereoState.INSTANCE.exitWorldPass();

        // Restore mono eye + main FB for the HUD/GUI rendering that follows.
        stereoscopic$setIrisActiveEye(0);
        StereoState.INSTANCE.setEye(StereoState.Eye.MONO);
        realMainFb.bindFramebuffer(true);
        GL11.glViewport(0, 0, fullW, fullH);
    }
```

- [ ] **Step 3: Add the import**

The new code references `PerEyeScratchFb`. Add to the imports near the top of the file:

```java
import com.mitchellmarx.stereoscopic.render.PerEyeScratchFb;
```

Also confirm `Framebuffer` is already imported (it is — used elsewhere in this mixin).

- [ ] **Step 4: Compile**

```bash
./gradlew compileMixinJava
```

Expected: BUILD SUCCESSFUL. If compilation fails because of removed eye-viewport/scissor lines, check that nothing else in the file references them — the original outer-scissor enable/disable is also gone since scratch FB makes it unnecessary.

- [ ] **Step 5: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinEntityRenderer_Stereo.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin): per-eye renderWorld writes to scratch FB then blit-squishes to main"
```

### Task 11: Hook scratch FB cleanup into shutdown

**Files affected:**
- Modify: `src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinMinecraft_AsyncCursor.java`

The existing `MixinMinecraft_AsyncCursor` already does HEAD-inject on `shutdownMinecraftApplet` to stop the cursor thread. Piggyback the scratch FB cleanup onto the same inject — both are GPU-resource cleanup at MC tear-down.

- [ ] **Step 1: Locate the existing shutdownMinecraftApplet inject**

```bash
grep -n 'shutdownMinecraftApplet' src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinMinecraft_AsyncCursor.java
```

Expected: one match.

- [ ] **Step 2: Add `PerEyeScratchFb.dispose()` to the inject method**

Find the method `stereoscopic$stopCursorThreadBeforeShutdown` and add the dispose call:

```java
    @Inject(method = "shutdownMinecraftApplet", at = @At("HEAD"))
    private void stereoscopic$stopCursorThreadBeforeShutdown(CallbackInfo ci) {
        CursorPresentThread.stop();
        PerEyeScratchFb.dispose();
    }
```

- [ ] **Step 3: Add the import**

```java
import com.mitchellmarx.stereoscopic.render.PerEyeScratchFb;
```

- [ ] **Step 4: Compile**

```bash
./gradlew compileMixinJava
```

- [ ] **Step 5: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinMinecraft_AsyncCursor.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "fix(scratch-fb): dispose on MC shutdown alongside cursor thread"
```

---

## Phase 3 — Smoke test

### Task 12: End-to-end build + verify mixin registration

- [ ] **Step 1: Full build**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL. Tests pass. `copyToTestInstance` deploys to Prism.

- [ ] **Step 2: Verify mixin registrations in the built jar**

```bash
unzip -p build/libs/stereoscopic-*-feat-*-dirty.jar mixins.stereoscopic.json | grep -oE '"[a-z]+\.[A-Za-z_]+"' | sort
```

Expected to contain:
- `"minecraft.MixinMinecraft_StereoScratchFb"` (new)
- `"iris.MixinCameraUniforms_PerEye"` (kept)
- `"iris.MixinMatrixUniforms_PerEye"` (kept)
- All Plan 1 + Plan 2 Phase 2 + Phase 4 + Plan 3 mixins.

Expected NOT to contain (deleted in Phase 1 reverts):
- `"iris.AccessorDepthTexture"`
- `"iris.AccessorRenderTarget"`
- `"iris.MixinDeferredWorldRenderingPipeline_PerEye"`
- `"iris.MixinFinalPassRenderer_PerEye"`
- `"iris.MixinIrisSamplers_PerEye"`
- `"iris.MixinRenderTargets_PerEye"`
- `"iris.MixinViewportUniforms_PerEye"`

### Task 13: Manual smoke test in Prism

- [ ] **Step 1: Launch the GTNH-daily profile**

Boot to main menu. No crashes expected.

- [ ] **Step 2: Enter world, toggle SBS on (Sodium video options)**

- [ ] **Step 3: Test plan items — no shaderpack loaded**

1. **World chunk geometry splits L/R.** Each half shows the world from the per-eye camera position. Verify by toggling IPD slider — distant terrain parallax should shift between eyes.
2. Sky still splits (Plan 1 mechanism still works).
3. HUD still splits (Plan 1 mechanism still works).
4. Held item (hand) still per-eye (Plan 2 Phase 2 still works).
5. No vertical streaks or aspect-ratio distortion in either eye.
6. Tick SBS off → returns to clean mono.

- [ ] **Step 4: Repeat test items 1–5 with a shaderpack loaded** (e.g., Complementary Reimagined or BSL via Iris)

All items should still pass. The scratch FB substitution applies uniformly; Iris's pipeline runs against scratch transparently.

- [ ] **Step 5: Exit MC cleanly via in-game menu**

No `javaw.exe` left in Task Manager. Plan 3's shutdown hook + the new `PerEyeScratchFb.dispose()` should keep the shutdown clean.

- [ ] **Step 6: Log results**

Append to `docs/superpowers/notes/v0.1.0-manual-test.md`:

```
YYYY-MM-DD Scratch-FB world rendering smoke test: PASSED/FAILED [notes on what works + what doesn't]
```

---

## Self-Review

**Coverage vs. goal:**
- [x] Replace sbs2 per-eye Iris machinery with scratch-FB pattern — Phase 1 reverts + Phase 2 new code.
- [x] World chunks split L/R both shader-on and shader-off — verified in Task 13 smoke test.
- [x] Retain temporal-uniform per-eye history slots (Camera + Matrix uniforms) — kept as documented in File Structure.

**Placeholder scan:** None. Every step has actual code or commands.

**Type/method consistency:** `PerEyeScratchFb.isActive()` / `setActive(boolean)` / `get()` / `ensureSized(int, int)` / `blitScratchToMain(Framebuffer)` / `dispose()` — used consistently across Tasks 8, 9, 10, 11. `Framebuffer.bindFramebuffer(boolean)` and `Framebuffer.framebufferObject` are the actual MCP names in 1.7.10.

**Risk to investigate during smoke test:**
- Iris may cache a `mainFb` reference at pipeline-init time rather than calling `Minecraft.getFramebuffer()` every frame. If so, our scratch substitution misses Iris's main-FB writes and shaders-on still composites to the real main FB at full screen instead of the eye region. Mitigation: if smoke test shows shaders-on doesn't split, instrument by logging `framebufferObject` from inside `MixinMinecraft_StereoScratchFb` to confirm Iris IS calling our intercept; if not, add an additional `@Redirect` at Iris's main-FB cache point.
- `Framebuffer` constructor (`new Framebuffer(w, h, useDepth)`) may issue GL calls during init. If called from a non-GL-thread, those would error. Our `ensureSized` runs from the render thread (inside the two-pass loop), so this should be safe — but watch for race-condition log noise on first frame.
