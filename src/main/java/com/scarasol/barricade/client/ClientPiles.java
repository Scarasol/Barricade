package com.scarasol.barricade.client;

import com.scarasol.barricade.Barricade;
import com.scarasol.barricade.network.PileNetwork;
import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.*;
import com.scarasol.barricade.sampling.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/** Current snapshots -> frozen samples -> finite region packs -> overlay and render deltas. */
final class ClientPiles {
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
    private UUID lastScheduled;
    private final FieldWorkCache<FieldRuntime> workCache = new FieldWorkCache<>(8, 500_000);
    private static final class Incoming {
        final UUID world;
        final PileNetwork.Assembly assembly = new PileNetwork.Assembly();
        final FieldStore store = new FieldStore();
        long latestTransfer = -1;
        Incoming(UUID world) { this.world = world; }
    }
    private static final class FieldRuntime {
        final FieldDefinition definition;
        final List<PileModel> models;
        final FieldViewCache view = new FieldViewCache();
        FieldRuntime(FieldDefinition definition, List<PileModel> models) { this.definition = definition; this.models = List.copyOf(models); }
    }
    private record Outcome(long ticket, long samples, FieldRuntime runtime, Set<BlockPos> cells,
                           Set<BlockPos> baseline, List<FieldObject> objects) { }
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
    private void advance() {
        FieldStore store = incoming.store;
        if (resourceEpoch != resources) {
            if (job != null) job.cancel(true);
            if (pending != null) pending.cancel(false);
            pending = null; resourceEpoch = resources;
            failed.clear(); GpuLayouts.reset();
        }
        if (epoch != store.samples()) {
            if (job != null) job.cancel(true);
            if (pending != null) pending.cancel(false);
            pending = null; fields.clear(); failed.clear(); displayed.clear(); displayedSnapshots.clear(); overlay = null;
            sampler.invalidateSamples();
            epoch = store.samples();
            sampler.context(ClientSampleCache.context(level, incoming.world, epoch)); GpuLayouts.reset();
            terrain.clear(); overlayEpoch++; BarricadeRenderer.clear();
        }
        Set<FieldDefinition> live = new HashSet<>();
        store.regions().values().forEach(region -> live.add(region.definition()));
        fields.keySet().retainAll(live); failed.retainAll(live);
        if (pending != null && pending.isDone()) {
            try {
                Outcome outcome = pending.join();
                if (outcome.ticket == generation && outcome.samples == epoch
                        && fields.get(outcome.runtime.definition) == outcome.runtime
                        && layoutBasis(store, outcome.runtime.definition).equals(outcome.baseline)) {
                    outcome.runtime.view.accept(outcome.cells, outcome.objects);
                    retainScope(store, outcome.runtime);
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
        if (stateRevision != store.revision() || (overlay == null && refreshComplete(store)
                && (level.getGameTime() - overlayWorldTime >= 10 || overlayWorldTime < 0)
                && store.regions().values().stream().anyMatch(region -> !region.embedded().isEmpty()))) {
            if (stateRevision != store.revision()) {
                terrain.synchronize(displayedSnapshots, store.regions());
                fields.values().forEach(runtime -> {
                    runtime.view.synchronizeBaseline(layoutBasis(store, runtime.definition));
                    retainScope(store, runtime);
                });
            }
            stateRevision = store.revision();
            overlayWorldTime = level.getGameTime(); terrain.invalidateEmbedded(); overlayEpoch++;
            displayed.replaceAll((id, revision) -> -1L);
        }
        publish(store);
        removeObsolete(store);
        sampler.tick();
        if (pending != null) return;
        List<FieldRegion> schedule = new ArrayList<>(store.regions().values().stream()
                .sorted(Comparator.comparing(FieldRegion::id)).toList());
        for (int i = 0; i < schedule.size(); i++) if (schedule.get(i).id().equals(lastScheduled)) {
            Collections.rotate(schedule, -i - 1); break;
        }
        for (FieldRegion region : schedule) {
            FieldDefinition definition = region.definition();
            if (failed.contains(definition)) continue;
            FieldRuntime runtime = fields.get(definition);
            if (runtime == null) {
                try {
                    PaletteSpec palette = store.palette(definition.palette());
                    sampler.resourcesReloaded(level.getRecipeManager(), palette.resources());
                    var samples = sampler.prepare(palette);
                    if (!samples.isDone()) break;
                    if (samples.join().isEmpty()) throw new IllegalArgumentException("No usable materials in selected or default tag");
                    runtime = new FieldRuntime(definition, samples.join()); fields.put(definition, runtime);
                } catch (RuntimeException | LinkageError exception) {
                    failed.add(definition); Barricade.LOGGER.error("Cannot freeze barricade material set", exception); continue;
                }
            }
            runtime.view.synchronizeBaseline(layoutBasis(store, definition));
            Set<BlockPos> added = runtime.view.missing(region.domain());
            if (added.isEmpty()) continue; // Deletion and embedding never submit a field solve.
            FieldRuntime target = runtime;
            Set<BlockPos> baseline = runtime.view.baseline();
            BlockPos focus = Minecraft.getInstance().player == null ? region.domain().iterator().next()
                    : Minecraft.getInstance().player.blockPosition().immutable();
            long samples = epoch;
            Set<FieldRuntime> retained = Set.copyOf(fields.values());
            lastScheduled = region.id();
            CompletableFuture<Outcome> result = new CompletableFuture<>(); pending = result;
            job = worker.submit(() -> {
                try {
                    result.complete(GpuLayouts.prepare(() -> {
                        Set<BlockPos> cells = FieldViewCache.nextBatch(added, focus, 64);
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
                    Minecraft.getInstance().execute(() -> { if (ticket == generation) failed.add(definition); });
            });
            break;
        }
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
            try { ready = overlay.advance(terrain, 64, start + 2_000_000); }
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
    private static void retainScope(FieldStore store, FieldRuntime runtime) {
        Set<BlockPos> scope = new HashSet<>();
        for (FieldRegion region : store.regions().values())
            if (region.definition().equals(runtime.definition)) scope.addAll(region.domain());
        runtime.view.retain(scope);
    }
    private static Set<BlockPos> layoutBasis(FieldStore store, FieldDefinition definition) {
        Set<BlockPos> result = new HashSet<>();
        for (FieldRegion region : store.regions().values())
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
