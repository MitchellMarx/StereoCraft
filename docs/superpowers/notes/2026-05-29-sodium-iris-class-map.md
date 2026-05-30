# Sodium 0.6.13 + Iris 1.8.12 class-map for the NeoForge port

**Investigated:** 2026-05-29. Source jars: those bundled in the Aeronautics test instance — `sodium-neoforge-0.6.13+mc1.21.1.jar` and `iris-neoforge-1.8.12+mc1.21.1.jar`.

This document tracks each Fabric mixin's target class + key methods and what changes (if any) the NeoForge port needs to make. Treat as a live reference — update inline as more is discovered during the mixin port work.

## Sodium

### `MixinSodiumWorldRenderer`

**Target (Fabric):** `net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer`
**Target (NeoForge 0.6.13):** Same FQN — package layout unchanged.

**Method signature (NeoForge):**
```
public void setupTerrain(net.minecraft.client.Camera, net.caffeinemc.mods.sodium.client.render.viewport.Viewport, boolean, boolean);
```
The Fabric `@Inject(method = "setupTerrain", at = HEAD/RETURN)` ports without modification.

**Port deltas:** MC import renames only:
- `net.minecraft.client.MinecraftClient` → `net.minecraft.client.Minecraft`
- `net.minecraft.client.render.Camera` → `net.minecraft.client.Camera`
- `net.minecraft.util.math.Vec3d` → `net.minecraft.world.phys.Vec3`

## Iris

**Top-line:** All 16 target classes referenced by the 9 Fabric Iris mixins exist at the same FQN in Iris 1.8.12. The Iris source tree is unusually stable between 1.8 and 1.10. The work is mostly:
1. MC import renames on the mixin side (`MinecraftClient` → `Minecraft`, `Vec3d` → `Vec3`, etc.).
2. **Two mixins (`FinalPassRenderer`, `RenderTargets`) reference 1.21.6+ blaze3d types** (`GpuTexture`, `GpuTextureView`, `CommandEncoder`, `RenderPass`, `TextureFormat`) which don't exist in MC 1.21.1. They need real rewrites against the older Iris-1.8 API surface (raw int texture IDs via `RenderTarget.getMainTexture()`/`getAltTexture()`, classic glScissor/glViewport, etc.).

### `MixinRenderTargets` — needs rewrite

**Target:** `net.irisshaders.iris.targets.RenderTargets` (✅ exists in 1.8.12)
**Fabric uses:** `GpuTexture`, `TextureFormat` from `com.mojang.blaze3d.textures.*` (1.21.6+). Also uses `Int2IntMap`, `Int2IntOpenHashMap` from fastutil (carried by Sodium).

**Port approach:** Iris 1.8.12's `RenderTarget` exposes `getMainTexture()` / `getAltTexture()` as raw `int` texture IDs (no GpuTexture wrapping). Per-eye colortex banking can be implemented by directly tracking the int IDs in a `reverseMap`. Skip the blaze3d.textures imports entirely.

### `MixinFinalPassRenderer` — needs rewrite

**Target:** `net.irisshaders.iris.pipeline.FinalPassRenderer` (✅ exists in 1.8.12)
**Fabric uses:** `CommandEncoder.createRenderPass`, `RenderPass`, `GpuTexture`, `GpuTextureView`, `TextureFormat` (all 1.21.6+).

**Port approach:** In Iris 1.8.12, the final pass renders through the older `glBlitFramebuffer`/textured-quad path. Rewrite to use that path with manually-controlled glViewport for per-eye placement.

### `MixinCameraUniforms` — port with MC renames

**Target:** `net.irisshaders.iris.uniforms.CameraUniforms$CameraPositionTracker` (✅)
**Methods touched:**
- `update` — RETURN inject
- `getCurrentCameraPosition`, `getPreviousCameraPosition`, `getPreviousCameraPositionUnshifted` — HEAD injects, cancellable

**Port deltas:** `org.joml.Vector3d` already correct (no MC types referenced). Verify each method exists in 1.8.12's `CameraPositionTracker` (likely identical — these are stable inner-class accessors).

### `MixinMatrixUniforms` — port verbatim

**Target:** `net.irisshaders.iris.uniforms.MatrixUniforms$Previous` (✅)
**Method:** `get()Lorg/joml/Matrix4fc;` — HEAD inject, cancellable

**Port deltas:** None expected. Pure-JOML on both sides.

### `MixinIrisRenderingPipeline` — port with MC renames

**Target:** `net.irisshaders.iris.pipeline.IrisRenderingPipeline` (✅)
**Method:** `beginLevelRendering` — HEAD inject + WrapOperation

**Port deltas:** Verify `beginLevelRendering` exists in 1.8.12 with the same shape. Likely yes — Iris pipeline lifecycle methods have been stable.

### `MixinShadowRenderer` — port with MC renames

**Target:** `net.irisshaders.iris.shadows.ShadowRenderer` (✅)
**Method:** `renderShadows` — HEAD (cancellable) + RETURN injects

**Port deltas:** MC imports (`MinecraftClient` → `Minecraft`, `Vec3d` → `Vec3`, `net.minecraft.client.render.Camera` → `net.minecraft.client.Camera`).

### `MixinIrisLodRenderProgram` — port verbatim

**Target:** `net.irisshaders.iris.compat.dh.IrisLodRenderProgram` (✅)
**Method:** `fillUniformData(Lorg/joml/Matrix4fc;Lorg/joml/Matrix4fc;IF)V` — `@ModifyVariable`

**Port deltas:** None expected. Pure-JOML + `ShadowRenderingState` (✅ exists in 1.8.12).

### `MixinDHCompat` — port verbatim

**Target:** `net.irisshaders.iris.compat.dh.DHCompat` (✅)
**Method:** `getProjection()Lorg/joml/Matrix4f;` — `@ModifyReturnValue`

**Port deltas:** None expected.

### `MixinGlFramebuffer` — port verbatim

**Target:** `net.irisshaders.iris.gl.framebuffer.GlFramebuffer` (✅)
**Pattern:** `@Accessor`-only mixin (no method injects).

**Port deltas:** None expected.

## Mojang/Yarn rename quick-reference

For the MC side of any mixin port:

| Yarn (Fabric) | Mojang/Parchment (NeoForge 1.21.1) |
|---|---|
| `net.minecraft.client.MinecraftClient` | `net.minecraft.client.Minecraft` |
| `net.minecraft.client.render.Camera` | `net.minecraft.client.Camera` |
| `net.minecraft.client.render.GameRenderer` | `net.minecraft.client.renderer.GameRenderer` |
| `net.minecraft.client.render.WorldRenderer` | `net.minecraft.client.renderer.LevelRenderer` |
| `net.minecraft.client.gl.Framebuffer` | `com.mojang.blaze3d.pipeline.RenderTarget` |
| `net.minecraft.client.gl.SimpleFramebuffer` | `com.mojang.blaze3d.pipeline.TextureTarget` |
| `net.minecraft.util.math.Vec3d` | `net.minecraft.world.phys.Vec3` |
| `MinecraftClient.getInstance().gameRenderer.getCamera()` | `Minecraft.getInstance().gameRenderer.getCamera()` (same; class rename only) |
| `mc.getFramebuffer()` | `mc.getMainRenderTarget()` |
| `mc.currentScreen` | `mc.screen` |
| `mc.mouse` | `mc.mouseHandler` |
| `Window.getHandle()` | `Window.getWindow()` |
| `RenderTarget.getColorAttachment()` returns `GpuTexture` | `RenderTarget.getColorTextureId()` returns `int` directly |
| `fb.textureWidth`, `fb.textureHeight` | `fb.width`, `fb.height` |
| `com.mojang.blaze3d.opengl.GlStateManager` | `com.mojang.blaze3d.platform.GlStateManager` |

## Blaze3d (1.21.6+ → 1.21.1) — APIs to AVOID

These exist in 1.21.6+ MC and Iris 1.10's code but **do not exist** in MC 1.21.1 + Iris 1.8.12:

- `com.mojang.blaze3d.textures.GpuTexture`, `GpuTextureView`, `TextureFormat`
- `com.mojang.blaze3d.systems.CommandEncoder`, `RenderPass`
- `RenderSystem.{en,dis}ableScissorForRenderTypeDraws(...)` — added with the CommandEncoder pipeline

For each occurrence, the 1.21.1 equivalent uses the older raw-int texture ID + glBlitFramebuffer + classic scissor path. The Iris-1.8 source itself uses these older patterns, so the per-eye additions slot in alongside them.
