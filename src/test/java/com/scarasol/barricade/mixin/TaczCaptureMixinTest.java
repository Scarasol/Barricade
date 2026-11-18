package com.scarasol.barricade.mixin;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.scarasol.barricade.mixin.plugin.MixinPlugin;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;

class TaczCaptureMixinTest {
    private static final String MIXIN = "com.scarasol.barricade.mixin.tacz.CaptureBufferMixin";
    private static final String TARGET = "com/tacz/guns/util/RenderHelper";

    @Test
    void taczHookIsClientOnlyAndTargetsTheExistingNativeBufferLookup() throws Exception {
        try (var input = new InputStreamReader(Objects.requireNonNull(
                getClass().getResourceAsStream("/barricade.mixins.json")), StandardCharsets.UTF_8)) {
            var config = JsonParser.parseReader(input).getAsJsonObject();
            assertTrue(config.getAsJsonArray("client").asList().stream()
                    .anyMatch(value -> value.getAsString().equals("tacz.CaptureBufferMixin")));
            assertTrue(config.getAsJsonArray("mixins").asList().stream()
                    .noneMatch(value -> value.getAsString().equals("tacz.CaptureBufferMixin")));
        }
        var target = method(bytecode(TARGET), "getBufferSource");
        assertEquals("(Lnet/minecraft/world/item/ItemDisplayContext;)"
                + "Lnet/minecraft/client/renderer/MultiBufferSource;", target.desc);
        assertNotEquals(0, target.access & Opcodes.ACC_STATIC);
        var mixin = bytecode(MIXIN.replace('.', '/'));
        assertTrue(mixin.invisibleAnnotations.stream()
                .anyMatch(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/Pseudo;")));
        var declaration = mixin.invisibleAnnotations.stream()
                .filter(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"))
                .findFirst().orElseThrow();
        assertEquals(List.of(TARGET.replace('/', '.')), value(declaration, "targets"));
        assertEquals(false, value(declaration, "remap"));
        var hook = method(mixin, "barricade$captureBuffer");
        var inject = hook.visibleAnnotations.stream()
                .filter(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;"))
                .findFirst().orElseThrow();
        assertEquals(List.of("getBufferSource"), value(inject, "method"));
        assertEquals(true, value(inject, "cancellable"));
        var at = (AnnotationNode) ((List<?>) value(inject, "at")).get(0);
        assertEquals("HEAD", value(at, "value"));
        assertTrue(calls(hook).stream().anyMatch(call -> call.owner.equals(
                "com/scarasol/barricade/client/CaptureBuffers") && call.name.equals("current")));
        assertTrue(calls(hook).stream().noneMatch(call -> call.owner.startsWith("com/tacz/")));
    }

    @Test
    void nativeTaczWorkbenchAndBedrockModelStillReachTheInterceptedLookup() throws Exception {
        assertTrue(bytecode("com/tacz/guns/client/renderer/block/GunSmithTableRenderer").methods.stream()
                .flatMap(method -> calls(method).stream())
                .anyMatch(call -> call.owner.equals("com/tacz/guns/client/model/bedrock/BedrockModel")
                        && call.name.equals("render")));
        var model = bytecode("com/tacz/guns/client/model/bedrock/BedrockModel");
        assertTrue(model.methods.stream().flatMap(method -> calls(method).stream())
                .anyMatch(call -> call.owner.equals(TARGET) && call.name.equals("getBufferSource")));
        var helper = bytecode(TARGET);
        for (var method : helper.methods) {
            if (!method.name.equals("endBatch")) continue;
            assertTrue(java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
                    .anyMatch(node -> node instanceof TypeInsnNode type && type.getOpcode() == Opcodes.INSTANCEOF
                            && type.desc.equals("net/minecraft/client/renderer/MultiBufferSource$BufferSource")));
        }
    }

    @Test
    void optionalTaczIsDetectedWithoutInitializationAndSkippedWhenAbsent() throws Exception {
        var installed = new MixinPlugin();
        installed.onLoad("com.scarasol.barricade.mixin");
        assertTrue(installed.shouldApplyMixin(TARGET.replace('/', '.'), MIXIN));
        var loader = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.tacz.") || name.startsWith("software.bernie.geckolib.")
                        || name.startsWith("com.github.mcmodderanchor.simplebedrockmodel."))
                    throw new ClassNotFoundException(name);
                if (!name.equals(MixinPlugin.class.getName())) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    var loaded = findLoadedClass(name);
                    if (loaded == null) {
                        try (var input = Objects.requireNonNull(getResourceAsStream(name.replace('.', '/') + ".class"))) {
                            byte[] bytes = input.readAllBytes();
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
        var absent = (IMixinConfigPlugin) loader.loadClass(MixinPlugin.class.getName()).getConstructor().newInstance();
        absent.onLoad("com.scarasol.barricade.mixin");
        assertFalse(absent.shouldApplyMixin(TARGET.replace('/', '.'), MIXIN));
        assertFalse(absent.shouldApplyMixin("anything", "com.scarasol.barricade.mixin.sbm.CaptureCullingMixin"));
        assertTrue(absent.shouldApplyMixin("anything", "com.scarasol.barricade.mixin.EmbeddedRenderMixin"));
        assertDoesNotThrow(() -> loader.loadClass("com.scarasol.barricade.client.CaptureBuffers")
                .getDeclaredMethods());
    }

    private static ClassNode bytecode(String name) throws IOException {
        try (var input = Objects.requireNonNull(TaczCaptureMixinTest.class.getResourceAsStream("/" + name + ".class"))) {
            var result = new ClassNode();
            new ClassReader(input).accept(result, 0);
            return result;
        }
    }
    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }
    private static List<MethodInsnNode> calls(MethodNode method) {
        var result = new ArrayList<MethodInsnNode>();
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call) result.add(call);
        return result;
    }
    private static Object value(AnnotationNode annotation, String key) {
        for (int index = 0; index < annotation.values.size(); index += 2)
            if (key.equals(annotation.values.get(index))) return annotation.values.get(index + 1);
        throw new AssertionError("Missing annotation value: " + key);
    }
}
