package dinner.dev.ventus;

import dinner.dev.ventus.network.WindSyncPayload;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3d;

/**
 * Sends each player the wind at their own eye position so the client HUD has something to draw. The
 * value comes straight from {@link WindField#sampleAt}, i.e. the same field the physics hooks use,
 * evaluated at the player rather than at a sub-level (there is no height fade-in or temporal ramp here
 * — the HUD shows the steady state).
 *
 * <p>The client cannot evaluate this field itself: the noise is seeded from the world seed, which only
 * the server has. So the value is pushed, and it is pushed sparingly — a player who is standing still
 * barely changes their wind, so the packet is held until it has moved by more than
 * {@link #MIN_DELTA} m/s, and never faster than {@link #SEND_INTERVAL_TICKS} ticks (5 Hz) either way.
 *
 * <p>Runs on the server thread from {@code ServerTickEvent.Post}. A player without the mod simply drops
 * the payload on arrival, because it is registered as optional.
 */
public final class WindHudSync {
    private WindHudSync() {
    }

    /** Five updates a second is plenty for an instrument needle. */
    private static final int SEND_INTERVAL_TICKS = 4;
    /** Ignore movement finer than this (m/s) so a gusting player is not sent a packet every tick. */
    private static final double MIN_DELTA = 0.05;

    private static final Vector3d SAMPLE_POINT = new Vector3d();
    private static final Vector3d RESULT = new Vector3d();
    private static final Map<UUID, Sent> LAST_SENT = new HashMap<>();

    /** The last wind a player was sent, for the change check. */
    private record Sent(float x, float y, float z) {
        boolean closeTo(final double otherX, final double otherY, final double otherZ) {
            final double dx = x - otherX;
            final double dy = y - otherY;
            final double dz = z - otherZ;
            return dx * dx + dy * dy + dz * dz < MIN_DELTA * MIN_DELTA;
        }
    }

    private static int tickCounter;

    public static void tick(final ServerTickEvent.Post event) {
        if (++tickCounter % SEND_INTERVAL_TICKS != 0) {
            return;
        }

        for (final ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            final Vec3 eye = player.getEyePosition();
            SAMPLE_POINT.set(eye.x, eye.y, eye.z);
            WindField.sampleAt(player.serverLevel(), SAMPLE_POINT, RESULT);

            final Sent previous = LAST_SENT.get(player.getUUID());
            if (previous != null && previous.closeTo(RESULT.x, RESULT.y, RESULT.z)) {
                continue;
            }

            final Sent current = new Sent((float) RESULT.x, (float) RESULT.y, (float) RESULT.z);
            LAST_SENT.put(player.getUUID(), current);
            PacketDistributor.sendToPlayer(player, new WindSyncPayload(current.x(), current.y(), current.z()));
        }
    }

    /** Drops the remembered value for a leaving player so the map does not grow with join count. */
    public static void forget(final Player player) {
        LAST_SENT.remove(player.getUUID());
    }
}
