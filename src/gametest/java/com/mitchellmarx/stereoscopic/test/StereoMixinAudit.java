package com.mitchellmarx.stereoscopic.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.service.MixinService;
import org.spongepowered.asm.util.Annotations;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Verifies every injector in the mod's mixin configs actually injected. The
 * Iris config uses {@code defaultRequire: 0}, so a broken injection there
 * fails silently (and one bad injection drops the whole mixin class).
 *
 * <p>Needs {@code -Dmixin.debug.export=true}: Mixin then writes every
 * transformed class to {@code .mixin.out/class}. For each mixin, each target is
 * force-loaded, its exported bytecode is read, and every injector handler of
 * the mixin must be (a) merged into the target and (b) invoked from at least
 * one method there — a merged-but-never-called handler is an injection that
 * found no target. Accessor/invoker interfaces: the target must implement them.
 */
final class StereoMixinAudit {

    private static final Set<String> INJECTORS = Set.of(
        "Lorg/spongepowered/asm/mixin/injection/Inject;",
        "Lorg/spongepowered/asm/mixin/injection/Redirect;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
        "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
        "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
        "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
        "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
        "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
        "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");

    /** Handlers / accessors verified by the last {@link #audit} call. */
    static int verified;

    private StereoMixinAudit() {}

    /** @return human-readable failures (empty = every injector injected). */
    static List<String> audit(Path gameDir, String... configResources) {
        List<String> failures = new ArrayList<>();
        verified = 0;
        Path out = gameDir.resolve(".mixin.out").resolve("class");
        if (!Files.isDirectory(out)) {
            failures.add("no " + out + " — run with -Dmixin.debug.export=true");
            return failures;
        }
        for (String res : configResources) {
            JsonObject cfg = readJson(res);
            String pkg = cfg.get("package").getAsString();
            List<String> names = new ArrayList<>();
            for (String key : new String[] {"mixins", "client"}) {
                JsonElement arr = cfg.get(key);
                if (arr instanceof JsonArray a) a.forEach(e -> names.add(e.getAsString()));
            }
            for (String n : names) {
                String mixin = pkg + "." + n;
                ClassNode mixinNode = node(mixin);
                if (mixinNode == null) {
                    failures.add(mixin + ": mixin class bytes not found");
                    continue;
                }
                List<String> handlers = new ArrayList<>();
                for (MethodNode m : mixinNode.methods) {
                    if (m.visibleAnnotations == null) continue;
                    for (AnnotationNode a : m.visibleAnnotations) {
                        if (INJECTORS.contains(a.desc)) handlers.add(m.name);
                    }
                }
                boolean isInterface = (mixinNode.access & Opcodes.ACC_INTERFACE) != 0;
                for (String target : targetsOf(mixinNode)) {
                    try {
                        Class.forName(target, true, StereoMixinAudit.class.getClassLoader());
                    } catch (Throwable t) {
                        failures.add(mixin + ": target " + target + " failed to load (" + t + ")");
                        continue;
                    }
                    Path cls = out.resolve(target.replace('.', '/') + ".class");
                    ClassNode tn = readClass(cls);
                    if (tn == null) {
                        failures.add(mixin + ": no exported bytecode for " + target + " (mixin not applied?)");
                        continue;
                    }
                    if (isInterface) {
                        if (tn.interfaces == null || !tn.interfaces.contains(mixin.replace('.', '/'))) {
                            failures.add(mixin + " (accessor) not implemented by " + target);
                        }
                        verified++;
                        continue;
                    }
                    for (String h : handlers) {
                        String merged = tn.methods.stream().map(m -> m.name)
                            .filter(nm -> nm.equals(h) || nm.endsWith("$" + h)).findFirst().orElse(null);
                        if (merged == null) {
                            failures.add(mixin + "." + h + " not merged into " + target);
                        } else if (!invoked(tn, merged)) {
                            failures.add(mixin + "." + h + " merged into " + target + " but never invoked (injection found no target)");
                        }
                        verified++;
                    }
                }
            }
        }
        return failures;
    }

    private static boolean invoked(ClassNode cls, String method) {
        for (MethodNode m : cls.methods) {
            if (m.name.equals(method)) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode mi && mi.name.equals(method) && mi.owner.equals(cls.name)) return true;
            }
        }
        return false;
    }

    private static List<String> targetsOf(ClassNode node) {
        List<String> out = new ArrayList<>();
        AnnotationNode ann = Annotations.getInvisible(node, Mixin.class);
        if (ann == null) ann = Annotations.getVisible(node, Mixin.class);
        if (ann == null) return out;
        List<Type> types = Annotations.getValue(ann, "value");
        if (types != null) types.forEach(t -> out.add(t.getClassName()));
        List<String> strs = Annotations.getValue(ann, "targets");
        if (strs != null) strs.forEach(s -> out.add(s.replace('/', '.')));
        return out;
    }

    private static ClassNode node(String cls) {
        try {
            return MixinService.getService().getBytecodeProvider().getClassNode(cls.replace('.', '/'));
        } catch (Exception e) {
            return null;
        }
    }

    private static ClassNode readClass(Path p) {
        if (!Files.isRegularFile(p)) return null;
        try {
            ClassNode n = new ClassNode();
            new ClassReader(Files.readAllBytes(p)).accept(n, 0);
            return n;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static JsonObject readJson(String res) {
        try (InputStream in = StereoMixinAudit.class.getClassLoader().getResourceAsStream(res)) {
            if (in == null) throw new AssertionError("mixin config not found: " + res);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + res, e);
        }
    }
}
