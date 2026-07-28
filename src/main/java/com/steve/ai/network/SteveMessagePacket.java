package com.steve.ai.network;

import com.steve.ai.client.ClientPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> client packet carrying a single Steve panel message.
 */
public class SteveMessagePacket {

    private final String steveName;
    private final String message;

    public SteveMessagePacket(String steveName, String message) {
        this.steveName = steveName;
        this.message = message;
    }

    public static void encode(SteveMessagePacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.steveName);
        buf.writeUtf(packet.message);
    }

    public static SteveMessagePacket decode(FriendlyByteBuf buf) {
        return new SteveMessagePacket(buf.readUtf(), buf.readUtf());
    }

    public static void handle(SteveMessagePacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() ->
            // Run the client-only handler safely; never classloads client code on a server.
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPacketHandler.handleSteveMessage(packet.steveName, packet.message)));
        context.setPacketHandled(true);
    }
}
