package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;

import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.*;
import com.scarasol.barricade.sampling.MemoryFixture;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.joml.Quaternionf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ReplayBatchTest {
    private static final BlockPos ZERO = new BlockPos(100_000, 80, -250_000);
    private static final UUID REGION = new UUID(1, 2), OTHER = new UUID(3, 4);
    private static final PileModel MODEL = new PileModel("test", "minecraft:oak_planks", 1, 1, 1, 1,
            List.of(new CollisionBox(0, 0, 0, 1, 1, 1)));
    private final PaletteSpec palette = PaletteSpec.create(
            List.of(new PileModels.Candidate("oak", "minecraft:oak_planks", 1)), "resources");

    @BeforeAll static void initialize() { MemoryFixture.create(false).reset(); }

    private ArchivedPileRecipe node(long id, List<Long> parents, boolean active) {
        return new ArchivedPileRecipe(id, 42, parents, Set.of(ZERO, ZERO.east()),
                active ? Set.of(ZERO) : Set.of(), Set.of(), palette.id(), ArchivedTerrainSnapshot.EMPTY, List.of());
    }
    private ArchivedPileReplay.Prepared input(ArchivedPileRecipe node) {
        List<ArchivedPileReplay.DamageShape> damage = node.damage().stream().map(edit ->
                new ArchivedPileReplay.DamageShape(edit.pos(), List.of(OrientedBox.axisAligned(0, 0, 0, 1, 1, 1)))).toList();
        return new ArchivedPileReplay.Prepared(node, node.active().isEmpty() ? List.of() : List.of(MODEL),
                List.of(), List.of(), damage);
    }
    private ArchivedRecipeStore store(List<ArchivedPileRecipe> nodes, Map<UUID, Long> heads) {
        CompoundTag payload = new CompoundTag();
        ArchivedRecipeArchive.write(payload, nodes);
        ListTag rules = new ListTag(), regions = new ListTag();
        rules.add(palette.save());
        payload.put("Palettes", rules);
        heads.forEach((id, head) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", id);
            entry.putLong("Head", head);
            regions.add(entry);
        });
        payload.put("Heads", regions);
        ArchivedRecipeStore store = new ArchivedRecipeStore();
        store.accept(payload);
        return store;
    }
    private ArchivedPileReplay.ObjectPiece piece(long birth, float x) {
        return new ArchivedPileReplay.ObjectPiece(new ArchivedPileReplay.Key(birth, 0), ZERO,
                PilePiece.create(MODEL, x, 0.5f, 0.5f, 1, new Quaternionf()));
    }
    private LayoutResult noGeneration(LayoutInput input) {
        fail("Lightweight records must never invoke the layout generator");
        return null;
    }
    private void success(ArchivedReplayBatch.Outcome outcome) {
        if (outcome.error() != null) throw new AssertionError("Failed recipe " + outcome.failed(), outcome.error());
        assertEquals(0, outcome.failed());
        assertNull(outcome.error());
    }

    @Test void ninetySevenLightEditsUseTwoBoundedBatchesAndMatchEverySequentialResult() {
        List<ArchivedPileRecipe> nodes = new ArrayList<>();
        nodes.add(node(1, List.of(), true));
        for (long id = 2; id <= 98; id++) {
            ArchivedPileRecipe edit = node(id, List.of(id - 1), false);
            if (id == 20 || id == 80) {
                BlockPos damage = id == 20 ? ZERO : ZERO.east();
                edit = new ArchivedPileRecipe(id, edit.seed(), edit.parents(), edit.domain(), edit.active(), edit.repack(),
                        edit.palette(), edit.terrain(), List.of(new ArchivedPileRecipe.Damage(damage, true, "", ArchivedTerrainSnapshot.EMPTY)));
            }
            nodes.add(edit);
        }
        ArchivedRecipeStore store = store(nodes, Map.of(REGION, 98L));
        Map<Long, List<ArchivedPileReplay.ObjectPiece>> results = new LinkedHashMap<>();
        results.put(1L, List.of(piece(1, 0.5f), piece(2, 1.5f)));
        Map<Long, List<ArchivedPileReplay.ObjectPiece>> sequential = new LinkedHashMap<>(results);
        for (ArchivedPileRecipe node : nodes.subList(1, nodes.size()))
            sequential.put(node.id(), ArchivedPileReplay.replay(input(node),
                    node.parents().stream().map(sequential::get).toList(), List.of(), this::noGeneration));

        AtomicInteger preparations = new AtomicInteger();
        int batches = 0;
        ArchivedReplayBatch batch;
        while ((batch = ArchivedReplayBatch.prepare(store, results, new HashSet<>(), node -> {
            preparations.incrementAndGet();
            return input(node);
        }, () -> 0)) != null) {
            assertEquals(batches == 0 ? 64 : 33, batch.size());
            var outcome = batch.replay(this::noGeneration);
            success(outcome);
            results.putAll(outcome.completed());
            batches++;
        }
        assertEquals(2, batches);
        assertEquals(97, preparations.get(), "No edits are skipped or prepared twice");
        assertEquals(sequential, results);
        assertTrue(results.get(98L).isEmpty(), "Damage is preserved; batching never refills holes");
        System.out.println("History dispatch regression: 97 sequential records -> 2 bounded worker batches; identical results");
    }

    @Test void generationAndTrailingEditsShareABatchButStopBeforeTheNextGeneration() {
        List<ArchivedPileRecipe> nodes = List.of(node(1, List.of(), true), node(2, List.of(1L), false),
                node(3, List.of(2L), false), node(4, List.of(3L), true));
        ArchivedRecipeStore store = store(nodes, Map.of(REGION, 4L));
        Map<Long, List<ArchivedPileReplay.ObjectPiece>> results = new HashMap<>();
        List<Long> prepared = new ArrayList<>();
        ArchivedReplayBatch batch = ArchivedReplayBatch.prepare(store, results, new HashSet<>(), node -> {
            prepared.add(node.id());
            return input(node);
        }, () -> 0);
        assertNotNull(batch);
        assertEquals(List.of(1L, 2L, 3L), prepared);
        AtomicInteger calls = new AtomicInteger();
        var outcome = batch.replay(layout -> {
            calls.incrementAndGet();
            return new LayoutResult(List.of(new RegionPiece(new GridPos(0, 0, 0),
                    PilePiece.create(MODEL, 0.5f, 0.5f, 0.5f, 1, new Quaternionf()))), false, Set.of());
        });
        success(outcome);
        assertEquals(1, calls.get());
        assertEquals(List.of(1L, 2L, 3L), new ArrayList<>(outcome.completed().keySet()));
        assertEquals(outcome.completed().get(1L), outcome.completed().get(3L));
        results.putAll(outcome.completed());
        ArchivedReplayBatch next = ArchivedReplayBatch.prepare(store, results, new HashSet<>(), this::input, () -> 0);
        assertNotNull(next);
        assertEquals(4, next.firstId());
        assertEquals(1, next.size());
    }

    @Test void preparationYieldsAtTheSoftBudgetAndResumesAtTheNextRecord() {
        ArchivedRecipeStore store = store(List.of(node(1, List.of(), false), node(2, List.of(1L), false),
                node(3, List.of(2L), false)), Map.of(REGION, 3L));
        AtomicLong clock = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        Function<ArchivedPileRecipe, ArchivedPileReplay.Prepared> prepare = node -> {
            calls.incrementAndGet();
            clock.addAndGet(ArchivedReplayBatch.PREPARATION_NANOS);
            return input(node);
        };
        Map<Long, List<ArchivedPileReplay.ObjectPiece>> results = new HashMap<>();
        ArchivedReplayBatch first = ArchivedReplayBatch.prepare(store, results, new HashSet<>(), prepare, clock::get);
        assertNotNull(first);
        assertEquals(1, first.size());
        assertEquals(1, calls.get());
        results.putAll(first.replay(this::noGeneration).completed());
        ArchivedReplayBatch second = ArchivedReplayBatch.prepare(store, results, new HashSet<>(), prepare, clock::get);
        assertNotNull(second);
        assertEquals(2, second.firstId());
        assertEquals(2, calls.get());
    }

    @Test void missingMaterialsDoNotScheduleTheRecordOrAnyOfItsChildren() {
        ArchivedRecipeStore store = store(List.of(node(1, List.of(), true), node(2, List.of(1L), false)),
                Map.of(REGION, 2L));
        List<Long> attempted = new ArrayList<>();
        assertNull(ArchivedReplayBatch.prepare(store, Map.of(), new HashSet<>(), node -> {
            attempted.add(node.id());
            return null;
        }, () -> 0));
        assertEquals(List.of(1L), attempted);
        ArchivedReplayBatch ready = ArchivedReplayBatch.prepare(store, Map.of(), new HashSet<>(), this::input, () -> 0);
        assertNotNull(ready);
        assertEquals(2, ready.size());
    }

    @Test void mergeAndNeighborDependenciesKeepTheirDepthFirstOrderAndDeduplicateObjects() {
        ArchivedPileRecipe merged = node(3, List.of(1L, 2L), false);
        ArchivedPileRecipe neighbor = node(4, List.of(1L), false);
        ArchivedPileRecipe head = new ArchivedPileRecipe(5, 42, List.of(3L), merged.domain(), Set.of(), Set.of(),
                palette.id(), ArchivedTerrainSnapshot.EMPTY, List.of(), List.of(4L));
        ArchivedRecipeStore store = store(List.of(head, neighbor, merged, node(2, List.of(1L), false),
                node(1, List.of(), true)), Map.of(REGION, 5L));
        var shared = piece(1, 0.5f);
        var other = piece(2, 1.5f);
        Map<Long, List<ArchivedPileReplay.ObjectPiece>> results = new HashMap<>();
        results.put(1L, List.of(shared));
        results.put(2L, List.of(shared, other));
        List<Long> order = new ArrayList<>();
        ArchivedReplayBatch batch = ArchivedReplayBatch.prepare(store, results, new HashSet<>(), node -> {
            order.add(node.id());
            return input(node);
        }, () -> 0);
        assertEquals(List.of(3L, 4L, 5L), order);
        results.clear();
        var outcome = Objects.requireNonNull(batch).replay(this::noGeneration);
        success(outcome);
        assertEquals(List.of(shared, other), outcome.completed().get(5L));
        assertEquals(List.of(shared), outcome.completed().get(4L));
    }

    @Test void failureKeepsTheSuccessfulPrefixAndDoesNotPoisonAnIndependentRegion() {
        ArchivedPileRecipe invalid = new ArchivedPileRecipe(3, 42, List.of(2L), Set.of(ZERO.east(100)), Set.of(), Set.of(),
                palette.id(), ArchivedTerrainSnapshot.EMPTY, List.of());
        ArchivedRecipeStore store = store(List.of(node(1, List.of(), true), node(2, List.of(1L), false), invalid,
                node(4, List.of(3L), false), node(10, List.of(1L), false)), Map.of(REGION, 4L, OTHER, 10L));
        Map<Long, List<ArchivedPileReplay.ObjectPiece>> results = new HashMap<>();
        results.put(1L, List.of(piece(1, 0.5f)));
        Set<Long> failed = new HashSet<>();
        ArchivedReplayBatch batch = ArchivedReplayBatch.prepare(store, results, failed, this::input, () -> 0);
        var outcome = Objects.requireNonNull(batch).replay(this::noGeneration);
        assertEquals(3, outcome.failed());
        assertInstanceOf(IllegalStateException.class, outcome.error());
        assertEquals(Set.of(2L), outcome.completed().keySet());
        results.putAll(outcome.completed());
        failed.add(outcome.failed());
        ArchivedReplayBatch independent = ArchivedReplayBatch.prepare(store, results, failed, this::input, () -> 0);
        assertNotNull(independent);
        assertEquals(10, independent.firstId());
        success(independent.replay(this::noGeneration));
        assertEquals(Set.of(3L, 4L), failed);
    }

    @Test void removingAnyScheduledRecordInvalidatesTheBatch() {
        ArchivedRecipeStore store = store(List.of(node(1, List.of(), false), node(2, List.of(1L), false)),
                Map.of(REGION, 2L));
        ArchivedReplayBatch batch = ArchivedReplayBatch.prepare(store, Map.of(), new HashSet<>(), this::input, () -> 0);
        assertNotNull(batch);
        assertTrue(batch.valid(Set.of(1L, 2L)));
        assertFalse(batch.valid(Set.of(1L)));
        assertFalse(batch.valid(Set.of(2L)));
    }

    @Test void interruptedBatchesStopBeforeReplayingAnotherRecord() {
        ArchivedRecipeStore store = store(List.of(node(1, List.of(), false)), Map.of(REGION, 1L));
        ArchivedReplayBatch batch = ArchivedReplayBatch.prepare(store, Map.of(), new HashSet<>(), this::input, () -> 0);
        assertNotNull(batch);
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> batch.replay(this::noGeneration));
        } finally {
            Thread.interrupted();
        }
    }
}
