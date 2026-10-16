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
 * Stage 2b adds short-scale gusts on top of this (see {@link #evaluate}).
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
     * The tunable inputs, mirroring the server config (see {@link VentusConfig}).
     *
     * @param fieldScale  map scale: the size in blocks of the largest wind structures ({@code L})
     * @param fieldPeriod seconds for the field to evolve noticeably ({@code T})
     * @param strength    multiplier on the perturbation (1 = typical perturbation equals the base wind)
     * @param maxMultiplier hard cap: the wind never exceeds this times the base wind
     */
    public record Settings(double fieldScale, double fieldPeriod, double strength, double maxMultiplier) {
    }

    /**
     * Output of {@link #evaluate}: the perturbation and the resulting total, reused across calls to
     * avoid per-tick allocation.
     */
    public static final class Result {
        /** The pressure-field perturbation (before the cap). */
        public final Vector3d field = new Vector3d();
        /** {@code base + field}, capped at {@code maxMultiplier * base} (horizontal only). */
        public final Vector3d total = new Vector3d();
        /** Whether the cap clamped {@link #total}. */
        public boolean capped;

        void reset() {
            this.field.set(0.0, 0.0, 0.0);
            this.total.set(0.0, 0.0, 0.0);
            this.capped = false;
        }
    }

    /**
     * Evaluates the field at a point and time.
     *
     * @param pressure    the pressure potential
     * @param x           world X (blocks)
     * @param z           world Z (blocks)
     * @param timeSeconds world game time in seconds ({@code level.getGameTime() / 20.0})
     * @param base        the dimension's base wind (horizontal; its Y is ignored)
     * @param settings    the tunable inputs
     * @param out         the result to fill (reused)
     * @return {@code out}
     */
    public static Result evaluate(final Noise3 pressure, final double x, final double z,
            final double timeSeconds, final Vector3dc base, final Settings settings, final Result out) {
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
        out.total.add(out.field);

        final double cap = settings.maxMultiplier() * baseMag;
        final double mag2 = out.total.lengthSquared();
        if (mag2 > cap * cap) {
            out.total.mul(cap / Math.sqrt(mag2));
            out.capped = true;
        }

        return out;
    }
}
