package com.scarasol.barricade.pile;

/** Encoded buffers for one current packing region or a group of independent regions. */
public abstract sealed class FieldBatch permits PackingBatch, PackingGroup {
    public final int[] input, initial;
    public final int size;
    protected FieldBatch(int[] input, int[] initial, int size) {
        this.input = input; this.initial = initial; this.size = size;
    }
    public static final class CapacityException extends IllegalArgumentException {
        public CapacityException(String message) { super(message); }
    }
}
