package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.scarasol.barricade.sampling.MemoryFixture;
import com.scarasol.barricade.recipe.EmbeddedBlock;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.fml.ModList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;

class SbmCaptureTest {
    private static final String PREFIX = "com.github.mcmodderanchor.simplebedrockmodel.";
    private static final String TARGET = PREFIX + "v2.common.model.baked.BakedGeometryChunkRenderer";
    private static final String MIXIN = "com.scarasol.barricade.mixin.sbm.CaptureCullingMixin";
    @TempDir Path directory;

    @Test
    void cullingHookMatchesTheBundledSbmAndIsOnlyRegisteredOnTheClient() throws Exception {
        try (var loader = BundledSbmFixture.open(directory);
             var reader = new InputStreamReader(Objects.requireNonNull(
                     getClass().getResourceAsStream("/barricade.mixins.json")), StandardCharsets.UTF_8)) {
            var config = JsonParser.parseReader(reader).getAsJsonObject();
            assertTrue(config.getAsJsonArray("client").asList().stream()
                    .anyMatch(value -> value.getAsString().equals("sbm.CaptureCullingMixin")));
            assertTrue(config.getAsJsonArray("mixins").asList().stream()
                    .noneMatch(value -> value.getAsString().equals("sbm.CaptureCullingMixin")));
            var mixin = bytecode(getClass().getClassLoader(), MIXIN);
            var declaration = mixin.invisibleAnnotations.stream()
                    .filter(value -> value.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"))
                    .findFirst().orElseThrow();
            assertEquals(List.of(TARGET), value(declaration, "targets"));
            assertEquals(false, value(declaration, "remap"));
            var hook = mixin.methods.stream().filter(value -> value.name.equals("barricade$captureAllFaces"))
                    .findFirst().orElseThrow();
            var modify = hook.visibleAnnotations.stream()
                    .filter(value -> value.desc.equals("Lorg/spongepowered/asm/mixin/injection/ModifyVariable;"))
                    .findFirst().orElseThrow();
            assertEquals(true, value(modify, "argsOnly"));
            assertEquals(0, value(modify, "ordinal"));
            var at = (AnnotationNode) value(modify, "at");
            assertEquals("HEAD", value(at, "value"));
            var target = bytecode(loader, TARGET);
            var signatures = (List<?>) value(modify, "method");
            assertEquals(1, target.methods.stream().filter(method -> signatures.contains(method.name + method.desc))
                    .count());
        }
    }

    @Test
    void theBundledSbmModelCapturesAllFacesRatherThanOnlyTheCurrentViewsFaces() throws Exception {
        MemoryFixture.create(true).reset();
        if (!RenderSystem.isOnRenderThread()) RenderSystem.initRenderThread();
        var previous = ModList.get();
        var instanceField = ModList.class.getDeclaredField("INSTANCE");
        instanceField.setAccessible(true);
        try (var loader = BundledSbmFixture.open(directory)) {
            // Only the headless mod registry is supplied; geometry and rendering are the real library.
            var headless = ModList.of(List.of(), List.of());
            var loadedMods = ModList.class.getDeclaredMethod("setLoadedMods", List.class);
            loadedMods.setAccessible(true);
            loadedMods.invoke(headless, List.of());
            var pojoType = loader.loadClass(PREFIX + "v1.common.resource.pojo.BedrockModelPOJO");
            var gson = (Gson) loader.loadClass(PREFIX + "v1.common.resource.GsonUtil").getField("CLIENT_GSON").get(null);
            Object pojo;
            try (var input = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
                    "/assets/superbwarfare/models/bedrock/block/fumo_25.geo.json")), StandardCharsets.UTF_8)) {
                pojo = gson.fromJson(input, pojoType);
            }
            var bakedType = loader.loadClass(PREFIX + "v2.common.model.baked.BakedBedrockModel");
            Object baked = bakedType.getMethod("bake", pojoType).invoke(null, pojo);
            Object chunks = bakedType.getMethod("chunks").invoke(baked);
            int expected = 0;
            for (int index = 0; index < Array.getLength(chunks); index++) {
                Object chunk = Array.get(chunks, index);
                expected += (int) chunk.getClass().getMethod("quadCount").invoke(chunk) * 4;
            }
            assertTrue(expected > 0);
            Object model = bakedType.getMethod("createInstance").invoke(baked);
            var render = model.getClass().getMethod("renderToBuffer", PoseStack.class, VertexConsumer.class,
                    int.class, int.class, float.class, float.class, float.class, float.class, boolean.class);
            var hookType = Class.forName(MIXIN);
            var hook = hookType.getDeclaredMethod("barricade$captureAllFaces", boolean.class);
            hook.setAccessible(true);
            var hookInstance = new com.scarasol.barricade.mixin.sbm.CaptureCullingMixin() { };
            assertEquals(false, hook.invoke(hookInstance, false));
            assertEquals(true, hook.invoke(hookInstance, true));
            RenderType material = RenderType.entityCutout(new ResourceLocation("barricade", "sbm-capture.png"));
            var world = MemoryFixture.create(true);
            BlockPos pos = new BlockPos(-17, -60, -1);
            world.context(Map.of(pos, Blocks.CHEST.defaultBlockState()), Map.of());
            var embedded = new EmbeddedBlock(pos, 7, 1);
            var appearance = embedded.model(world);
            try (StaticMesh mesh = NativeModelCaptureTest.capture(appearance.parts(),
                    (entity, tick, pose, buffers, light, overlay) ->
                    assertDoesNotThrow(() -> render.invoke(model, pose, buffers.getBuffer(material), light, overlay,
                            1f, 1f, 1f, 1f, hook.invoke(hookInstance, false))))) {
                assertEquals(expected, mesh.batches.stream().mapToInt(batch -> batch.vertices.size()).sum());
                assertSame(material, mesh.batches.get(0).material);
                assertTrue(mesh.bounds.getSize() > 0);
                assertTrue(BarricadeRenderer.transformed(pos, embedded.transform(appearance), mesh.bounds).getSize() > 0);
                assertEquals(0, world.chunkRequests());
            }
            int culled = 0;
            try (StaticMesh mesh = NativeModelCaptureTest.capture((entity, tick, pose, buffers, light, overlay) ->
                    assertDoesNotThrow(() -> render.invoke(model, pose, buffers.getBuffer(material), light, overlay,
                            1f, 1f, 1f, 1f, false)))) {
                culled = mesh.batches.stream().mapToInt(batch -> batch.vertices.size()).sum();
            } catch (UnsupportedOperationException error) {
                assertEquals("Empty appearance", error.getMessage());
            }
            assertTrue(culled < expected, "The native view culling must reproduce the missing-face failure");
            System.out.printf("Bundled SBM model: %d full vertices, %d view-culled vertices%n", expected, culled);
            assertEquals(false, hook.invoke(hookInstance, false));
        } finally {
            instanceField.set(null, previous);
        }
    }

    @Test
    void pluginDetectsBundledSbmWithoutInitializingIt() throws Exception {
        try (var sbm = BundledSbmFixture.open(directory)) {
            var loader = new ClassLoader(sbm) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (!name.equals("com.scarasol.barricade.mixin.plugin.MixinPlugin"))
                        return super.loadClass(name, resolve);
                    synchronized (getClassLoadingLock(name)) {
                        var loaded = findLoadedClass(name);
                        if (loaded == null) {
                            try (var input = Objects.requireNonNull(getResourceAsStream(name.replace('.', '/') + ".class"))) {
                                byte[] bytes = input.readAllBytes();
                                loaded = defineClass(name, bytes, 0, bytes.length);
                            } catch (java.io.IOException error) {
                                throw new ClassNotFoundException(name, error);
                            }
                        }
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                }
            };
            var plugin = (IMixinConfigPlugin) loader.loadClass("com.scarasol.barricade.mixin.plugin.MixinPlugin")
                    .getConstructor().newInstance();
            plugin.onLoad("com.scarasol.barricade.mixin");
            assertTrue(plugin.shouldApplyMixin(TARGET, MIXIN));
        }
    }

    private static ClassNode bytecode(ClassLoader loader, String name) throws Exception {
        try (var input = Objects.requireNonNull(loader.getResourceAsStream(name.replace('.', '/') + ".class"))) {
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
    private static Object value(AnnotationNode annotation, String key) {
        for (int index = 0; index < annotation.values.size(); index += 2)
            if (key.equals(annotation.values.get(index))) return annotation.values.get(index + 1);
        throw new AssertionError("Missing annotation value: " + key);
    }
}
