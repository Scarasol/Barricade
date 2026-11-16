package com.scarasol.barricade.pile;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.recipe.*;
import com.scarasol.barricade.sampling.MemoryFixture;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

class TaggedPaletteTest {
    private static final UUID A = new UUID(1, 2), B = new UUID(3, 4), C = new UUID(5, 6);
    private static final BlockPos P = new BlockPos(-17, 8, 31);
    private static final List<PileModels.Candidate> MATERIALS = List.of(
            new PileModels.Candidate("oak", "minecraft:oak_planks", 1));
    private static final PaletteSpec DEFAULT = PaletteSpec.create(MATERIALS, "test");
    private static PaletteSpec tagged(String tag) {
        return PaletteSpec.create(MATERIALS, "test", tag, DEFAULT);
    }

    @Test void plainNamesRemainDefaultAndExplicitTagNamesSupportNamespacesPathsAndOptionalHash() {
        MemoryFixture.create(false);
        ItemStack stack = new ItemStack(Blocks.STONE);
        assertEquals(PileModels.DEFAULT_TAG, PileService.source(stack));
        for (String name : List.of("Roadblock", "logs", "NOT:A:TAG", "minecraft:oak planks", ":logs"))
            assertEquals(PileModels.DEFAULT_TAG, PileService.source(stack.setHoverName(Component.literal(name))));
        assertEquals("example:wood/logs", PileService.source(stack.setHoverName(Component.literal("example:wood/logs"))));
        assertEquals("minecraft:logs", PileService.source(stack.setHoverName(Component.literal(" #minecraft:logs "))));
    }

    @Test void differentTagIdentitiesDoNotCollapseEvenWhenTheirMaterialsMatch() {
        PaletteSpec first = tagged("example:a"), second = tagged("example:b");
        assertNotEquals(first.id(), second.id());
        assertEquals(first, PaletteSpec.load(first.save()));
        assertEquals(DEFAULT, first.fallback());
        assertEquals("example:a", first.tag());
        PileData data = new PileData();
        data.add(P, A, 42, first);
        data.add(P.east(), B, 9, second);
        data.add(P.west(), C, 13, first);
        assertEquals(2, data.regionCount());
        assertEquals(A, data.regionAt(P.west()));
        assertEquals(B, data.regionAt(P.east()));
        assertEquals(Set.of(P, P.west()), data.connectedComponent(P));
        assertEquals(data.save(new CompoundTag()), PileData.load(data.save(new CompoundTag())).save(new CompoundTag()));
    }

    @Test void growthAcrossReloadKeepsOldDefinitionsAndOnlyMergesCompatibleSnapshots() {
        PaletteSpec first = tagged("example:a");
        PaletteSpec changed = PaletteSpec.create(List.of(
                new PileModels.Candidate("stone", "minecraft:stone", 2)), "test", "example:a", DEFAULT);
        PileData data = new PileData();
        data.add(P, A, 42, first);
        FieldDefinition before = data.snapshot(A).definition();
        data.add(P.east(), B, 9, changed);
        assertEquals(2, data.regionCount());
        assertEquals(before, data.snapshot(A).definition());
        assertEquals(changed.id(), data.snapshot(B).definition().palette());
    }

    @Test void resamplingAndRegenerationResolveEachOriginalTagAndKeepEmbeddedState() {
        PaletteSpec left = tagged("example:a"), right = tagged("example:b");
        PileData data = new PileData();
        data.add(P, A, 42, left);
        data.add(P.east(), B, 9, right);
        EmbeddedBlock embedded = data.addEmbedded(P);
        Map<String, PaletteSpec> revised = new HashMap<>();
        for (String tag : List.of(left.tag(), right.tag()))
            revised.put(tag, PaletteSpec.create(MATERIALS, "new-resources", tag, DEFAULT));
        data.resample(old -> revised.get(old.tag()));
        assertEquals(1, data.sampleEpoch());
        assertEquals(revised.get(left.tag()).id(), data.snapshot(A).definition().palette());
        assertEquals(revised.get(right.tag()).id(), data.snapshot(B).definition().palette());
        assertEquals(embedded, data.embeddedAt(P));
        assertEquals(1, data.regenerate(P, revised.get(left.tag()), 91, C));
        assertEquals(B, data.regionAt(P.east()));
        assertEquals(embedded, data.embeddedAt(P));
        assertEquals("example:a", data.palette(data.snapshot(C).definition().palette()).tag());
    }

    @Test void currentDefaultPaletteRoundTripsAndNestedFallbacksAreRejected() {
        PaletteSpec loaded = PaletteSpec.load(DEFAULT.save());
        assertEquals(DEFAULT, loaded);
        assertEquals(DEFAULT.id(), PaletteSpec.create(loaded.candidates(), loaded.resources()).id());
        assertEquals(PileModels.DEFAULT_TAG, loaded.tag());
        PileData data = new PileData();
        data.add(P, A, 42, loaded);
        CompoundTag save = data.save(new CompoundTag());
        assertEquals(3, save.getInt("Format"));
        assertEquals(save, PileData.load(save).save(new CompoundTag()));
        CompoundTag nested = tagged("example:a").save();
        nested.getCompound("Fallback").put("Fallback", DEFAULT.save());
        assertThrows(IllegalArgumentException.class, () -> PaletteSpec.load(nested));
    }

    @Test void transientPlacementSelectorSurvivesCaptureButIsRemovedOnFailureRollbackAndConsumption() {
        var level = MemoryFixture.create(false);
        ItemStack stack = new ItemStack(Blocks.STONE).setHoverName(Component.literal("example:a"));
        var context = new DirectionalPlaceContext(level, P, Direction.NORTH, stack, Direction.UP);
        level.captureBlockSnapshots = true;
        try {
            try (var placement = PileService.placing(context)) { placement.successful(); }
            assertEquals("example:a", PileService.placementSource(level, P));
            assertEquals(PileModels.DEFAULT_TAG, PileService.placementSource(level, P));
            try (var ignored = PileService.placing(context)) { }
            assertEquals(PileModels.DEFAULT_TAG, PileService.placementSource(level, P));
            try (var placement = PileService.placing(context)) { placement.successful(); }
            PileService.clearPlacement(level, P);
            assertEquals(PileModels.DEFAULT_TAG, PileService.placementSource(level, P));
        } finally { level.captureBlockSnapshots = false; PileService.clearPlacement(level, P); }
        try (var placement = PileService.placing(context)) {
            assertEquals("example:a", PileService.placementSource(level, P));
            placement.successful();
        }
        assertEquals(PileModels.DEFAULT_TAG, PileService.placementSource(level, P));
    }

    @Test void predictedClientAndMemoryPlacementsCannotEnterTheServerSelectorScope() throws Exception {
        var node = new org.objectweb.asm.tree.ClassNode();
        try (var input = getClass().getResourceAsStream("/com/scarasol/barricade/block/BarricadeItem.class")) {
            new org.objectweb.asm.ClassReader(Objects.requireNonNull(input)).accept(node, 0);
        }
        var place = node.methods.stream().filter(method -> method.name.equals("place")).findFirst().orElseThrow();
        int guard = -1, scope = -1, index = 0;
        for (var instruction : place.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.TypeInsnNode type
                    && type.getOpcode() == org.objectweb.asm.Opcodes.INSTANCEOF
                    && type.desc.equals("net/minecraft/server/level/ServerLevel")) guard = index;
            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                    && call.owner.equals("com/scarasol/barricade/pile/PileService") && call.name.equals("placing")) scope = index;
            index++;
        }
        assertTrue(guard >= 0 && scope > guard, "Only server placements may access shared placement selectors");
    }

    @Test void arbitraryTagLookupIsIsolatedCachedAndInvalidatedWithoutChangingDefaultSelection() {
        MemoryFixture.create(false);
        var registry = BuiltInRegistries.BLOCK;
        var a = TagKey.create(Registries.BLOCK, new ResourceLocation("example", "a"));
        var b = TagKey.create(Registries.BLOCK, new ResourceLocation("example", "b"));
        registry.getOrCreateTag(a); registry.getOrCreateTag(b);
        var original = registry.getTags().collect(java.util.stream.Collectors.toMap(
                com.mojang.datafixers.util.Pair::getFirst, pair -> pair.getSecond().stream().toList()));
        try {
            var selected = new HashMap<>(original);
            selected.put(a, List.of(registry.wrapAsHolder(Blocks.OAK_PLANKS)));
            selected.put(b, List.of(registry.wrapAsHolder(Blocks.STONE)));
            registry.bindTags(selected); PileModels.tagsChanged();
            var defaults = PileModels.candidates();
            assertEquals(List.of("minecraft:oak_planks"), PileModels.candidates(a.location()).stream()
                    .map(PileModels.Candidate::state).toList());
            assertEquals(List.of("minecraft:stone"), PileModels.candidates(b.location()).stream()
                    .map(PileModels.Candidate::state).toList());
            assertSame(defaults, PileModels.candidates());
            assertEquals("example:missing", PileService.palette("example:missing").tag());
            assertTrue(PileService.palette("example:missing").candidates().isEmpty());
            assertNotNull(PileService.palette("example:missing").fallback());
            selected.put(a, selected.get(b));
            registry.bindTags(selected); PileModels.tagsChanged();
            assertEquals(List.of("minecraft:stone"), PileModels.candidates(a.location()).stream()
                    .map(PileModels.Candidate::state).toList());
        } finally { registry.bindTags(original); PileModels.tagsChanged(); }
    }
}
