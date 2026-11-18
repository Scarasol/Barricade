package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL46.*;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.github.argon4w.acceleratedrendering.configs.FeatureConfig;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.AcceleratedRingBuffers;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.AcceleratedBufferBuilder;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.draw.IDrawMethod;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.draw.basevertex.BaseVertexDrawMethod;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.layers.LayerKey;
import com.github.argon4w.acceleratedrendering.core.buffers.environments.IBufferEnvironment;
import com.github.argon4w.acceleratedrendering.core.buffers.memory.VertexLayout;
import com.github.argon4w.acceleratedrendering.core.meshes.ServerMesh;
import com.github.argon4w.acceleratedrendering.core.programs.culling.ICullingProgramDispatcher;
import com.github.argon4w.acceleratedrendering.core.programs.dispatchers.IPolygonProgramDispatcher;
import com.github.argon4w.acceleratedrendering.core.programs.dispatchers.TransformProgramDispatcher;
import com.github.argon4w.acceleratedrendering.core.programs.dispatchers.meshes.MeshUploadingProgramDispatcher;
import com.github.argon4w.acceleratedrendering.core.programs.overrides.ITransformOverride;
import com.github.argon4w.acceleratedrendering.core.programs.overrides.IUploadingOverride;
import com.github.argon4w.acceleratedrendering.core.programs.overrides.ProgramOverride;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.scarasol.barricade.pile.CollisionBox;
import com.scarasol.barricade.pile.PileModel;
import com.scarasol.barricade.recipe.EmbeddedBlock;
import java.nio.charset.StandardCharsets;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

/** Explicit GPU probe; runs real AR upload dispatchers without launching Minecraft. */
@EnabledIfEnvironmentVariable(named = "BARRICADE_GPU_TESTS", matches = "1")
class AcceleratedRenderingGpuTest {
    private static long window;

    @BeforeAll
    static void context() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        assertTrue(glfwInit());
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(32, 32, "Barricade GPU test", 0, 0);
        assertNotEquals(0, window);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        System.out.println("Offscreen GPU: " + glGetString(GL_RENDERER));
        var config = CommentedConfig.inMemory();
        FeatureConfig.SPEC.correct(config);
        FeatureConfig.SPEC.setConfig(config);
    }

    @AfterAll
    static void closeContext() {
        if (window != 0) {
            GL.setCapabilities(null);
            glfwDestroyWindow(window);
        }
        glfwTerminate();
    }

    @Test
    void multipleOwnedMeshBuffersPreserveEveryTransformedVertexOnTheGpu() throws Exception {
        for (int instances : new int[]{1, 3, 65}) {
            runUploads(instances, false);
        }
    }

    @Test
    void ownedMeshesCanShareThePipelineWithDirectVerticesAndAnExternalSparseMesh() throws Exception {
        runUploads(3, true);
    }

    @Test
    void entityAndBlockLayoutsPreserveColorTextureOverlayLightAndNormalsOnTheGpu() throws Exception {
        for (boolean block : new boolean[]{false, true}) {
            try (var environment = new Environment(block)) {
                var buffers = new AcceleratedRingBuffers.Buffers(environment);
                StaticMesh.Acceleration acceleration = null;
                try {
                    RenderType material = block ? RenderType.cutout()
                            : RenderType.entityCutout(new ResourceLocation("barricade", "gpu-test.png"));
                    var builder = new AcceleratedBufferBuilder(buffers.getVertexBuffer(), buffers.getVaryingBuffer(),
                            buffers.getElementSegment(), buffers, null, new LayerKey(0, material));
                    List<StaticMesh.Vertex> vertices = new ArrayList<>();
                    for (int index = 0; index < 4; index++)
                        vertices.add(new StaticMesh.Vertex(index, 0.5f, 0, 31 + index, 79, 163, 211,
                                0.125f * index, 0.625f, 48 | 160 << 16, 64, 0, 1, 0));
                    acceleration = AcceleratedRenderingBridge.create(
                            new StaticMesh.Batch(material, vertices, new Vector3f()), builder);
                    assertNotNull(acceleration);
                    PoseStack pose = new PoseStack();
                    pose.mulPose(new Quaternionf().rotationX((float) Math.PI / 2));
                    assertTrue(acceleration.draw(builder, pose.last(), 32 | 208 << 16));
                    environment.uploads.dispatch(List.of(builder), buffers);
                    environment.transforms.dispatch(List.of(builder));
                    glMemoryBarrier(GL_ALL_BARRIER_BITS);
                    glFinish();
                    assertEquals(GL_NO_ERROR, glGetError());
                    buffers.bindTransformBuffers();
                    int output = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 1);
                    int stride = environment.getVertexSize();
                    var bytes = MemoryUtil.memAlloc(stride * 4).order(ByteOrder.nativeOrder());
                    try {
                        glGetNamedBufferSubData(output, builder.getVertexCountOffset() * stride, bytes);
                        for (int index = 0; index < 4; index++) {
                            int offset = stride * index;
                            int source = Math.round(bytes.getFloat(offset));
                            assertTrue(source >= 0 && source < 4);
                            assertEquals(0, bytes.getFloat(offset + 4), 0.000001f);
                            assertEquals(0.5f, bytes.getFloat(offset + 8), 0.000001f);
                            assertEquals(31 + source, bytes.get(offset + 12) & 255);
                            assertEquals(79, bytes.get(offset + 13) & 255);
                            assertEquals(163, bytes.get(offset + 14) & 255);
                            assertEquals(211, bytes.get(offset + 15) & 255);
                            assertEquals(0.125f * source, bytes.getFloat(offset + 16));
                            assertEquals(0.625f, bytes.getFloat(offset + 20));
                            int lightOffset = block ? 24 : 28;
                            if (!block) assertEquals(48 | 160 << 16, bytes.getInt(offset + 24));
                            assertEquals(64 | 208 << 16, bytes.getInt(offset + lightOffset));
                            assertEquals(0, bytes.get(offset + lightOffset + 4));
                            assertEquals(0, bytes.get(offset + lightOffset + 5));
                            assertEquals(127, bytes.get(offset + lightOffset + 6));
                        }
                    } finally {
                        MemoryUtil.memFree(bytes);
                    }
                } finally {
                    glFinish();
                    buffers.delete();
                    if (acceleration != null) acceleration.close();
                }
            }
        }
    }

    @Test
    void animatedInstancesReuseOneMeshWithDistinctPoseTintLightAndOverlayOnTheGpu() throws Exception {
        try (var environment = new Environment()) {
            var buffers = new AcceleratedRingBuffers.Buffers(environment);
            StaticMesh.Acceleration acceleration = null;
            try {
                RenderType material = RenderType.entityCutout(new ResourceLocation("barricade", "animated-test.png"));
                var builder = new AcceleratedBufferBuilder(buffers.getVertexBuffer(), buffers.getVaryingBuffer(),
                        buffers.getElementSegment(), buffers, null, new LayerKey(0, material));
                List<StaticMesh.Vertex> vertices = new ArrayList<>();
                for (int index = 0; index < 4; index++)
                    vertices.add(new StaticMesh.Vertex(index, .5f, 0, 255, 255, 255, 255,
                            .125f * index, .625f, 0, 0, 0, 1, 0));
                acceleration = AcceleratedRenderingBridge.create(
                        new StaticMesh.Batch(material, vertices, new Vector3f()), builder);
                assertNotNull(acceleration);
                int[] colors = {0x7F4080FF, 0xC0FF2040}, lights = {64 | 208 << 16, 112 | 144 << 16};
                int[] overlays = {3 | 7 << 16, 11 | 4 << 16};
                for (int instance = 0; instance < 2; instance++) {
                    var pose = new PoseStack();
                    pose.translate(instance * 4, instance, -2);
                    pose.mulPose(new Quaternionf().rotationX((float) Math.PI / 2));
                    assertTrue(acceleration.draw(builder, pose.last(), lights[instance],
                            colors[instance], overlays[instance]));
                }
                assertEquals(1, builder.getMeshUploaders().size());
                environment.uploads.dispatch(List.of(builder), buffers);
                environment.transforms.dispatch(List.of(builder));
                glMemoryBarrier(GL_ALL_BARRIER_BITS);
                glFinish();
                assertEquals(GL_NO_ERROR, glGetError());
                buffers.bindTransformBuffers();
                int output = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 1), stride = environment.getVertexSize();
                var bytes = MemoryUtil.memAlloc(stride * 8).order(ByteOrder.nativeOrder());
                try {
                    glGetNamedBufferSubData(output, builder.getVertexCountOffset() * stride, bytes);
                    for (int vertex = 0; vertex < 8; vertex++) {
                        int offset = vertex * stride, instance = Math.round(bytes.getFloat(offset + 4));
                        assertTrue(instance == 0 || instance == 1);
                        int index = Math.round(bytes.getFloat(offset)) - instance * 4;
                        assertTrue(index >= 0 && index < 4);
                        assertEquals(-1.5f, bytes.getFloat(offset + 8), .000001f);
                        int color = colors[instance];
                        assertEquals(color >>> 16 & 255, bytes.get(offset + 12) & 255);
                        assertEquals(color >>> 8 & 255, bytes.get(offset + 13) & 255);
                        assertEquals(color & 255, bytes.get(offset + 14) & 255);
                        assertEquals(color >>> 24, bytes.get(offset + 15) & 255);
                        assertEquals(.125f * index, bytes.getFloat(offset + 16));
                        assertEquals(overlays[instance], bytes.getInt(offset + 24));
                        assertEquals(lights[instance], bytes.getInt(offset + 28));
                        assertEquals(0, bytes.get(offset + 32));
                        assertEquals(0, bytes.get(offset + 33));
                        assertEquals(127, bytes.get(offset + 34));
                    }
                } finally { MemoryUtil.memFree(bytes); }
            } finally {
                glFinish();
                buffers.delete();
                if (acceleration != null) acceleration.close();
            }
        }
    }

    private static void runUploads(int instances, boolean mixed) throws Exception {
        try (var environment = new Environment()) {
            var buffers = new AcceleratedRingBuffers.Buffers(environment);
            List<StaticMesh.Acceleration> meshes = new ArrayList<>();
            try {
                RenderType material = RenderType.entityCutout(new ResourceLocation("barricade", "gpu-test.png"));
                var builder = new AcceleratedBufferBuilder(buffers.getVertexBuffer(), buffers.getVaryingBuffer(),
                        buffers.getElementSegment(), buffers, null, new LayerKey(0, material));
                List<Vector3f> expected = new ArrayList<>();
                List<StaticMesh.Vertex> firstVertices = List.of();
                PileModel poseModel = new PileModel("pose", "minecraft:stone", 1, 1, 1, 1, List.of(CollisionBox.CUBE));
                for (int count : new int[]{4, 24, 32, 72, 144}) {
                    List<StaticMesh.Vertex> vertices = new ArrayList<>();
                    for (int index = 0; index < count; index++) {
                        vertices.add(new StaticMesh.Vertex(index % 7 * 0.125f, index % 11 * 0.0625f,
                                index % 13 * -0.03125f, 255, 255, 255, 255, 0.25f, 0.75f,
                                0, 0, 0, 1, 0));
                    }
                    if (count == 4) firstVertices = vertices;
                    var batch = new StaticMesh.Batch(material, vertices, new Vector3f());
                    var acceleration = AcceleratedRenderingBridge.create(batch, builder);
                    assertNotNull(acceleration);
                    meshes.add(acceleration);
                    for (int instance = 0; instance < instances; instance++) {
                        PoseStack pose = new PoseStack();
                        pose.translate(meshes.size() * 3, instance * 2, -6);
                        pose.mulPose(new EmbeddedBlock(new BlockPos(meshes.size(), instance, 0), instance + 1, 1)
                                .transform(poseModel).rotation());
                        pose.scale(0.95f, 0.95f, 0.95f);
                        assertTrue(acceleration.draw(builder, pose.last(), 240 | 240 << 16));
                        vertices.forEach(vertex -> expected.add(pose.last().pose().transformPosition(
                                new Vector3f(vertex.x(), vertex.y(), vertex.z()))));
                    }
                }
                assertTrue(builder.getMeshUploaders().values().stream()
                        .allMatch(uploader -> uploader.getServerMesh().forceDense()));
                if (mixed) {
                    PoseStack directPose = new PoseStack();
                    directPose.translate(-5, 2, 1);
                    for (var vertex : firstVertices) {
                        vertex.write(builder, directPose.last(), 240);
                        expected.add(directPose.last().pose().transformPosition(
                                new Vector3f(vertex.x(), vertex.y(), vertex.z())));
                    }
                    // Model a separate AR user retaining the ordinary sparse upload choice.
                    ServerMesh original = builder.getMeshUploaders().values().stream()
                            .map(uploader -> uploader.getServerMesh())
                            .filter(mesh -> mesh.size() == 4).findFirst().orElseThrow();
                    ServerMesh external = new ServerMesh(original.meshLayer(), ServerMesh.Builder.COUNTER++,
                            original.size(), original.offset(), false, original.meshBuffer());
                    PoseStack sparsePose = new PoseStack();
                    sparsePose.translate(-3, 4, 2);
                    builder.beginTransform(sparsePose.last().pose(), sparsePose.last().normal());
                    try {
                        external.write(builder, -1, 240, 0);
                    } finally {
                        builder.endTransform();
                    }
                    for (var vertex : firstVertices) {
                        expected.add(sparsePose.last().pose().transformPosition(
                                new Vector3f(vertex.x(), vertex.y(), vertex.z())));
                    }
                }
                environment.uploads.dispatch(List.of(builder), buffers);
                environment.transforms.dispatch(List.of(builder));
                glMemoryBarrier(GL_ALL_BARRIER_BITS);
                glFinish();
                assertEquals(GL_NO_ERROR, glGetError(), "AR upload generated an OpenGL error");
                buffers.bindTransformBuffers();
                int output = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 1);
                int stride = (int) environment.layout.getSize();
                var bytes = MemoryUtil.memAlloc(expected.size() * stride);
                int total = expected.size();
                try {
                    glGetNamedBufferSubData(output, builder.getVertexCountOffset() * stride, bytes);
                    for (int index = 0; index < total; index++) {
                        int offset = index * stride;
                        Vector3f actual = new Vector3f(bytes.getFloat(offset),
                                bytes.getFloat(offset + 4), bytes.getFloat(offset + 8));
                        int match = -1;
                        for (int candidate = 0; candidate < expected.size(); candidate++) {
                            if (expected.get(candidate).distanceSquared(actual) < 0.00000001f) {
                                match = candidate;
                                break;
                            }
                        }
                        assertTrue(match >= 0, "Unexpected GPU vertex " + index + " of " + total
                                + " (" + instances + " instances per mesh): " + actual);
                        expected.remove(match);
                    }
                    assertTrue(expected.isEmpty());
                    System.out.println("AR GPU readback matched " + total + " vertices across "
                            + meshes.size() + " owned mesh buffers");
                } finally {
                    MemoryUtil.memFree(bytes);
                }
            } finally {
                glFinish();
                buffers.delete();
                meshes.forEach(StaticMesh.Acceleration::close);
            }
        }
    }

    private static final class Environment implements IBufferEnvironment, AutoCloseable {
        final VertexFormat format;
        final VertexLayout layout;
        final MeshUploadingProgramDispatcher uploads = new MeshUploadingProgramDispatcher();
        final TransformProgramDispatcher transforms = new TransformProgramDispatcher();
        final int transformProgram;
        final int uploadProgram;
        final ProgramOverride programs = new ProgramOverride(0, new ITransformOverride() {
            @Override public void useProgram() { glUseProgram(transformProgram); }
            @Override public void setupProgram() { }
            @Override public void uploadVarying(long address, int index) { }
            @Override public long getVaryingSize() { return 16; }
            @Override public int dispatchTransform(int count, int vertexOffset, int varyingOffset) {
                glUniform1ui(0, count);
                glUniform1ui(1, vertexOffset);
                glUniform1ui(2, varyingOffset);
                glDispatchCompute((count + 127) / 128, 1, 1);
                return GL_SHADER_STORAGE_BARRIER_BIT;
            }
        }, new IUploadingOverride() {
            @Override public void useProgram() { glUseProgram(uploadProgram); }
            @Override public void setupProgram() { }
            @Override public void uploadMeshInfo(long address, int index) { }
            @Override public long getMeshInfoSize() { return 28; }
            @Override public int dispatchUploading(int count, int size, int offset) {
                glUniform1ui(0, count);
                glUniform1ui(1, size);
                glUniform1ui(glGetUniformLocation(uploadProgram, "meshOffset"), offset);
                glDispatchCompute((count * size + 127) / 128, 1, 1);
                return GL_SHADER_STORAGE_BARRIER_BIT;
            }
        });

        Environment() throws Exception { this(false); }

        Environment(boolean block) throws Exception {
            format = block ? DefaultVertexFormat.BLOCK : DefaultVertexFormat.NEW_ENTITY;
            layout = new VertexLayout(format);
            String prefix = block ? "block" : "entity";
            transformProgram = compile("transform/" + prefix + "_vertex_transform_shader.compute");
            uploadProgram = compile("uploading/" + prefix + "_mesh_uploading_shader.compute");
        }

        @Override public int getVertexSize() { return (int) layout.getSize(); }
        @Override public int getOverrideCount() { return 1; }
        @Override public IDrawMethod getDrawMethod() { return BaseVertexDrawMethod.INSTANCE; }
        @Override public Set<VertexFormat> getVertexFormats() { return Set.of(format); }
        @Override public VertexLayout getLayout() { return layout; }
        @Override public MeshUploadingProgramDispatcher selectMeshUploadingProgramDispatcher() { return uploads; }
        @Override public TransformProgramDispatcher selectTransformProgramDispatcher() { return transforms; }
        @Override public ProgramOverride getProgramOverride(int index) { return programs; }
        @Override public ProgramOverride getProgramOverride(RenderType type) { return programs; }
        @Override public ICullingProgramDispatcher selectCullingProgramDispatcher(RenderType type) {
            return new ICullingProgramDispatcher() {
                @Override public int dispatch(AcceleratedBufferBuilder builder) { return 0; }
                @Override public boolean shouldCull() { return false; }
            };
        }
        @Override public IPolygonProgramDispatcher selectProcessingProgramDispatcher(VertexFormat.Mode mode) {
            return builder -> 0;
        }
        @Override public boolean isAccelerated(VertexFormat candidate) { return candidate == format; }
        @Override public void setupBufferState() { }
        @Override public void clear() { }
        @Override public void close() {
            glDeleteProgram(transformProgram);
            glDeleteProgram(uploadProgram);
        }
    }

    private static int compile(String path) throws Exception {
        String resource = "/assets/acceleratedrendering/shaders/core/" + path;
        String source;
        try (var input = AcceleratedRenderingGpuTest.class.getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        int shader = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(shader, source);
        glCompileShader(shader);
        assertEquals(GL_TRUE, glGetShaderi(shader, GL_COMPILE_STATUS), glGetShaderInfoLog(shader));
        int program = glCreateProgram();
        glAttachShader(program, shader);
        glLinkProgram(program);
        glDeleteShader(shader);
        assertEquals(GL_TRUE, glGetProgrami(program, GL_LINK_STATUS), glGetProgramInfoLog(program));
        return program;
    }
}
