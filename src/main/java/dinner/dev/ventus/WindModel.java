package dinner.dev.ventus;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * The stage-2 wind model: the pressure field that makes the wind vary over the world and in time.
 * <p>
 * This class is deliberately free of any Minecraft type (it only uses JOML). The stateful, world- and
 * config-aware parts live in {@link WindField}; here is only the arithmetic, so it can be reasoned
 * about and tested in isolation.
 * <p>
 * <b>How the field is built.</b> A scalar <b>pressure potential</b> {@code P(x, z, t)} is sampled from
 * 3D simplex noise at {@code (x / scale, z / scale, t / period)}. The wind perturbation is the
 * potential's gradient rotated by 90&deg; in the XZ plane, {@code (-dP/dz, dP/dx)}: rotating turns the
 * "downhill" gradient into a flow <em>along the isobars</em>, which is what a real wind does around a
 * pressure system. That perturbation is then added <em>on top of</em> the dimension's base wind, so the
 * prevailing direction anchors the flow and the field deflects and modulates it. Note that anchoring is
 * not a guarantee the heading wins: at the default {@code fieldStrength = 1} the typical deflection is
 * already as large as the base, and weather scales the field but never the base (see below).
 * <p>
 * <b>Magnitude.</b> The raw noise gradient has an unpredictable magnitude, so it is scaled by
 * {@code base / GRAD_REFERENCE}. {@code GRAD_REFERENCE} is the measured mean of {@code |rotated
 * gradient|} for vanilla's {@link net.minecraft.world.level.levelgen.synth.SimplexNoise} over
 * {@link #GRAD_STEP}, so the <b>typical perturbation magnitude equals the base wind magnitude</b>.
 * The gradient is <em>not</em> normalised per-sample: dividing by its own length would make the
 * direction flip across gradient zeros and introduce a discontinuity.
 * <p>
 * A hard <b>cap</b> ({@code maxMultiplier * base}) bounds the result so a bad parameter or an extreme
 * noise value can never produce an absurd wind or a NaN.
 * <p>
 * <b>Gusts (stage 2b).</b> A second, shorter-wavelength noise adds faster variation on top of the
 * pressure field. It is applied mostly <em>along</em> the local wind direction with a small lateral
 * component, so it feels like the wind speeding up and easing off rather than changing direction. Its
 * amplitude is a fraction of the base wind.
 * <p>
 * <b>Weather (stage 3).</b> A scalar {@code weather} multiplier (rain, then thunder) is applied to the
 * field and the gusts but never to the base wind, so the configured prevailing flow itself is
 * unchanged. The <em>total</em> can still turn: at storm strength the perturbation grows while the
 * base does not, and the budget caps magnitude rather than heading, so a storm may blow the air
 * against {@code direction}. That is accepted behaviour, not a bug - see the README.
 * <p>
 * <b>Vertical air (stage 4).</b> Thermals and orographic lift are <em>not</em> part of the pressure
 * potential: they are driven by the sun, the biome and the shape of the ground, so they are computed
 * from world terms that {@link WindField} supplies as plain scalars, using the pure functions
 * {@link #thermal} and {@link #orographic}. The leeward side of a slope also roughens the gusts, which
 * is the one stage-4 term that reaches back into this class ({@code leeBoost}).
 */
public final class WindModel {
    private WindModel() {
    }

    /**
     * A 3D scalar noise field. Implemented over vanilla's {@code SimplexNoise} in {@link WindField},
     * and over a cheap stand-in in tests.
     */
    @FunctionalInterface
    public interface Noise3 {
        double at(double x, double y, double z);
    }

    /** Central-difference step, in noise space (the potential's own units, before scaling). */
    private static final double GRAD_STEP = 0.1;

    /**
     * Measured mean magnitude of the rotated central-difference gradient of vanilla's simplex noise
     * over {@link #GRAD_STEP} ({@code 0.4163}, from 300k samples). Dividing by this makes the typical
     * perturbation the same size as the base wind.
     */
    private static final double GRAD_REFERENCE = 0.4163;

    /**
     * A fixed offset (in gust noise space) used to sample a second, decorrelated scalar from the same
     * gust noise for the lateral component. Sampling one noise field twice is cheaper than carrying a
     * second {@link Noise3} and is just as smooth.
     */
    private static final double GUST_LATERAL_OFFSET_X = 5.0;
    private static final double GUST_LATERAL_OFFSET_Z = -2.0;

    /**
     * The local wind, as a fraction of the base wind, below which the gusts fade out. At or above
     * {@code GUST_CALM_FADE * reference} the factor is exactly 1, so ordinary wind is untouched and
     * {@code gustAmplitude} keeps meaning what the config says. At zero local wind it is 0.
     * <p>
     * The gust is oriented along the local (base + field) wind, and its amplitude does not shrink as
     * that vector shortens. Where the perturbation cancels the base the direction is undefined and
     * the gust swings with it — measured, the total jumped 86% of the base wind across a quarter
     * block. Fading on the local speed removes the jump and is also the physical answer: a col in
     * the pressure field is a calm point, and calm air carries no gusts.
     * <p>
     * A constant rather than a config key on purpose. Adding a key later is backwards-compatible and
     * removing one is not, so it stays hard-coded until somebody actually asks to tune it.
     */
    private static final double GUST_CALM_FADE = 0.5;

    /**
     * The tunable inputs, mirroring the server config (see {@link VentusConfig}).
     *
     * @param fieldScale           map scale: the size in blocks of the largest wind structures ({@code L})
     * @param fieldPeriod          seconds for the field to evolve noticeably ({@code T})
     * @param strength             multiplier on the perturbation (1 = typical perturbation equals the base wind)
     * @param maxMultiplier        hard cap: the wind never exceeds this times the base wind
     * @param gustScale            map scale in blocks of the gusts (smaller than {@code fieldScale})
     * @param gustPeriod           seconds for a gust to build and fade
     * @param gustAmplitude        gust strength as a fraction of the base wind
     * @param gustLateralFraction  how much of the gust pushes sideways (0 = purely along the wind)
     * @param leeGustBoost         extra gust amplitude on the lee side of a slope, at slope -1 (0 = off)
     */
    public record Settings(double fieldScale, double fieldPeriod, double strength, double maxMultiplier,
            double gustScale, double gustPeriod, double gustAmplitude, double gustLateralFraction,
            double leeGustBoost) {
    }

    /**
     * Output of {@link #evaluate}: the perturbation and the resulting total, reused across calls to
     * avoid per-tick allocation.
     */
    public static final class Result {
        /** The pressure-field perturbation (before the cap). */
        public final Vector3d field = new Vector3d();
        /** The gust contribution (before the cap); zero when gusts are off. */
        public final Vector3d gust = new Vector3d();
        /** {@code base + field + gust}, capped at {@code maxMultiplier * base} (horizontal only). */
        public final Vector3d total = new Vector3d();
        /** Whether the cap clamped {@link #total}. */
        public boolean capped;

        void reset() {
            this.field.set(0.0, 0.0, 0.0);
            this.gust.set(0.0, 0.0, 0.0);
            this.total.set(0.0, 0.0, 0.0);
            this.capped = false;
        }
    }

    /**
     * Weather scaling for the perturbations, from the dimension's continuous weather levels.
     *
     * <p>Vanilla fades {@code rainLevel} and {@code thunderLevel} in and out by 0.01 per tick, so
     * feeding those levels (rather than the boolean "is it raining?" flags) makes the wind strengthen
     * and ease over those same ~5 seconds. There is no step at the threshold the flags use, and no
     * per-position biome or heightmap lookup: the weather is global to the dimension, which is all the
     * field needs.
     *
     * <p>At full rain the result is {@code rainMultiplier}, and at full rain <em>and</em> full thunder
     * it is {@code rainMultiplier * thunderMultiplier}. Both multipliers are at least 1, so this only
     * ever roughens the air.
     *
     * @param rainMultiplier  the config multiplier applied at full rain
     * @param thunderMultiplier the config multiplier applied at full thunder
     * @param rainLevel       {@code level.getRainLevel(1.0F)}, in {@code [0, 1]}
     * @param thunderLevel    {@code level.getThunderLevel(1.0F)}, in {@code [0, 1]}; vanilla already
     *                        folds the rain level into it, so it is 0 whenever it is not raining
     * @return the multiplier to apply to the field and gusts (1 = no weather)
     */
    public static double weatherScale(final double rainMultiplier, final double thunderMultiplier,
            final double rainLevel, final double thunderLevel) {
        if (rainLevel <= 0.0) {
            return 1.0;
        }

        final double rainScale = 1.0 + (rainMultiplier - 1.0) * rainLevel;
        final double thunderScale = 1.0 + (thunderMultiplier - 1.0) * thunderLevel;
        return rainScale * thunderScale;
    }

    /**
     * The upward speed of a thermal at one point, in blocks/second.
     *
     * <p>A thermal is surface heating made visible: it needs sun ({@code daylight}), ground worth
     * heating ({@code warmth}) and an overcast-free sky ({@code dryness}). The noise carves the
     * rising air into <em>bubbles</em> — only its positive half lifts, so between thermals the air is
     * simply still rather than sinking, which is what makes them worth circling.
     *
     * <p>Every factor is a plain {@code [0, 1]} scalar supplied by {@link WindField}, so this is a
     * product of four numbers and cannot produce a NaN or an overshoot. The noise is clamped into
     * {@code [0, 1]} first, so {@code strength} really is the ceiling even if a noise sample wanders
     * outside its nominal range.
     *
     * @param noise       thermal-bubble noise, nominally in {@code [-1, 1]}
     * @param daylight    how far past dawn and short of dusk the sun is, in {@code [0, 1]}
     * @param warmth      how hot the biome is, in {@code [0, 1]}
     * @param dryness     {@code 1 - rainLevel}: an overcast sky has no thermals
     * @param strength    the configured maximum updraft, in blocks/second
     * @return the updraft, {@code >= 0} and {@code <= strength}
     */
    public static double thermal(final double noise, final double daylight, final double warmth,
            final double dryness, final double strength) {
        final double bubble = Math.min(1.0, Math.max(0.0, noise));
        return strength * daylight * warmth * dryness * bubble;
    }

    /**
     * The vertical speed the terrain forces on the air, in blocks/second: the <b>orographic</b> term.
     *
     * <p>Air that moves horizontally at {@code horizontalSpeed} over ground rising at {@code slope}
     * (rise over run, so {@code 1.0} is a 45&deg; slope) is itself climbing at
     * {@code horizontalSpeed * slope}. That is ridge lift on a windward face and, with a negative
     * slope, the sink behind the ridge — the same term, signed.
     *
     * <p>{@code strength} scales the physical value; {@code 1.0} is the real thing, lower values tame
     * Minecraft's blocky cliffs, higher ones exaggerate them.
     *
     * @param horizontalSpeed the wind speed in the XZ plane, blocks/second, <b>signed along the
     *                        direction {@code slope} was measured in</b> — see {@link #alongBaseWind}.
     *                        An unsigned speed here would keep lifting the air on a face the wind has
     *                        since reversed away from
     * @param slope           rise over run of the terrain along the wind direction (positive = the
     *                        wind is climbing, negative = it is descending)
     * @param strength        the configured multiplier
     * @return the vertical air speed, positive up
     */
    public static double orographic(final double horizontalSpeed, final double slope, final double strength) {
        return horizontalSpeed * slope * strength;
    }

    /**
     * The local wind's component <em>along the prevailing base wind</em>: positive when the air runs
     * as the dimension's wind does, negative once the perturbations have turned it around, and zero
     * exactly where they cancel it.
     *
     * <p>{@link #orographic} is {@code speed &times; slope}, and the slope is measured along the base
     * direction, so a plain (unsigned) speed would make the sign depend on the terrain alone. This is
     * the other half: it turns negative precisely when the air moves against the direction the slope
     * was measured in, and it passes through zero continuously at the calm point instead of jumping.
     *
     * @param baseX  the prevailing wind's X (the axis the slope was measured along)
     * @param baseZ  the prevailing wind's Z
     * @param totalX the local wind's X (base + field + gust)
     * @param totalZ the local wind's Z
     * @return the local wind's speed along the base axis, signed; {@code 0} when the base has no
     *         direction to speak of (the slope is {@code NaN} there anyway, so the term drops out)
     */
    public static double alongBaseWind(final double baseX, final double baseZ,
            final double totalX, final double totalZ) {
        final double length = Math.hypot(baseX, baseZ);
        if (length == 0.0) {
            return 0.0;
        }
        return (totalX * baseX + totalZ * baseZ) / length;
    }

    /**
     * How much extra gust amplitude the lee side of a slope gets, as a fraction of the base gust
     * amplitude ({@code 0} = none).
     *
     * <p>Only a descending slope produces the extra turbulence: the air has just poured over a ridge
     * and is tumbling. A windward slope ({@code slope >= 0}) is smooth lift and gets nothing. The
     * result is clamped so an over-steep cliff cannot run away.
     *
     * @param slope rise over run of the terrain along the wind direction
     * @param boost the configured maximum, at a slope of {@code -1} or steeper
     * @return the multiplier to add to 1 when scaling the gusts
     */
    public static double leeGustBoost(final double slope, final double boost) {
        if (slope >= 0.0) {
            return 0.0;
        }
        return boost * Math.min(1.0, -slope);
    }

    /**
     * The height of the ground at one column, or {@link Double#NaN} when it is not known.
     *
     * <p>Supplied by the caller so that <em>which</em> surface counts is an explicit decision rather
     * than something buried in the arithmetic. For orographic lift the caller must return the surface
     * the air actually flows over — the sea surface, not the seabed, and the ground under a canopy,
     * not the canopy itself. See {@code WindField.surfaceAt}.
     */
    @FunctionalInterface
    public interface ColumnHeight {
        double at(double x, double z);
    }

    /**
     * The rise-over-run of the ground along a horizontal direction: the signed slope that orographic
     * lift is built from, positive where the air is about to climb.
     *
     * <p>Two columns {@code probe} blocks either side, so a single-block step is averaged away
     * instead of towering over the answer, and the result is clamped to a 45&deg; slope — both the
     * physical maximum worth modelling and the slope at which {@link #leeGustBoost} reaches its
     * ceiling. Minecraft terrain can be a vertical wall, and letting that through would turn ridge
     * lift into a 45 m/s updraft.
     *
     * <p>The direction is normalised here, so the caller may pass an arbitrary non-zero vector.
     *
     * @param heights the ground surface the air flows over (see {@link ColumnHeight})
     * @param x       world X of the point being measured
     * @param z       world Z of the point being measured
     * @param dirX    X component of the horizontal direction to measure along
     * @param dirZ    Z component of the horizontal direction to measure along
     * @param probe   blocks either side to sample; {@code <= 0} gives {@link Double#NaN}
     * @return the slope clamped to {@code [-1, 1]}, or {@link Double#NaN} if either column is unknown
     */
    public static double slopeAlongWind(final ColumnHeight heights, final double x, final double z,
            final double dirX, final double dirZ, final double probe) {
        final double dirLength = Math.hypot(dirX, dirZ);
        if (dirLength == 0.0 || probe <= 0.0) {
            return Double.NaN;
        }

        final double ux = dirX / dirLength;
        final double uz = dirZ / dirLength;

        final double downwind = heights.at(x + ux * probe, z + uz * probe);
        final double upwind = heights.at(x - ux * probe, z - uz * probe);
        if (Double.isNaN(downwind) || Double.isNaN(upwind)) {
            return Double.NaN;
        }

        final double slope = (downwind - upwind) / (2.0 * probe);
        if (slope > 1.0) {
            return 1.0;
        }
        return slope < -1.0 ? -1.0 : slope;
    }

    /**
     * Keeps a finished wind within {@code maxMultiplier} times the prevailing wind at the same point,
     * and guarantees that it is finite.
     *
     * <p>The whole vector is scaled, <em>never</em> clipped per component. Clipping each component
     * separately would change the heading exactly when the cap engages — the strongest gusts would
     * arrive from the wrong direction — whereas scaling preserves the direction to the last bit.
     *
     * <p>A non-finite component zeroes the whole vector rather than being clamped, so a NaN can
     * never reach a rigid body and turn into a permanent one-tick force.
     *
     * @param wind         the finished wind, already scaled; modified in place
     * @param prevailing   the prevailing wind at the same point (after any height profile)
     * @param maxMultiplier the budget as a multiple of {@code prevailing}
     * @return the resulting magnitude, {@code 0} if the wind was non-finite
     */
    public static double clampToBudget(final Vector3d wind, final double prevailing, final double maxMultiplier) {
        if (!Double.isFinite(wind.x) || !Double.isFinite(wind.y) || !Double.isFinite(wind.z)) {
            wind.set(0.0, 0.0, 0.0);
            return 0.0;
        }

        final double cap = maxMultiplier * prevailing;
        final double mag2 = wind.lengthSquared();
        if (mag2 > cap * cap) {
            wind.mul(cap / Math.sqrt(mag2));
        }
        return wind.length();
    }

    /**
     * How much of a terrain-locked effect survives at a height above the ground: an exponential
     * decay with {@code falloff} as its e-folding distance.
     *
     * <p>A ridge lifts the air beside it; an aircraft crossing the range far above the crest should
     * feel almost none of it. Both the orographic term and the leeward turbulence are scaled by this,
     * while thermals deliberately are not — a thermal exists <em>because</em> it rose.
     *
     * @param heightAboveGround how far above the terrain, in blocks ({@code <= 0} counts as ground)
     * @param falloff           the e-folding distance in blocks; {@code <= 0} disables the decay
     * @return a factor in {@code (0, 1]}
     */
    public static double heightDecay(final double heightAboveGround, final double falloff) {
        if (falloff <= 0.0 || heightAboveGround <= 0.0) {
            return 1.0;
        }
        return Math.exp(-heightAboveGround / falloff);
    }

    /**
     * One step of exponential smoothing, easing {@code current} towards {@code target} by a factor
     * that depends on how much time has passed rather than on the frame rate.
     *
     * <p>Used on the thermal and orographic terms only: both are derived from integer terrain
     * heights, so as an aircraft moves the sampled columns change and the updraft would otherwise
     * step. Gusts are not smoothed, because they are meant to be quick.
     *
     * @param current       the previously smoothed value
     * @param target        the value to ease towards
     * @param seconds       the time constant (how long to close about 63% of the gap); {@code <= 0}
     *                      disables smoothing and returns {@code target}
     * @param deltaSeconds  the time since the previous step
     * @return the new smoothed value
     */
    public static double approach(final double current, final double target, final double seconds,
            final double deltaSeconds) {
        if (seconds <= 0.0) {
            return target;
        }
        return current + (target - current) * (1.0 - Math.exp(-deltaSeconds / seconds));
    }

    /**
     * Hermite smoothstep on {@code [0, 1]}, with zero slope at both ends so a band built from it
     * starts and stops without a step.
     *
     * @param t the position across the band; clamped to {@code [0, 1]}
     * @return a value in {@code [0, 1]}
     */
    public static double smoothstep(final double t) {
        final double clamped = Math.min(1.0, Math.max(0.0, t));
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }

    /**
     * How strongly the jet stream is blowing at a given <em>absolute</em> altitude.
     *
     * <p>A jet is a band of fast air, not a feature of the ground below it, so this is keyed on world
     * Y rather than on height above terrain. The band is a smoothstep centred on {@code core}: the
     * multiplier is {@code 1 + strength} at the core, eases to {@code 1} at {@code core ± thickness},
     * and stays at {@code 1} outside. It scales the prevailing wind's <em>magnitude</em> only — the
     * jet rides on the direction the dimension already blows, which is what keeps it composable with
     * the clamp budget downstream.
     *
     * @param altitude  absolute world Y (blocks)
     * @param core      absolute world Y of the jet core
     * @param thickness half-width of the band in blocks; {@code <= 0} disables the jet
     * @param strength  extra multiplier at the core (0 = none, 9 = ten times the base)
     * @return a multiplier {@code >= 1}
     */
    public static double jetFactor(final double altitude, final double core, final double thickness,
            final double strength) {
        if (strength <= 0.0 || thickness <= 0.0) {
            return 1.0;
        }
        final double across = Math.abs(altitude - core) / thickness;
        if (across >= 1.0) {
            return 1.0;
        }
        return 1.0 + strength * smoothstep(1.0 - across);
    }

    /**
     * The altitude the wind shear is measured from.
     *
     * <p>Shear is a near-surface effect: it grows as you climb away from the ground and then
     * saturates, which is what the real boundary layer does — the Ekman spiral converges after a
     * kilometre or so rather than turning forever. Without a cap the growth runs all the way to the
     * top of Sable's atmosphere, where the wind has done several full turns and the budget has no
     * say over the heading. Below Y 0 this reads as Y 0, so the deep dark does not turn the wind the
     * other way.
     *
     * @param altitude    absolute world Y (blocks)
     * @param maxAltitude the altitude at which the shear stops growing; {@code <= 0} caps it at the
     *                    ground, which switches the shear off entirely
     * @return the altitude to measure the shear from, clamped to {@code [0, maxAltitude]}
     */
    public static double shearAltitude(final double altitude, final double maxAltitude) {
        return Math.min(Math.max(0.0, altitude), Math.max(0.0, maxAltitude));
    }

    /**
     * The wind shear's effect on speed at a given absolute altitude.
     *
     * <p>Shear is a rate per 100 blocks measured from Y 0, so it describes how the wind at one height
     * compares to the wind at another, with no ground involved. It is additive, and floored at zero
     * so a negative rate can calm the air at altitude but never reverse it. The altitude is the one
     * {@link #shearAltitude} returns, so the shear saturates at {@code maxAltitude} and reads as
     * ground level below Y 0.
     *
     * @param altitude    absolute world Y (blocks)
     * @param perHundred  multiplier gained per 100 blocks of altitude (0 = none)
     * @param maxAltitude the altitude at which the shear stops growing
     * @return a multiplier {@code >= 0}
     */
    public static double shearFactor(final double altitude, final double perHundred,
            final double maxAltitude) {
        if (perHundred == 0.0) {
            return 1.0;
        }
        return Math.max(0.0, 1.0 + perHundred * shearAltitude(altitude, maxAltitude) / 100.0);
    }

    /**
     * How far the wind's heading has turned at a given absolute altitude, in degrees.
     *
     * <p>The other half of shear: as well as changing speed with height, the wind veers. The angle is
     * applied to the base wind before anything downstream measures the terrain along it, so the ridge
     * lift at altitude is taken along the wind that actually blows there. Positive turns the wind
     * clockwise from above, the same sense the configured direction uses ({@code 0°} is east,
     * {@code 90°} is south), so a positive rate turns the wind towards the next direction key in
     * sequence. The turn saturates at {@code maxAltitude} along with the speed, so it can never
     * accumulate into a full reversal however high the atmosphere reaches.
     *
     * @param altitude          absolute world Y (blocks)
     * @param degreesPerHundred degrees of turn per 100 blocks of altitude (0 = none)
     * @param maxAltitude       the altitude at which the shear stops turning
     * @return the rotation in degrees; positive turns the wind clockwise when viewed from above
     */
    public static double shearDegrees(final double altitude, final double degreesPerHundred,
            final double maxAltitude) {
        return degreesPerHundred * shearAltitude(altitude, maxAltitude) / 100.0;
    }

    /**
     * Rotates the horizontal part of a vector about world Y, in place.
     *
     * <p>Positive angles turn the vector towards +Z, which is the same sense the configured wind
     * direction already uses ({@code 0°} is east, {@code 90°} is south). Y is left alone.
     *
     * @param vector the vector to rotate (reused)
     * @param degrees the rotation
     * @return {@code vector}
     */
    public static Vector3d rotateXZ(final Vector3d vector, final double degrees) {
        if (degrees == 0.0) {
            return vector;
        }
        final double radians = Math.toRadians(degrees);
        final double cosine = Math.cos(radians);
        final double sine = Math.sin(radians);
        final double x = vector.x * cosine - vector.z * sine;
        final double z = vector.x * sine + vector.z * cosine;
        return vector.set(x, vector.y, z);
    }

    /**
     * Evaluates the field at a point and time.
     *
     * @param pressure    the pressure potential
     * @param gust        the gust noise, or {@code null} to disable gusts
     * @param x           world X (blocks)
     * @param z           world Z (blocks)
     * @param timeSeconds world game time in seconds ({@code level.getGameTime() / 20.0})
     * @param base        the dimension's base wind (horizontal; its Y is ignored)
     * @param weather     rain/storm scaling applied to the perturbations only (1 = no weather);
     *                    the base wind is never scaled by it
     * @param settings    the tunable inputs
     * @param out         the result to fill (reused)
     * @return {@code out}
     */
    public static Result evaluate(final Noise3 pressure, final Noise3 gust, final double x, final double z,
            final double timeSeconds, final Vector3dc base, final double weather,
            final Settings settings, final Result out) {
        return evaluate(pressure, gust, x, z, timeSeconds, base, weather, 0.0, settings, out);
    }

    /**
     * Evaluates the field at a point and time, with the leeward gust boost from stage 4.
     *
     * <p>The perturbations are sized against the base wind itself, which is the right answer as long
     * as the base is the dimension's. Stage 5 callers pass the pre-altitude-profile magnitude to the
     * other overload instead.
     *
     * @param pressure    the pressure potential
     * @param gust        the gust noise, or {@code null} to disable gusts
     * @param x           world X (blocks)
     * @param z           world Z (blocks)
     * @param timeSeconds world game time in seconds ({@code level.getGameTime() / 20.0})
     * @param base        the dimension's base wind (horizontal; its Y is ignored)
     * @param weather     rain/storm scaling applied to the perturbations only (1 = no weather);
     *                    the base wind is never scaled by it
     * @param leeBoost    extra gust amplitude as a fraction of the base gust, from
     *                    {@link #leeGustBoost}; 0 on level or windward ground
     * @param settings    the tunable inputs
     * @param out         the result to fill (reused)
     * @return {@code out}
     */
    public static Result evaluate(final Noise3 pressure, final Noise3 gust, final double x, final double z,
            final double timeSeconds, final Vector3dc base, final double weather, final double leeBoost,
            final Settings settings, final Result out) {
        return evaluate(pressure, gust, x, z, timeSeconds, base, base.length(), weather, leeBoost,
                settings, out);
    }

    /**
     * Evaluates the field at a point and time, with a perturbation scale separate from the base wind.
     *
     * <p>Two magnitudes are in play and they deliberately differ. {@code base} is the wind as it
     * actually blows here — after the jet stream and the wind shear — and it is what goes into the
     * result, what the gusts blow along, and what the clamp's budget is a multiple of. {@code
     * reference} is the dimension's base wind <em>before</em> that altitude profile, and it is what
     * sets how turbulent the air is. A jet stream is laminar: fast, not rough. Letting the
     * perturbations scale with the jet would have made the air at the core several times stormier
     * than at sea level, which is exactly backwards.
     *
     * @param pressure    the pressure potential
     * @param gust        the gust noise, or {@code null} to disable gusts
     * @param x           world X (blocks)
     * @param z           world Z (blocks)
     * @param timeSeconds world game time in seconds ({@code level.getGameTime() / 20.0})
     * @param base        the wind here, after the altitude profile (horizontal; its Y is ignored)
     * @param reference   the base wind's magnitude before the altitude profile, which sizes the
     *                    pressure field and the gusts
     * @param weather     rain/storm scaling applied to the perturbations only (1 = no weather);
     *                    the base wind is never scaled by it
     * @param leeBoost    extra gust amplitude as a fraction of the base gust, from
     *                    {@link #leeGustBoost}; 0 on level or windward ground
     * @param settings    the tunable inputs
     * @param out         the result to fill (reused)
     * @return {@code out}
     */
    public static Result evaluate(final Noise3 pressure, final Noise3 gust, final double x, final double z,
            final double timeSeconds, final Vector3dc base, final double reference, final double weather,
            final double leeBoost, final Settings settings, final Result out) {
        out.reset();

        final double baseMag = base.length();
        out.total.set(base.x(), 0.0, base.z());
        if (baseMag <= 0.0) {
            return out;
        }

        final double nx = x / settings.fieldScale();
        final double nz = z / settings.fieldScale();
        final double nt = timeSeconds / settings.fieldPeriod();

        final double gx = pressure.at(nx + GRAD_STEP, nz, nt) - pressure.at(nx - GRAD_STEP, nz, nt);
        final double gz = pressure.at(nx, nz + GRAD_STEP, nt) - pressure.at(nx, nz - GRAD_STEP, nt);

        final double gain = settings.strength() * reference / GRAD_REFERENCE;
        out.field.set(-gz * gain, 0.0, gx * gain);
        out.field.mul(weather);
        out.total.add(out.field);

        // Gusts: along the local (base + field) wind, with a small lateral component.
        if (gust != null && settings.gustAmplitude() > 0.0) {
            final double dirLen = Math.hypot(out.total.x, out.total.z);
            if (dirLen > 1e-9) {
                final double dirX = out.total.x / dirLen;
                final double dirZ = out.total.z / dirLen;
                final double ngx = x / settings.gustScale();
                final double ngz = z / settings.gustScale();
                final double ngt = timeSeconds / settings.gustPeriod();
                final double along = gust.at(ngx, ngz, ngt);
                final double across = gust.at(ngx + GUST_LATERAL_OFFSET_X, ngz + GUST_LATERAL_OFFSET_Z, ngt);
                // Fade the gust out as the local wind approaches zero, so it cannot swing through an
                // undefined direction where the perturbation cancels the base. Exactly 1 above the
                // threshold, so ordinary wind is untouched.
                final double fade = reference > 0.0
                        ? smoothstep(dirLen / (GUST_CALM_FADE * reference))
                        : 0.0;
                final double amp = settings.gustAmplitude() * reference * weather * (1.0 + leeBoost) * fade;
                final double lateral = settings.gustLateralFraction() * across;
                out.gust.set((dirX * along - dirZ * lateral) * amp, 0.0, (dirZ * along + dirX * lateral) * amp);
                out.total.add(out.gust);
            }
        }

        final double cap = settings.maxMultiplier() * baseMag;
        final double mag2 = out.total.lengthSquared();
        if (mag2 > cap * cap) {
            out.total.mul(cap / Math.sqrt(mag2));
            out.capped = true;
        }

        return out;
    }
}
