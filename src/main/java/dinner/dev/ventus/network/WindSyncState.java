package dinner.dev.ventus.network;

/**
 * The last wind vector the server sent for the local player, kept as plain fields.
 *
 * <p>This class has no Minecraft <em>client</em> types so it can be referenced
 * from the network handler, which NeoForge also instantiates on a dedicated server. In practice it is
 * only ever written by the client-side {@code playToClient} handler and only ever read by the client
 * HUD.
 */
public final class WindSyncState {
    private WindSyncState() {
    }

    private static boolean valid;
    private static float x;
    private static float y;
    private static float z;

    /** Records a fresh sample from the server. Called on the main thread. */
    public static void accept(final WindSyncPayload payload) {
        x = payload.x();
        y = payload.y();
        z = payload.z();
        valid = true;
    }

    /** Whether any wind has been received from the server yet. */
    public static boolean isValid() {
        return valid;
    }

    /** Forgets the last sample (used when the client leaves a world). */
    public static void invalidate() {
        valid = false;
    }

    public static float x() {
        return x;
    }

    public static float y() {
        return y;
    }

    public static float z() {
        return z;
    }
}
