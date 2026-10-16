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
 * prevailing direction always dominates and the field only deflects and modulates it.
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
 * field and the gusts but never to the base wind, so a storm makes the air rougher without shifting
 * the prevailing direction or its average strength.
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
     */
    public record Settings(double fieldScale, double fieldPeriod, double strength, double maxMultiplier,
            double gustScale, double gustPeriod, double gustAmplitude, double gustLateralFraction) {
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

        final double gain = settings.strength() * baseMag / GRAD_REFERENCE;
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
                final double amp = settings.gustAmplitude() * baseMag * weather;
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
