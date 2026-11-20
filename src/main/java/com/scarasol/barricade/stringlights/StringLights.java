package com.scarasol.barricade.stringlights;

import com.scarasol.barricade.Barricade;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.registries.RegistryObject;

@Mod.EventBusSubscriber(modid = Barricade.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class StringLights {
    public static final ResourceLocation WIRE_ID = id("string_light_wire");
    public static final ResourceLocation BOX_ID = id("string_light_box");
    public static final ResourceLocation BULB_ID = id("string_light_bulb");
    public static final RegistryObject<Block> BOX = RegistryObject.create(BOX_ID, ForgeRegistries.BLOCKS);
    public static final RegistryObject<Block> BULB = RegistryObject.create(BULB_ID, ForgeRegistries.BLOCKS);
    public static final RegistryObject<Item> WIRE = RegistryObject.create(WIRE_ID, ForgeRegistries.ITEMS);
    public static final RegistryObject<Item> BOX_ITEM = RegistryObject.create(BOX_ID, ForgeRegistries.ITEMS);
    static final RegistryObject<BlockEntityType<?>> BOX_ENTITY =
            RegistryObject.create(BOX_ID, ForgeRegistries.BLOCK_ENTITY_TYPES);
    private StringLights() { }

    static ResourceLocation id(String path) { return new ResourceLocation(Barricade.MOD_ID, path); }

    @SubscribeEvent public static void register(RegisterEvent event) {
        event.register(ForgeRegistries.Keys.BLOCKS, register -> {
            register.register(BOX_ID, new PowerBoxBlock());
            register.register(BULB_ID, new BulbBlock());
        });
        event.register(ForgeRegistries.Keys.ITEMS, register -> {
            register.register(WIRE_ID, new LightWireItem());
            register.register(BOX_ID, new PowerBoxItem(BOX.get()));
        });
        event.register(ForgeRegistries.Keys.BLOCK_ENTITY_TYPES, register ->
                register.register(BOX_ID, BlockEntityType.Builder.of(PowerBoxEntity::new, BOX.get()).build(null)));
    }

    @SubscribeEvent public static void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(LightNetwork::initialize);
    }

}
