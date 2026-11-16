package com.scarasol.barricade.window;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.sampling.MemoryFixture;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowBoardsChunkRecoveryTest {
    private static WindowSealBlock wood, iron;
    private static net.minecraft.core.Registry<Biome> biomes;
    @TempDir Path directory;

    @BeforeAll
    @SuppressWarnings("deprecation")
    static void bootstrap() {
        biomes = MemoryFixture.create(false).registryAccess().registryOrThrow(Registries.BIOME);
        var registry = (ForgeRegistry<Block>) ForgeRegistries.BLOCKS;
        boolean locked = registry.isLocked();
        registry.unfreeze();
        ((MappedRegistry<Block>) BuiltInRegistries.BLOCK).unfreeze();
        try {
            wood = new WindowBoardsBlock();
            registry.register(-1, new ResourceLocation("barricade", "recovery_wood_fixture"), wood);
            iron = new WindowBarsBlock();
            registry.register(-1, new ResourceLocation("barricade", "recovery_iron_fixture"), iron);
        } finally {
            BuiltInRegistries.BLOCK.freeze();
            if (locked) registry.freeze();
        }
        for (var block : List.of(wood, iron))
            block.getStateDefinition().getPossibleStates().forEach(BlockState::initCache);
    }
    private static ProtoChunk chunk(int x, int z) {
        return new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY,
                LevelHeightAccessor.create(-64, 384), biomes, null);
    }
    private static void set(ChunkAccess chunk, BlockPos pos, BlockState state) {
        chunk.getSection(chunk.getSectionIndex(pos.getY()))
                .setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state);
    }
    private static BlockState state(SealMaterial material, Direction facing) {
        return (material == SealMaterial.IRON ? iron : wood).defaultBlockState().setValue(WindowSealBlock.FACING, facing);
    }
    private static void reconcile(ChunkAccess chunk, WindowBoardsData data, Set<BlockPos> pending) {
        Map<BlockPos, WindowBoardsData.Observation> observed = new LinkedHashMap<>();
        for (BlockPos pos : new TreeSet<>(pending)) {
            BlockState state = chunk.getBlockState(pos);
            observed.put(pos, state.getBlock() instanceof WindowSealBlock seal
                    ? new WindowBoardsData.Observation(state.getValue(WindowSealBlock.FACING), seal.material()) : null);
        }
        WindowBoardsService.reconcile(data, observed, RandomSource.create(71));
    }
    private static void load(ChunkAccess chunk, WindowBoardsData data) {
        Set<BlockPos> pending = new HashSet<>();
        WindowBoardsService.collectLoaded(chunk, data, pending);
        reconcile(chunk, data, pending);
    }
    @Test void unreadableStoreRebuildsExistingBlocksInBothMaterialsAndAllSixFacings() throws Exception {
        Path file = directory.resolve(WindowBoardsData.NAME + ".dat");
        for (Direction facing : Direction.values()) for (SealMaterial material : SealMaterial.values()) {
            ProtoChunk chunk = chunk(-1, -1);
            boolean horizontal = facing.getAxis() == Direction.Axis.Y;
            BoardPlane plane = new BoardPlane(facing, horizontal ? 16 : -1);
            Set<BoardCell> shape = BoardLayoutTest.rectangle(-3, horizontal ? -3 : 14, 3, 3);
            WindowBoardsData previous = new WindowBoardsData();
            for (BoardCell cell : new TreeSet<>(shape)) {
                BlockPos pos = plane.block(cell);
                set(chunk, pos, state(material, facing));
                previous.place(pos, facing, UUID.randomUUID(), 42, material);
            }
            CompoundTag invalid = previous.save(new CompoundTag());
            invalid.getList("Groups", Tag.TAG_COMPOUND).getCompound(0).remove("Material");
            CompoundTag root = new CompoundTag();
            root.put("data", invalid);
            root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
            NbtIo.writeCompressed(root, file.toFile());
            var storage = new DimensionDataStorage(directory.toFile(), DataFixers.getDataFixer());
            WindowBoardsData recovered = WindowBoardsData.open(storage, file);
            assertTrue(recovered.groups().isEmpty());
            load(chunk, recovered);
            assertEquals(1, recovered.groups().size());
            BoardGroup group = recovered.groups().iterator().next();
            assertEquals(plane, group.plane());
            assertEquals(material, group.material());
            assertEquals(shape, group.shape());
            assertEquals(BoardLayout.boundary(shape), group.supports());
            assertTrue(group.damage().isEmpty());
            assertNotNull(group.layoutKey());
            assertFalse(BoardLayout.visible(BoardLayout.generate(group), group, Set.of()).isEmpty());
            for (BoardCell cell : shape) assertEquals(group.id(), recovered.activeAt(plane.block(cell)));
            storage.save();
            var reloaded = WindowBoardsData.open(
                    new DimensionDataStorage(directory.toFile(), DataFixers.getDataFixer()), file);
            assertEquals(recovered.save(new CompoundTag()), reloaded.save(new CompoundTag()));
            CompoundTag saved = reloaded.save(new CompoundTag());
            load(chunk, reloaded);
            assertEquals(saved, reloaded.save(new CompoundTag()), "Revisiting a rebuilt chunk is not maintenance");
        }
    }
    @Test void rediscoveryDoesNotResetValidDamageOrSplitOwnership() {
        for (SealMaterial material : SealMaterial.values()) {
            ProtoChunk chunk = chunk(0, 0);
            WindowBoardsData data = new WindowBoardsData();
            BoardPlane plane = new BoardPlane(Direction.NORTH, 0);
            Set<BoardCell> shape = new HashSet<>(BoardLayoutTest.rectangle(0, 0, 2, 2));
            shape.addAll(BoardLayoutTest.rectangle(6, 0, 3, 3));
            for (BoardCell cell : shape) set(chunk, plane.block(cell), state(material, plane.facing()));
            load(chunk, data);
            for (BoardCell cell : List.of(new BoardCell(1, 0), new BoardCell(0, 1), new BoardCell(7, 1))) {
                data.remove(plane.block(cell));
                set(chunk, plane.block(cell), Blocks.AIR.defaultBlockState());
            }
            assertEquals(3, data.groups().size());
            assertEquals(2, data.groups().stream().filter(group -> group.remnant() != null).count());
            assertEquals(1, data.groups().stream().filter(group -> !group.damage().isEmpty()).count());
            CompoundTag before = data.save(new CompoundTag());
            data.setDirty(false);
            load(chunk, data);
            assertEquals(before, data.save(new CompoundTag()));
            assertFalse(data.isDirty());
        }
    }
    @Test void indexedMissingBlocksStillReconcileWhenTheChunkContainsNoSeals() {
        var chunk = chunk(-1, -1);
        var data = new WindowBoardsData();
        BlockPos pos = new BlockPos(-1, -32, -1);
        data.place(pos, Direction.UP, UUID.randomUUID(), 42);
        Set<BlockPos> pending = new HashSet<>();
        WindowBoardsService.collectLoaded(chunk, data, pending);
        assertEquals(Set.of(pos), pending);
        reconcile(chunk, data, pending);
        assertTrue(data.groups().isEmpty());
    }
    @Test void discoveryJoinsAcrossChunkEdgesAsChunksArriveWithoutTouchingOtherChunks() {
        for (SealMaterial material : SealMaterial.values()) {
            var left = chunk(-1, 0);
            var right = chunk(0, 0);
            var data = new WindowBoardsData();
            BoardPlane plane = new BoardPlane(Direction.NORTH, 0);
            Set<BoardCell> shape = BoardLayoutTest.rectangle(-1, 15, 2, 2);
            for (BoardCell cell : shape) {
                BlockPos pos = plane.block(cell);
                set(pos.getX() < 0 ? left : right, pos, state(material, plane.facing()));
            }
            load(left, data);
            assertEquals(2, data.activeInChunk(left.getPos().toLong()).size());
            assertTrue(data.activeInChunk(right.getPos().toLong()).isEmpty());
            load(right, data);
            assertEquals(1, data.groups().size());
            BoardGroup group = data.groups().iterator().next();
            assertEquals(shape, group.shape());
            assertEquals(BoardLayout.boundary(shape), group.supports());
            CompoundTag saved = data.save(new CompoundTag());
            load(left, data);
            load(right, data);
            assertEquals(saved, data.save(new CompoundTag()));
        }
    }
    @Test void paletteFilteringSkipsUnrelatedSectionsAndUsesFullWorldCoordinates() {
        var chunk = chunk(-2, 3);
        var counted = new LevelChunkSection(biomes) {
            int reads;
            @Override public BlockState getBlockState(int x, int y, int z) {
                reads++;
                return super.getBlockState(x, y, z);
            }
        };
        chunk.getSections()[0] = counted;
        BlockPos pos = new BlockPos(-17, -64, 63);
        set(chunk, pos, Blocks.STONE.defaultBlockState());
        var data = new WindowBoardsData();
        Set<BlockPos> pending = new HashSet<>();
        WindowBoardsService.collectLoaded(chunk, data, pending);
        assertTrue(pending.isEmpty());
        assertEquals(0, counted.reads, "Chunks without seal palettes must not be scanned block by block");
        set(chunk, pos, wood.defaultBlockState());
        BlockPos top = new BlockPos(-32, 319, 48);
        set(chunk, top, iron.defaultBlockState());
        WindowBoardsService.collectLoaded(chunk, data, pending);
        assertEquals(Set.of(pos, top), pending);
        assertEquals(4096, counted.reads, "Only the matching section is scanned");
    }
}
