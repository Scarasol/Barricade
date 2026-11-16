package com.scarasol.barricade.sampling;

import static org.junit.jupiter.api.Assertions.*;

import com.scarasol.barricade.pile.ModelPart;
import com.scarasol.barricade.pile.PileModels;
import com.scarasol.barricade.pile.PileModel;
import com.scarasol.barricade.pile.LegacyPileGenerator;
import com.scarasol.barricade.pile.GridPos;
import com.scarasol.barricade.pile.RegionPiece;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

class MaterialSamplerTest {
    @Test
    void samplingStoresOneAppearanceEnvelopeAndUsesItsCenterWithoutPhysicsBoxes() {
        var level = MemoryFixture.create(false);
        var envelope = new com.scarasol.barricade.pile.CollisionBox(.375f, 0, .375f, .625f, 1, .625f);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var sampler = new MaterialSampler(level, null, "", parts -> {
            assertEquals(1, parts.size());
            assertTrue(parts.get(0).blockState().startsWith("minecraft:oak_fence"));
            calls.incrementAndGet();
            return envelope;
        })) {
            var task = sampler.request(key("minecraft:oak_fence"));
            while (!task.isDone()) sampler.tick();
            var sample = task.join();
            assertEquals(List.of(envelope), sample.boxes());
            assertEquals(.5f, sample.model("post", 1).pivotY());
            assertEquals(1, sample.model("post", 1).span().y);
            assertSame(task, sampler.request(key("minecraft:oak_fence")));
            assertEquals(1, calls.get());
            assertEquals(0, level.chunkRequests());
        }
    }

    @Test
    void independentClientSamplersProduceEqualResultsDespiteOppositeRequestOrders() {
        List<String> states = PileModels.palette().stream().map(PileModel::blockState).toList();
        Map<String, MaterialSampler.Sample> forward = sampleInOrder(states);
        List<String> reverse = new ArrayList<>(states);
        java.util.Collections.reverse(reverse);
        assertEquals(forward, sampleInOrder(reverse));
    }
    private Map<String, MaterialSampler.Sample> sampleInOrder(List<String> states) {
        try (MaterialSampler sampler = MemoryFixture.sampler(MemoryFixture.create(false))) {
            Map<String, CompletableFuture<MaterialSampler.Sample>> tasks = new HashMap<>();
            states.forEach(state -> tasks.put(state, sampler.request(key(state))));
            for (int tick = 0; tasks.values().stream().anyMatch(task -> !task.isDone()) && tick < 64; tick++) sampler.tick();
            Map<String, MaterialSampler.Sample> result = new HashMap<>();
            tasks.forEach((state, task) -> result.put(state, task.join()));
            return Map.copyOf(result);
        }
    }
    @Test
    void allDefaultMaterialsAreFirstSamplesAndBedAndDoorAreDiscoveredWithoutAssemblyRules() {
        MemoryLevel level = MemoryFixture.create(false);
        try (MaterialSampler sampler = MemoryFixture.sampler(level)) {
            List<CompletableFuture<MaterialSampler.Sample>> tasks = new ArrayList<>();
            for (var model : PileModels.palette()) tasks.add(sampler.request(key(model.blockState())));
            var bed = sampler.request(key("minecraft:red_bed"));
            tasks.add(bed);
            for (int tick = 0; tasks.stream().anyMatch(task -> !task.isDone()) && tick < 32; tick++) sampler.tick();
            for (var task : tasks) assertFalse(task.join().parts().isEmpty());
            var door = sampler.request(key(PileModels.palette().stream()
                    .filter(model -> model.id().equals("oak_door")).findFirst().orElseThrow().blockState())).join();
            assertEquals(2, door.parts().size());
            assertEquals(2, door.model("door", 1).span().y, 0.00001);
            assertTrue(door.parts().stream().allMatch(part -> part.blockState().contains("facing=east")));
            assertEquals(2, bed.join().parts().size());
            assertEquals(2, bed.join().model("bed", 1).span().z, 0.00001);
            assertTrue(bed.join().parts().stream().allMatch(part -> !part.blockEntityData().isEmpty()));
            assertEquals(0, level.chunkRequests());
            System.out.println("Production sampler first-use batch: " + sampler.stats());
        }
    }

    @Test
    void additionalBlockEntitiesUseTheSameSamplingEntry() {
        try (MaterialSampler sampler = MemoryFixture.sampler(MemoryFixture.create(false))) {
            var tasks = List.of("minecraft:chest", "minecraft:bell", "minecraft:enchanting_table").stream()
                    .map(state -> sampler.request(key(state))).toList();
            for (int tick = 0; tasks.stream().anyMatch(task -> !task.isDone()) && tick < 32; tick++) sampler.tick();
            for (var task : tasks) {
                assertTrue(task.join().parts().stream().anyMatch(part -> !part.blockEntityData().isEmpty()));
                assertFalse(task.join().boxes().isEmpty());
            }
        }
    }

    @Test
    void requestsCoalesceAndDifferentStateNbtOrDirectionDoesNotAlias() {
        try (MaterialSampler sampler = MemoryFixture.sampler(MemoryFixture.create(false))) {
            var first = sampler.request(key("minecraft:oak_slab"));
            assertSame(first, sampler.request(key("minecraft:oak_slab")));
            var top = sampler.request(key("minecraft:oak_slab[type=top]"));
            var east = sampler.request(new MaterialSampler.Key("minecraft:oak_slab", new CompoundTag(), Direction.EAST));
            CompoundTag data = new CompoundTag();
            data.putString("CustomName", "\"test\"");
            var tagged = sampler.request(new MaterialSampler.Key("minecraft:oak_slab", data, Direction.NORTH));
            data.putString("CustomName", "\"changed\"");
            assertNotSame(first, top);
            assertNotSame(first, east);
            assertNotSame(first, tagged);
            for (int i = 0; i < 8 && !tagged.isDone(); i++) sampler.tick();
            assertSame(first, sampler.request(key("minecraft:oak_slab")));
            assertFalse(first.join().parts().equals(top.join().parts()));
        }
    }

    @Test
    void unsupportedChunkAndDelayedTickPathsFailAndStopCompletesWaitingRequests() {
        MemoryLevel level = MemoryFixture.create(false);
        assertThrows(UnsupportedOperationException.class, () -> level.getChunkSource());
        assertThrows(UnsupportedOperationException.class,
                () -> level.scheduleTick(BlockPos.ZERO, Blocks.STONE, 1));
        try (MaterialSampler sampler = MemoryFixture.sampler(level)) {
            var unsupported = sampler.request(key("minecraft:air"));
            sampler.tick();
            assertTrue(unsupported.isCompletedExceptionally());
            assertSame(unsupported, sampler.request(key("minecraft:air")));
            var waiting = sampler.request(key("minecraft:oak_slab"));
            sampler.close();
            assertTrue(waiting.isCompletedExceptionally());
        }
    }

    @Test
    void blockEntityReplacementDoesNotRemoveTheNewEntityAndPartNbtIsIsolated() {
        MemoryLevel level = MemoryFixture.create(false);
        BlockPos pos = new BlockPos(0, 8, 0);
        level.seed(pos, Blocks.CHEST.defaultBlockState());
        level.setBlock(pos, Blocks.BARREL.defaultBlockState(), 0);
        assertNotNull(level.getBlockEntity(pos));
        assertEquals(Blocks.BARREL, level.getBlockEntity(pos).getBlockState().getBlock());
        CompoundTag data = new CompoundTag();
        data.putString("CustomName", "\"initial\"");
        ModelPart part = new ModelPart("minecraft:barrel", 0, 0, 0, data);
        data.putString("CustomName", "\"mutated\"");
        part.blockEntityData().putInt("x", 50);
        assertEquals("\"initial\"", part.blockEntityData().getString("CustomName"));
        assertFalse(part.blockEntityData().contains("x"));
        assertEquals(part, ModelPart.load(part.save()));
        var stack = new net.minecraft.world.item.ItemStack(Blocks.BARREL);
        CompoundTag item = new CompoundTag();
        item.put("BlockEntityTag", part.blockEntityData());
        stack.setTag(item);
        assertTrue(level.applyItemData(pos, null, stack));
        assertEquals("initial", net.minecraft.network.chat.Component.Serializer.fromJson(
                level.getBlockEntity(pos).saveWithoutMetadata().getString("CustomName")).getString());
        item.put("BlockEntityTag", level.getBlockEntity(pos).saveWithoutMetadata());
        stack.setTag(item);
        assertFalse(level.applyItemData(pos, null, stack));
    }

    @Test
    void tagChangesRebuildSelectionAndPreserveMatchingSamples() {
        try (MaterialSampler sampler = MemoryFixture.sampler(MemoryFixture.create(false))) {
            var registry = BuiltInRegistries.BLOCK;
            var tag = PileModels.materialsTag();
            registry.getOrCreateTag(tag);
            var original = registry.getTags().collect(java.util.stream.Collectors.toMap(
                    com.mojang.datafixers.util.Pair::getFirst, pair -> pair.getSecond().stream().toList()));
            try {
                var selected = new HashMap<>(original);
                selected.put(tag, List.of(registry.wrapAsHolder(Blocks.OAK_SLAB),
                        registry.wrapAsHolder(Blocks.RED_BED)));
                registry.bindTags(selected);
                PileModels.tagsChanged();
                var first = sampler.prepare(PileModels.candidates(), PileModels.revision());
                assertFalse(first.isDone(), "First selection must wait for every requested material");
                for (int tick = 0; !first.isDone() && tick < 32; tick++) sampler.tick();
                assertEquals(2, first.join().size());
                var red = sampler.request(key("minecraft:red_bed"));
                selected.put(tag, List.of(registry.wrapAsHolder(Blocks.RED_BED),
                        registry.wrapAsHolder(Blocks.BLUE_BED)));
                registry.bindTags(selected);
                PileModels.tagsChanged();
                var next = sampler.prepare(PileModels.candidates(), PileModels.revision());
                assertFalse(next.isDone());
                for (int tick = 0; !next.isDone() && tick < 32; tick++) sampler.tick();
                assertEquals(2, next.join().size());
                assertTrue(next.join().stream().noneMatch(model -> model.blockState().contains("oak_slab")));
                assertTrue(next.join().stream().anyMatch(model -> model.blockState().contains("blue_bed")));
                assertSame(red, sampler.request(key("minecraft:red_bed")));
                assertEquals(2, first.join().size(), "A tag edit must not mutate an already prepared palette");
            } finally {
                registry.bindTags(original);
                PileModels.tagsChanged();
            }
        }
    }

    @Test
    void sampledFullSizeFurnitureParticipatesInFinalPacking() {
        try (MaterialSampler sampler = MemoryFixture.sampler(MemoryFixture.create(false))) {
            List<CompletableFuture<PileModel>> tasks = PileModels.palette().stream().map(model ->
                    sampler.request(key(model.blockState())).thenApply(sample -> sample.model(model.id(), model.weight())))
                    .toList();
            for (int tick = 0; tasks.stream().anyMatch(task -> !task.isDone()) && tick < 32; tick++) sampler.tick();
            List<PileModel> palette = tasks.stream().map(CompletableFuture::join).toList();
            Set<GridPos> domain = new HashSet<>();
            for (int x = 0; x < 5; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 3; z++) {
                domain.add(new GridPos(x, y, z));
            }
            int beds = 0;
            for (long seed : new long[]{7, 42, 100, 321}) {
                var result = LegacyPileGenerator.generate(new LegacyPileGenerator.Snapshot(seed, 0, domain, palette,
                        List.of(), List.of(), List.of(), List.of()));
                for (int pass = 1; result.continueFilling() && pass < 12; pass++) {
                    result = LegacyPileGenerator.generate(new LegacyPileGenerator.Snapshot(seed, pass, domain, palette,
                            result.pieces(), List.of(), List.of(), List.of(), result.work(), Set.of()));
                }
                List<RegionPiece> pieces = result.pieces();
                var panels = pieces.stream().filter(piece -> piece.piece().model().panel()).toList();
                long bedCount = pieces.stream().filter(piece -> piece.piece().model().id().equals("red_bed")).count();
                beds += (int) bedCount;
                System.out.printf("Sampled palette seed=%d pieces=%d panels=%d beds=%d%n",
                        seed, pieces.size(), panels.size(), bedCount);
                assertFalse(panels.isEmpty());
                for (RegionPiece panel : panels) {
                    assertTrue(LegacyPileGenerator.panelVisibility(panel, domain, pieces, List.of()) >= 3f / 9);
                }
                assertTrue(pieces.stream().allMatch(piece -> piece.piece().scale() >= 0.95f
                        && piece.piece().scale() <= 1.05f && LegacyPileGenerator.fitsDomain(piece.colliders(), domain)));
            }
            assertTrue(beds > 0, "Beds must get a placement opportunity before small filler consumes the region");
        }
    }

    private MaterialSampler.Key key(String state) {
        return new MaterialSampler.Key(state, new CompoundTag(), Direction.NORTH);
    }
}
