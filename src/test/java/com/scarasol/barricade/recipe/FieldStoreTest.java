package com.scarasol.barricade.recipe;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.pile.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class FieldStoreTest {
    private static final UUID ID = new UUID(1, 2);
    private final PaletteSpec palette = PaletteSpec.create(List.of(
            new PileModels.Candidate("a", "minecraft:oak_planks", 1)), "test");
    private CompoundTag payload(long base, long revision, FieldRegion region) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Format", 2); tag.putLong("Base", base); tag.putLong("Revision", revision);
        ListTag groups = new ListTag(), rules = new ListTag();
        if (region != null) groups.add(region.save());
        rules.add(palette.save()); tag.put("Regions", groups); tag.put("Palettes", rules);
        return tag;
    }
    private FieldRegion region(long revision, Set<BlockPos> domain) {
        return new FieldRegion(ID, revision, FieldDefinition.create(42, palette), domain, new SpatialMask(), Map.of());
    }
    private FieldRegion embeddedRegion(long revision, BlockPos pos) {
        return new FieldRegion(ID, revision, FieldDefinition.create(42, palette), Set.of(pos),
                new SpatialMask(), Map.of(pos, new EmbeddedBlock(pos, 1, revision)));
    }
    @Test void fullBaselinesAndCurrentDeltasAreAtomicAndStaleRevisionsCannotUndoThePresent() {
        FieldStore store = new FieldStore();
        var one = region(1, Set.of(BlockPos.ZERO));
        store.accept(payload(-1, 10, one));
        assertEquals(one, store.regions().get(ID));
        var two = region(2, Set.of(BlockPos.ZERO, BlockPos.ZERO.east()));
        assertThrows(IllegalArgumentException.class, () -> store.accept(payload(9, 12, two)));
        assertEquals(one, store.regions().get(ID));
        store.accept(payload(10, 12, two));
        store.accept(payload(-1, 10, one));
        assertEquals(two, store.regions().get(ID));
        CompoundTag removal = payload(12, 13, null), id = new CompoundTag();
        id.putUUID("Id", ID); ListTag removed = new ListTag(); removed.add(id); removal.put("Removed", removed);
        store.accept(removal); assertTrue(store.regions().isEmpty());
        store.accept(payload(-1, 20, one)); assertEquals(one, store.regions().get(ID));
    }
    @Test void embeddedPositionIndexTracksAtomicBaselinesAndRemovals() {
        FieldStore store = new FieldStore();
        BlockPos pos = BlockPos.ZERO;
        var embedded = embeddedRegion(1, pos);
        store.accept(payload(-1, 10, embedded));
        assertEquals(embedded.embedded().get(pos), store.embeddedAt(pos));

        store.accept(payload(10, 11, region(2, Set.of(pos))));
        assertNull(store.embeddedAt(pos));

        CompoundTag removal = payload(11, 12, null), id = new CompoundTag();
        id.putUUID("Id", ID); ListTag removed = new ListTag(); removed.add(id); removal.put("Removed", removed);
        store.accept(removal);
        assertNull(store.embeddedAt(pos));
    }
    @Test void paletteContentIsCanonicalAndFrozenAcrossTagChanges() {
        var a = new PileModels.Candidate("a", "minecraft:stone", 1);
        var b = new PileModels.Candidate("b", "minecraft:oak_planks", 2);
        assertEquals(PaletteSpec.create(List.of(a, b), "same"), PaletteSpec.create(List.of(b, a), "same"));
        assertNotEquals(PaletteSpec.create(List.of(a, b), "same").id(),
                PaletteSpec.create(List.of(a), "same").id());
    }
}
