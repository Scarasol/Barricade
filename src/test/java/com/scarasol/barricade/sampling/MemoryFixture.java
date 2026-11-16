package com.scarasol.barricade.sampling;

import com.mojang.serialization.Lifecycle;
import java.util.OptionalLong;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;

public final class MemoryFixture {
    private static RegistryAccess registries;

    public static MaterialSampler sampler(MemoryLevel level) { return sampler(level, null, ""); }

    public static MaterialSampler sampler(MemoryLevel level, SampleCache cache, String context) {
        // Headless tests use outline envelopes; production takes bounds from captured render vertices.
        return new MaterialSampler(level, cache, context, parts -> {
            net.minecraft.world.phys.AABB bounds = null;
            BlockPos origin = new BlockPos(0, 8, 0);
            for (var part : parts) {
                BlockPos pos = origin.offset(Math.round(part.x()), Math.round(part.y()), Math.round(part.z()));
                var shape = level.getBlockState(pos).getShape(level, pos);
                if (shape.isEmpty()) continue;
                var box = shape.bounds().move(part.x(), part.y(), part.z());
                bounds = bounds == null ? box : bounds.minmax(box);
            }
            if (bounds == null) throw new UnsupportedOperationException("Empty fixture appearance");
            return new com.scarasol.barricade.pile.CollisionBox((float) bounds.minX, (float) bounds.minY,
                    (float) bounds.minZ, (float) bounds.maxX, (float) bounds.maxY, (float) bounds.maxZ);
        });
    }

    public static TestMemoryLevel create(boolean client) {
        if (registries == null) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            RegistryAccess builtins = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            var lookup = new RegistrySetBuilder().add(Registries.DAMAGE_TYPE, DamageTypes::bootstrap).build(builtins);
            var damage = new MappedRegistry<DamageType>(Registries.DAMAGE_TYPE, Lifecycle.stable());
            lookup.lookupOrThrow(Registries.DAMAGE_TYPE).listElements()
                    .forEach(holder -> Registry.register(damage, holder.key(), holder.value()));
            var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
            Registry.register(biomes, Biomes.PLAINS, new Biome.BiomeBuilder().hasPrecipitation(true)
                    .temperature(0.8f).downfall(0.4f)
                    .specialEffects(new BiomeSpecialEffects.Builder().fogColor(0).waterColor(0x3f76e4)
                            .waterFogColor(0).skyColor(0).build())
                    .mobSpawnSettings(MobSpawnSettings.EMPTY).generationSettings(BiomeGenerationSettings.EMPTY).build());
            var dimensions = new MappedRegistry<DimensionType>(Registries.DIMENSION_TYPE, Lifecycle.stable());
            Registry.register(dimensions, BuiltinDimensionTypes.OVERWORLD,
                    new DimensionType(OptionalLong.of(6000), true, false, false, true, 1,
                            true, false, 0, 32, 32, BlockTags.INFINIBURN_OVERWORLD,
                            new ResourceLocation("minecraft", "overworld"), 0,
                            new DimensionType.MonsterSettings(false, false, ConstantInt.of(0), 0)));
            registries = new RegistryAccess.ImmutableRegistryAccess(Stream.concat(builtins.registries(), Stream.of(
                    new RegistryAccess.RegistryEntry<>(Registries.DAMAGE_TYPE, damage),
                    new RegistryAccess.RegistryEntry<>(Registries.BIOME, biomes),
                    new RegistryAccess.RegistryEntry<>(Registries.DIMENSION_TYPE, dimensions))));
        }
        return new TestMemoryLevel(registries, registries.registryOrThrow(Registries.DIMENSION_TYPE)
                .getHolderOrThrow(BuiltinDimensionTypes.OVERWORLD), FeatureFlags.DEFAULT_FLAGS,
                new RecipeManager(), client);
    }
}
