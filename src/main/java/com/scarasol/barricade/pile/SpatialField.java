package com.scarasol.barricade.pile;

import com.scarasol.barricade.recipe.*;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtIo;

/** A fixed coarse skeleton followed by independent fine interiors. Only GPU jobs accept objects. */
public final class SpatialField {
    private final String dimension, geometry;
    private final FieldDefinition definition;
    private final List<PileModel> models;
    private final Function<FieldBatch, int[]> gpu;
    private final int side, reach, geometryHalo;
    private final Layer skeleton, interiors;
    private long jobs, vertices, hits, prepared, prepareNanos, gpuWaitNanos, peakBytes;
    private record Packed(List<RegionPiece> pieces, List<OrientedBox> guards) { }
    private record Answer(List<FieldObject> objects, List<OrientedBox> guards) { }
    private static final class Layer {
        final String name;
        final int side, offset, passes;
        final List<PileModel> models;
        final Map<GridPos, Set<GridPos>> basis = new TreeMap<>();
        final Map<GridPos, Answer> complete = new HashMap<>();
        final Map<GridPos, long[]> timings = new TreeMap<>();
        Layer(String name, int side, int offset, int passes, List<PileModel> models) {
            this.name = name; this.side = side; this.offset = offset; this.passes = passes; this.models = models;
        }
        GridPos region(BlockPos cell) {
            return new GridPos(Math.floorDiv(cell.getX() + offset, side), Math.floorDiv(cell.getY() + offset, side),
                    Math.floorDiv(cell.getZ() + offset, side));
        }
        GridPos origin(GridPos block) {
            return new GridPos(block.x() * side - offset, block.y() * side - offset, block.z() * side - offset);
        }
    }

    public SpatialField(String dimension, FieldDefinition definition, List<PileModel> models, Function<FieldBatch, int[]> gpu) {
        this(dimension, definition, models, gpu, FieldRules.REGION_SIDE);
    }
    /** Offline size comparison; production uses the versioned rule above. */
    public SpatialField(String dimension, FieldDefinition definition, List<PileModel> models,
                        Function<FieldBatch, int[]> gpu, int side) {
        this(dimension, definition, models, gpu, side, FieldRules.SKELETON_SIDE, FieldRules.SKELETON_OFFSET);
    }
    /** Offline grid comparison only; normal callers use the fixed production rules. */
    public SpatialField(String dimension, FieldDefinition definition, List<PileModel> models,
                        Function<FieldBatch, int[]> gpu, int side, int skeletonSide, int skeletonOffset) {
        if (side < 2 || side > 8 || skeletonSide < 2 || skeletonSide > 8
                || skeletonOffset < 0 || skeletonOffset >= skeletonSide)
            throw new IllegalArgumentException("Unsupported region grid");
        this.dimension = dimension; this.definition = definition; this.gpu = Objects.requireNonNull(gpu); this.side = side;
        this.models = models.stream().sorted(Comparator.comparing(PileModel::id)).toList();
        if (models.isEmpty()) throw new IllegalArgumentException("No supported materials in frozen palette");
        for (PileModel model : models)
            if (model.span().length() * 1.05f / 2 > FieldRules.MAX_MODEL_RADIUS)
                throw new FieldBatch.CapacityException("Sample geometry exceeds packing capacity: " + model.id());
        reach = Math.max(1, (int) Math.ceil(models.stream()
                .mapToDouble(model -> model.span().length() * 1.05f / 2).max().orElse(0)));
        geometryHalo = Math.max(1, (reach + side - 1) / side);
        geometry = fingerprint(this.models);
        skeleton = new Layer("skeleton", skeletonSide, skeletonOffset, 1,
                this.models.stream().filter(model -> model.elongated() || model.bulk()).toList());
        interiors = new Layer("interior", side, 0, FieldRules.PRIMARY_PASSES, this.models);
    }
    public String geometry() { return geometry; }
    public int side() { return side; }
    public record Metrics(long jobs, long vertices, long edges, long cacheHits, long prepared,
                          long prepareNanos, long peakBufferBytes, int cachedSlots, int cachedPoses) { }
    public record RegionTiming(GridPos region, int cells, String stage, int side, long preparationNanos, long batchNanos) { }
    public List<RegionTiming> regionTimings() {
        List<RegionTiming> result = new ArrayList<>();
        for (Layer layer : List.of(skeleton, interiors)) layer.timings.forEach((block, timing) ->
                result.add(new RegionTiming(block, layer.basis.getOrDefault(block, Set.of()).size(), layer.name,
                        layer.side, timing[0], timing[1])));
        return List.copyOf(result);
    }
    public int cachedSlots() { return skeleton.complete.size() + interiors.complete.size(); }
    public Metrics metrics() {
        return new Metrics(jobs, vertices, 0, hits, prepared, prepareNanos, peakBytes, cachedSlots(), 0);
    }
    public void releasePreparationCache() { /* All temporary candidate arrays are released after their batch. */ }

    public static GridPos region(BlockPos cell) { return region(cell, FieldRules.REGION_SIDE); }
    public static GridPos region(BlockPos cell, int side) {
        return new GridPos(Math.floorDiv(cell.getX(), side), Math.floorDiv(cell.getY(), side), Math.floorDiv(cell.getZ(), side));
    }
    /** Current baseline = logical cells union damage cells. Embedding never enters it. */
    public Set<GridPos> baseline(Set<BlockPos> cells) {
        Set<GridPos> changed = updateBasis(interiors, cells), coarseChanged = updateBasis(skeleton, cells);
        if (changed.isEmpty()) return Set.of();
        skeleton.complete.keySet().removeIf(block -> near(block, coarseChanged, 1));
        Set<GridPos> affected = new HashSet<>(changed);
        interiors.complete.keySet().removeIf(block -> {
            int radius = (2 * skeleton.side + reach + side - 1) / side;
            if (!near(block, changed, radius)) return false;
            affected.add(block); return true;
        });
        return Set.copyOf(affected);
    }
    private static Set<GridPos> updateBasis(Layer layer, Set<BlockPos> cells) {
        return updateCells(layer, cells, layer.basis);
    }
    private static Set<GridPos> updateCells(Layer layer, Set<BlockPos> cells, Map<GridPos, Set<GridPos>> current) {
        Map<GridPos, Set<GridPos>> next = new TreeMap<>();
        for (BlockPos cell : cells) {
            GridPos block = layer.region(cell), origin = layer.origin(block);
            next.computeIfAbsent(block, ignored -> new TreeSet<>()).add(new GridPos(
                    cell.getX() - origin.x(), cell.getY() - origin.y(), cell.getZ() - origin.z()));
        }
        Set<GridPos> changed = new HashSet<>(current.keySet());
        changed.addAll(next.keySet());
        changed.removeIf(block -> Objects.equals(current.get(block), next.get(block)));
        current.clear(); next.forEach((key, value) -> current.put(key, Set.copyOf(value)));
        return Set.copyOf(changed);
    }
    private static boolean near(GridPos block, Set<GridPos> others, int radius) {
        for (GridPos other : others)
            if (Math.abs(block.x() - other.x()) <= radius && Math.abs(block.y() - other.y()) <= radius
                    && Math.abs(block.z() - other.z()) <= radius) return true;
        return false;
    }
    /** Partial display batches must supply the complete baseline in the two-set overload. */
    public List<FieldObject> query(Set<BlockPos> domain) { return query(domain, domain); }
    public List<FieldObject> query(Set<BlockPos> domain, Set<BlockPos> baseline) {
        long start = System.nanoTime(), wait = gpuWaitNanos;
        try {
            baseline(baseline);
            Set<GridPos> targets = new TreeSet<>(), coarse = new TreeSet<>();
            for (BlockPos cell : domain) {
                GridPos lo = interiors.region(cell.offset(-reach, -reach, -reach));
                GridPos hi = interiors.region(cell.offset(reach, reach, reach));
                for (int x = lo.x(); x <= hi.x(); x++) for (int y = lo.y(); y <= hi.y(); y++)
                    for (int z = lo.z(); z <= hi.z(); z++) {
                        GridPos block = new GridPos(x, y, z);
                        if (interiors.basis.containsKey(block)) targets.add(block);
                    }
                for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
                    GridPos parent = skeleton.region(cell.offset(x, y, z));
                    if (skeleton.basis.containsKey(parent)) coarse.add(parent);
                }
            }
            Set<GridPos> needed = new TreeSet<>(coarse);
            targets.forEach(block -> needed.addAll(parents(block)));
            build(skeleton, needed);
            build(interiors, targets);
            TreeMap<FieldObject.Key, FieldObject> result = new TreeMap<>();
            collect(interiors, targets, domain, result); collect(skeleton, coarse, domain, result);
            return List.copyOf(result.values());
        } finally { prepareNanos += System.nanoTime() - start - (gpuWaitNanos - wait); }
    }
    /** Foreign procedural cells do not reserve space in this query. */
    public List<FieldObject> query(Set<BlockPos> domain, Set<BlockPos> baseline, Set<BlockPos> ignoredForeignCells) {
        return query(domain, baseline);
    }
    private void collect(Layer layer, Set<GridPos> blocks, Set<BlockPos> domain, Map<FieldObject.Key, FieldObject> result) {
        for (GridPos block : blocks) {
            hits++;
            for (FieldObject object : layer.complete.get(block).objects)
                if (FieldVisibility.activated(object, domain)) result.put(object.key(), object);
        }
    }
    private Set<GridPos> parents(GridPos block) {
        GridPos origin = interiors.origin(block);
        GridPos lo = skeleton.region(new BlockPos(origin.x() - reach, origin.y() - reach, origin.z() - reach));
        GridPos hi = skeleton.region(new BlockPos(origin.x() + side - 1 + reach,
                origin.y() + side - 1 + reach, origin.z() + side - 1 + reach));
        Set<GridPos> result = new TreeSet<>();
        for (int x = lo.x(); x <= hi.x(); x++) for (int y = lo.y(); y <= hi.y(); y++) for (int z = lo.z(); z <= hi.z(); z++) {
            GridPos other = new GridPos(x, y, z);
            if (skeleton.basis.containsKey(other)) result.add(other);
        }
        return result;
    }
    private void build(Layer layer, Set<GridPos> targets) {
        List<GridPos> pending = targets.stream().filter(block -> !layer.complete.containsKey(block)).sorted().toList();
        Map<GridPos, List<OrientedBox>> bodies = new HashMap<>(), guards = new HashMap<>();
        for (GridPos block : pending) {
            List<OrientedBox> solid = new ArrayList<>(), rays = new ArrayList<>();
            GridPos origin = layer.origin(block);
            if (layer == interiors) for (GridPos parent : parents(block)) {
                Answer answer = skeleton.complete.get(parent);
                if (answer == null) throw new IllegalStateException("Incomplete skeleton dependency");
                for (FieldObject object : answer.objects)
                    for (OrientedBox box : object.piece().colliders(object.owner().getX() - origin.x(),
                            object.owner().getY() - origin.y(), object.owner().getZ() - origin.z()))
                        if (overlaps(box, layer.side, reach)) solid.add(box);
                GridPos coarseOrigin = skeleton.origin(parent);
                for (OrientedBox ray : answer.guards) {
                    OrientedBox relative = ray.translated(coarseOrigin.x() - origin.x(), coarseOrigin.y() - origin.y(),
                            coarseOrigin.z() - origin.z());
                    if (overlaps(relative, layer.side, reach)) rays.add(relative);
                }
            }
            bodies.put(block, solid); guards.put(block, rays);
        }
        packMany(layer, pending, bodies, guards).forEach((block, packed) ->
                layer.complete.put(block, new Answer(objects(layer, block, packed.pieces, namespace(layer, block)), packed.guards)));
    }
    private static boolean overlaps(OrientedBox box, int side, int reach) {
        return box.maxX() >= -reach && box.maxY() >= -reach && box.maxZ() >= -reach
                && box.minX() <= side + reach && box.minY() <= side + reach && box.minZ() <= side + reach;
    }
    private Set<GridPos> geometryDomain(Layer layer, GridPos block) {
        Set<GridPos> result = new HashSet<>();
        visit(block, layer == interiors ? geometryHalo : 1, other -> {
            for (GridPos cell : layer.basis.getOrDefault(other, Set.of()))
                result.add(new GridPos((other.x() - block.x()) * layer.side + cell.x(),
                        (other.y() - block.y()) * layer.side + cell.y(), (other.z() - block.z()) * layer.side + cell.z()));
        });
        return Set.copyOf(result);
    }
    private Map<GridPos, Packed> packMany(Layer layer, List<GridPos> blocks, Map<GridPos, List<OrientedBox>> obstacles,
                                         Map<GridPos, List<OrientedBox>> guards) {
        Map<GridPos, Packed> result = new HashMap<>();
        Map<GridPos, Set<GridPos>> domains = new HashMap<>();
        blocks.forEach(block -> { result.put(block, new Packed(List.of(), List.of())); domains.put(block, geometryDomain(layer, block)); });
        List<GridPos> pending = layer.models.isEmpty() ? List.of() : blocks;
        for (int pass = 0; pass < layer.passes && !pending.isEmpty(); pass++) {
            List<GridPos> next = new ArrayList<>();
            for (int first = 0; first < pending.size();) {
                cancelled();
                int last = first, cells = 0;
                while (last < pending.size() && (last == first || cells + layer.basis.get(pending.get(last)).size() <= 512))
                    cells += layer.basis.get(pending.get(last++)).size();
                List<GridPos> group = pending.subList(first, last);
                PackingBatch[] plans = new PackingBatch[group.size()];
                long[] preparation = new long[group.size()];
                int generation = pass;
                work(plans.length, 2, i -> {
                    GridPos block = group.get(i); long start = System.nanoTime();
                    plans[i] = PackingBatch.prepare(new PackingInput(blockSeed(layer, block), generation, domains.get(block),
                            layer.models, result.get(block).pieces, obstacles.get(block), layer.side, true, layer.basis.get(block),
                            guards.get(block)), models, 225, layer == interiors ? 1 : 0);
                    // Keep normal continuation unchanged; rank only on its stopping or final pass.
                    if (generation + 1 < layer.passes)
                        plans[i].input[22] = plans[i].initial[4] + Math.max(1, plans[i].input[7] / 2);
                    preparation[i] = System.nanoTime() - start;
                });
                int from = 0;
                while (from < plans.length) {
                    int to = from; long words = 0;
                    while (to < plans.length && (to == from || words + plans[to].input.length + plans[to].initial.length <= 8_000_000))
                        words += plans[to].input.length + plans[to++].initial.length;
                    List<PackingBatch> values = Arrays.asList(plans).subList(from, to);
                    FieldBatch job = values.size() == 1 ? values.get(0) : new PackingGroup(values);
                    long start = System.nanoTime();
                    int[] output = solve(job);
                    long elapsed = System.nanoTime() - start;
                    List<int[]> states = job instanceof PackingGroup combined ? combined.split(output) : List.of(output);
                    for (int i = from; i < to; i++) {
                        GridPos block = group.get(i); PackingBatch plan = plans[i]; int[] state = states.get(i - from);
                        prepared += plan.size;
                        List<OrientedBox> protectedRays = new ArrayList<>(result.get(block).guards);
                        protectedRays.addAll(plan.guards(state));
                        result.put(block, new Packed(plan.decode(state), List.copyOf(protectedRays)));
                        long[] timing = layer.timings.computeIfAbsent(block, ignored -> new long[2]);
                        timing[0] += preparation[i]; timing[1] += elapsed;
                        boolean incomplete = state[4] < plan.input[7] * 80;
                        for (int owner = 0; owner < plan.input[7]; owner++)
                            incomplete |= state[state[12] + owner] < 44;
                        if (state[0] != 0 && state[24] == 0 && incomplete
                                && state[4] - plan.initial[4] >= Math.max(1, layer.basis.get(block).size() / 2)) next.add(block);
                    }
                    from = to;
                }
                first = last;
            }
            pending = next;
        }
        return result;
    }
    private int[] solve(FieldBatch batch) {
        long start = System.nanoTime();
        try {
            int[] result = gpu.apply(batch); jobs++; vertices += batch.size;
            peakBytes = Math.max(peakBytes, (batch.input.length + (long) batch.initial.length) * 4);
            return result;
        } finally { gpuWaitNanos += System.nanoTime() - start; }
    }
    private List<FieldObject> objects(Layer source, GridPos block, List<RegionPiece> packed, String namespace) {
        GridPos origin = source.origin(block);
        List<FieldObject> result = new ArrayList<>();
        for (int i = 0; i < packed.size(); i++) {
            RegionPiece piece = packed.get(i);
            BlockPos owner = new BlockPos(origin.x() + piece.owner().x(), origin.y() + piece.owner().y(), origin.z() + piece.owner().z());
            PileModel model = piece.piece().model();
            int layer = model.panel() ? 0 : model.elongated() ? 1 : model.bulk() ? 2 : model.volume() >= .15f ? 3 : 4;
            result.add(new FieldObject(new FieldObject.Key(dimension, definition, namespace, origin, layer, i), owner, piece.piece()));
        }
        result.sort(Comparator.comparing(FieldObject::key)); return List.copyOf(result);
    }
    /** Full current geometry of each actual coarse solve is part of the identity. */
    private String namespace(Layer layer, GridPos block) {
        StringBuilder result = new StringBuilder(layer.name).append(':').append(geometry).append(':').append(layer.side)
                .append(':').append(layer.offset);
        Set<GridPos> local = new TreeSet<>();
        visit(block, layer == interiors ? geometryHalo : 1, other -> { if (layer.basis.containsKey(other)) local.add(other); });
        appendInputs(result, layer, local, layer.origin(block));
        if (layer == interiors) {
            Set<GridPos> coarse = new TreeSet<>();
            for (GridPos parent : parents(block)) visit(parent, 1, other -> {
                if (skeleton.basis.containsKey(other)) coarse.add(other);
            });
            appendInputs(result, skeleton, coarse, layer.origin(block));
        }
        return result.toString();
    }
    private static void appendInputs(StringBuilder result, Layer layer, Set<GridPos> inputs, GridPos origin) {
        result.append(':').append(layer.name);
        for (GridPos other : inputs) {
            BitSet bits = new BitSet(layer.side * layer.side * layer.side);
            for (GridPos cell : layer.basis.get(other)) bits.set((cell.x() * layer.side + cell.y()) * layer.side + cell.z());
            GridPos where = layer.origin(other);
            result.append(';').append(where.x() - origin.x()).append(',').append(where.y() - origin.y())
                    .append(',').append(where.z() - origin.z()).append('=');
            for (long word : bits.toLongArray()) result.append(Long.toUnsignedString(word, 16)).append('.');
        }
    }
    private long blockSeed(Layer layer, GridPos block) {
        return mix(definition.seed() ^ hash(dimension) ^ hash(definition.parameters()) ^ hash(layer.name)
                ^ mix(block.x()) ^ Long.rotateLeft(mix(block.y()), 21) ^ Long.rotateLeft(mix(block.z()), 42));
    }
    private static void visit(GridPos block, int radius, Consumer<GridPos> visitor) {
        for (int x = -radius; x <= radius; x++) for (int y = -radius; y <= radius; y++) for (int z = -radius; z <= radius; z++)
            visitor.accept(new GridPos(block.x() + x, block.y() + y, block.z() + z));
    }
    public static long mix(long value) {
        value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
        value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }
    private static long hash(String text) {
        long result = 0;
        for (int i = 0; i < text.length(); i++) result = mix(result ^ text.charAt(i));
        return result;
    }
    private static String fingerprint(List<PileModel> models) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes);
            for (PileModel model : models) NbtIo.write(model.save(), output);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static void cancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Regional packing cancelled");
    }
    static void work(int size, int threshold, IntConsumer action) {
        if (size < threshold) { for (int i = 0; i < size; i++) { cancelled(); action.accept(i); } }
        else Workers.POOL.invoke(new Work(0, size, Thread.currentThread(), action, Math.max(1, Math.min(128, size / 4))));
    }
    private static final class Workers {
        static final ForkJoinPool POOL = new ForkJoinPool(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
    }
    private static final class Work extends RecursiveAction {
        final int first, last, grain; final Thread owner; final IntConsumer action;
        Work(int first, int last, Thread owner, IntConsumer action, int grain) {
            this.first = first; this.last = last; this.owner = owner; this.action = action; this.grain = grain;
        }
        @Override protected void compute() {
            if (owner.isInterrupted()) throw new CancellationException("Packing preparation cancelled");
            if (last - first <= grain) { for (int i = first; i < last; i++) action.accept(i); return; }
            int middle = (first + last) >>> 1;
            Work left = new Work(first, middle, owner, action, grain); left.fork();
            try { new Work(middle, last, owner, action, grain).compute(); }
            catch (RuntimeException | Error failure) {
                try { left.join(); } catch (RuntimeException | Error other) { if (other != failure) failure.addSuppressed(other); }
                throw failure;
            }
            left.join();
        }
    }
}
