package com.mitchellmarx.stereoscopic.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

/**
 * Keybinding to open the {@link StereoOptionsScreen}. Default unbound — users
 * pick a key via MC's Controls → Key Binds. The options screen IS still
 * accessible from the file-config path (edit {@code stereoscopic-options.json}
 * and restart) for users who don't want a key bound.
 */
public final class StereoKeybindings {

    private StereoKeybindings() {}

    public static final KeyMapping OPEN_OPTIONS = new KeyMapping(
        "key.stereoscopic.options",
        KeyConflictContext.UNIVERSAL,
        InputConstants.Type.KEYSYM,
        InputConstants.UNKNOWN.getValue(),
        "key.categories.stereoscopic"
    );

    public static void register(IEventBus modBus) {
        modBus.addListener((RegisterKeyMappingsEvent event) -> event.register(OPEN_OPTIONS));
    }

    @EventBusSubscriber(modid = "stereoscopic", value = Dist.CLIENT)
    public static final class ClientTickHandler {
        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Pre event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen != null) return;
            while (OPEN_OPTIONS.consumeClick()) {
                mc.setScreen(new StereoOptionsScreen(null));
            }
        }
    }
}
