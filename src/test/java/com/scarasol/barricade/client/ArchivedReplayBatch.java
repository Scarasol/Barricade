package com.scarasol.barricade.client;

import com.scarasol.barricade.Barricade;
import com.scarasol.barricade.pile.LayoutInput;
import com.scarasol.barricade.pile.LayoutResult;
import com.scarasol.barricade.recipe.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Main-thread snapshots, followed by ordered replay on the layout worker. */
final class ArchivedReplayBatch {
    static final int MAX_RECORDS = 64;
    static final long PREPARATION_NANOS = 2_000_000;
    private record Step(ArchivedPileReplay.Prepared input, double preparationMs) { }
    record Outcome(Map<Long, List<ArchivedPileReplay.ObjectPiece>> completed, long failed, Throwable error) {
        Outcome {
            completed = Collections.unmodifiableMap(new LinkedHashMap<>(completed));
        }
    }
    private final List<Step> steps;
    private final Map<Long, List<ArchivedPileReplay.ObjectPiece>> boundaries;
    private final long queued = System.nanoTime();

    private ArchivedReplayBatch(List<Step> steps, Map<Long, List<ArchivedPileReplay.ObjectPiece>> boundaries) {
        this.steps = List.copyOf(steps);
        this.boundaries = Map.copyOf(boundaries);
    }

    static ArchivedReplayBatch prepare(ArchivedRecipeStore store, Map<Long, List<ArchivedPileReplay.ObjectPiece>> results,
            Set<Long> failed, Function<ArchivedPileRecipe, ArchivedPileReplay.Prepared> prepare, LongSupplier clock) {
        long start = clock.getAsLong();
        List<Step> steps = new ArrayList<>();
        Set<Long> scheduled = new HashSet<>();
        Map<Long, List<ArchivedPileReplay.ObjectPiece>> boundaries = new HashMap<>();
        while (steps.size() < MAX_RECORDS && (steps.isEmpty() || clock.getAsLong() - start < PREPARATION_NANOS)) {
            Long id = next(store, results.keySet(), failed, scheduled);
            if (id == null) break;
            ArchivedPileRecipe node = store.recipes().get(id);
            // Only the first record may generate geometry; trailing edits need no GPU round trip.
            if (!steps.isEmpty() && !node.active().isEmpty()) break;
            long preparationStart = clock.getAsLong();
            ArchivedPileReplay.Prepared input = prepare.apply(node);
            if (input == null) break;
            steps.add(new Step(input, (clock.getAsLong() - preparationStart) / 1_000_000.0));
            for (long prerequisite : node.prerequisites()) {
                if (!scheduled.contains(prerequisite))
                    boundaries.put(prerequisite, Objects.requireNonNull(results.get(prerequisite)));
            }
            scheduled.add(id);
        }
        return steps.isEmpty() ? null : new ArchivedReplayBatch(steps, boundaries);
    }

    private static Long next(ArchivedRecipeStore store, Set<Long> results, Set<Long> failed, Set<Long> scheduled) {
        ArrayDeque<Long> stack = new ArrayDeque<>();
        store.heads().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> stack.addLast(entry.getValue()));
        while (!stack.isEmpty()) {
            long id = stack.removeFirst();
            if (results.contains(id) || scheduled.contains(id) || failed.contains(id)) continue;
            ArchivedPileRecipe node = store.recipes().get(id);
            if (node.prerequisites().stream().anyMatch(failed::contains)) { failed.add(id); continue; }
            List<Long> missing = node.prerequisites().stream()
                    .filter(parent -> !results.contains(parent) && !scheduled.contains(parent)).toList();
            if (missing.isEmpty()) return id;
            stack.addFirst(id);
            for (int index = missing.size() - 1; index >= 0; index--) stack.addFirst(missing.get(index));
        }
        return null;
    }

    long firstId() { return steps.get(0).input.recipe().id(); }
    int size() { return steps.size(); }
    boolean valid(Set<Long> recipes) {
        return steps.stream().allMatch(step -> recipes.contains(step.input.recipe().id()));
    }

    Outcome replay(Function<LayoutInput, LayoutResult> generator) {
        long start = System.nanoTime();
        Map<Long, List<ArchivedPileReplay.ObjectPiece>> available = new HashMap<>(boundaries);
        Map<Long, List<ArchivedPileReplay.ObjectPiece>> completed = new LinkedHashMap<>();
        for (Step step : steps) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Replay batch cancelled");
            ArchivedPileRecipe node = step.input.recipe();
            long workerStart = System.nanoTime();
            try {
                var parents = node.parents().stream().map(available::get).toList();
                var neighbors = node.neighbors().stream().map(available::get).toList();
                var pieces = ArchivedPileReplay.replay(step.input, parents, neighbors, generator);
                long end = System.nanoTime();
                available.put(node.id(), pieces);
                completed.put(node.id(), pieces);
                double queueMs = completed.size() == 1 ? (start - queued) / 1_000_000.0 : 0;
                double computeMs = (end - workerStart) / 1_000_000.0;
                Barricade.LOGGER.debug(
                        "Client replay {}: {} ms, {} objects (prepare={} ms, queue={} ms, compute={} ms, cells={}, active={})",
                        node.id(), queueMs + computeMs, pieces.size(), step.preparationMs, queueMs, computeMs,
                        node.domain().size(), node.active().size());
            } catch (CancellationException exception) {
                throw exception;
            } catch (RuntimeException | LinkageError exception) {
                return new Outcome(completed, node.id(), exception);
            }
        }
        Barricade.LOGGER.debug("Client replay batch: {} records, {} ms", completed.size(),
                (System.nanoTime() - start) / 1_000_000.0);
        return new Outcome(completed, 0, null);
    }
}
