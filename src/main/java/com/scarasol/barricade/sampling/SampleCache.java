package com.scarasol.barricade.sampling;

import com.google.gson.*;
import com.scarasol.barricade.Barricade;
import com.scarasol.barricade.pile.CollisionBox;
import com.scarasol.barricade.pile.ModelPart;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.Direction;
import net.minecraft.nbt.TagParser;

/** Disposable client data. SNBT strings preserve the numeric types inside block entity data. */
public final class SampleCache implements AutoCloseable {
    private static final int VERSION = 1, MAX_ENTRIES = 8192, MAX_SCOPES = 16;
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    public record Entry(MaterialSampler.Key key, MaterialSampler.Sample sample, String failure) { }
    private final Path file;
    private final LinkedHashMap<String, LinkedHashMap<MaterialSampler.Key, Entry>> scopes = new LinkedHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Barricade sample cache");
        thread.setDaemon(true);
        return thread;
    });
    private CompletableFuture<Void> pending = CompletableFuture.completedFuture(null);
    private long revision, saved;
    private boolean closed;

    public SampleCache(Path file) {
        this.file = file.toAbsolutePath().normalize();
        read();
    }

    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public synchronized Entry get(String scope, MaterialSampler.Key key) {
        var entries = scopes.get(scope);
        return entries == null ? null : entries.get(key);
    }

    public synchronized void put(String scope, MaterialSampler.Key key, MaterialSampler.Sample sample, String failure) {
        if (scope.isEmpty()) return;
        var entries = scopes.computeIfAbsent(scope, ignored -> new LinkedHashMap<>());
        entries.put(key, new Entry(key, sample, failure));
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
        while (scopes.size() > MAX_SCOPES) scopes.remove(scopes.keySet().iterator().next());
        revision++;
    }

    public synchronized void invalidate(String scope) {
        if (scopes.remove(scope) != null) revision++;
    }

    public synchronized int size() {
        return scopes.values().stream().mapToInt(Map::size).sum();
    }

    public synchronized CompletableFuture<Void> flush() {
        if (revision == saved) return pending;
        long snapshotRevision = revision;
        Map<String, List<Entry>> snapshot = new TreeMap<>();
        scopes.forEach((scope, entries) -> snapshot.put(scope, List.copyOf(entries.values())));
        saved = snapshotRevision;
        pending = pending.handle((ignored, error) -> null).thenRunAsync(() -> {
            try {
                write(snapshot);
            } catch (IOException | RuntimeException exception) {
                synchronized (this) { if (saved == snapshotRevision) saved = -1; }
                Barricade.LOGGER.warn("Could not write client material sample cache: {}", exception.toString());
            }
        }, writer);
        return pending;
    }

    private void read() {
        if (!Files.isRegularFile(file)) return;
        try {
            if (Files.size(file) > MAX_BYTES) throw new IOException("Sample cache exceeds size limit");
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.get("version").getAsInt() != VERSION) return;
            int total = 0;
            for (var group : root.getAsJsonObject("environments").entrySet()) {
                if (scopes.size() >= MAX_SCOPES) break;
                if (!group.getKey().matches("[0-9a-f]{64}")) continue;
                var entries = new LinkedHashMap<MaterialSampler.Key, Entry>();
                for (JsonElement value : group.getValue().getAsJsonArray()) {
                    if (++total > MAX_ENTRIES * MAX_SCOPES) break;
                    try {
                        Entry entry = decode(value.getAsJsonObject());
                        entries.put(entry.key, entry);
                        if (entries.size() >= MAX_ENTRIES) break;
                    } catch (Exception exception) {
                        Barricade.LOGGER.debug("Ignoring invalid cached material entry: {}", exception.toString());
                    }
                }
                scopes.put(group.getKey(), entries);
            }
        } catch (IOException | RuntimeException exception) {
            scopes.clear();
            Barricade.LOGGER.warn("Ignoring unreadable client material sample cache: {}", exception.toString());
        }
    }

    private void write(Map<String, List<Entry>> snapshot) throws IOException {
        JsonObject root = new JsonObject(), environments = new JsonObject();
        root.addProperty("version", VERSION);
        snapshot.forEach((scope, entries) -> {
            JsonArray values = new JsonArray();
            entries.forEach(entry -> values.add(encode(entry)));
            environments.add(scope, values);
        });
        root.add("environments", environments);
        byte[] bytes = new GsonBuilder().setPrettyPrinting().create().toJson(root).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Sample cache exceeds size limit");
        Path directory = file.getParent(), temporary = file.resolveSibling(file.getFileName() + ".tmp").normalize();
        if (!temporary.getParent().equals(directory)) throw new IOException("Invalid cache temporary path");
        Files.createDirectories(directory);
        Files.write(temporary, bytes);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static JsonObject encode(Entry entry) {
        JsonObject value = new JsonObject();
        value.addProperty("state", entry.key.state());
        value.addProperty("item_nbt", entry.key.itemData().toString());
        value.addProperty("direction", entry.key.direction().getName());
        if (entry.sample == null) value.addProperty("unsupported", entry.failure);
        else {
            JsonArray parts = new JsonArray(), boxes = new JsonArray();
            for (ModelPart part : entry.sample.parts()) {
                JsonObject body = new JsonObject();
                body.addProperty("state", part.blockState());
                body.add("offset", vector(part.x(), part.y(), part.z()));
                if (!part.blockEntityData().isEmpty()) body.addProperty("nbt", part.blockEntityData().toString());
                parts.add(body);
            }
            for (CollisionBox box : entry.sample.boxes()) {
                JsonObject shape = new JsonObject();
                shape.add("from", vector(box.minX(), box.minY(), box.minZ()));
                shape.add("to", vector(box.maxX(), box.maxY(), box.maxZ()));
                boxes.add(shape);
            }
            value.add("parts", parts);
            value.add("collision_boxes", boxes);
        }
        return value;
    }

    private static Entry decode(JsonObject value) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String state = value.get("state").getAsString(), item = value.get("item_nbt").getAsString();
        Direction direction = Direction.byName(value.get("direction").getAsString());
        if (state.isBlank() || state.length() > 4096 || item.length() > 131072 || direction == null)
            throw new IllegalArgumentException("Invalid cached sample key");
        var key = new MaterialSampler.Key(state, TagParser.parseTag(item), direction);
        if (value.has("unsupported")) return new Entry(key, null, value.get("unsupported").getAsString());
        JsonArray bodies = value.getAsJsonArray("parts"), shapes = value.getAsJsonArray("collision_boxes");
        if (bodies.isEmpty() || bodies.size() > 16 || shapes.isEmpty() || shapes.size() > 16)
            throw new IllegalArgumentException("Invalid cached assembly size");
        List<ModelPart> parts = new ArrayList<>();
        List<CollisionBox> boxes = new ArrayList<>();
        for (JsonElement body : bodies) {
            JsonObject part = body.getAsJsonObject();
            JsonArray offset = part.getAsJsonArray("offset");
            if (offset.size() != 3) throw new IllegalArgumentException("Invalid cached part offset");
            String data = part.has("nbt") ? part.get("nbt").getAsString() : "{}";
            if (data.length() > 131072) throw new IllegalArgumentException("Cached block entity is too large");
            var nbt = TagParser.parseTag(data);
            if (nbt.sizeInBytes() > 65536) throw new IllegalArgumentException("Cached block entity is too large");
            parts.add(new ModelPart(part.get("state").getAsString(), offset.get(0).getAsFloat(),
                    offset.get(1).getAsFloat(), offset.get(2).getAsFloat(), nbt));
        }
        shapes.forEach(shape -> boxes.add(CollisionBox.fromJson(shape.getAsJsonObject())));
        return new Entry(key, new MaterialSampler.Sample(parts, boxes), null);
    }

    private static JsonArray vector(float x, float y, float z) {
        JsonArray result = new JsonArray();
        result.add(x); result.add(y); result.add(z);
        return result;
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        try { flush().get(5, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        catch (ExecutionException | TimeoutException exception) {
            Barricade.LOGGER.warn("Client sample cache did not finish saving: {}", exception.toString());
        } finally { writer.shutdown(); }
    }
}
