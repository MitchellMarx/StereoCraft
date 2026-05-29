package com.mitchellmarx.stereoscopic.loading.rfb;

import com.gtnewhorizons.retrofuturabootstrap.api.PluginContext;
import com.gtnewhorizons.retrofuturabootstrap.api.RetroFuturaBootstrap;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class StereoscopicRfbPlugin implements RfbPlugin {

    @Override
    public void onConstruction(@NotNull PluginContext ctx) {}

    @Override
    public @NotNull RfbClassTransformer @Nullable [] makeTransformers() {
        final boolean isServer = (null == RetroFuturaBootstrap.API.launchClassLoader().findClassMetadata("net.minecraft.client.main.Main"));
        if (isServer) {
            return null;
        }

        final boolean isObf = RetroFuturaBootstrap.API.launchClassLoader().findClassMetadata("net.minecraft.world.World") == null;
        return new RfbClassTransformer[] {
            new RFBIsbrhTessellatorAbuseTransformer(isObf)
        };
    }
}
