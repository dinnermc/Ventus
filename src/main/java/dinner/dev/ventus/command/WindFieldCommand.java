package dinner.dev.ventus.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dinner.dev.ventus.WindCarrier;
import dinner.dev.ventus.WindField;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.Locale;

/**
 * The {@code /windfield} command.
 * <p>
 * {@code /windfield info} reports, for the Sable sub-level the executing player is standing on
 * (or riding): the wind sampled for that sub-level, the sub-level's material velocity at the
 * player's position, and the air-relative velocity that Sable's lift &amp; drag actually sees. It
 * also reports whether the wind is non-zero ({@code windActive}) plus the dimension's drag
 * coefficient and the sub-level's mass, so a stalled test can be diagnosed from the output alone.
 * <p>
 * {@code /windfield info <pos>} does the same for the sub-level containing {@code pos}. This variant
 * needs no player, so it is usable from a dedicated server console or RCON.
 * <p>
 * When there is no sub-level at the sampled position the command still reports the field itself —
 * base, field, gust, height factor, slope, thermal and orographic — because those are properties of
 * the position. That makes it usable to walk or fly around sampling biomes and slopes without being
 * aboard anything.
 */
public final class WindFieldCommand {
    private WindFieldCommand() {
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("windfield")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("info")
                        .executes(context -> infoPlayer(context.getSource()))
                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                .executes(context -> infoPosition(context.getSource(), Vec3Argument.getVec3(context, "pos"))))));
    }

    private static int infoPlayer(final CommandSourceStack source) {
        final ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (final Exception e) {
            source.sendFailure(Component.literal("[Ventus] /windfield info must be run by a player. From a console, use /windfield info <pos>."));
            return 0;
        }

        final Vec3 playerPos = player.position();
        final Vector3d worldPos = new Vector3d(playerPos.x, playerPos.y, playerPos.z);

        final SubLevel tracking = Sable.HELPER.getTrackingOrVehicleSubLevel(player);
        final SubLevel subLevel = tracking != null ? tracking : Sable.HELPER.getContaining(player);

        return report(source, player.serverLevel(), worldPos, subLevel);
    }

    private static int infoPosition(final CommandSourceStack source, final Vec3 pos) {
        final ServerLevel level = source.getLevel();
        final Vector3d worldPos = new Vector3d(pos.x, pos.y, pos.z);
        final SubLevel subLevel = Sable.HELPER.getContaining(level, worldPos);
        return report(source, level, worldPos, subLevel);
    }

    private static int report(final CommandSourceStack source, final ServerLevel level, final Vector3d worldPos, final SubLevel subLevel) {
        // The steady-state breakdown of the field at the sample point (base / field / height / total).
        final WindField.Breakdown parts = WindField.breakdown(level, worldPos, new WindField.Breakdown());

        if (subLevel == null) {
            // The field itself is a property of the position, not of a sub-level, so it is still
            // worth reporting. This makes the command usable to walk or fly around sampling biomes
            // and slopes without needing to be aboard anything.
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "[Ventus] (no sub-level at the sampled position)%n%s", fieldLines(parts, worldPos.y()))), false);
            return 1;
        }

        // The wind Sable actually sees for this sub-level: the per-tick sample stored on the
        // sub-level, or a fresh sample if the sub-level is not (yet) a WindCarrier.
        final Vector3d wind;
        if (subLevel instanceof final WindCarrier carrier) {
            wind = new Vector3d(carrier.ventus$wind());
        } else {
            wind = WindField.sampleAt(level, worldPos, new Vector3d());
        }
        final boolean windActive = wind.lengthSquared() != 0.0;

        // The two inputs the d*w impulse needs: a positive drag coefficient and a positive mass.
        final double universalDrag = DimensionPhysicsData.getUniversalDrag(level);
        Double massValue = null;
        if (subLevel instanceof final ServerSubLevel serverSubLevel) {
            final MassData massData = serverSubLevel.getMassTracker();
            if (massData != null) {
                massValue = massData.getMass();
            }
        }
        final String massText = massValue == null ? "unknown" : String.format(Locale.ROOT, "%.3f", massValue);

        // getVelocity() expects the sample point in the sub-level's local space.
        final Vector3d localPos = subLevel.logicalPose().transformPositionInverse(new Vector3d(worldPos));
        final Vector3d subLevelVelocity = Sable.HELPER.getVelocity(level, subLevel, localPos, new Vector3d());
        // Respects the kill switch: reports what lift & drag actually see, not the raw field.
        final Vector3d relativeVelocity = new Vector3d(WindField.airRelative(subLevelVelocity, wind));

        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "[Ventus] sub-level=%s%n"
                        + "  windActive=%s  wind=(%.3f, %.3f, %.3f)%n"
                        + "%s%n"
                        + "  universalDrag=%.3f  mass=%s%n"
                        + "  subLevelVel=(%.3f, %.3f, %.3f)%n"
                        + "  air-relative=(%.3f, %.3f, %.3f)",
                subLevel.getUniqueId(),
                windActive, wind.x, wind.y, wind.z,
                fieldLines(parts, worldPos.y()),
                universalDrag, massText,
                subLevelVelocity.x, subLevelVelocity.y, subLevelVelocity.z,
                relativeVelocity.x, relativeVelocity.y, relativeVelocity.z)), false);
        return 1;
    }

    /** The position-dependent part of the report, shared by the with- and without-sub-level paths. */
    private static String fieldLines(final WindField.Breakdown parts, final double altitude) {
        return String.format(Locale.ROOT,
                "  heightFactor=%.3f  weather=%.2f  capped=%s%n"
                        + "  y=%.1f  jet=%.2fx  shear=%.2fx  turn=%.1f deg%n"
                        + "  slope=%s  leeBoost=%.2f%n"
                        + "  thermal=%.3f  orographic=%.3f%n"
                        + "  base=(%.3f, %.3f, %.3f)%n"
                        + "  field=(%.3f, %.3f, %.3f)%n"
                        + "  gust=(%.3f, %.3f, %.3f)%n"
                        + "  total=(%.3f, %.3f, %.3f)",
                parts.heightFactor, parts.weather, parts.capped,
                altitude, parts.jet, parts.shear, parts.shearDegrees,
                Double.isNaN(parts.slope) ? "n/a" : String.format(Locale.ROOT, "%.3f", parts.slope),
                parts.leeBoost,
                parts.thermal, parts.orographic,
                parts.base.x, parts.base.y, parts.base.z,
                parts.field.x, parts.field.y, parts.field.z,
                parts.gust.x, parts.gust.y, parts.gust.z,
                parts.total.x, parts.total.y, parts.total.z);
    }
}
