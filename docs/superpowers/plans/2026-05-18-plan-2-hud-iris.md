# Plan 2 — HUD/GUI Duplication + Iris Integration

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** With Plan 1's SBS toggle working, layer on (1) full HUD/GUI duplication per eye, (2) Iris shaderpack support via external mixins into Angelica-bundled Iris classes, (3) hand-renderer per-eye depth, (4) achievement popup per-eye, (5) `TIMER`/`tickDelta` freeze between eye renders, (6) `ChromaticTooltips` re-arm between eyes' post events.

**Architecture:** See **Plan 1 "Porting principle"** for the canonical rule (port sbs2's `master...stereo-sbs-2` diff verbatim; NEW files copy whole, MODIFIED Angelica files apply via the most surgical external mechanism). For this plan specifically:

- Iris classes (`net.coderbot.iris.*`) — shaded into published Angelica — are targeted via **External Mixins** (`@Inject`/`@Redirect`).
- Minecraft 1.7.10 classes — same.
- If any sbs2 commit in this plan modifies `Angelica/loading/{fml,rfb}` (transformer registration sites) or `Angelica/glsm/GLStateManager.java`, deliver via **our own** `StereoscopicCoreMod` (FML coremod) or `StereoscopicRfbPlugin` (RFB plugin) — both already exist after Plan 1 Phase 5b. Adding additional transformers means appending to the existing plugin classes, not creating new ones.
- Per-eye state is read from `StereoState.INSTANCE` (Plan 1).

**Tech Stack:** Same as Plan 1. Adds the Iris API surface — relies on `net.coderbot.iris.pipeline.HandRenderer`, `RenderTargets`, `CameraUniforms`, `MatrixUniforms`, `ViewportUniforms`, `IrisSamplers`, `DeferredWorldRenderingPipeline`, `WorldRenderingPipeline`, `CompositeRenderer`, `FinalPassRenderer`, `Iris` (all FQNs verified against Angelica-sbs2 in Task 1).

**Reference port source:** `C:\CODE\Angelica-sbs2` branch `stereo-sbs-2`. Each Iris-side task in this plan ports a specific diff from `git show <sha>` for the relevant commit:
- `fa3a63f4` — *Iris shaderpack support* (the bulk of the Iris work — 8 files)
- `e4345194` — *hand renderer per-eye depth + HUD caching in stereo*
- `936840f9` — *skip Iris shadow pass on the second eye* (this bit lands in Plan 4's perf pass; the *infrastructure* it depends on lives here)
- `e659263b` — *re-arm ChromaticTooltips between LEFT/RIGHT Post events*
- `ed335bff` — *freeze TIMER + tickDelta between eye renderWorld calls*

**Prerequisites:** Plan 1 must be complete and the jar must build green. Verify with `./gradlew clean build && unzip -l build/libs/stereoscopic-*.jar | grep MixinEntityRenderer_Stereo`.

**Phase 3 + Phase 4 code-review follow-ups** are now in Plan 1 Phase 5c (Tasks 23f–23k). Do not duplicate them here.

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
| `…/mixin/angelica/MixinGLStateManager_StereoRemap.java` | external mixin into Angelica's `GLStateManager.glScissor` and `glViewport` — remaps to the current eye's region whenever `StereoState.isInGuiPass()` / `isInWorldPass()` is true. Replaces the sbs2 `StereoGLSMBridge`/`StereoHook` mechanism, which depended on hook surfaces only present in the abandoned `Angelica-sbs2` fork. Required before HUD/Iris work because vanilla and Iris code internally calls `glViewport(0,0,fullW,fullH)` to reset to full screen, which must remap to the eye region while a GUI/world pass is active. |

**Modified in this plan:**

| Path | Why |
|---|---|
| `…/resources/mixins.stereoscopic.json` | Add the 12 new mixin entries |
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

### Task 1b: MixinGLStateManager_StereoRemap — external GLSM scissor/viewport remap

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\angelica\MixinGLStateManager_StereoRemap.java`
- Modify: `C:\CODE\Stereoscopic-Angelica\src\main\resources\mixins.stereoscopic.json` — add `"angelica.MixinGLStateManager_StereoRemap"`

**Context — why this task exists:** sbs2 added a `StereoHook` interface and `GLSMHooks.stereoHook` field inside Angelica, and registered a callback against it from `StereoGLSMBridge`. That mechanism doesn't exist in published Angelica `[2.1, 2.2)` and won't be added (this mod replaces Angelica-sbs entirely — see spec §"Overview"). The equivalent behavior must be achieved via external mixin into `GLStateManager`.

**Responsibilities — three remaps the mixin must perform** (math ported from the removed Plan 1 `StereoGLSMBridge`):

1. **`glScissor(x, y, w, h)` while `StereoState.isInGuiPass()`** — input is in full-screen pixels (the GUI thinks it's drawing into the whole window). Remap into the current eye's viewport region:
    ```
    vp = StereoState.INSTANCE.getEyeVp{X,Y,W,H}()
    out.x = vp.x + x * vp.w / displayW
    out.y = vp.y + y * vp.h / displayH
    out.w = w     * vp.w / displayW
    out.h = h     * vp.h / displayH
    ```
2. **`glViewport(0, 0, displayW, displayH)` while `StereoState.isInWorldPass()`** — Iris's `CompositeRenderer` and `FinalPassRenderer` reset to full-FB between shader phases. Remap to `(0, 0, irisFbWidth, irisFbHeight)` — currently identity, but keeps the Iris-side surface stable for future per-eye render-target sizing changes.
3. **`glViewport(0, 0, displayW, displayH)` while `StereoState.isInGuiPass()`** — vanilla popup/HUD code resets to full screen as setup. Remap to the eye's `(eyeVpX, eyeVpY, eyeVpW, eyeVpH)`.

Calls outside the gui-pass / world-pass windows pass through unchanged. Calls inside those windows whose `(x, y, w, h)` doesn't match the "reset to full display" pattern also pass through unchanged (the world-pass and gui-pass-viewport remaps only fire when the caller is trying to set the FULL screen — partial-screen viewport sets from inside the mixins already aim at the right region).

- [ ] **Step 1: Inspect Angelica's `GLStateManager` to find the actual method signatures and call sites**

```bash
unzip -p "$ANGELICA_JAR" com/gtnewhorizons/angelica/glsm/GLStateManager.class | javap -p - | grep -E '(glScissor|glViewport)'
```

Capture the method signatures (parameter types, static vs instance). Then decide between `@Inject(at = @At("HEAD"), cancellable = true)` + `ci.cancel()` + call `GL11.glScissor` with remapped coords, vs `@Redirect` of the underlying `org.lwjgl.opengl.GL11.glScissor` call inside `GLStateManager`. **`@Inject` + cancellable is the cleaner pattern** — it lets us veto the original `glScissor`/`glViewport` and issue our own. Use `@Redirect` only if `@Inject` proves unworkable for some structural reason.

- [ ] **Step 2: Write the mixin**

Skeleton (fill in `<method-signature>` from Step 1):

```java
package com.mitchellmarx.stereoscopic.mixin.angelica;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GLStateManager.class, remap = false)
public abstract class MixinGLStateManager_StereoRemap {

    @Inject(method = "glScissor<method-signature-from-Step-1>",
            at = @At("HEAD"), cancellable = true)
    private static void stereoscopic$remapScissor(int x, int y, int width, int height, CallbackInfo ci) {
        final StereoState st = StereoState.INSTANCE;
        if (!st.isInGuiPass()) return;
        final Minecraft mc = Minecraft.getMinecraft();
        final int dw = mc.displayWidth, dh = mc.displayHeight;
        if (dw <= 0 || dh <= 0) return;
        final int vx = st.getEyeVpX(), vy = st.getEyeVpY(), vw = st.getEyeVpW(), vh = st.getEyeVpH();
        GL11.glScissor(vx + (int)((long) x * vw / dw),
                       vy + (int)((long) y * vh / dh),
                       (int)((long) width  * vw / dw),
                       (int)((long) height * vh / dh));
        ci.cancel();
    }

    @Inject(method = "glViewport<method-signature-from-Step-1>",
            at = @At("HEAD"), cancellable = true)
    private static void stereoscopic$remapViewport(int x, int y, int width, int height, CallbackInfo ci) {
        final StereoState st = StereoState.INSTANCE;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        final int dw = mc.displayWidth, dh = mc.displayHeight;
        if (x != 0 || y != 0 || width != dw || height != dh) return;
        if (st.isInWorldPass()) {
            GL11.glViewport(0, 0, st.irisFbWidth(dw), st.irisFbHeight(dh));
            ci.cancel();
            return;
        }
        if (st.isInGuiPass()) {
            GL11.glViewport(st.getEyeVpX(), st.getEyeVpY(), st.getEyeVpW(), st.getEyeVpH());
            ci.cancel();
        }
    }
}
```

- [ ] **Step 3: Add to mixin config**

```json
"angelica.MixinGLStateManager_StereoRemap"
```

- [ ] **Step 4: Compile**

```bash
./gradlew compileMixinJava
```

- [ ] **Step 5: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/mixin/java/com/mitchellmarx/stereoscopic/mixin/angelica/MixinGLStateManager_StereoRemap.java src/main/resources/mixins.stereoscopic.json
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin): GLStateManager scissor/viewport remap during stereo gui/world pass"
```

> **Mouse-coord remap responsibility (sbs2 bridge item 4) is NOT in this mixin.** sbs2's `stereoMouseGetX`/`Y` lived next to the GLSM hooks because they shared a runtime call site. In this mod, mouse coords during stereo are handled by Plan 3's cursor backend reading directly from `StereoCursor` / `CursorBackend`. No GLSM-side intervention is needed.

---

## Phase 2 — Hand renderer per-eye depth

### Task 2: Read the sbs2 HandRenderer edit — **DONE**

**Files:** none (research)

The canonical state (`git diff master...stereo-sbs-2 -- 'src/main/java/net/coderbot/iris/pipeline/HandRenderer.java'`) differs from the historical commit `e4345194` that originally added the stereo translations. Cumulative state:

- **Projection-space offset: dropped.** sbs2 originally added `sign * 0.07f`; a later commit removed it (the value was tuned for red/cyan glasses, not SBS — at the hand's close camera distance it produced hundreds of pixels of un-convergent per-eye disparity). HEAD has only an explanatory comment at that location.
- **Modelview offset: uses `StereoState.getEyeOffset()`** (ipd/2, same as world geometry), not the originally-planned `getHandEyeOffset()` (`±0.1f * ipd/0.064`, the vanilla anaglyph value). Sharing the world's per-eye offset keeps the held item's disparity consistent with surrounding world geometry instead of flat-at-screen-depth.

Both edits live inside one private method: **`setupGlState(RenderGlobal, Camera, float)`**.

### Task 3: MixinHandRenderer_StereoDepth — **DONE at commit `31c919e`**

**Files:**
- Created: `src/mixin/java/com/mitchellmarx/stereoscopic/mixin/iris/MixinHandRenderer_StereoDepth.java`
- Modified: `src/main/resources/mixins.stereoscopic.json` — added `"iris.MixinHandRenderer_StereoDepth"`

Shipped state (single `@Inject`, modelview-only, mirroring sbs2 HEAD):

```java
@Mixin(value = HandRenderer.class, remap = false)
public abstract class MixinHandRenderer_StereoDepth {

    @Inject(
        method = "setupGlState",
        at = @At(
            value = "INVOKE",
            target = "Lcom/gtnewhorizons/angelica/glsm/GLStateManager;glLoadIdentity()V",
            ordinal = 1,
            shift = At.Shift.AFTER
        )
    )
    private void stereoscopic$applyHandModelviewOffset(CallbackInfo ci) {
        if (!StereoState.INSTANCE.isActive()) return;
        final float dx = StereoState.INSTANCE.getEyeOffset();
        if (dx != 0f) {
            GLStateManager.glTranslatef(dx, 0f, 0f);
        }
    }
}
```

Key choices:
- `ordinal = 1` on `glLoadIdentity` — the first `glLoadIdentity` is the PROJECTION-mode reset (where the dropped projection offset would have gone); the second is the MODELVIEW-mode reset, which is our anchor.
- `remap = false` on the `@Mixin` annotation — `HandRenderer` is an Iris class, java-side, not in the searge mapping. Without this the AP fails with "Unable to locate obfuscation mapping for @Inject target setupGlState".
- No projection-space `@Inject` — matches sbs2 HEAD's "disabled, comment-only" state.

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
 * with StereoState.enterGuiPass active so the MixinGLStateManager_StereoRemap mixin (Task 1b)
 * routes the popup's internal full-FB viewport reset to the current eye's region.
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

### Task 13c: MixinEntityRenderer_StereoTimerFreeze — freeze TIMER/tickDelta on RIGHT eye

**Reference port source:** sbs2 commit `ed335bff` modifies `src/mixin/java/com/gtnewhorizons/angelica/mixins/early/shaders/MixinEntityRenderer.java` (+8/-2). It adds a guard inside Angelica's existing `iris$beginRender` injection (HEAD of `EntityRenderer.renderWorld(FJ)`) that skips the per-frame `setTickDelta(...)` and `SystemTimeUniforms.TIMER.beginFrame(nanoTime)` calls when `StereoState.getCurrentEye() == Eye.RIGHT`. `SystemTimeUniforms.COUNTER.beginFrame()` stays unconditional.

**Delivery mechanism:** External `@Mixin(EntityRenderer.class)` with two `@Redirect`s at the same `INVOKE` call sites as Angelica's mixin. We can't @Inject into Angelica's existing `@Inject` body, but we can @Redirect the specific call instructions inside `renderWorld(FJ)` regardless of which mixin emitted them — mixin merges both into a single class transform.

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\minecraft\MixinEntityRenderer_StereoTimerFreeze.java`
- Modify: `C:\CODE\Stereoscopic-Angelica\src\main\resources\mixins.stereoscopic.json` — add `"minecraft.MixinEntityRenderer_StereoTimerFreeze"` to client array (alphabetical, after `MixinEntityRenderer_Stereo`)

- [ ] **Step 1: Identify exact Iris FQNs**

```bash
git -C /c/CODE/Angelica-sbs2 show ed335bff -- 'src/mixin/java/com/gtnewhorizons/angelica/mixins/early/shaders/MixinEntityRenderer.java'
```

Capture the FQNs for `CapturedRenderingState.setTickDelta(F)V` and `SystemTimeUniforms$Timer.beginFrame(J)V` (the inner class). Verify they exist in Angelica's published Iris by checking `Angelica-2.1.23-dev.jar` (cache path):
```bash
unzip -l "$(find ~/.gradle/caches/modules-2/files-2.1/com.github.GTNewHorizons/Angelica -name '*-dev.jar' -not -name '*-sources*' | head -1)" | grep -E '(CapturedRenderingState|SystemTimeUniforms)'
```

- [ ] **Step 2: Write the mixin**

```java
package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.core.StereoState;
import net.coderbot.iris.uniforms.CapturedRenderingState;
import net.coderbot.iris.uniforms.SystemTimeUniforms;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Port of sbs2 commit ed335bff. Skips setTickDelta and TIMER.beginFrame on the RIGHT
 * eye so they freeze to the LEFT eye's snapshot. COUNTER (in Angelica's shaders mixin
 * alongside these two calls) ticks unconditionally — not redirected.
 */
@Mixin(value = EntityRenderer.class, priority = 1050)
public abstract class MixinEntityRenderer_StereoTimerFreeze {

    @Redirect(
        method = "renderWorld(FJ)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/coderbot/iris/uniforms/CapturedRenderingState;setTickDelta(F)V"
        )
    )
    private void stereoscopic$freezeTickDeltaOnRightEye(CapturedRenderingState state, float partialTicks) {
        if (StereoState.INSTANCE.getCurrentEye() == StereoState.Eye.RIGHT) return;
        state.setTickDelta(partialTicks);
    }

    @Redirect(
        method = "renderWorld(FJ)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/coderbot/iris/uniforms/SystemTimeUniforms$Timer;beginFrame(J)V"
        )
    )
    private void stereoscopic$freezeTimerOnRightEye(SystemTimeUniforms.Timer timer, long nanoTime) {
        if (StereoState.INSTANCE.getCurrentEye() == StereoState.Eye.RIGHT) return;
        timer.beginFrame(nanoTime);
    }
}
```

If `CapturedRenderingState` is a singleton accessed via `CapturedRenderingState.INSTANCE.setTickDelta(...)` rather than a static call, the receiver type of the `@Redirect` adapts accordingly — verify against the actual sbs2 diff.

- [ ] **Step 3: Register and compile**

```bash
./gradlew compileMixinJava
```

Add the mixin entry to `mixins.stereoscopic.json`'s client array.

- [ ] **Step 4: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinEntityRenderer_StereoTimerFreeze.java src/main/resources/mixins.stereoscopic.json
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin): freeze TIMER + tickDelta on RIGHT eye (port ed335bff)"
```

---

## Phase 5 — Verify HUD/TIMER/ChromaticTooltips bits from Plan 1's MixinEntityRenderer_Stereo

### Task 14: Confirm Plan 1's full port included the HUD/TIMER/ChromaticTooltips injections

**Files:**
- Verify only: `…/mixin/minecraft/MixinEntityRenderer_Stereo.java`

The Plan 1 task that ported MixinEntityRenderer_Stereo did so verbatim — meaning all the injection points sbs2 added in commits `7594da2a` (HUD/GUI), `5b771503` (cursor/scissor/event dup), and `e659263b` (ChromaticTooltips re-arm) should already be present in the ported file. `ed335bff` (TIMER/tickDelta freeze) lives in Angelica's shaders mixin, not in `MixinEntityRenderer_Stereo`, and is handled by Task 13c (`MixinEntityRenderer_StereoTimerFreeze`) — not here.

- [ ] **Step 1: Verify the methods exist by grep**

```bash
grep -n -E '(stereoscopic\$|enterGuiPass|exitGuiPass|enterWorldPass|exitWorldPass|ChromaticTooltips)' \
    /c/CODE/Stereoscopic-Angelica/src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinEntityRenderer_Stereo.java
```

Expected: matches for each of the bracketed terms (note: `TIMER` is NOT one of them — TIMER freeze lives in Task 13c, not in this file).

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

**This is the test Plan 1 Task 26 deferred.** Plan 1's smoke test covered the UI-level toggle + sky/HUD splitting. This task covers the world-chunk-render path, which depends on the per-eye Iris RenderTargets / DeferredWorldRenderingPipeline / Composite/Final pass mixins shipped in this plan.

Following the spec's manual test plan items B (SBS on), C (off), D (slider), E (Iris shaderpack), G (HUD duplication including F3 + achievement popup), plus the carry-forward from Plan 1 Task 26:

- [ ] **Step 1**: Deploy via `./gradlew build`.
- [ ] **Step 2**: Launch the GTNH-daily profile.
- [ ] **Step 3**: Exercise the test items below. **Tick-box ON, no shaderpack loaded:**
  1. Sky AND HUD AND **world chunk geometry** all split L/R. Each half shows the world from the per-eye camera position (verify by toggling IPD — geometry shifts).
  2. No extreme-edge / narrow-strip artifacts in the chunk area. Each eye fills its half cleanly.
  3. IPD slider adjusts visible parallax of distant terrain.
  4. Tick OFF → returns to clean full-screen mono.
- [ ] **Step 4**: Repeat with a shaderpack loaded (Complementary Reimagined or BSL). World, sky, HUD, hand all split per-eye with shader effects intact.
- [ ] **Step 5**: Update `docs/superpowers/notes/v0.1.0-manual-test.md` with results.

If world chunks still render full-screen mono (or with narrow-strip artifacts) under either configuration, the per-eye RenderTargets port (Tasks 4–11) is incomplete or wrong. Do not advance to Plan 3 until both shader-on and shader-off cases visibly split.

---

## Self-Review

**Coverage vs spec section "Goals":**
- [x] B — HUD + GUI duplication. Plan 1's MixinEntityRenderer_Stereo port already covers the main injection points; Task 14 verifies.
- [x] C — Hand renderer per-eye depth (Task 3).
- [x] D — Iris shaderpack support (Tasks 4–11).
- [x] I — Achievement popup duplicated across eyes (Task 13).
- [x] J — TIMER / tickDelta freeze (Task 13c — external mixin into Angelica's shaders path).
- [x] K — ChromaticTooltips re-arm (Plan 1 port, verified in Task 14).

**Type consistency:** `IPerEyeRenderTargets` (Task 4) introduces `stereoscopic$setActiveEye(int)` / `stereoscopic$getActiveEye()`. Task 10's `MixinDeferredWorldRenderingPipeline_PerEye` calls those names. Consistent.

**Placeholders:** Tasks 4–11 each contain `<…>` blocks where the executor must port specific hunks from `git show fa3a63f4`. These are *instructions*, not "TBD" — the patterns and `@At` strategy table in Task 4 give the executor everything they need. The placeholder is the *bytecode position*, which can't be hard-coded into the plan without freezing it to a specific Angelica build.

**Scope check:** Plan 2 deliberately leaves the shadow-pass-skip *flag* for Plan 4 (the perf plan); the *infrastructure* for setActiveEye is laid here.

---

**End of Plan 2.** Plan 3 picks up the async OS cursor.
