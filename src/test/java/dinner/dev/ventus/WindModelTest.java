package dinner.dev.ventus;

import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WindModel}. These run without Minecraft: the model only uses JOML, and the
 * noise is supplied through the {@link WindModel.Noise3} interface. Smooth analytic noise stands in
 * for Sable's {@code SimplexNoise} so the tests are deterministic.
 */
class WindModelTest {

    private static final Vector3d BASE = new Vector3d(3.0, 0.0, 0.0);

    /** Dry weather: the multiplier where neither field nor gust is scaled. */
    private static final double DRY = 1.0;
    /** A thunderstorm: rainMultiplier * thunderMultiplier from the default config. */
    private static final double STORM = 2.4;

    /** The config defaults {@link #DRY} and {@link #STORM} are built from. */
    private static final double RAIN_MULT = 1.5;
    private static final double THUNDER_MULT = 1.6;

    /** A smooth, deterministic stand-in for the pressure noise. */
    private static double pressure(double x, double y, double z) {
        return Math.sin(0.9 * x) + Math.cos(1.1 * z) + 0.3 * Math.sin(0.5 * y + 0.2 * z);
    }

    /** An independent, smooth, deterministic stand-in for the gust noise. */
    private static double gust(double x, double y, double z) {
        return Math.sin(1.7 * x + 0.4 * z) * Math.cos(0.9 * z) + 0.4 * Math.sin(2.3 * z + 0.5 * y);
    }

    /** Settings with the config defaults for the gust parameters. */
    private static WindModel.Settings settings(final double scale, final double period,
            final double strength, final double cap) {
        return new WindModel.Settings(scale, period, strength, cap, 150.0, 120.0, 0.35, 0.3);
    }

    private static WindModel.Result evaluate(final WindModel.Noise3 pressure, final WindModel.Noise3 gust,
            final double x, final double z, final double time, final Vector3dc base, final double weather,
            final WindModel.Settings settings) {
        return WindModel.evaluate(pressure, gust, x, z, time, base, weather, settings,
                new WindModel.Result());
    }

    @Test
    void zeroBaseGivesZeroWind() {
        final WindModel.Result out = evaluate(WindModelTest::pressure, WindModelTest::gust,
                123.0, -456.0, 7.0, new Vector3d(0.0, 0.0, 0.0), DRY, settings(1500, 1200, 1.0, 3.0));
        assertEquals(0.0, out.total.lengthSquared(), 1e-12);
    }

    @Test
    void zeroStrengthKeepsTheBaseWindExactlyWhenGustsAreOff() {
        final WindModel.Result out = evaluate(WindModelTest::pressure, null,
                500.0, 250.0, 33.0, BASE, DRY, settings(1500, 1200, 0.0, 3.0));
        assertEquals(BASE.x, out.total.x, 1e-12);
        assertEquals(BASE.y, out.total.y, 1e-12);
        assertEquals(BASE.z, out.total.z, 1e-12);
    }

    @Test
    void zeroGustAmplitudeLeavesTheGustComponentEmpty() {
        final WindModel.Settings s = new WindModel.Settings(1500, 1200, 1.0, 3.0, 150, 120, 0.0, 0.3);
        final WindModel.Result out = evaluate(WindModelTest::pressure, WindModelTest::gust,
                500.0, 250.0, 33.0, BASE, DRY, s);
        assertEquals(0.0, out.gust.lengthSquared(), 1e-12);
    }

    @Test
    void weatherScalesThePerturbationsButNotTheBaseWind() {
        // A huge cap keeps this test out of the clamp, so both perturbations scale exactly.
        final WindModel.Settings s = new WindModel.Settings(1500, 1200, 1.0, 50.0, 150, 120, 0.35, 0.3);
        final WindModel.Result dry = evaluate(WindModelTest::pressure, WindModelTest::gust,
                500.0, 250.0, 12.0, BASE, DRY, s);
        final WindModel.Result storm = evaluate(WindModelTest::pressure, WindModelTest::gust,
                500.0, 250.0, 12.0, BASE, STORM, s);

        assertFalse(dry.capped, "cap must not engage for this test");
        assertFalse(storm.capped, "cap must not engage for this test");

        assertEquals(dry.field.x * STORM, storm.field.x, 1e-9);
        assertEquals(dry.field.z * STORM, storm.field.z, 1e-9);

        // The gust amplitude scales with the weather too. Its *direction* is taken from the local
        // wind, which the scaled field has already turned, so only the magnitude is comparable — and
        // because the along/lateral pair sits in an orthonormal frame, |gust| is amp * sqrt(a^2+b^2).
        assertEquals(dry.gust.length() * STORM, storm.gust.length(), 1e-9);

        // Whatever is left after removing field and gust must still be the plain base wind.
        final double dryBase = dry.total.x - dry.field.x - dry.gust.x;
        final double stormBase = storm.total.x - storm.field.x - storm.gust.x;
        assertEquals(dryBase, stormBase, 1e-9);
        assertEquals(BASE.x, stormBase, 1e-9);
    }

    @Test
    void weatherScaleRampsWithTheRainLevelInsteadOfStepping() {
        // The rain level fades by 0.01 per tick in vanilla, so feeding it straight into the formula
        // must produce an equally gradual wind. A boolean "is it raining?" would have stepped.
        assertEquals(DRY, WindModel.weatherScale(RAIN_MULT, THUNDER_MULT, 0.0, 0.0), 1e-12);
        assertEquals(RAIN_MULT, WindModel.weatherScale(RAIN_MULT, THUNDER_MULT, 1.0, 0.0), 1e-12);

        double previous = WindModel.weatherScale(RAIN_MULT, THUNDER_MULT, 0.0, 0.0);
        double maxStep = 0.0;
        for (int i = 1; i <= 100; i++) {
            final double rain = i / 100.0;
            final double scale = WindModel.weatherScale(RAIN_MULT, THUNDER_MULT, rain, 0.0);
            assertTrue(scale >= previous, "weather must never ease off while it rains harder");
            maxStep = Math.max(maxStep, scale - previous);
            previous = scale;
        }
        assertTrue(maxStep < 0.01, "weather jumped " + maxStep + " per 1% of rain (should ramp)");
    }

    @Test
    void weatherScaleIsFullStormOnlyWhenBothLevelsAreFull() {
        // Half rain is halfway to rainMultiplier, not all the way there.
        assertEquals(1.25, WindModel.weatherScale(RAIN_MULT, THUNDER_MULT, 0.5, 0.0), 1e-12);
        // Full rain, no thunder: just the rain multiplier.
        assertEquals(RAIN_MULT, WindModel.weatherScale(RAIN_MULT, THUNDER_MULT, 1.0, 0.0), 1e-12);
        // Both full: the two multipliers stack, which is the 2.4 used above.
        assertEquals(STORM, WindModel.weatherScale(RAIN_MULT, THUNDER_MULT, 1.0, 1.0), 1e-12);
        // Vanilla gates the thunder level behind the rain level, so this combination is what a dry
        // dimension with a leftover thunder level would look like — and it must stay dry.
        assertEquals(DRY, WindModel.weatherScale(RAIN_MULT, THUNDER_MULT, 0.0, 0.7), 1e-12);
    }

    @Test
    void gustsStayCappedEvenInAStorm() {
        final WindModel.Settings s = new WindModel.Settings(1500, 1200, 1.0, 3.0, 150, 120, 10.0, 0.3);
        final WindModel.Result out = new WindModel.Result();
        for (int i = 0; i < 2000; i++) {
            WindModel.evaluate(WindModelTest::pressure, WindModelTest::gust, i * 11.0, i * -7.0, i * 0.5,
                    BASE, STORM, s, out);
            assertTrue(out.total.length() <= 3.0 * BASE.length() + 1e-9, "cap at " + i);
        }
    }

    @Test
    void windIsCappedAtMaxMultiplier() {
        // A noise with a huge slope forces the perturbation far past the cap.
        final WindModel.Noise3 steep = (x, y, z) -> 1000.0 * x;
        final WindModel.Result out = evaluate(steep, null, 10.0, 10.0, 0.0, BASE, DRY,
                settings(1500, 1200, 1.0, 3.0));
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
            WindModel.evaluate(WindModelTest::pressure, WindModelTest::gust, x, z, t, BASE, STORM,
                    settings(1500, 1200, 1.0, 3.0), out);
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

        WindModel.evaluate(WindModelTest::pressure, WindModelTest::gust, 0.0, 100.0, 0.0, BASE, DRY, s, out);
        for (double x = 0.5; x <= 6000.0; x += 0.5) {
            WindModel.evaluate(WindModelTest::pressure, WindModelTest::gust, x, 100.0, 0.0, BASE, DRY, s, out);
            if (x > 0.5) {
                maxStep = Math.max(maxStep, prev.distance(out.total));
            }
            prev.set(out.total);
        }

        assertTrue(maxStep < 0.05 * BASE.length(),
                "wind jumped " + maxStep + " per half-block (should be smooth)");
    }

    @Test
    void isDeterministic() {
        final WindModel.Result a = evaluate(WindModelTest::pressure, WindModelTest::gust,
                42.0, 84.0, 12.0, BASE, DRY, settings(1500, 1200, 1.0, 3.0));
        final Vector3d first = new Vector3d(a.total);
        final WindModel.Result b = evaluate(WindModelTest::pressure, WindModelTest::gust,
                42.0, 84.0, 12.0, BASE, DRY, settings(1500, 1200, 1.0, 3.0));
        assertEquals(first.x, b.total.x, 0.0);
        assertEquals(first.z, b.total.z, 0.0);
    }
}
