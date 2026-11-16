package com.scarasol.barricade.pile;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Immutable GPU layout input and its CPU-prepared candidate and initial-state buffers. */
public final class ArchivedBatchLayout {
    public static final class CapacityException extends IllegalArgumentException {
        public CapacityException(String message) { super(message); }
    }
    public static final int HEADER = 32, META = 12, BOX = FixedGeometry.STRIDE, ENTRY = 8;
    public static final int[] TRIALS = {12, 12, 12, 64, 80}, ROUNDS = {2, 2, 2, 8, 12};
    public final int[] input, initial;
    public final List<RegionPiece> candidates, preserved;
    private final Set<GridPos> work;
    private final int pass;

    private ArchivedBatchLayout(int[] input, int[] initial, List<RegionPiece> candidates,
                        List<RegionPiece> preserved, Set<GridPos> work, int pass) {
        this.input = input; this.initial = initial;
        this.candidates = List.copyOf(candidates); this.preserved = List.copyOf(preserved); this.work = Set.copyOf(work);
        this.pass = pass;
    }
    public static boolean supported(LayoutInput snapshot) {
        if (snapshot.active().isEmpty() || snapshot.active().size() > 512) return false;
        if ((long) snapshot.existing().size() + snapshot.neighbors().size()
                + snapshot.obstacles().size() + snapshot.unloaded().size() > 50_000) return false;
        for (var list : List.of(snapshot.neighbors(), snapshot.obstacles(), snapshot.unloaded()))
            for (OrientedBox box : list)
                if (box.maxX() - box.minX() > 16 || box.maxY() - box.minY() > 16
                        || box.maxZ() - box.minZ() > 16) return false;
        int[] bounds = bounds(snapshot.domain(), 10);
        return (long) bounds[3] * bounds[4] * bounds[5] <= 250_000;
    }
    public static ArchivedBatchLayout prepare(LayoutInput snapshot) {
        return prepare(snapshot, false);
    }
    /** Test-only unpruned reference with the later production axis proposals. Historical replay stays unchanged. */
    static ArchivedBatchLayout prepareWithAxisProposals(LayoutInput snapshot) {
        return prepare(snapshot, true);
    }
    private static ArchivedBatchLayout prepare(LayoutInput snapshot, boolean axisProposals) {
        if (!supported(snapshot)) throw new CapacityException("Region exceeds GPU layout limits");
        List<RegionPiece> kept = new ArrayList<>();
        Set<GridPos> work = new HashSet<>(snapshot.active());
        SpatialIndex disturbed = new SpatialIndex();
        snapshot.repack().forEach(cell -> disturbed.add(OrientedBox.axisAligned(
                cell.x(), cell.y(), cell.z(), cell.x() + 1, cell.y() + 1, cell.z() + 1)));
        for (RegionPiece piece : snapshot.existing()) {
            if (piece.colliders().stream().noneMatch(box -> disturbed.intersects(box, 0))) kept.add(piece);
            else for (OrientedBox box : piece.colliders()) {
                for (int x = (int) Math.floor(box.minX()); x <= Math.floor(box.maxX()); x++)
                    for (int y = (int) Math.floor(box.minY()); y <= Math.floor(box.maxY()); y++)
                        for (int z = (int) Math.floor(box.minZ()); z <= Math.floor(box.maxZ()); z++) {
                            GridPos cell = new GridPos(x, y, z);
                            if (snapshot.domain().contains(cell)) work.add(cell);
                        }
            }
        }
        List<GridPos> cells = work.stream().sorted().toList();
        cells = new ArrayList<>(cells);
        long seed = snapshot.seed() ^ (long) snapshot.generation() * 0x9e3779b97f4a7c15L;
        Collections.shuffle(cells, new Random(seed ^ 0x194EAA17L));
        Map<GridPos, Integer> owners = new HashMap<>();
        for (int i = 0; i < cells.size(); i++) owners.put(cells.get(i), i);
        int[] h = new int[HEADER], dim = bounds(snapshot.domain(), 10);
        System.arraycopy(dim, 0, h, 0, 6);
        h[6] = dim[3] * dim[4] * dim[5]; h[7] = cells.size();
        Ints data = new Ints(); data.add(h);
        h[8] = data.size;
        int[] domain = new int[h[6]];
        snapshot.domain().forEach(cell -> domain[index(h, cell.x(), cell.y(), cell.z())] =
                owners.containsKey(cell) ? 2 + owners.get(cell) : 1);
        data.add(domain);
        h[9] = data.size;
        cells.forEach(cell -> data.add(cell.x(), cell.y(), cell.z()));
        List<OrientedBox> boxGeometry = new ArrayList<>();
        List<int[]> entries = new ArrayList<>(), meta = new ArrayList<>();
        for (var list : List.of(snapshot.neighbors(), snapshot.obstacles(), snapshot.unloaded()))
            for (OrientedBox box : list) addEntry(entries, boxGeometry, List.of(box), list == snapshot.neighbors() ? 0 : 2);
        CellMask mask = new CellMask(snapshot.domain());
        SpatialIndex occupied = new SpatialIndex(), obstacles = new SpatialIndex();
        snapshot.obstacles().forEach(obstacles::add);
        snapshot.neighbors().forEach(occupied::add);
        kept.forEach(piece -> piece.colliders().forEach(occupied::add));
        for (RegionPiece piece : kept) {
            addEntry(entries, boxGeometry, piece.colliders(), 0);
            if (piece.piece().model().panel()) {
                List<OrientedBox> rays = PanelExposure.corridors(piece, mask, occupied, obstacles);
                if (rays.size() >= 3) addEntry(entries, boxGeometry, rays.subList(0, 3), 1);
            }
        }
        List<RegionPiece> candidates = new ArrayList<>();
        int[] ranges = new int[cells.size() * 10];
        Random random = new Random(seed ^ 0xBCA74E1298A21F35L);
        Random poseRandom = new Random(snapshot.seed() ^ 0x15AC81E739L);
        List<Quaternionf> surfaces = new ArrayList<>();
        for (int i = 0; i < 8; i++) surfaces.add(new Quaternionf().rotationYXZ(
                poseRandom.nextFloat() * (float) (Math.PI * 2),
                (poseRandom.nextFloat() - .5f) * (i < 2 ? .025f : .4f),
                (poseRandom.nextFloat() - .5f) * (i < 2 ? .025f : .4f)));
        List<List<PileModel>> phases = List.of(
                snapshot.models().stream().filter(PileModel::panel).toList(),
                snapshot.models().stream().filter(model -> model.elongated() && !model.panel()).toList(),
                snapshot.models().stream().filter(PileModel::bulk).toList(),
                snapshot.models().stream().filter(model -> !model.elongated() && !model.bulk() && model.volume() >= .15f).toList(),
                snapshot.models().stream().filter(model -> !model.elongated() && !model.bulk() && model.volume() < .3f).toList());
        List<List<OrientedBox>> anchorsByOwner = new ArrayList<>(cells.size());
        for (GridPos cell : cells) {
            interrupted();
            List<OrientedBox> anchors = occupied.nearby(
                    cell.x() + .5f, cell.y() + .5f, cell.z() + .5f, 1.1f);
            anchors.addAll(obstacles.nearby(
                    cell.x() + .5f, cell.y() + .5f, cell.z() + .5f, 1.1f));
            anchorsByOwner.add(anchors);
        }
        for (int phase = 0; phase < 5; phase++) {
            List<PileModel> models = phases.get(phase);
            double weight = models.stream().mapToDouble(PileModel::weight).sum();
            for (int owner = 0; owner < cells.size(); owner++) {
                interrupted();
                int range = (phase * cells.size() + owner) * 2;
                ranges[range] = candidates.size();
                GridPos cell = cells.get(owner);
                List<OrientedBox> anchors = anchorsByOwner.get(owner);
                for (int trial = 0; trial < (models.isEmpty() ? 0 : TRIALS[phase]); trial++) {
                    double choice = random.nextDouble() * weight;
                    PileModel model = models.get(models.size() - 1);
                    for (PileModel option : models) { choice -= option.weight(); if (choice <= 0) { model = option; break; } }
                    Vector3f center = new Vector3f(cell.x() + .08f + random.nextFloat() * .84f,
                            cell.y() + .08f + random.nextFloat() * .84f, cell.z() + .08f + random.nextFloat() * .84f);
                    OrientedBox anchor = null;
                    if (phase != 0 && !anchors.isEmpty() && random.nextInt(4) != 0) {
                        anchor = anchors.get(random.nextInt(anchors.size()));
                        float distance = anchor.point(0, 0, 0).distanceSquared(center);
                        for (int i = 0; i < 5; i++) {
                            OrientedBox other = anchors.get(random.nextInt(anchors.size()));
                            float d = other.point(0, 0, 0).distanceSquared(center);
                            if (d < distance) { anchor = other; distance = d; }
                        }
                    }
                    OrientedBox.Contact surface = anchor == null ? null
                            : anchor.contactToward(center, random.nextFloat() < .45f, 1);
                    Quaternionf rotation = model.panel()
                            ? ArchivedPileGeometry.panelOrientation(model, cell, mask, obstacles, random)
                            : ArchivedPileGeometry.orientation(model, random, surface);
                    Vector3f span = model.span();
                    if (phase != 0 && anchor == null && random.nextInt(4) != 0) {
                        rotation = new Quaternionf(surfaces.get(random.nextInt(surfaces.size())));
                        Vector3f thin = span.x <= span.y && span.x <= span.z ? new Vector3f(1, 0, 0)
                                : span.y <= span.z ? new Vector3f(0, 1, 0) : new Vector3f(0, 0, 1);
                        rotation.mul(new Quaternionf().rotationTo(thin, new Vector3f(0, 1, 0)))
                                .rotateAxis(random.nextInt(4) * (float) (Math.PI / 2), thin.x, thin.y, thin.z);
                    }
                    if (axisProposals && phase >= 2 && trial % 4 == 0)
                        rotation = new Quaternionf().rotationY((trial / 4 % 4) * (float) (Math.PI / 2));
                    float scale = .95f + random.nextFloat() * .1f;
                    RegionPiece piece = ArchivedPileGeometry.atCenter(model, center, scale, rotation, cell);
                    int start = boxGeometry.size();
                    boxGeometry.addAll(piece.colliders());
                    int count = boxGeometry.size() - start, rayStart = boxGeometry.size();
                    if (model.panel()) {
                        List<OrientedBox> rays = PanelExposure.corridors(piece, mask, new SpatialIndex(), obstacles);
                        if (rays.size() >= 3) boxGeometry.addAll(rays.subList(0, 3));
                    }
                    int rayCount = boxGeometry.size() - rayStart, anchorId = -1;
                    if (anchor != null) {
                        int face = 0;
                        Vector3f anchorCenter = anchor.point(0, 0, 0);
                        for (int axis = 0; axis < 3; axis++) {
                            Vector3f edge = anchor.point(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0)
                                    .sub(anchorCenter).normalize();
                            float projection = edge.dot(surface.normal());
                            if (Math.abs(projection) > .9f) { face = axis * 2 + (projection < 0 ? 1 : 0); break; }
                        }
                        anchorId = boxGeometry.size() * 8 + face;
                        boxGeometry.add(anchor);
                    }
                    meta.add(new int[]{start, count, owner, phase,
                            Math.max(1, Math.round(128f / (float) Math.pow(model.volume(), .25))),
                            rayStart, rayCount, anchorId, Math.round(center.x * 1024),
                            Math.round(center.y * 1024), Math.round(center.z * 1024), random.nextInt()});
                    candidates.add(piece);
                }
                ranges[range + 1] = candidates.size();
            }
        }
        h[10] = data.size; meta.forEach(data::add); h[11] = meta.size();
        h[12] = data.size; data.add(encodeGeometry(boxGeometry));
        h[13] = data.size; entries.forEach(data::add); h[14] = entries.size();
        boolean filler = !phases.get(3).isEmpty() || !phases.get(4).isEmpty();
        h[15] = data.size;
        data.add(filler || !phases.get(1).isEmpty() || !phases.get(2).isEmpty() ? Math.max(1, work.size() / 12) : 100000,
                filler || !phases.get(2).isEmpty() ? Math.max(1, work.size() / 16) : 100000,
                filler ? Math.max(1, work.size() / 5) : 100000, 100000, 100000);
        h[16] = data.size; data.add(ranges);
        h[17] = data.size;
        int[] points = new int[cells.size() * 375];
        Random jitter = new Random(snapshot.seed());
        for (GridPos cell : work.stream().sorted().toList()) {
            int owner = owners.get(cell);
            for (int x = 0; x < 5; x++) for (int y = 0; y < 5; y++) for (int z = 0; z < 5; z++) {
                int p = owner * 375 + (x * 25 + y * 5 + z) * 3;
                points[p] = Math.round((cell.x() + (x + .2f + jitter.nextFloat() * .6f) / 5) * 1024);
                points[p + 1] = Math.round((cell.y() + (y + .2f + jitter.nextFloat() * .6f) / 5) * 1024);
                points[p + 2] = Math.round((cell.z() + (z + .2f + jitter.nextFloat() * .6f) / 5) * 1024);
            }
        }
        data.add(points);
        int[] input = data.array();
        System.arraycopy(h, 0, input, 0, HEADER);
        int n = cells.size(), capacity = Math.min(Arrays.stream(ROUNDS).sum() * n, candidates.size());
        int outputCapacity = Arrays.stream(ROUNDS).sum() * n;
        int[] s = new int[HEADER];
        int cursor = HEADER;
        s[5] = cursor; cursor += outputCapacity * 4;
        s[6] = cursor; s[7] = entries.size() + capacity * 2; cursor += s[7] * ENTRY;
        s[8] = cursor; cursor += h[6];
        s[9] = cursor; s[10] = Math.max(8192, s[7] * 48); cursor += s[10] * 2;
        s[11] = cursor; cursor += n * 4;
        s[12] = cursor; cursor += n;
        s[13] = cursor; cursor += n;
        s[14] = cursor; cursor += candidates.size() * 2;
        s[15] = cursor; cursor += candidates.size();
        s[16] = cursor; cursor += 5;
        s[17] = cursor; cursor += n;
        s[18] = cursor; cursor += n;
        s[19] = cursor; cursor += candidates.size();
        s[20] = cursor++;
        s[21] = cursor; cursor += n;
        s[22] = cursor++;
        int[] state = new int[cursor];
        System.arraycopy(s, 0, state, 0, HEADER);
        Arrays.fill(state, s[8], s[8] + h[6], -1);
        Arrays.fill(state, s[5], s[5] + outputCapacity * 4, -1);
        Arrays.fill(state, s[17], s[17] + n, -1);
        for (int[] entry : entries) {
            appendInitial(input, state, entry);
            if (entry[3] != 1) coverInitial(input, state, entry);
        }
        for (RegionPiece piece : kept) {
            Integer owner = owners.get(piece.owner());
            if (owner != null) state[s[13] + owner]++;
            PileModel model = piece.piece().model();
            if (model.panel()) state[s[16]]++;
            else if (model.elongated()) state[s[16] + 1]++;
            else if (model.bulk()) state[s[16] + 2]++;
        }
        if (state[3] != 0) throw new CapacityException("Initial occupancy exceeds batch layout limits");
        return new ArchivedBatchLayout(input, state, candidates, kept, work, snapshot.generation());
    }
    public LayoutResult decode(int[] state) {
        if (state[3] != 0) throw new CapacityException("Batch occupancy capacity exceeded");
        List<RegionPiece> result = new ArrayList<>(preserved);
        if (state[0] < 0 || state[0] > candidates.size()) throw new IllegalStateException("Invalid batch result size");
        int decoded = 0, owners = input[7], slot = 0;
        for (int phase = 0; phase < ROUNDS.length; phase++) for (int round = 0; round < ROUNDS[phase]; round++)
            for (int k = 0; k < owners; k++, slot++) {
                int record = state[5] + slot * 4, id = state[record];
                if (id < 0) continue;
                int m = input[10] + id * META;
                RegionPiece piece = candidates.get(id);
                result.add(new RegionPiece(piece.owner(), piece.piece().translated(
                        (state[record + 1] - input[m + 8]) / 1024f, (state[record + 2] - input[m + 9]) / 1024f,
                        (state[record + 3] - input[m + 10]) / 1024f)));
                decoded++;
            }
        if (decoded != state[0]) throw new IllegalStateException("Batch result count does not match placed candidates");
        boolean incomplete = state[4] < work.size() * 80;
        for (int i = 0; i < work.size(); i++) incomplete |= state[state[12] + i] < 44;
        return new LayoutResult(result, pass < 7 && state[0] > 0 && incomplete
                && state[4] - initial[4] >= Math.max(1, work.size() / 2), work);
    }
    private static void interrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Batch layout interrupted");
    }
    private static final class EncodingWorkers {
        static final ForkJoinPool POOL = new ForkJoinPool(Math.max(1,
                Math.min(3, Runtime.getRuntime().availableProcessors() - 2)));
    }
    private static int[] encodeGeometry(List<OrientedBox> boxes) {
        int[] encoded = new int[boxes.size() * BOX];
        if (boxes.size() < 512) {
            for (int i = 0; i < boxes.size(); i++) FixedGeometry.encode(boxes.get(i), encoded, i * BOX);
        } else EncodingWorkers.POOL.invoke(new Encoding(boxes, encoded, 0, boxes.size()));
        return encoded;
    }
    private static final class Encoding extends RecursiveAction {
        private final List<OrientedBox> boxes;
        private final int[] encoded;
        private final int first, last;
        Encoding(List<OrientedBox> boxes, int[] encoded, int first, int last) {
            this.boxes = boxes; this.encoded = encoded; this.first = first; this.last = last;
        }
        @Override protected void compute() {
            if (last - first <= 256) {
                for (int i = first; i < last; i++) FixedGeometry.encode(boxes.get(i), encoded, i * BOX);
            } else {
                int middle = (first + last) >>> 1;
                invokeAll(new Encoding(boxes, encoded, first, middle), new Encoding(boxes, encoded, middle, last));
            }
        }
    }
    private static void appendInitial(int[] input, int[] state, int[] entry) {
        if (state[1] >= state[7]) throw new CapacityException("Initial GPU occupancy entries exceed capacity");
        int id = state[1]++, offset = state[6] + id * ENTRY;
        state[offset] = entry[0]; state[offset + 1] = entry[1]; state[offset + 2] = 0; state[offset + 3] = entry[3];
        int[] boxBounds = geometryBounds(input, entry[0], entry[1], 3);
        state[offset + 4] = boxBounds[0]; state[offset + 5] = boxBounds[1]; state[offset + 6] = boxBounds[2];
        for (int x = boxBounds[0]; x <= boxBounds[3]; x++)
            for (int y = boxBounds[1]; y <= boxBounds[4]; y++)
                for (int z = boxBounds[2]; z <= boxBounds[5]; z++) {
                    int cell = index(input, x, y, z);
                    if (cell < 0) continue;
                    if (state[2] >= state[10]) throw new CapacityException("Initial GPU occupancy nodes exceed capacity");
                    int node = state[2]++, link = state[9] + node * 2;
                    state[link] = id; state[link + 1] = state[state[8] + cell]; state[state[8] + cell] = node;
                }
    }
    private static void coverInitial(int[] input, int[] state, int[] entry) {
        int[] boxBounds = geometryBounds(input, entry[0], entry[1], 0);
        for (int x = boxBounds[0]; x <= boxBounds[3]; x++)
            for (int y = boxBounds[1]; y <= boxBounds[4]; y++)
                for (int z = boxBounds[2]; z <= boxBounds[5]; z++) {
                    int index = index(input, x, y, z);
                    if (index < 0) continue;
                    int owner = input[input[8] + index] - 2;
                    if (owner < 0) continue;
                    for (int sample = 0; sample < 125; sample++) {
                        int address = state[11] + owner * 4 + sample / 32, bit = 1 << (sample % 32);
                        if ((state[address] & bit) != 0) continue;
                        int point = input[17] + owner * 375 + sample * 3;
                        for (int box = 0; box < entry[1]; box++) {
                            int boxId = input[12] + (entry[0] + box) * BOX;
                            if (FixedGeometry.contains(input, boxId, 0,
                                    input[point], input[point + 1], input[point + 2])) {
                                state[address] |= bit;
                                state[state[12] + owner]++;
                                state[4]++;
                                break;
                            }
                        }
                    }
                }
    }
    private static int[] geometryBounds(int[] input, int start, int count, int margin) {
        int[] result = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int i = 0; i < count; i++) {
            int box = input[12] + (start + i) * BOX;
            for (int axis = 0; axis < 3; axis++) {
                int center = input[box + axis], extent = FixedGeometry.extent(input, box, axis) + margin;
                result[axis] = Math.min(result[axis], Math.floorDiv(center - extent, 1024));
                result[axis + 3] = Math.max(result[axis + 3], Math.floorDiv(center + extent, 1024));
            }
        }
        return result;
    }
    private static void addEntry(List<int[]> entries, List<OrientedBox> boxes, List<OrientedBox> colliders, int guard) {
        int start = boxes.size();
        boxes.addAll(colliders);
        if (boxes.size() > start) entries.add(new int[]{start, boxes.size() - start, 0, guard});
    }
    private static int[] bounds(Set<GridPos> domain, int margin) {
        int x = Integer.MAX_VALUE, y = x, z = x, a = Integer.MIN_VALUE, b = a, c = a;
        for (GridPos cell : domain) {
            x = Math.min(x, cell.x()); y = Math.min(y, cell.y()); z = Math.min(z, cell.z());
            a = Math.max(a, cell.x()); b = Math.max(b, cell.y()); c = Math.max(c, cell.z());
        }
        return new int[]{x - margin, y - margin, z - margin, a - x + 1 + margin * 2,
                b - y + 1 + margin * 2, c - z + 1 + margin * 2};
    }
    static int index(int[] a, int x, int y, int z) {
        x -= a[0]; y -= a[1]; z -= a[2];
        return x < 0 || y < 0 || z < 0 || x >= a[3] || y >= a[4] || z >= a[5] ? -1
                : (x * a[4] + y) * a[5] + z;
    }
    private static final class Ints {
        int[] values = new int[4096]; int size;
        void add(int... data) {
            if (size + data.length > values.length) values = Arrays.copyOf(values, Math.max(size + data.length, values.length * 2));
            System.arraycopy(data, 0, values, size, data.length); size += data.length;
        }
        int[] array() { return Arrays.copyOf(values, size); }
    }
}
