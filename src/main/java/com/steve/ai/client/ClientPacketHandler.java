package com.steve.ai.client;

/**
 * Client-only handlers for packets received from the server.
 *
 * <p>Kept in its own class so it is only ever classloaded on the physical client
 * (referenced via {@code DistExecutor}), never on a dedicated server.</p>
 */
public final class ClientPacketHandler {

    private ClientPacketHandler() {
    }

    public static void handleSteveMessage(String steveName, String message) {
        SteveGUI.addSteveMessage(steveName, message);
    }
}
