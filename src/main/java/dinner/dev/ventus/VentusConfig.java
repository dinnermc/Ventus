package dinner.dev.ventus;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.joml.Vector3d;

/**
 * The server-side config ({@code config/ventus-server.toml}).
 * <p>
 * Everything the wind field needs is here: a global multiplier and, per dimension, a base
 * magnitude (m/s) and a base direction (degrees). The direction convention is
 * <b>0&deg; = +X (east)</b>, <b>90&deg; = +Z (south)</b>, measured in the XZ plane, so the default
 * Overworld wind ({@code magnitude=3, direction=0}) blows due east at 3 m/s.
 * <p>
 * The {@code -Dventus.wind=x,y,z} system property overrides this config entirely (see
 * {@link WindField}); it is a debug aid, not a config value.
 */
public final class VentusConfig {
    private VentusConfig() {
    }

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.DoubleValue WIND_SCALE;
    public static final ModConfigSpec.DoubleValue FADE_HEIGHT;
    public static final ModConfigSpec.DoubleValue SURFACE_CALM;

    public static final ModConfigSpec.DoubleValue OVERWORLD_MAGNITUDE;
    public static final ModConfigSpec.DoubleValue OVERWORLD_DIRECTION;
    public static final ModConfigSpec.DoubleValue NETHER_MAGNITUDE;
    public static final ModConfigSpec.DoubleValue NETHER_DIRECTION;
    public static final ModConfigSpec.DoubleValue END_MAGNITUDE;
    public static final ModConfigSpec.DoubleValue END_DIRECTION;

    static {
        final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        WIND_SCALE = builder
                .comment("Global multiplier applied to the sampled wind field.",
                        "Does not affect the -Dventus.wind debug override.")
                .defineInRange("windScale", 1.0, 0.0, 100.0);

        FADE_HEIGHT = builder
                .comment("Altitude above the terrain at which the wind reaches full strength (blocks).",
                        "Below this the wind ramps in smoothly down to surfaceCalm at the surface.")
                .defineInRange("fadeHeight", 32.0, 1.0, 512.0);

        SURFACE_CALM = builder
                .comment("Fraction of the wind that still blows at the terrain surface (0 = calm ground).",
                        "Low values stop a grounded sub-level from sliding while allowing a little",
                        "weathervaning. Does not apply to submerged sub-levels, which get no wind.")
                .defineInRange("surfaceCalm", 0.1, 0.0, 1.0);

        builder.comment("Base wind per dimension. Magnitude is in m/s (blocks/second).",
                        "Direction is in degrees: 0 = +X (east), 90 = +Z (south).")
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
