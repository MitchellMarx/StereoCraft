package com.mitchellmarx.stereoscopic.loading;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import cpw.mods.fml.relauncher.IFMLLoadingPlugin.MCVersion;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;

@MCVersion("1.7.10")
public class StereoscopicCoreMod implements IFMLLoadingPlugin {
    private static final Logger LOGGER = LogManager.getLogger("StereoscopicCoreMod");

    /**
     * Captured from {@link #injectData(Map)}'s {@code runtimeDeobfuscationEnabled} entry.
     * True in production obfuscated environments, false in dev. Read by the transformers'
     * no-arg constructors to pick the correct (obf vs. MCP) method names.
     *
     * IFMLLoadingPlugin.injectData is invoked by FML before any of this plugin's transformers
     * are instantiated, so this field is populated by the time the transformer ctor runs.
     */
    public static boolean OBF_ENV = false;

    /**
     * True once {@link #addCursorPackageToRedirectorExclusions()} successfully added our prefix
     * to Angelica's GLSM-redirector exclusions. Set from the first FML lifecycle hook that
     * finds the redirector handle populated. Public so the IsbrhTessellator transformer
     * constructor can also retry if the coremod-constructor / injectData attempts both ran
     * too early.
     */
    public static volatile boolean CURSOR_EXCLUSION_APPLIED = false;

    public StereoscopicCoreMod() {
        // Add our cursor package to Angelica's GLSM redirector exclusion list.
        //
        // Sbs2's CursorPresentThread lives at com.gtnewhorizons.angelica.stereo. — that package
        // is explicitly added to AngelicaRedirector.allExclusions inside the sbs2 fork's
        // AngelicaRedirector constructor. Mainline Angelica (which we depend on) doesn't have
        // that line; our cursor classes get GLSM-redirected by default. Cursor thread then
        // races with main thread's ImmediateModeRecorder/PersistentStreamingBuffer (singletons)
        // and the first cross-thread glEnd corrupts state, crashing a few frames later in
        // CustomMainMenu's drawCompleteImage at the title screen.
        //
        // Fix: reach into RFB's registered transformer handles, find the redirector, and add
        // our prefix to its mutable exclusions list. We call this from multiple FML lifecycle
        // hooks because the empirically-observed timing (handle list empty at coremod
        // constructor) means we need a later attempt; the static CURSOR_EXCLUSION_APPLIED
        // guard ensures the work is done at most once.
        addCursorPackageToRedirectorExclusions("coremod-ctor");
    }

    public static synchronized void addCursorPackageToRedirectorExclusions(String hook) {
        if (CURSOR_EXCLUSION_APPLIED) return;
        try {
            final Class<?> mainClass = Class.forName("com.gtnewhorizons.retrofuturabootstrap.Main");
            final java.lang.reflect.Method getRfbTransformers = mainClass.getMethod("getRfbTransformers");
            final List<?> handles = (List<?>) getRfbTransformers.invoke(null);
            if (handles.isEmpty()) {
                LOGGER.info("[" + hook + "] RFB transformer handle list is empty; will retry at a later hook.");
                return;
            }
            // Diagnostic: print all handle IDs so we know what's actually registered.
            final StringBuilder ids = new StringBuilder();
            for (Object h : handles) {
                final String id = (String) h.getClass().getMethod("id").invoke(h);
                if (ids.length() > 0) ids.append(", ");
                ids.append(id);
            }
            LOGGER.info("[" + hook + "] RFB handles present: [" + ids + "]");
            for (Object handle : handles) {
                final String id = (String) handle.getClass().getMethod("id").invoke(handle);
                // Handle IDs are namespaced "<pluginId>:<transformerId>" — Angelica's GLSM
                // redirector is registered as "angelica:redirector".
                if (!"angelica:redirector".equals(id)) continue;
                @SuppressWarnings("unchecked")
                final List<String> exclusions = (List<String>) handle.getClass()
                    .getMethod("exclusions").invoke(handle);
                if (exclusions.contains("com.mitchellmarx.stereoscopic.cursor.")) {
                    LOGGER.info("[" + hook + "] Cursor-package exclusion already present.");
                    CURSOR_EXCLUSION_APPLIED = true;
                    return;
                }
                try {
                    exclusions.add("com.mitchellmarx.stereoscopic.cursor.");
                    LOGGER.info("[" + hook + "] Added com.mitchellmarx.stereoscopic.cursor. to GLSM redirector exclusions.");
                } catch (UnsupportedOperationException ue) {
                    final java.lang.reflect.Field exclusionsField =
                        handle.getClass().getDeclaredField("exclusions");
                    exclusionsField.setAccessible(true);
                    final List<String> replacement = new java.util.ArrayList<>(exclusions);
                    replacement.add("com.mitchellmarx.stereoscopic.cursor.");
                    exclusionsField.set(handle, replacement);
                    LOGGER.info("[" + hook + "] Replaced redirector exclusions list with mutable copy including our cursor prefix.");
                }
                CURSOR_EXCLUSION_APPLIED = true;
                return;
            }
            LOGGER.info("[" + hook + "] Redirector handle not found yet; will retry at a later hook.");
        } catch (Throwable t) {
            LOGGER.warn("[" + hook + "] Failed to add cursor-package exclusion to GLSM redirector.", t);
        }
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[] {
            "com.mitchellmarx.stereoscopic.transformer.IsbrhTessellatorAbuseClassTransformer"
        };
    }

    @Override
    public String getModContainerClass() { return null; }

    @Override
    public String getSetupClass() { return null; }

    @Override
    public void injectData(Map<String, Object> data) {
        final Object obf = data.get("runtimeDeobfuscationEnabled");
        if (obf instanceof Boolean) {
            OBF_ENV = (Boolean) obf;
        }
        addCursorPackageToRedirectorExclusions("injectData");
    }

    @Override
    public String getAccessTransformerClass() { return null; }
}
