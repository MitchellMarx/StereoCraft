package com.mitchellmarx.stereoscopic;

import com.mitchellmarx.stereoscopic.compat.chromatictooltips.ChromaticTooltipsCompat;
import com.mitchellmarx.stereoscopic.compat.xaero.XaeroCompat;
import com.mitchellmarx.stereoscopic.config.StereoConfig;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@Mod(
    modid = Stereoscopic.MODID,
    name = "StereoCraft: Stereoscopic 3D",
    version = Tags.VERSION,
    dependencies = "required-after:angelica;required-after:gtnhlib",
    acceptableRemoteVersions = "*",
    acceptedMinecraftVersions = "[1.7.10]"
)
@SideOnly(Side.CLIENT)
public class Stereoscopic {
    public static final String MODID = "stereoscopic";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        StereoConfig.load(event.getSuggestedConfigurationFile());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // Each compat shim in its own try/catch so partial state from a successful shim
        // doesn't leak if a later one throws. After all attempts, if any failed, rethrow
        // the first failure so mod init aborts (matches sbs2's ClientProxy.init pattern of
        // converting reflective API drift to a fatal failure).
        ReflectiveOperationException firstFailure = null;
        try { ChromaticTooltipsCompat.init(); }
        catch (ReflectiveOperationException e) { firstFailure = e; }
        try { XaeroCompat.init(); }
        catch (ReflectiveOperationException e) { if (firstFailure == null) firstFailure = e; }
        if (firstFailure != null) throw new RuntimeException(firstFailure);
    }
}
