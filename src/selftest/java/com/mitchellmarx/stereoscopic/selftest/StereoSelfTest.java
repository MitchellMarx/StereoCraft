package com.mitchellmarx.stereoscopic.selftest;

import com.mitchellmarx.stereoscopic.config.StereoConfig;
import com.mitchellmarx.stereoscopic.core.StereoMode;
import com.mitchellmarx.stereoscopic.selftest.StereoMetrics.Shot;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.passive.EntitySheep;
import net.minecraft.init.Blocks;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Angelica (1.7.10) counterpart of the Fabric client gametest / NeoForge
 * self-test. Separate dev-only mod in the {@code selftest} source set, active
 * only with {@code -Dstereoscopic.selftest=true} (see build.gradle.kts). Drives
 * the client from tick events: creates a superflat world, builds the same
 * marker scene on the server thread, steps through the stereo configurations,
 * screenshots the main framebuffer, runs {@link StereoChecks}, writes PASS/FAIL
 * to {@link #RESULT_FILE} and shuts down; the gradle task fails unless PASS.
 */
@Mod(modid = "stereoscopic_selftest", name = "StereoCraft self-test", version = "1", dependencies = "required-after:stereoscopic")
public final class StereoSelfTest {

    static final String RESULT_FILE = "stereo-selftest-result.txt";
    private static final boolean ENABLED = Boolean.getBoolean("stereoscopic.selftest");
    private static final float IPD = 0.064f;
    private static final float CONV = 4f;
    private static final float PARALLEL_IPD = 0.1f;
    /** Superflat 1.7.10: bedrock y0, dirt y1-2, grass y3 — feet at y4. */
    private static final int Y = 4;

    /** -Dstereoscopic.test.shaderpack: shader passes (skipped if absent). */
    private static final String SHADER_PACK = System.getProperty("stereoscopic.test.shaderpack");
    private static final boolean SHADERS = SHADER_PACK != null && new File(SHADER_PACK).isFile();

    /** Forward distance (blocks) from the eye to each marker's visible face. */
    private static final Map<StereoMetrics.Marker, Double> DEPTH = new EnumMap<>(StereoMetrics.Marker.class);
    static {
        DEPTH.put(StereoMetrics.Marker.RED, 5.5);
        DEPTH.put(StereoMetrics.Marker.BLUE, 11.5);
        DEPTH.put(StereoMetrics.Marker.YELLOW, 29.5);
        DEPTH.put(StereoMetrics.Marker.SHEEP, 7.6);
        DEPTH.put(StereoMetrics.Marker.PARTICLE, 19.5);
    }

    /** shader: options file text to load the test pack with before this step, "-" to turn shaders off. */
    private static final class Step {
        final String name; final StereoMode mode; final float ipd, conv; final boolean swap, resize; final String shader;
        Step(String name, StereoMode mode, float ipd, float conv, boolean swap, boolean resize) {
            this(name, mode, ipd, conv, swap, resize, null);
        }
        Step(String name, StereoMode mode, float ipd, float conv, boolean swap, boolean resize, String shader) {
            this.name = name; this.mode = mode; this.ipd = ipd; this.conv = conv; this.swap = swap; this.resize = resize; this.shader = shader;
        }
    }

    private static final Step[] STEPS = steps();

    private static Step[] steps() {
        java.util.List<Step> l = new java.util.ArrayList<>(java.util.Arrays.asList(
            new Step("mono", StereoMode.OFF, IPD, CONV, false, false),
            new Step("half_conv4", StereoMode.SBS_HALF, IPD, CONV, false, false),
            new Step("half_conv4_swap", StereoMode.SBS_HALF, IPD, CONV, true, false),
            new Step("half_parallel", StereoMode.SBS_HALF, PARALLEL_IPD, 0f, false, false),
            new Step("half_parallel_swap", StereoMode.SBS_HALF, PARALLEL_IPD, 0f, true, false),
            new Step("full_conv4", StereoMode.SBS_FULL, IPD, CONV, false, false),
            new Step("half_ipd0", StereoMode.SBS_HALF, 0.0005f, 0f, false, false),
            new Step("half_conv4_after_resize", StereoMode.SBS_HALF, IPD, CONV, false, true)));
        if (SHADERS) {
            // Complementary r5.7: TAA_MODE 1 = TAA on, 0 = off.
            for (String[] pass : new String[][] {{"shaders", "TAA_MODE=1\n"}, {"shaders_noTAA", "TAA_MODE=0\n"}}) {
                l.add(new Step(pass[0] + "_half_conv4", StereoMode.SBS_HALF, IPD, CONV, false, false, pass[1]));
                l.add(new Step(pass[0] + "_half_parallel", StereoMode.SBS_HALF, PARALLEL_IPD, 0f, false, false));
                l.add(new Step(pass[0] + "_half_ipd0", StereoMode.SBS_HALF, 0.0005f, 0f, false, false));
            }
        }
        return l.toArray(new Step[0]);
    }

    private enum Phase { BOOT, CREATING, SCENE, SETTLE, STEP, POPUP, EXITING, DONE }

    private Phase phase = Phase.BOOT;
    private int wait = 0;
    private int step = 0;
    private int totalTicks = 0;
    private volatile boolean sceneBuilt = false;
    private volatile boolean sceneRequested = false;
    private final Map<String, Shot> shots = new HashMap<>();
    private Shot popupShot;

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        if (ENABLED) FMLCommonHandler.instance().bus().register(this);
    }

    /** World edits must happen on the integrated server thread. */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !sceneRequested || sceneBuilt) return;
        MinecraftServer server = MinecraftServer.getServer();
        WorldServer w = server.worldServerForDimension(0);
        w.setWorldTime(6000);
        w.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
        w.getGameRules().setOrCreateGameRule("doMobSpawning", "false"); // superflat slimes wander into view
        w.getWorldInfo().setRaining(false);
        w.getWorldInfo().setThundering(false);
        column(w, -2, 6, 5, 14);   // red, near
        column(w, 0, 12, 6, 11);   // blue, mid
        column(w, 5, 30, 9, 4);    // yellow, far
        // World-gen animals can wander next to the markers.
        for (Object o : new java.util.ArrayList<Object>(w.loadedEntityList)) {
            if (o instanceof net.minecraft.entity.EntityLiving) ((net.minecraft.entity.Entity) o).setDead();
        }
        EntitySheep sheep = new EntitySheep(w);
        sheep.setFleeceColor(2); // magenta
        sheep.setLocationAndAngles(2.5, Y, 8.5, 90f, 0f);
        sheep.addPotionEffect(new PotionEffect(Potion.moveSlowdown.id, 1_000_000, 10));
        w.spawnEntityInWorld(sheep);
        for (Object o : server.getConfigurationManager().playerEntityList) {
            ((EntityPlayerMP) o).playerNetServerHandler.setPlayerLocation(0.5, Y, 0.5, 0f, 10f);
        }
        sceneBuilt = true;
    }

    private static void column(WorldServer w, int x, int z, int height, int woolMeta) {
        for (int y = Y; y < Y + height; y++) w.setBlock(x, y, z, Blocks.wool, woolMeta, 3);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || phase == Phase.DONE) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (++totalTicks > 20 * 300 && phase != Phase.EXITING) finish(mc, "timed out in phase " + phase);
        if (wait > 0) { wait--; return; }
        try {
            switch (phase) {
                case BOOT:
                    mc.gameSettings.pauseOnLostFocus = false;
                    if (!(mc.currentScreen instanceof GuiMainMenu) || mc.theWorld != null) return;
                    WorldSettings ws = new WorldSettings(0L, WorldSettings.GameType.CREATIVE, false, false, WorldType.FLAT);
                    ws.enableCommands();
                    mc.launchIntegratedServer("stereo-selftest-" + System.currentTimeMillis(), "stereo-selftest", ws);
                    phase = Phase.CREATING;
                    break;
                case CREATING:
                    if (mc.theWorld == null || mc.thePlayer == null || mc.currentScreen != null) return;
                    mc.gameSettings.hideGUI = true;
                    // The "Press E" tutorial popup ignores hideGUI and covers markers.
                    mc.gameSettings.showInventoryAchievementHint = false;
                    mc.guiAchievement.func_146257_b(); // clear the already-queued hint
                    sceneRequested = true;
                    phase = Phase.SCENE;
                    break;
                case SCENE:
                    if (!sceneBuilt) return;
                    phase = Phase.SETTLE;
                    wait = 100; // chunk rebuild after the edits + teleport
                    break;
                case SETTLE:
                    apply(mc, STEPS[0]);
                    phase = Phase.STEP;
                    wait = 20;
                    break;
                case STEP:
                    Step s = STEPS[step];
                    shots.put(s.name, capture(mc, s.name));
                    if (++step >= STEPS.length) {
                        // Achievement/tutorial popup: GuiAchievement draws outside
                        // the GUI flush with its own full-display viewport reset.
                        apply(mc, new Step("popup", StereoMode.SBS_HALF, IPD, CONV, false, false, SHADERS ? "-" : null));
                        mc.guiAchievement.func_146255_b(net.minecraft.stats.AchievementList.openInventory);
                        phase = Phase.POPUP;
                        wait = 80; // tutorial slides in after 2.5 s
                        return;
                    }
                    apply(mc, STEPS[step]);
                    // First stereo activation re-sizes the Iris targets; allow
                    // a full chunk/render settle like the other harnesses. A
                    // shader pack load compiles every program first.
                    wait = STEPS[step].shader != null ? 300 : step == 1 ? 60 : (STEPS[step].resize ? 40 : 20);
                    break;
                case POPUP:
                    popupShot = capture(mc, "half_popup");
                    mc.guiAchievement.func_146257_b();
                    evaluate(mc);
                    break;
                case EXITING:
                    phase = Phase.DONE;
                    mc.shutdown();
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            StereoChecks.LOG.error("[stereo-test] self-test crashed", t);
            finish(mc, "exception: " + t);
        }
    }

    private static void apply(Minecraft mc, Step s) throws Exception {
        StereoConfig.stereoscopicMode = s.mode;
        StereoConfig.stereoIpd = s.ipd;
        StereoConfig.stereoConvergence = s.conv;
        StereoConfig.stereoSwapEyes = s.swap;
        if (s.resize) Display.setDisplayMode(new DisplayMode(1000, 560));
        if (s.shader != null) setShaders(mc, s.shader);
        // Particle marker, re-spawned for each shot (the capture follows the
        // wait). Left of the sheep, unlike the Fabric scene's x -3: in the
        // 1.7.10 view that spot shares rows with the near red pillar's edge,
        // whose disparity leaks into the particle's correlation patch.
        mc.effectRenderer.addEffect(new MarkerFX(mc.theWorld, 9.0, Y + 2.5, 20.0));
    }

    /** Loads the test pack with {@code options} (its .txt), or turns shaders off for "-". */
    private static void setShaders(Minecraft mc, String options) throws Exception {
        net.coderbot.iris.config.IrisConfig cfg = net.coderbot.iris.Iris.getIrisConfig();
        boolean on = !options.equals("-");
        if (on) {
            Path src = new File(SHADER_PACK).toPath();
            Path dir = new File(mc.mcDataDir, "shaderpacks").toPath();
            Files.createDirectories(dir);
            Files.copy(src, dir.resolve(src.getFileName().toString()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.write(dir.resolve(src.getFileName() + ".txt"), options.getBytes("UTF-8")); // Iris per-pack options
            cfg.setShaderPackName(src.getFileName().toString());
        }
        cfg.setShadersEnabled(on);
        cfg.save();
        net.coderbot.iris.Iris.reload();
        StereoChecks.LOG.info("[stereo-test] shaders {}: {} [{}]", on ? "ON" : "OFF", SHADER_PACK, options.trim());
    }

    /**
     * Particle marker: a motionless cyan-wool digging particle (normal
     * EffectRenderer path), 1 block across, never fading for 20 s. 1.7.10 has
     * no static particle type like later versions' block_marker.
     */
    private static final class MarkerFX extends net.minecraft.client.particle.EntityDiggingFX {
        MarkerFX(net.minecraft.world.World w, double x, double y, double z) {
            super(w, x, y, z, 0, 0, 0, Blocks.wool, 9); // 9 = cyan
            motionX = motionY = motionZ = 0;
            particleGravity = 0;
            particleScale = 5f;
            particleMaxAge = 400;
            particleRed = particleGreen = particleBlue = 1f;
        }

        @Override
        public void onUpdate() {
            prevPosX = posX; prevPosY = posY; prevPosZ = posZ;
            if (particleAge++ >= particleMaxAge) setDead();
        }
    }

    private static Shot capture(Minecraft mc, String name) {
        File dir = new File(mc.mcDataDir, "screenshots");
        String file = "selftest_" + name + ".png";
        ScreenShotHelper.saveScreenshot(mc.mcDataDir, file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        return StereoMetrics.measure(name, new File(dir, file).toPath());
    }

    private void evaluate(Minecraft mc) {
        StereoChecks c = new StereoChecks(IPD, PARALLEL_IPD);
        c.presence(shots.get("half_conv4"), shots.get("half_conv4_swap"), shots.get("half_parallel"),
            shots.get("half_parallel_swap"), shots.get("full_conv4"), shots.get("half_conv4_after_resize"));
        c.swapMirrors(shots.get("half_conv4"), shots.get("half_conv4_swap"));
        c.swapMirrors(shots.get("half_parallel"), shots.get("half_parallel_swap"));
        c.parallel(shots.get("half_parallel"));
        c.depthScaling(shots.get("half_parallel"), DEPTH);
        c.convergenceConsistent(shots.get("half_conv4"), shots.get("half_parallel"));
        c.aspect(shots.get("mono"), shots.get("half_conv4"), shots.get("full_conv4"));
        c.eyesIdentical(shots.get("half_ipd0"));
        c.popupInBothHalves(popupShot);
        if (SHADERS) {
            for (String p : new String[] {"shaders", "shaders_noTAA"}) {
                c.presence(shots.get(p + "_half_conv4"), shots.get(p + "_half_parallel"));
                c.parallel(shots.get(p + "_half_parallel"));
                c.depthScaling(shots.get(p + "_half_parallel"), DEPTH);
                c.convergenceConsistent(shots.get(p + "_half_conv4"), shots.get(p + "_half_parallel"));
                c.eyesIdentical(shots.get(p + "_half_ipd0"));
            }
        } else {
            StereoChecks.LOG.info("[stereo-test] shaders: SKIPPED (no -Dstereoscopic.test.shaderpack)");
        }
        finish(mc, c.failures.isEmpty() ? null : String.join("\n  ", c.failures));
    }

    private void finish(Minecraft mc, String failure) {
        if (failure == null) {
            StereoChecks.LOG.info("[stereo-test] ALL CHECKS PASSED");
        } else {
            StereoChecks.LOG.error("[stereo-test] Stereo self-test failed:\n  {}", failure);
        }
        try {
            Path result = mc.mcDataDir.toPath().resolve(RESULT_FILE);
            Files.write(result, (failure == null ? "PASS\n" : "FAIL\n  " + failure + "\n").getBytes("UTF-8"));
        } catch (Exception ex) {
            StereoChecks.LOG.error("[stereo-test] could not write result file", ex);
        }
        StereoConfig.stereoscopicMode = StereoMode.OFF;
        phase = Phase.EXITING;
        wait = 20;
    }
}
