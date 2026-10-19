package dinner.dev.ventus;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.joml.Vector3d;

/**
 * The server-side config ({@code config/ventus-server.toml}). Per dimension: a base magnitude
 * (m/s) and a direction (degrees, 0 = +X east, 90 = +Z south; the default Overworld wind blows
 * due east at 3 m/s). The {@code -Dventus.wind=x,y,z} system property overrides this config
 * entirely — see {@link WindField}.
 */
public final class VentusConfig {
    private VentusConfig() {
    }

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.DoubleValue WIND_SCALE;
    public static final ModConfigSpec.DoubleValue FADE_HEIGHT;
    public static final ModConfigSpec.DoubleValue SURFACE_CALM;

    public static final ModConfigSpec.DoubleValue FIELD_SCALE;
    public static final ModConfigSpec.DoubleValue FIELD_PERIOD;
    public static final ModConfigSpec.DoubleValue FIELD_STRENGTH;
    public static final ModConfigSpec.DoubleValue MAX_WIND_MULTIPLIER;

    public static final ModConfigSpec.DoubleValue GUST_SCALE;
    public static final ModConfigSpec.DoubleValue GUST_PERIOD;
    public static final ModConfigSpec.DoubleValue GUST_AMPLITUDE;
    public static final ModConfigSpec.DoubleValue GUST_LATERAL_FRACTION;

    public static final ModConfigSpec.DoubleValue RAIN_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue THUNDER_MULTIPLIER;

    public static final ModConfigSpec.DoubleValue THERMAL_STRENGTH;
    public static final ModConfigSpec.DoubleValue THERMAL_SCALE;
    public static final ModConfigSpec.DoubleValue THERMAL_PERIOD;
    public static final ModConfigSpec.DoubleValue THERMAL_COLD_TEMPERATURE;
    public static final ModConfigSpec.DoubleValue THERMAL_HOT_TEMPERATURE;
    public static final ModConfigSpec.DoubleValue SLOPE_PROBE;
    public static final ModConfigSpec.DoubleValue SLOPE_STRENGTH;
    public static final ModConfigSpec.DoubleValue SLOPE_HEIGHT_FALLOFF;
    public static final ModConfigSpec.DoubleValue LEE_GUST_BOOST;
    public static final ModConfigSpec.DoubleValue VERTICAL_SMOOTH_SECONDS;
    public static final ModConfigSpec.DoubleValue THERMAL_SKY_CLEARANCE;

    public static final ModConfigSpec.DoubleValue JET_ALTITUDE;
    public static final ModConfigSpec.DoubleValue JET_THICKNESS;
    public static final ModConfigSpec.DoubleValue JET_STRENGTH;
    public static final ModConfigSpec.DoubleValue SHEAR_SPEED_PER_100;
    public static final ModConfigSpec.DoubleValue SHEAR_DEGREES_PER_100;
    public static final ModConfigSpec.DoubleValue SHEAR_MAX_ALTITUDE;

    public static final ModConfigSpec.DoubleValue OVERWORLD_MAGNITUDE;
    public static final ModConfigSpec.DoubleValue OVERWORLD_DIRECTION;
    public static final ModConfigSpec.DoubleValue NETHER_MAGNITUDE;
    public static final ModConfigSpec.DoubleValue NETHER_DIRECTION;
    public static final ModConfigSpec.DoubleValue END_MAGNITUDE;
    public static final ModConfigSpec.DoubleValue END_DIRECTION;

    static {
        final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        ENABLED = builder
                .comment("Master switch: off = zero wind everywhere and the client HUD disappears. Hands "
                        + "the air to another wind mod; hot-reloaded, no restart needed.")
                .define("enabled", true);

        WIND_SCALE = builder
                .comment("Global multiplier applied to the sampled wind field (not the -Dventus.wind override).")
                .defineInRange("windScale", 1.0, 0.0, 100.0);

        FADE_HEIGHT = builder
                .comment("Altitude above the terrain where the wind reaches full strength (blocks); below "
                        + "it the wind ramps down to surfaceCalm at the surface.")
                .defineInRange("fadeHeight", 32.0, 1.0, 512.0);

        SURFACE_CALM = builder
                .comment("Fraction of the wind still blowing at the terrain surface (0 = calm ground); "
                        + "submerged sub-levels get no wind.")
                .defineInRange("surfaceCalm", 0.1, 0.0, 1.0);

        FIELD_SCALE = builder
                .comment("Map scale (blocks) of the largest wind structures: smaller values give more, "
                        + "tighter weather systems.")
                .defineInRange("fieldScale", 1500.0, 100.0, 100000.0);

        FIELD_PERIOD = builder
                .comment("Seconds for the pressure field to evolve noticeably; larger values give steadier wind.")
                .defineInRange("fieldPeriod", 1200.0, 20.0, 86400.0);

        FIELD_STRENGTH = builder
                .comment("Strength of the pressure-field perturbation as a multiple of the base wind; "
                        + "0 disables the field.")
                .defineInRange("fieldStrength", 1.0, 0.0, 5.0);

        MAX_WIND_MULTIPLIER = builder
                .comment("Hard cap: the total wind never exceeds this multiple of the dimension's base "
                        + "wind magnitude.")
                .defineInRange("maxWindMultiplier", 3.0, 1.0, 10.0);

        GUST_SCALE = builder
                .comment("Map scale (blocks) of the gusts; smaller than fieldScale, so gusts are local.")
                .defineInRange("gustScale", 150.0, 20.0, 10000.0);

        GUST_PERIOD = builder
                .comment("Seconds for a gust to build and fade.")
                .defineInRange("gustPeriod", 120.0, 5.0, 86400.0);

        GUST_AMPLITUDE = builder
                .comment("Gust strength as a fraction of the base wind; 0 disables gusts.")
                .defineInRange("gustAmplitude", 0.35, 0.0, 2.0);

        GUST_LATERAL_FRACTION = builder
                .comment("How much of a gust pushes sideways rather than along the wind; 0 = purely a "
                        + "speed change.")
                .defineInRange("gustLateralFraction", 0.3, 0.0, 1.0);

        RAIN_MULTIPLIER = builder
                .comment("Strength the pressure field and the gusts reach at full rain, as a multiple of "
                        + "their dry strength; eased in by vanilla's ~5 s rain fade. The base wind is not touched.")
                .defineInRange("rainMultiplier", 1.5, 1.0, 10.0);

        THUNDER_MULTIPLIER = builder
                .comment("Extra strength at full thunder, on top of rainMultiplier (vanilla's thunder "
                        + "level is already gated by the rain level).")
                .defineInRange("thunderMultiplier", 1.6, 1.0, 10.0);

        THERMAL_STRENGTH = builder
                .comment("Ceiling of a thermal updraft in m/s over hot ground in full sun with a clear "
                        + "sky; 0 disables thermals.")
                .defineInRange("thermalStrength", 2.5, 0.0, 20.0);

        THERMAL_SCALE = builder
                .comment("Map scale (blocks) of the thermal bubbles.")
                .defineInRange("thermalScale", 200.0, 20.0, 10000.0);

        THERMAL_PERIOD = builder
                .comment("Seconds for the thermal bubbles to drift and evolve.")
                .defineInRange("thermalPeriod", 90.0, 5.0, 86400.0);

        THERMAL_COLD_TEMPERATURE = builder
                .comment("Biome base temperature at or below which there are no thermals (vanilla ranges "
                        + "roughly 0.2 snowy to 2.0 desert).")
                .defineInRange("thermalColdTemperature", 0.8, -2.0, 30.0);

        THERMAL_HOT_TEMPERATURE = builder
                .comment("Biome base temperature at which thermals reach their full strength.")
                .defineInRange("thermalHotTemperature", 2.0, -2.0, 30.0);

        SLOPE_PROBE = builder
                .comment("Blocks either side of a point used to measure the terrain slope; larger values "
                        + "smooth blocky cliffs away.")
                .defineInRange("slopeProbe", 16.0, 1.0, 256.0);

        SLOPE_STRENGTH = builder
                .comment("Multiplier on the physical orographic lift (1.0 = the real thing; negative "
                        + "slopes give downdraft).")
                .defineInRange("slopeStrength", 0.5, -5.0, 5.0);

        SLOPE_HEIGHT_FALLOFF = builder
                .comment("E-folding distance in blocks for the orographic lift and the leeward turbulence "
                        + "above the terrain: both are multiplied by exp(-height / this).")
                .defineInRange("slopeHeightFalloff", 50.0, 1.0, 1000.0);

        LEE_GUST_BOOST = builder
                .comment("Extra gust amplitude behind a ridge as a fraction of the base gust, at a slope "
                        + "of -1 or steeper (0 = none).")
                .defineInRange("leeGustBoost", 1.0, 0.0, 10.0);

        VERTICAL_SMOOTH_SECONDS = builder
                .comment("Seconds over which the thermal and orographic updrafts ease towards their "
                        + "target (0 = no smoothing).")
                .defineInRange("verticalSmoothSeconds", 1.5, 0.0, 60.0);

        THERMAL_SKY_CLEARANCE = builder
                .comment("Blocks of clearance above the terrain before a point sees the full sun for "
                        + "thermal purposes.")
                .defineInRange("thermalSkyClearance", 8.0, 0.0, 256.0);

        JET_ALTITUDE = builder
                .comment("Absolute world Y of the jet stream's core (keyed on absolute altitude, not "
                        + "height above the ground).")
                .defineInRange("jetAltitude", 220.0, -64.0, 2048.0);
        JET_THICKNESS = builder
                .comment("Half-width of the jet band in blocks: the multiplier eases from its peak at "
                        + "jetAltitude to none at jetAltitude +/- this. 0 switches the jet off.")
                .defineInRange("jetThickness", 60.0, 0.0, 2048.0);
        JET_STRENGTH = builder
                .comment("Extra wind multiplier at the jet's core (the base wind is multiplied by 1 + "
                        + "this, easing back to 1 at the band edges). Speed only — the gusts are not scaled.")
                .defineInRange("jetStrength", 2.0, 0.0, 100.0);
        SHEAR_SPEED_PER_100 = builder
                .comment("Wind speed change per 100 blocks of absolute altitude, as a multiplier; "
                        + "additive and floored at zero, so it can still the wind but never reverse it.")
                .defineInRange("shearSpeedPer100", 0.25, -1.0, 100.0);
        SHEAR_DEGREES_PER_100 = builder
                .comment("Degrees the wind's heading turns per 100 blocks of absolute altitude (positive "
                        + "turns clockwise from above, the same sense the direction keys use).")
                .defineInRange("shearDegreesPer100", 15.0, -360.0, 360.0);
        SHEAR_MAX_ALTITUDE = builder
                .comment("Absolute world Y above which the shear stops growing and holds its value "
                        + "rather than disappearing; 0 switches the shear off.")
                .defineInRange("shearMaxAltitude", 300.0, 0.0, 3500.0);

        builder.comment("Base wind per dimension. Magnitude in m/s (blocks/second); direction in degrees: "
                        + "0 = +X (east), 90 = +Z (south).")
                .push("dimensions");

        builder.push("overworld");
        OVERWORLD_MAGNITUDE = builder.comment("Overworld base wind magnitude (m/s).")
                .defineInRange("magnitude", 3.0, 0.0, 100.0);
        OVERWORLD_DIRECTION = builder.comment("Overworld base wind direction (degrees).")
                .defineInRange("direction", 0.0, -360.0, 360.0);
        builder.pop();

        builder.push("nether");
        NETHER_MAGNITUDE = builder.comment("Nether base wind magnitude (m/s). Default calm.")
                .defineInRange("magnitude", 0.0, 0.0, 100.0);
        NETHER_DIRECTION = builder.comment("Nether base wind direction (degrees).")
                .defineInRange("direction", 0.0, -360.0, 360.0);
        builder.pop();

        builder.push("the_end");
        END_MAGNITUDE = builder.comment("End base wind magnitude (m/s). Default calm.")
                .defineInRange("magnitude", 0.0, 0.0, 100.0);
        END_DIRECTION = builder.comment("End base wind direction (degrees).")
                .defineInRange("direction", 0.0, -360.0, 360.0);
        builder.pop();

        builder.pop();

        SPEC = builder.build();
    }

    /**
     * @return the master switch. While it is off the field is zero everywhere, for every caller.
     */
    public static boolean enabled() {
        return ENABLED.get();
    }

    public static double windScale() {
        return WIND_SCALE.get();
    }

    /**
     * @return altitude above the terrain (blocks) at which the wind reaches full strength
     */
    public static double fadeHeight() {
        return FADE_HEIGHT.get();
    }

    /**
     * @return the fraction of the wind that still blows at the terrain surface, in {@code [0, 1]}
     */
    public static double surfaceCalm() {
        return SURFACE_CALM.get();
    }

    /**
     * @return the multiplier applied to the field and gusts wherever it is raining
     */
    public static double rainMultiplier() {
        return RAIN_MULTIPLIER.get();
    }

    /**
     * @return the extra multiplier applied during a thunderstorm (on top of {@link #rainMultiplier()})
     */
    public static double thunderMultiplier() {
        return THUNDER_MULTIPLIER.get();
    }

    /** @return the ceiling of a thermal updraft, in m/s (0 disables thermals) */
    public static double thermalStrength() {
        return THERMAL_STRENGTH.get();
    }

    /** @return the map scale (blocks) of the thermal bubbles */
    public static double thermalScale() {
        return THERMAL_SCALE.get();
    }

    /** @return seconds for the thermal bubbles to drift and evolve */
    public static double thermalPeriod() {
        return THERMAL_PERIOD.get();
    }

    /** @return the biome temperature at or below which there are no thermals */
    public static double thermalColdTemperature() {
        return THERMAL_COLD_TEMPERATURE.get();
    }

    /** @return the biome temperature at which thermals reach full strength */
    public static double thermalHotTemperature() {
        return THERMAL_HOT_TEMPERATURE.get();
    }

    /** @return blocks either side of a point used to measure the terrain slope */
    public static double slopeProbe() {
        return SLOPE_PROBE.get();
    }

    /** @return the multiplier on the physical orographic lift */
    public static double slopeStrength() {
        return SLOPE_STRENGTH.get();
    }

    /** @return the hard cap: the total wind never exceeds this multiple of the base wind magnitude */
    public static double maxWindMultiplier() {
        return MAX_WIND_MULTIPLIER.get();
    }

    /** @return altitude over which the orographic lift and leeward turbulence decay by a factor of e */
    public static double slopeHeightFalloff() {
        return SLOPE_HEIGHT_FALLOFF.get();
    }

    /** @return the extra gust amplitude behind a ridge, as a fraction of the base gust */
    public static double leeGustBoost() {
        return LEE_GUST_BOOST.get();
    }

    /** @return seconds over which the vertical terms ease towards their target (0 = no smoothing) */
    public static double verticalSmoothSeconds() {
        return VERTICAL_SMOOTH_SECONDS.get();
    }

    /** @return blocks of clearance needed above the terrain for a point to see full sun */
    public static double thermalSkyClearance() {
        return THERMAL_SKY_CLEARANCE.get();
    }

    /** @return absolute world Y of the jet stream's core */
    public static double jetAltitude() {
        return JET_ALTITUDE.get();
    }

    /** @return half-width of the jet band in blocks (0 = the jet is off) */
    public static double jetThickness() {
        return JET_THICKNESS.get();
    }

    /** @return extra wind multiplier at the jet core */
    public static double jetStrength() {
        return JET_STRENGTH.get();
    }

    /** @return wind speed multiplier gained per 100 blocks of absolute altitude */
    public static double shearSpeedPer100() {
        return SHEAR_SPEED_PER_100.get();
    }

    /** @return degrees of heading turn per 100 blocks of absolute altitude */
    public static double shearDegreesPer100() {
        return SHEAR_DEGREES_PER_100.get();
    }

    /** @return the absolute world Y at which the wind shear saturates (0 = the shear is off) */
    public static double shearMaxAltitude() {
        return SHEAR_MAX_ALTITUDE.get();
    }

    /**
     * @return the field model inputs, read fresh from the (hot-reloadable) config on every call
     */
    public static WindModel.Settings windModelSettings() {
        return new WindModel.Settings(
                FIELD_SCALE.get(),
                FIELD_PERIOD.get(),
                FIELD_STRENGTH.get(),
                MAX_WIND_MULTIPLIER.get(),
                GUST_SCALE.get(),
                GUST_PERIOD.get(),
                GUST_AMPLITUDE.get(),
                GUST_LATERAL_FRACTION.get(),
                LEE_GUST_BOOST.get());
    }

    /**
     * Writes the dimension's base wind vector into {@code dest} and returns it. Dimensions that
     * have no configured base wind (anything but the Overworld, Nether and End) get a zero vector.
     *
     * @param dimension the dimension key ({@link Level#OVERWORLD}, {@link Level#NETHER}, {@link Level#END}, ...)
     * @param dest      the vector to write into
     * @return {@code dest}
     */
    public static Vector3d baseWind(final ResourceKey<Level> dimension, final Vector3d dest) {
        final double magnitude;
        final double direction;

        if (dimension == Level.OVERWORLD) {
            magnitude = OVERWORLD_MAGNITUDE.get();
            direction = OVERWORLD_DIRECTION.get();
        } else if (dimension == Level.NETHER) {
            magnitude = NETHER_MAGNITUDE.get();
            direction = NETHER_DIRECTION.get();
        } else if (dimension == Level.END) {
            magnitude = END_MAGNITUDE.get();
            direction = END_DIRECTION.get();
        } else {
            return dest.set(0.0, 0.0, 0.0);
        }

        if (magnitude <= 0.0) {
            return dest.set(0.0, 0.0, 0.0);
        }

        final double radians = Math.toRadians(direction);
        return dest.set(Math.cos(radians) * magnitude, 0.0, Math.sin(radians) * magnitude);
    }
}
