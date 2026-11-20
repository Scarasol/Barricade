package com.scarasol.barricade.stringlights;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class LightNetwork {
    private static final String PROTOCOL = "string_lights_1";
    static final int PAGE_ENTRIES = 128;
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(StringLights.id("string_lights"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static Consumer<ViewPacket> views = ignored -> { };
    private static Consumer<Selection> selections = ignored -> { };
    private LightNetwork() { }

    static void initialize() {
        CHANNEL.registerMessage(0, ViewPacket.class, ViewPacket::write, ViewPacket::read, (packet, ctx) -> {
            ctx.get().enqueueWork(() -> views.accept(packet));
            ctx.get().setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, Selection.class, Selection::write, Selection::read, (packet, ctx) -> {
            ctx.get().enqueueWork(() -> selections.accept(packet));
            ctx.get().setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void client(Consumer<ViewPacket> views, Consumer<Selection> selections) {
        LightNetwork.views = views;
        LightNetwork.selections = selections;
    }

    static void send(ServerPlayer player, ServerLevel level, long chunk, CompoundTag snapshot) {
        var target = PacketDistributor.PLAYER.with(() -> player);
        var dimension = level.dimension().location();
        if (snapshot == null) {
            CHANNEL.send(target, new ViewPacket(dimension, chunk, 0, 1, null));
            return;
        }
        ListTag nodes = snapshot.getList("nodes", Tag.TAG_COMPOUND);
        ListTag spans = snapshot.getList("spans", Tag.TAG_COMPOUND);
        ListTag lights = snapshot.getList("lights", Tag.TAG_COMPOUND);
        int max = Math.max(nodes.size(), Math.max(spans.size(), lights.size()));
        int pages = Math.max(1, (max + PAGE_ENTRIES - 1) / PAGE_ENTRIES);
        for (int i = 0; i < pages; i++) {
            CompoundTag part = snapshot;
            if (pages > 1) {
                part = new CompoundTag();
                if (snapshot.contains("lights")) {
                    part.put("lights", page(lights, i));
                } else {
                    part.put("nodes", page(nodes, i));
                    part.put("spans", page(spans, i));
                }
            }
            CHANNEL.send(target, new ViewPacket(dimension, chunk, i, pages, part));
        }
    }

    private static ListTag page(ListTag from, int part) {
        int end = Math.min(from.size(), (part + 1) * PAGE_ENTRIES);
        ListTag result = new ListTag();
        for (int i = part * PAGE_ENTRIES; i < end; i++) result.add(from.get(i));
        return result;
    }

    static void select(ServerPlayer player, UUID node) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new Selection(player.level().dimension().location(), node));
    }

    public record ViewPacket(ResourceLocation dimension, long chunk, int part, int parts, CompoundTag snapshot) {
        void write(FriendlyByteBuf buf) {
            buf.writeResourceLocation(dimension);
            buf.writeLong(chunk);
            buf.writeVarInt(part);
            buf.writeVarInt(parts);
            buf.writeNbt(snapshot);
        }
        static ViewPacket read(FriendlyByteBuf buf) {
            return new ViewPacket(buf.readResourceLocation(), buf.readLong(), buf.readVarInt(), buf.readVarInt(), buf.readNbt());
        }
    }

    public record Selection(ResourceLocation dimension, UUID node) {
        void write(FriendlyByteBuf buf) {
            buf.writeResourceLocation(dimension);
            buf.writeBoolean(node != null);
            if (node != null) buf.writeUUID(node);
        }
        static Selection read(FriendlyByteBuf buf) {
            return new Selection(buf.readResourceLocation(), buf.readBoolean() ? buf.readUUID() : null);
        }
    }

}
