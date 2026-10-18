package dinner.dev.ventus.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dinner.dev.ventus.VentusConfig;
import dinner.dev.ventus.WindCarrier;
import dinner.dev.ventus.WindField;
import dinner.dev.ventus.WindHudSync;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Coordinates;
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
 * {@code /windfield stats [radius] [step]} instead sweeps a horizontal grid around the player and
 * reports how often the wind points against the configured base wind, plus how often the perturbation
 * outweighs it. That is the measurement behind the storm-reversal claim in the README, and it can only
 * be made in the world, on the real seed and the real rain/thunder levels.
 * <p>
 * When there is no sub-level at the sampled position the command still reports the field itself —
 * base, field, gust, height factor, slope, thermal and orographic — because those are properties of
 * the position. That makes it usable to walk or fly around sampling biomes and slopes without being
 * aboard anything. Both column heights are reported with them ({@code groundHeight} with leaves
 * excluded, {@code floorHeight} with leaves included), so a forest edge can be told apart from a
 * hill without working the canopy height back out of the other numbers.
 * <p>
 * {@code /windfield set} forces this dimension's <em>base</em> wind to a vector for the running
 * session, and {@code /windfield storm} sets its weather. Neither is written to the config, so both
 * die with the server; together they let an admin (or a video shoot) dial in a known wind without
 * editing a file or waiting for the weather to turn.
 */
public final class WindFieldCommand {
    private WindFieldCommand() {
    }

    /**
     * Hard ceiling on the samples one {@code stats} sweep may take. Every sample runs the full field
     * (several noise evaluations plus config reads) on the <b>server thread</b>, so an unbounded
     * radius/step pair would lock the game rather than just take a while: the argument ranges allow
     * up to 4 million points. Rejected before the loop starts, with the count in the message.
     */
    private static final long MAX_SAMPLES = 65_000L;

    /** The three weather presets {@code /windfield storm} can ask for. */
    private enum Weather {
        CLEAR,
        RAIN,
        STORM
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        // /windfield stats [radius] [step] - centred on the executing player.
        final LiteralArgumentBuilder<CommandSourceStack> stats = Commands.literal("stats")
                .executes(context -> statsAtPlayer(context.getSource(), 4096, 64))
                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 4096))
                        .executes(context -> statsAtPlayer(context.getSource(),
                                IntegerArgumentType.getInteger(context, "radius"), 16))
                        .then(Commands.argument("step", IntegerArgumentType.integer(4, 1024))
                                .executes(context -> statsAtPlayer(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "radius"),
                                        IntegerArgumentType.getInteger(context, "step")))));

        // /windfield stats at <pos> [radius] [step] - centred on a point, so a console or an RCON
        // client can take the measurement with no player online.
        final RequiredArgumentBuilder<CommandSourceStack, Coordinates> atPosition =
                Commands.argument("pos", Vec3Argument.vec3())
                        .executes(context -> stats(context.getSource(),
                                centerOf(Vec3Argument.getVec3(context, "pos")), 4096, 64))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(16, 4096))
                                .executes(context -> stats(context.getSource(),
                                        centerOf(Vec3Argument.getVec3(context, "pos")),
                                        IntegerArgumentType.getInteger(context, "radius"), 16))
                                .then(Commands.argument("step", IntegerArgumentType.integer(4, 1024))
                                        .executes(context -> stats(context.getSource(),
                                                centerOf(Vec3Argument.getVec3(context, "pos")),
                                                IntegerArgumentType.getInteger(context, "radius"),
                                                IntegerArgumentType.getInteger(context, "step")))));

        // /windfield set [clear | <wind>] - force this dimension's base wind, for this session only.
        // Bare, it reports what is in force, so nobody has to remember whether they set one.
        final LiteralArgumentBuilder<CommandSourceStack> set = Commands.literal("set")
                .executes(context -> setReport(context.getSource()))
                .then(Commands.literal("clear")
                        .executes(context -> setClear(context.getSource())))
                .then(Commands.argument("wind", Vec3Argument.vec3(false))
                        .executes(context -> setWind(context.getSource(),
                                Vec3Argument.getVec3(context, "wind"))));

        // /windfield storm [clear | rain] - bare means a full storm, which is the one people want.
        final LiteralArgumentBuilder<CommandSourceStack> storm = Commands.literal("storm")
                .executes(context -> storm(context.getSource(), Weather.STORM))
                .then(Commands.literal("clear")
                        .executes(context -> storm(context.getSource(), Weather.CLEAR)))
                .then(Commands.literal("rain")
                        .executes(context -> storm(context.getSource(), Weather.RAIN)));

        dispatcher.register(Commands.literal("windfield")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("info")
                        .executes(context -> infoPlayer(context.getSource()))
                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                .executes(context -> infoPosition(context.getSource(),
                                        Vec3Argument.getVec3(context, "pos")))))
                .then(stats.then(Commands.literal("at").then(atPosition)))
                .then(set)
                .then(storm));
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

        final int result = report(source, player.serverLevel(), worldPos, subLevel);

        // What the client HUD is actually being fed, for comparison with the wind above. If the two
        // disagree while aboard, the HUD is sampling somewhere other than the vehicle.
        final Vec3 eye = player.getEyePosition();
        final Vec3 hudWind = WindHudSync.lastSentTo(player);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "[Ventus] hud level=%s  hudEye=(%.1f, %.1f, %.1f)%n"
                        + "  hudWind=%s",
                player.serverLevel().dimension().location(), eye.x, eye.y, eye.z,
                hudWind == null
                        ? "not sent yet"
                        : String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)",
                                hudWind.x, hudWind.y, hudWind.z))), false);
        return result;
    }

    private static int infoPosition(final CommandSourceStack source, final Vec3 pos) {
        final ServerLevel level = source.getLevel();
        final Vector3d worldPos = new Vector3d(pos.x, pos.y, pos.z);
        final SubLevel subLevel = Sable.HELPER.getContaining(level, worldPos);
        return report(source, level, worldPos, subLevel);
    }

    /** {@code /windfield set} — reports the override in force for the executing dimension. */
    private static int setReport(final CommandSourceStack source) {
        final ServerLevel level = source.getLevel();
        final Vector3d override = WindField.baseOverride(level.dimension());

        if (override != null) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "[Ventus] %s base wind is forced to (%.3f, %.3f, %.3f) for this session."
                            + " /windfield set clear puts the config back in charge.",
                    level.dimension().location(), override.x, override.y, override.z)), false);
            return 1;
        }

        final Vector3d configured = new Vector3d();
        VentusConfig.baseWind(level.dimension(), configured);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "[Ventus] %s has no override; its base wind comes from ventus-server.toml:"
                        + " (%.3f, %.3f, %.3f)",
                level.dimension().location(), configured.x, configured.y, configured.z)), false);
        return 1;
    }

    /**
     * {@code /windfield set <wind>} — forces this dimension's <em>base</em> wind to the vector.
     * <p>
     * The vector argument is {@code vec3(false)}, not the default {@code vec3()}: the default snaps
     * whole numbers to the centre of that block (+0.5 on X and Z), which is right for a position and
     * wrong for a velocity — {@code 10 0 0} has to mean ten metres a second east, not 10.5.
     * <p>
     * Only the configured {@code [dimensions]} entry is replaced: the pressure field, the gusts, the
     * weather scaling and the whole altitude profile are still applied on top, so an override reads
     * like a real wind rather than a fixed vector bolted into the physics. Everything that samples
     * the base wind goes through {@link WindField#baseWind}, so the command, the HUD and the
     * sub-levels cannot disagree about it.
     */
    private static int setWind(final CommandSourceStack source, final Vec3 wind) {
        final ServerLevel level = source.getLevel();
        WindField.setBaseOverride(level.dimension(), new Vector3d(wind.x, wind.y, wind.z));

        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "[Ventus] %s base wind forced to (%.3f, %.3f, %.3f) for this session.%s The pressure"
                        + " field, gusts, weather and altitude profile still apply on top of it.",
                level.dimension().location(), wind.x, wind.y, wind.z,
                wind.y == 0.0
                        ? ""
                        : " Note: the configured base winds are horizontal; a vertical component is"
                                + " kept as it is, not discarded.")), false);
        return 1;
    }

    private static int setClear(final CommandSourceStack source) {
        final ServerLevel level = source.getLevel();
        if (!WindField.clearBaseOverride(level.dimension())) {
            source.sendFailure(Component.literal(String.format(Locale.ROOT,
                    "[Ventus] %s had no override to clear.", level.dimension().location())));
            return 0;
        }

        final Vector3d configured = new Vector3d();
        VentusConfig.baseWind(level.dimension(), configured);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "[Ventus] override dropped; %s is back to the configured base wind"
                        + " (%.3f, %.3f, %.3f).",
                level.dimension().location(), configured.x, configured.y, configured.z)), false);
        return 1;
    }

    /**
     * {@code /windfield storm [clear | rain]} — sets the executing dimension's weather.
     * <p>
     * Goes through vanilla's own {@code setWeatherParameters}, with the durations held open so the
     * weather stays where it was put until something else changes it. The rain and thunder
     * <em>levels</em> then ease in over about five seconds, exactly as they do for real weather,
     * which is what the field reads: the wind builds instead of stepping.
     */
    private static int storm(final CommandSourceStack source, final Weather weather) {
        final ServerLevel level = source.getLevel();
        // A switch *expression*, not a statement: only the expression form is exhaustive-checked,
        // so the compiler can prove every path yields a message.
        final String effect = switch (weather) {
            case CLEAR -> {
                level.setWeatherParameters(Integer.MAX_VALUE, 0, false, false);
                yield "Rain and thunder ease out over ~5 seconds, so the wind settles back to its"
                        + " dry-weather strength.";
            }
            case RAIN -> {
                level.setWeatherParameters(0, Integer.MAX_VALUE, true, false);
                yield "Rain eases in over ~5 seconds, so the wind builds the way it does in a real"
                        + " shower.";
            }
            case STORM -> {
                level.setWeatherParameters(0, Integer.MAX_VALUE, true, true);
                yield "Rain and thunder ease in over ~5 seconds, so the wind builds the way it does"
                        + " in a real storm.";
            }
        };

        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "[Ventus] %s weather set to %s. %s",
                level.dimension().location(), weather.name().toLowerCase(Locale.ROOT), effect)), false);
        return 1;
    }

    /**
     * {@code /windfield stats [radius] [step]} — sweeps a horizontal grid of samples around the
     * executing player and reports how often the wind ends up pointing against the dimension's
     * configured base wind.
     * <p>
     * This is the measurement behind the claim that a storm can reverse the heading. It has to run in
     * the world: the grid is sampled with the real per-dimension {@code SimplexNoise} at the real world
     * seed and the current rain/thunder levels, none of which the unit tests can see (Minecraft is not
     * on the test classpath — see the note in {@code build.gradle}).
     * <p>
     * <b>Window size matters.</b> The pressure noise is correlated over {@code fieldScale} (1500 blocks),
     * so a window much smaller than that samples a single patch of noise and reports that patch's
     * heading rather than the distribution — a first attempt with a 784-block window read 85% reversed
     * where the true figure is 50%. The default 4096-block window covers several cells; for a steadier
     * figure run it at a few far-apart centres and average, since {@code breakdown} touches no chunk and
     * an arbitrarily distant centre costs nothing but arithmetic.
     * <p>
     * {@code breakdown} never forces a chunk load, so points over unloaded terrain report
     * {@code slope = NaN} and assume full wind. Heading does not depend on terrain, so those samples
     * are still valid; they are counted separately so the figure can be read with that in mind.
     */
    /** Resolves the centre from the executing player, for the no-position form of the command. */
    private static int statsAtPlayer(final CommandSourceStack source, final int radius, final int step) {
        final Vec3 pos;
        try {
            pos = source.getPlayerOrException().position();
        } catch (final Exception e) {
            source.sendFailure(Component.literal(
                    "[Ventus] /windfield stats needs a player. From a console or RCON, use /windfield stats at <pos>."));
            return 0;
        }
        return stats(source, new Vector3d(pos.x, pos.y, pos.z), radius, step);
    }

    private static Vector3d centerOf(final Vec3 pos) {
        return new Vector3d(pos.x, pos.y, pos.z);
    }

    private static int stats(final CommandSourceStack source, final Vector3d center, final int radius, final int step) {
        final ServerLevel level = source.getLevel();

        final int perAxis = (2 * radius) / step + 1;
        final long sampleCount = (long) perAxis * perAxis;
        if (sampleCount > MAX_SAMPLES) {
            source.sendFailure(Component.literal(String.format(Locale.ROOT,
                    "[Ventus] %,d samples exceeds the %,d limit; run on the server thread, so a sweep this"
                            + " size would freeze the game (radius %d, step %d). Raise the step or lower the"
                            + " radius.",
                    sampleCount, MAX_SAMPLES, radius, step)));
            return 0;
        }

        final WindField.Breakdown parts = new WindField.Breakdown();

        int samples = 0;
        int measured = 0;
        int totalReverses = 0;
        int fieldReverses = 0;
        int fieldBeatsBase = 0;
        int capped = 0;
        int unknownTerrain = 0;
        double sumFieldRatio = 0.0;
        double sumBaseMag = 0.0;
        double sumTotalMag = 0.0;
        double weather = 1.0;

        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                WindField.breakdown(level, new Vector3d(center.x + dx, center.y, center.z + dz), parts);
                samples++;
                weather = parts.weather;
                if (parts.capped) {
                    capped++;
                }
                if (Double.isNaN(parts.slope)) {
                    unknownTerrain++;
                }

                // Compare headings in XZ only: base and field are horizontal, and the vertical
                // component of total carries thermal and orographic lift, which have no heading.
                final double baseMag = Math.hypot(parts.base.x, parts.base.z);
                if (baseMag == 0.0) {
                    continue;
                }
                measured++;

                final double fieldMag = Math.hypot(parts.field.x, parts.field.z);
                sumFieldRatio += fieldMag / baseMag;
                sumBaseMag += baseMag;
                sumTotalMag += Math.hypot(parts.total.x, parts.total.z);

                if (parts.field.x * parts.base.x + parts.field.z * parts.base.z < 0.0) {
                    fieldReverses++;
                }
                if (fieldMag > baseMag) {
                    fieldBeatsBase++;
                }
                if (parts.total.x * parts.base.x + parts.total.z * parts.base.z < 0.0) {
                    totalReverses++;
                }
            }
        }

        if (measured == 0) {
            source.sendFailure(Component.literal(
                    "[Ventus] no samples with a base wind - check [dimensions] in ventus-server.toml."));
            return 0;
        }

        final double width = 2.0 * radius + step;
        // Format outside the lambda: the counters are loop locals, so they are not effectively final.
        final String report = String.format(Locale.ROOT,
                "[Ventus] stats: %d samples over %.0f x %.0f blocks, step %d, y=%.1f%n"
                        + "  weather=%.2f  (%d samples had no terrain loaded)%n"
                        + "  total opposes base: %.1f%%   field opposes base: %.1f%%%n"
                        + "  field stronger than base: %.1f%%   mean |field|/|base| = %.2f%n"
                        + "  capped: %.1f%%   mean |base| = %.2f   mean |total| = %.2f",
                samples, width, width, step, center.y,
                weather, unknownTerrain,
                100.0 * totalReverses / measured,
                100.0 * fieldReverses / measured,
                100.0 * fieldBeatsBase / measured,
                sumFieldRatio / measured,
                100.0 * capped / samples,
                sumBaseMag / measured,
                sumTotalMag / measured);
        source.sendSuccess(() -> Component.literal(report), false);
        return measured;
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
                        + "  groundHeight=%s  (surfaceAt, leaves excluded)%n"
                        + "  floorHeight=%s  (OCEAN_FLOOR, leaves included)%n"
                        + "  thermal=%.3f  orographic=%.3f%n"
                        + "  base=(%.3f, %.3f, %.3f)%n"
                        + "  field=(%.3f, %.3f, %.3f)%n"
                        + "  gust=(%.3f, %.3f, %.3f)%n"
                        + "  total=(%.3f, %.3f, %.3f)",
                parts.heightFactor, parts.weather, parts.capped,
                altitude, parts.jet, parts.shear, parts.shearDegrees,
                Double.isNaN(parts.slope) ? "n/a" : String.format(Locale.ROOT, "%.3f", parts.slope),
                parts.leeBoost,
                Double.isNaN(parts.groundHeight) ? "n/a"
                        : String.format(Locale.ROOT, "%.1f", parts.groundHeight),
                Double.isNaN(parts.floorHeight) ? "n/a"
                        : String.format(Locale.ROOT, "%.1f", parts.floorHeight),
                parts.thermal, parts.orographic,
                parts.base.x, parts.base.y, parts.base.z,
                parts.field.x, parts.field.y, parts.field.z,
                parts.gust.x, parts.gust.y, parts.gust.z,
                parts.total.x, parts.total.y, parts.total.z);
    }
}
