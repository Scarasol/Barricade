package com.scarasol.barricade.recipe;

import com.scarasol.barricade.pile.PileModel;
import com.scarasol.barricade.pile.PileModels;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

public record PaletteSpec(String id, String resources, List<PileModels.Candidate> candidates,
                          String source, PaletteSpec fallback) {
    public PaletteSpec(String id, String resources, List<PileModels.Candidate> candidates) {
        this(id, resources, candidates, "", null);
    }
    public PaletteSpec {
        candidates = List.copyOf(candidates);
        if (fallback != null && (fallback.fallback() != null || !fallback.source().isEmpty()))
            throw new IllegalArgumentException("Material fallback must be the default snapshot");
    }

    public String tag() { return source.isEmpty() ? PileModels.DEFAULT_TAG : source; }

    public static PaletteSpec create(List<PileModels.Candidate> candidates, String resources) {
        return create(candidates, resources, "", null);
    }

    public static PaletteSpec create(List<PileModels.Candidate> candidates, String resources,
                                     String source, PaletteSpec fallback) {
        candidates = candidates.stream().sorted(java.util.Comparator.comparing(PileModels.Candidate::id)
                .thenComparing(PileModels.Candidate::state).thenComparingDouble(PileModels.Candidate::weight)).toList();
        PaletteSpec provisional = new PaletteSpec("", resources, candidates, source, fallback);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.write(provisional.save(), new DataOutputStream(bytes));
            String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
            return new PaletteSpec(id, resources, candidates, source, fallback);
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not fingerprint material rules", exception);
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", id);
        tag.putString("Resources", resources);
        if (!source.isEmpty()) tag.putString("SourceTag", source);
        if (fallback != null) tag.put("Fallback", fallback.save());
        ListTag values = new ListTag();
        for (var candidate : candidates) {
            CompoundTag value = new CompoundTag();
            value.putString("Id", candidate.id());
            value.putString("State", candidate.state());
            value.putFloat("Weight", candidate.weight());
            if (candidate.explicit() != null) value.put("Explicit", candidate.explicit().save());
            values.add(value);
        }
        tag.put("Candidates", values);
        return tag;
    }

    public static PaletteSpec load(CompoundTag tag) {
        CompoundTag fallback = tag.getCompound("Fallback");
        if (fallback.contains("Fallback") || fallback.contains("SourceTag"))
            throw new IllegalArgumentException("Invalid nested material fallback");
        List<PileModels.Candidate> candidates = new ArrayList<>();
        for (Tag entry : tag.getList("Candidates", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) entry;
            PileModel explicit = value.contains("Explicit") ? PileModel.load(value.getCompound("Explicit")) : null;
            candidates.add(new PileModels.Candidate(value.getString("Id"), value.getString("State"),
                    value.getFloat("Weight"), explicit));
        }
        return new PaletteSpec(tag.getString("Id"), tag.getString("Resources"), candidates,
                tag.getString("SourceTag"), tag.contains("Fallback", Tag.TAG_COMPOUND) ? load(fallback) : null);
    }
}
