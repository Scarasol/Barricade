package net.minecraftforge.registries;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.fluids.FluidType;

/** Minimal entity-construction prerequisites for headless collision tests, not a mod loader. */
public final class WindowBoardsTestRegistries {
    private WindowBoardsTestRegistries() {}

    public static void initializeFluids() {
        if (ForgeRegistries.FLUID_TYPES.get() != null) return;
        var registry = (ForgeRegistry<FluidType>) new RegistryBuilder<FluidType>()
                .setName(ForgeRegistries.Keys.FLUID_TYPES.location()).create();
        registry.register(-1, new ResourceLocation("minecraft", "empty"), new FluidType(FluidType.Properties.create()) {});
        ForgeMod.EMPTY_TYPE.updateReference(registry);
    }

    public static void initializePlayerAttributes() {
        if (ForgeMod.ENTITY_REACH.isPresent()) return;
        var registry = (ForgeRegistry<Attribute>) ForgeRegistries.ATTRIBUTES;
        boolean locked = registry.isLocked();
        registry.unfreeze();
        ((MappedRegistry<Attribute>) BuiltInRegistries.ATTRIBUTE).unfreeze();
        try {
            attribute(registry, ForgeMod.SWIM_SPEED, 1, 0, 1024);
            attribute(registry, ForgeMod.NAMETAG_DISTANCE, 64, 0, 64);
            attribute(registry, ForgeMod.ENTITY_GRAVITY, 0.08, -8, 8);
            attribute(registry, ForgeMod.STEP_HEIGHT_ADDITION, 0, -512, 512);
            attribute(registry, ForgeMod.BLOCK_REACH, 4.5, 0, 1024);
            attribute(registry, ForgeMod.ENTITY_REACH, 3, 0, 1024);
        } finally {
            BuiltInRegistries.ATTRIBUTE.freeze();
            if (locked) registry.freeze();
        }
    }

    private static void attribute(ForgeRegistry<Attribute> registry, RegistryObject<Attribute> reference,
                                  double value, double minimum, double maximum) {
        registry.register(-1, reference.getId(),
                new RangedAttribute(reference.getId().toString(), value, minimum, maximum).setSyncable(true));
        reference.updateReference(registry);
    }
}
