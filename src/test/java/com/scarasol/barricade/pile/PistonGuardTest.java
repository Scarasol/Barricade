package com.scarasol.barricade.pile;

import static org.junit.jupiter.api.Assertions.*;

import com.scarasol.barricade.block.BarricadeBlock;
import com.scarasol.barricade.sampling.MemoryFixture;
import com.scarasol.barricade.sampling.MemoryLevel;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.event.level.PistonEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PistonGuardTest {
    private static final BlockPos BASE = new BlockPos(0, 8, 0), HEAD = BASE.east(), PULL = BASE.east(2);
    private static final UUID ID = new UUID(1, 2);

    @BeforeAll static void bootstrap() { MemoryFixture.create(false).reset(); }

    @Test
    void barricadeDeclaresBlockReactionAndVanillaRejectsItOnEveryAxis() throws Exception {
        var node = new org.objectweb.asm.tree.ClassNode();
        try (var input = BarricadeBlock.class.getResourceAsStream("BarricadeBlock.class")) {
            assertNotNull(input);
            new org.objectweb.asm.ClassReader(input).accept(node, 0);
        }
        var constructor = node.methods.stream().filter(method -> method.name.equals("<init>")).findFirst().orElseThrow();
        boolean configured = false;
        for (var instruction : constructor.instructions)
            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals("pushReaction")) {
                var previous = call.getPrevious();
                while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
                assertInstanceOf(org.objectweb.asm.tree.FieldInsnNode.class, previous);
                var reaction = (org.objectweb.asm.tree.FieldInsnNode) previous;
                assertEquals("net/minecraft/world/level/material/PushReaction", reaction.owner);
                assertEquals("BLOCK", reaction.name);
                configured = true;
            }
        assertTrue(configured, "The barricade's own constructor must configure the blocking property");
        var world = MemoryFixture.create(false);
        try {
            // Exercise native BLOCK semantics without unfreezing Forge's registry to construct a test block.
            BlockState state = Blocks.PISTON_HEAD.defaultBlockState();
            assertEquals(PushReaction.BLOCK, state.getPistonPushReaction());
            for (Direction direction : Direction.values()) for (boolean destroy : new boolean[]{false, true})
                assertFalse(PistonBaseBlock.isPushable(state, world, PULL, direction, destroy, direction));
        } finally {
            world.reset();
        }
    }

    @Test
    void directAndChainedPushesProtectOnlyRegisteredEmbeddedPositionsWithoutChangingData() {
        try (var fixture = new Fixture(false, true, Map.of(
                HEAD, Blocks.STONE.defaultBlockState(), PULL, Blocks.OAK_PLANKS.defaultBlockState()))) {
            assertFalse(fixture.blocked(new PileData()));
            PileData embedded = embedding(PULL);
            CompoundTag before = embedded.save(new CompoundTag());
            assertTrue(fixture.blocked(embedded));
            assertEquals(before, embedded.save(new CompoundTag()));
            assertFalse(fixture.blocked(embedding(PULL.south())), "An ordinary neighboring block is not in the move");
            assertTrue(fixture.world.writtenPositions().isEmpty());
            assertEquals(0, fixture.world.chunkRequests());
        }
    }

    @Test
    void embeddedFragileBlocksCannotBeCrushedByExtensionOrByAPulledSideBranch() {
        try (var fixture = new Fixture(false, true, Map.of(HEAD, Blocks.TORCH.defaultBlockState()))) {
            assertEquals(PushReaction.DESTROY, fixture.world.getBlockState(HEAD).getPistonPushReaction());
            assertTrue(fixture.blocked(embedding(HEAD)));
        }
        try (var fixture = new Fixture(true, false, Map.of(
                PULL, Blocks.SLIME_BLOCK.defaultBlockState(),
                PULL.south(), Blocks.STONE.defaultBlockState(),
                HEAD.south(), Blocks.TORCH.defaultBlockState()))) {
            assertTrue(fixture.blocked(embedding(HEAD.south())));
        }
    }

    @Test
    void stickyRetractionProtectsTheDirectPullButOrdinaryRetractionRemainsAllowed() {
        for (boolean sticky : new boolean[]{false, true}) {
            try (var fixture = new Fixture(sticky, false, Map.of(PULL, Blocks.STONE.defaultBlockState()))) {
                assertEquals(sticky, fixture.blocked(embedding(PULL)));
                assertFalse(fixture.blocked(embedding(PULL.south())));
            }
        }
    }

    @Test
    void stickySideBranchesAreProtectedEvenWhileTheRetractingHeadStillBlocksTheHelper() {
        try (var fixture = new Fixture(true, false, Map.of(
                PULL, Blocks.SLIME_BLOCK.defaultBlockState(),
                PULL.south(), Blocks.SLIME_BLOCK.defaultBlockState(),
                PULL.south(2), Blocks.STONE.defaultBlockState()))) {
            var helper = fixture.event().getStructureHelper();
            assertFalse(helper.resolve(), "Pre fires before the piston head is cleared");
            assertFalse(helper.getToPush().contains(PULL.south(2)));
            PileData embedded = embedding(PULL.south(2));
            CompoundTag before = embedded.save(new CompoundTag());
            assertTrue(fixture.blocked(embedded));
            assertEquals(before, embedded.save(new CompoundTag()));
            assertEquals(Blocks.PISTON_HEAD.defaultBlockState().setValue(PistonHeadBlock.FACING, Direction.EAST),
                    fixture.world.getBlockState(HEAD), "The guard must not temporarily remove the real head");
            assertTrue(fixture.world.writtenPositions().isEmpty());
        }
    }

    @Test
    void slimeAndHoneyDoNotAcquireAnUnrelatedEmbeddedNeighbor() {
        for (boolean extend : new boolean[]{false, true}) {
            BlockPos root = extend ? HEAD : PULL;
            try (var fixture = new Fixture(true, extend, Map.of(
                    root, Blocks.SLIME_BLOCK.defaultBlockState(),
                    root.south(), Blocks.HONEY_BLOCK.defaultBlockState()))) {
                assertFalse(fixture.blocked(embedding(root.south())));
            }
        }
    }

    @Test
    void nativeImmovableBlocksAndOverLimitStructuresDoNotPreventAnOtherwiseEmptyRetraction() {
        try (var fixture = new Fixture(true, false, Map.of(PULL, Blocks.FURNACE.defaultBlockState()))) {
            assertFalse(fixture.blocked(embedding(PULL)), "Vanilla already leaves this immovable block in place");
        }
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int x = 2; x < 15; x++) blocks.put(BASE.east(x), Blocks.SLIME_BLOCK.defaultBlockState());
        try (var fixture = new Fixture(true, false, blocks)) {
            assertFalse(fixture.blocked(embedding(PULL)), "More than twelve moving blocks cannot be pulled");
            assertEquals(0, fixture.world.chunkRequests());
        }
    }

    @Test
    void pullGuardMatchesVanillaDependenciesWithTheHeadClearedAcrossRandomStructures() {
        MemoryFixture.create(false).reset();
        var random = new Random(7341);
        BlockState[] states = {Blocks.STONE.defaultBlockState(), Blocks.SLIME_BLOCK.defaultBlockState(),
                Blocks.HONEY_BLOCK.defaultBlockState(), Blocks.TORCH.defaultBlockState(),
                Blocks.OBSIDIAN.defaultBlockState()};
        for (int run = 0; run < 300; run++) {
            Map<BlockPos, BlockState> blocks = new HashMap<>();
            blocks.put(PULL, states[run % 3]);
            for (BlockPos pos : BlockPos.betweenClosed(BASE.offset(-1, -1, -2), BASE.offset(5, 1, 2)))
                if (!pos.equals(BASE) && !pos.equals(HEAD) && !pos.equals(PULL) && random.nextInt(5) == 0)
                    blocks.put(pos.immutable(), states[random.nextInt(states.length)]);
            var marked = blocks.keySet().stream().sorted().toList();
            PileData data = embedding(marked.get(random.nextInt(marked.size())));
            try (var fixture = new Fixture(true, false, blocks)) {
                boolean actual = fixture.blocked(data);
                Map<BlockPos, BlockState> cleared = new HashMap<>(blocks);
                cleared.put(BASE, fixture.world.getBlockState(BASE));
                fixture.world.context(cleared, Map.of());
                var helper = new PistonStructureResolver(fixture.world, BASE, Direction.EAST, false);
                boolean resolved = helper.resolve();
                boolean expected = resolved && (helper.getToPush().stream().anyMatch(pos ->
                        data.embeddedAt(pos) != null || data.embeddedAt(pos.west()) != null)
                        || helper.getToDestroy().stream().anyMatch(pos -> data.embeddedAt(pos) != null));
                assertEquals(expected, actual, "Structure " + run + ": " + blocks);
            }
        }
    }

    private PileData embedding(BlockPos pos) {
        PileData data = new PileData();
        data.add(pos, ID, 42);
        assertNotNull(data.addEmbedded(pos));
        return data;
    }

    private static final class Fixture implements AutoCloseable {
        final com.scarasol.barricade.sampling.TestMemoryLevel world = MemoryFixture.create(false);
        final boolean extend;

        Fixture(boolean sticky, boolean extend, Map<BlockPos, BlockState> blocks) {
            this.extend = extend;
            Map<BlockPos, BlockState> context = new HashMap<>();
            context.put(BASE, (sticky ? Blocks.STICKY_PISTON : Blocks.PISTON).defaultBlockState()
                    .setValue(PistonBaseBlock.FACING, Direction.EAST).setValue(PistonBaseBlock.EXTENDED, !extend));
            if (!extend) context.put(HEAD,
                    Blocks.PISTON_HEAD.defaultBlockState().setValue(PistonHeadBlock.FACING, Direction.EAST));
            context.putAll(blocks);
            world.context(context, Map.of());
        }

        PistonEvent.Pre event() {
            return new PistonEvent.Pre(world, BASE, Direction.EAST, extend
                    ? PistonEvent.PistonMoveType.EXTEND : PistonEvent.PistonMoveType.RETRACT);
        }

        boolean blocked(PileData data) { return PistonGuard.blocks(event(), data); }
        @Override public void close() { world.reset(); }
    }
}
