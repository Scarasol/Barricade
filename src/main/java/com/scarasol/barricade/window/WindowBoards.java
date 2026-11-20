package com.scarasol.barricade.window;

import com.scarasol.barricade.Barricade;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.registries.RegistryObject;

@Mod.EventBusSubscriber(modid = Barricade.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class WindowBoards {
    public static final ResourceLocation ID = new ResourceLocation(Barricade.MOD_ID, "window_boards");
    public static final RegistryObject<Block> BLOCK = RegistryObject.create(ID, ForgeRegistries.BLOCKS);
    public static final RegistryObject<Item> ITEM = RegistryObject.create(ID, ForgeRegistries.ITEMS);
    public static final ResourceLocation BARS_ID = new ResourceLocation(Barricade.MOD_ID, "window_bars");
    public static final RegistryObject<Block> BARS_BLOCK = RegistryObject.create(BARS_ID, ForgeRegistries.BLOCKS);
    public static final RegistryObject<Item> BARS_ITEM = RegistryObject.create(BARS_ID, ForgeRegistries.ITEMS);
    private WindowBoards() { }
    @SubscribeEvent public static void register(RegisterEvent event) {
        event.register(ForgeRegistries.Keys.BLOCKS, helper -> {
            helper.register(ID, new WindowBoardsBlock());
            helper.register(BARS_ID, new WindowBarsBlock());
        });
        event.register(ForgeRegistries.Keys.ITEMS, helper -> {
            helper.register(ID, new WindowSealItem(BLOCK.get()));
            helper.register(BARS_ID, new WindowSealItem(BARS_BLOCK.get()));
        });
    }
    @SubscribeEvent public static void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(WindowBoardsNetwork::initialize);
    }
}
