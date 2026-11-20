package com.scarasol.barricade.stringlights;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class LightRulesTest {
    @Test void analyticalArcAgreesWithAnIndependentFinePolyline() {
        for (Vec3 delta : List.of(new Vec3(8, 0, 0), new Vec3(8, -4, 6), new Vec3(0.000001, 23, 0.00002),
                new Vec3(0.000001, -23, 0), new Vec3(0, 31, 0), new Vec3(0.01, 0.004, 0.003),
                new Vec3(0, 0, 32))) {
            var curve = new LightCurve(new Vec3(-2, 1.75, 0.125), delta.add(-2, 1.75, 0.125));
            Vec3 last = curve.start();
            double sum = 0;
            for (int i = 1; i <= 16000; i++) {
                Vec3 point = curve.point(i / 16000.0);
                sum += point.distanceTo(last);
                last = point;
            }
            assertEquals(sum, curve.length(), 1e-7, delta.toString());
            assertEquals(curve.bulbs(), new LightCurve(curve.end(), curve.start()).bulbs());
        }
        var max = new LightCurve(Vec3.ZERO, new Vec3(32, 0, 0));
        assertEquals(33, max.cost());
        assertEquals(32, new LightCurve(Vec3.ZERO, new Vec3(0, 32, 0)).cost());
        assertThrows(IllegalArgumentException.class, () -> new LightCurve(Vec3.ZERO, new Vec3(32.000001, 0, 0)));
    }

    @Test void gridSplitIsContinuousAndDisturbanceNeverLeavesTheSelectedCell() {
        for (int i = 1; i <= 50; i++) {
            Vec3 a = new Vec3(-18.25 + i / 8.0, -5.75, -1.75);
            Vec3 b = a.add(0.25 * i, (i % 5) - 2, i / 4.0);
            var curve = new LightCurve(a, b);
            int count = Math.max(1, (int) Math.round(curve.length() / 4));
            assertEquals(count, curve.bulbs().size());
            double last = 0;
            for (var piece : curve.pieces()) {
                assertEquals(last, piece.from());
                assertTrue(last < piece.to());
                assertEquals(piece.cell(), BlockPos.containing(curve.point((piece.from() + piece.to()) / 2)));
                last = piece.to();
            }
            assertEquals(1, last);
            for (int lamp = 0; lamp < count; lamp++) {
                var bulb = curve.bulbs().get(lamp);
                var base = curve.point(curve.parameter((lamp + 0.5) * curve.length() / count));
                assertEquals(BlockPos.containing(base), bulb.cell());
                assertEquals(bulb.cell(), BlockPos.containing(bulb.anchor()));
            }
        }
        var vertical = new LightCurve(new Vec3(0.5, -4, 0.5), new Vec3(0.5, 4, 0.5));
        assertEquals(List.of(-2, 2), vertical.bulbs().stream().map(bulb -> bulb.cell().getY()).toList());
    }

    @Test void resizeLeaveAndReconnectPreserveIdentityAndCurrentDamage() {
        var data = new LightData();
        var graph = data.graph;
        var a = add(graph, 0);
        var b = add(graph, 5);
        var c = add(graph, 13);
        var d = add(graph, 20);
        assertTrue(graph.attach(a.surface, a));
        assertTrue(graph.attach(d.surface, d));
        var ab = span(graph, a, b);
        var bc = span(graph, b, c);
        var ac = span(graph, a, c);
        bc.damage(0);
        int damagedRefund = bc.refund();
        assertFalse(graph.canJoin(b, d));
        assertTrue(graph.remove(ab));
        assertFalse(graph.canJoin(b, d));
        assertTrue(graph.remove(ac));
        assertNull(b.source());
        assertTrue(graph.canJoin(b, d));
        span(graph, b, d);
        assertFalse(graph.canJoin(a, b));
        assertEquals(d.surface, c.source());

        LightData loaded = LightData.load(data.save(new CompoundTag()));
        var restored = loaded.graph.span(bc.id);
        assertEquals(bc.curve.bulbs(), restored.curve.bulbs());
        assertTrue(restored.broken(0));
        assertEquals(damagedRefund, restored.refund());
        assertEquals(d.surface, restored.a.source());
        restored.repair(0);
        assertEquals(damagedRefund + 1, restored.refund());
        assertEquals(a.surface, loaded.graph.box(a.surface).source());
        assertThrows(IllegalArgumentException.class, () -> LightData.load(new CompoundTag()));
    }

    @Test void ordinaryMountAndReplacementOnlyClaimTrulyUnpoweredComponents() {
        var graph = new LightGraph();
        var a = add(graph, 0);
        var b = add(graph, 8);
        var c = add(graph, 12);
        var ab = span(graph, a, b);
        var bc = span(graph, b, c);
        bc.damage(0);
        assertTrue(graph.attach(a.surface, a));
        graph.detach(a.surface);
        assertTrue(graph.attach(b.surface.above(), b));
        assertFalse(graph.attach(a.surface, a));
        assertEquals(b.surface.above(), c.source());
        graph.detach(b.surface.above());
        assertTrue(graph.attach(a.surface, a));
        assertSame(ab, graph.span(ab.id));
        assertTrue(graph.span(bc.id).broken(0));
    }

    @Test void middleChunkAndOverhangingHitCellsAreIndexedWithoutOccupancy() {
        var graph = new LightGraph();
        var a = add(graph, -1);
        var b = add(graph, 31);
        var span = span(graph, a, b);
        var dirty = new HashSet<Long>();
        var spatial = new LightSpatial(dirty::add);
        spatial.add(span);
        assertTrue(spatial.sections.get(ChunkPos.asLong(0, 0)).spans.contains(span));
        assertTrue(spatial.sections.get(ChunkPos.asLong(-1, 0)).spans.contains(span));
        assertTrue(spatial.sections.get(ChunkPos.asLong(1, 0)).spans.contains(span));
        for (var lamp : span.curve.bulbs()) {
            assertEquals(1, spatial.cell(lamp.cell()).size());
            assertFalse(spatial.hitCell(BlockPos.containing(lamp.glass().getCenter())).isEmpty());
        }
        spatial.remove(span);
        assertTrue(spatial.sections.isEmpty());
    }

    @Test void energyIsReceiveOnlyAndAnExactFractionalCostWithNoIdleLeak() {
        int[] changes = new int[1];
        var energy = new BoxEnergy(() -> changes[0]++);
        assertEquals(1000, energy.receiveEnergy(1001, true));
        assertEquals(0, energy.getEnergyStored());
        assertEquals(0, changes[0]);
        assertEquals(1000, energy.receiveEnergy(1001, false));
        assertFalse(energy.canExtract());
        assertEquals(0, energy.extractEnergy(50, false));
        for (int i = 0; i < 10; i++) assertTrue(energy.payTick(7, false));
        for (int i = 0; i < 10; i++) assertTrue(energy.payTick(13, false));
        assertEquals(990, energy.getEnergyStored());
        for (int i = 0; i < 100; i++) assertFalse(energy.payTick(0, false));
        assertEquals(990, energy.getEnergyStored());
        var saved = new BoxEnergy(() -> { });
        saved.load(energy.units());
        assertEquals(energy.units(), saved.units());
    }

    @Test void lackOfFundsDoesNotPartiallyChargeAndRecoveryIsReachable() {
        var energy = new BoxEnergy(() -> { });
        energy.receiveEnergy(2, false);
        assertFalse(energy.payTick(21, true));
        assertEquals(2, energy.getEnergyStored());
        energy.receiveEnergy(1, false);
        assertTrue(energy.payTick(21, true));
        assertEquals(39, energy.units());
        assertFalse(energy.payTick(40, false));
        assertEquals(39, energy.units());
        energy.receiveEnergy(1000, false);
        assertTrue(energy.payTick(19_990, true));
    }

    static LightGraph.Node add(LightGraph graph, int x) {
        var node = new LightGraph.Node(UUID.randomUUID(), new Vec3(x + 0.5, 4.5, 0.5),
                new BlockPos(x, 4, 0), Direction.NORTH, false);
        graph.add(node);
        return node;
    }

    static LightGraph.Span span(LightGraph graph, LightGraph.Node a, LightGraph.Node b) {
        var span = new LightGraph.Span(UUID.randomUUID(), a, b, new LightCurve(a.anchor, b.anchor), 0);
        graph.join(span);
        return span;
    }
}
