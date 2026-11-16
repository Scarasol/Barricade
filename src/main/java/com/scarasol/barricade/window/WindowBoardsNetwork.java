package com.scarasol.barricade.window;

import com.scarasol.barricade.Barricade;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class WindowBoardsNetwork {
    private static final String PROTOCOL = "window_boards_1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Barricade.MOD_ID, "window_boards"), () -> PROTOCOL,
            PROTOCOL::equals, PROTOCOL::equals);
    private static Consumer<Packet> receiver = ignored -> { };
    private WindowBoardsNetwork() { }
    public static void initialize() {
        CHANNEL.registerMessage(0, Packet.class, Packet::write, Packet::read, (packet, context) -> {
            context.get().enqueueWork(() -> receiver.accept(packet));
            context.get().setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    public static void receiver(Consumer<Packet> callback) { receiver = callback; }
    public static void send(ServerPlayer player, Packet packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
    /** The sender transfers ownership of freshly serialized metadata; receivers only read it. */
    public record Packet(ResourceLocation dimension, UUID world, boolean reset, UUID id, CompoundTag group) {
        void write(FriendlyByteBuf buffer) {
            buffer.writeResourceLocation(dimension);
            buffer.writeUUID(world);
            buffer.writeBoolean(reset);
            buffer.writeUUID(id);
            buffer.writeNbt(group);
        }
        static Packet read(FriendlyByteBuf buffer) {
            ResourceLocation dimension = buffer.readResourceLocation();
            UUID world = buffer.readUUID();
            boolean reset = buffer.readBoolean();
            UUID id = buffer.readUUID();
            CompoundTag group = buffer.readNbt();
            if (group == null) throw new IllegalArgumentException("Missing window-board packet data");
            return new Packet(dimension, world, reset, id, group);
        }
    }
}
