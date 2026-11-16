package com.scarasol.barricade.sampling;

import com.scarasol.barricade.Barricade;
import com.scarasol.barricade.client.MaterialMeshes;
import com.scarasol.barricade.pile.CollisionBox;
import com.scarasol.barricade.pile.ModelPart;
import com.scarasol.barricade.pile.PileModel;
import com.scarasol.barricade.pile.PileModels;
import com.scarasol.barricade.recipe.PaletteSpec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.core.registries.BuiltInRegistries;

public final class MaterialSampler implements AutoCloseable {
    public record Key(String state, CompoundTag itemData, Direction direction) {
        public Key {
            itemData = itemData.copy();
        }
        @Override public CompoundTag itemData() { return itemData.copy(); }
    }

    public record Sample(List<ModelPart> parts, List<CollisionBox> boxes) {
        public Sample {
            parts = List.copyOf(parts);
            boxes = List.copyOf(boxes);
        }
        public PileModel model(String id, float weight) {
            float x = Float.POSITIVE_INFINITY, y = x, z = x;
            float a = Float.NEGATIVE_INFINITY, b = a, c = a;
            for (CollisionBox box : boxes) {
                x = Math.min(x, box.minX()); y = Math.min(y, box.minY()); z = Math.min(z, box.minZ());
                a = Math.max(a, box.maxX()); b = Math.max(b, box.maxY()); c = Math.max(c, box.maxZ());
            }
            return new PileModel(id, parts, 1, 1, 1, weight, boxes,
                    (x + a) / 2, (y + b) / 2, (z + c) / 2);
        }
    }

    private final MemoryLevel level;
    private final SampleCache persistent;
    private final Function<List<ModelPart>, CollisionBox> bounds;
    private String context, scope = "";
    private final Map<Key, CompletableFuture<Sample>> cache = new LinkedHashMap<>();
    private final ArrayDeque<Key> queue = new ArrayDeque<>();
    private long totalNanos, maximumNanos, hits, diskHits;
    private long sampleHash;
    private int successes, failures;
    private boolean closed;
    private final Thread owner = Thread.currentThread();
    private long preparationRevision = -1;
    private List<PileModels.Candidate> candidates = List.of();
    private CompletableFuture<List<PileModel>> prepared;
    private final Map<String, CompletableFuture<List<PileModel>>> selections = new LinkedHashMap<>();
    private long selectionEpoch;
    private String resourceSignature = "";

    public MaterialSampler(MemoryLevel level) { this(level, null, ""); }
    public MaterialSampler(MemoryLevel level, SampleCache persistent, String context) {
        this(level, persistent, context, MaterialMeshes::bounds);
    }
    public MaterialSampler(MemoryLevel level, SampleCache persistent, String context,
                           Function<List<ModelPart>, CollisionBox> bounds) {
        this.level = level;
        this.persistent = persistent;
        this.context = context;
        this.bounds = bounds;
    }

    public void context(String value) {
        checkThread();
        if (!context.equals(value)) {
            clearMemory();
            context = value;
            scope = resourceSignature.isEmpty() ? "" : SampleCache.digest(context + "\n" + resourceSignature);
        }
    }

    public CompletableFuture<Sample> request(Key key) {
        checkThread();
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Sampler stopped"));
        CompletableFuture<Sample> found = cache.get(key);
        if (found != null) {
            hits++;
            return found;
        }
        SampleCache.Entry saved = persistent == null || scope.isEmpty() ? null : persistent.get(scope, key);
        if (saved != null) {
            CompletableFuture<Sample> result;
            if (saved.sample() == null) result = CompletableFuture.failedFuture(
                    new UnsupportedOperationException(saved.failure()));
            else {
                result = CompletableFuture.completedFuture(saved.sample());
                sampleHash += (long) key.state().hashCode() * 31 + saved.sample().hashCode();
            }
            diskHits++;
            cache.put(key, result);
            return result;
        }
        CompletableFuture<Sample> result = new CompletableFuture<>();
        cache.put(key, result);
        queue.addLast(key);
        return result;
    }

    public void tick() {
        checkThread();
        long start = System.nanoTime();
        for (int count = 0; count < 64 && !queue.isEmpty(); count++) {
            Key key = queue.removeFirst();
            CompletableFuture<Sample> result = cache.get(key);
            long sampleStart = System.nanoTime();
            try {
                Sample sample = collect(key);
                if (persistent != null) persistent.put(scope, key, sample, null);
                sampleHash += (long) key.state().hashCode() * 31 + sample.hashCode();
                result.complete(sample);
                successes++;
                Barricade.LOGGER.debug("First material sample {}: {} ms, shape_hash={}", key.state(),
                        (System.nanoTime() - sampleStart) / 1_000_000.0, Integer.toHexString(sample.hashCode()));
            } catch (RuntimeException | LinkageError exception) {
                if (persistent != null && exception instanceof UnsupportedOperationException)
                    persistent.put(scope, key, null, exception.getMessage());
                failures++;
                result.completeExceptionally(exception);
                Barricade.LOGGER.warn("Unsupported barricade material {}: {}", key.state(), exception.toString());
            } finally {
                long nanos = System.nanoTime() - sampleStart;
                totalNanos += nanos;
                maximumNanos = Math.max(maximumNanos, nanos);
                level.reset();
            }
            if (System.nanoTime() - start >= 2_000_000) break;
        }
        if (queue.isEmpty() && persistent != null) persistent.flush();
    }

    /** Selectors may change without invalidating samples or saved object definitions. */
    public CompletableFuture<List<PileModel>> prepare(PaletteSpec selection) {
        checkThread();
        CompletableFuture<List<PileModel>> found = selections.get(selection.id());
        if (found != null) return found;
        long epoch = selectionEpoch;
        CompletableFuture<List<PileModel>> result = prepare(selection.candidates(), selection.id().hashCode())
                .thenCompose(models -> {
                    if (epoch != selectionEpoch) return CompletableFuture.failedFuture(
                            new IllegalStateException("Material selection invalidated"));
                    List<PileModel> supported = models.stream()
                            .filter(model -> model.span().length() * 1.05f / 2 <= 5).toList();
                    return supported.isEmpty() && selection.fallback() != null ? prepare(selection.fallback())
                            : CompletableFuture.completedFuture(supported);
                });
        if (selections.size() >= 256) selections.entrySet().removeIf(entry -> entry.getValue().isDone());
        selections.put(selection.id(), result);
        return result;
    }

    public CompletableFuture<List<PileModel>> prepare(
            List<PileModels.Candidate> selected, long revision) {
        checkThread();
        if (prepared != null && revision == preparationRevision && candidates.equals(selected)) return prepared;
        candidates = List.copyOf(selected);
        preparationRevision = revision;
        List<CompletableFuture<PileModel>> models = selected.stream().map(candidate -> candidate.explicit() != null
                ? CompletableFuture.completedFuture(candidate.explicit())
                : request(new Key(candidate.state(), new CompoundTag(), Direction.NORTH))
                        .handle((sample, error) -> error == null ? sample.model(candidate.id(), candidate.weight()) : null))
                .toList();
        prepared = CompletableFuture.allOf(models.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> models.stream().map(CompletableFuture::join)
                        .filter(java.util.Objects::nonNull).toList());
        return prepared;
    }

    public void resourcesReloaded(net.minecraft.world.item.crafting.RecipeManager recipes, String signature) {
        checkThread();
        level.recipes(recipes);
        if (!resourceSignature.equals(signature)) clearMemory();
        resourceSignature = signature;
        scope = SampleCache.digest(context + "\n" + signature);
    }

    /** Data-driven placement may change on reload; a tag-only change retains matching samples. */
    public void invalidateSamples() {
        checkThread();
        if (persistent != null) {
            persistent.invalidate(scope);
            persistent.flush();
        }
        clearMemory();
    }

    private void clearMemory() {
        selectionEpoch++;
        List.copyOf(cache.values()).stream().filter(future -> !future.isDone())
                .forEach(future -> future.completeExceptionally(new IllegalStateException("Resources reloaded")));
        cache.clear();
        queue.clear();
        prepared = null;
        selections.clear();
        sampleHash = 0;
    }

    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Sampling must run on its owning thread");
    }

    private Sample collect(Key key) {
        level.reset();
        // Sampling order and cache hits must not change a material's placement randomness.
        level.random.setSeed(0x9E3779B97F4A7C15L ^ (long) key.state().hashCode() * 0xBF58476D1CE4E5B9L
                ^ (long) key.itemData().hashCode() * 0x94D049BB133111EBL ^ key.direction().get3DDataValue());
        BlockPos origin = new BlockPos(0, 8, 0);
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                level.seed(new BlockPos(x, 7, z), Blocks.DIRT.defaultBlockState());
            }
        }
        try {
            var parsed = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), key.state(), false);
            ItemStack stack = new ItemStack(parsed.blockState().getBlock());
            if (!(stack.getItem() instanceof BlockItem item)) {
                throw new UnsupportedOperationException("No BlockItem placement entry");
            }
            stack.setTag(key.itemData());
            CompoundTag states = stack.getOrCreateTag().getCompound("BlockStateTag").copy();
            parsed.properties().forEach((property, value) ->
                    states.putString(property.getName(), propertyValue(property, parsed.blockState())));
            stack.getOrCreateTag().put("BlockStateTag", states);
            if (!item.place(new DirectionalPlaceContext(level, origin, key.direction(), stack, Direction.UP))
                    .consumesAction()) {
                throw new UnsupportedOperationException("Placement rejected in canonical ground context");
            }
            level.finishUpdates();
            List<ModelPart> parts = new ArrayList<>();
            for (BlockPos pos : level.writtenPositions().stream().sorted().toList()) {
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) continue;
                BlockPos offset = pos.subtract(origin);
                var entity = level.getBlockEntity(pos);
                CompoundTag nbt = entity == null ? new CompoundTag() : entity.saveWithFullMetadata();
                if (nbt.sizeInBytes() > 65_536) {
                    throw new UnsupportedOperationException("Block entity sample exceeds NBT budget");
                }
                parts.add(new ModelPart(BlockStateParser.serialize(state),
                        offset.getX(), offset.getY(), offset.getZ(), nbt));
            }
            if (parts.isEmpty() || parts.size() > 16) {
                throw new UnsupportedOperationException("Sample has empty or over-budget parts");
            }
            return new Sample(parts, List.of(bounds.apply(parts)));
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            throw new IllegalArgumentException("Invalid material state: " + key.state(), exception);
        }
    }

    private static <T extends Comparable<T>> String propertyValue(Property<T> property, BlockState state) {
        return property.getName(state.getValue(property));
    }

    public String stats() {
        return "samples=" + successes + ", failed=" + failures + ", queued=" + queue.size()
                + ", reused=" + hits + ", disk_reused=" + diskHits + ", total_ms=" + totalNanos / 1_000_000.0
                + ", max_ms=" + maximumNanos / 1_000_000.0 + ", chunk_requests=" + level.chunkRequests()
                + ", sample_hash=" + Long.toHexString(sampleHash);
    }

    @Override public void close() {
        if (persistent != null) persistent.flush();
        closed = true;
        cache.values().forEach(future -> future.completeExceptionally(new IllegalStateException("Sampler stopped")));
        cache.clear();
        queue.clear();
        level.reset();
    }
}
