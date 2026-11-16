package com.scarasol.barricade.pile;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.block.BarricadeBlock;
import com.scarasol.barricade.recipe.*;
import com.scarasol.barricade.sampling.MemoryFixture;
import java.nio.file.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
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

class PileRecoveryTest {
    private static final String FENCE_TAG = "example:fences";
    private static final PaletteSpec DEFAULT = PaletteSpec.create(List.of(
            new PileModels.Candidate("oak", "minecraft:oak_planks", 1)), "current");
    private static final PaletteSpec FENCES = PaletteSpec.create(List.of(
            new PileModels.Candidate("fence", "minecraft:oak_fence", 1)), "current", FENCE_TAG, DEFAULT);
    private static BarricadeBlock block;
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
            block = new BarricadeBlock();
            registry.register(-1, new ResourceLocation("barricade", "pile_recovery_fixture"), block);
        } finally {
            BuiltInRegistries.BLOCK.freeze();
            if (locked) registry.freeze();
        }
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
    private static PaletteSpec resolve(String source) { return FENCE_TAG.equals(source) ? FENCES : DEFAULT; }
    private Path file() { return directory.resolve("barricade_embedded_v1.dat"); }
    private DimensionDataStorage storage() {
        return new DimensionDataStorage(directory.toFile(), DataFixers.getDataFixer());
    }
    private void write(CompoundTag data) throws Exception {
        CompoundTag wrapper = new CompoundTag();
        wrapper.put("data", data);
        wrapper.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        NbtIo.writeCompressed(wrapper, file().toFile());
    }
    private static void rebuild(ChunkAccess chunk, PileData data) {
        PileRecovery.rebuild(chunk, data, PileRecoveryTest::resolve, RandomSource.create(42));
    }
    private static PaletteSpec at(PileData data, BlockPos pos) {
        return data.palette(data.snapshot(data.regionAt(pos)).definition().palette());
    }

    @Test void missingCorruptAndAlreadyClearedStoresRebuildExistingBlocksAndGeneratePieces() throws Exception {
        var chunk = chunk(-1, 1);
        BlockPos pos = new BlockPos(-1, -32, 17);
        set(chunk, pos, block.defaultBlockState());
        set(chunk, pos.above(), block.defaultBlockState());
        set(chunk, pos.west(), Blocks.OAK_PLANKS.defaultBlockState());
        for (String source : List.of("missing", "corrupt", "already-cleared")) {
            Files.deleteIfExists(file());
            if (source.equals("corrupt")) Files.write(file(), new byte[]{1, 2, 3});
            if (source.equals("already-cleared")) write(new PileData().save(new CompoundTag()));
            var storage = storage();
            PileData data = PileData.loadCurrent(storage, file(), PileRecoveryTest::resolve);
            assertEquals(0, data.cellCount(), source);
            rebuild(chunk, data);
            assertEquals(2, data.cellCount(), source);
            assertEquals(1, data.regionCount(), source);
            assertEquals(DEFAULT, at(data, pos));
            var region = data.snapshot(data.regionAt(pos));
            var model = new PileModel("oak", "minecraft:oak_planks", .45f, .35f, .4f, 1, List.of(CollisionBox.CUBE));
            var field = new SpatialField("test", region.definition(), List.of(model), FieldOracle::solve);
            assertFalse(field.query(region.domain(), region.baseline()).isEmpty(),
                    "Recovery must supply real generator inputs, not just collision blocks: " + source);
            assertEquals(Blocks.OAK_PLANKS.defaultBlockState(), chunk.getBlockState(pos.west()));
            storage.save();
            PileData reloaded = PileData.loadCurrent(storage(), file(), PileRecoveryTest::resolve);
            assertEquals(data.save(new CompoundTag()), reloaded.save(new CompoundTag()));
            CompoundTag before = reloaded.save(new CompoundTag());
            PileRecovery.rebuild(chunk, reloaded, ignored -> fail("A healthy chunk must not resolve defaults"),
                    RandomSource.create(7));
            assertEquals(before, reloaded.save(new CompoundTag()));
            assertFalse(reloaded.isDirty());
        }
    }

    @Test void partialFailureRetainsReadableTagAndLeavesHealthyDamageAndEmbeddingUntouched() throws Exception {
        var chunk = chunk(0, 0);
        BlockPos healthy = new BlockPos(1, 80, 1), bad = healthy.east(6);
        UUID goodId = new UUID(1, 2), badId = new UUID(3, 4);
        PileData original = new PileData();
        original.add(healthy, goodId, 42, DEFAULT);
        original.add(healthy.above(), goodId, 42, DEFAULT);
        original.remove(healthy.above());
        original.addEmbedded(healthy);
        set(chunk, healthy, Blocks.FURNACE.defaultBlockState());
        original.add(bad, badId, 100, FENCES);
        set(chunk, bad, block.defaultBlockState());
        CompoundTag saved = original.save(new CompoundTag());
        saved.getList("Regions", Tag.TAG_COMPOUND).getCompound(1).remove("Baseline");
        write(saved);
        var storage = storage();
        PileData restored = PileData.loadCurrent(storage, file(), PileRecoveryTest::resolve);
        assertEquals(FENCES, at(restored, bad));
        assertNotEquals(badId, restored.regionAt(bad));
        rebuild(chunk, restored);
        assertEquals(original.snapshot(goodId), restored.snapshot(goodId));
        assertEquals(FENCES, at(restored, bad));
        assertEquals(Blocks.FURNACE.defaultBlockState(), chunk.getBlockState(healthy));
        assertFalse(restored.contains(healthy.above()));
        storage.save();
        assertEquals(restored.save(new CompoundTag()),
                PileData.loadCurrent(storage(), file(), PileRecoveryTest::resolve).save(new CompoundTag()));
    }

    @Test void damagedPaletteStillYieldsItsTagWithoutDecodingItsOldCandidateGeometry() throws Exception {
        BlockPos pos = new BlockPos(2, 80, 2);
        PileData original = new PileData();
        original.add(pos, UUID.randomUUID(), 100, FENCES);
        CompoundTag saved = original.save(new CompoundTag());
        CompoundTag palette = saved.getList("Palettes", Tag.TAG_COMPOUND).getCompound(0);
        CompoundTag nested = new CompoundTag();
        nested.put("Fallback", new CompoundTag());
        palette.put("Fallback", nested);
        write(saved);
        PileData restored = PileData.loadCurrent(storage(), file(), PileRecoveryTest::resolve);
        assertEquals(FENCES, at(restored, pos));
        assertTrue(restored.snapshot(restored.regionAt(pos)).mask().isEmpty());
        assertFalse(restored.hasEmbedded());
    }

    @Test void historicalMetadataYieldsOnlyTheTagAndMembershipNotAnOldLayoutOrAlgorithm() throws Exception {
        BlockPos pos = new BlockPos(-1, 80, 1);
        for (boolean hasTag : List.of(true, false)) {
            CompoundTag root = new CompoundTag(), region = new CompoundTag(), palette = new CompoundTag(), recipe = new CompoundTag();
            root.putInt("Format", 1);
            region.putLong("Head", 19);
            region.putLong("Seed", 42);
            region.putLongArray("Domain", new long[]{pos.asLong()});
            palette.putString("Id", "discarded-palette");
            if (hasTag) palette.putString("SourceTag", FENCE_TAG);
            palette.putString("Candidates", "unreadable historical geometry");
            recipe.putLong("Id", 19);
            recipe.putString("Palette", "discarded-palette");
            recipe.putInt("Algorithm", -100);
            ListTag regions = new ListTag(), palettes = new ListTag(), recipes = new ListTag();
            regions.add(region); palettes.add(palette); recipes.add(recipe);
            root.put("Regions", regions); root.put("Palettes", palettes); root.put("Recipes", recipes);
            write(root);
            var storage = storage();
            PileData restored = PileData.loadCurrent(storage, file(), PileRecoveryTest::resolve);
            assertEquals(hasTag ? FENCES : DEFAULT, at(restored, pos));
            assertEquals(FieldDefinition.ALGORITHM, restored.snapshot(restored.regionAt(pos)).definition().algorithm());
            var chunk = chunk(-1, 0);
            set(chunk, pos, block.defaultBlockState());
            rebuild(chunk, restored);
            storage.save();
            CompoundTag current = NbtIo.readCompressed(file().toFile()).getCompound("data");
            assertEquals(3, current.getInt("Format"));
            assertFalse(current.contains("Recipes"));
            assertFalse(current.contains("Contexts"));
            assertEquals(restored.save(new CompoundTag()), current);
        }
    }

    @Test void unreadableTagOrEmptyPoolUsesTheExistingProductionDefaultSelection() throws Exception {
        var registry = BuiltInRegistries.BLOCK;
        registry.getOrCreateTag(PileModels.materialsTag());
        var original = registry.getTags().collect(java.util.stream.Collectors.toMap(
                com.mojang.datafixers.util.Pair::getFirst, pair -> pair.getSecond().stream().toList()));
        try {
            var selected = new HashMap<>(original);
            selected.put(PileModels.materialsTag(), List.of(registry.wrapAsHolder(Blocks.OAK_PLANKS)));
            registry.bindTags(selected); PileModels.tagsChanged();
            for (String source : List.of("", "INVALID TAG", "example:missing_material_pool")) {
                BlockPos pos = new BlockPos(1, 80, 1);
                PileData data = new PileData();
                data.add(pos, UUID.randomUUID(), 42, DEFAULT);
                CompoundTag root = data.save(new CompoundTag());
                root.getList("Regions", Tag.TAG_COMPOUND).getCompound(0).putInt("Algorithm", -1);
                root.getList("Palettes", Tag.TAG_COMPOUND).getCompound(0).putString("SourceTag", source);
                write(root);
                PileData restored = PileData.loadCurrent(storage(), file());
                PaletteSpec resolved = at(restored, pos);
                if (source.equals("example:missing_material_pool")) {
                    assertTrue(resolved.candidates().isEmpty());
                    assertEquals(PileService.palette(), resolved.fallback());
                } else assertEquals(PileService.palette(), resolved);
                try (var sampler = MemoryFixture.sampler(MemoryFixture.create(false))) {
                    var future = sampler.prepare(resolved);
                    for (int i = 0; i < 256 && !future.isDone(); i++) sampler.tick();
                    assertTrue(future.isDone());
                    assertFalse(future.join().isEmpty(), source);
                    assertTrue(future.join().stream().allMatch(model -> model.blockState().startsWith("minecraft:oak_planks")));
                }
            }
        } finally { registry.bindTags(original); PileModels.tagsChanged(); }
    }

    @Test void anUnloadedRegionsExtractedTagSurvivesSavingAndTheNextServerLoad() throws Exception {
        BlockPos left = new BlockPos(-1, 80, 1), right = left.east();
        PileData original = new PileData();
        original.add(left, UUID.randomUUID(), 42, FENCES);
        original.add(right, UUID.randomUUID(), 42, FENCES);
        CompoundTag saved = original.save(new CompoundTag());
        saved.getList("Regions", Tag.TAG_COMPOUND).getCompound(0).putInt("Algorithm", -1);
        write(saved);
        var leftChunk = chunk(-1, 0);
        var rightChunk = chunk(0, 0);
        set(leftChunk, left, block.defaultBlockState());
        set(rightChunk, right, block.defaultBlockState());
        var storage = storage();
        PileData restored = PileData.loadCurrent(storage, file(), PileRecoveryTest::resolve);
        rebuild(leftChunk, restored);
        storage.save();
        PileData reloaded = PileData.loadCurrent(storage(), file(), PileRecoveryTest::resolve);
        PileRecovery.rebuild(rightChunk, reloaded, ignored -> fail("Extracted tag must already be saved"),
                RandomSource.create(1));
        assertEquals(FENCES, at(reloaded, right));
        assertEquals(reloaded.regionAt(left), reloaded.regionAt(right));
        assertFalse(reloaded.isDirty());
    }

    @Test void entirelyMissingMembershipJoinsAcrossChunkEdgesAsChunksArrive() {
        BlockPos left = new BlockPos(-1, 80, 1), right = left.east();
        var a = chunk(-1, 0);
        var b = chunk(0, 0);
        set(a, left, block.defaultBlockState());
        set(b, right, block.defaultBlockState());
        PileData data = new PileData();
        rebuild(a, data);
        assertEquals(Set.of(left), data.cellsInChunk(a.getPos().toLong()));
        assertTrue(data.cellsInChunk(b.getPos().toLong()).isEmpty());
        rebuild(b, data);
        assertEquals(1, data.regionCount());
        assertEquals(data.regionAt(left), data.regionAt(right));
        assertEquals(Set.of(right), data.cellsInChunk(b.getPos().toLong()));
    }

    @Test void rediscoveryPrunesStaleMembershipWithoutDeletingNativeBlocksOrResurrectingAir() {
        var chunk = chunk(0, 0);
        BlockPos pos = new BlockPos(1, 80, 1);
        PileData data = new PileData();
        data.add(pos, UUID.randomUUID(), 42, DEFAULT);
        data.add(pos.east(), UUID.randomUUID(), 42, DEFAULT);
        set(chunk, pos, Blocks.FURNACE.defaultBlockState());
        rebuild(chunk, data);
        assertEquals(0, data.cellCount());
        assertEquals(0, data.regionCount());
        assertTrue(data.cellsInChunk(chunk.getPos().toLong()).isEmpty());
        assertEquals(Blocks.FURNACE.defaultBlockState(), chunk.getBlockState(pos));
        assertTrue(chunk.getBlockState(pos.east()).isAir());
    }

    @Test void paletteFilteringSkipsOtherSectionsAndHandlesNegativeAndMaximumHeightCoordinates() {
        var chunk = chunk(-2, 3);
        var counted = new LevelChunkSection(biomes) {
            int reads;
            @Override public BlockState getBlockState(int x, int y, int z) {
                reads++;
                return super.getBlockState(x, y, z);
            }
        };
        chunk.getSections()[0] = counted;
        BlockPos pos = new BlockPos(-17, -64, 63), top = new BlockPos(-32, 319, 48);
        set(chunk, pos, Blocks.STONE.defaultBlockState());
        PileData data = new PileData();
        rebuild(chunk, data);
        assertEquals(0, data.cellCount());
        assertEquals(0, counted.reads);
        set(chunk, pos, block.defaultBlockState());
        set(chunk, top, block.defaultBlockState());
        rebuild(chunk, data);
        assertEquals(4096, counted.reads);
        assertEquals(Set.of(pos, top), data.cellsInChunk(chunk.getPos().toLong()));
    }

    @Test void measures512CellMetadataRecoveryWithoutRepeatedWorldOrGeneratorWork() {
        var chunk = chunk(0, 0);
        for (int y = 0; y < 8; y++) for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++)
            set(chunk, new BlockPos(x, y, z), block.defaultBlockState());
        long[] cold = new long[9], healthy = new long[9];
        for (int round = -3; round < cold.length; round++) {
            PileData data = new PileData();
            long start = System.nanoTime();
            rebuild(chunk, data);
            long end = System.nanoTime();
            assertEquals(512, data.cellCount());
            CompoundTag before = data.save(new CompoundTag());
            data.setDirty(false);
            long repeat = System.nanoTime();
            rebuild(chunk, data);
            long done = System.nanoTime();
            assertEquals(before, data.save(new CompoundTag()));
            assertFalse(data.isDirty());
            if (round >= 0) { cold[round] = end - start; healthy[round] = done - repeat; }
        }
        Arrays.sort(cold); Arrays.sort(healthy);
        System.out.printf("PILE RECOVERY 512 cells metadata-only median rebuild=%.3f ms healthy-revisit=%.3f ms%n",
                cold[4] / 1_000_000.0, healthy[4] / 1_000_000.0);
    }
}
