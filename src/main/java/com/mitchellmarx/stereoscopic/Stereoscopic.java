package com.mitchellmarx.stereoscopic;

import com.mitchellmarx.stereoscopic.core.StereoOptions;
import com.mitchellmarx.stereoscopic.cursor.StereoCursor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.api.distmarker.Dist;

@Mod(value = Stereoscopic.MOD_ID, dist = Dist.CLIENT)
public final class Stereoscopic {
    public static final String MOD_ID = "stereoscopic";

    public Stereoscopic(IEventBus modBus) {
        modBus.addListener(this::onClientSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            StereoOptions.INSTANCE.load();
            StereoCursor.start();
        });
    }
}
