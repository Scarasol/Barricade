package com.scarasol.barricade.recipe;

import com.scarasol.barricade.pile.SpatialMask;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

public record FieldRegion(UUID id, long revision, FieldDefinition definition, Set<BlockPos> domain,
                          SpatialMask mask, Map<BlockPos, EmbeddedBlock> embedded, Set<BlockPos> baseline) {
    public FieldRegion(UUID id, long revision, FieldDefinition definition, Set<BlockPos> domain,
                       SpatialMask mask, Map<BlockPos, EmbeddedBlock> embedded) {
        this(id, revision, definition, domain, mask, embedded, baseline(domain, mask));
    }
    private static Set<BlockPos> baseline(Set<BlockPos> domain, SpatialMask mask) {
        Set<BlockPos> result = new HashSet<>(domain); result.addAll(mask.cells()); return Set.copyOf(result);
    }
    public FieldRegion {
        domain = Set.copyOf(domain); mask = new SpatialMask(mask); embedded = Map.copyOf(embedded);
        baseline = Set.copyOf(baseline);
        if (revision <= 0 || domain.isEmpty() || !domain.containsAll(embedded.keySet())
                || !baseline.containsAll(domain) || !baseline.containsAll(mask.cells()))
            throw new IllegalArgumentException("Invalid current barricade region");
    }
    @Override public SpatialMask mask() { return new SpatialMask(mask); }
    public CompoundTag save() {
        CompoundTag tag = definition.save();
        tag.putUUID("Id", id); tag.putLong("Revision", revision);
        RecipeCodec.positions(tag, "Domain", domain); tag.put("Mask", mask.save());
        RecipeCodec.positions(tag, "Baseline", baseline);
        ListTag blocks = new ListTag();
        embedded.values().stream().sorted(Comparator.comparing(EmbeddedBlock::pos)).forEach(block -> blocks.add(block.save()));
        tag.put("Embedded", blocks); return tag;
    }
    public static FieldRegion load(CompoundTag tag) {
        if (!tag.contains("Baseline", Tag.TAG_LONG_ARRAY)) throw new IllegalArgumentException("Missing finite-region baseline");
        Map<BlockPos, EmbeddedBlock> embedded = new HashMap<>();
        for (Tag value : tag.getList("Embedded", Tag.TAG_COMPOUND)) {
            EmbeddedBlock block = EmbeddedBlock.load((CompoundTag) value);
            if (embedded.put(block.pos(), block) != null) throw new IllegalArgumentException("Duplicate embedded member");
        }
        return new FieldRegion(tag.getUUID("Id"), tag.getLong("Revision"), FieldDefinition.load(tag),
                RecipeCodec.positions(tag, "Domain"), SpatialMask.load(tag.getList("Mask", Tag.TAG_COMPOUND)), embedded,
                RecipeCodec.positions(tag, "Baseline"));
    }
}
