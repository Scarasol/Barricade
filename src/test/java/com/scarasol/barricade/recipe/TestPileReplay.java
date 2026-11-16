package com.scarasol.barricade.recipe;

import com.scarasol.barricade.pile.LayoutInput;
import com.scarasol.barricade.pile.LayoutResult;
import com.scarasol.barricade.pile.TestBatchSolver;
import java.util.List;
import java.util.function.Function;

public final class TestPileReplay {
    private TestPileReplay() { }

    public static List<ArchivedPileReplay.ObjectPiece> replay(ArchivedPileReplay.Prepared prepared,
                                                       List<List<ArchivedPileReplay.ObjectPiece>> parents) {
        return replay(prepared, parents, List.of());
    }

    public static List<ArchivedPileReplay.ObjectPiece> replay(ArchivedPileReplay.Prepared prepared,
            List<List<ArchivedPileReplay.ObjectPiece>> parents, List<List<ArchivedPileReplay.ObjectPiece>> nearby) {
        return ArchivedPileReplay.replay(prepared, parents, nearby, TestBatchSolver::generate);
    }

    public static List<ArchivedPileReplay.ObjectPiece> replay(ArchivedPileReplay.Prepared prepared,
            List<List<ArchivedPileReplay.ObjectPiece>> parents, List<List<ArchivedPileReplay.ObjectPiece>> nearby,
            Function<LayoutInput, LayoutResult> generator) {
        return ArchivedPileReplay.replay(prepared, parents, nearby, generator);
    }
}
