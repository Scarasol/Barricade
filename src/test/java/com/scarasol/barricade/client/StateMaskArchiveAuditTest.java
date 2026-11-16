package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;

import com.scarasol.barricade.recipe.*;
import com.scarasol.barricade.sampling.MemoryFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "BARRICADE_GPU_TESTS", matches = "1")
class StateMaskArchiveAuditTest {
    @Test void inspectRecordedInputsWithoutChangingTheSave() throws Exception {
        MemoryFixture.create(false).reset();
        Path file = Path.of("run/saves/Test World/data/barricade_embedded_v1.dat");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(file), "Optional local test world");
        byte[] original = Files.readAllBytes(file);
        CompoundTag tag = NbtIo.readCompressed(file.toFile()).getCompound("data");
        List<ArchivedPileRecipe> recipes = ArchivedRecipeArchive.read(tag);
        List<PaletteSpec> palettes = new ArrayList<>();
        for (Tag value : tag.getList("Palettes", Tag.TAG_COMPOUND))
            palettes.add(PaletteSpec.load((CompoundTag) value));
        Set<String> states = new TreeSet<>(), damaged = new TreeSet<>();
        for (ArchivedPileRecipe recipe : recipes) {
            recipe.terrain().blocks().values().forEach(block -> states.add(block.state()));
            for (ArchivedPileRecipe.Damage damage : recipe.damage()) {
                if (!damage.fullCell()) damaged.add(damage.state());
                damage.context().blocks().values().forEach(block -> states.add(block.state()));
            }
        }
        System.out.printf(Locale.ROOT,
                "State-mask archive: recipes=%d generating=%d palettes=%d regions=%d%n",
                recipes.size(), recipes.stream().filter(node -> !node.active().isEmpty()).count(),
                palettes.size(), tag.getList("Regions", Tag.TAG_COMPOUND).size());
        for (Tag value : tag.getList("Regions", Tag.TAG_COMPOUND)) {
            CompoundTag region = (CompoundTag) value;
            ArchivedPileRecipe head = recipes.stream().filter(node -> node.id() == region.getLong("Head"))
                    .findFirst().orElseThrow();
            System.out.printf(Locale.ROOT, "State-mask region: head=%d cells=%d embedded=%d palette=%s%n",
                    head.id(), head.domain().size(), head.embedded().size(), head.palette());
        }
        System.out.println("State-mask terrain states: " + states);
        System.out.println("State-mask non-cell damage states: " + damaged);
        assertArrayEquals(original, Files.readAllBytes(file), "Audit must never modify a live save");
    }
}
