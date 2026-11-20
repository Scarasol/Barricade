package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.*;
import com.scarasol.barricade.sampling.MemoryFixture;
import java.util.*;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

class ClientPileScheduleTest {
    private static final PaletteSpec PALETTE = PaletteSpec.create(List.of(
            new PileModels.Candidate("oak", "minecraft:oak_planks", 1)), "schedule-test");
    private static final FieldDefinition DEFINITION = FieldDefinition.create(42, PALETTE);
    private static final Function<FieldDefinition, ClientPiles.FieldRuntime> NO_PREPARE = definition ->
            fail("Unexpected material request " + definition);

    @Test void settledRevisionDoesNotVisitRegionsAndWakesOnResourceOrSampleReset() {
        var store = new FieldStore();
        var region = region(1, 1, DEFINITION, Set.of(BlockPos.ZERO));
        store.accept(snapshot(1, PALETTE, List.of(region)));
        var queries = new ClientPiles.QuerySchedule();
        var failed = new HashSet<>(Set.of(DEFINITION));
        Map<FieldDefinition, ClientPiles.FieldRuntime> fields = new HashMap<>();
        assertNull(queries.next(store, fields, failed, NO_PREPARE));

        failed.clear();
        assertNull(queries.next(store, fields, failed, NO_PREPARE), "Same revision was already exhausted");
        queries.wake(); // Resource reload clears failed definitions without changing the wire revision.
        Function<FieldDefinition, ClientPiles.FieldRuntime> prepare = definition ->
                fields.computeIfAbsent(definition, key -> new ClientPiles.FieldRuntime(key, List.of()));
        assertEquals(region, queries.next(store, fields, failed, prepare).region());
        fields.get(DEFINITION).view.accept(region.domain(), List.of());
        assertNull(queries.next(store, fields, failed, NO_PREPARE));

        fields.clear();
        queries.wake(); // A sample epoch reset discards all runtime views.
        assertEquals(region, queries.next(store, fields, failed, prepare).region());
    }

    @Test void unfinishedSamplingKeepsItsTurnAndSubmittedWorkIsNotMarkedIdle() {
        var store = new FieldStore();
        var a = region(1, 1, DEFINITION, Set.of(BlockPos.ZERO));
        var b = region(2, 1, FieldDefinition.create(43, PALETTE), Set.of(BlockPos.ZERO.east(80)));
        store.accept(snapshot(1, PALETTE, List.of(b, a)));
        var queries = new ClientPiles.QuerySchedule();
        Map<FieldDefinition, ClientPiles.FieldRuntime> fields = new HashMap<>();
        try (var sampler = MemoryFixture.sampler(MemoryFixture.create(false))) {
            Function<FieldDefinition, ClientPiles.FieldRuntime> prepare = definition -> {
                assertEquals(DEFINITION, definition, "Do not abandon or rotate the pending palette");
                var samples = sampler.prepare(PALETTE);
                if (!samples.isDone()) return null;
                assertFalse(samples.join().isEmpty());
                return fields.computeIfAbsent(definition, key -> new ClientPiles.FieldRuntime(key, samples.join()));
            };
            assertNull(queries.next(store, fields, Set.of(), prepare));
            assertNull(queries.next(store, fields, Set.of(), prepare));
            sampler.tick();
            var first = queries.next(store, fields, Set.of(), prepare);
            assertNotNull(first);
            assertEquals(a, first.region());

            // A cancelled/obsolete job accepts no cells. The cursor still gives the other region its turn.
            fields.put(b.definition(), new ClientPiles.FieldRuntime(b.definition(), first.runtime().models));
            var second = queries.next(store, fields, Set.of(), NO_PREPARE);
            assertEquals(b, second.region());
            second.runtime().view.accept(second.missing(), List.of());
            assertEquals(a, queries.next(store, fields, Set.of(), NO_PREPARE).region(),
                    "The cancelled region must still be scheduled on the same revision");
        }
    }

    @Test void editsWakeLayoutOnlyForChangedGeometryAndResubscribedCells() {
        var store = new FieldStore();
        var a = region(1, 1, DEFINITION, Set.of(BlockPos.ZERO, BlockPos.ZERO.east()));
        var b = region(2, 1, DEFINITION, Set.of(BlockPos.ZERO.east(160)));
        var runtime = new ClientPiles.FieldRuntime(DEFINITION, List.of());
        var fields = Map.of(DEFINITION, runtime);
        var queries = new ClientPiles.QuerySchedule();
        store.accept(snapshot(1, PALETTE, List.of(a, b)));
        runtime.synchronizeLayout(store.regions().values());
        while (true) {
            var query = queries.next(store, fields, Set.of(), NO_PREPARE);
            if (query == null) break;
            runtime.view.accept(query.missing(), List.of());
        }
        var grown = region(1, 2, DEFINITION, Set.of(BlockPos.ZERO, BlockPos.ZERO.east(), BlockPos.ZERO.south()));
        store.accept(snapshot(2, PALETTE, List.of(grown, b)));
        runtime.synchronizeLayout(store.regions().values());
        assertTrue(runtime.view.ready(b.domain()), "Distant partition keeps its ready cells");
        var growth = queries.next(store, fields, Set.of(), NO_PREPARE);
        assertEquals(grown, growth.region());
        assertTrue(growth.missing().contains(BlockPos.ZERO.south()));
        runtime.view.accept(growth.missing(), List.of());
        assertNull(queries.next(store, fields, Set.of(), NO_PREPARE));

        BlockPos removed = BlockPos.ZERO.south();
        var embedding = new FieldRegion(a.id(), 3, DEFINITION, grown.domain(), new SpatialMask(),
                Map.of(removed, new EmbeddedBlock(removed, 7, 1)));
        var mask = new SpatialMask();
        mask.set(removed);
        var damaged = new FieldRegion(a.id(), 4, DEFINITION, a.domain(), mask, Map.of());
        store.accept(snapshot(3, PALETTE, List.of(embedding, b)));
        runtime.synchronizeLayout(store.regions().values());
        assertNull(queries.next(store, fields, Set.of(), NO_PREPARE));
        store.accept(snapshot(4, PALETTE, List.of(damaged, b)));
        runtime.synchronizeLayout(store.regions().values());
        assertNull(queries.next(store, fields, Set.of(), NO_PREPARE), "Mask carries the original baseline");

        store.accept(snapshot(5, PALETTE, List.of(damaged)));
        runtime.synchronizeLayout(store.regions().values());
        assertFalse(runtime.view.ready(b.domain()));
        assertNull(queries.next(store, fields, Set.of(), NO_PREPARE));
        store.accept(snapshot(6, PALETTE, List.of(damaged, b)));
        runtime.synchronizeLayout(store.regions().values());
        assertEquals(b, queries.next(store, fields, Set.of(), NO_PREPARE).region());
    }

    static CompoundTag snapshot(long revision, PaletteSpec palette, Collection<FieldRegion> regions) {
        CompoundTag payload = new CompoundTag();
        payload.putInt("Format", 2);
        payload.putLong("Base", -1);
        payload.putLong("Revision", revision);
        ListTag palettes = new ListTag(), groups = new ListTag();
        palettes.add(palette.save());
        regions.forEach(region -> groups.add(region.save()));
        payload.put("Palettes", palettes);
        payload.put("Regions", groups);
        return payload;
    }

    static FieldRegion region(long id, long revision, FieldDefinition definition, Set<BlockPos> cells) {
        return new FieldRegion(new UUID(0, id), revision, definition, cells, new SpatialMask(), Map.of());
    }
}
