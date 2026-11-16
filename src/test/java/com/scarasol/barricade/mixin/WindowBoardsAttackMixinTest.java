package com.scarasol.barricade.mixin;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

class WindowBoardsAttackMixinTest {
    @Test void wrapsOnlyTheTwoNativeLeftClickCallsAndNeverRedirectsOrReplacesTheDamagePath() throws Exception {
        var mixin = bytecode("com/scarasol/barricade/mixin/WindowBoardsAttackMixin");
        var minecraft = bytecode("net/minecraft/client/Minecraft");
        var input = minecraft.methods.stream().filter(method -> method.name.equals("handleKeybinds")).findFirst().orElseThrow();
        Set<String> found = new HashSet<>();
        for (var method : mixin.methods) {
            if (method.visibleAnnotations == null) continue;
            for (var annotation : method.visibleAnnotations) {
                assertNotEquals("Lorg/spongepowered/asm/mixin/injection/Redirect;", annotation.desc);
                if (!annotation.desc.equals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;")) continue;
                assertEquals(List.of("handleKeybinds()V"), value(annotation, "method"));
                assertEquals(1, value(annotation, "require"));
                assertEquals(1, value(annotation, "allow"));
                var at = (AnnotationNode) ((List<?>) value(annotation, "at")).get(0);
                assertEquals("INVOKE", value(at, "value"));
                String target = (String) value(at, "target");
                assertTrue(found.add(target));
                int matches = 0, restores = 0, originalCalls = 0;
                boolean directCall = false;
                for (var instruction : input.instructions)
                    if (instruction instanceof MethodInsnNode call
                            && target.equals("L" + call.owner + ";" + call.name + call.desc)) matches++;
                assertEquals(1, matches);
                for (var instruction : method.instructions) {
                    if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                            && field.owner.equals("net/minecraft/client/Minecraft") && field.name.equals("hitResult")) restores++;
                    if (instruction instanceof MethodInsnNode call) {
                        if (call.owner.equals("com/llamalad7/mixinextras/injector/wrapoperation/Operation")
                                && call.name.equals("call")) {
                            originalCalls++;
                            if (restores == 0) directCall = true;
                        }
                        assertNotEquals("attack", call.name, "Damage must still be dispatched by native input processing");
                        assertNotEquals("hurt", call.name);
                    }
                }
                assertEquals(3, restores, "Scoped targeting must restore hitResult on both return and exception");
                assertTrue(directCall, "Unchanged attacks must return to native input without writing hitResult");
                assertTrue(method.tryCatchBlocks.stream().anyMatch(block -> block.type == null));
                assertTrue(originalCalls >= 1);
            }
        }
        assertEquals(Set.of("Lnet/minecraft/client/Minecraft;startAttack()Z",
                "Lnet/minecraft/client/Minecraft;continueAttack(Z)V"), found);
    }

    @Test void mixinIsClientOnlyAndSharedRayCodeNeverReferencesMinecraftClientClasses() throws Exception {
        try (var reader = new InputStreamReader(Objects.requireNonNull(
                getClass().getResourceAsStream("/barricade.mixins.json")), StandardCharsets.UTF_8)) {
            var config = JsonParser.parseReader(reader).getAsJsonObject();
            assertTrue(config.getAsJsonArray("client").asList().stream()
                    .anyMatch(value -> value.getAsString().equals("WindowBoardsAttackMixin")));
            assertFalse(config.getAsJsonArray("mixins").asList().stream()
                    .anyMatch(value -> value.getAsString().equals("WindowBoardsAttackMixin")));
        }
        var attack = bytecode("com/scarasol/barricade/window/WindowBoardsAttack");
        for (var method : attack.methods) for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call) assertFalse(call.owner.startsWith("net/minecraft/client/"));
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int index = 0; index < annotation.values.size(); index += 2)
            if (key.equals(annotation.values.get(index))) return annotation.values.get(index + 1);
        throw new AssertionError("Missing annotation value: " + key);
    }
    private static ClassNode bytecode(String name) throws Exception {
        try (var input = Objects.requireNonNull(WindowBoardsAttackMixinTest.class.getResourceAsStream("/" + name + ".class"))) {
            var result = new ClassNode();
            new ClassReader(input).accept(result, 0);
            return result;
        }
    }
}
