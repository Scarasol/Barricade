package com.scarasol.barricade.pile;

import java.util.*;

/** Independent regions share uploads and fences, never geometry, quotas or acceptance state. */
public final class PackingGroup extends FieldBatch {
    public final List<PackingBatch> plans;
    private final int[] stateOffsets;
    public PackingGroup(List<PackingBatch> plans) { this(List.copyOf(plans), encode(plans)); }
    private PackingGroup(List<PackingBatch> plans, int[][] buffers) {
        super(buffers[0], buffers[1], plans.stream().mapToInt(plan -> plan.size).sum());
        this.plans = plans; this.stateOffsets = buffers[2];
    }
    private static int[][] encode(List<PackingBatch> plans) {
        int inputWords = align(4 + plans.size() * 2), stateWords = 0;
        for (PackingBatch plan : plans) { inputWords += align(plan.input.length); stateWords += align(plan.initial.length); }
        int[] input = new int[inputWords], initial = new int[stateWords], offsets = new int[plans.size()];
        input[0] = -9; input[1] = plans.size();
        int a = align(4 + plans.size() * 2), s = 0;
        for (int i = 0; i < plans.size(); i++) {
            PackingBatch plan = plans.get(i);
            input[4 + i * 2] = a; input[5 + i * 2] = s; offsets[i] = s;
            System.arraycopy(plan.input, 0, input, a, plan.input.length);
            System.arraycopy(plan.initial, 0, initial, s, plan.initial.length);
            a += align(plan.input.length); s += align(plan.initial.length);
        }
        return new int[][]{input, initial, offsets};
    }
    private static int align(int words) { return (words + 63) / 64 * 64; }
    public List<int[]> split(int[] state) {
        boolean compact = state.length == plans.stream().mapToInt(PackingBatch::resultWords).sum();
        if (!compact && state.length != initial.length) throw new IllegalStateException("Invalid grouped packing output");
        List<int[]> result = new ArrayList<>();
        int next = 0;
        for (int i = 0; i < plans.size(); i++) {
            int offset = compact ? next : stateOffsets[i];
            int words = compact ? plans.get(i).resultWords() : plans.get(i).initial.length;
            result.add(Arrays.copyOfRange(state, offset, offset + words));
            next += words;
        }
        return result;
    }
    public int[] combine(List<int[]> states) {
        int[] result = initial.clone();
        for (int i = 0; i < plans.size(); i++)
            System.arraycopy(states.get(i), 0, result, stateOffsets[i], states.get(i).length);
        return result;
    }
}
