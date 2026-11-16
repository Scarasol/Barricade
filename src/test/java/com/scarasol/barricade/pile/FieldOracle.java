package com.scarasol.barricade.pile;

/** Test-only stage oracle. No runtime class or fallback may refer to this selector. */
public final class FieldOracle {
    private FieldOracle() { }
    public static int[] solve(FieldBatch batch) {
        if (batch instanceof PackingGroup group)
            return group.combine(group.plans.stream().map(TestBatchSolver::solve).toList());
        return TestBatchSolver.solve((PackingBatch) batch);
    }
}
