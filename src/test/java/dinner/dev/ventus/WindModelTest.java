package dinner.dev.ventus;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WindModel}. These run without Minecraft: the model only uses JOML, and the
 * noise is supplied through the {@link WindModel.Noise3} interface. A smooth analytic noise stands in
 * for Sable's {@code SimplexNoise} so the tests are deterministic.
 */
class WindModelTest {

    private static final Vector3d BASE = new Vector3d(3.0, 0.0, 0.0);

    /** A smooth, deterministic stand-in for simplex noise. */
    private static double smooth(double x, double y, double z) {
        return Math.sin(0.9 * x) + Math.cos(1.1 * z) + 0.3 * Math.sin(0.5 * y + 0.2 * z);
    }

    private static WindModel.Settings settings(final double scale, final double period,
            final double strength, final double cap) {
        return new WindModel.Settings(scale, period, strength, cap);
    }

    @Test
    void zeroBaseGivesZeroWind() {
        final WindModel.Result out = WindModel.evaluate(WindModelTest::smooth, 123.0, -456.0, 7.0,
                new Vector3d(0.0, 0.0, 0.0), settings(1500, 1200, 1.0, 3.0), new WindModel.Result());
        assertEquals(0.0, out.total.lengthSquared(), 1e-12);
    }

    @Test
    void zeroStrengthKeepsTheBaseWindExactly() {
        final WindModel.Result out = WindModel.evaluate(WindModelTest::smooth, 500.0, 250.0, 33.0,
                BASE, settings(1500, 1200, 0.0, 3.0), new WindModel.Result());
        assertEquals(BASE.x, out.total.x, 1e-12);
        assertEquals(BASE.y, out.total.y, 1e-12);
        assertEquals(BASE.z, out.total.z, 1e-12);
    }

    @Test
    void windIsCappedAtMaxMultiplier() {
        // A noise with a huge slope forces the perturbation far past the cap.
        final WindModel.Noise3 steep = (x, y, z) -> 1000.0 * x;
        final WindModel.Result out = WindModel.evaluate(steep, 10.0, 10.0, 0.0, BASE,
                settings(1500, 1200, 1.0, 3.0), new WindModel.Result());
        assertTrue(out.capped, "expected the cap to engage");
        assertEquals(3.0 * BASE.length(), out.total.length(), 1e-9);
    }

    @Test
    void outputIsAlwaysFinite() {
        final WindModel.Result out = new WindModel.Result();
        for (int i = 0; i < 2000; i++) {
            final double x = i * 37.7 - 40000.0;
            final double z = i * -19.3 + 25000.0;
            final double t = i * 0.5;
            WindModel.evaluate(WindModelTest::smooth, x, z, t, BASE, settings(1500, 1200, 1.0, 3.0), out);
            assertFalse(Double.isNaN(out.total.x) || Double.isInfinite(out.total.x), "x at " + i);
            assertFalse(Double.isNaN(out.total.z) || Double.isInfinite(out.total.z), "z at " + i);
            assertTrue(out.total.length() <= 3.0 * BASE.length() + 1e-9, "cap at " + i);
        }
    }

    @Test
    void fieldIsContinuousAlongALine() {
        // The whole point of a potential-based field: no jumps. Walk a line and check that the wind
        // never moves more than a small fraction of its own magnitude per half-block.
        final WindModel.Settings s = settings(1500, 1200, 1.0, 3.0);
        final WindModel.Result out = new WindModel.Result();
        final Vector3d prev = new Vector3d();
        double maxStep = 0.0;

        WindModel.evaluate(WindModelTest::smooth, 0.0, 100.0, 0.0, BASE, s, out);
        for (double x = 0.5; x <= 6000.0; x += 0.5) {
            WindModel.evaluate(WindModelTest::smooth, x, 100.0, 0.0, BASE, s, out);
            if (x > 0.5) {
                maxStep = Math.max(maxStep, prev.distance(out.total));
            }
            prev.set(out.total);
        }

        assertTrue(maxStep < 0.02 * BASE.length(),
                "wind jumped " + maxStep + " per half-block (should be smooth)");
    }

    @Test
    void isDeterministic() {
        final WindModel.Result a = WindModel.evaluate(WindModelTest::smooth, 42.0, 84.0, 12.0, BASE,
                settings(1500, 1200, 1.0, 3.0), new WindModel.Result());
        final Vector3d first = new Vector3d(a.total);
        final WindModel.Result b = WindModel.evaluate(WindModelTest::smooth, 42.0, 84.0, 12.0, BASE,
                settings(1500, 1200, 1.0, 3.0), new WindModel.Result());
        assertEquals(first.x, b.total.x, 0.0);
        assertEquals(first.z, b.total.z, 0.0);
    }
}
