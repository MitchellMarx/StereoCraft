# Plan 2 — HUD/GUI Duplication + Iris Integration

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** With Plan 1's SBS toggle working, layer on (1) full HUD/GUI duplication per eye, (2) Iris shaderpack support via external mixins into Angelica-bundled Iris classes, (3) hand-renderer per-eye depth, (4) achievement popup per-eye, (5) `TIMER`/`tickDelta` freeze between eye renders, (6) `ChromaticTooltips` re-arm between eyes' post events.

**Architecture:** External mixins into Angelica's shaded Iris (`net.coderbot.iris.*`) and Minecraft 1.7.10 classes. Per-eye state is read from `StereoState.INSTANCE`. The per-eye `RenderTargets`, camera uniforms, matrix uniforms, and shadow-pass-skip changes that sbs2 makes inline in Iris's source are reproduced here as `@Inject`/`@Redirect` mixins.

**Tech Stack:** Same as Plan 1. Adds the Iris API surface — relies on `net.coderbot.iris.pipeline.HandRenderer`, `RenderTargets`, `CameraUniforms`, `MatrixUniforms`, `ViewportUniforms`, `IrisSamplers`, `DeferredWorldRenderingPipeline`, `WorldRenderingPipeline`, `CompositeRenderer`, `FinalPassRenderer`, `Iris` (all FQNs verified against Angelica-sbs2 in Task 1).

**Reference port source:** `C:\CODE\Angelica-sbs2` branch `stereo-sbs-2`. Each Iris-side task in this plan ports a specific diff from `git show <sha>` for the relevant commit:
- `fa3a63f4` — *Iris shaderpack support* (the bulk of the Iris work — 8 files)
- `e4345194` — *hand renderer per-eye depth + HUD caching in stereo*
- `936840f9` — *skip Iris shadow pass on the second eye* (this bit lands in Plan 4's perf pass; the *infrastructure* it depends on lives here)
- `e659263b` — *re-arm ChromaticTooltips between LEFT/RIGHT Post events*
- `ed335bff` — *freeze TIMER + tickDelta between eye renderWorld calls*

**Prerequisites:** Plan 1 must be complete and the jar must build green. Verify with `./gradlew clean build && unzip -l build/libs/stereoscopic-*.jar | grep MixinEntityRenderer_Stereo`.

---

## File Structure

**Created in this plan:**

| Path | Sbs2 source / commit |
|---|---|
| `…/mixin/iris/MixinHandRenderer_StereoDepth.java` | sbs2 edit to `net/coderbot/iris/pipeline/HandRenderer.java`, commit `e4345194` (and follow-ups) |
| `…/mixin/iris/MixinRenderTargets_PerEye.java` | sbs2 edit to `net/coderbot/iris/rendertarget/RenderTargets.java`, commit `fa3a63f4` |
| `…/mixin/iris/MixinCameraUniforms_PerEye.java` | sbs2 edit to `net/coderbot/iris/uniforms/CameraUniforms.java`, `fa3a63f4` |
| `…/mixin/iris/MixinMatrixUniforms_PerEye.java` | sbs2 edit to `net/coderbot/iris/uniforms/MatrixUniforms.java`, `fa3a63f4` |
| `…/mixin/iris/MixinViewportUniforms_PerEye.java` | sbs2 edit to `net/coderbot/iris/uniforms/ViewportUniforms.java`, `fa3a63f4` |
| `…/mixin/iris/MixinIrisSamplers_PerEye.java` | sbs2 edit to `net/coderbot/iris/samplers/IrisSamplers.java`, `fa3a63f4` |
| `…/mixin/iris/MixinWorldRenderingPipeline_PerEye.java` | sbs2 edit to `net/coderbot/iris/pipeline/WorldRenderingPipeline.java`, `fa3a63f4` |
| `…/mixin/iris/MixinDeferredWorldRenderingPipeline_PerEye.java` | sbs2 edit to `net/coderbot/iris/pipeline/DeferredWorldRenderingPipeline.java`, `fa3a63f4` |
| `…/mixin/iris/MixinCompositeRenderer_PerEye.java` | sbs2 edit to `net/coderbot/iris/postprocess/CompositeRenderer.java`, `fa3a63f4` |
| `…/mixin/iris/MixinFinalPassRenderer_PerEye.java` | sbs2 edit to `net/coderbot/iris/postprocess/FinalPassRenderer.java`, `fa3a63f4` |
| `…/mixin/minecraft/MixinMinecraft_StereoAchievement.java` | port of `MixinMinecraft_StereoAchievement.java` from sbs2 (67 lines) |
| `…/mixin/minecraft/MixinFMLCommonHandler_Stereo.java` | port of `MixinFMLCommonHandler_Stereo.java` from sbs2 (68 lines) — duplicates `RenderTickEvent.END` per-eye so WAILA-style overlays appear in both halves |

**Modified in this plan:**

| Path | Why |
|---|---|
| `…/resources/mixins.stereoscopic.json` | Add the 11 new mixin entries |
| `…/mixin/minecraft/MixinEntityRenderer_Stereo.java` | Verify HUD/TIMER/ChromaticTooltips bits from the Plan 1 verbatim port are wired (no code change expected — Plan 1 ported the whole file) |

---

## Phase 1 — Preflight: confirm Iris targets are reachable

### Task 1: Verify Angelica's shaded Iris FQNs are unrelocated

**Files:** none (verification — guards against the spec's "Angelica relocation" risk)

- [ ] **Step 1: Resolve the Angelica jar path**

```bash
cd /c/CODE/Stereoscopic-Angelica
ANGELICA_JAR=$(./gradlew -q dependencies --configuration runtimeClasspath 2>/dev/null \
    | grep -oE 'Angelica-[0-9.]+' | head -1)
find ~/.gradle/caches/modules-2/files-2.1/com.github.GTNewHorizons/Angelica -name "*.jar" -not -name "*-sources*" | head -1
```

Capture the path into a shell variable for the next step.

- [ ] **Step 2: Grep for all 10 Iris classes Plan 2 mixins target**

```bash
unzip -l "$ANGELICA_JAR" | grep -E '(HandRenderer|RenderTargets|CameraUniforms|MatrixUniforms|ViewportUniforms|IrisSamplers|WorldRenderingPipeline|DeferredWorldRenderingPipeline|CompositeRenderer|FinalPassRenderer)\.class' | head -15
```

Expected: 10 lines, all under `net/coderbot/iris/.../*.class`. If a class is missing or appears under `com/gtnewhorizons/angelica/shaded/`, STOP — adapt the FQN in every mixin in this plan before continuing.

- [ ] **Step 3: Document outcome**

Append a line to `docs/superpowers/notes/v0.1.0-manual-test.md`:

```
2026-05-18 Iris-target preflight: 10/10 classes found unrelocated in Angelica <version>. OK to proceed.
```

---

## Phase 2 — Hand renderer per-eye depth

### Task 2: Read the sbs2 HandRenderer edit

**Files:** none (research)

- [ ] **Step 1: View the exact diff**

```bash
git -C /c/CODE/Angelica-sbs2 show e4345194 -- 'src/main/java/net/coderbot/iris/pipeline/HandRenderer.java'
```

The diff adds two stereo-aware translation blocks:
1. **Projection-space** offset of `sign * 0.07f` inside the method that builds the hand's projection matrix (look for the existing `// TODO: Anaglyph` comment with `0.07F`).
2. **Modelview** offset of `getHandEyeOffset()` (`±0.1f * ipd/0.064`) inside the method that builds the hand's modelview (look for the `// TODO: Anaglyph` with `0.1F`).

Identify the **exact method names** in HandRenderer that contain those `// TODO: Anaglyph` blocks. They are the `@Inject` targets for Task 3.

### Task 3: MixinHandRenderer_StereoDepth

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\iris\MixinHandRenderer_StereoDepth.java`

- [ ] **Step 1: Write the file**

```java
package com.mitchellmarx.stereoscopic.mixin.iris;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.coderbot.iris.pipeline.HandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stereo-aware per-eye translations for the held item. Reproduces sbs2 commit e4345194's
 * inline edits to net.coderbot.iris.pipeline.HandRenderer as external @Inject mixins.
 *
 * Two translations:
 *   1. Projection-space:  ±0.07f (sign LEFT=-1, RIGHT=+1 — flipped vs modelview because
 *      the hand uses an inverted Z scale, DEPTH=0.125f).
 *   2. Modelview:         ±getHandEyeOffset() (≈ ±0.1f * ipd/0.064).
 */
@Mixin(HandRenderer.class)
public abstract class MixinHandRenderer_StereoDepth {

    // Target the existing // TODO: Anaglyph block in the projection-matrix-build method.
    // Replace <PROJECTION_METHOD> with the method name identified in Task 2 (the one that
    // calls glScalef(1.0F, 1.0F, DEPTH) right before the // TODO: Anaglyph comment).
    @Inject(
        method = "<PROJECTION_METHOD>",
        at = @At(value = "INVOKE",
                 target = "Lcom/gtnewhorizons/angelica/glsm/GLStateManager;glScalef(FFF)V",
                 ordinal = 0,
                 shift = At.Shift.AFTER)
    )
    private void stereoscopic$applyHandProjectionOffset(CallbackInfo ci) {
        if (!StereoState.INSTANCE.isActive()) return;
        final float sign = StereoState.INSTANCE.isLeftEye() ? -1f
            : (StereoState.INSTANCE.isRightEye() ? 1f : 0f);
        if (sign != 0f) {
            GLStateManager.glTranslatef(sign * 0.07f, 0f, 0f);
        }
    }

    // Target the modelview-build method right after the inner glLoadIdentity().
    // Replace <MODELVIEW_METHOD> with the method name identified in Task 2 (the one that
    // calls hurtCameraEffect right after the // TODO: Anaglyph comment).
    @Inject(
        method = "<MODELVIEW_METHOD>",
        at = @At(value = "INVOKE",
                 target = "Lcom/gtnewhorizons/angelica/glsm/GLStateManager;glLoadIdentity()V",
                 ordinal = 0,
                 shift = At.Shift.AFTER)
    )
    private void stereoscopic$applyHandModelviewOffset(CallbackInfo ci) {
        if (!StereoState.INSTANCE.isActive()) return;
        final float dx = StereoState.INSTANCE.getHandEyeOffset();
        if (dx != 0f) {
            GLStateManager.glTranslatef(dx, 0f, 0f);
        }
    }
}
```

- [ ] **Step 2: Resolve the two `<…_METHOD>` placeholders**

From Task 2's reading of the sbs2 diff, find the surrounding method declarations. They will be private/package-private methods on `HandRenderer`. Substitute the resolved names. The shape of the surrounding code (the `glScalef(1.0F, 1.0F, DEPTH)` and `glLoadIdentity()` anchors) is what the `@At` targets latch onto — those anchors must match the live HandRenderer in the pinned Angelica.

- [ ] **Step 3: Add to mixins.stereoscopic.json**

Edit `src/main/resources/mixins.stereoscopic.json` and add `"iris.MixinHandRenderer_StereoDepth"` to the `"client"` array.

- [ ] **Step 4: Compile**

```bash
./gradlew compileMixinJava
```

If the anchor `target` strings don't match the actual HandRenderer bytecode, RFG's mixin AP reports the mismatched descriptor. Adjust the FQN slashes (`/`) and signature (`(FFF)V`) to match.

- [ ] **Step 5: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/mixin/java/com/mitchellmarx/stereoscopic/mixin/iris/MixinHandRenderer_StereoDepth.java src/main/resources/mixins.stereoscopic.json
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin/iris): per-eye projection + modelview offsets on HandRenderer"
```

---

## Phase 3 — Iris per-eye RenderTargets, uniforms, samplers (commit fa3a63f4)

The bulk of sbs2's Iris-shaderpack support is one commit, `fa3a63f4`, touching 8 Iris files. Each becomes a single external mixin in our mod. Tasks 4–11 follow an identical template: **read the relevant slice of `git show fa3a63f4`, write a mixin that reproduces the same edit via `@Inject`/`@Redirect`, recompile, commit.**

For brevity, the per-task template is documented once in Task 4 and referenced thereafter. The exact `@At` targets must come from inspecting the live Iris source on the pinned Angelica — `target = "L<owner>;<name><desc>"` strings only stick if they match bytecode.

### Task 4: MixinRenderTargets_PerEye

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\iris\MixinRenderTargets_PerEye.java`

- [ ] **Step 1: Read the sbs2 edits to RenderTargets.java**

```bash
git -C /c/CODE/Angelica-sbs2 show fa3a63f4 -- 'src/main/java/net/coderbot/iris/rendertarget/RenderTargets.java'
```

Identify each hunk:
- New field(s) — e.g. `private int activeEye`, `private List<GlTexture> perEyeColorTargets`, etc. These become `@Shadow` fields if they exist or `@Unique` fields if added by us.
- Modified methods — typically `getDepthTexture(...)`, `getColorTexture(...)`, the constructor (allocates per-eye texture banks based on `StereoState.INSTANCE.stereoEyeCount()`), and a new `setActiveEye(int)` method.

**Strategy decision per hunk:**
- Pure new method (e.g. `setActiveEye`) → `@Inject` at `@At("HEAD")` with `cancellable=true`, returning early, **or** add as new method to a `@Mixin` implementing an interface mixin (cleaner). Since we don't need to expose `setActiveEye` to other code, just inject it as a new public method on the target via mixin's `@Mixin` interface mechanism: declare an interface `IPerEyeRenderTargets`, the mixin implements it.
- Modified method body (e.g. getter switches on eye) → `@ModifyVariable` or `@Redirect` on the specific field read.
- Constructor changes (per-eye allocation) → `@Inject` at `@At("RETURN")` to allocate the additional textures.

- [ ] **Step 2: Write a small interface for the new API surface**

`C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\compat\iris\IPerEyeRenderTargets.java`:

```java
package com.mitchellmarx.stereoscopic.compat.iris;

/**
 * Adds setActiveEye(int) to Iris's RenderTargets so per-eye texture banks can be selected.
 * Sbs2 added this method inline; we add it via mixin onto RenderTargets.
 */
public interface IPerEyeRenderTargets {
    void stereoscopic$setActiveEye(int eyeIndex);
    int  stereoscopic$getActiveEye();
}
```

- [ ] **Step 3: Write the mixin**

```java
package com.mitchellmarx.stereoscopic.mixin.iris;

import com.mitchellmarx.stereoscopic.compat.iris.IPerEyeRenderTargets;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.coderbot.iris.rendertarget.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(RenderTargets.class)
public abstract class MixinRenderTargets_PerEye implements IPerEyeRenderTargets {

    @Unique private int stereoscopic$activeEye = 0;

    @Override
    public void stereoscopic$setActiveEye(int eyeIndex) {
        this.stereoscopic$activeEye = eyeIndex == 1 ? 1 : 0;
    }

    @Override
    public int stereoscopic$getActiveEye() { return stereoscopic$activeEye; }

    // Hunk 2: <inject the per-eye texture-bank allocation in the constructor here>
    // Hunk 3: <inject getColorTexture / getDepthTexture redirects to read per-eye banks>
    //
    // Each <...> placeholder MUST be filled with an actual @Inject/@Redirect block targeting
    // the bytecode location sbs2 modified in fa3a63f4 — see Step 1's `git show` output.
    // If the sbs2 patch only modifies behavior (no new fields needed for that hunk), use
    // @Redirect on the existing field access (e.g. ColorTexture[] -> int activeEye-based switch).
}
```

The `<…>` placeholders are not "TBD" — they're explicit instructions: read the diff, mirror each hunk as a mixin annotation. The patterns are:

| Sbs2 change | Mixin annotation |
|---|---|
| Adds a new field | `@Unique private <type> <name>;` |
| Adds a new method | Mixin declares the method via the interface in Step 2 (avoids name collisions with future Iris updates) |
| Modifies an existing method body around an instance-field read | `@Redirect(at = @At(value = "FIELD", target = "L<owner>;<name>:<desc>"))` |
| Modifies an existing method body at a method call | `@Redirect(at = @At(value = "INVOKE", target = "L<owner>;<name><desc>"))` |
| Adds a block at method start/end | `@Inject(at = @At("HEAD"))` or `@At("RETURN")` |

- [ ] **Step 4: Compile**

```bash
./gradlew compileMixinJava
```

- [ ] **Step 5: Add to mixins.stereoscopic.json**

Add `"iris.MixinRenderTargets_PerEye"`.

- [ ] **Step 6: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/main/java/com/mitchellmarx/stereoscopic/compat src/mixin/java/com/mitchellmarx/stereoscopic/mixin/iris/MixinRenderTargets_PerEye.java src/main/resources/mixins.stereoscopic.json
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin/iris): per-eye RenderTargets banks + setActiveEye accessor"
```

### Task 5: MixinCameraUniforms_PerEye

**Files:**
- Create: `…/mixin/iris/MixinCameraUniforms_PerEye.java`

- [ ] **Step 1: Read sbs2 edit**

```bash
git -C /c/CODE/Angelica-sbs2 show fa3a63f4 -- 'src/main/java/net/coderbot/iris/uniforms/CameraUniforms.java'
```

Identify: per-eye history slots (the change duplicates `previousCameraPosition` into a 2-slot array indexed by current eye).

- [ ] **Step 2: Write the mixin using the Task 4 template**

```java
package com.mitchellmarx.stereoscopic.mixin.iris;

import com.mitchellmarx.stereoscopic.core.StereoState;
import net.coderbot.iris.uniforms.CameraUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(CameraUniforms.class)
public abstract class MixinCameraUniforms_PerEye {
    @Unique private static final double[][] stereoscopic$previousCameraPositionPerEye = new double[2][3];

    // <Hunk: redirect reads of previousCameraPosition to the per-eye slot>
    // <Hunk: redirect writes of previousCameraPosition to the per-eye slot>
    // Index = StereoState.INSTANCE.currentEyeIndex().
}
```

Fill the hunks based on Step 1's diff.

- [ ] **Step 3: Add to mixin config + compile + commit**

```bash
# Append "iris.MixinCameraUniforms_PerEye" to mixins.stereoscopic.json
./gradlew compileMixinJava
git -C /c/CODE/Stereoscopic-Angelica add ...
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin/iris): per-eye history slots in CameraUniforms"
```

### Task 6: MixinMatrixUniforms_PerEye

Identical pattern to Task 5 but for `MatrixUniforms.java` — duplicates `previousModelView`, `previousProjection` into per-eye slots.

- [ ] **Step 1**: `git show fa3a63f4 -- 'src/main/java/net/coderbot/iris/uniforms/MatrixUniforms.java'`
- [ ] **Step 2**: Write the mixin (template from Task 5).
- [ ] **Step 3**: Add to mixin config, compile, commit `feat(mixin/iris): per-eye history slots in MatrixUniforms`.

### Task 7: MixinViewportUniforms_PerEye

Identical pattern to Task 5 but for `ViewportUniforms.java` — `viewWidth` / `viewHeight` should return `StereoState.INSTANCE.irisFbWidth(displayWidth)` / `irisFbHeight(displayHeight)` (which in sbs2 just returns the full size; the indirection lets future changes adjust).

- [ ] **Step 1**: `git show fa3a63f4 -- 'src/main/java/net/coderbot/iris/uniforms/ViewportUniforms.java'`
- [ ] **Step 2**: Write the mixin.
- [ ] **Step 3**: Add to mixin config, compile, commit `feat(mixin/iris): route viewport uniforms through StereoState`.

### Task 8: MixinIrisSamplers_PerEye

`IrisSamplers.java` — sbs2 adds a per-eye depth-texture binding so shaderpacks read the correct eye's depth.

- [ ] **Step 1**: `git show fa3a63f4 -- 'src/main/java/net/coderbot/iris/samplers/IrisSamplers.java'`
- [ ] **Step 2**: Write the mixin.
- [ ] **Step 3**: Add to mixin config, compile, commit `feat(mixin/iris): per-eye depth sampler binding`.

### Task 9: MixinWorldRenderingPipeline_PerEye

Pipeline interface gains a `setActiveEye(int)` hook (sbs2 added it inline). For our external mixin, declare the hook via an interface mixin on `WorldRenderingPipeline.class` that defaults to a no-op.

- [ ] **Step 1**: `git show fa3a63f4 -- 'src/main/java/net/coderbot/iris/pipeline/WorldRenderingPipeline.java'`
- [ ] **Step 2**: Write a default-method interface mixin.
- [ ] **Step 3**: Add to mixin config, compile, commit `feat(mixin/iris): WorldRenderingPipeline.setActiveEye hook`.

### Task 10: MixinDeferredWorldRenderingPipeline_PerEye

`DeferredWorldRenderingPipeline.java` is the concrete pipeline. Sbs2 implements `setActiveEye(int)` to:
- Call `RenderTargets.setActiveEye(eye)` (now wired via `IPerEyeRenderTargets`).
- Skip the shadow pass when `eye == 1` (the 936840f9 perf change — but the *infrastructure* for the skip lives here; the actual skip flag lands in Plan 4).

- [ ] **Step 1**: `git show fa3a63f4 -- 'src/main/java/net/coderbot/iris/pipeline/DeferredWorldRenderingPipeline.java'`
- [ ] **Step 2**: Write the mixin. Cast `RenderTargets` to `IPerEyeRenderTargets` to call `stereoscopic$setActiveEye`.
- [ ] **Step 3**: Add to mixin config, compile, commit `feat(mixin/iris): DeferredWorldRenderingPipeline.setActiveEye wiring`.

### Task 11: MixinCompositeRenderer_PerEye + MixinFinalPassRenderer_PerEye

Both `CompositeRenderer.java` and `FinalPassRenderer.java` need their `glViewport(0,0,fbW,fbH)` calls to be intercepted when the current eye viewport applies. Sbs2 inlines the check; we mixin the `glViewport` call site.

- [ ] **Step 1**: `git show fa3a63f4 -- 'src/main/java/net/coderbot/iris/postprocess/CompositeRenderer.java' 'src/main/java/net/coderbot/iris/postprocess/FinalPassRenderer.java'`
- [ ] **Step 2**: Write both mixins. They are structurally identical — `@Redirect` the `GL11.glViewport(I,I,I,I)V` call to a method that consults `StereoState`.
- [ ] **Step 3**: Add to mixin config, compile, commit `feat(mixin/iris): per-eye viewport in CompositeRenderer + FinalPassRenderer`.

### Task 12: Verify Iris path end-to-end (manual)

**Files:** none.

- [ ] **Step 1**: Build and deploy.

```bash
./gradlew clean build
```

- [ ] **Step 2**: Launch Prism GTNH profile with a known-good shaderpack (e.g. Complementary Reimagined for 1.7.10, if installed via GTNH's Iris).

- [ ] **Step 3**: Verify the toggle (Sodium → SBS on) keeps the shaderpack rendering — both eyes see shader output, shadows render correctly (from the mono camera; Plan 4 wires the shadow-pass-skip toggle). Hand renders at per-eye depth (Task 3 effect).

If the screen goes black or one eye is shader-corrupt: the most likely failure is a mismatched `@At` target in one of Tasks 4–11. Diff the live Angelica Iris class against `fa3a63f4`'s view and adjust.

- [ ] **Step 4**: Log results to `docs/superpowers/notes/v0.1.0-manual-test.md`.

---

## Phase 4 — Achievement popup per-eye

### Task 13: MixinMinecraft_StereoAchievement

**Files:**
- Create: `…/mixin/minecraft/MixinMinecraft_StereoAchievement.java`

Reference: `C:\CODE\Angelica-sbs2\src\mixin\java\com\gtnewhorizons\angelica\mixins\early\angelica\stereo\MixinMinecraft_StereoAchievement.java` (67 lines). Verbatim port with package substitution.

- [ ] **Step 1: Write the file**

```java
package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.core.StereoHudMode;
import com.mitchellmarx.stereoscopic.core.StereoMode;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.achievement.GuiAchievement;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla Minecraft.runGameLoop calls guiAchievement.updateAchievementWindow after the
 * EntityRenderer.updateCameraAndRender block — outside every stereo redirect. The popup's own
 * setup resets the GL viewport to the full display each call, so left alone it draws once at the
 * top-right of the full window and only one eye sees it. Redirect the call and post it per eye,
 * with StereoState.enterGuiPass active so the GLSM bridge can route the inner full-FB viewport
 * reset to the current eye's region.
 */
@Mixin(value = Minecraft.class, priority = 1100)
public class MixinMinecraft_StereoAchievement {

    @Redirect(
        method = "runGameLoop",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/achievement/GuiAchievement;func_146254_a()V"
        )
    )
    private void stereoscopic$stereoAchievement(GuiAchievement popup) {
        final StereoMode mode = StereoState.INSTANCE.getFrameMode();
        final boolean stereoActive = mode != null && mode.isActive()
            && StereoState.INSTANCE.getFrameHudMode() == StereoHudMode.DUPLICATE
            && mode.isSideBySide() && mode.isHalf();
        if (!stereoActive) {
            popup.func_146254_a();
            return;
        }

        final Minecraft mc = Minecraft.getMinecraft();
        final int fullW = mc.displayWidth;
        final int fullH = mc.displayHeight;
        final int eyeW = fullW / 2;
        final int eyeH = fullH;

        GL11.glViewport(0, 0, eyeW, eyeH);
        StereoState.INSTANCE.enterGuiPass(0, 0, eyeW, eyeH);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        popup.func_146254_a();
        StereoState.INSTANCE.exitGuiPass();

        GL11.glViewport(eyeW, 0, eyeW, eyeH);
        StereoState.INSTANCE.enterGuiPass(eyeW, 0, eyeW, eyeH);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        popup.func_146254_a();
        StereoState.INSTANCE.exitGuiPass();

        GL11.glViewport(0, 0, fullW, fullH);
    }
}
```

- [ ] **Step 2: Add to mixin config**

Add `"minecraft.MixinMinecraft_StereoAchievement"` to `mixins.stereoscopic.json`.

- [ ] **Step 3: Compile**

```bash
./gradlew compileMixinJava
```

- [ ] **Step 4: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinMinecraft_StereoAchievement.java src/main/resources/mixins.stereoscopic.json
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin): duplicate achievement popup across eye halves"
```

### Task 13b: MixinFMLCommonHandler_Stereo — per-eye `RenderTickEvent.END`

**Files:**
- Create: `…/mixin/minecraft/MixinFMLCommonHandler_Stereo.java`

Source: `C:\CODE\Angelica-sbs2\src\mixin\java\com\gtnewhorizons\angelica\mixins\early\angelica\stereo\MixinFMLCommonHandler_Stereo.java` (68 lines). Verbatim port with the standard substitutions (`com.gtnewhorizons.angelica.stereo.*` → `com.mitchellmarx.stereoscopic.core.*`, `angelica$` → `stereoscopic$`).

- [ ] **Step 1: Write the file**

```java
package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.core.StereoHudMode;
import com.mitchellmarx.stereoscopic.core.StereoMode;
import com.mitchellmarx.stereoscopic.core.StereoState;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Duplicate RenderTickEvent.END per-eye. WAILA and other END-phase subscribers draw overlays in
 * this event, which fires from FMLCommonHandler.onRenderTickEnd AFTER updateCameraAndRender
 * returns and our stereo viewport restore has run — without this redirect they draw at the full
 * viewport and a bottom-right-anchored overlay lands at the right edge of the right eye instead
 * of one copy per eye.
 */
@Mixin(value = FMLCommonHandler.class, priority = 1100, remap = false)
public abstract class MixinFMLCommonHandler_Stereo {

    @Redirect(
        method = "onRenderTickEnd",
        at = @At(
            value = "INVOKE",
            target = "Lcpw/mods/fml/common/eventhandler/EventBus;post(Lcpw/mods/fml/common/eventhandler/Event;)Z"
        )
    )
    private boolean stereoscopic$stereoRenderTickEnd(EventBus bus, Event event) {
        final StereoMode mode = StereoState.INSTANCE.getFrameMode();
        final boolean stereoActive = mode != null && mode.isActive()
            && StereoState.INSTANCE.getFrameHudMode() == StereoHudMode.DUPLICATE
            && mode.isSideBySide() && mode.isHalf();
        if (!stereoActive) {
            return bus.post(event);
        }

        final Minecraft mc = Minecraft.getMinecraft();
        final int fullW = mc.displayWidth;
        final int fullH = mc.displayHeight;
        final int eyeW = fullW / 2;
        final int eyeH = fullH;

        // LEFT eye
        GL11.glViewport(0, 0, eyeW, eyeH);
        StereoState.INSTANCE.enterGuiPass(0, 0, eyeW, eyeH);
        final boolean result = bus.post(event);
        StereoState.INSTANCE.exitGuiPass();

        // RIGHT eye: build a fresh event — Forge phase-tracking refuses re-posting the same instance.
        GL11.glViewport(eyeW, 0, eyeW, eyeH);
        StereoState.INSTANCE.enterGuiPass(eyeW, 0, eyeW, eyeH);
        if (event instanceof TickEvent.RenderTickEvent) {
            final TickEvent.RenderTickEvent original = (TickEvent.RenderTickEvent) event;
            final TickEvent.RenderTickEvent copy =
                new TickEvent.RenderTickEvent(original.phase, original.renderTickTime);
            bus.post(copy);
        }
        StereoState.INSTANCE.exitGuiPass();

        GL11.glViewport(0, 0, fullW, fullH);
        return result;
    }
}
```

- [ ] **Step 2: Add to mixin config**

Append `"minecraft.MixinFMLCommonHandler_Stereo"` to `mixins.stereoscopic.json`.

- [ ] **Step 3: Compile + commit**

```bash
./gradlew compileMixinJava
git -C /c/CODE/Stereoscopic-Angelica add src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinFMLCommonHandler_Stereo.java src/main/resources/mixins.stereoscopic.json
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin): duplicate RenderTickEvent.END per-eye (WAILA-style overlay support)"
```

---

## Phase 5 — Verify HUD/TIMER/ChromaticTooltips bits from Plan 1's MixinEntityRenderer_Stereo

### Task 14: Confirm Plan 1's full port included the HUD/TIMER/ChromaticTooltips injections

**Files:**
- Verify only: `…/mixin/minecraft/MixinEntityRenderer_Stereo.java`

The Plan 1 task that ported MixinEntityRenderer_Stereo did so verbatim — meaning all the injection points sbs2 added in commits `7594da2a` (HUD/GUI), `5b771503` (cursor/scissor/event dup), `ed335bff` (TIMER/tickDelta freeze), and `e659263b` (ChromaticTooltips re-arm) should already be present in the ported file.

- [ ] **Step 1: Verify the methods exist by grep**

```bash
grep -n -E '(stereoscopic\$|enterGuiPass|exitGuiPass|enterWorldPass|exitWorldPass|TIMER|ChromaticTooltips)' \
    /c/CODE/Stereoscopic-Angelica/src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinEntityRenderer_Stereo.java
```

Expected: matches for each of the bracketed terms. If `TIMER` doesn't appear: the freeze hook may need a separate port — see Step 2.

- [ ] **Step 2: If any hook is missing, port it now**

For each missing hook, run:

```bash
git -C /c/CODE/Angelica-sbs2 log --oneline --diff-filter=AM -- 'src/mixin/java/com/gtnewhorizons/angelica/mixins/early/angelica/stereo/MixinEntityRenderer_Stereo.java' | head -10
```

Find the commit that introduced the missing hook, then `git show <sha> -- '<file>'` and port the diff to our `MixinEntityRenderer_Stereo.java`. Substitutions: `angelica$` → `stereoscopic$`, `AngelicaConfig.X` → `StereoConfig.X`, `com.gtnewhorizons.angelica.stereo.*` → `com.mitchellmarx.stereoscopic.core.*`.

- [ ] **Step 3: Commit (only if Step 2 made changes)**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinEntityRenderer_Stereo.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin): port missing HUD/TIMER/ChromaticTooltips injections from sbs2"
```

---

## Phase 6 — Full build + manual smoke test

### Task 15: End-to-end build

- [ ] **Step 1**:

```bash
cd /c/CODE/Stereoscopic-Angelica
./gradlew clean build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2**: Verify mixin registrations

```bash
unzip -p build/libs/stereoscopic-*.jar mixins.stereoscopic.json | grep -oE '"[a-z]+\.\w+"'
```

Expected entries (Plan 1 + Plan 2):
```
"minecraft.MixinEntityRenderer_Stereo"
"minecraft.MixinEntityRenderer_StereoCamera"
"minecraft.MixinFramebuffer_Stereo"
"minecraft.MixinMinecraft_StereoAchievement"
"minecraft.MixinFMLCommonHandler_Stereo"
"sodium.MixinSodiumGameOptionPages_StereoToggle"
"iris.MixinHandRenderer_StereoDepth"
"iris.MixinRenderTargets_PerEye"
"iris.MixinCameraUniforms_PerEye"
"iris.MixinMatrixUniforms_PerEye"
"iris.MixinViewportUniforms_PerEye"
"iris.MixinIrisSamplers_PerEye"
"iris.MixinWorldRenderingPipeline_PerEye"
"iris.MixinDeferredWorldRenderingPipeline_PerEye"
"iris.MixinCompositeRenderer_PerEye"
"iris.MixinFinalPassRenderer_PerEye"
```

### Task 16: Manual smoke test in Prism

Following the spec's manual test plan items B (SBS on), C (off), D (slider), E (Iris shaderpack), G (HUD duplication including F3 + achievement popup):

- [ ] **Step 1**: Deploy via `./gradlew build`.
- [ ] **Step 2**: Launch profile.
- [ ] **Step 3**: Exercise the test items.
- [ ] **Step 4**: Update `docs/superpowers/notes/v0.1.0-manual-test.md` with results.

If anything red: do not advance to Plan 3 until Plan 2 is green.

---

## Self-Review

**Coverage vs spec section "Goals":**
- [x] B — HUD + GUI duplication. Plan 1's MixinEntityRenderer_Stereo port already covers the main injection points; Task 14 verifies.
- [x] C — Hand renderer per-eye depth (Task 3).
- [x] D — Iris shaderpack support (Tasks 4–11).
- [x] I — Achievement popup duplicated across eyes (Task 13).
- [x] J — TIMER / tickDelta freeze (Plan 1 port, verified in Task 14).
- [x] K — ChromaticTooltips re-arm (Plan 1 port, verified in Task 14).

**Type consistency:** `IPerEyeRenderTargets` (Task 4) introduces `stereoscopic$setActiveEye(int)` / `stereoscopic$getActiveEye()`. Task 10's `MixinDeferredWorldRenderingPipeline_PerEye` calls those names. Consistent.

**Placeholders:** Tasks 4–11 each contain `<…>` blocks where the executor must port specific hunks from `git show fa3a63f4`. These are *instructions*, not "TBD" — the patterns and `@At` strategy table in Task 4 give the executor everything they need. The placeholder is the *bytecode position*, which can't be hard-coded into the plan without freezing it to a specific Angelica build.

**Scope check:** Plan 2 deliberately leaves the shadow-pass-skip *flag* for Plan 4 (the perf plan); the *infrastructure* for setActiveEye is laid here.

---

**End of Plan 2.** Plan 3 picks up the async OS cursor.
