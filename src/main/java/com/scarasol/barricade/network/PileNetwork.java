package com.scarasol.barricade.network;

import com.scarasol.barricade.Barricade;
import com.scarasol.barricade.pile.PileService;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;

/** Bounded transport; never accepts client-provided layout, states or furniture NBT. */
public final class PileNetwork {
    public static final int CHUNK_BYTES = 48 * 1024;
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final int MAX_NBT = 64 * 1024 * 1024;
    private static final class Channel {
        static final SimpleChannel VALUE = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(Barricade.MOD_ID, "recipes"), () -> "field5", "field5"::equals, "field5"::equals);
    }
    private static Consumer<Fragment> receiver = ignored -> { };
    private PileNetwork() { }
    public static void clientReceiver(Consumer<Fragment> consumer) { receiver = consumer; }
    public static void init() {
        Channel.VALUE.registerMessage(0, Fragment.class, Fragment::write, Fragment::read, (packet, context) -> {
            context.get().enqueueWork(() -> receiver.accept(packet));
            context.get().setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        Channel.VALUE.registerMessage(1, Resync.class, (packet, buffer) -> { }, buffer -> new Resync(), (packet, context) -> {
            ServerPlayer player = context.get().getSender();
            if (player != null) context.get().enqueueWork(() -> PileService.resync(player));
            context.get().setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }
    private record Resync() { }
    public static void requestResync() { Channel.VALUE.sendToServer(new Resync()); }
    public static void send(ServerPlayer player, Fragment fragment) {
        Channel.VALUE.send(PacketDistributor.PLAYER.with(() -> player), fragment);
    }
    public record Fragment(ResourceLocation dimension, UUID world, long transfer, int index, int total, byte[] bytes) {
        public Fragment {
            if (total <= 0 || total > (MAX_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES
                    || index < 0 || index >= total || bytes.length > CHUNK_BYTES)
                throw new IllegalArgumentException("Invalid barricade transfer");
            bytes = bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
        void write(FriendlyByteBuf buffer) {
            buffer.writeResourceLocation(dimension);
            buffer.writeUUID(world);
            buffer.writeLong(transfer);
            buffer.writeVarInt(index);
            buffer.writeVarInt(total);
            buffer.writeByteArray(bytes);
        }
        static Fragment read(FriendlyByteBuf buffer) {
            return new Fragment(buffer.readResourceLocation(), buffer.readUUID(), buffer.readLong(),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readByteArray(CHUNK_BYTES));
        }
    }
    public static List<Fragment> fragment(ResourceLocation dimension, UUID world, long transfer, CompoundTag payload) {
        try {
            if (payload.sizeInBytes() > MAX_NBT) throw new IllegalArgumentException("Metadata exceeds NBT budget");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            NbtIo.writeCompressed(payload, output);
            byte[] bytes = output.toByteArray();
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Metadata exceeds compressed budget");
            int count = (bytes.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
            List<Fragment> result = new ArrayList<>();
            for (int index = 0; index < count; index++) result.add(new Fragment(dimension, world, transfer,
                    index, count, Arrays.copyOfRange(bytes, index * CHUNK_BYTES, Math.min(bytes.length, (index + 1) * CHUNK_BYTES))));
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode barricade metadata", exception);
        }
    }
    public static final class Assembly {
        private Fragment header;
        private byte[][] parts;
        private int received, bytes;
        private long completed = -1;
        public CompoundTag accept(Fragment packet) {
            if (packet.transfer <= completed) return null;
            if (header == null || packet.transfer > header.transfer) {
                header = packet;
                parts = new byte[packet.total][];
                received = bytes = 0;
            }
            if (packet.transfer != header.transfer) return null;
            if (!header.world.equals(packet.world) || !header.dimension.equals(packet.dimension) || packet.total != parts.length)
                throw new IllegalArgumentException("Inconsistent barricade transfer");
            if (parts[packet.index] != null) {
                if (!Arrays.equals(parts[packet.index], packet.bytes)) throw new IllegalArgumentException("Conflicting fragment");
                return null;
            }
            parts[packet.index] = packet.bytes;
            bytes += packet.bytes.length;
            if (bytes > MAX_BYTES) throw new IllegalArgumentException("Transfer exceeds memory budget");
            if (++received != parts.length) return null;
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream(bytes);
                for (byte[] part : parts) output.write(part);
                try (DataInputStream input = new DataInputStream(new GZIPInputStream(
                        new ByteArrayInputStream(output.toByteArray())))) {
                    CompoundTag result = NbtIo.read(input, new NbtAccounter(MAX_NBT));
                    completed = packet.transfer;
                    parts = null;
                    header = null;
                    return result;
                }
            } catch (IOException exception) {
                throw new IllegalArgumentException("Invalid barricade metadata", exception);
            }
        }
    }
}
