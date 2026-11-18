package com.scarasol.barricade.pile;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;

public final class TestBatchSolver {
    private static final ForkJoinPool POOL = new ForkJoinPool(Math.max(1,
            Math.min(3, Runtime.getRuntime().availableProcessors() - 2)));

    private TestBatchSolver() { }

    public static int[] solve(ArchivedBatchLayout layout) {
        return solve(layout.input, layout.initial, ArchivedBatchLayout.ROUNDS);
    }
    public static int[] solve(PackingBatch layout) { return solve(layout.input, layout.initial, PackingBatch.ROUNDS); }
    static int[] solve(int[] source, int[] initial, int[] rounds) {
        int[] input = source.clone(), state = initial.clone();
        LayoutKernel kernel = new LayoutKernel(input, state);
        for (int phase = 0; phase < 5; phase++) for (int round = 0; round < rounds[phase]; round++) {
            interrupted();
            int first = input[input[16] + phase * input[7] * 2];
            int last = input[input[16] + (phase + 1) * input[7] * 2 - 1];
            if (last - first >= 1024) POOL.invoke(new Evaluation(kernel, first, last, phase, round, Thread.currentThread()));
            else for (int id = first; id < last; id++) kernel.evaluate(id, phase, round);
            interrupted();
            if (input[7] >= 64) POOL.invoke(new Decisions(kernel, 0, input[7], phase, round, false));
            else for (int owner = 0; owner < input[7]; owner++) kernel.propose(owner, phase, round);
            if (input[7] >= 64) POOL.invoke(new Decisions(kernel, 0, input[7], phase, round, true));
            else for (int owner = 0; owner < input[7]; owner++) kernel.screen(owner, phase, round);
            interrupted();
            kernel.apply(phase, round);
        }
        for (int id = 0; id < input[11]; id++) kernel.rankCandidate(id);
        for (int owner = 0; owner < input[7]; owner++) kernel.rankOwner(owner);
        kernel.retainObject();
        return state;
    }

    public static LayoutResult generate(LayoutInput input) {
        ArchivedBatchLayout plan = ArchivedBatchLayout.prepare(input);
        return plan.decode(solve(plan));
    }

    private static void interrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Test layout interrupted");
    }

    private static final class Evaluation extends RecursiveAction {
        private final LayoutKernel kernel;
        private final int first, last, phase, round;
        private final Thread owner;
        Evaluation(LayoutKernel kernel, int first, int last, int phase, int round, Thread owner) {
            this.kernel = kernel; this.first = first; this.last = last;
            this.phase = phase; this.round = round; this.owner = owner;
        }
        @Override protected void compute() {
            if (owner.isInterrupted()) throw new CancellationException("Test layout interrupted");
            if (last - first <= 128) {
                for (int id = first; id < last; id++) {
                    if (owner.isInterrupted()) throw new CancellationException("Test layout interrupted");
                    kernel.evaluate(id, phase, round);
                }
            } else {
                int middle = (first + last) >>> 1;
                invokeAll(new Evaluation(kernel, first, middle, phase, round, owner),
                        new Evaluation(kernel, middle, last, phase, round, owner));
            }
        }
    }

    private static final class Decisions extends RecursiveAction {
        private final LayoutKernel kernel;
        private final int first, last, phase, round;
        private final boolean screen;
        Decisions(LayoutKernel kernel, int first, int last, int phase, int round, boolean screen) {
            this.kernel = kernel; this.first = first; this.last = last;
            this.phase = phase; this.round = round; this.screen = screen;
        }
        @Override protected void compute() {
            interrupted();
            if (last - first <= 16) {
                for (int owner = first; owner < last; owner++) {
                    if (screen) kernel.screen(owner, phase, round);
                    else kernel.propose(owner, phase, round);
                }
            } else {
                int middle = (first + last) >>> 1;
                invokeAll(new Decisions(kernel, first, middle, phase, round, screen),
                        new Decisions(kernel, middle, last, phase, round, screen));
            }
        }
    }
}
