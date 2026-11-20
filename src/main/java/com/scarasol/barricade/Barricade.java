package com.scarasol.barricade;

import com.mojang.logging.LogUtils;
import com.scarasol.barricade.block.BarricadeBlock;
import com.scarasol.barricade.block.BarricadeItem;
import com.scarasol.barricade.network.PileNetwork;
import com.scarasol.barricade.pile.PileService;
import com.scarasol.barricade.stringlights.StringLights;
import com.scarasol.barricade.window.WindowBoards;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.MinecraftForge;
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
    private static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);

    public static final RegistryObject<Block> BARRICADE = BLOCKS.register("barricade", BarricadeBlock::new);
    public static final RegistryObject<Item> BARRICADE_ITEM = ITEMS.register("barricade",
            () -> new BarricadeItem(BARRICADE.get(), new Item.Properties()));
    private static final RegistryObject<CreativeModeTab> MAIN_TAB = CREATIVE_TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.barricade"))
                    .icon(() -> new ItemStack(BARRICADE_ITEM.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(BARRICADE_ITEM.get());
                        output.accept(WindowBoards.ITEM.get());
                        output.accept(WindowBoards.BARS_ITEM.get());
                        output.accept(StringLights.BOX_ITEM.get());
                        output.accept(StringLights.WIRE.get());
                    })
                    .build());

    @SuppressWarnings("removal")
    public Barricade() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(bus);
        ITEMS.register(bus);
        CREATIVE_TABS.register(bus);
        PileNetwork.init();
        MinecraftForge.EVENT_BUS.register(PileService.class);
    }
}
