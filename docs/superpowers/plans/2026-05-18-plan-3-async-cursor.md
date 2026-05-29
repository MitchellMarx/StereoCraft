# Plan 3 — Async OS Cursor (Windows)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render the real OS arrow cursor into each eye half via an asynchronous WGL present thread, so users in stereo SBS_HALF + DUPLICATE see a working cursor in GUIs. Trap the cursor in the client rect during gameplay. Halve cursor X delta to match SBS_HALF's horizontal compression. Replace Plan 1's stub `CursorPresentThread` + `StereoCursor` with the real implementations.

**Architecture:** A `CursorBackend` interface selects platform-specific behavior at mod init. `WindowsCursorBackend` delegates to `CursorPresentThread` (Windows-specific WGL + GDI + Win32 reflection — the workhorse, ported verbatim from sbs2's 1253-line implementation). `NoOpCursorBackend` is a fallback for macOS/Linux. `StereoCursor` is the main-thread facade reading the virtual cursor position the present thread maintains.

**Tech Stack:** Plan 1/2 stack plus: Win32 reflection (`User32.GetCursorPos`, `LoadCursorW`, `ClipCursor`, `SetCursorPos`, GDI `BitBlt`, etc.), WGL (`wglCreateContextAttribsARB`, `wglMakeCurrent`, `wglSwapBuffers`), LWJGL `GLContext` reflection for HDC/HGLRC.

**Reference port source:** `C:\CODE\Angelica-sbs2` branch `stereo-sbs-2`. Files to port verbatim:
- `src/main/java/com/gtnewhorizons/angelica/stereo/CursorPresentThread.java` (1253 lines)
- `src/main/java/com/gtnewhorizons/angelica/stereo/StereoCursor.java` (125 lines)
- `src/mixin/java/com/gtnewhorizons/angelica/mixins/early/angelica/stereo/MixinMinecraft_AsyncCursor.java` (69 lines)
- `src/mixin/java/com/gtnewhorizons/angelica/mixins/early/angelica/stereo/MixinFramebuffer_AsyncCursor.java` (43 lines)

The `CursorBackend`/`WindowsCursorBackend`/`NoOpCursorBackend` interface layer is **new** in this mod — sbs2 doesn't have it because Angelica-sbs2 is hard-coded Windows. The existing `C:\CODE\Stereoscopic` Fabric mod has the same abstraction layer (verified) — that's the UX precedent we mirror.

**Prerequisites:** Plan 1 + Plan 2 complete and green. Verify with `./gradlew clean build && unzip -l build/libs/stereoscopic-*.jar | grep CursorPresentThread`. The stub from Plan 1 Task 16 must be present.

---

## File Structure

**Created in this plan:**

| Path | Responsibility |
|---|---|
| `…/cursor/CursorBackend.java` | Interface — `ensureStarted()`, `stop()`, `isRunning()`, `publishFrame()`, `setCursorHidden(boolean)`, `setCursorMode(int)`, `resetCursorPolling()`, `CURSOR_DISABLED` + `CURSOR_NORMAL` constants. |
| `…/cursor/WindowsCursorBackend.java` | Delegates each method to `CursorPresentThread`'s real (Windows) static methods. |
| `…/cursor/NoOpCursorBackend.java` | All methods no-op. Mac/Linux fallback. |
| `…/cursor/CursorBackendSelector.java` | Picks the backend at mod init based on `System.getProperty("os.name")`. |

**Replaced (currently stubs from Plan 1):**

| Path | Stub from Plan 1 → real impl in Plan 3 |
|---|---|
| `…/cursor/CursorPresentThread.java` | Port verbatim from sbs2 (1253 lines, Windows-specific WGL impl). |
| `…/cursor/StereoCursor.java` | Port verbatim from sbs2 (125 lines). |

**New mixins:**

| Path | Sbs2 source |
|---|---|
| `…/mixin/minecraft/MixinMinecraft_AsyncCursor.java` | sbs2's `MixinMinecraft_AsyncCursor.java` (69 lines). |
| `…/mixin/minecraft/MixinFramebuffer_AsyncCursor.java` | sbs2's `MixinFramebuffer_AsyncCursor.java` (43 lines). |

**Modified:**

| Path | Why |
|---|---|
| `…/resources/mixins.stereoscopic.json` | Add the 2 new mixin entries |
| `…/Stereoscopic.java` | Call `CursorBackendSelector.install()` in `preInit` |
| `…/mixin/minecraft/MixinEntityRenderer_Stereo.java` | Verify the Plan 1 port already wires `StereoCursor.update()` at frame begin (sbs2 commit `7f293ae4`) — if not, add it now |

---

## Phase 1 — CursorBackend abstraction

### Task 1: CursorBackend interface

**Files:**
- Create: `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\cursor\CursorBackend.java`

- [ ] **Step 1: Write the interface**

```java
package com.mitchellmarx.stereoscopic.cursor;

/**
 * Platform-abstract async cursor backend. WindowsCursorBackend delegates to CursorPresentThread
 * for the real WGL+GDI implementation; NoOpCursorBackend exists for macOS/Linux where the
 * Windows-specific Win32 reflection in CursorPresentThread wouldn't link.
 *
 * <p>The selected backend is installed at mod init by CursorBackendSelector and accessed
 * via {@link #current()}. All call sites (StereoState, StereoCursor, the AsyncCursor mixins)
 * route through this interface.</p>
 */
public interface CursorBackend {

    /** Match CursorPresentThread.CURSOR_NORMAL — passed through to setCursorMode(). */
    int CURSOR_NORMAL   = 0;
    /** Match CursorPresentThread.CURSOR_DISABLED — passed through to setCursorMode(). */
    int CURSOR_DISABLED = 1;

    void ensureStarted();
    void stop();
    boolean isRunning();
    void publishFrame();

    void setCursorHidden(boolean hidden);
    void setCursorMode(int mode);
    void resetCursorPolling();

    static CursorBackend current() { return CursorBackendHolder.INSTANCE; }
    static void install(CursorBackend backend) { CursorBackendHolder.INSTANCE = backend; }

    /** Package-private holder to avoid leaking a mutable static onto the interface itself. */
    final class CursorBackendHolder {
        static CursorBackend INSTANCE = new NoOpCursorBackend(); // default until install() lands
        private CursorBackendHolder() {}
    }
}
```

- [ ] **Step 2: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/main/java/com/mitchellmarx/stereoscopic/cursor/CursorBackend.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(cursor): CursorBackend interface (default NoOp)"
```

### Task 2: NoOpCursorBackend

**Files:**
- Create: `…/cursor/NoOpCursorBackend.java`

- [ ] **Step 1: Write the impl**

```java
package com.mitchellmarx.stereoscopic.cursor;

/**
 * macOS/Linux fallback. Stereo rendering still works (screen splits, world renders per eye),
 * but the user sees no real OS arrow cursor in the eye halves. Mouse position handling falls
 * back to LWJGL's Mouse.getX/Y, identical to mono.
 */
public final class NoOpCursorBackend implements CursorBackend {
    @Override public void ensureStarted()             {}
    @Override public void stop()                      {}
    @Override public boolean isRunning()              { return false; }
    @Override public void publishFrame()              {}
    @Override public void setCursorHidden(boolean h)  {}
    @Override public void setCursorMode(int mode)     {}
    @Override public void resetCursorPolling()        {}
}
```

- [ ] **Step 2: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/main/java/com/mitchellmarx/stereoscopic/cursor/NoOpCursorBackend.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(cursor): NoOpCursorBackend (Mac/Linux fallback)"
```

---

## Phase 2 — Port CursorPresentThread verbatim from sbs2

### Task 3: Replace the Plan 1 stub CursorPresentThread with the real impl

**Files:**
- Replace: `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\cursor\CursorPresentThread.java`

Source: `C:\CODE\Angelica-sbs2\src\main\java\com\gtnewhorizons\angelica\stereo\CursorPresentThread.java` (1253 lines).

**Substitutions on copy:**
1. `package com.gtnewhorizons.angelica.stereo;` → `package com.mitchellmarx.stereoscopic.cursor;`
2. All `import com.gtnewhorizons.angelica.stereo.X;` → `import com.mitchellmarx.stereoscopic.cursor.X;` or `import com.mitchellmarx.stereoscopic.core.X;` depending on whether X is a cursor- or core-namespace class.
3. Any read of `com.gtnewhorizons.angelica.config.AngelicaConfig.X` → `com.mitchellmarx.stereoscopic.config.StereoConfig.X`. (CursorPresentThread reads stereoIpd / stereoscopicMode / stereoHudMode in places.)
4. Everything else verbatim — including the WGL reflection, the triple-buffered presentation, the `LoadCursorW` GDI logic, the Win32 `ClipCursor` etc.

- [ ] **Step 1: Pre-flight — open both files side by side**

```bash
wc -l /c/CODE/Angelica-sbs2/src/main/java/com/gtnewhorizons/angelica/stereo/CursorPresentThread.java
wc -l /c/CODE/Stereoscopic-Angelica/src/main/java/com/mitchellmarx/stereoscopic/cursor/CursorPresentThread.java   # currently 12 (stub)
```

Note the source size: 1253 lines. The Plan 1 stub will be replaced entirely (`Write` tool, not `Edit`).

- [ ] **Step 2: Copy + substitute**

Read the source file in full. Apply the substitutions above when writing the destination.

Substitution checklist (do all four; missing one will cause a compile error):
- `^package com\.gtnewhorizons\.angelica\.stereo;$` → `^package com.mitchellmarx.stereoscopic.cursor;$`
- `^import com\.gtnewhorizons\.angelica\.stereo\.(\w+);$` → `^import com.mitchellmarx.stereoscopic.cursor.\1;$` (most) or `^import com.mitchellmarx.stereoscopic.core.\1;$` (for `StereoState`, `StereoMode`, `StereoHudMode`, `StereoDebugEye`)
- `^import com\.gtnewhorizons\.angelica\.config\.AngelicaConfig;$` → `^import com.mitchellmarx.stereoscopic.config.StereoConfig;$`
- `\bAngelicaConfig\.` → `StereoConfig.`

(Method-level identifier prefixes like `angelica$` are not used in this file — it's not a mixin.)

- [ ] **Step 3: Verify imports are clean**

```bash
grep '^import' /c/CODE/Stereoscopic-Angelica/src/main/java/com/mitchellmarx/stereoscopic/cursor/CursorPresentThread.java | grep -E '(angelica|AngelicaConfig)'
```

Expected: empty output. Any remaining `angelica` import means a substitution was missed.

- [ ] **Step 4: Compile**

```bash
./gradlew compileJava
```

If a `Cannot find symbol: X` error fires, it's almost certainly an `AngelicaConfig.X` reference that was missed — grep and fix.

- [ ] **Step 5: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/main/java/com/mitchellmarx/stereoscopic/cursor/CursorPresentThread.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(cursor): port CursorPresentThread (WGL/GDI async present) from sbs2"
```

### Task 4: Replace the Plan 1 stub StereoCursor with the real impl

**Files:**
- Replace: `C:\CODE\Stereoscopic-Angelica\src\main\java\com\mitchellmarx\stereoscopic\cursor\StereoCursor.java`

Source: `C:\CODE\Angelica-sbs2\src\main\java\com\gtnewhorizons\angelica\stereo\StereoCursor.java` (125 lines).

Substitutions identical to Task 3.

- [ ] **Step 1: Copy + substitute** as in Task 3.

- [ ] **Step 2: Compile**

```bash
./gradlew compileJava
```

- [ ] **Step 3: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/main/java/com/mitchellmarx/stereoscopic/cursor/StereoCursor.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(cursor): port StereoCursor (virtual cursor facade) from sbs2"
```

### Task 5: WindowsCursorBackend delegating to CursorPresentThread

**Files:**
- Create: `…/cursor/WindowsCursorBackend.java`

- [ ] **Step 1: Write the impl**

```java
package com.mitchellmarx.stereoscopic.cursor;

/** Delegates each interface method to the CursorPresentThread static API. */
public final class WindowsCursorBackend implements CursorBackend {
    @Override public void ensureStarted()             { CursorPresentThread.ensureStarted();       }
    @Override public void stop()                      { CursorPresentThread.stop();                }
    @Override public boolean isRunning()              { return CursorPresentThread.isRunning();    }
    @Override public void publishFrame()              { CursorPresentThread.publishFrame();        }
    @Override public void setCursorHidden(boolean h)  { CursorPresentThread.setCursorHidden(h);    }
    @Override public void setCursorMode(int mode)     { CursorPresentThread.setCursorMode(mode);   }
    @Override public void resetCursorPolling()        { CursorPresentThread.resetCursorPolling();  }
}
```

If a method in `CursorPresentThread` has a slightly different signature than what `CursorBackend` declares (e.g. `static void setCursorMode(int mode)` exists but the constant names differ), align the constants in `CursorBackend` to match `CursorPresentThread`'s naming — NOT the other way around. Sbs2 is the source of truth.

- [ ] **Step 2: Verify static signatures match**

```bash
grep -E 'public static (void|boolean) (ensureStarted|stop|isRunning|publishFrame|setCursorHidden|setCursorMode|resetCursorPolling)' \
    /c/CODE/Stereoscopic-Angelica/src/main/java/com/mitchellmarx/stereoscopic/cursor/CursorPresentThread.java
```

Each method named in `WindowsCursorBackend` should appear in the output. If any is missing, the sbs2 source has a different name — grep for the actual name and adjust.

- [ ] **Step 3: Compile + commit**

```bash
./gradlew compileJava
git -C /c/CODE/Stereoscopic-Angelica add src/main/java/com/mitchellmarx/stereoscopic/cursor/WindowsCursorBackend.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(cursor): WindowsCursorBackend delegating to CursorPresentThread"
```

### Task 6: CursorBackendSelector + wire into @Mod entrypoint

**Files:**
- Create: `…/cursor/CursorBackendSelector.java`
- Modify: `…/Stereoscopic.java`

- [ ] **Step 1: Write the selector**

```java
package com.mitchellmarx.stereoscopic.cursor;

/** Picks the platform backend at mod init. v0.1.0 = Windows only; everything else = NoOp. */
public final class CursorBackendSelector {
    private CursorBackendSelector() {}

    public static void install() {
        final String os = System.getProperty("os.name", "").toLowerCase();
        final CursorBackend backend = os.contains("win")
            ? new WindowsCursorBackend()
            : new NoOpCursorBackend();
        CursorBackend.install(backend);
    }
}
```

- [ ] **Step 2: Wire into `Stereoscopic.preInit`**

Modify `Stereoscopic.java`'s `preInit`:

```java
@Mod.EventHandler
public void preInit(FMLPreInitializationEvent event) {
    StereoConfig.load(event.getSuggestedConfigurationFile());
    com.mitchellmarx.stereoscopic.core.StereoGLSMBridge.register();
    com.mitchellmarx.stereoscopic.cursor.CursorBackendSelector.install();
}
```

- [ ] **Step 3: Compile + commit**

```bash
./gradlew compileJava
git -C /c/CODE/Stereoscopic-Angelica add src/main/java/com/mitchellmarx/stereoscopic/cursor/CursorBackendSelector.java src/main/java/com/mitchellmarx/stereoscopic/Stereoscopic.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(cursor): install platform backend at mod preInit"
```

### Task 7: Route StereoState lifecycle through CursorBackend, not direct CursorPresentThread

**Files:**
- Modify: `…/core/StereoState.java`

The Plan 1 port of `StereoState` calls `CursorPresentThread.stop()` / `.ensureStarted()` directly. That worked because Plan 1's `CursorPresentThread` was a no-op stub. Now that the real Windows impl is in place, route through `CursorBackend.current()` so Mac/Linux use NoOp.

- [ ] **Step 1: Replace the two direct calls**

In `StereoState.java`:

```java
// Before (Plan 1):
CursorPresentThread.stop();
// After (Plan 3):
CursorBackend.current().stop();
```

```java
// Before (Plan 1):
CursorPresentThread.ensureStarted();
// After (Plan 3):
CursorBackend.current().ensureStarted();
```

Update the imports — drop `import com.mitchellmarx.stereoscopic.cursor.CursorPresentThread;`, add `import com.mitchellmarx.stereoscopic.cursor.CursorBackend;`.

- [ ] **Step 2: Run the StereoState tests**

```bash
./gradlew test --tests com.mitchellmarx.stereoscopic.core.StereoStateTest
```

Expected: all pass. (`NoOpCursorBackend` is the default before `install()` runs in `preInit`, so unit tests don't need to start a real cursor thread.)

- [ ] **Step 3: Commit**

```bash
git -C /c/CODE/Stereoscopic-Angelica add src/main/java/com/mitchellmarx/stereoscopic/core/StereoState.java
git -C /c/CODE/Stereoscopic-Angelica commit -m "refactor(core): route StereoState through CursorBackend instead of direct CursorPresentThread"
```

---

## Phase 3 — Cursor mixins

### Task 8: MixinFramebuffer_AsyncCursor

**Files:**
- Create: `…/mixin/minecraft/MixinFramebuffer_AsyncCursor.java`

Source: `C:\CODE\Angelica-sbs2\src\mixin\java\com\gtnewhorizons\angelica\mixins\early\angelica\stereo\MixinFramebuffer_AsyncCursor.java` (43 lines).

- [ ] **Step 1: Write the file (verbatim port with substitutions)**

```java
package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.cursor.CursorBackend;
import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When the cursor backend is running, it owns presentation of MC's main framebuffer to the
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
        if (!CursorBackend.current().isRunning()) return;
        final Framebuffer self = (Framebuffer) (Object) this;
        if (self == Minecraft.getMinecraft().getFramebuffer()) {
            CursorBackend.current().publishFrame();
            ci.cancel();
        }
    }
}
```

- [ ] **Step 2: Add to mixin config**

Append `"minecraft.MixinFramebuffer_AsyncCursor"` to `mixins.stereoscopic.json`.

- [ ] **Step 3: Compile + commit**

```bash
./gradlew compileMixinJava
git -C /c/CODE/Stereoscopic-Angelica add src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinFramebuffer_AsyncCursor.java src/main/resources/mixins.stereoscopic.json
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin): publish framebufferMc to cursor backend at framebufferRender HEAD"
```

### Task 9: MixinMinecraft_AsyncCursor

**Files:**
- Create: `…/mixin/minecraft/MixinMinecraft_AsyncCursor.java`

Source: `C:\CODE\Angelica-sbs2\src\mixin\java\com\gtnewhorizons\angelica\mixins\early\angelica\stereo\MixinMinecraft_AsyncCursor.java` (69 lines).

- [ ] **Step 1: Write the file**

```java
package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.cursor.CursorBackend;
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
        CursorBackend.current().stop();
    }

    @Redirect(
        method = "func_147120_f",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;update()V", remap = false)
    )
    private void stereoscopic$skipSwapWhenCursorThreadOwnsIt() {
        if (CursorBackend.current().isRunning()) {
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

- [ ] **Step 2: Add to mixin config**

Append `"minecraft.MixinMinecraft_AsyncCursor"`.

- [ ] **Step 3: Compile + commit**

```bash
./gradlew compileMixinJava
git -C /c/CODE/Stereoscopic-Angelica add src/mixin/java/com/mitchellmarx/stereoscopic/mixin/minecraft/MixinMinecraft_AsyncCursor.java src/main/resources/mixins.stereoscopic.json
git -C /c/CODE/Stereoscopic-Angelica commit -m "feat(mixin): redirect Display.update() and stop cursor thread on MC shutdown"
```

---

## Phase 4 — Wire StereoCursor.update() into per-frame begin

### Task 10: Confirm MixinEntityRenderer_Stereo calls StereoCursor.update()

**Files:**
- Verify only: `…/mixin/minecraft/MixinEntityRenderer_Stereo.java`

Sbs2 commit `7f293ae4` ("seed virtual cursor at left-eye center on GUI open") adds a `StereoCursor.update()` call inside the per-frame begin block. Plan 1 ported MixinEntityRenderer_Stereo verbatim, so the call should already be present.

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

## Phase 5 — Build + manual smoke test

### Task 11: End-to-end build

- [ ] **Step 1:**

```bash
cd /c/CODE/Stereoscopic-Angelica
./gradlew clean build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Verify mixin entries**

```bash
unzip -p build/libs/stereoscopic-*.jar mixins.stereoscopic.json | grep -E 'AsyncCursor'
```

Expected: both `minecraft.MixinFramebuffer_AsyncCursor` and `minecraft.MixinMinecraft_AsyncCursor`.

### Task 12: Manual smoke test in Prism (Windows machine only)

- [ ] **Step 1**: Deploy via `./gradlew build`.

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

**Coverage vs spec section "Goals":**
- [x] G — Async OS-arrow cursor via separate GL/WGL context — `CursorPresentThread` ported in Task 3.
- [x] H — Cursor trap, virtual cursor seed at LEFT-eye center, X delta halved — covered by `StereoCursor` port (Task 4) and verified in Task 10.
- [x] M (partial) — Clean shutdown: cursor present thread stopped on MC shutdown (Task 9). The wider "clean shutdown" scope (lwjgl3ify SDL3 path closes, etc.) lands in Plan 4.

**Type consistency:**
- `CursorBackend.current()` returns a `CursorBackend` — used in Tasks 7, 8, 9.
- `CursorBackend.install(...)` is static — used in Task 6.
- `CursorPresentThread.isRunning()` / `.publishFrame()` etc. static signatures — used by `WindowsCursorBackend` (Task 5). Task 5 Step 2 verifies these names match the ported file.

**Placeholders:** None. Tasks 3–4 are verbatim ports with mechanical substitutions; the substitution rules are explicit and complete.

**Scope check:** Cursor work is self-contained — touches only `cursor/` and 3 mixins. No coupling to Iris (Plan 2) or Sodium perf (Plan 4).

**Risk note:** `CursorPresentThread`'s WGL reflection relies on LWJGL's `GLContext` and `Display` internals being reachable. Lwjgl3ify-3.0.17 (the test environment) rewrites org/lwjgl/ → org/lwjglx/ — sbs2 already handles this via `Class.forName` string literals which lwjgl3ify doesn't rewrite. Plan 4 verifies this still works on the latest lwjgl3ify; if it doesn't, the fix lives in Plan 4 (commit `89d5515c` in sbs2).

---

**End of Plan 3.** Plan 4 wraps up: Sodium perf skips, lwjgl3ify-3.0.17 SDL3 compat, final shutdown polish.
