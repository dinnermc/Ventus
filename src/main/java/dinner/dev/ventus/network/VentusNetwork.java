package dinner.dev.ventus.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Network payloads shared by both sides.
 *
 * <p>The registrar is marked {@link PayloadRegistrar#optional() optional} so a client that does not
 * have Ventus installed simply drops the wind packets instead of refusing to join, and a server that
 * does not have Ventus never sends anything (the HUD then stays hidden for lack of data).
 *
 * <p>This class is safe to load on a dedicated server: it references no client class. The handler
 * only ever runs on the client, but {@link WindSyncState} — the class it touches — has no client
 * dependencies either.
 */
public final class VentusNetwork {
    private VentusNetwork() {
    }

    /** Registers the payloads. Fired on both logical sides, on the mod bus. */
    public static void register(final RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .optional()
                .playToClient(WindSyncPayload.TYPE, WindSyncPayload.CODEC,
                        (payload, context) -> WindSyncState.accept(payload));
    }
}
