package com.scarasol.barricade.mixin;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

class WindowBoardsClientHooksTest {
    @Test void visibilityHooksAreClientOnlyTailInjectionsIntoExistingNativeMethods() throws Exception {
        try (var reader = new InputStreamReader(Objects.requireNonNull(
                getClass().getResourceAsStream("/barricade.mixins.json")), StandardCharsets.UTF_8)) {
            var config = JsonParser.parseReader(reader).getAsJsonObject();
            for (String name : List.of("WindowBoardsClientLevelMixin", "WindowBoardsClientChunksMixin")) {
                assertTrue(config.getAsJsonArray("client").asList().stream().anyMatch(value -> name.equals(value.getAsString())));
                assertFalse(config.getAsJsonArray("mixins").asList().stream().anyMatch(value -> name.equals(value.getAsString())));
            }
        }
        verifyHooks("WindowBoardsClientLevelMixin", "net/minecraft/client/multiplayer/ClientLevel",
                Map.of("setBlocksDirty", "blockChanged", "onChunkLoaded", "chunkChanged", "unload", "chunkChanged"));
        verifyHooks("WindowBoardsClientChunksMixin", "net/minecraft/client/multiplayer/ClientChunkCache",
                Map.of("updateViewCenter", "chunksChanged", "updateViewRadius", "chunksChanged"));
    }
    @Test void normalBlockChangesAndFullChunkPacketsReachTheObservedNativeEntrypoints() throws Exception {
        assertTrue(calls(readClass("net/minecraft/world/level/Level"), "setBlocksDirty"));
        assertTrue(calls(readClass("net/minecraft/client/multiplayer/ClientChunkCache"), "onChunkLoaded"));
        assertTrue(calls(readClass("net/minecraft/client/multiplayer/ClientChunkCache$Storage"), "unload"));
    }
    private static void verifyHooks(String name, String target, Map<String, String> expected) throws Exception {
        ClassNode mixin = readClass("com/scarasol/barricade/mixin/" + name);
        ClassNode nativeClass = readClass(target);
        Set<String> found = new HashSet<>();
        for (MethodNode handler : mixin.methods) {
            if (handler.visibleAnnotations == null) continue;
            for (AnnotationNode annotation : handler.visibleAnnotations) {
                assertNotEquals("Lorg/spongepowered/asm/mixin/injection/Redirect;", annotation.desc);
                if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
                @SuppressWarnings("unchecked") var methods = (List<String>) value(annotation, "method");
                @SuppressWarnings("unchecked") var points = (List<AnnotationNode>) value(annotation, "at");
                assertEquals("TAIL", value(points.get(0), "value"));
                for (String method : methods) {
                    assertTrue(found.add(method));
                    MethodNode nativeMethod = nativeClass.methods.stream()
                            .filter(candidate -> candidate.name.equals(method)).findFirst().orElseThrow();
                    Type[] arguments = Type.getArgumentTypes(handler.desc);
                    assertEquals("org/spongepowered/asm/mixin/injection/callback/CallbackInfo",
                            arguments[arguments.length - 1].getInternalName());
                    if (arguments.length > 1) assertArrayEquals(Type.getArgumentTypes(nativeMethod.desc),
                            Arrays.copyOf(arguments, arguments.length - 1));
                    boolean notified = false;
                    for (var instruction : handler.instructions)
                        if (instruction instanceof MethodInsnNode call && call.owner.equals(
                                "com/scarasol/barricade/window/client/WindowBoardsClient")
                                && call.name.equals(expected.get(method))) notified = true;
                    assertTrue(notified, "Missing visibility notification for " + method);
                }
            }
        }
        assertEquals(expected.keySet(), found);
    }
    private static boolean calls(ClassNode type, String name) {
        for (MethodNode method : type.methods) for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.name.equals(name)) return true;
        return false;
    }
    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2)
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        throw new AssertionError("Missing annotation value: " + key);
    }
    private static ClassNode readClass(String name) throws Exception {
        var type = new ClassNode();
        try (var input = Objects.requireNonNull(WindowBoardsClientHooksTest.class.getResourceAsStream("/" + name + ".class"))) {
            new ClassReader(input).accept(type, 0);
        }
        return type;
    }
}
