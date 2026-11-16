package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;

import com.scarasol.barricade.pile.CollisionBox;
import com.scarasol.barricade.pile.PileModel;
import com.scarasol.barricade.pile.PilePiece;
import com.scarasol.barricade.sampling.MemoryFixture;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

class EmbeddedSurfaceTest {
    private static final BlockPos OWNER = new BlockPos(100_000, 80, -250_000);
    private static final PileModel MODEL = new PileModel("cube", "minecraft:furnace", 1, 1, 1, 1,
            List.of(CollisionBox.CUBE));

    private PilePiece piece(Quaternionf rotation) {
        return PilePiece.create(MODEL, 0.5f, 0.5f, 0.5f, 0.95f, rotation);
    }

    @Test
    void sixInsetCubeFacesSampleTheirOwnOutwardCellRatherThanTheOpaqueCenter() {
        var world = new LightWorld();
        assertTrue(world.getBlockState(OWNER).isSolidRender(world, OWNER));
        PilePiece piece = piece(new Quaternionf());
        try (StaticMesh mesh = cube()) {
            assertEquals(1, mesh.batches.size(), "Ordinary pile rendering keeps its single material batch");
            assertEquals(6, mesh.surfaces().size());
            assertSame(mesh.surfaces(), mesh.surfaces(), "Directional meshes are cached, not rebuilt each frame");
            for (StaticMesh.Batch face : mesh.surfaces()) {
                Direction direction = Direction.getNearest(face.lightNormal.x, face.lightNormal.y, face.lightNormal.z);
                assertEquals(OWNER.relative(direction), SurfaceLight.position(
                        OWNER, piece, face.lightPosition, face.lightNormal, true));
                assertEquals(4, face.vertices.size());
            }
        }
    }

    @Test
    void samplingFollowsLargeRotationsAndKeepsAlreadyExposedFacesAtTheirActualSurface() {
        var piece = piece(new Quaternionf().rotationZ((float) Math.PI / 2));
        assertEquals(OWNER.west(), SurfaceLight.position(OWNER, piece,
                new Vector3f(0.5f, 1, 0.5f), new Vector3f(0, 1, 0), true));
        assertEquals(OWNER.below(), SurfaceLight.position(OWNER, piece(new Quaternionf().rotationX((float) Math.PI)),
                new Vector3f(0.5f, 1, 0.5f), new Vector3f(0, 1, 0), true));
        var outward = PilePiece.create(MODEL, 1.5f, 0.5f, 0.5f, 1, new Quaternionf());
        assertEquals(OWNER.east(2), SurfaceLight.position(OWNER, outward,
                new Vector3f(1, 0.5f, 0.5f), new Vector3f(1, 0, 0), true));
        assertEquals(OWNER, SurfaceLight.position(OWNER, piece(new Quaternionf()),
                new Vector3f(0.5f, 1, 0.5f), new Vector3f(0, 1, 0), false),
                "Nonopaque hosts must not be projected to an unrelated adjacent cell");
    }

    @Test
    void vanillaLightReadsRespectDarknessFaceDirectionAndBlockLightWithoutBrightestNeighborCompensation() {
        var world = new LightWorld();
        assertEquals(0, LevelRenderer.getLightColor(world, OWNER), "The old center sample is black");
        assertEquals(15 << 20, LevelRenderer.getLightColor(world, OWNER.above()));
        assertEquals(0, LevelRenderer.getLightColor(world, OWNER.below()));
        assertEquals(9 << 4, LevelRenderer.getLightColor(world, OWNER.east()));
        world.day = false;
        assertEquals(0, LevelRenderer.getLightColor(world, OWNER.above()));
        assertEquals(9 << 4, LevelRenderer.getLightColor(world, OWNER.east()));
        try (StaticMesh mesh = cube()) {
            for (boolean day : new boolean[]{true, false}) {
                world.day = day;
                for (StaticMesh.Batch face : mesh.surfaces()) {
                    Direction direction = Direction.getNearest(face.lightNormal.x, face.lightNormal.y, face.lightNormal.z);
                    BlockPos sample = SurfaceLight.position(OWNER, piece(new Quaternionf()),
                            face.lightPosition, face.lightNormal, true);
                    int expected = direction == Direction.EAST ? 9 << 4
                            : day && direction == Direction.UP ? 15 << 20 : 0;
                    assertEquals(expected, LevelRenderer.getLightColor(world, sample));
                }
            }
        }
    }

    @Test
    void overhangingFacesSampleOutsideTheEmbeddedCellNotOutsideTheirLogicalOwner() {
        var world = new LightWorld();
        BlockPos logicalOwner = OWNER.east();
        PilePiece overhang = PilePiece.create(MODEL, -.5f, .5f, .5f, .95f, new Quaternionf());
        try (StaticMesh mesh = cube()) {
            StaticMesh.Batch original = mesh.batches.get(0);
            assertEquals(OWNER, BlockPos.containing(logicalOwner.getX() + (double) overhang.x(),
                    logicalOwner.getY() + (double) overhang.y(), logicalOwner.getZ() + (double) overhang.z()));
            assertEquals(0, LevelRenderer.getLightColor(world, OWNER));
            assertSame(original.surfaces(), original.surfaces(), "Only affected batches need cached face meshes");
            assertEquals(mesh.surfaces(), original.surfaces());
            assertEquals(original.vertices.size(),
                    original.surfaces().stream().mapToInt(face -> face.vertices.size()).sum());
            for (boolean day : new boolean[]{true, false}) {
                world.day = day;
                for (StaticMesh.Batch face : original.surfaces()) {
                    Direction direction = Direction.getNearest(face.lightNormal.x, face.lightNormal.y, face.lightNormal.z);
                    assertEquals(OWNER, SurfaceLight.position(logicalOwner, overhang,
                            face.lightPosition, face.lightNormal, false), "The old sample is trapped in the native cell");
                    BlockPos sample = SurfaceLight.position(logicalOwner, overhang,
                            face.lightPosition, face.lightNormal, OWNER::equals);
                    assertEquals(OWNER.relative(direction), sample);
                    assertEquals(direction == Direction.EAST ? 9 << 4
                            : day && direction == Direction.UP ? 15 << 20 : 0,
                            LevelRenderer.getLightColor(world, sample));
                }
            }
        }
    }

    @Test
    void surfaceCorrectionOnlyMovesSamplesInsideTheSuppliedOpaqueCells() {
        PilePiece overhang = PilePiece.create(MODEL, -.5f, .5f, .5f, .95f,
                new Quaternionf().rotationZ((float) Math.PI / 2));
        BlockPos logicalOwner = OWNER.east();
        try (StaticMesh mesh = cube()) {
            for (StaticMesh.Batch face : mesh.surfaces()) {
                assertEquals(SurfaceLight.position(logicalOwner, overhang,
                                face.lightPosition, face.lightNormal, false),
                        SurfaceLight.position(logicalOwner, overhang,
                                face.lightPosition, face.lightNormal, ignored -> false),
                        "No projection is applied when the caller supplies no opaque cells");
                assertEquals(SurfaceLight.position(OWNER, piece(overhang.rotation()),
                                face.lightPosition, face.lightNormal, true),
                        SurfaceLight.position(logicalOwner, overhang,
                                face.lightPosition, face.lightNormal, OWNER::equals),
                        "Projection follows the transformed face normal even across a logical cell boundary");
            }
            PilePiece exposed = PilePiece.create(MODEL, 1.5f, .5f, .5f, 1, new Quaternionf());
            assertEquals(OWNER.east(2), SurfaceLight.position(OWNER, exposed,
                    new Vector3f(1, .5f, .5f), new Vector3f(1, 0, 0), OWNER::equals));
        }
    }

    @Test
    void addingAnOrdinaryGrassNeighborDoesNotMakeTheWholeBatchBlackOrChangeItsGeometry() {
        var world = new LightWorld();
        world.blocks.put(OWNER, Blocks.AIR.defaultBlockState());
        BlockPos neighbor = OWNER.above();
        for (Direction side : Direction.values()) world.sky.put(neighbor.relative(side), 15);
        world.block.put(neighbor.east(), 9);
        var board = new PileModel("board", "minecraft:oak_planks", .8f, .1f, .8f, 1,
                List.of(CollisionBox.CUBE));
        PilePiece piece = PilePiece.create(board, .5f, 1.02f, .5f, 1, new Quaternionf());
        try (StaticMesh mesh = cube()) {
            StaticMesh.Batch batch = mesh.batches.get(0);
            assertEquals(15 << 20, LevelRenderer.getLightColor(world, neighbor));
            assertFalse(SurfaceLight.directional(world, neighbor, 15 << 20));
            assertFalse(BarricadeRenderer.suppressOriginal(neighbor));

            world.blocks.put(neighbor, Blocks.GRASS_BLOCK.defaultBlockState());
            int oldCenterLight = LevelRenderer.getLightColor(world, neighbor);
            assertEquals(0, oldCenterLight, "The old one-sample batch would turn completely black");
            assertTrue(SurfaceLight.directional(world, neighbor, oldCenterLight),
                    "Ordinary opaque neighbors need the same surface correction as embedded hosts");
            var surfaces = batch.surfaces();
            assertEquals(batch.vertices.size(), surfaces.stream().mapToInt(face -> face.vertices.size()).sum());
            for (StaticMesh.Batch surface : surfaces)
                assertNotEquals(0, SurfaceLight.sample(world, ignored -> true, OWNER, piece, surface),
                        "Each exposed face must read the supplied surrounding daylight");

            world.day = false;
            for (StaticMesh.Batch surface : surfaces) {
                Direction side = Direction.getNearest(surface.lightNormal.x, surface.lightNormal.y, surface.lightNormal.z);
                assertEquals(side == Direction.EAST ? 9 << 4 : 0,
                        SurfaceLight.sample(world, ignored -> true, OWNER, piece, surface),
                        "Correction must preserve darkness and local block light, not force a brightness floor");
            }
            assertSame(surfaces, batch.surfaces(), "Neighbor changes reuse cached surface geometry");
            world.blocks.remove(neighbor); world.day = true;
            assertFalse(SurfaceLight.directional(world, neighbor, LevelRenderer.getLightColor(world, neighbor)));
            assertSame(batch, mesh.batches.get(0));
            assertEquals(piece, PilePiece.create(board, .5f, 1.02f, .5f, 1, new Quaternionf()));
        }
    }

    @Test
    void aSurfaceProbeThatJustEntersAnOpaqueNeighborAlsoExitsThatNeighbor() {
        PilePiece nearBoundary = PilePiece.create(MODEL, .5f, .4995f, .5f, 1, new Quaternionf());
        BlockPos above = OWNER.above();
        assertEquals(above, SurfaceLight.position(OWNER, nearBoundary,
                new Vector3f(.5f, 1, .5f), new Vector3f(0, 1, 0), ignored -> false));
        assertEquals(OWNER.above(2), SurfaceLight.position(OWNER, nearBoundary,
                new Vector3f(.5f, 1, .5f), new Vector3f(0, 1, 0), above::equals));
    }

    @Test
    void darkAirAndNormallyLitBatchesKeepTheirSingleBatchPathAndUnknownChunksAreNotRead() {
        var world = new LightWorld();
        world.day = false;
        assertFalse(SurfaceLight.directional(world, OWNER.above(), 0),
                "Real darkness in air does not trigger directional mesh splitting");
        int reads = world.stateReads;
        assertFalse(SurfaceLight.directional(world, OWNER, 9 << 4),
                "An ordinary emitting block does not need a zero-light correction");
        assertEquals(reads, world.stateReads, "Normally lit batches add no extra world-state query");
        try (StaticMesh mesh = cube()) {
            for (StaticMesh.Batch surface : mesh.surfaces())
                assertEquals(0, SurfaceLight.sample(world, ignored -> false, OWNER, piece(new Quaternionf()), surface));
        }
        assertEquals(reads, world.stateReads, "Unknown chunks are never read for surface correction");
    }

    @Test
    void hiddenNativeBlockNoLongerCutsAHoleInItsNeighborsButSamplingAndUnmarkedBlocksStayNative() throws Exception {
        var world = new LightWorld();
        var grass = world.getBlockState(OWNER.below());
        var furnace = world.getBlockState(OWNER);
        assertTrue(furnace.canOcclude());
        assertFalse(grass.skipRendering(furnace, Direction.UP));
        assertFalse(Shapes.joinIsNotEmpty(grass.getFaceOcclusionShape(world, OWNER.below(), Direction.UP),
                furnace.getFaceOcclusionShape(world, OWNER, Direction.DOWN), BooleanOp.ONLY_FIRST),
                "Vanilla face occlusion removes the grass top even when only the furnace draw is suppressed");
        var field = BarricadeRenderer.class.getDeclaredField("SUPPRESSED");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") Set<BlockPos> suppressed = (Set<BlockPos>) field.get(null);
        Method hook = Class.forName("com.scarasol.barricade.mixin.EmbeddedFaceMixin").getDeclaredMethod(
                "barricade$showFaceBesideEmbedded", BlockState.class, net.minecraft.world.level.BlockGetter.class,
                BlockPos.class, Direction.class, BlockPos.class, CallbackInfoReturnable.class);
        hook.setAccessible(true);
        try {
            var ordinary = new CallbackInfoReturnable<Boolean>("test", true);
            hook.invoke(null, world.getBlockState(OWNER.below()), world, OWNER.below(), Direction.UP, OWNER, ordinary);
            assertFalse(ordinary.isCancelled(), "Ordinary adjacent placement cannot change face visibility");
            suppressed.add(OWNER);
            for (Direction side : Direction.values()) {
                BlockPos neighbor = OWNER.relative(side);
                var callback = new CallbackInfoReturnable<Boolean>("test", true);
                hook.invoke(null, Blocks.STONE.defaultBlockState(), world, neighbor, side.getOpposite(), OWNER, callback);
                assertTrue(callback.isCancelled());
                assertTrue(callback.getReturnValueZ());
            }
            assertTrue(world.getBlockState(OWNER).isSolidRender(world, OWNER),
                    "Rendering must not change the real block or its physical occlusion properties");
            var memory = MemoryFixture.create(true);
            try {
                var callback = new CallbackInfoReturnable<Boolean>("test", true);
                hook.invoke(null, Blocks.STONE.defaultBlockState(), memory, OWNER.below(), Direction.UP, OWNER, callback);
                assertFalse(callback.isCancelled(), "World coordinates must not leak into in-memory captures");
                assertEquals(0, memory.chunkRequests());
            } finally {
                memory.reset();
            }
        } finally {
            suppressed.remove(OWNER);
        }
    }

    private StaticMesh cube() {
        MemoryFixture.create(true);
        List<StaticMesh.Vertex> vertices = new ArrayList<>();
        for (Direction side : Direction.values()) {
            int axis = side.getAxis().ordinal();
            for (int corner = 0; corner < 4; corner++) {
                float[] point = new float[3];
                point[axis] = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
                point[(axis + 1) % 3] = corner == 1 || corner == 2 ? 1 : 0;
                point[(axis + 2) % 3] = corner >= 2 ? 1 : 0;
                vertices.add(new StaticMesh.Vertex(point[0], point[1], point[2], 255, 255, 255, 255,
                        0, 0, 0, 0, side.getStepX(), side.getStepY(), side.getStepZ()));
            }
        }
        return new StaticMesh(List.of(new StaticMesh.Batch(RenderType.cutout(), vertices, new Vector3f(0.5f))),
                new net.minecraft.world.phys.AABB(0, 0, 0, 1, 1, 1));
    }

    private static final class LightWorld implements BlockAndTintGetter {
        boolean day = true;
        int stateReads;
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final Map<BlockPos, Integer> sky = new HashMap<>(), block = new HashMap<>();

        LightWorld() { MemoryFixture.create(true); }
        @Override public BlockState getBlockState(BlockPos pos) {
            stateReads++;
            if (blocks.containsKey(pos)) return blocks.get(pos);
            return pos.equals(OWNER) ? Blocks.FURNACE.defaultBlockState()
                    : pos.getY() < OWNER.getY() ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }
        @Override public int getBrightness(LightLayer layer, BlockPos pos) {
            if (getBlockState(pos).isSolidRender(this, pos)) return 0;
            if (layer == LightLayer.BLOCK && block.containsKey(pos)) return block.get(pos);
            if (pos.equals(OWNER.east()) && layer == LightLayer.BLOCK) return 9;
            return day && layer == LightLayer.SKY ? sky.getOrDefault(pos, pos.equals(OWNER.above()) ? 15 : 0) : 0;
        }
        @Override public float getShade(Direction direction, boolean shade) { return 1; }
        @Override public LevelLightEngine getLightEngine() { throw new AssertionError("Use the supplied vanilla light values"); }
        @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) { return 0xffffff; }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }
}
