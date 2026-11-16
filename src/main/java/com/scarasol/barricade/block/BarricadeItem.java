package com.scarasol.barricade.block;

import com.scarasol.barricade.pile.PileService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

public final class BarricadeItem extends BlockItem {
    public BarricadeItem(Block block, Properties properties) { super(block, properties); }

    @Override public InteractionResult place(BlockPlaceContext context) {
        if (!(context.getLevel() instanceof ServerLevel)) return super.place(context);
        // Forge commits onPlace after this call returns; retain only the captured placement's selector.
        try (PileService.PlacementScope placement = PileService.placing(context)) {
            InteractionResult result = super.place(context);
            if (result.consumesAction()) placement.successful();
            return result;
        }
    }
}
