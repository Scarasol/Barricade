package com.scarasol.barricade.pile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class LegacyPileGenerator {
    public static final int MAX_PIECES = 72;
    public static final float MIN_SCALE = 0.95f;
    public static final float MAX_SCALE = 1.05f;
    public static final float OVERHANG = 0.22f;
    private static final int SAMPLE_RESOLUTION = 5;
    private static final float TARGET_COVERAGE = 0.64f;
    private static final float INTERSECTION_ALLOWANCE = 0.008f;
    private static final int MAX_ATTEMPTS_PER_JOB = 80_000;

    private LegacyPileGenerator() {
    }

    public record Snapshot(long seed, int generation, Set<GridPos> domain,
                           List<PileModel> models, List<RegionPiece> existing,
                           List<OrientedBox> neighbors, List<OrientedBox> obstacles,
                           List<OrientedBox> unloaded, Set<GridPos> active, Set<GridPos> repack,
                           boolean preserveExisting) {
        public Snapshot(long seed, int generation, Set<GridPos> domain,
                        List<PileModel> models, List<RegionPiece> existing,
                        List<OrientedBox> neighbors, List<OrientedBox> obstacles,
                        List<OrientedBox> unloaded, Set<GridPos> active, Set<GridPos> repack) {
            this(seed, generation, domain, models, existing, neighbors, obstacles, unloaded, active, repack, false);
        }
        public Snapshot(long seed, int generation, Set<GridPos> domain, List<PileModel> models,
                        List<RegionPiece> existing, List<OrientedBox> neighbors,
                        List<OrientedBox> obstacles, List<OrientedBox> unloaded) {
            this(seed, generation, domain, models, existing, neighbors, obstacles, unloaded, domain, Set.of());
        }

        public Snapshot {
            domain = Set.copyOf(domain);
            models = List.copyOf(models);
            existing = List.copyOf(existing);
            neighbors = List.copyOf(neighbors);
            obstacles = List.copyOf(obstacles);
            unloaded = List.copyOf(unloaded);
            active = Set.copyOf(active);
            repack = Set.copyOf(repack);
            if (domain.isEmpty() || models.isEmpty() || active.isEmpty()
                    || !domain.containsAll(active) || !active.containsAll(repack)) {
                throw new IllegalArgumentException("A pile needs a domain and a palette");
            }
        }
    }

    public record Result(List<RegionPiece> pieces, boolean continueFilling, Set<GridPos> work) {
        public Result {
            pieces = List.copyOf(pieces);
            work = Set.copyOf(work);
        }
    }

    public static Result generate(Snapshot input) {
        CellMask domain = new CellMask(input.domain);
        SpatialIndex occupied = new SpatialIndex();
        input.neighbors.forEach(occupied::add);
        SpatialIndex obstacles = new SpatialIndex();
        input.obstacles.forEach(obstacles::add);
        List<RegionPiece> result = new ArrayList<>();
        Map<GridPos, Integer> ownerCounts = new HashMap<>();
        Map<String, Integer> modelCounts = new HashMap<>();
        Set<GridPos> work = new HashSet<>(input.active);
        SpatialIndex disturbed = new SpatialIndex();
        input.repack.forEach(pos -> disturbed.add(OrientedBox.axisAligned(
                pos.x(), pos.y(), pos.z(), pos.x() + 1, pos.y() + 1, pos.z() + 1)));
        List<RegionPiece> preserved = new ArrayList<>();
        for (RegionPiece piece : input.existing) {
            checkInterrupted();
            List<OrientedBox> boxes = piece.colliders();
            if (boxes.stream().anyMatch(box -> disturbed.intersects(box, 0))) {
                addAffectedCells(work, boxes, domain);
                continue;
            }
            if (domain.contains(piece.owner()) && (input.preserveExisting
                    || valid(boxes, domain, occupied, obstacles)
                    && ownerCounts.getOrDefault(piece.owner(), 0) < MAX_PIECES)) {
                result.add(piece);
                preserved.add(piece);
                ownerCounts.merge(piece.owner(), 1, Integer::sum);
                modelCounts.merge(piece.piece().model().id(), 1, Integer::sum);
                boxes.forEach(occupied::add);
            } else {
                addAffectedCells(work, boxes, domain);
            }
        }
        Coverage coverage = new Coverage(input.seed, work);
        coverage.add(input.neighbors);
        preserved.forEach(piece -> coverage.add(piece.colliders()));
        float initialCoverage = coverage.fraction();
        // Unknown terrain prevents new overhangs, but never erases already saved geometry.
        input.unloaded.forEach(obstacles::add);
        Random random = new Random(input.seed ^ (0x9E3779B97F4A7C15L * (input.generation + 1L)));
        int[] attemptsPerCell = {60, 120, 240, 1200, 1350};
        List<List<PileModel>> phases = List.of(
                input.models.stream().filter(PileModel::panel).toList(),
                input.models.stream().filter(model -> model.elongated() && !model.panel()).toList(),
                input.models.stream().filter(PileModel::bulk).toList(),
                input.models.stream().filter(model -> !model.bulk() && !model.elongated()
                        && model.volume() >= 0.15f).toList(),
                input.models.stream().filter(model -> !model.bulk() && !model.elongated()
                        && model.volume() < 0.3f).toList());
        int totalAttempts = 0;
        for (int phase = 0; phase < phases.size(); phase++) {
            if (!phases.get(phase).isEmpty()) totalAttempts += attemptsPerCell[phase];
        }
        boolean hasFiller = !phases.get(3).isEmpty() || !phases.get(4).isEmpty();
        int panelLimit = hasFiller || !phases.get(1).isEmpty() || !phases.get(2).isEmpty()
                ? Math.max(1, work.size() / 12) : Integer.MAX_VALUE;
        int longLimit = hasFiller || !phases.get(2).isEmpty()
                ? Math.max(1, work.size() / 16) : Integer.MAX_VALUE;
        int bulkLimit = hasFiller ? Math.max(1, work.size() / 5) : Integer.MAX_VALUE;
        int additions = 0;
        int panels = (int) preserved.stream().filter(piece -> piece.piece().model().panel()).count();
        int bulk = (int) preserved.stream().filter(piece -> piece.piece().model().bulk()).count();
        int longItems = (int) preserved.stream().filter(piece -> piece.piece().model().elongated()
                && !piece.piece().model().panel()).count();
        List<PanelExposure.Guard> guards = new ArrayList<>();
        for (RegionPiece piece : preserved) {
            if (piece.piece().model().panel()) {
                guards.add(new PanelExposure.Guard(PanelExposure.corridors(piece, domain, occupied, obstacles)));
            }
        }
        Map<GridPos, List<OrientedBox>> contactCache = new HashMap<>();
        Map<GridPos, List<OrientedBox>> obstacleContacts = new HashMap<>();
        Vector3f anchorCenter = new Vector3f(), zero = new Vector3f();
        for (int phase = 0; phase < attemptsPerCell.length; phase++) {
            List<PileModel> models = phases.get(phase);
            if (models.isEmpty()) {
                continue;
            }
            float totalWeight = (float) models.stream().mapToDouble(PileModel::weight).sum();
            long requested = (long) attemptsPerCell[phase] * work.size();
            int attempts = (int) Math.min(MAX_ATTEMPTS_PER_JOB * attemptsPerCell[phase] / totalAttempts, requested);
            RegionPiece best = null;
            float bestScore = 0;
            for (int attempt = 0; attempt < attempts; attempt++) {
                checkInterrupted();
                if (phase == 0 && panels >= panelLimit
                        || phase == 1 && longItems >= longLimit
                        || phase == 2 && bulk >= bulkLimit) {
                    break;
                }
                if (coverage.complete()) {
                    return new Result(result, false, work);
                }
                Sample sample = coverage.uncovered(random);
                PileModel model = chooseModel(models, totalWeight, random);
                OrientedBox.Contact contact = null;
                if (random.nextFloat() < 0.7f) {
                    List<OrientedBox> anchors = contactCache.computeIfAbsent(sample.cell, cell -> {
                        List<OrientedBox> nearby = occupied.nearby(cell.x() + 0.5f, cell.y() + 0.5f,
                                cell.z() + 0.5f, 1.1f);
                        nearby.addAll(obstacleContacts.computeIfAbsent(cell, key ->
                                obstacles.nearby(key.x() + 0.5f, key.y() + 0.5f, key.z() + 0.5f, 1.1f)));
                        return nearby;
                    });
                    if (!anchors.isEmpty()) {
                        OrientedBox anchor = anchors.get(random.nextInt(anchors.size()));
                        float closest = anchor.point(0, 0, 0, anchorCenter).distanceSquared(sample.point);
                        for (int pick = 0; pick < 5; pick++) {
                            OrientedBox other = anchors.get(random.nextInt(anchors.size()));
                            float distance = other.point(0, 0, 0, anchorCenter).distanceSquared(sample.point);
                            if (distance < closest) {
                                anchor = other;
                                closest = distance;
                            }
                        }
                        float offset = random.nextFloat() < 0.45f ? random.nextFloat() * 0.25f : 1;
                        contact = anchor.contactToward(sample.point, random.nextFloat() < 0.45f, offset);
                    }
                }
                Quaternionf rotation = model.panel() && random.nextFloat() < 0.8f
                        ? panelOrientation(model, sample.cell, domain, obstacles, random)
                        : orientation(model, random, contact);
                float scale = MIN_SCALE + random.nextFloat() * (MAX_SCALE - MIN_SCALE);
                Vector3f target = new Vector3f(sample.point);
                if (contact != null) {
                    RegionPiece centered = atCenter(model, zero, scale, rotation, new GridPos(0, 0, 0));
                    float min = Float.POSITIVE_INFINITY;
                    for (OrientedBox box : centered.colliders()) {
                        min = Math.min(min, box.minimumProjection(contact.normal(), zero));
                    }
                    target.set(contact.point()).fma(0.006f - min, contact.normal());
                } else {
                    target.add((random.nextFloat() - 0.5f) * 0.25f,
                            (random.nextFloat() - 0.5f) * 0.25f, (random.nextFloat() - 0.5f) * 0.25f);
                }
                RegionPiece candidate = atCenter(model, target, scale, rotation, sample.cell);
                candidate = reassign(candidate, domain, work);
                List<OrientedBox> boxes = candidate == null ? List.of() : candidate.colliders();
                if (candidate != null && ownerCounts.getOrDefault(candidate.owner(), 0) < MAX_PIECES
                        && valid(boxes, domain, occupied, obstacles)) {
                    candidate = reassign(settle(candidate, domain, occupied, obstacles, random),
                            domain, work);
                    if (candidate != null && ownerCounts.getOrDefault(candidate.owner(), 0) < MAX_PIECES) {
                        boxes = candidate.colliders();
                        boolean hidden = false;
                        for (PanelExposure.Guard guard : guards) if (!guard.permits(boxes)) {
                            hidden = true;
                            break;
                        }
                        if (hidden) continue;
                        int added = coverage.added(boxes);
                        float score = score(candidate, boxes, added, occupied, obstacles, modelCounts, result.size());
                        if (model.panel()) {
                            int visible = PanelExposure.corridors(candidate, domain, occupied, obstacles).size();
                            if (visible < 3) continue;
                            score += visible * 3;
                        }
                        if (added > 0 && score > bestScore) {
                            best = candidate;
                            bestScore = score;
                        }
                    }
                }
                if ((attempt % 12 == 11 || attempt == attempts - 1) && best != null) {
                    result.add(best);
                    ownerCounts.merge(best.owner(), 1, Integer::sum);
                    boxes = best.colliders();
                    for (PanelExposure.Guard guard : guards) guard.accept(boxes);
                    if (best.piece().model().panel()) {
                        guards.add(new PanelExposure.Guard(
                                PanelExposure.corridors(best, domain, occupied, obstacles)));
                    }
                    boxes.forEach(occupied::add);
                    coverage.add(boxes);
                    modelCounts.merge(best.piece().model().id(), 1, Integer::sum);
                    if (best.piece().model().panel()) {
                        panels++;
                    }
                    if (best.piece().model().bulk()) {
                        bulk++;
                    }
                    if (best.piece().model().elongated() && !best.piece().model().panel()) longItems++;
                    additions++;
                    var cached = contactCache.keySet().iterator();
                    while (cached.hasNext()) {
                        GridPos cell = cached.next();
                        for (OrientedBox box : boxes) if (SpatialIndex.affectsNearby(box, cell, 1.1f)) {
                            cached.remove();
                            break;
                        }
                    }
                    best = null;
                    bestScore = 0;
                }
            }
        }
        // Preserve this pass and refine remaining gaps in another bounded worker job.
        return new Result(result, additions > 0 && coverage.fraction() - initialCoverage >= 0.004f
                && !coverage.complete(), work);
    }

    static Quaternionf panelOrientation(PileModel model, GridPos cell, CellMask domain,
                                                SpatialIndex obstacles, Random random) {
        Vector3f span = model.span();
        float min = Math.min(span.x, Math.min(span.y, span.z));
        float max = Math.max(span.x, Math.max(span.y, span.z));
        Vector3f thin = span.x == min ? new Vector3f(1, 0, 0)
                : span.y == min ? new Vector3f(0, 1, 0) : new Vector3f(0, 0, 1);
        Vector3f longAxis = span.x == max ? new Vector3f(1, 0, 0)
                : span.y == max ? new Vector3f(0, 1, 0) : new Vector3f(0, 0, 1);
        List<Vector3f> outside = new ArrayList<>();
        for (Vector3f direction : List.of(new Vector3f(1, 0, 0), new Vector3f(-1, 0, 0),
                new Vector3f(0, 0, 1), new Vector3f(0, 0, -1), new Vector3f(0, 1, 0))) {
            GridPos next = new GridPos(cell.x() + (int) direction.x, cell.y() + (int) direction.y,
                    cell.z() + (int) direction.z);
            if (!domain.contains(next) && !obstacles.contains(next.x() + 0.5f, next.y() + 0.5f, next.z() + 0.5f)) {
                outside.add(direction);
            }
        }
        Vector3f normal = outside.isEmpty() ? new Vector3f(0, 1, 0)
                : outside.get(random.nextInt(outside.size()));
        Quaternionf rotation = new Quaternionf().rotationTo(thin, normal);
        Vector3f desired = Math.abs(normal.y) < 0.8f ? new Vector3f(0, 1, 0)
                : random.nextBoolean() ? new Vector3f(1, 0, 0) : new Vector3f(0, 0, 1);
        Vector3f mapped = rotation.transform(new Vector3f(longAxis));
        float spin = (float) Math.atan2(normal.dot(new Vector3f(mapped).cross(desired)), mapped.dot(desired));
        if (random.nextFloat() < 0.3f) spin += (float) (Math.PI / 2);
        return rotation.rotateAxis(spin + (random.nextFloat() - 0.5f) * 0.5f, thin.x, thin.y, thin.z)
                .rotateX((random.nextFloat() - 0.5f) * 0.25f)
                .rotateZ((random.nextFloat() - 0.5f) * 0.25f);
    }

    public static float panelVisibility(RegionPiece panel, Set<GridPos> domain,
                                        List<RegionPiece> pieces, List<OrientedBox> obstacleBoxes) {
        SpatialIndex occupied = new SpatialIndex(), obstacles = new SpatialIndex();
        pieces.stream().filter(piece -> piece != panel).forEach(piece -> piece.colliders().forEach(occupied::add));
        obstacleBoxes.forEach(obstacles::add);
        return PanelExposure.corridors(panel, new CellMask(domain), occupied, obstacles).size() / 9f;
    }

    static Quaternionf orientation(PileModel model, Random random, OrientedBox.Contact contact) {
        Vector3f span = model.span();
        float min = Math.min(span.x, Math.min(span.y, span.z));
        float max = Math.max(span.x, Math.max(span.y, span.z));
        float yaw = random.nextInt(4) * (float) (Math.PI / 2)
                + (float) (Math.PI / 12) + random.nextFloat() * (float) (Math.PI / 3);
        if (min < max * 0.6f) {
            Vector3f thin = span.x == min ? new Vector3f(1, 0, 0)
                    : span.y == min ? new Vector3f(0, 1, 0) : new Vector3f(0, 0, 1);
            Vector3f normal = contact == null ? new Vector3f(0, 1, 0) : contact.normal();
            if (contact == null && random.nextFloat() < 0.22f) {
                normal = new Vector3f((float) Math.cos(yaw), 0, (float) Math.sin(yaw));
            }
            Quaternionf rotation = new Quaternionf().rotationTo(thin, normal);
            boolean followSurface = contact != null && random.nextFloat() < 0.85f;
            if (followSurface) {
                Vector3f longAxis = span.x == max ? new Vector3f(1, 0, 0)
                        : span.y == max ? new Vector3f(0, 1, 0) : new Vector3f(0, 0, 1);
                Vector3f mapped = rotation.transform(new Vector3f(longAxis));
                yaw = (float) Math.atan2(normal.dot(new Vector3f(mapped).cross(contact.tangent())),
                        mapped.dot(contact.tangent())) + (random.nextFloat() - 0.5f) * 0.08f;
            }
            float tilt = followSurface ? 0.035f : 0.35f;
            return rotation.rotateAxis(yaw, thin.x, thin.y, thin.z)
                    .rotateX((random.nextFloat() - 0.5f) * tilt)
                    .rotateZ((random.nextFloat() - 0.5f) * tilt);
        }
        Quaternionf result = new Quaternionf().rotationYXZ(yaw,
                (random.nextFloat() - 0.5f) * 0.5f, (random.nextFloat() - 0.5f) * 0.5f);
        return random.nextFloat() < 0.35f ? result.rotateZ((float) (Math.PI / 2)) : result;
    }

    static RegionPiece atCenter(PileModel model, Vector3f center, float scale,
                                        Quaternionf rotation, GridPos owner) {
        Vector3f offset = model.collisionCenter().sub(model.pivotX(), model.pivotY(), model.pivotZ())
                .mul(model.sizeX() * scale, model.sizeY() * scale, model.sizeZ() * scale);
        rotation.transform(offset);
        return new RegionPiece(owner, PilePiece.create(model, center.x - offset.x - owner.x(),
                center.y - offset.y - owner.y(), center.z - offset.z - owner.z(), scale, rotation));
    }

    private static RegionPiece reassign(RegionPiece piece, CellMask domain, Set<GridPos> work) {
        PilePiece local = piece.piece();
        int x = piece.owner().x(), y = piece.owner().y(), z = piece.owner().z();
        Vector3f modelCenter = local.model().collisionCenter();
        Vector3f center = local.transformPoint(modelCenter.x, modelCenter.y, modelCenter.z).add(x, y, z);
        GridPos owner = new GridPos((int) Math.floor(center.x), (int) Math.floor(center.y), (int) Math.floor(center.z));
        if (!domain.contains(owner)) {
            owner = piece.owner();
        }
        if (!work.contains(owner)) {
            return null;
        }
        return new RegionPiece(owner, local.translated(x - owner.x(), y - owner.y(), z - owner.z()));
    }

    private static float score(RegionPiece candidate, List<OrientedBox> boxes, int added,
                               SpatialIndex occupied, SpatialIndex obstacles,
                               Map<String, Integer> modelCounts, int count) {
        if (added == 0) {
            return 0;
        }
        PilePiece piece = candidate.piece();
        float repeats = (float) modelCounts.getOrDefault(piece.model().id(), 0) / (count + 1);
        float contact = 0;
        for (OrientedBox box : boxes) {
            if (occupied.intersects(box, -0.045f) || obstacles.intersects(box, -0.045f)) {
                contact = 5;
                break;
            }
        }
        return added / (float) Math.pow(piece.model().volume(), 0.25)
                * (1 - repeats * 0.35f - (axisAlignment(piece.rotation()) > 0.985f ? 0.28f : 0)) + contact;
    }

    public static float axisAlignment(Quaternionf rotation) {
        float sum = 0;
        for (Vector3f axis : List.of(new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1))) {
            rotation.transform(axis);
            sum += Math.max(Math.abs(axis.x), Math.max(Math.abs(axis.y), Math.abs(axis.z)));
        }
        return sum / 3;
    }

    private static void addAffectedCells(Set<GridPos> work, List<OrientedBox> boxes, CellMask domain) {
        for (OrientedBox box : boxes) {
            for (int x = (int) Math.floor(box.minX()); x <= Math.floor(box.maxX()); x++) {
                for (int y = (int) Math.floor(box.minY()); y <= Math.floor(box.maxY()); y++) {
                    for (int z = (int) Math.floor(box.minZ()); z <= Math.floor(box.maxZ()); z++) {
                        GridPos cell = new GridPos(x, y, z);
                        if (domain.contains(cell)) {
                            work.add(cell);
                        }
                    }
                }
            }
        }
    }

    public static boolean fitsDomain(List<OrientedBox> boxes, Set<GridPos> domain) {
        return fitsDomain(boxes, new CellMask(domain));
    }

    private static boolean fitsDomain(List<OrientedBox> boxes, CellMask domain) {
        Vector3f point = new Vector3f();
        for (OrientedBox box : boxes) {
            if (domain.containsBounds(box)) continue;
            // Subdivide long colliders too, so a beam cannot bridge a concave hole undetected.
            int nx = Math.max(2, (int) Math.ceil((box.maxX() - box.minX()) * 4));
            int ny = Math.max(2, (int) Math.ceil((box.maxY() - box.minY()) * 4));
            int nz = Math.max(2, (int) Math.ceil((box.maxZ() - box.minZ()) * 4));
            for (int x = 0; x <= nx; x++) {
                for (int y = 0; y <= ny; y++) {
                    for (int z = 0; z <= nz; z++) {
                        if (!nearDomain(box.point(2f * x / nx - 1, 2f * y / ny - 1, 2f * z / nz - 1, point), domain)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    public static float coverage(long seed, Set<GridPos> domain, List<OrientedBox> boxes) {
        Coverage coverage = new Coverage(seed, domain);
        coverage.add(boxes);
        return coverage.fraction();
    }

    private static boolean nearDomain(Vector3f point, CellMask domain) {
        int px = (int) Math.floor(point.x), py = (int) Math.floor(point.y), pz = (int) Math.floor(point.z);
        if (domain.contains(px, py, pz)) {
            return true;
        }
        for (int x = px - 1; x <= px + 1; x++) {
            for (int y = py - 1; y <= py + 1; y++) {
                for (int z = pz - 1; z <= pz + 1; z++) {
                    if (domain.contains(x, y, z)) {
                        float dx = Math.max(Math.max(x - point.x, 0), point.x - x - 1);
                        float dy = Math.max(Math.max(y - point.y, 0), point.y - y - 1);
                        float dz = Math.max(Math.max(z - point.z, 0), point.z - z - 1);
                        if (dx * dx + dy * dy + dz * dz <= OVERHANG * OVERHANG) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean valid(List<OrientedBox> boxes, CellMask domain,
                                 SpatialIndex occupied, SpatialIndex obstacles) {
        for (OrientedBox box : boxes) {
            if (occupied.intersects(box, INTERSECTION_ALLOWANCE) || obstacles.intersects(box, 0)) {
                return false;
            }
        }
        return fitsDomain(boxes, domain);
    }

    private static RegionPiece settle(RegionPiece original, CellMask domain,
                                      SpatialIndex occupied, SpatialIndex obstacles, Random random) {
        RegionPiece piece = original;
        int steps = 0;
        for (int round = 0; round < 3; round++) {
            while (steps++ < 48) {
                RegionPiece next = new RegionPiece(piece.owner(), piece.piece().translated(0, -0.035f, 0));
                if (!valid(next.colliders(), domain, occupied, obstacles)) {
                    break;
                }
                piece = next;
            }
            PilePiece local = piece.piece();
            PileModel model = local.model();
            Vector3f modelCenter = model.collisionCenter();
            Vector3f center = local.transformPoint(modelCenter.x, modelCenter.y, modelCenter.z)
                    .add(piece.owner().x(), piece.owner().y(), piece.owner().z());
            Quaternionf rotation = local.rotation().rotateX((random.nextFloat() - 0.5f) * 0.13f)
                    .rotateZ((random.nextFloat() - 0.5f) * 0.13f);
            RegionPiece relaxed = atCenter(model, new Vector3f(center).add(0, -0.01f, 0),
                    local.scale(), rotation, piece.owner());
            if (valid(relaxed.colliders(), domain, occupied, obstacles)) {
                piece = relaxed;
            }
        }
        return piece;
    }

    private static PileModel chooseModel(List<PileModel> models, float totalWeight, Random random) {
        float choice = random.nextFloat() * totalWeight;
        for (PileModel model : models) {
            choice -= model.weight();
            if (choice <= 0) {
                return model;
            }
        }
        return models.get(models.size() - 1);
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Pile generation interrupted");
        }
    }

    private static final class Sample {
        private final GridPos cell;
        private final Vector3f point;
        private int uncoveredIndex;
        private long visited;

        private Sample(GridPos cell, Vector3f point, int uncoveredIndex) {
            this.cell = cell;
            this.point = point;
            this.uncoveredIndex = uncoveredIndex;
        }
    }

    private static final class Coverage {
        private static final int MIN_CELL_SAMPLES = 44;
        private final CellMap<SampleBucket> buckets = new CellMap<>();
        private final List<SampleBucket> lowCoverage = new ArrayList<>();
        private final List<Sample> uncovered = new ArrayList<>();
        private final int total;
        private long query;

        private Coverage(long seed, Set<GridPos> domain) {
            Random random = new Random(seed);
            for (GridPos cell : domain.stream().sorted().toList()) {
                checkInterrupted();
                List<Sample> samples = new ArrayList<>();
                SampleBucket bucket = new SampleBucket(samples, lowCoverage.size());
                lowCoverage.add(bucket);
                for (int x = 0; x < SAMPLE_RESOLUTION; x++) {
                    for (int y = 0; y < SAMPLE_RESOLUTION; y++) {
                        for (int z = 0; z < SAMPLE_RESOLUTION; z++) {
                            Vector3f point = new Vector3f(cell.x() + (x + 0.2f + random.nextFloat() * 0.6f) / SAMPLE_RESOLUTION,
                                    cell.y() + (y + 0.2f + random.nextFloat() * 0.6f) / SAMPLE_RESOLUTION,
                                    cell.z() + (z + 0.2f + random.nextFloat() * 0.6f) / SAMPLE_RESOLUTION);
                            Sample sample = new Sample(cell, point, uncovered.size());
                            samples.add(sample);
                            uncovered.add(sample);
                        }
                    }
                }
                buckets.put(cell.x(), cell.y(), cell.z(), bucket);
            }
            total = uncovered.size();
        }

        private float fraction() {
            return 1f - (float) uncovered.size() / total;
        }

        private boolean complete() {
            return fraction() >= TARGET_COVERAGE && lowCoverage.isEmpty();
        }

        private Sample uncovered(Random random) {
            if (!lowCoverage.isEmpty() && random.nextFloat() < 0.65f) {
                List<Sample> samples = lowCoverage.get(random.nextInt(lowCoverage.size())).samples;
                int start = random.nextInt(samples.size());
                for (int i = 0; i < samples.size(); i++) {
                    Sample sample = samples.get((start + i) % samples.size());
                    if (sample.uncoveredIndex >= 0) {
                        return sample;
                    }
                }
            }
            return uncovered.get(random.nextInt(uncovered.size()));
        }

        private int added(List<OrientedBox> boxes) {
            return visit(boxes, false);
        }

        private void add(List<OrientedBox> boxes) {
            visit(boxes, true);
        }

        private int visit(List<OrientedBox> boxes, boolean mark) {
            int count = 0;
            query++;
            for (OrientedBox box : boxes) {
                for (int x = (int) Math.floor(box.minX()); x <= Math.floor(box.maxX()); x++) {
                    for (int y = (int) Math.floor(box.minY()); y <= Math.floor(box.maxY()); y++) {
                        for (int z = (int) Math.floor(box.minZ()); z <= Math.floor(box.maxZ()); z++) {
                            SampleBucket bucket = buckets.get(x, y, z);
                            if (bucket == null) {
                                continue;
                            }
                            for (Sample sample : bucket.samples) {
                                if (sample.uncoveredIndex >= 0 && sample.visited != query
                                        && box.contains(sample.point.x, sample.point.y, sample.point.z)) {
                                    sample.visited = query;
                                    count++;
                                    if (mark) {
                                        int index = sample.uncoveredIndex;
                                        Sample last = uncovered.remove(uncovered.size() - 1);
                                        if (index < uncovered.size()) {
                                            uncovered.set(index, last);
                                            last.uncoveredIndex = index;
                                        }
                                        sample.uncoveredIndex = -1;
                                        if (++bucket.covered == MIN_CELL_SAMPLES) {
                                            int bucketIndex = bucket.lowIndex;
                                            SampleBucket lastBucket = lowCoverage.remove(lowCoverage.size() - 1);
                                            if (bucketIndex < lowCoverage.size()) {
                                                lowCoverage.set(bucketIndex, lastBucket);
                                                lastBucket.lowIndex = bucketIndex;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return count;
        }
    }

    private static final class SampleBucket {
        private final List<Sample> samples;
        private int lowIndex;
        private int covered;

        private SampleBucket(List<Sample> samples, int lowIndex) {
            this.samples = samples;
            this.lowIndex = lowIndex;
        }
    }
}
