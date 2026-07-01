package com.mitchellmarx.stereoscopic;

import com.mitchellmarx.stereoscopic.core.StereoOptions;
import com.mitchellmarx.stereoscopic.cursor.StereoCursor;
import com.mitchellmarx.stereoscopic.gui.sodium.SodiumOptionsPageIntegration;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = Stereoscopic.MOD_ID, dist = Dist.CLIENT)
public final class Stereoscopic {
    public static final String MOD_ID = "stereoscopic";
    public static final Logger LOG = LoggerFactory.getLogger("Stereoscopic");

    public Stereoscopic(IEventBus modBus) {
        modBus.addListener(this::onClientSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            StereoOptions.INSTANCE.load();
            StereoCursor.start();
            SodiumOptionsPageIntegration.tryRegister();
            LOG.info("Stereoscopic initialized");
            ModList.get().getModContainerById("iris")
                .ifPresent(c -> LOG.info("Iris detected: version={}",
                    c.getModInfo().getVersion()));
        });
    }
}
