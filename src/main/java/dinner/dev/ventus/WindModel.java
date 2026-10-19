package dinner.dev.ventus;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * The pressure-field wind model: a scalar noise potential whose rotated gradient perturbs the
 * dimension's base wind, plus gusts and weather scaling. Pure arithmetic over JOML, no Minecraft
 * types, so it unit-tests cleanly; the world- and config-aware state lives in {@link WindField}.
 */
public final class WindModel {
    private WindModel() {
    }

    /**
     * A 3D scalar noise field. Vanilla's {@code SimplexNoise} in {@link WindField}, a cheap stand-in
     * in tests.
     */
    @FunctionalInterface
    public interface Noise3 {
        double at(double x, double y, double z);
    }

    /** Central-difference step, in noise space (the potential's own units, before scaling). */
    private static final double GRAD_STEP = 0.1;

    /**
     * Mean |rotated gradient| of vanilla's simplex noise over {@link #GRAD_STEP} ({@code 0.4163}).
     * Dividing by it makes a typical perturbation the size of the base wind.
     */
    private static final double GRAD_REFERENCE = 0.4163;

    /** Offset in gust-noise space, for a second decorrelated scalar used by the lateral component. */
    private static final double GUST_LATERAL_OFFSET_X = 5.0;
    private static final double GUST_LATERAL_OFFSET_Z = -2.0;

    /**
     * Local wind, as a fraction of the base, below which gusts fade out. Keeps the gust from
     * swinging through an undefined direction where the perturbation cancels the base.
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
     * Scales the field and gusts by the dimension's rain and thunder levels (1 = no weather). The
     * base wind is never scaled. Vanilla's continuous levels fade in over ~5 s, so the wind does too.
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
     * Upward speed of a thermal at one point, blocks/second: sun x biome warmth x dryness x bubble
     * noise, so the ceiling is {@code strength} and between bubbles the air simply sits still.
     */
    public static double thermal(final double noise, final double daylight, final double warmth,
            final double dryness, final double strength) {
        final double bubble = Math.min(1.0, Math.max(0.0, noise));
        return strength * daylight * warmth * dryness * bubble;
    }

    /**
     * Vertical speed the terrain forces on the air, blocks/second: {@code horizontalSpeed * slope *
     * strength}. {@code horizontalSpeed} must be signed along the slope's axis (see
     * {@link #alongBaseWind}), so a face the wind has reversed away from sinks instead of lifting.
     */
    public static double orographic(final double horizontalSpeed, final double slope, final double strength) {
        return horizontalSpeed * slope * strength;
    }

    /**
     * The local wind's component along the prevailing base wind: positive with the flow, negative
     * once the perturbations have turned it around. Gives ridge lift its sign.
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
     * Extra gust amplitude on the lee side of a slope, as a fraction of the base gust (0 = none).
     * Windward slopes get nothing; clamped so a sheer cliff cannot run away.
     */
    public static double leeGustBoost(final double slope, final double boost) {
        if (slope >= 0.0) {
            return 0.0;
        }
        return boost * Math.min(1.0, -slope);
    }

    /**
     * The height of the ground at one column, {@link Double#NaN} when unknown. For orographic lift
     * the caller supplies the surface the air actually flows over (see {@code WindField.surfaceAt}).
     */
    @FunctionalInterface
    public interface ColumnHeight {
        double at(double x, double z);
    }

    /**
     * Rise-over-run of the ground along a direction, clamped to {@code [-1, 1]} so a sheer wall
     * cannot become an absurd updraft. NaN when either probe column is unknown; the direction is
     * normalised internally.
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
     * Keeps a finished wind within {@code maxMultiplier * prevailing}, scaling the whole vector so
     * the heading never changes; a non-finite component zeroes it instead of clamping.
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
     * Fraction of a terrain-locked effect surviving above the ground: exponential decay with
     * {@code falloff} as the e-folding distance. Thermals are not decayed.
     */
    public static double heightDecay(final double heightAboveGround, final double falloff) {
        if (falloff <= 0.0 || heightAboveGround <= 0.0) {
            return 1.0;
        }
        return Math.exp(-heightAboveGround / falloff);
    }

    /**
     * One step of exponential smoothing towards {@code target} with time constant {@code seconds},
     * independent of frame rate. Used on the terrain terms so they ease as the sampled columns
     * change; gusts are left quick.
     */
    public static double approach(final double current, final double target, final double seconds,
            final double deltaSeconds) {
        if (seconds <= 0.0) {
            return target;
        }
        return current + (target - current) * (1.0 - Math.exp(-deltaSeconds / seconds));
    }

    /** Hermite smoothstep on {@code [0, 1]}, zero slope at both ends. */
    public static double smoothstep(final double t) {
        final double clamped = Math.min(1.0, Math.max(0.0, t));
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }

    /**
     * Jet-stream strength at an absolute altitude: a smoothstep band around {@code core}, up to
     * {@code 1 + strength} at the core and 1 outside. Scales magnitude only; the jet never turns
     * the wind.
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
     * Altitude the shear is measured from, clamped to {@code [0, maxAltitude]} so the turn and the
     * speed saturate, and the deep dark below Y 0 reads as ground level.
     */
    public static double shearAltitude(final double altitude, final double maxAltitude) {
        return Math.min(Math.max(0.0, altitude), Math.max(0.0, maxAltitude));
    }

    /**
     * Speed multiplier from wind shear: additive {@code perHundred} per 100 blocks, floored so a
     * negative rate can calm the air but never reverse it.
     */
    public static double shearFactor(final double altitude, final double perHundred,
            final double maxAltitude) {
        if (perHundred == 0.0) {
            return 1.0;
        }
        return Math.max(0.0, 1.0 + perHundred * shearAltitude(altitude, maxAltitude) / 100.0);
    }

    /**
     * Degrees the wind has veered at an altitude ({@code degreesPerHundred} per 100 blocks; same
     * sense as the configured direction, positive clockwise from above). Applied to the base wind
     * before the terrain slope is measured along it.
     */
    public static double shearDegrees(final double altitude, final double degreesPerHundred,
            final double maxAltitude) {
        return degreesPerHundred * shearAltitude(altitude, maxAltitude) / 100.0;
    }

    /**
     * Rotates the horizontal part of a vector about Y in place; positive towards +Z (0 deg east,
     * 90 deg south).
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

    /** Evaluates the field at a point and time into {@code out} (reused). */
    public static Result evaluate(final Noise3 pressure, final Noise3 gust, final double x, final double z,
            final double timeSeconds, final Vector3dc base, final double weather,
            final Settings settings, final Result out) {
        return evaluate(pressure, gust, x, z, timeSeconds, base, weather, 0.0, settings, out);
    }

    /** Evaluates the field with a leeward gust boost; the perturbations are sized against {@code base}. */
    public static Result evaluate(final Noise3 pressure, final Noise3 gust, final double x, final double z,
            final double timeSeconds, final Vector3dc base, final double weather, final double leeBoost,
            final Settings settings, final Result out) {
        return evaluate(pressure, gust, x, z, timeSeconds, base, base.length(), weather, leeBoost,
                settings, out);
    }

    /**
     * The main evaluator. {@code base} is the wind as it blows here (after the altitude profile);
     * {@code reference} is the dimension's base before it. Gusts and the pressure field are sized
     * against {@code reference}, so a jet stream is fast air rather than rough air.
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
                // Fades out as the local wind approaches zero; exactly 1 above the threshold.
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
