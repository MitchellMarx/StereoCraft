package com.mitchellmarx.stereoscopic.gui;

import com.mitchellmarx.stereoscopic.core.StereoMode;
import com.mitchellmarx.stereoscopic.core.StereoOptions;
import com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Standalone Stereoscopic options screen. Accessed via the
 * {@code key.stereoscopic.options} keybinding (defaults unbound). Reads /
 * writes {@link StereoOptions#INSTANCE} and triggers an Iris pipeline rebuild
 * on mode change (so an Aeronautics user can toggle stereo mid-session
 * without restarting).
 */
public final class StereoOptionsScreen extends Screen {

    private final Screen parent;

    // Working copy — committed to options on Apply, discarded on Cancel.
    private StereoMode draftMode;
    private float draftIpdMm;          // millimeters in the UI; 55–75
    private float draftConvergence;    // blocks; 0–32
    private boolean draftSwapEyes;

    public StereoOptionsScreen(Screen parent) {
        super(Component.literal("Stereoscopic Options"));
        this.parent = parent;
        StereoOptions o = StereoOptions.INSTANCE;
        this.draftMode = o.mode;
        this.draftIpdMm = o.ipd * 1000f;
        this.draftConvergence = o.convergence;
        this.draftSwapEyes = o.swapEyes;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = 40;
        int rowH = 28;
        int wideW = 220;

        // Mode (CycleButton)
        this.addRenderableWidget(
            CycleButton.<StereoMode>builder(m -> Component.literal(m.name()))
                .withValues(StereoMode.OFF, StereoMode.SBS_HALF)
                .withInitialValue(draftMode)
                .create(cx - wideW / 2, y, wideW, 20,
                    Component.literal("Mode"),
                    (btn, value) -> draftMode = value));
        y += rowH;

        // IPD slider (55-75 mm)
        this.addRenderableWidget(new IpdSlider(cx - wideW / 2, y, wideW, 20, draftIpdMm));
        y += rowH;

        // Convergence slider (0-32 blocks)
        this.addRenderableWidget(new ConvergenceSlider(cx - wideW / 2, y, wideW, 20, draftConvergence));
        y += rowH;

        // Swap eyes (CycleButton on/off)
        this.addRenderableWidget(
            CycleButton.onOffBuilder(draftSwapEyes)
                .create(cx - wideW / 2, y, wideW, 20,
                    Component.literal("Swap eyes"),
                    (btn, value) -> draftSwapEyes = value));
        y += rowH + 12;

        // Apply / Cancel
        int halfBtnW = (wideW - 8) / 2;
        this.addRenderableWidget(
            Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(cx - wideW / 2, y, halfBtnW, 20)
                .build());
        this.addRenderableWidget(
            Button.builder(Component.literal("Apply"), b -> applyAndClose())
                .bounds(cx - wideW / 2 + halfBtnW + 8, y, halfBtnW, 20)
                .build());
    }

    private void applyAndClose() {
        StereoOptions o = StereoOptions.INSTANCE;
        boolean modeChanged = (o.mode != draftMode);
        o.mode = draftMode;
        o.ipd = draftIpdMm / 1000f;
        o.convergence = draftConvergence;
        o.swapEyes = draftSwapEyes;
        o.save();
        if (modeChanged) {
            PerEyeRenderTargetHooks.rebuildPipelineForStereoToggle();
        }
        onClose();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);
    }

    // --- Sliders ---

    private final class IpdSlider extends AbstractSliderButton {
        private static final float MIN = 55f, MAX = 75f;

        IpdSlider(int x, int y, int w, int h, float initialMm) {
            super(x, y, w, h, Component.empty(), (Mth.clamp(initialMm, MIN, MAX) - MIN) / (MAX - MIN));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            float mm = MIN + (float) this.value * (MAX - MIN);
            this.setMessage(Component.literal(String.format("IPD: %.1f mm", mm)));
        }

        @Override
        protected void applyValue() {
            draftIpdMm = MIN + (float) this.value * (MAX - MIN);
        }
    }

    private final class ConvergenceSlider extends AbstractSliderButton {
        private static final float MIN = 0f, MAX = 32f;

        ConvergenceSlider(int x, int y, int w, int h, float initialBlocks) {
            super(x, y, w, h, Component.empty(), (Mth.clamp(initialBlocks, MIN, MAX) - MIN) / (MAX - MIN));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            float v = MIN + (float) this.value * (MAX - MIN);
            this.setMessage(Component.literal(
                v < 0.5f ? "Convergence: 0 (parallel-axis)"
                         : String.format("Convergence: %.1f blocks", v)));
        }

        @Override
        protected void applyValue() {
            draftConvergence = MIN + (float) this.value * (MAX - MIN);
        }
    }
}
