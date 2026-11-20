package com.scarasol.barricade.stringlights;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class PowerBoxEntity extends BlockEntity {
    private static final String INFINITE_ENERGY = "infiniteEnergy";
    private final BoxEnergy energy = new BoxEnergy(this::setChanged);
    private LazyOptional<IEnergyStorage> capability = LazyOptional.of(() -> energy);
    private boolean infiniteEnergy;
    private boolean supplying;
    private boolean starved;
    private int demand;
    private int lastFe = -1;
    private int lastDemand = -1;
    private boolean lastSupply;
    public PowerBoxEntity(BlockPos pos, BlockState state) {
        super(StringLights.BOX_ENTITY.get(), pos, state);
    }

    @Override public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) LightWorld.get(server).loadedBox(this);
    }

    boolean settle(int lamps) {
        boolean enabled = getBlockState().getValue(PowerBoxBlock.ON);
        demand = enabled ? lamps : 0;
        boolean wasSupplying = supplying;
        supplying = demand > 0 && (infiniteEnergy || energy.payTick(demand, starved));
        starved = demand > 0 && !supplying;
        return wasSupplying != supplying;
    }

    void stop() {
        supplying = false;
        demand = 0;
    }

    void syncStatus() {
        int fe = displayedEnergy();
        if (lastFe != fe || lastDemand != demand || lastSupply != supplying) {
            lastFe = fe;
            lastDemand = demand;
            lastSupply = supplying;
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
        }
    }

    public int displayedEnergy() { return energy.getEnergyStored(); }
    public int demand() { return demand; }
    public boolean supplying() { return supplying; }

    @Override public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ENERGY && !isRemoved()) return capability.cast();
        return super.getCapability(cap, side);
    }

    @Override public void invalidateCaps() {
        super.invalidateCaps();
        capability.invalidate();
    }

    @Override public void reviveCaps() {
        super.reviveCaps();
        capability = LazyOptional.of(() -> energy);
    }

    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("energy", energy.units());
        if (infiniteEnergy) tag.putBoolean(INFINITE_ENERGY, true);
    }

    @Override public void load(CompoundTag tag) {
        super.load(tag);
        energy.load(tag.getInt("energy"));
        infiniteEnergy = tag.getBoolean(INFINITE_ENERGY);
        demand = tag.getInt("demand");
        supplying = tag.getBoolean("supplying");
    }

    @Override public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("energy", energy.units());
        tag.putInt("demand", demand);
        tag.putBoolean("supplying", supplying);
        return tag;
    }

    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
