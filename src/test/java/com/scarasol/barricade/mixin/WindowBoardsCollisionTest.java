package com.scarasol.barricade.mixin;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.scarasol.barricade.sampling.MemoryFixture;
import com.scarasol.barricade.window.WindowBoardsBlock;
import com.scarasol.barricade.window.WindowBarsBlock;
import com.scarasol.barricade.window.WindowSealBlock;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagEntry;
import net.minecraft.tags.TagFile;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.WindowBoardsTestRegistries;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class WindowBoardsCollisionTest {
    private static final BlockPos POS = new BlockPos(0, 8, 0);
    private static WindowBoardsBlock boards;
    private static WindowBarsBlock bars;
    private Map<TagKey<EntityType<?>>, List<Holder<EntityType<?>>>> originalTags;

    @BeforeAll
    @SuppressWarnings("deprecation")
    static void bootstrap() {
        MemoryFixture.create(false);
        WindowBoardsTestRegistries.initializeFluids();
        var registry = BuiltInRegistries.BLOCK;
        var id = new ResourceLocation("barricade", "window_boards");
        // Unit tests have no mod registration lifecycle; register only this fixture through Forge's API.
        var forge = (ForgeRegistry<Block>) ForgeRegistries.BLOCKS;
        boolean locked = forge.isLocked();
        forge.unfreeze();
        ((MappedRegistry<Block>) registry).unfreeze();
        try {
            boards = new WindowBoardsBlock();
            forge.register(-1, id, boards);
            bars = new WindowBarsBlock();
            forge.register(-1, new ResourceLocation("barricade", "window_bars"), bars);
        } finally {
            registry.freeze();
            if (locked) forge.freeze();
        }
        boards.getStateDefinition().getPossibleStates().forEach(BlockState::initCache);
        bars.getStateDefinition().getPossibleStates().forEach(BlockState::initCache);
    }

    @BeforeEach void bindDefaults() {
        var registry = BuiltInRegistries.ENTITY_TYPE;
        registry.getOrCreateTag(WindowBoardsBlock.PASS_THROUGH_ENTITY_TYPES);
        originalTags = registry.getTags().collect(Collectors.toMap(
                pair -> pair.getFirst(), pair -> pair.getSecond().stream().toList()));
        allow(EntityType.ARROW, EntityType.SPECTRAL_ARROW, EntityType.TRIDENT);
    }

    @AfterEach void restoreTags() { BuiltInRegistries.ENTITY_TYPE.bindTags(originalTags); }

    private void allow(EntityType<?>... types) {
        var registry = BuiltInRegistries.ENTITY_TYPE;
        var selected = new HashMap<>(originalTags);
        selected.put(WindowBoardsBlock.PASS_THROUGH_ENTITY_TYPES,
                Arrays.stream(types).map(registry::wrapAsHolder).toList());
        registry.bindTags(selected);
    }

    @Test void filteringKeepsOutlinesAndContextFreePanelsOnBothSidesAndAllSixFacings() {
        for (boolean client : new boolean[]{false, true}) {
            var level = MemoryFixture.create(client);
            var arrow = new Arrow(EntityType.ARROW, level);
            var untagged = new Snowball(EntityType.SNOWBALL, level);
            for (WindowSealBlock block : List.of(boards, bars)) for (Direction facing : Direction.values()) {
                BlockState state = block.defaultBlockState().setValue(WindowSealBlock.FACING, facing);
                VoxelShape outline = state.getShape(level, POS, CollisionContext.of(arrow));
                assertFalse(outline.isEmpty());
                assertTrue(state.getCollisionShape(level, POS, CollisionContext.of(arrow)).isEmpty());
                assertEquals(outline.bounds(), state.getCollisionShape(level, POS, CollisionContext.of(untagged)).bounds());
                assertEquals(outline.bounds(), state.getCollisionShape(level, POS).bounds());
                assertEquals(outline.bounds(), state.getCollisionShape(level, POS, CollisionContext.empty()).bounds());
                assertEquals(outline.bounds(), state.getShape(level, POS, CollisionContext.of(untagged)).bounds());
                assertEquals(6.0 / 16, outline.max(facing.getAxis()) - outline.min(facing.getAxis()), 1.0e-12);
            }
            assertFalse(arrow.isNoPhysics());
        }
    }

    @Test void colliderRaysSkipOnlyBoardsAndStillHitTheWallBehindThem() {
        for (WindowSealBlock block : List.of(boards, bars))
            for (boolean client : new boolean[]{false, true}) for (Direction facing : Direction.values()) {
            var level = MemoryFixture.create(client);
            BlockState state = block.defaultBlockState().setValue(WindowSealBlock.FACING, facing);
            BlockPos wall = POS.relative(facing.getOpposite(), 2);
            level.context(Map.of(POS, state, wall, Blocks.STONE.defaultBlockState()), Map.of());
            Vec3 center = Vec3.atCenterOf(POS);
            Vec3 axis = Vec3.atLowerCornerOf(facing.getNormal());
            Vec3 start = center.add(axis.scale(2)), end = center.subtract(axis.scale(3));
            var arrow = new Arrow(EntityType.ARROW, level);
            var collider = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, arrow));
            assertEquals(HitResult.Type.BLOCK, collider.getType());
            assertEquals(wall, collider.getBlockPos());
            assertEquals(POS, level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, new Snowball(EntityType.SNOWBALL, level))).getBlockPos());
            assertEquals(POS, level.clip(new ClipContext(start, end, ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, arrow)).getBlockPos());
            assertEquals(POS, level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, null)).getBlockPos());
        }
    }

    @Test void ordinaryEntityMovementUsesTheSameTypeWhitelistWithoutScoreboardTags() {
        for (WindowSealBlock block : List.of(boards, bars)) {
            allow(EntityType.ARROW, EntityType.SPECTRAL_ARROW, EntityType.TRIDENT);
            var level = MemoryFixture.create(false);
            BlockState state = block.defaultBlockState().setValue(WindowSealBlock.FACING, Direction.NORTH);
            level.context(Map.of(POS, state), Map.of());
            var entity = new ItemEntity(EntityType.ITEM, level);
            entity.setPos(0.5, 8.25, -0.5);
            entity.addTag(WindowBoardsBlock.PASS_THROUGH_ENTITY_TYPES.location().toString());
            Vec3 motion = new Vec3(0, 0, 2);
            assertTrue(Entity.collideBoundingBox(entity, motion, entity.getBoundingBox(), level, List.of()).z < motion.z);
            allow(EntityType.ITEM);
            assertEquals(motion, Entity.collideBoundingBox(entity, motion, entity.getBoundingBox(), level, List.of()));
            assertEquals(state.getShape(level, POS).bounds(), state.getShape(level, POS, CollisionContext.of(entity)).bounds());
            allow();
            assertTrue(Entity.collideBoundingBox(entity, motion, entity.getBoundingBox(), level, List.of()).z < motion.z);
        }
    }

    @Test void tagRebindingImmediatelyChangesCollisionWithoutASeparateCache() {
        for (WindowSealBlock block : List.of(boards, bars)) {
            allow(EntityType.ARROW, EntityType.SPECTRAL_ARROW, EntityType.TRIDENT);
            var level = MemoryFixture.create(false);
            BlockState state = block.defaultBlockState();
            CollisionContext arrow = CollisionContext.of(new Arrow(EntityType.ARROW, level));
            CollisionContext snowball = CollisionContext.of(new Snowball(EntityType.SNOWBALL, level));
            assertTrue(state.getCollisionShape(level, POS, arrow).isEmpty());
            assertFalse(state.getCollisionShape(level, POS, snowball).isEmpty());
            allow(EntityType.SNOWBALL);
            assertFalse(state.getCollisionShape(level, POS, arrow).isEmpty());
            assertTrue(state.getCollisionShape(level, POS, snowball).isEmpty());
            allow();
            assertFalse(state.getCollisionShape(level, POS, arrow).isEmpty());
            assertFalse(state.getCollisionShape(level, POS, snowball).isEmpty());
        }
    }

    @Test void arrowWrapperPreservesOriginalCallsAndAllNonExemptResults() {
        var level = MemoryFixture.create(false);
        for (BlockState state : List.of(boards.defaultBlockState(), bars.defaultBlockState(), Blocks.STONE.defaultBlockState())) {
            for (EntityType<? extends Projectile> type : List.of(EntityType.ARROW, EntityType.SPECTRAL_ARROW,
                    EntityType.TRIDENT, EntityType.SNOWBALL)) {
                VoxelShape originalShape = state.getCollisionShape(level, POS);
                var calls = new AtomicInteger();
                VoxelShape actual = new ArrowHarness(type, level).barricade$windowBoardsEmbedding(state, level, POS, arguments -> {
                    assertArrayEquals(new Object[]{state, level, POS}, arguments);
                    calls.incrementAndGet();
                    return originalShape;
                });
                assertEquals(1, calls.get());
                if (state.getBlock() instanceof WindowSealBlock && type != EntityType.SNOWBALL) assertTrue(actual.isEmpty());
                else assertSame(originalShape, actual);
            }
        }
    }

    @Test void aSlowArrowCanEndItsStepInsideThePanelWithoutTheEmbeddingCheckStoppingIt() {
        for (WindowSealBlock block : List.of(boards, bars)) {
            allow(EntityType.ARROW, EntityType.SPECTRAL_ARROW, EntityType.TRIDENT);
            var level = MemoryFixture.create(false);
            BlockState state = block.defaultBlockState().setValue(WindowSealBlock.FACING, Direction.NORTH);
            level.context(Map.of(POS, state), Map.of());
            var arrow = new Arrow(EntityType.ARROW, level);
            Vec3 start = new Vec3(0.5, 8.5, 0.5), end = start.add(0, 0, 0.25);
            assertEquals(HitResult.Type.MISS, level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, arrow)).getType());
            VoxelShape original = state.getCollisionShape(level, POS);
            assertTrue(original.toAabbs().stream().anyMatch(box -> box.move(POS).contains(end)));
            VoxelShape filtered = new ArrowHarness(EntityType.ARROW, level).barricade$windowBoardsEmbedding(
                    state, level, POS, arguments -> original);
            assertFalse(filtered.toAabbs().stream().anyMatch(box -> box.move(POS).contains(end)));
            allow();
            assertSame(original, new ArrowHarness(EntityType.ARROW, level).barricade$windowBoardsEmbedding(
                    state, level, POS, arguments -> original));
        }
    }

    @Test void packagedTagLoadsWithoutGunModsAndKeepsTheirTypesOptional() throws Exception {
        try (var input = getClass().getResourceAsStream("/data/barricade/tags/entity_types/window_boards_pass_through.json")) {
            assertNotNull(input);
            var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            TagFile file = TagFile.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow();
            assertFalse(file.replace());
            assertEquals(Set.of("tacz:bullet", "superbwarfare:projectile"), file.entries().stream()
                    .filter(entry -> !entry.isRequired()).map(entry -> entry.getId().toString()).collect(Collectors.toSet()));
            var entries = file.entries().stream().map(entry -> new TagLoader.EntryWithSource(entry, "barricade")).toList();
            var arrows = List.of(
                    new TagLoader.EntryWithSource(TagEntry.element(new ResourceLocation("minecraft", "arrow")), "vanilla"),
                    new TagLoader.EntryWithSource(TagEntry.element(new ResourceLocation("minecraft", "spectral_arrow")), "vanilla"));
            var loader = new TagLoader<EntityType<?>>(BuiltInRegistries.ENTITY_TYPE::getOptional, "tags/entity_types");
            var loaded = loader.build(Map.of(WindowBoardsBlock.PASS_THROUGH_ENTITY_TYPES.location(), entries,
                    new ResourceLocation("minecraft", "arrows"), arrows));
            assertEquals(Set.of(EntityType.ARROW, EntityType.SPECTRAL_ARROW, EntityType.TRIDENT),
                    Set.copyOf(loaded.get(WindowBoardsBlock.PASS_THROUGH_ENTITY_TYPES.location())));
        }
    }

    @Test void arrowWrapperTargetsExactlyTheOneContextFreeTickQueryAndIsRegisteredOnBothSides() throws Exception {
        ClassNode mixin = bytecode(WindowBoardsArrowMixin.class);
        var handler = mixin.methods.stream().filter(method -> method.name.equals("barricade$windowBoardsEmbedding"))
                .findFirst().orElseThrow();
        AnnotationNode wrap = handler.visibleAnnotations.stream().filter(annotation -> annotation.desc.equals(
                "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;")).findFirst().orElseThrow();
        assertEquals(List.of("tick()V"), annotationValue(wrap, "method"));
        assertEquals(1, annotationValue(wrap, "require"));
        assertEquals(1, annotationValue(wrap, "allow"));
        var at = (AnnotationNode) ((List<?>) annotationValue(wrap, "at")).get(0);
        assertEquals("INVOKE", annotationValue(at, "value"));
        String target = (String) annotationValue(at, "target");
        var tick = bytecode(AbstractArrow.class).methods.stream()
                .filter(method -> method.name.equals("tick") && method.desc.equals("()V")).findFirst().orElseThrow();
        int matches = 0;
        for (var instruction : tick.instructions)
            if (instruction instanceof MethodInsnNode call && target.equals("L" + call.owner + ";" + call.name + call.desc))
                matches++;
        assertEquals(1, matches);
        try (var input = getClass().getResourceAsStream("/barricade.mixins.json")) {
            assertNotNull(input);
            var config = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(config.getAsJsonArray("mixins").asList().stream()
                    .anyMatch(value -> value.getAsString().equals("WindowBoardsArrowMixin")));
            assertTrue(config.getAsJsonArray("client").asList().stream()
                    .noneMatch(value -> value.getAsString().equals("WindowBoardsArrowMixin")));
        }
    }

    private static ClassNode bytecode(Class<?> type) throws Exception {
        try (var input = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            assertNotNull(input);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static Object annotationValue(AnnotationNode annotation, String name) {
        for (int index = 0; index < annotation.values.size(); index += 2)
            if (annotation.values.get(index).equals(name)) return annotation.values.get(index + 1);
        fail("Missing annotation value: " + name);
        return null;
    }

    private static final class ArrowHarness extends WindowBoardsArrowMixin {
        ArrowHarness(EntityType<? extends Projectile> type, Level level) { super(type, level); }
        @Override protected void defineSynchedData() {}
    }
}
