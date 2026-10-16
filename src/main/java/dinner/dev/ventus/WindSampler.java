package dinner.dev.ventus;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Samples the wind for every Sable sub-level once per game tick, storing it in the sub-level's
 * {@link WindCarrier}.
 * <p>
 * Sampling once per tick (instead of per physics sub-step) keeps the height field lookup out of the
 * hot path and gives a stable wind for the whole tick. The physics hooks in
 * {@code ServerSubLevelWindMixin} then just read the stored vector.
 * <p>
 * <b>Threading.</b> Both this listener ({@link ServerTickEvent.Pre}) and the hooks run on the server
 * thread: Sable ticks its physics from {@code ServerLevel.tick} via {@code SubLevelContainer.tick},
 * which is the server thread, and Sable has no physics worker threads. So the mutable vector needs
 * no synchronisation. {@code -Pventus.debugWind=true} logs this thread once, and the hook logs its
 * own once, so the two can be compared at runtime.
 */
public final class WindSampler {
    private WindSampler() {
    }

    private static final boolean DEBUG_WIND = Boolean.getBoolean("ventus.debugWind");
    private static boolean ventus$threadLogged;

    public static void tick(final ServerTickEvent.Pre event) {
        if (WindField.isCalm()) {
            return;
        }

        if (DEBUG_WIND && !ventus$threadLogged) {
            ventus$threadLogged = true;
            Ventus.LOGGER.info("[ventus] sampler thread={}", Thread.currentThread().getName());
        }

        final MinecraftServer server = event.getServer();
        for (final ServerLevel level : server.getAllLevels()) {
            final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                continue;
            }

            for (final ServerSubLevel subLevel : container.getAllSubLevels()) {
                if (subLevel instanceof final WindCarrier carrier) {
                    WindField.sample(level, subLevel.logicalPose().position(), carrier);
                }
            }
        }
    }
}
