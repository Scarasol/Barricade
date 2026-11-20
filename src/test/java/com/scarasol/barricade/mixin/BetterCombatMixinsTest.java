package com.scarasol.barricade.mixin;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

class BetterCombatMixinsTest {
    @Test void absentBetterCombatLeavesTheNativeAttackHelperLoadable() {
        var loader = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.bettercombat.") || name.startsWith("dev.kosmx.")
                        || name.startsWith("me.shedaniel.")) throw new ClassNotFoundException(name);
                if (!name.startsWith("com.scarasol.barricade.window.WindowBoardsAttack"))
                    return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    var loaded = findLoadedClass(name);
                    if (loaded == null) {
                        try (var input = getResourceAsStream(name.replace('.', '/') + ".class")) {
                            byte[] bytes = Objects.requireNonNull(input).readAllBytes();
                            loaded = defineClass(name, bytes, 0, bytes.length);
                        } catch (IOException error) {
                            throw new ClassNotFoundException(name, error);
                        }
                    }
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
        };
        assertDoesNotThrow(() -> Class.forName("com.scarasol.barricade.window.WindowBoardsAttack", true, loader)
                .getDeclaredMethods());
    }

    @Test void compatibilityMixinsAreClientOnlyAndRegisteredBehindThePlugin() throws Exception {
        try (var reader = new InputStreamReader(Objects.requireNonNull(
                getClass().getResourceAsStream("/barricade.mixins.json")), StandardCharsets.UTF_8)) {
            var config = JsonParser.parseReader(reader).getAsJsonObject();
            assertEquals("com.scarasol.barricade.mixin.plugin.MixinPlugin", config.get("plugin").getAsString());
            var optional = Set.of("bettercombat.WindowBoardsBetterCombatAttackMixin",
                    "bettercombat.WindowBoardsRadialFilterMixin");
            assertTrue(config.getAsJsonArray("client").asList().stream()
                    .map(value -> value.getAsString()).collect(java.util.stream.Collectors.toSet()).containsAll(optional));
            assertTrue(config.getAsJsonArray("mixins").asList().stream()
                    .noneMatch(value -> optional.contains(value.getAsString())));
        }
        for (String name : List.of("WindowBoardsAttack", "WindowSealBlock")) {
            var node = bytecode("com/scarasol/barricade/window/" + name);
            for (var method : node.methods) for (var call : calls(method))
                assertFalse(call.owner.startsWith("net/bettercombat/")
                        || call.owner.startsWith("com/scarasol/barricade/compat/bettercombat/"));
        }
    }

    @Test void radialFilterRetainsNativeGeometryAndWrapsBothExistingObstacleChecksWithoutRedirects() throws Exception {
        var mixin = bytecode("com/scarasol/barricade/mixin/bettercombat/WindowBoardsRadialFilterMixin");
        var target = bytecode("net/bettercombat/client/collision/TargetFinder$RadialFilter");
        var constructor = method(mixin, "barricade$resolveAttackHand");
        var inject = annotation(constructor, "Lorg/spongepowered/asm/mixin/injection/Inject;");
        assertEquals(List.of("<init>"), value(inject, "method"));
        assertEquals("RETURN", value((AnnotationNode) ((List<?>) value(inject, "at")).get(0), "value"));
        assertEquals(1, calls(constructor).stream().filter(call -> call.name.equals("currentAttack")).count());
        var wrapper = method(mixin, "barricade$skipSeals");
        var wrap = annotation(wrapper, "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");
        assertEquals(List.of("lambda$filter$0"), value(wrap, "method"));
        assertEquals(2, value(wrap, "require"));
        assertEquals(2, value(wrap, "allow"));
        var at = (AnnotationNode) ((List<?>) value(wrap, "at")).get(0);
        String invocation = (String) value(at, "target");
        assertEquals(2, calls(method(target, "lambda$filter$0")).stream()
                .filter(call -> invocation.equals("L" + call.owner + ";" + call.name + call.desc)).count());
        assertTrue(calls(wrapper).stream().anyMatch(call -> call.name.equals("clip")
                && call.owner.equals("com/scarasol/barricade/window/WindowBoardsAttack")));
        assertTrue(calls(wrapper).stream().anyMatch(call -> call.name.equals("call")
                && call.owner.equals("com/llamalad7/mixinextras/injector/wrapoperation/Operation")));
        assertTrue(calls(wrapper).stream().noneMatch(call -> call.name.equals("currentAttack")
                || call.name.equals("getAttributes")), "Resolve permissions once per filter, not per entity/ray");
        assertNoDamageOrRedirect(mixin);
    }

    @Test void optionalInputHookReusesTheNativeScopedSelectionAndBetterCombatTargetFinder() throws Exception {
        var mixin = bytecode("com/scarasol/barricade/mixin/bettercombat/WindowBoardsBetterCombatAttackMixin");
        var inject = annotation(method(mixin, "barricade$betterCombatPick"),
                "Lorg/spongepowered/asm/mixin/injection/Inject;");
        assertEquals(List.of("pick"), value(inject, "method"));
        assertEquals(true, value(inject, "cancellable"));
        assertEquals("HEAD", value((AnnotationNode) ((List<?>) value(inject, "at")).get(0), "value"));
        var compat = bytecode("com/scarasol/barricade/compat/bettercombat/BetterCombatCompat");
        assertTrue(calls(method(compat, "pick")).stream().anyMatch(call ->
                call.owner.equals("net/bettercombat/client/collision/TargetFinder")
                        && call.name.equals("findAttackTargets")));
        assertTrue(calls(method(compat, "pick")).stream().noneMatch(call -> call.name.equals("getEntityReach")));
        assertNoDamageOrRedirect(mixin);
        assertNoDamageOrRedirect(compat);
    }

    private static void assertNoDamageOrRedirect(ClassNode node) {
        for (var method : node.methods) {
            if (method.visibleAnnotations != null)
                assertTrue(method.visibleAnnotations.stream()
                        .noneMatch(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;")));
            for (var call : calls(method)) {
                assertNotEquals("hurt", call.name);
                assertNotEquals("send", call.name);
                assertFalse(call.name.equals("attack") && !call.owner.equals("net/bettercombat/api/AttackHand"));
            }
        }
    }
    private static List<MethodInsnNode> calls(MethodNode method) {
        var result = new ArrayList<MethodInsnNode>();
        for (var node : method.instructions) if (node instanceof MethodInsnNode call) result.add(call);
        return result;
    }
    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }
    private static AnnotationNode annotation(MethodNode method, String descriptor) {
        return method.visibleAnnotations.stream().filter(value -> value.desc.equals(descriptor)).findFirst().orElseThrow();
    }
    private static Object value(AnnotationNode annotation, String key) {
        for (int index = 0; index < annotation.values.size(); index += 2)
            if (key.equals(annotation.values.get(index))) return annotation.values.get(index + 1);
        throw new AssertionError("Missing annotation value: " + key);
    }
    private static ClassNode bytecode(String name) throws IOException {
        try (var input = Objects.requireNonNull(BetterCombatMixinsTest.class.getResourceAsStream("/" + name + ".class"))) {
            var result = new ClassNode();
            new ClassReader(input).accept(result, 0);
            return result;
        }
    }
}
