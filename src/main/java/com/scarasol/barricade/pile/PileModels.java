package com.scarasol.barricade.pile;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.scarasol.barricade.Barricade;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;

public final class PileModels extends SimpleJsonResourceReloadListener {
    public static final String DEFAULT_TAG = "barricade:materials";
    private static volatile List<PileModel> palette = bundled();
    private static volatile long revision;
    private static long candidateRevision = -1;
    private static final Map<ResourceLocation, List<Candidate>> cachedCandidates = new LinkedHashMap<>();
    private static Map<String, PileModel> explicitModels = Map.of();

    public static TagKey<Block> materialsTag() { return MaterialTag.VALUE; }

    private static final class MaterialTag {
        private static final TagKey<Block> VALUE = TagKey.create(Registries.BLOCK,
                new ResourceLocation(Barricade.MOD_ID, "materials"));
    }

    public record Candidate(String id, String state, float weight, PileModel explicit) {
        public Candidate(String id, String state, float weight) { this(id, state, weight, null); }
    }

    public static List<Candidate> candidates() {
        return candidates(materialsTag().location());
    }

    public static List<Candidate> candidates(ResourceLocation source) {
        if (candidateRevision != revision) {
            cachedCandidates.clear();
            candidateRevision = revision;
        }
        List<Candidate> cached = cachedCandidates.get(source);
        if (cached != null) return cached;
        Map<Block, List<PileModel>> settings = new LinkedHashMap<>();
        for (PileModel model : palette) {
            try {
                Block block = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(),
                        model.blockState(), false).blockState().getBlock();
                settings.computeIfAbsent(block, ignored -> new ArrayList<>()).add(model);
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ignored) {
                // Invalid optional overrides must not hide valid tag entries.
            }
        }
        List<Candidate> result = new ArrayList<>();
        BuiltInRegistries.BLOCK.getTag(TagKey.create(Registries.BLOCK, source)).ifPresent(tag -> tag.stream()
                .map(net.minecraft.core.Holder::value)
                .filter(block -> !(block instanceof com.scarasol.barricade.block.BarricadeBlock))
                .sorted(java.util.Comparator.comparing(block -> BuiltInRegistries.BLOCK.getKey(block).toString()))
                .forEach(block -> {
                    List<PileModel> variants = settings.getOrDefault(block, List.of());
                    String id = BuiltInRegistries.BLOCK.getKey(block).toString();
                    if (variants.isEmpty()) result.add(new Candidate(id, id, 1));
                    for (PileModel setting : variants) {
                        result.add(new Candidate(setting.id(), setting.blockState(), setting.weight(),
                                explicitModels.get(setting.id())));
                    }
                }));
        List<Candidate> selected = List.copyOf(result);
        if (cachedCandidates.size() >= 256) cachedCandidates.clear();
        cachedCandidates.put(source, selected);
        return selected;
    }

    public static long revision() { return revision; }
    public static void tagsChanged() { revision++; }

    public PileModels() {
        super(new Gson(), "barricade_models");
    }

    public static List<PileModel> palette() {
        return palette;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Map<String, PileModel> models = new LinkedHashMap<>();
        Map<String, PileModel> explicit = new LinkedHashMap<>();
        // Sorted resources make palette selection reproducible across server restarts.
        resources.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            JsonObject root = entry.getValue().getAsJsonObject();
            List<PileModel> parsed = parse(root);
            for (int i = 0; i < parsed.size(); i++) {
                PileModel model = parsed.get(i);
                try {
                    for (ModelPart part : model.parts()) {
                        var state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(),
                                part.blockState(), false).blockState();
                    }
                } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
                    throw new IllegalArgumentException("Invalid block state in " + entry.getKey(), exception);
                }
                models.put(model.id(), model);
                JsonObject definition = root.getAsJsonArray("models").get(i).getAsJsonObject();
                if (definition.has("parts") || definition.has("collision_boxes")) {
                    explicit.put(model.id(), model);
                } else {
                    explicit.remove(model.id());
                }
            }
        });
        palette = models.isEmpty() ? bundled() : List.copyOf(models.values());
        explicitModels = Map.copyOf(explicit);
        revision++;
        Barricade.LOGGER.info("Loaded {} barricade pile models", palette.size());
    }

    public static List<PileModel> parse(JsonObject root) {
        List<PileModel> models = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("models")) {
            JsonObject json = element.getAsJsonObject();
            JsonArray size = json.getAsJsonArray("size");
            if (size.size() != 3) {
                throw new IllegalArgumentException("Model size must have three coordinates");
            }
            List<CollisionBox> boxes = new ArrayList<>();
            if (json.has("collision_boxes")) {
                for (JsonElement box : json.getAsJsonArray("collision_boxes")) {
                    boxes.add(CollisionBox.fromJson(box.getAsJsonObject()));
                }
            } else {
                boxes.add(CollisionBox.CUBE);
            }
            List<ModelPart> parts = new ArrayList<>();
            if (json.has("parts")) {
                for (JsonElement partElement : json.getAsJsonArray("parts")) {
                    JsonObject part = partElement.getAsJsonObject();
                    float[] offset = vector(part, "offset", 0, 0, 0);
                    parts.add(new ModelPart(part.get("block").getAsString(), offset[0], offset[1], offset[2]));
                }
                if (!json.has("collision_boxes")) {
                    throw new IllegalArgumentException("Composite models need explicit collision_boxes");
                }
            } else {
                parts.add(new ModelPart(json.get("block").getAsString(), 0, 0, 0));
            }
            float[] pivot = vector(json, "pivot", 0.5f, 0.5f, 0.5f);
            models.add(new PileModel(json.get("id").getAsString(), parts,
                    size.get(0).getAsFloat(), size.get(1).getAsFloat(), size.get(2).getAsFloat(),
                    json.has("weight") ? json.get("weight").getAsFloat() : 1, boxes,
                    pivot[0], pivot[1], pivot[2]));
        }
        if (models.isEmpty()) {
            throw new IllegalArgumentException("The model palette must not be empty");
        }
        return List.copyOf(models);
    }

    private static float[] vector(JsonObject json, String key, float x, float y, float z) {
        if (!json.has(key)) {
            return new float[]{x, y, z};
        }
        JsonArray values = json.getAsJsonArray(key);
        if (values.size() != 3) {
            throw new IllegalArgumentException(key + " must contain three coordinates");
        }
        return new float[]{values.get(0).getAsFloat(), values.get(1).getAsFloat(), values.get(2).getAsFloat()};
    }

    private static List<PileModel> bundled() {
        var stream = PileModels.class.getResourceAsStream("/data/barricade/barricade_models/default.json");
        if (stream == null) {
            throw new IllegalStateException("Missing bundled barricade palette");
        }
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return parse(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not read barricade palette", exception);
        }
    }
}
