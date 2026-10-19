package dinner.dev.ventus.network;

/**
 * The last wind vector the server sent for the local player, kept as plain fields. Has no
 * Minecraft client types, so the network handler can reference it on a dedicated server too.
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
