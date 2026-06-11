package com.steve.ai.network;

import com.steve.ai.SteveMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Minimal networking layer for Steve.
 *
 * <p>The agent logic (and therefore all of its user-facing feedback like "Thinking..."
 * or error messages) runs on the server side, but the side panel that shows those
 * messages is a client-only GUI. This channel carries those messages from server to
 * client so the panel can actually display them.</p>
 */
public final class SteveNetwork {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
        .named(new ResourceLocation(SteveMod.MODID, "main"))
        .clientAcceptedVersions(PROTOCOL_VERSION::equals)
        .serverAcceptedVersions(PROTOCOL_VERSION::equals)
        .networkProtocolVersion(() -> PROTOCOL_VERSION)
        .simpleChannel();

    private SteveNetwork() {
    }

    /**
     * Registers all packets. Must be called once during common setup.
     */
    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++,
            SteveMessagePacket.class,
            SteveMessagePacket::encode,
            SteveMessagePacket::decode,
            SteveMessagePacket::handle);

        SteveMod.LOGGER.info("Steve network channel registered");
    }

    /**
     * Sends a Steve message to all connected clients (singleplayer or server).
     *
     * @param steveName Name of the Steve the message is from
     * @param message   The message text to show in the panel
     */
    public static void sendMessageToAll(String steveName, String message) {
        CHANNEL.send(PacketDistributor.ALL.noArg(), new SteveMessagePacket(steveName, message));
    }
}
