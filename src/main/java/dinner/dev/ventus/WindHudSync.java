package dinner.dev.ventus;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
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
 * Sends each player the wind for the client HUD: the vector of the sub-level they are standing
 * on, riding or tracking ({@link WindCarrier#ventus$wind()}), or the field at their eye
 * position when they are in the world. Pushed at most every {@link #SEND_INTERVAL_TICKS} ticks
 * and only when the wind moved by more than {@link #MIN_DELTA}, because the client cannot
 * evaluate the field itself — the noise is seeded from the world seed. A player without the mod
 * drops the payload; the registrar is optional.
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
            final SubLevel subLevel = subLevelFor(player);
            final Vector3d wind;
            if (subLevel instanceof final WindCarrier carrier) {
                // aboard something: the air the vehicle is in, already sampled for it this tick
                wind = carrier.ventus$wind();
            } else {
                final Vec3 eye = player.getEyePosition();
                SAMPLE_POINT.set(eye.x, eye.y, eye.z);
                wind = WindField.sampleAt(player.serverLevel(), SAMPLE_POINT, RESULT);
            }

            final Sent previous = LAST_SENT.get(player.getUUID());
            if (previous != null && previous.closeTo(wind.x, wind.y, wind.z)) {
                continue;
            }

            final Sent current = new Sent((float) wind.x, (float) wind.y, (float) wind.z);
            LAST_SENT.put(player.getUUID(), current);
            PacketDistributor.sendToPlayer(player, new WindSyncPayload(current.x(), current.y(), current.z()));
        }
    }

    /**
     * The sub-level this player is standing in, riding, or tracking — the same lookup
     * {@code /windfield info} uses, so the instrument and the command can never disagree about
     * which sub-level is meant.
     */
    private static SubLevel subLevelFor(final ServerPlayer player) {
        final SubLevel tracking = Sable.HELPER.getTrackingOrVehicleSubLevel(player);
        return tracking != null ? tracking : Sable.HELPER.getContaining(player);
    }

    /**
     * The wind this player's HUD was last sent, or {@code null} if nothing has been sent yet.
     * Public so {@code /windfield info} can show it next to the wind the sub-level has, and the two
     * can be compared.
     */
    public static Vec3 lastSentTo(final Player player) {
        final Sent sent = LAST_SENT.get(player.getUUID());
        return sent == null ? null : new Vec3(sent.x, sent.y, sent.z);
    }

    /** Drops the remembered value for a leaving player so the map does not grow with join count. */
    public static void forget(final Player player) {
        LAST_SENT.remove(player.getUUID());
    }
}
