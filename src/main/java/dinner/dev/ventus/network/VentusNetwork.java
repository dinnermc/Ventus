package dinner.dev.ventus.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers the wind payload as {@link PayloadRegistrar#optional() optional}, so a client
 * without Ventus drops it instead of refusing to join. References no client class; safe on a
 * dedicated server.
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
