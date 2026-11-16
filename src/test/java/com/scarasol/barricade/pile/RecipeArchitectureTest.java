package com.scarasol.barricade.pile;

import static org.junit.jupiter.api.Assertions.*;

import com.scarasol.barricade.block.BarricadeBlock;
import com.scarasol.barricade.network.PileNetwork;
import com.scarasol.barricade.recipe.*;
import com.scarasol.barricade.sampling.MemoryFixture;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

class RecipeArchitectureTest {
    private static final BlockPos ZERO = new BlockPos(100_000, 80, -250_000);
    private static final UUID ID = new UUID(1, 2), OTHER = new UUID(3, 4);
    private final PaletteSpec palette = PaletteSpec.create(List.of(
            new PileModels.Candidate("oak", "minecraft:oak_planks", 1)), "resources-a");

    @Test void serverSavesOnlyInputsAndPendingEditsRoundTrip() {
        ArchivedPileData data = new ArchivedPileData();
        data.add(ZERO, ID, 42);
        data.add(ZERO.east(), OTHER, 9);
        assertEquals(ID, data.regionAt(ZERO.east()));
        assertEquals(1, data.queuedCount());
        CompoundTag saved = data.save(new CompoundTag());
        assertFalse(saved.toString().contains("Pieces"));
        assertFalse(saved.toString().contains("Collision"));
        assertFalse(saved.contains("Version"));
        assertEquals(saved, ArchivedPileData.load(saved).save(new CompoundTag()));
        ArchivedPileData restored = ArchivedPileData.load(saved);
        long head = restored.commit(ID, palette, ArchivedTerrainSnapshot.EMPTY);
        assertEquals(Set.of(ZERO, ZERO.east()), restored.recipe(head).active());
        assertEquals(restored.save(new CompoundTag()), ArchivedPileData.load(restored.save(new CompoundTag())).save(new CompoundTag()));
        assertThrows(IllegalArgumentException.class, () -> ArchivedPileData.load(new CompoundTag()));
    }

    @Test void mergeRetainsBothLineagesAndDamageDoesNotScheduleFilling() {
        ArchivedPileData data = new ArchivedPileData();
        data.add(ZERO, ID, 42);
        long left = data.commit(ID, palette, ArchivedTerrainSnapshot.EMPTY);
        data.add(ZERO.east(2), OTHER, 7);
        long right = data.commit(OTHER, palette, ArchivedTerrainSnapshot.EMPTY);
        data.add(ZERO.east(), UUID.randomUUID(), 8);
        long joined = data.commit(ID, palette, ArchivedTerrainSnapshot.EMPTY);
        assertEquals(List.of(left, right), data.recipe(joined).parents());
        assertEquals(1, data.regionCount());
        data.remove(ZERO.east());
        long damaged = data.commit(ID, palette, ArchivedTerrainSnapshot.EMPTY);
        ArchivedPileRecipe edit = data.recipe(damaged);
        assertTrue(edit.active().isEmpty());
        assertTrue(edit.repack().isEmpty());
        assertEquals(List.of(joined), edit.parents());
        assertEquals(1, edit.damage().size());
        assertEquals(Set.of(ZERO), data.connectedComponent(ZERO));
        assertEquals(Set.of(ZERO.east(2)), data.connectedComponent(ZERO.east(2)));
        assertEquals(ID, data.regionAt(ZERO.east(2)), "Logical identity survives a physical split");
    }

    @Test void fullRegenerationReleasesUnreachableHistoryButPartialRegenerationRetainsIt() {
        ArchivedPileData data = new ArchivedPileData();
        data.add(ZERO, ID, 42);
        data.add(ZERO.east(), OTHER, 1);
        data.add(ZERO.east(2), OTHER, 1);
        data.commit(ID, palette, ArchivedTerrainSnapshot.EMPTY);
        data.remove(ZERO.east());
        data.commit(ID, palette, ArchivedTerrainSnapshot.EMPTY);
        assertEquals(1, data.regenerate(ZERO));
        long partial = data.commit(ID, palette, ArchivedTerrainSnapshot.EMPTY);
        assertFalse(data.recipe(partial).parents().isEmpty());
        data.add(ZERO.east(), OTHER, 1);
        data.commit(ID, palette, ArchivedTerrainSnapshot.EMPTY);
        assertEquals(3, data.regenerate(ZERO));
        long reset = data.commit(ID, palette, ArchivedTerrainSnapshot.EMPTY);
        assertTrue(data.recipe(reset).parents().isEmpty());
        assertEquals(1, data.recipeCount());
        data.remove(ZERO);
        data.remove(ZERO.east());
        data.remove(ZERO.east(2));
        assertEquals(0, data.recipeCount());
    }
    @Test void diagonalRegionsReferenceHistoricalNeighborsWithoutMergingTheirObjects() {
        ArchivedPileData data = new ArchivedPileData();
        data.add(ZERO, ID, 42);
        long first = data.commit(ID, palette, ArchivedTerrainSnapshot.EMPTY);
        BlockPos diagonal = ZERO.east().south();
        data.add(diagonal, OTHER, 10);
        long second = data.commit(OTHER, palette, ArchivedTerrainSnapshot.EMPTY);
        assertTrue(data.recipe(second).parents().isEmpty());
        assertEquals(List.of(first), data.recipe(second).neighbors());
        assertEquals(Set.of(first, second), data.dependencies(List.of(second)));
        data.regenerate(ZERO);
        long third = data.commit(ID, palette, ArchivedTerrainSnapshot.EMPTY);
        assertEquals(List.of(second), data.recipe(third).neighbors());
        assertEquals(Set.of(first, second, third), data.dependencies(List.of(third)));
        assertEquals(data.save(new CompoundTag()), ArchivedPileData.load(data.save(new CompoundTag())).save(new CompoundTag()));
    }
    @Test void neighborObjectsContributeCollisionButNeverBecomePartOfTheGeneratedRegion() {
        var neighbor = new ArchivedPileReplay.ObjectPiece(new ArchivedPileReplay.Key(99, 0), ZERO.east(),
                PilePiece.create(TestPalette.models().get(0), 0.3f, 0.5f, 0.5f, 1, new Quaternionf()));
        var prepared = new ArchivedPileReplay.Prepared(node(1, Set.of(ZERO), Set.of(ZERO)),
                TestPalette.models(), List.of(), List.of(), List.of());
        var output = TestPileReplay.replay(prepared, List.of(), List.of(List.of(neighbor)));
        assertFalse(output.isEmpty());
        assertTrue(output.stream().allMatch(object -> object.owner().equals(ZERO)));
        assertFalse(output.stream().anyMatch(object -> object.key().equals(neighbor.key())));
        var obstacle = neighbor.piece().colliders(1, 0, 0);
        assertTrue(output.stream().flatMap(object -> object.piece().colliders(0, 0, 0).stream())
                .noneMatch(box -> obstacle.stream().anyMatch(other -> box.intersects(other, 0.009f))));
    }

    @Test void watchingPaddedChunksFindsFullRegionWithoutReadingWorldChunks() {
        ArchivedPileData data = new ArchivedPileData();
        for (int x = 0; x < 70; x++) data.add(ZERO.east(x), UUID.randomUUID(), x);
        UUID id = data.regionAt(ZERO);
        data.commit(id, palette, ArchivedTerrainSnapshot.EMPTY);
        long chunk = ChunkPos.asLong(ZERO);
        assertEquals(Set.of(id), data.visible(Set.of(chunk)));
        assertEquals(70, data.recipe(data.head(id)).domain().size());
        ArchivedPileData restored = ArchivedPileData.load(data.save(new CompoundTag()));
        assertEquals(Set.of(id), restored.visible(Set.of(chunk)));
        assertEquals(data.recipe(data.head(id)), restored.recipe(restored.head(id)));
        assertEquals(Set.of(), data.visible(Set.of(ChunkPos.asLong(1000, 1000))));
    }

    private ArchivedPileRecipe node(long id, Set<BlockPos> domain, Set<BlockPos> active) {
        return new ArchivedPileRecipe(id, 42, id == 1 ? List.of() : List.of(id - 1),
                domain, active, Set.of(), palette.id(), ArchivedTerrainSnapshot.EMPTY, List.of());
    }
    private ArchivedPileReplay.ObjectPiece piece(float x, int index) {
        return new ArchivedPileReplay.ObjectPiece(new ArchivedPileReplay.Key(1, index), ZERO,
                PilePiece.create(TestPalette.models().get(0), x, 0.5f, 0.5f, 1, new Quaternionf()));
    }
    @Test void damageUsesCenterThenInsetBodyAndNeverFillsTheHole() {
        var edge = piece(1.46f, 0);
        var body = piece(1.3f, 1);
        var centered = piece(0.9f, 2);
        var damage = new ArchivedPileReplay.DamageShape(ZERO, List.of(OrientedBox.axisAligned(0, 0, 0, 1, 1, 1)));
        var prepared = new ArchivedPileReplay.Prepared(node(2, Set.of(ZERO), Set.of()), List.of(), List.of(),
                List.of(), List.of(damage));
        var output = TestPileReplay.replay(prepared, List.of(List.of(edge, body, centered)));
        assertEquals(List.of(edge), output);
        assertEquals(output, TestPileReplay.replay(prepared, List.of(List.of(edge, body, centered))));
    }
    @Test void deletingOwnerTransfersOnlyStorageWithoutChangingWorldTransform() {
        var edge = piece(1.46f, 0);
        var prepared = new ArchivedPileReplay.Prepared(node(2, Set.of(ZERO.east()), Set.of()),
                List.of(), List.of(), List.of(), List.of(new ArchivedPileReplay.DamageShape(ZERO,
                        List.of(OrientedBox.axisAligned(0, 0, 0, 1, 1, 1)))));
        var output = TestPileReplay.replay(prepared, List.of(List.of(edge)));
        assertEquals(1, output.size());
        assertEquals(ZERO.east(), output.get(0).owner());
        assertEquals(edge.key(), output.get(0).key());
        assertEquals(edge.piece().translated(-1, 0, 0), output.get(0).piece());
        assertEquals(edge.piece().colliders(0, 0, 0).get(0).minX(),
                output.get(0).piece().colliders(1, 0, 0).get(0).minX(), 0.00001);
    }
    @Test void replayDeduplicatesSharedAncestorsAndIsIndependentOfParentCompletionOrder() {
        var a = piece(0.5f, 0);
        var b = piece(1.5f, 1);
        var prepared = new ArchivedPileReplay.Prepared(node(3, Set.of(ZERO, ZERO.east()), Set.of()),
                List.of(), List.of(), List.of(), List.of());
        assertEquals(List.of(a, b), TestPileReplay.replay(prepared, List.of(List.of(a), List.of(a, b))));
        assertEquals(List.of(a, b), TestPileReplay.replay(prepared, List.of(List.of(a, b), List.of(a))));
    }
    @Test void laterGrowthDoesNotEraseDistantHistoricalOverhangLeftByDamage() {
        var overhang = piece(1.46f, 0);
        BlockPos added = ZERO.east(5);
        var prepared = new ArchivedPileReplay.Prepared(node(2, Set.of(ZERO, added), Set.of(added)),
                TestPalette.models(), List.of(), List.of(), List.of());
        var output = TestPileReplay.replay(prepared, List.of(List.of(overhang)));
        assertTrue(output.contains(overhang));
        assertTrue(output.stream().anyMatch(object -> object.owner().equals(added)));
    }
    @Test void reconnectReplaysExactGenerationAndDamageRegardlessOfCurrentClientWorld() {
        Set<BlockPos> domain = Set.of(ZERO, ZERO.east());
        ArchivedPileRecipe first = node(1, domain, domain);
        var prepared = new ArchivedPileReplay.Prepared(first, TestPalette.models(), List.of(), List.of(), List.of());
        var generated = TestPileReplay.replay(prepared, List.of());
        assertFalse(generated.isEmpty());
        var damage = new ArchivedPileReplay.DamageShape(ZERO, List.of(OrientedBox.axisAligned(0, 0, 0, 1, 1, 1)));
        var second = new ArchivedPileReplay.Prepared(node(2, domain, Set.of()), List.of(), List.of(), List.of(), List.of(damage));
        var live = TestPileReplay.replay(second, List.of(generated));
        var reconnect = TestPileReplay.replay(second, List.of(TestPileReplay.replay(prepared, List.of())));
        assertEquals(live, reconnect);
        assertTrue(live.size() < generated.size());
        assertTrue(generated.containsAll(live));
    }

    private CompoundTag manifest(List<ArchivedPileRecipe> nodes, Map<UUID, Long> heads) {
        CompoundTag payload = new CompoundTag();
        ListTag palettes = new ListTag(), regions = new ListTag();
        ArchivedRecipeArchive.write(payload, nodes);
        palettes.add(palette.save());
        heads.forEach((id, head) -> {
            CompoundTag group = new CompoundTag();
            group.putUUID("Id", id);
            group.putLong("Head", head);
            regions.add(group);
        });
        payload.put("Palettes", palettes);
        payload.put("Heads", regions);
        return payload;
    }
    @Test void manifestsResolveShuffledInputsAndMissingPrerequisitesFailAtomically() {
        ArchivedPileRecipe one = node(1, Set.of(ZERO), Set.of(ZERO));
        ArchivedPileRecipe two = node(2, Set.of(ZERO), Set.of());
        ArchivedRecipeStore store = new ArchivedRecipeStore();
        store.accept(manifest(List.of(two, one), Map.of(ID, 2L)));
        assertEquals(2, store.recipes().size());
        assertThrows(IllegalArgumentException.class, () -> store.accept(manifest(List.of(), Map.of(ID, 99L))));
        assertEquals(Map.of(ID, 2L), store.heads());
        store.accept(manifest(List.of(one), Map.of(ID, 1L)));
        assertEquals(Set.of(1L), store.recipes().keySet());
    }
    @Test void tagRevisionsAreStoredAsActualImmutableRules() {
        var changed = PaletteSpec.create(List.of(new PileModels.Candidate("oak", "minecraft:oak_planks", 5)), "resources-a");
        assertNotEquals(palette.id(), changed.id());
        ArchivedPileData data = new ArchivedPileData();
        data.add(ZERO, ID, 42);
        long old = data.commit(ID, palette, ArchivedTerrainSnapshot.EMPTY);
        data.add(ZERO.east(), OTHER, 1);
        long next = data.commit(ID, changed, ArchivedTerrainSnapshot.EMPTY);
        ArchivedPileData restored = ArchivedPileData.load(data.save(new CompoundTag()));
        assertEquals(palette, restored.palette(restored.recipe(old).palette()));
        assertEquals(changed, restored.palette(restored.recipe(next).palette()));
    }

    @Test void embeddedMembersPersistAcrossRecipeSnapshotsWithoutRepackingOrLeavingTheRegion() {
        ArchivedPileData data = new ArchivedPileData();
        data.add(ZERO, ID, 42);
        data.add(ZERO.east(), OTHER, 9);
        long initial = data.commit(ID, palette, ArchivedTerrainSnapshot.EMPTY);
        assertFalse(data.recipe(initial).active().isEmpty());

        EmbeddedBlock embedded = data.addEmbedded(ZERO.east());
        assertNotNull(embedded);
        assertEquals(1, embedded.revision());
        long placed = data.commit(ID, palette, ArchivedTerrainSnapshot.EMPTY);
        assertTrue(data.recipe(placed).active().isEmpty());
        assertEquals(Map.of(ZERO.east(), embedded), data.recipe(placed).embedded());
        assertEquals(Set.of(ZERO, ZERO.east()), data.connectedComponent(ZERO));

        ArchivedPileData restored = ArchivedPileData.load(data.save(new CompoundTag()));
        assertEquals(embedded, restored.embeddedAt(ZERO.east()));
        assertEquals(data.save(new CompoundTag()), restored.save(new CompoundTag()));
        EmbeddedBlock revised = restored.updateEmbedded(ZERO.east());
        assertEquals(embedded.instance(), revised.instance());
        assertEquals(2, revised.revision());
        assertTrue(restored.removeEmbedded(ZERO.east()));
        assertTrue(restored.contains(ZERO.east()));
        assertNull(restored.embeddedAt(ZERO.east()));
    }
    @Test void embeddingNeverCreatesMissingRegionMembership() {
        ArchivedPileData data = new ArchivedPileData();
        CompoundTag saved = data.save(new CompoundTag());
        assertNull(data.addEmbedded(ZERO));
        assertFalse(data.contains(ZERO));
        assertEquals(saved, data.save(new CompoundTag()));
    }

    @Test void embeddedPresenceFilterTracksMergesCancellationRemovalAndReload() {
        ArchivedPileData data = new ArchivedPileData();
        assertFalse(data.hasEmbedded());
        data.add(ZERO, ID, 42);
        data.add(ZERO.east(2), OTHER, 9);
        data.addEmbedded(ZERO);
        data.addEmbedded(ZERO.east(2));
        assertTrue(data.hasEmbedded());
        data.add(ZERO.east(), UUID.randomUUID(), 10);
        data.updateEmbedded(ZERO);
        data.remove(ZERO.east(2));
        assertTrue(data.hasEmbedded());
        ArchivedPileData loaded = ArchivedPileData.load(data.save(new CompoundTag()));
        assertTrue(loaded.hasEmbedded());
        assertTrue(loaded.cancelEmbedding(ZERO));
        assertFalse(loaded.hasEmbedded());
        assertFalse(ArchivedPileData.load(loaded.save(new CompoundTag())).hasEmbedded());
    }

    @Test void canceledEmbeddingNeverCarvesOrSchedulesRefilling() {
        ArchivedPileData data = new ArchivedPileData();
        data.add(ZERO, ID, 42);
        data.commit(ID, palette, ArchivedTerrainSnapshot.EMPTY);
        data.addEmbedded(ZERO);
        assertTrue(data.cancelEmbedding(ZERO));
        var aborted = data.recipe(data.commit(ID, palette, ArchivedTerrainSnapshot.EMPTY));
        assertTrue(aborted.embedded().isEmpty());
        assertTrue(aborted.damage().isEmpty());
        assertTrue(aborted.active().isEmpty());
        assertTrue(aborted.repack().isEmpty());
        assertTrue(data.contains(ZERO));
    }

    @Test void embeddedRemovalRefillsOnlyNearbyCellsAndKeepsOtherEmbeddedMembersFixed() {
        ArchivedPileData data = new ArchivedPileData();
        for (int x = 0; x <= 6; x++) data.add(ZERO.east(x), ID, 42);
        data.commit(ID, palette, ArchivedTerrainSnapshot.EMPTY);
        BlockPos diagonal = ZERO.west().north();
        data.add(diagonal, OTHER, 13);
        data.commit(OTHER, palette, ArchivedTerrainSnapshot.EMPTY);
        data.addEmbedded(ZERO);
        EmbeddedBlock otherEmbedded = data.addEmbedded(ZERO.east());
        data.commit(ID, palette, ArchivedTerrainSnapshot.EMPTY);
        assertTrue(data.removeEmbedded(ZERO));
        ArchivedPileData restored = ArchivedPileData.load(data.save(new CompoundTag()));
        assertEquals(2, restored.queuedCount());
        var local = restored.recipe(restored.commit(ID, palette, ArchivedTerrainSnapshot.EMPTY));
        assertEquals(Set.of(ZERO, ZERO.east(2)), local.active());
        assertTrue(local.repack().isEmpty());
        assertEquals(Map.of(ZERO.east(), otherEmbedded), local.embedded());
        var neighbor = restored.recipe(restored.commit(OTHER, palette, ArchivedTerrainSnapshot.EMPTY));
        assertEquals(Set.of(diagonal), neighbor.active());
        assertTrue(neighbor.repack().isEmpty());
        assertTrue(neighbor.damage().isEmpty());
        assertFalse(restored.removeEmbedded(ZERO));
        assertEquals(0, restored.queuedCount());
    }

    @Test void currentStorageNameAndFormatRemainFixedWithoutAlternativeReaders() throws Exception {
        var data = bytecode("com/scarasol/barricade/pile/PileData");
        assertEquals("barricade_embedded_v1", data.fields.stream().filter(field -> field.name.equals("NAME"))
                .findFirst().orElseThrow().value);
        assertEquals(3, new PileData().save(new CompoundTag()).getInt("Format"));
        assertEquals(10, FieldDefinition.ALGORITHM);
        assertEquals(FieldRules.FINGERPRINT, FieldDefinition.PARAMETERS);
        long loads = data.methods.stream().filter(method -> method.name.equals("load")).count();
        assertEquals(1, loads, "Only one saved-data reader is supported");
        assertTrue(calls(data, "load", "com/scarasol/barricade/pile/PileData", "readCurrent"));
    }
    @Test void savedDataRejectsUnsupportedFormatsAndInvalidCountersWithoutRepairingThem() {
        ArchivedPileData data = new ArchivedPileData();
        data.add(ZERO, ID, 42);
        EmbeddedBlock embedded = data.addEmbedded(ZERO);
        long head = data.commit(ID, palette, ArchivedTerrainSnapshot.EMPTY);
        CompoundTag saved = data.save(new CompoundTag());
        assertEquals(saved, ArchivedPileData.load(saved).save(new CompoundTag()));

        CompoundTag unsupported = saved.copy();
        unsupported.putInt("Format", saved.getInt("Format") - 1);
        assertThrows(IllegalArgumentException.class, () -> ArchivedPileData.load(unsupported));
        CompoundTag missingCounter = saved.copy();
        missingCounter.remove("NextEmbedded");
        assertThrows(IllegalArgumentException.class, () -> ArchivedPileData.load(missingCounter));
        CompoundTag invalidCounter = saved.copy();
        invalidCounter.putLong("NextEmbedded", embedded.instance());
        assertThrows(IllegalArgumentException.class, () -> ArchivedPileData.load(invalidCounter));
        assertEquals(embedded.instance(), invalidCounter.getLong("NextEmbedded"));

        CompoundTag unsupportedRecipe = data.recipe(head).save();
        unsupportedRecipe.putInt("Algorithm", ArchivedPileRecipe.ALGORITHM - 1);
        assertThrows(IllegalArgumentException.class, () -> ArchivedPileRecipe.load(unsupportedRecipe));
    }
    @Test void repeatedHistoricalEnvironmentIsEncodedOnceAndMissingReferencesAreRejected() {
        ArchivedPileRecipe one = node(1, Set.of(ZERO), Set.of(ZERO));
        ArchivedPileRecipe two = node(2, Set.of(ZERO), Set.of());
        CompoundTag archive = new CompoundTag();
        ArchivedRecipeArchive.write(archive, List.of(one, two));
        assertEquals(1, archive.getList("Contexts", Tag.TAG_COMPOUND).size());
        assertEquals(List.of(one, two), ArchivedRecipeArchive.read(archive));
        archive.getList("Recipes", Tag.TAG_COMPOUND).getCompound(1).putInt("TerrainIndex", 10);
        assertThrows(IllegalArgumentException.class, () -> ArchivedRecipeArchive.read(archive));
    }
    @Test void fragmentedMetadataHandlesDuplicatesOutOfOrderAndStaleTransfers() {
        byte[] noise = new byte[200_000];
        new Random(42).nextBytes(noise);
        CompoundTag payload = new CompoundTag();
        payload.putByteArray("Noise", noise);
        var packets = new ArrayList<>(PileNetwork.fragment(new ResourceLocation("minecraft", "overworld"), ID, 5, payload));
        assertTrue(packets.size() > 3);
        Collections.shuffle(packets, new Random(5));
        PileNetwork.Assembly assembly = new PileNetwork.Assembly();
        assertNull(assembly.accept(packets.get(0)));
        assertNull(assembly.accept(packets.get(0)));
        CompoundTag result = null;
        for (int i = 1; i < packets.size(); i++) {
            CompoundTag completed = assembly.accept(packets.get(i));
            if (completed != null) result = completed;
        }
        assertEquals(payload, result);
        assertNull(assembly.accept(packets.get(0)));
        assertThrows(IllegalArgumentException.class, () -> new PileNetwork.Fragment(
                new ResourceLocation("minecraft", "overworld"), ID, 6, 0, 1000, new byte[0]));
    }
    @Test void ordinaryBarricadesHaveNoEntityAndServerClassesHaveNoClientOrSamplerLinks() throws Exception {
        var block = bytecode("com/scarasol/barricade/block/BarricadeBlock");
        assertEquals("net/minecraft/world/level/block/Block", block.superName);
        assertTrue(block.interfaces.isEmpty());
        assertTrue(block.methods.stream().noneMatch(method -> method.name.equals("newBlockEntity")));
        var mod = bytecode("com/scarasol/barricade/Barricade");
        assertTrue(mod.fields.stream().noneMatch(field -> field.name.equals("BARRICADE_ENTITY")));
        for (String name : List.of("PileData", "PileService")) {
            try (var input = getClass().getResourceAsStream("/com/scarasol/barricade/pile/" + name + ".class")) {
                String constants = new String(Objects.requireNonNull(input).readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
                assertFalse(constants.contains("net/minecraft/client"));
                assertFalse(constants.contains("MaterialSampler"));
                assertFalse(constants.contains("LegacyPileGenerator"));
                assertFalse(constants.contains("Executors"));
            }
        }
    }
    private org.objectweb.asm.tree.ClassNode bytecode(String name) throws Exception {
        var node = new org.objectweb.asm.tree.ClassNode();
        try (var input = getClass().getResourceAsStream("/" + name + ".class")) {
            new org.objectweb.asm.ClassReader(Objects.requireNonNull(input)).accept(node, 0);
        }
        return node;
    }
    @Test void onlyLoadedChunksRebuildMissingMembershipAndRightClicksStillRequireExistingData() throws Exception {
        var service = bytecode("com/scarasol/barricade/pile/PileService");
        assertTrue(calls(service, "chunkLoaded", "com/scarasol/barricade/pile/PileRecovery", "rebuild"));
        for (var method : service.methods) {
            for (var instruction : method.instructions) {
                if (!(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call)) continue;
                assertFalse(call.owner.startsWith("net/minecraft/world/level/chunk/"),
                        "Chunk-local recovery is isolated from ordinary service paths");
                if (method.name.equals("rightClick"))
                    assertFalse(call.owner.equals("com/scarasol/barricade/pile/PileData") && call.name.equals("add"),
                            "Right-click embedding requires existing membership, not legacy-data adoption");
            }
        }
    }
    @Test void blockLifecycleOwnsTopologyAndEmbeddingNeverCommitsPermanentCarving() throws Exception {
        var service = bytecode("com/scarasol/barricade/pile/PileService");
        assertTrue(service.methods.stream().noneMatch(method -> method.name.equals("before") || method.name.equals("after")));
        for (var method : service.methods) {
            for (var instruction : method.instructions) {
                if (!(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call)) continue;
                assertFalse(call.owner.equals("com/scarasol/barricade/pile/PileData") && call.name.equals("damage"),
                        "Only the explicit embedding transaction may carve conflicts");
                assertFalse(call.owner.equals("com/scarasol/barricade/pile/PileData")
                        && (call.name.equals("add") || call.name.equals("remove")),
                        "Ordinary barricade membership belongs to BarricadeBlock");
            }
        }
        var placement = bytecode("com/scarasol/barricade/pile/EmbeddedPlacement");
        var place = placement.methods.stream().filter(method -> method.name.equals("place")).findFirst().orElseThrow();
        int carvings = 0;
        for (var instruction : place.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                    && call.owner.equals("com/scarasol/barricade/pile/PileData") && call.name.equals("damage"))
                carvings++;
        }
        assertEquals(0, carvings, "Embedding is exclusively a temporary current-state overlay");
        var block = bytecode("com/scarasol/barricade/block/BarricadeBlock");
        assertTrue(calls(block, "onPlace", "com/scarasol/barricade/pile/PileData", "add"));
        assertTrue(calls(block, "onRemove", "com/scarasol/barricade/block/BarricadeBlock", "removed"));
        assertTrue(calls(block, "removed", "com/scarasol/barricade/pile/PileData", "remove"));
    }

    private boolean calls(org.objectweb.asm.tree.ClassNode owner, String method, String target, String name) {
        return owner.methods.stream().filter(candidate -> candidate.name.equals(method)).anyMatch(candidate -> {
            for (var instruction : candidate.instructions)
                if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                        && call.owner.equals(target) && call.name.equals(name)) return true;
            return false;
        });
    }

    @Test void committedObserverUsesOnlyLoadedDataAndNeverHooksSetBlock() throws Exception {
        String config;
        try (var input = getClass().getResourceAsStream("/barricade.mixins.json")) {
            config = new String(Objects.requireNonNull(input).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertFalse(config.contains("LevelEditingMixin"));
        var observer = bytecode("com/scarasol/barricade/mixin/EmbeddedStateMixin");
        assertTrue(calls(observer, "barricade$embeddedChanged", "com/scarasol/barricade/pile/PileData", "loaded"));
        assertTrue(calls(observer, "barricade$embeddedChanged", "com/scarasol/barricade/pile/PileData", "hasEmbedded"));
        assertTrue(calls(observer, "barricade$embeddedChanged", "com/scarasol/barricade/pile/PileData", "embeddedAt"));
        for (var method : observer.methods) for (var instruction : method.instructions) {
            if (!(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call)) continue;
            assertFalse(call.owner.equals("com/scarasol/barricade/pile/PileData")
                    && Set.of("get", "add", "remove", "damage", "nearby").contains(call.name));
            assertFalse(call.owner.equals("com/scarasol/barricade/recipe/ArchivedTerrainSnapshot"));
        }
        var data = bytecode("com/scarasol/barricade/pile/PileData");
        assertFalse(calls(data, "loaded", "net/minecraft/server/level/ServerLevel", "getDataStorage"));
    }
    @Test void pistonProtectionUsesAPreEventAndReadOnlyLoadedMetadataWithoutMovementMixins() throws Exception {
        var service = bytecode("com/scarasol/barricade/pile/PileService");
        var method = service.methods.stream().filter(candidate -> candidate.name.equals("piston")).findFirst().orElseThrow();
        assertEquals("(Lnet/minecraftforge/event/level/PistonEvent$Pre;)V", method.desc);
        assertTrue(calls(service, "piston", "com/scarasol/barricade/pile/PileData", "loaded"));
        assertFalse(calls(service, "piston", "com/scarasol/barricade/pile/PileData", "get"));
        assertTrue(calls(service, "piston", "com/scarasol/barricade/pile/PistonGuard", "blocks"));
        var guard = bytecode("com/scarasol/barricade/pile/PistonGuard");
        for (var candidate : guard.methods) for (var instruction : candidate.instructions) {
            if (!(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call)) continue;
            assertFalse(Set.of("setBlock", "removeBlock", "destroyBlock", "moveBlocks").contains(call.name));
            if (call.owner.equals("com/scarasol/barricade/pile/PileData")) assertEquals("embeddedAt", call.name);
        }
    }
    @Test void embeddedSuppressionRebuildsUnchangedBlockMeshesAndPublishesToChunkWorkers() throws Exception {
        var renderer = bytecode("com/scarasol/barricade/client/BarricadeRenderer");
        int rebuilds = 0, concurrentSets = 0;
        for (var method : renderer.methods) for (var instruction : method.instructions) {
            if (!(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call)) continue;
            if (call.owner.equals("net/minecraft/client/renderer/LevelRenderer")) {
                assertNotEquals("setBlockDirty", call.name, "Identical states skip rebuilding the original mesh");
                if (method.name.equals("rebuildOriginal") && call.name.equals("setBlocksDirty")) rebuilds++;
            }
            if (method.name.equals("<clinit>") && call.owner.equals("java/util/concurrent/ConcurrentHashMap")
                    && call.name.equals("newKeySet")) concurrentSets++;
        }
        assertEquals(1, rebuilds);
        assertEquals(1, concurrentSets, "Chunk compilation reads suppression from worker threads");
    }
    @Test void clientAccessorsTargetExistingMinecraftMembers() throws Exception {
        for (var target : Map.of("net/minecraft/client/renderer/RenderType", "sortOnUpload",
                "net/minecraft/client/renderer/RenderType$CompositeState", "transparencyState",
                "net/minecraft/client/renderer/RenderType$CompositeRenderType", "state").entrySet())
            assertTrue(bytecode(target.getKey()).fields.stream().anyMatch(field -> field.name.equals(target.getValue())));
    }

    @Test void behavioralMixinsTargetExistingMethodsAndOnlyForgeOverloadSkipsRemapping() throws Exception {
        int hooks = 0;
        for (var target : Map.of(
                "EmbeddedRenderMixin", "net/minecraft/client/renderer/block/BlockRenderDispatcher",
                "EmbeddedFaceMixin", "net/minecraft/world/level/block/Block",
                "EmbeddedBlockEntityRenderMixin", "net/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher",
                "EmbeddedBlockEntityUpdateMixin", "net/minecraft/client/multiplayer/ClientPacketListener",
                "EmbeddedStateMixin", "net/minecraft/server/level/ServerLevel",
                "BlockItemSamplingMixin", "net/minecraft/world/item/BlockItem").entrySet()) {
            var mixin = bytecode("com/scarasol/barricade/mixin/" + target.getKey());
            var owner = bytecode(target.getValue());
            for (var method : mixin.methods) {
                if (method.visibleAnnotations == null) continue;
                var inject = method.visibleAnnotations.stream()
                        .filter(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;"))
                        .findFirst().orElse(null);
                if (inject == null) continue;
                int selectorIndex = inject.values.indexOf("method");
                assertTrue(selectorIndex >= 0);
                for (Object selector : (List<?>) inject.values.get(selectorIndex + 1))
                    assertTrue(owner.methods.stream().anyMatch(candidate ->
                            (candidate.name + candidate.desc).equals(selector)), target.getKey() + ": " + selector);
                int remapIndex = inject.values.indexOf("remap");
                boolean remap = remapIndex < 0 || Boolean.TRUE.equals(inject.values.get(remapIndex + 1));
                assertEquals(!target.getKey().equals("EmbeddedRenderMixin"), remap, target.getKey());
                hooks++;
            }
        }
        assertEquals(6, hooks);
    }
}
