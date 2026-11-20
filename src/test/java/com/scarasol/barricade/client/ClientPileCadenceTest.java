package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ClientPileCadenceTest {
    private static final FieldDefinition DEFINITION =
            new FieldDefinition(42, FieldDefinition.ALGORITHM, FieldDefinition.PARAMETERS, "cadence");

    @Test void boundedAtomicQueriesGiveSmallRegionsTheirNextTurnEvenAfterCursorRemoval() {
        BlockPos focus = new BlockPos(-1, 63, 17);
        FieldRegion medium = region(1, line(focus, 512)), small = region(2, Set.of(focus.south(20)));
        FieldRegion large = region(3, line(focus.north(20), 577)), wrapped = region(0, Set.of(focus.south(30)));
        List<FieldRegion> regions = List.of(wrapped, large, medium, small);
        assertEquals(List.of(small, large, wrapped, medium), ClientPiles.queryOrder(regions, medium.id()));
        assertEquals(List.of(small, large, wrapped), ClientPiles.queryOrder(List.of(wrapped, large, small), medium.id()));
        assertEquals(List.of(wrapped, medium, small, large), ClientPiles.queryOrder(regions, large.id()));
        var runtime = new ClientPiles.FieldRuntime(DEFINITION, List.of());
        var fields = Map.of(DEFINITION, runtime);
        var view = runtime.view;
        assertEquals(64, ClientPiles.queryLimit(512, medium, regions, fields, Set.of()));
        assertEquals(64, ClientPiles.queryLimit(512, medium, List.of(medium, small), Map.of(), Set.of()));
        assertEquals(512, ClientPiles.queryLimit(512, medium, List.of(medium), fields, Set.of()));
        view.accept(small.domain(), List.of());
        assertEquals(512, ClientPiles.queryLimit(512, medium, List.of(medium, small), fields, Set.of()));

        Set<BlockPos> first = batch(large, focus, fields);
        assertEquals(64, first.size());
        assertEquals(line(focus.north(20), 64), first);
        view.accept(first, List.of());
        assertFalse(view.ready(large.domain()));
        Set<BlockPos> second = batch(large, focus.west(1000), fields);
        assertEquals(64, second.size(), "513 cells still cannot take a whole turn");
        assertTrue(Collections.disjoint(first, second));
        view.accept(second, List.of());
        Set<BlockPos> last = batch(large, focus, fields);
        assertEquals(449, last.size());
        view.accept(last, List.of());
        assertTrue(view.ready(large.domain()));
    }

    private static Set<BlockPos> batch(FieldRegion region, BlockPos focus,
                                       Map<FieldDefinition, ClientPiles.FieldRuntime> fields) {
        Set<BlockPos> missing = fields.get(region.definition()).view.missing(region.domain());
        return FieldViewCache.nextBatch(missing, focus,
                ClientPiles.queryLimit(missing.size(), region, List.of(region), fields, Set.of()));
    }

    @Test void readyCellsAreNotPublishedFromAnObsoleteOutcomeAndSameBasisEditsCanUseIt() {
        BlockPos cell = BlockPos.ZERO;
        var original = region(1, Set.of(cell, cell.east()));
        var adjacent = region(2, Set.of(cell.east(6)));
        Set<BlockPos> baseline = Set.of(cell, cell.east(), cell.east(6));
        var runtime = new ClientPiles.FieldRuntime(DEFINITION, List.of());
        var outcome = new ClientPiles.Outcome(4, 9, runtime, original.domain(), baseline, List.of());
        List<FieldRegion> regions = List.of(original, adjacent);
        assertFalse(outcome.accept(5, 9, runtime, regions));
        assertFalse(outcome.accept(4, 10, runtime, regions));
        assertFalse(outcome.accept(4, 9, new ClientPiles.FieldRuntime(DEFINITION, List.of()), regions));
        assertFalse(outcome.accept(4, 9, runtime, List.of(original, region(2, Set.of(cell.east(7))))));
        assertEquals(0, runtime.view.queriedCells());

        var cancelled = new CompletableFuture<ClientPiles.Outcome>();
        assertTrue(cancelled.cancel(false));
        assertFalse(cancelled.complete(outcome));
        assertThrows(CancellationException.class, cancelled::join);

        // Destroying a cell changes the active domain, but not its layout baseline.
        SpatialMask damage = new SpatialMask();
        damage.set(cell.east());
        var damaged = new FieldRegion(original.id(), 2, DEFINITION, Set.of(cell), damage, Map.of());
        assertTrue(outcome.accept(4, 9, runtime, List.of(damaged, adjacent)));
        assertTrue(runtime.view.ready(damaged.domain()));
        assertFalse(runtime.view.ready(adjacent.domain()), "Readiness is the query scope, not the shared baseline");
    }

    private static Set<BlockPos> line(BlockPos origin, int size) {
        Set<BlockPos> cells = new HashSet<>();
        for (int i = 0; i < size; i++) cells.add(origin.east(i));
        return cells;
    }

    private static FieldRegion region(long id, Set<BlockPos> cells) {
        return new FieldRegion(new UUID(0, id), 1, DEFINITION, cells, new SpatialMask(), Map.of());
    }
}
