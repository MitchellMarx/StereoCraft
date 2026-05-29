# Plan 3 — Async OS Cursor Mixins + Smoke Test

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** With Plan 1's cursor port complete (`CursorBackend` interface + `WindowsCursorBackend` + `NoOpCursorBackend` + real `CursorPresentThread` + real `StereoCursor`), add the two mixins that integrate the cursor system into Minecraft's frame/shutdown lifecycle, then run an end-to-end manual smoke test.

**Architecture:** See **Plan 1 "Porting principle"** for the canonical rule (port sbs2's `master...stereo-sbs-2` diff verbatim; NEW files copy whole, MODIFIED Angelica files apply externally via mixin/coremod/RFB-plugin).

Plan 1 Phase 4 Tasks 22b–22f already landed the cursor scaffolding — interface, backends, present thread, and main-thread facade. Plan 1 Task 22 (`MixinEntityRenderer_Stereo` verbatim port) already wires `StereoCursor.update()` into the per-frame begin block, since sbs2's source had that call inline. This plan adds the two **external mixins** (NEW files in sbs2's diff at `mixins/early/angelica/stereo/Mixin{Minecraft,Framebuffer}_AsyncCursor.java`) that close the loop: redirecting MC's framebuffer-render and Display.update calls so the cursor thread owns presentation when active.

Also: re-enable the cursor-thread runtime activation that Plan 1 Phase 6 debugging gated off (StereoState.beginFrame()'s ensureStarted/stop calls + MixinEntityRenderer_Stereo's StereoCursor.update() call — both TODO(plan-3) marked).

**Tech Stack:** Plan 1/2 stack. No new dependencies.

**Reference port source:** `C:\CODE\Angelica-sbs2` branch `stereo-sbs-2`. Files to port verbatim:
- `src/mixin/java/com/gtnewhorizons/angelica/mixins/early/angelica/stereo/MixinMinecraft_AsyncCursor.java` (69 lines)
- `src/mixin/java/com/gtnewhorizons/angelica/mixins/early/angelica/stereo/MixinFramebuffer_AsyncCursor.java` (43 lines)

**Substitutions for both files:**
- Package: `com.gtnewhorizons.angelica.mixins.early.angelica.stereo` → `com.mitchellmarx.stereoscopic.mixin.minecraft`
- `import com.gtnewhorizons.angelica.stereo.CursorPresentThread;` → `import com.mitchellmarx.stereoscopic.cursor.CursorPresentThread;`
- `angelica$` → `stereoscopic$`
- If sbs2 uses `CursorBackend.current().X()`, treat as `CursorPresentThread.X()` (our Plan 1 design has CursorPresentThread own the backend internally; no `CursorBackend.current()` accessor).

**Prerequisites:** Plan 1 fully complete (including all of Phase 4 — Tasks 20, 21, 22a–22i, 22) and green build. Verify with `./gradlew clean build && unzip -l build/libs/stereoscopic-*.jar | grep -E 'CursorPresentThread|StereoCursor'` and confirm both `.class` entries appear *not* as Plan 1 stubs.

---

## File Structure

**Created in this plan:**

| Path | Sbs2 source |
|---|---|
| `…/mixin/minecraft/MixinMinecraft_AsyncCursor.java` | sbs2's `MixinMinecraft_AsyncCursor.java` (69 lines) — redirects `Display.update()` and stops cursor thread at MC shutdown. |
| `…/mixin/minecraft/MixinFramebuffer_AsyncCursor.java` | sbs2's `MixinFramebuffer_AsyncCursor.java` (43 lines) — publishes `framebufferMc` into the cursor thread's present texture and cancels MC's own blit when the cursor thread owns presentation. |

**Modified:**

| Path | Why |
|---|---|
| `…/resources/mixins.stereoscopic.json` | Add the 2 new mixin entries |
| `…/mixin/minecraft/MixinEntityRenderer_Stereo.java` | Verify the Plan 1 port already wires `StereoCursor.update()` at frame begin (sbs2 commit `7f293ae4`) — Plan 1 ported the file verbatim so this should already be present. |

---

## Phase 1 — Cursor mixins

### Task 1: MixinFramebuffer_AsyncCursor

**Files:**
- Create: `…/mixin/minecraft/MixinFramebuffer_AsyncCursor.java`

**Source:** `C:\CODE\Angelica-sbs2\src\mixin\java\com\gtnewhorizons\angelica\mixins\early\angelica\stereo\MixinFramebuffer_AsyncCursor.java` (43 lines).

- [ ] **Step 1: Read sbs2 source**

```bash
cat /c/CODE/Angelica-sbs2/src/mixin/java/com/gtnewhorizons/angelica/mixins/early/angelica/stereo/MixinFramebuffer_AsyncCursor.java
```

- [ ] **Step 2: Port the file applying substitutions**

Skeleton (fill body verbatim from sbs2; the sbs2 file likely uses `CursorBackend.current().*` which substitutes to `CursorPresentThread.*` in our design):

```java
package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.cursor.CursorPresentThread;
import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When the cursor thread is running, it owns presentation of MC's main framebuffer to the
 * default backbuffer — it does its own blit + cursor + swap at compositor rate. MC's main
 * thread also calls framebufferRender to blit framebufferMc; with the cursor thread also
 * writing the default FB, the two writes race and tear.
 *
 * <p>framebufferRender's HEAD is also where we publish framebufferMc into the cursor thread's
 * present texture — by then every render path that targets framebufferMc has run.</p>
 */
@Mixin(value = Framebuffer.class, priority = 1100)
public class MixinFramebuffer_AsyncCursor {

    @Inject(method = "framebufferRender", at = @At("HEAD"), cancellable = true)
    public void stereoscopic$capturePublishAndSkip(int width, int height, CallbackInfo ci) {
        if (!CursorPresentThread.isRunning()) return;
        final Framebuffer self = (Framebuffer) (Object) this;
        if (self == Minecraft.getMinecraft().getFramebuffer()) {
            CursorPresentThread.publishFrame();
            ci.cancel();
        }
    }
}
```

- [ ] **Step 3: Compile**

```bash
./gradlew compileMixinJava
```

- [ ] **Step 4: Commit (mixin config update batched in Task 4)**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinFramebuffer_AsyncCursor.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin): publish framebufferMc to cursor thread at framebufferRender HEAD"
```

### Task 2: MixinMinecraft_AsyncCursor

**Files:**
- Create: `…/mixin/minecraft/MixinMinecraft_AsyncCursor.java`

**Source:** `C:\CODE\Angelica-sbs2\src\mixin\java\com\gtnewhorizons\angelica\mixins\early\angelica\stereo\MixinMinecraft_AsyncCursor.java` (69 lines).

- [ ] **Step 1: Read sbs2 source**

```bash
cat /c/CODE/Angelica-sbs2/src/mixin/java/com/gtnewhorizons/angelica/mixins/early/angelica/stereo/MixinMinecraft_AsyncCursor.java
```

- [ ] **Step 2: Port the file applying substitutions**

```java
package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.cursor.CursorPresentThread;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stop the cursor thread before window teardown; redirect MC's Display.update() to
 * Display.processMessages() while the cursor thread owns presentation.
 *
 * <p>Display.update() does swap + pumpMessages. Cursor thread also swaps. Two swaps race
 * on the same window backbuffer; MC's wins, presents black. processMessages() pumps without
 * swapping — leaving cursor thread sole presenter.</p>
 */
@Mixin(value = Minecraft.class, priority = 1100)
public class MixinMinecraft_AsyncCursor {

    @Inject(method = "shutdownMinecraftApplet", at = @At("HEAD"))
    private void stereoscopic$stopCursorThreadBeforeShutdown(CallbackInfo ci) {
        CursorPresentThread.stop();
    }

    @Redirect(
        method = "func_147120_f",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;update()V", remap = false)
    )
    private void stereoscopic$skipSwapWhenCursorThreadOwnsIt() {
        if (CursorPresentThread.isRunning()) {
            Display.processMessages();
            // lwjglx's Display.update() also transfers latestResized → displayResized;
            // processMessages() doesn't. Detect resize ourselves and drive mc.resize.
            final Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && !mc.isFullScreen()) {
                final int w = Display.getWidth();
                final int h = Display.getHeight();
                if (w > 0 && h > 0 && (w != mc.displayWidth || h != mc.displayHeight)) {
                    mc.resize(w, h);
                }
            }
        } else {
            Display.update();
        }
    }
}
```

- [ ] **Step 3: Compile**

```bash
./gradlew compileMixinJava
```

- [ ] **Step 4: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinMinecraft_AsyncCursor.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin): redirect Display.update() and stop cursor thread on MC shutdown"
```

---

## Phase 2 — Verify per-frame begin wiring

### Task 3: Confirm MixinEntityRenderer_Stereo calls StereoCursor.update()

**Files:**
- Verify only: `…/mixin/minecraft/MixinEntityRenderer_Stereo.java`

Sbs2 commit `7f293ae4` ("seed virtual cursor at left-eye center on GUI open") adds a `StereoCursor.update()` call inside the per-frame begin block of `MixinEntityRenderer_Stereo`. Plan 1 Task 22 ported that file verbatim, so the call should already be present.

- [ ] **Step 1: Verify**

```bash
grep -n 'StereoCursor\.update' /c/CODE/Stereoscopic-Angelica/src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinEntityRenderer_Stereo.java
```

Expected: at least one match. If missing, port the relevant `7f293ae4` hunk:

```bash
git -C /c/CODE/Angelica-sbs2 show 7f293ae4 -- 'src/mixin/java/com/gtnewhorizons/angelica/mixins/early/angelica/stereo/MixinEntityRenderer_Stereo.java'
```

Apply the substitutions (`angelica$` → `stereoscopic$`, `com.gtnewhorizons.angelica.stereo.StereoCursor` → `com.mitchellmarx.stereoscopic.cursor.StereoCursor`) and edit the file.

- [ ] **Step 2: Commit (only if Step 1 found a gap)**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinEntityRenderer_Stereo.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin): wire StereoCursor.update() into per-frame begin"
```

---

## Phase 3 — Register mixins + build + smoke test

### Task 4: Register the two new mixins in mixins.stereoscopic.json

**Files:**
- Modify: `…/src/main/resources/mixins.stereoscopic.json`

Append `"minecraft.MixinFramebuffer_AsyncCursor"` and `"minecraft.MixinMinecraft_AsyncCursor"` to the `client` array.

- [ ] **Step 1: Update the JSON.** Keep existing entries in their current order; insert these two alongside the other `minecraft.*` mixins (alphabetical order is fine but not required).

- [ ] **Step 2: Compile (full mixin pass)**

```bash
./gradlew compileMixinJava
```

- [ ] **Step 3: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/main/resources/mixins.stereoscopic.json
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin): register MixinFramebuffer_AsyncCursor + MixinMinecraft_AsyncCursor"
```

### Task 5: End-to-end build

- [ ] **Step 1:**

```bash
cd /c/CODE/Stereoscopic-Angelica
./gradlew clean build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Verify mixin entries in the packaged jar**

```bash
unzip -p build/libs/stereoscopic-*.jar mixins.stereoscopic.json | grep -E 'AsyncCursor'
```

Expected: both `minecraft.MixinFramebuffer_AsyncCursor` and `minecraft.MixinMinecraft_AsyncCursor`.

### Task 6: Manual smoke test in Prism (Windows machine only)

- [ ] **Step 1**: Deploy via `./gradlew build` (the `copyToTestInstance` task fires after `build` and drops the jar into the GTNH-daily profile's mods dir).

- [ ] **Step 2**: Launch the GTNH-daily profile. Enter a world. Toggle SBS on.

- [ ] **Step 3**: Test plan items F + H from the spec:
  - **F.1** Open an in-game GUI (e.g. inventory). OS arrow cursor should appear in BOTH eye halves.
  - **F.2** Move the mouse. Both cursors track. Speed should feel natural (X delta halved internally — sbs2 commit `965bb81d`).
  - **F.3** Cursor should NOT leave the left-eye GUI region (clamped at seam).
  - **F.4** Close the GUI. Cursor disappears; mouse-look works normally.
  - **F.5** Re-open GUI. Cursor seeds at left-eye center (sbs2 `7f293ae4`).
  - **F.6** Exit MC cleanly via the in-game menu. No `javaw.exe` left running (Task Manager check). This validates `shutdownMinecraftApplet` HEAD inject (sbs2 `2de08d1` / `df975f3e`).

- [ ] **Step 4**: Log to `docs/superpowers/notes/v0.1.0-manual-test.md`.

---

## Self-Review

**Coverage vs spec §"Goals":**
- [x] G — Async OS-arrow cursor via separate GL/WGL context — `CursorPresentThread` real impl landed in Plan 1 Task 22e; the two mixins here close the loop (own presentation when running, stop cleanly on shutdown).
- [x] H — Cursor trap, virtual cursor seed at LEFT-eye center, X delta halved — `StereoCursor` real port landed in Plan 1 Task 22f; verified per-frame wiring in Task 3 above.
- [x] M (partial) — Clean shutdown: cursor present thread stopped on MC shutdown (Task 2). The wider "clean shutdown" scope (lwjgl3ify SDL3 path closes, etc.) lands in Plan 4.

**Scope check:** This plan is just the two cursor mixins + verification + smoke test. All the cursor scaffolding (`CursorBackend` interface, `WindowsCursorBackend`, `NoOpCursorBackend`, real `CursorPresentThread`, real `StereoCursor`) moved into Plan 1 Phase 4 (Tasks 22b–22f) because Plan 1's `MixinEntityRenderer_Stereo` port already depends on those symbols existing — keeping them in Plan 3 would have made Plan 1 uncompilable.

**Risk note:** `CursorPresentThread`'s WGL reflection (in Plan 1 Task 22e) relies on LWJGL's `GLContext` and `Display` internals being reachable. Lwjgl3ify-3.0.17 (the test environment) rewrites `org/lwjgl/` → `org/lwjglx/` — sbs2 already handles this via `Class.forName` string literals which lwjgl3ify doesn't rewrite. Plan 4 verifies this still works on the latest lwjgl3ify; if it doesn't, the fix lives in Plan 4 (commit `89d5515c` in sbs2).

---

**End of Plan 3.** Plan 4 wraps up: Sodium perf skips, lwjgl3ify-3.0.17 SDL3 compat, final shutdown polish.
