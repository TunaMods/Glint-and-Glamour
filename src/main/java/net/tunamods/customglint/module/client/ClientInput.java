package net.tunamods.customglint.module.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;

/**
 * Client-only input queries. Kept out of server-reachable item classes so they never hard-reference
 * {@link Minecraft} / {@link InputConstants} in their bytecode. Only reached from client-only screens
 * and tooltip rendering, so this class (and its client imports) never loads on a dedicated server.
 *
 * <p>{@code Screen.hasShiftDown()} is gone; query the keyboard state directly.
 */
public final class ClientInput {
    private ClientInput() {}

    public static boolean hasShiftDown() {
        return InputConstants.isKeyDown(InputConstants.KEY_LSHIFT)
            || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
    }
}
