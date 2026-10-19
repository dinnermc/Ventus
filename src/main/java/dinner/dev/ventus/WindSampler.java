package dinner.dev.ventus;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Samples the wind for every Sable sub-level once per game tick, storing it in the sub-level's
 * {@link WindCarrier}; the physics hooks then just read the stored vector. Runs on the server
 * thread, like the hooks, so the mutable vector needs no synchronisation.
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
