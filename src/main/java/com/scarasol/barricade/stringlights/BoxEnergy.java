package com.scarasol.barricade.stringlights;

import net.minecraftforge.energy.IEnergyStorage;

final class BoxEnergy implements IEnergyStorage {
    static final int CAPACITY_FE = 1000;
    private static final int UNITS_PER_FE = 20;
    private static final int CAPACITY = CAPACITY_FE * UNITS_PER_FE;
    private final Runnable changed;
    private int units;

    BoxEnergy(Runnable changed) { this.changed = changed; }

    @Override public int receiveEnergy(int amount, boolean simulate) {
        int accepted = Math.max(0, Math.min(amount, (CAPACITY - units) / UNITS_PER_FE));
        if (!simulate && accepted != 0) {
            units += accepted * UNITS_PER_FE;
            changed.run();
        }
        return accepted;
    }

    boolean payTick(int lamps, boolean recovering) {
        if (lamps <= 0) return false;
        if (lamps > CAPACITY) return false;
        int restart = recovering ? Math.min(CAPACITY, lamps + Math.min(UNITS_PER_FE * 2, lamps)) : lamps;
        // Do not lock recovery if the last whole-FE input leaves the balance just below full.
        if ((units < restart && (CAPACITY - units) >= UNITS_PER_FE) || units < lamps) return false;
        units -= lamps;
        changed.run();
        return true;
    }

    int units() { return units; }
    void load(int amount) { units = Math.max(0, Math.min(CAPACITY, amount)); }

    @Override public int extractEnergy(int amount, boolean simulate) { return 0; }
    @Override public int getEnergyStored() { return units / UNITS_PER_FE; }
    @Override public int getMaxEnergyStored() { return CAPACITY_FE; }
    @Override public boolean canExtract() { return false; }
    @Override public boolean canReceive() { return true; }
}
