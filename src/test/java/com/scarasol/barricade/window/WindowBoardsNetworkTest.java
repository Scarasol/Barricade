package com.scarasol.barricade.window;

import static org.junit.jupiter.api.Assertions.*;
import io.netty.buffer.Unpooled;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class WindowBoardsNetworkTest {
    @Test void resetDeleteAndBothMaterialPacketsKeepTheExistingWireFormat() {
        UUID world = new UUID(1, 2), id = new UUID(3, 4);
        var dimension = new ResourceLocation("minecraft", "overworld");
        var shape = BoardLayoutTest.rectangle(-17, -4, 3, 3);
        for (SealMaterial material : SealMaterial.values()) {
            BoardGroup group = new BoardGroup(id, 5, 42, new BoardPlane(Direction.DOWN, 64), shape,
                    Set.of(new BoardCell(-16, -3)), BoardLayoutTest.supports(shape), null, material);
            for (boolean reset : new boolean[]{true, false}) for (CompoundTag metadata :
                    new CompoundTag[]{new CompoundTag(), group.save()}) {
                var original = new WindowBoardsNetwork.Packet(dimension, world, reset, id, metadata);
                assertSame(metadata, original.group());
                FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    original.write(buffer);
                    assertTrue(buffer.readableBytes() < 128 * 1024);
                    var decoded = WindowBoardsNetwork.Packet.read(buffer);
                    assertEquals(dimension, decoded.dimension());
                    assertEquals(world, decoded.world());
                    assertEquals(id, decoded.id());
                    assertEquals(reset, decoded.reset());
                    assertEquals(metadata, decoded.group());
                    assertSame(decoded.group(), decoded.group());
                    assertEquals(0, buffer.readableBytes());
                } finally { buffer.release(); }
            }
        }
    }
    @Test void ownedMetadataIsNeitherRecursivelyMeasuredNorCopied() {
        CompoundTag metadata = new CompoundTag() {
            @Override public int sizeInBytes() { throw new AssertionError("Redundant metadata traversal"); }
            @Override public CompoundTag copy() { throw new AssertionError("Redundant metadata copy"); }
        };
        var packet = new WindowBoardsNetwork.Packet(new ResourceLocation("minecraft", "overworld"),
                new UUID(1, 2), false, new UUID(3, 4), metadata);
        assertSame(metadata, packet.group());
        assertEquals(Set.of(packet.id()), new WindowBoardsUpdates().accept(packet).removed());
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.write(buffer);
            assertEquals(metadata, WindowBoardsNetwork.Packet.read(buffer).group());
        } finally { buffer.release(); }
    }
}
