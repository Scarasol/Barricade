package com.scarasol.barricade.client;

import com.scarasol.barricade.Barricade;
import com.scarasol.barricade.network.PileNetwork;
import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.*;
import com.scarasol.barricade.sampling.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/** Current snapshots -> frozen samples -> finite region packs -> overlay and render deltas. */
final class ClientPiles {
    private static final int FULL_QUERY_LIMIT = 512, PARTIAL_QUERY_LIMIT = 64;
    private static final Map<ResourceLocation, Incoming> INCOMING = new LinkedHashMap<>();
    private static ClientPiles current;
    private static long generation, resources;
    private final ClientLevel level;
    private final Incoming incoming;
    private final MaterialSampler sampler;
    private final TerrainShapes terrain;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Barricade field preparation"); thread.setDaemon(true); return thread;
    });
    private final Map<FieldDefinition, FieldRuntime> fields = new HashMap<>();
    private final Set<FieldDefinition> failed = new HashSet<>();
    private final Map<UUID, Long> displayed = new HashMap<>();
    private final Map<UUID, FieldRegion> displayedSnapshots = new HashMap<>();
    private final long ticket = ++generation;
    private long epoch, resourceEpoch, overlayEpoch, stateRevision = -1, overlayWorldTime = -1, maxPublishNanos;
    private CompletableFuture<Outcome> pending;
    private Future<?> job;
    private FieldOverlay overlay;
    private final QuerySchedule queries = new QuerySchedule();
    private final FieldWorkCache<FieldRuntime> workCache = new FieldWorkCache<>(8, 500_000);
    private static final class Incoming {
        final UUID world;
        final PileNetwork.Assembly assembly = new PileNetwork.Assembly();
        final FieldStore store = new FieldStore();
        long latestTransfer = -1;
        Incoming(UUID world) { this.world = world; }
    }
    static final class FieldRuntime {
        final FieldDefinition definition;
        final List<PileModel> models;
        final FieldViewCache view = new FieldViewCache();
        FieldRuntime(FieldDefinition definition, List<PileModel> models) { this.definition = definition; this.models = List.copyOf(models); }
        void synchronizeLayout(Collection<FieldRegion> regions) {
            view.synchronizeBaseline(layoutBasis(regions, definition));
            retainScope(regions);
        }
        void retainScope(Collection<FieldRegion> regions) {
            Set<BlockPos> scope = new HashSet<>();
            for (FieldRegion region : regions)
                if (region.definition().equals(definition)) scope.addAll(region.domain());
            view.retain(scope);
        }
    }
    record Outcome(long ticket, long samples, FieldRuntime runtime, Set<BlockPos> cells,
                   Set<BlockPos> baseline, List<FieldObject> objects) {
        boolean accept(long generation, long epoch, FieldRuntime current, Collection<FieldRegion> regions) {
            if (ticket != generation || samples != epoch || current != runtime
                    || !layoutBasis(regions, runtime.definition).equals(baseline)) return false;
            runtime.view.accept(cells, objects);
            return true;
        }
    }
    record Query(FieldRuntime runtime, FieldRegion region, Set<BlockPos> missing, int limit) { }
    static final class QuerySchedule {
        private long idleRevision = Long.MIN_VALUE;
        private UUID lastScheduled;

        void wake() { idleRevision = Long.MIN_VALUE; }

        Query next(FieldStore store, Map<FieldDefinition, FieldRuntime> fields, Set<FieldDefinition> failed,
                   Function<FieldDefinition, FieldRuntime> prepare) {
            if (idleRevision == store.revision()) return null;
            var regions = store.regions().values();
            for (FieldRegion region : queryOrder(regions, lastScheduled)) {
                FieldDefinition definition = region.definition();
                if (failed.contains(definition)) continue;
                FieldRuntime runtime = fields.get(definition);
                if (runtime == null) {
                    runtime = prepare.apply(definition);
                    if (runtime == null) {
                        if (failed.contains(definition)) continue;
                        return null; // Sampling is still in progress; retry after the next sampler tick.
                    }
                }
                if (runtime.view.ready(region.domain())) continue;
                var missing = runtime.view.missing(region.domain());
                lastScheduled = region.id();
                return new Query(runtime, region, missing, queryLimit(missing.size(), region, regions, fields, failed));
            }
            // Only an exhausted queue may sleep. Geometry changes on a new store revision or epoch.
            idleRevision = store.revision();
            return null;
        }
    }
    private ClientPiles(ClientLevel level, Incoming incoming) {
        this.level = level; this.incoming = incoming;
        epoch = incoming.store.samples(); resourceEpoch = resources;
        sampler = new MaterialSampler(new MemoryLevel(level.registryAccess(), level.dimensionTypeRegistration(),
                level.enabledFeatures(), level.getRecipeManager(), false), ClientSampleCache.ready(),
                ClientSampleCache.context(level, incoming.world, epoch));
        terrain = new TerrainShapes(level);
    }
    static void receive(PileNetwork.Fragment fragment) {
        Incoming input = INCOMING.get(fragment.dimension());
        if (input != null && !input.world.equals(fragment.world()) && fragment.transfer() <= input.latestTransfer) return;
        if (input == null || !input.world.equals(fragment.world())) {
            input = new Incoming(fragment.world()); INCOMING.put(fragment.dimension(), input);
            if (INCOMING.size() > 4) INCOMING.remove(INCOMING.keySet().iterator().next());
        }
        input.latestTransfer = Math.max(input.latestTransfer, fragment.transfer());
        try {
            CompoundTag payload = input.assembly.accept(fragment);
            if (payload != null) input.store.accept(payload);
        } catch (RuntimeException exception) {
            Barricade.LOGGER.warn("Invalid barricade field metadata; requesting current baseline", exception);
            PileNetwork.requestResync();
        }
    }
    static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            if (current != null) { current.close(); current = null; }
            return;
        }
        Incoming input = INCOMING.get(level.dimension().location());
        if (current != null && (current.level != level || current.incoming != input)) {
            current.close(); current = null;
        }
        if (input == null || ClientSampleCache.ready() == null) return;
        if (current == null) current = new ClientPiles(level, input);
        current.advance();
    }
    static void resourcesReloaded() { resources++; }
    static boolean isEmbeddedAt(ClientLevel level, BlockPos pos) {
        if (Minecraft.getInstance().level != level) return false;
        Incoming input = INCOMING.get(level.dimension().location());
        return input != null && input.store.embeddedAt(pos) != null;
    }
    private void advance() {
        FieldStore store = incoming.store;
        if (resourceEpoch != resources) {
            if (job != null) job.cancel(true);
            if (pending != null) pending.cancel(false);
            pending = null; resourceEpoch = resources;
            failed.clear(); queries.wake(); GpuLayouts.reset();
        }
        if (epoch != store.samples()) {
            if (job != null) job.cancel(true);
            if (pending != null) pending.cancel(false);
            pending = null; fields.clear(); failed.clear(); displayed.clear(); displayedSnapshots.clear(); overlay = null;
            sampler.invalidateSamples();
            epoch = store.samples();
            sampler.context(ClientSampleCache.context(level, incoming.world, epoch)); GpuLayouts.reset();
            queries.wake(); terrain.clear(); overlayEpoch++; BarricadeRenderer.clear();
        }
        boolean changed = stateRevision != store.revision();
        if (changed) {
            Set<FieldDefinition> live = new HashSet<>();
            store.regions().values().forEach(region -> live.add(region.definition()));
            fields.keySet().retainAll(live); failed.retainAll(live);
        }
        if (pending != null && pending.isDone()) {
            try {
                Outcome outcome = pending.join();
                if (outcome.accept(generation, epoch, fields.get(outcome.runtime.definition), store.regions().values())) {
                    outcome.runtime.retainScope(store.regions().values());
                    displayed.replaceAll((id, revision) -> {
                        FieldRegion region = store.regions().get(id);
                        return region != null && region.definition().equals(outcome.runtime.definition) ? -1L : revision;
                    });
                    overlay = null; overlayEpoch++;
                }
            } catch (CancellationException exception) { /* Lifecycle cancellation cannot publish. */ }
            catch (CompletionException exception) { Barricade.LOGGER.error("Required spatial field computation failed", exception.getCause()); }
            pending = null;
        }
        if (changed || (overlay == null && refreshComplete(store)
                && (level.getGameTime() - overlayWorldTime >= 10 || overlayWorldTime < 0)
                && store.regions().values().stream().anyMatch(region -> !region.embedded().isEmpty()))) {
            if (changed) {
                terrain.synchronize(displayedSnapshots, store.regions());
                fields.values().forEach(runtime -> runtime.synchronizeLayout(store.regions().values()));
            }
            stateRevision = store.revision();
            overlayWorldTime = level.getGameTime(); terrain.invalidateEmbedded(); overlayEpoch++;
            displayed.replaceAll((id, revision) -> -1L);
        }
        publish(store);
        removeObsolete(store);
        sampler.tick();
        if (pending != null) return;
        Query query = queries.next(store, fields, failed, this::prepareField);
        if (query == null) return;
        FieldRuntime target = query.runtime;
        FieldDefinition definition = target.definition;
        Set<BlockPos> baseline = target.view.baseline();
        BlockPos focus = Minecraft.getInstance().player == null ? query.region.domain().iterator().next()
                : Minecraft.getInstance().player.blockPosition().immutable();
        long samples = epoch;
        Set<FieldRuntime> retained = Set.copyOf(fields.values());
        CompletableFuture<Outcome> result = new CompletableFuture<>(); pending = result;
        job = worker.submit(() -> {
            try {
                result.complete(GpuLayouts.prepare(() -> {
                    Set<BlockPos> cells = FieldViewCache.nextBatch(query.missing, focus, query.limit);
                    workCache.retain(retained);
                    SpatialField field = workCache.acquire(target, () -> new SpatialField(
                            level.dimension().location().toString(), definition, target.models, GpuLayouts::generate));
                    try { return new Outcome(ticket, samples, target, cells, baseline,
                            field.query(cells, baseline)); }
                    finally { workCache.trim(target); }
                }));
            } catch (RuntimeException | LinkageError exception) { result.completeExceptionally(exception); }
        });
        result.whenComplete((ignored, error) -> {
            if (error != null && !(error instanceof CancellationException))
                Minecraft.getInstance().execute(() -> {
                    if (ticket == generation && fields.get(definition) == target) failed.add(definition);
                });
        });
    }
    private FieldRuntime prepareField(FieldDefinition definition) {
        FieldStore store = incoming.store;
        try {
            PaletteSpec palette = store.palette(definition.palette());
            sampler.resourcesReloaded(level.getRecipeManager(), palette.resources());
            var samples = sampler.prepare(palette);
            if (!samples.isDone()) return null;
            var models = samples.join();
            if (models.isEmpty()) throw new IllegalArgumentException("No usable materials in selected or default tag");
            FieldRuntime runtime = new FieldRuntime(definition, models);
            runtime.view.synchronizeBaseline(layoutBasis(store.regions().values(), definition));
            fields.put(definition, runtime);
            return runtime;
        } catch (RuntimeException | LinkageError exception) {
            failed.add(definition); Barricade.LOGGER.error("Cannot freeze barricade material set", exception);
            return null;
        }
    }
    static List<FieldRegion> queryOrder(Collection<FieldRegion> regions, UUID after) {
        // Start at the cursor's successor even if the last region disappeared during its query.
        return regions.stream().sorted(Comparator
                .comparing((FieldRegion region) -> after != null && region.id().compareTo(after) <= 0)
                .thenComparing(FieldRegion::id)).toList();
    }
    static int queryLimit(int missing, FieldRegion selected, Collection<FieldRegion> regions,
                          Map<FieldDefinition, FieldRuntime> fields, Set<FieldDefinition> failed) {
        if (missing > FULL_QUERY_LIMIT) return PARTIAL_QUERY_LIMIT;
        if (missing > PARTIAL_QUERY_LIMIT) for (FieldRegion other : regions) {
            if (other.id().equals(selected.id()) || failed.contains(other.definition())) continue;
            FieldRuntime peer = fields.get(other.definition());
            if (peer == null || !peer.view.ready(other.domain())) return PARTIAL_QUERY_LIMIT;
        }
        // The whole region must be ready anyway, but do not expand a turn while another region waits.
        return FULL_QUERY_LIMIT;
    }
    private void publish(FieldStore store) {
        long start = System.nanoTime();
        if (overlay != null) {
            FieldRegion current = store.regions().get(overlay.region.id());
            if (current == null || current.revision() != overlay.region.revision() || overlay.epoch != overlayEpoch) overlay = null;
        }
        if (overlay == null) for (FieldRegion region : store.regions().values()) {
            FieldRuntime runtime = fields.get(region.definition());
            if (runtime == null || !runtime.view.ready(region.domain())
                    || Objects.equals(displayed.get(region.id()), region.revision())) continue;
            if (!runtime.view.ready(region.domain()) && displayedSnapshots.values().stream().anyMatch(old ->
                    !store.regions().containsKey(old.id()) && old.domain().stream().anyMatch(region.domain()::contains))) continue;
            overlay = new FieldOverlay(region, overlayEpoch, runtime.view.objects(), store.regions().values()); break;
        }
        if (overlay != null) {
            boolean ready = false;
            try { ready = overlay.advanceForTick(terrain, start, System::nanoTime); }
            catch (RuntimeException | LinkageError exception) {
                Barricade.LOGGER.warn("Current obstacle/embedded geometry unavailable: {}", exception.toString());
                // Keep the last complete display and retry a complete current overlay later.
                overlay = null;
            }
            if (ready) {
                BarricadeRenderer.replace(overlay.region.id(), overlay.visible());
                BarricadeRenderer.replaceEmbedded(overlay.region.id(), overlay.region.embedded());
                displayed.put(overlay.region.id(), overlay.region.revision());
                displayedSnapshots.put(overlay.region.id(), overlay.region);
                overlay = null;
            }
        }
        maxPublishNanos = Math.max(maxPublishNanos, System.nanoTime() - start);
    }
    private boolean refreshComplete(FieldStore store) {
        return store.regions().values().stream().allMatch(region -> {
            FieldRuntime runtime = fields.get(region.definition());
            return runtime == null || runtime.view.queriedCells() == 0
                    || Objects.equals(displayed.get(region.id()), region.revision());
        });
    }
    private static Set<BlockPos> layoutBasis(Collection<FieldRegion> regions, FieldDefinition definition) {
        Set<BlockPos> result = new HashSet<>();
        for (FieldRegion region : regions)
            if (region.definition().equals(definition)) result.addAll(region.baseline());
        return Set.copyOf(result);
    }
    private void removeObsolete(FieldStore store) {
        for (UUID id : displayed.keySet().stream().filter(id -> !store.regions().containsKey(id)).toList()) {
            FieldRegion old = displayedSnapshots.get(id);
            boolean awaitingSuccessor = old != null && store.regions().values().stream().anyMatch(region ->
                    (!Objects.equals(displayed.get(region.id()), region.revision())
                            || fields.get(region.definition()) == null
                            || !fields.get(region.definition()).view.ready(region.domain()))
                            && region.domain().stream().anyMatch(old.domain()::contains));
            if (awaitingSuccessor) continue;
            BarricadeRenderer.remove(id); displayed.remove(id); displayedSnapshots.remove(id);
        }
    }
    static String stats() {
        if (current == null) return "No client barricade world";
        return current.sampler.stats() + ", fields=" + current.fields.size() + ", failed_fields=" + current.failed.size()
                + ", max_publish_ms=" + current.maxPublishNanos / 1e6 + "; " + GpuLayouts.stats() + "; " + BarricadeRenderer.stats();
    }
    private void close() {
        generation++;
        if (job != null) job.cancel(true);
        if (pending != null) pending.cancel(false);
        worker.shutdownNow(); sampler.close(); GpuLayouts.reset();
        BarricadeRenderer.clear(); MaterialMeshes.clear();
    }
    static void logout() {
        if (current != null) { current.close(); current = null; }
        INCOMING.clear(); BarricadeRenderer.clear(); MaterialMeshes.clear();
    }
}
