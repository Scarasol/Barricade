package com.scarasol.barricade.recipe;

/** One versioned production algorithm. Region size is fixed after offline measurements. */
public final class FieldRules {
    private FieldRules() { }
    public static final int REGION_SIDE = 3;
    public static final int SKELETON_SIDE = 4;
    public static final int SKELETON_OFFSET = 2;
    public static final int PRIMARY_PASSES = 4, LAYERS = 5;
    public static final int MAX_MODEL_RADIUS = 5;
    // A ranked body can cross its cube; include its skeleton inputs and their geometry halo.
    public static final int DEPENDENCY_RADIUS = (2 * SKELETON_SIDE + MAX_MODEL_RADIUS + REGION_SIDE - 1) / REGION_SIDE;
    public static final int DISPLAY_RADIUS = DEPENDENCY_RADIUS + (MAX_MODEL_RADIUS + REGION_SIDE - 1) / REGION_SIDE;
    public static final float OVERHANG = .5f;
    public static final String FINGERPRINT = "regional-8:side=" + REGION_SIDE + ":skeleton=" + SKELETON_SIDE + ":offset=" + SKELETON_OFFSET
            + ":skeletonPasses=1:passes=" + PRIMARY_PASSES + ":bounds=closed-body-and-corridors"
            + ":trials=12,12,12,64,80:rounds=2,2,2,8,8:overhang=0"
            + ":scale=.95,1.05:order=declared-skeleton-then-independent-interiors:geometry=neighbor-baseline:settle=active-center"
            + ":fill=quarter-axis-center-2to4-wall-projection:baseline=domain-union-mask:domain-walls=body-only";
}
