package com.scarasol.barricade;

import com.mojang.logging.LogUtils;
import com.scarasol.barricade.block.BarricadeBlock;
import com.scarasol.barricade.block.BarricadeItem;
import com.scarasol.barricade.network.PileNetwork;
import com.scarasol.barricade.pile.PileService;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

@Mod(Barricade.MOD_ID)
public final class Barricade {
    public static final String MOD_ID = "barricade";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID);
    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);

    public static final RegistryObject<Block> BARRICADE = BLOCKS.register("barricade", BarricadeBlock::new);
    public static final RegistryObject<Item> BARRICADE_ITEM = ITEMS.register("barricade",
            () -> new BarricadeItem(BARRICADE.get(), new Item.Properties()));

    @SuppressWarnings("removal")
    public Barricade() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(bus);
        ITEMS.register(bus);
        PileNetwork.init();
        bus.addListener(this::creativeTab);
        MinecraftForge.EVENT_BUS.register(PileService.class);
    }

    private void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(BARRICADE_ITEM);
        }
    }
}
