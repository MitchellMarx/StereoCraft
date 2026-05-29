# Plan 4 — Sodium Perf + lwjgl3ify Compat + Shutdown Polish

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Final v0.1.0 work — Sodium 2nd-eye chunk-upload skip, Iris shadow-pass skip on 2nd eye, verify lwjgl3ify-3.0.17 SDL3 compat carried through from Plan 3's tip port, and confirm clean shutdown across all subsystems. After this plan ships green, v0.1.0 is feature-complete.

**Architecture:** See **Plan 1 "Porting principle"** for the canonical rule. Two new **External Mixins** (one into Angelica's Celeritas-Sodium `RenderGlobal`, one into Iris's `DeferredWorldRenderingPipeline`) port the two perf commits' modifications-to-Angelica-source as external `@Inject` redirects. Both gate behavior on `StereoState.INSTANCE.getCurrentEye() == Eye.RIGHT`. No new state classes.

**Tech Stack:** Same as Plan 3.

**Reference port source:** sbs2 commits:
- `aa91edc5` — *Stereo SBS perf: skip Sodium updateChunks on the second eye* (touches `mixins/early/celeritas/terrain/MixinRenderGlobal.java`)
- `936840f9` — *Stereo SBS perf: skip Iris shadow pass on the second eye* (touches `mixins/early/shaders/MixinEntityRenderer.java` and `iris/pipeline/DeferredWorldRenderingPipeline.java`)
- `89d5515c` — *Stereo SBS: lwjgl3ify-3.0.17 compatibility (SDL3, no GLFW)* (touches `CursorPresentThread.java`)
- `2de08d1` / `df975f3e` — clean cursor thread shutdown (already in Plan 3)

**Prerequisites:** Plans 1, 2, 3 complete and green. Verify with `./gradlew clean build && ls build/libs/`. Manual Prism smoke tests for those plans documented in `docs/superpowers/notes/v0.1.0-manual-test.md`.

**Important port note** (memory: `project_sodium_chunk_skip`): the simple "skip second eye's frustum-cull entirely when RIGHT" pattern that sbs2 uses can theoretically miss chunks visible only in the RIGHT eye's view frustum. At 64 mm IPD and 16-block chunk granularity that's sub-pixel and unobservable; this is why sbs2's approach works in practice. **We port sbs2 directly** — if/when a tester reports a missing-chunk artifact under stereo, we'd widen the LEFT-eye frustum to cover both eyes' visibility instead. Out of scope for v0.1.0.

---

## File Structure

**Created in this plan:**

| Path | Sbs2 source |
|---|---|
| `…/mixin/sodium/MixinRenderGlobal_StereoChunkSkip.java` | sbs2 `aa91edc5` diff to `MixinRenderGlobal.java` |
| `…/mixin/iris/MixinDeferredWorldRenderingPipeline_ShadowSkip.java` | sbs2 `936840f9` — combined: `renderShadows` HEAD skip + shadow-clear skip |

**Modified:**

| Path | Why |
|---|---|
| `…/resources/mixins.stereoscopic.json` | Add the 2 new mixin entries |

**Verification only (no code changes):**

| What | How |
|---|---|
| `…/cursor/CursorPresentThread.java` lwjgl3ify-3.0.17 SDL3 path | Grep for `wglGetCurrentDC` / `WindowFromDC` etc.; confirm GLFW reflection is absent |
| `…/mixin/minecraft/MixinMinecraft_AsyncCursor.java` clean shutdown | Verify `shutdownMinecraftApplet` HEAD inject is present from Plan 3 Task 9 |

---

## Phase 1 — Sodium chunk-skip on second eye

### Task 1: Identify the target class & method on Angelica's bundled Celeritas

**Files:** none (research)

- [ ] **Step 1: Confirm the FQN of the target class in Angelica's jar**

```bash
ANGELICA_JAR=$(find ~/.gradle/caches/modules-2/files-2.1/com.github.GTNewHorizons/Angelica -name "*.jar" -not -name "*-sources*" | head -1)
unzip -l "$ANGELICA_JAR" | grep -E 'RenderGlobal\.class' | head -5
```

`net/minecraft/client/renderer/RenderGlobal.class` is the MC class. Angelica's mixin (sbs2 source) targets MC's `RenderGlobal` from a celeritas-namespaced mixin — meaning the mixin attaches behavior to MC's class. Our external mixin can target the same FQN directly: `@Mixin(RenderGlobal.class)`.

- [ ] **Step 2: Confirm the method signature**

```bash
javap -classpath "$ANGELICA_JAR" net.minecraft.client.renderer.RenderGlobal | grep clipRenderersByFrustum
```

Expected: `public void clipRenderersByFrustum(net.minecraft.client.renderer.culling.ICamera, float)`. If a remapping difference fires (`func_*` SRG name vs deobf name), use the SRG name in the `@Inject` target.

### Task 2: MixinRenderGlobal_StereoChunkSkip

**Important — Angelica's `@Overwrite` collision:** Angelica's `mixins/early/celeritas/terrain/MixinRenderGlobal` uses `@Overwrite` on `clipRenderersByFrustum`. When we `@Inject(at=@At("HEAD"), cancellable=true)` on the same method, our injection lands at the start of the merged method body (which IS Angelica's overwrite body), so the HEAD-cancel works in principle — but the mixin AP may flag the overlap. Verify after `compileMixinJava`: scan the build log for "method already overwritten" / "no valid injection point" warnings. If Angelica's overwrite wins outright, raise our mixin's priority (`@Mixin(value = RenderGlobal.class, priority = 900)` — lower number = higher priority in Mixin) OR target Celeritas's BFS entry point one level deeper instead.

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\sodium\MixinRenderGlobal_StereoChunkSkip.java`

Source: sbs2 commit `aa91edc5` (12-line diff to `MixinRenderGlobal.java`).

- [ ] **Step 1: Write the file**

```java
package com.mitchellmarx.stereoscopic.mixin.sodium;

import com.mitchellmarx.stereoscopic.core.StereoState;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skip Sodium's chunk visibility BFS on the second eye. The chunk graph is keyed on camera
 * position + frustum, both identical between eyes (IPD offset lives in the modelview matrix,
 * not the camera position or frustum used for chunk culling). LEFT-eye's visible-chunks list
 * is correct for RIGHT eye — re-running BFS/cull/upload would burn ~half the chunk work for
 * no visible difference.
 *
 * <p>Known caveat (memory note project_sodium_chunk_skip): at extreme IPDs or very small
 * chunk sizes, the RIGHT eye's frustum could theoretically include chunks the LEFT eye's
 * frustum excluded. At 64 mm IPD + 16-block chunks this is sub-pixel; if a tester reports
 * a missing-chunk artifact, widen the LEFT eye's frustum to cover both eyes instead of
 * disabling this skip.
 */
@Mixin(RenderGlobal.class)
public abstract class MixinRenderGlobal_StereoChunkSkip {

    @Inject(method = "clipRenderersByFrustum", at = @At("HEAD"), cancellable = true)
    private void stereoscopic$skipChunkUpdateOnSecondEye(ICamera camera, float partialTicks, CallbackInfo ci) {
        if (StereoState.INSTANCE.isActive()
            && StereoState.INSTANCE.getCurrentEye() == StereoState.Eye.RIGHT) {
            ci.cancel();
        }
    }
}
```

- [ ] **Step 2: If the method is SRG-named in Angelica's bundled remapped jar, adjust**

If `compileMixinJava` reports "method clipRenderersByFrustum not found", look up the 1.7.10 SRG name (typically `func_72716_a` for `clipRenderersByFrustum`) and substitute. Cross-check by grepping the resolved Angelica jar's class via `javap`.

- [ ] **Step 3: Add to mixin config**

Append `"sodium.MixinRenderGlobal_StereoChunkSkip"` to `mixins.stereoscopic.json`.

- [ ] **Step 4: Compile**

```bash
cd /c/CODE/Stereoscopic-Angelica
./gradlew compileMixinJava
```

- [ ] **Step 5: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/mixin/java/com/mitchellmarx/stereoscopic/mixin/sodium/MixinRenderGlobal_StereoChunkSkip.java src/main/resources/mixins.stereoscopic.json
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin/sodium): skip chunk visibility BFS on second eye"
```

---

## Phase 2 — Iris shadow-pass skip on second eye

### Task 3: MixinDeferredWorldRenderingPipeline_ShadowSkip

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\mixin\java\com\mitchellmarx\stereoscopic\mixin\iris\MixinDeferredWorldRenderingPipeline_ShadowSkip.java`

Source: sbs2 commit `936840f9`. Sbs2 makes two changes:
1. Injects into Angelica's *own* mixin (`MixinEntityRenderer` in shaders namespace) to skip the `pipeline.renderShadows(...)` call on RIGHT eye. We can't mixin a mixin — so we go one level lower and mixin `DeferredWorldRenderingPipeline.renderShadows` directly with a HEAD cancel.
2. Modifies `DeferredWorldRenderingPipeline.java` source to skip the shadow-target clear on RIGHT eye (would otherwise wipe LEFT's output).

Both effects, plus the chunk-skip from Task 2, give the full sbs2 perf parity.

- [ ] **Step 1: Identify the shadow-clear injection point**

```bash
git -C /c/CODE/Angelica-sbs2 show 936840f9 -- 'src/main/java/net/coderbot/iris/pipeline/DeferredWorldRenderingPipeline.java'
```

The diff guards `if (shadowRenderTargets != null && !skipShadowClear)` — the `skipShadowClear` flag is set per-frame from `StereoState.INSTANCE.getCurrentEye() == Eye.RIGHT`. The enclosing method is `prepareRenderTargets` (verified against sbs2 source — the block at line ~1258 is preceded by `GLStateManager.glActiveTexture(GL13.GL_TEXTURE0)` then `final Vector4f emptyClearColor = new Vector4f(1.0F)`, both inside `private void prepareRenderTargets()`). Use `method = "prepareRenderTargets"` in the `@Inject`/`@WrapOperation` annotation.

- [ ] **Step 2: Write the mixin**

```java
package com.mitchellmarx.stereoscopic.mixin.iris;

import com.mitchellmarx.stereoscopic.core.StereoState;
import net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two-part skip of the Iris shadow pass on the RIGHT eye:
 *
 *  1. {@code renderShadows} HEAD inject — cancel the entire shadow render. Shadow map is
 *     rendered from the sun/moon POV, identical for both eyes; re-running it on the RIGHT
 *     was ~35% of frame time (sbs2 commit 936840f9 measurement). LEFT eye's shadow map is
 *     still bound when the RIGHT eye runs.
 *
 *  2. Shadow-clear skip in the method that clears render targets at frame start — clearing
 *     on the RIGHT eye would wipe the LEFT eye's shadow map. Targets a field-read on
 *     {@code shadowRenderTargets}; we cancel the entire enclosing clear logic when on RIGHT.
 *
 * Paired with the chunk skip in MixinRenderGlobal_StereoChunkSkip (Plan 4 Task 2).
 */
@Mixin(DeferredWorldRenderingPipeline.class)
public abstract class MixinDeferredWorldRenderingPipeline_ShadowSkip {

    @Inject(method = "renderShadows", at = @At("HEAD"), cancellable = true, remap = false)
    private void stereoscopic$skipShadowRenderOnRightEye(CallbackInfo ci) {
        if (StereoState.INSTANCE.isActive()
            && StereoState.INSTANCE.getCurrentEye() == StereoState.Eye.RIGHT) {
            ci.cancel();
        }
    }

    // Hunk 2: shadow-clear skip. The enclosing method is prepareRenderTargets (resolved
    // in Step 1). The injection point is the FIELD-read of shadowRenderTargets right
    // before the if-block that performs the clear.
    @Inject(
        method = "prepareRenderTargets",
        at = @At(
            value = "FIELD",
            target = "Lnet/coderbot/iris/pipeline/DeferredWorldRenderingPipeline;shadowRenderTargets:Lnet/coderbot/iris/shadows/ShadowRenderTargets;",
            opcode = org.objectweb.asm.Opcodes.GETFIELD,
            remap = false
        ),
        cancellable = true
    )
    private void stereoscopic$skipShadowClearOnRightEye(CallbackInfo ci) {
        if (StereoState.INSTANCE.isActive()
            && StereoState.INSTANCE.getCurrentEye() == StereoState.Eye.RIGHT) {
            // Don't cancel the WHOLE enclosing method — only skip the clear block.
            // Use @WrapOperation or a @Redirect targeting the GETFIELD result, returning
            // null so the if(shadowRenderTargets != null) short-circuits. The simplest
            // working approach is documented in Step 3.
            ci.cancel();
        }
    }
}
```

- [ ] **Step 3: Refine the shadow-clear skip strategy if Step 2's @Inject doesn't fit**

The Step 2 `@Inject` shape may cancel too much (the whole enclosing method). If the enclosing method has post-clear logic that must still run, switch to `@WrapOperation` from MixinExtras (already on classpath via Angelica's dependencies):

```java
@WrapOperation(
    method = "prepareRenderTargets",
    at = @At(value = "FIELD",
             target = "Lnet/coderbot/iris/pipeline/DeferredWorldRenderingPipeline;shadowRenderTargets:Lnet/coderbot/iris/shadows/ShadowRenderTargets;",
             opcode = Opcodes.GETFIELD,
             remap = false)
)
private ShadowRenderTargets stereoscopic$nullShadowTargetsOnRightEye(
        DeferredWorldRenderingPipeline self,
        Operation<ShadowRenderTargets> original) {
    final ShadowRenderTargets real = original.call(self);
    if (real != null
        && StereoState.INSTANCE.isActive()
        && StereoState.INSTANCE.getCurrentEye() == StereoState.Eye.RIGHT) {
        return null;   // makes the if(shadowRenderTargets != null) branch fall through
    }
    return real;
}
```

`@WrapOperation` lives in `com.llamalad7.mixinextras.injector.wrapoperation`. This is precisely the kind of "skip a specific block without canceling the surrounding method" the operation was designed for.

Pick Step 2's @Inject OR Step 3's @WrapOperation — not both. @WrapOperation is the cleaner shape for this hunk; the @Inject is documented above as a fallback if MixinExtras isn't available.

- [ ] **Step 4: Add to mixin config**

Append `"iris.MixinDeferredWorldRenderingPipeline_ShadowSkip"` to `mixins.stereoscopic.json`.

- [ ] **Step 5: Compile**

```bash
./gradlew compileMixinJava
```

- [ ] **Step 6: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/mixin/java/com/mitchellmarx/stereoscopic/mixin/iris/MixinDeferredWorldRenderingPipeline_ShadowSkip.java src/main/resources/mixins.stereoscopic.json
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin/iris): skip shadow render + shadow-clear on second eye"
```

---

## Phase 3 — Verify lwjgl3ify-3.0.17 SDL3 compat

### Task 4: Confirm the Plan 3 verbatim port carries the SDL3 path

**Files:** verify only — `…/cursor/CursorPresentThread.java`

Sbs2 commit `89d5515c` rewrites `CursorPresentThread` to drop the three GLFW reflective lookups (`glfwGetCurrentContext`, `glfwGetWin32Window`, `glfwGetWGLContext`) and source the same handles from raw WGL: HGLRC from `wglGetCurrentContext`, HDC from `wglGetCurrentDC`, HWND from `WindowFromDC(hdc)`. Also rewrites WGL/GDI32 entry points whose LWJGL3 wrappers grew an `IntBuffer` first arg in 3.4.x to use raw function pointers from `opengl32.dll` / `gdi32.dll`.

Since Plan 3 ports the sbs2 tip (which includes `89d5515c`), this should all be present. Confirm.

- [ ] **Step 1: Verify GLFW lookups are absent**

```bash
grep -nE 'glfwGet(CurrentContext|Win32Window|WGLContext)' \
    /c/CODE/Stereoscopic-Angelica/src/main/java/com/mitchellmarx/stereoscopic/cursor/CursorPresentThread.java
```

Expected: empty output. If matches appear, the Plan 3 port grabbed an older sbs2 revision — re-port from the tip of `stereo-sbs-2`.

- [ ] **Step 2: Verify the WGL/Win32 raw path is present**

```bash
grep -nE 'wglGetCurrentDC|wglGetCurrentContext|WindowFromDC' \
    /c/CODE/Stereoscopic-Angelica/src/main/java/com/mitchellmarx/stereoscopic/cursor/CursorPresentThread.java
```

Expected: at least one match for each of the three identifiers.

- [ ] **Step 3: Verify raw function-pointer loaders for the WGL/GDI32 calls**

```bash
grep -nE 'opengl32\.dll|gdi32\.dll' \
    /c/CODE/Stereoscopic-Angelica/src/main/java/com/mitchellmarx/stereoscopic/cursor/CursorPresentThread.java
```

Expected: at least one match for each.

- [ ] **Step 4: If any verification fails, re-port `CursorPresentThread`**

```bash
git -C /c/CODE/Angelica-sbs2 log --oneline -- 'src/main/java/com/gtnewhorizons/angelica/stereo/CursorPresentThread.java' | head -10
```

The most recent commit touching that file is the SDL3 one (`89d5515c`) plus subsequent polish. Re-run Plan 3 Task 3 against the tip.

**No commit for Task 4 if verification passes.** This task is a checkpoint.

---

## Phase 4 — Clean shutdown — final verification

### Task 5: Confirm the full shutdown sequence

**Files:** verify only.

Three subsystems must clean up at MC exit:
1. **Cursor present thread** — `CursorBackend.current().stop()` from `MixinMinecraft_AsyncCursor#stereoscopic$stopCursorThreadBeforeShutdown` (Plan 3 Task 9). Verified in Plan 3 Task 12 Step 3 F.6.
2. **`StereoConfig` save on toggle** — config is saved each toggle, no shutdown-save needed.
3. **Resource handles** — Iris owns its own pipeline disposal. Sodium owns its own chunk cleanup. We don't introduce any new long-lived handles in Plan 4.

- [ ] **Step 1: Re-run the shutdown smoke test (Plan 3 Task 12 Step 3 F.6) after all of Plan 4 is in**

Exit MC from the in-game menu, then Task Manager → confirm no `javaw.exe` lingering.

- [ ] **Step 2: Log result**

Append to `docs/superpowers/notes/v0.1.0-manual-test.md`:

```
2026-05-18  Plan 4 shutdown smoke: clean exit from in-game menu, no lingering javaw.exe.
```

---

## Phase 5 — Full v0.1.0 build + handoff smoke test

### Task 6: Final build with all four plans applied

- [ ] **Step 1: Clean build**

```bash
cd /c/CODE/Stereoscopic-Angelica
./gradlew clean build
```

Expected: BUILD SUCCESSFUL. Artifact at `build/libs/stereoscopic-0.1.0.jar`.

- [ ] **Step 2: Verify final mixin list**

```bash
unzip -p build/libs/stereoscopic-0.1.0.jar mixins.stereoscopic.json | python -m json.tool
```

Expected `"client"` array (alphabetical here for review; order in the file matches insertion):

```
iris.MixinCameraUniforms_PerEye
iris.MixinCompositeRenderer_PerEye
iris.MixinDeferredWorldRenderingPipeline_PerEye
iris.MixinDeferredWorldRenderingPipeline_ShadowSkip
iris.MixinFinalPassRenderer_PerEye
iris.MixinHandRenderer_StereoDepth
iris.MixinIrisSamplers_PerEye
iris.MixinMatrixUniforms_PerEye
iris.MixinRenderTargets_PerEye
iris.MixinViewportUniforms_PerEye
iris.MixinWorldRenderingPipeline_PerEye
minecraft.MixinEntityRenderer_Stereo
minecraft.MixinEntityRenderer_StereoCamera
minecraft.MixinFMLCommonHandler_Stereo
minecraft.MixinFramebuffer_AsyncCursor
minecraft.MixinFramebuffer_Stereo
minecraft.MixinMinecraft_AsyncCursor
minecraft.MixinMinecraft_StereoAchievement
sodium.MixinRenderGlobal_StereoChunkSkip
sodium.MixinSodiumGameOptionPages_StereoToggle
```

Total: 20 mixins.

### Task 7: Full manual test plan run

Walk through every item in the spec's manual test plan (A–H, since H was renamed from "Voxy" to "Clean shutdown" in spec self-review):

- [ ] **A. Mono baseline** — no regression vs vanilla GTNH.
- [ ] **B. SBS toggle on** — splits correctly, both eyes render.
- [ ] **C. SBS toggle off** — returns to mono within one frame.
- [ ] **D. IPD slider** — 0/64/200/500 mm. Eye separation tracks.
- [ ] **E. Iris shaderpack** — both eyes render with shader; hand depth correct; shadows from mono camera; no second-eye shadow re-render (FPS bump confirms).
- [ ] **F. Async cursor** — visible in both eyes; X delta halved; mouse trap during gameplay.
- [ ] **G. HUD/GUI** — F3, chat, inventory, achievement popup, WAILA-style overlay all duplicated per eye.
- [ ] **H. Clean shutdown** — cursor thread terminates, no lingering JVM.

Log full results to `docs/superpowers/notes/v0.1.0-manual-test.md`. v0.1.0 is feature-complete when every row is green.

### Task 8: Tag v0.1.0

If all manual rows are green:

- [ ] **Step 1: Bump version (if needed) and commit**

If `mod_version` in `gradle.properties` is still `0.1.0`, no change needed. Otherwise edit it to `0.1.0` and commit.

- [ ] **Step 2: Tag**

```bash
git -C /c/CODE/Stereoscopic-Angelica tag -a v0.1.0 -m "v0.1.0 — full sbs2 feature parity as a standalone Forge 1.7.10 mod depending on Angelica [2.1,2.2)"
```

- [ ] **Step 3: (Optional) Push tag**

```bash
# Only if a remote is set up.
git -C /c/CODE/Stereoscopic-Angelica push --tags
```

---

## Self-Review

**Coverage vs spec section "Goals":**
- [x] E — Sodium perf (chunk skip — Task 2; shadow skip — Task 3).
- [x] F (perf part) — same as E.
- [x] L — lwjgl3ify-3.0.17 SDL3 compat (Task 4 verification; the actual code rides along in Plan 3's CursorPresentThread tip port).
- [x] M — Clean shutdown (Plan 3 Task 9 + Plan 4 Task 5 verification).

**Type consistency:** `StereoState.INSTANCE.isActive()` returns `boolean`, `getCurrentEye()` returns `StereoState.Eye`. Used identically in Tasks 2 and 3. Consistent with Plan 1's `StereoState` port.

**Placeholders:** None remaining. Task 3's previous `<SHADOW_CLEAR_METHOD>` placeholder is resolved to `prepareRenderTargets` (verified against sbs2 source at line ~1258 of `DeferredWorldRenderingPipeline.java`).

**Scope check:** Plan 4 is intentionally narrow — three small mixins (one already in Plan 2/3 verbatim port, two new in Plan 4) and two verification checkpoints. Anything not on this list is out of v0.1.0 scope and waits for v0.1.1+.

---

**End of Plan 4. End of v0.1.0 plan set.**

The four plans together implement the full spec `docs/superpowers/specs/2026-05-18-stereoscopic-angelica-design.md`. After executing Plans 1 → 2 → 3 → 4 in order with manual smoke tests between each, `Stereoscopic-Angelica` v0.1.0 is feature-complete with sbs2 parity, depending on (not modifying) Angelica.
