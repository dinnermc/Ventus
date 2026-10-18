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
        return new WindModel.Settings(scale, period, strength, cap, 150.0, 120.0, 0.35, 0.3, 0.0);
    }

    private static WindModel.Result evaluate(final WindModel.Noise3 pressure, final WindModel.Noise3 gust,
            final double x, final double z, final double time, final Vector3dc base, final double weather,
            final WindModel.Settings settings) {
        return evaluate(pressure, gust, x, z, time, base, weather, 0.0, settings);
    }

    private static WindModel.Result evaluate(final WindModel.Noise3 pressure, final WindModel.Noise3 gust,
            final double x, final double z, final double time, final Vector3dc base, final double weather,
            final double leeBoost, final WindModel.Settings settings) {
        return WindModel.evaluate(pressure, gust, x, z, time, base, weather, leeBoost, settings,
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
        final WindModel.Settings s = new WindModel.Settings(1500, 1200, 1.0, 3.0, 150, 120, 0.0, 0.3, 0.0);
        final WindModel.Result out = evaluate(WindModelTest::pressure, WindModelTest::gust,
                500.0, 250.0, 33.0, BASE, DRY, s);
        assertEquals(0.0, out.gust.lengthSquared(), 1e-12);
    }

    @Test
    void weatherScalesThePerturbationsButNotTheBaseWind() {
        // A huge cap keeps this test out of the clamp, so both perturbations scale exactly.
        final WindModel.Settings s = new WindModel.Settings(1500, 1200, 1.0, 50.0, 150, 120, 0.35, 0.3, 0.0);
        final WindModel.Result dry = evaluate(WindModelTest::pressure, WindModelTest::gust,
                500.0, 250.0, 12.0, BASE, DRY, s);
        final WindModel.Result storm = evaluate(WindModelTest::pressure, WindModelTest::gust,
                500.0, 250.0, 12.0, BASE, STORM, s);

        assertFalse(dry.capped, "cap must not engage for this test");
        assertFalse(storm.capped, "cap must not engage for this test");

        assertEquals(dry.field.x * STORM, storm.field.x, 1e-9);
        assertEquals(dry.field.z * STORM, storm.field.z, 1e-9);

        // The gust ratio below only holds where the calm-point fade is exactly 1 in *both* runs. That
        // is true here by the choice of sample point, not by construction - so state it. If someone
        // moves this point somewhere the storm field cancels the base, one fade starts shrinking and
        // the ratio would fail with a bare number instead of an explanation.
        // Threshold = WindModel.GUST_CALM_FADE * reference.
        for (final WindModel.Result r : new WindModel.Result[] { dry, storm }) {
            final double localLen = Math.hypot(BASE.x + r.field.x, BASE.z + r.field.z);
            assertTrue(localLen >= 0.5 * BASE.length(),
                    "sample point (500, 250) is too calm for this assertion: |local wind| = "
                            + localLen + ", fade threshold = " + 0.5 * BASE.length()
                            + " - pick somewhere with ordinary wind");
        }

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
    void thermalNeedsSunWarmthAndDryGround() {
        final double strength = 4.0;
        // Every gate closed: night, cold biome, overcast, or negative noise.
        assertEquals(0.0, WindModel.thermal(1.0, 0.0, 1.0, 1.0, strength), 1e-12);
        assertEquals(0.0, WindModel.thermal(1.0, 1.0, 0.0, 1.0, strength), 1e-12);
        assertEquals(0.0, WindModel.thermal(1.0, 1.0, 1.0, 0.0, strength), 1e-12);
        // Only the positive half of the noise lifts, so the air between bubbles is still, not sinking.
        assertEquals(0.0, WindModel.thermal(-1.0, 1.0, 1.0, 1.0, strength), 1e-12);
        assertEquals(0.0, WindModel.thermal(-0.5, 1.0, 1.0, 1.0, strength), 1e-12);
        // All gates open at the top of a bubble: the configured ceiling.
        assertEquals(strength, WindModel.thermal(1.0, 1.0, 1.0, 1.0, strength), 1e-12);
        // Half a bubble is half the ceiling.
        assertEquals(strength / 2.0, WindModel.thermal(0.5, 1.0, 1.0, 1.0, strength), 1e-12);
        // The gates multiply: half sun over warm ground is half a thermal.
        assertEquals(strength / 2.0, WindModel.thermal(1.0, 0.5, 1.0, 1.0, strength), 1e-12);
        // Nothing can push it past the ceiling, whatever the noise does.
        assertEquals(strength, WindModel.thermal(99.0, 1.0, 1.0, 1.0, strength), 1e-12);
    }

    @Test
    void orographicLiftIsWindSpeedTimesSlope() {
        // Air moving at 10 m/s over ground climbing at 0.2 climbs at 2 m/s: ridge lift.
        assertEquals(2.0, WindModel.orographic(10.0, 0.2, 1.0), 1e-12);
        // The same slope facing away from the wind is the sink behind the ridge.
        assertEquals(-2.0, WindModel.orographic(10.0, -0.2, 1.0), 1e-12);
        // Flat ground, or no wind, forces nothing vertical.
        assertEquals(0.0, WindModel.orographic(10.0, 0.0, 1.0), 1e-12);
        assertEquals(0.0, WindModel.orographic(0.0, 0.5, 1.0), 1e-12);
        // The strength knob scales the physical value.
        assertEquals(1.0, WindModel.orographic(10.0, 0.2, 0.5), 1e-12);
    }

    @Test
    void anInvertedLocalWindTurnsRidgeLiftIntoSink() {
        // The slope is measured along +base (here +X), so positive means the hill rises toward base.
        final double slope = 0.2;

        final double withTheWind = WindModel.alongBaseWind(3.0, 0.0, 3.0, 0.0);
        final double against = WindModel.alongBaseWind(3.0, 0.0, -3.0, 0.0);
        assertEquals(3.0, withTheWind, 1e-12);
        assertEquals(-3.0, against, 1e-12);

        // Air running as base does climbs that face: 3 m/s over ground rising 0.2 per block lifts
        // the air at 0.6 m/s (speed x slope x strength), a number worked out here rather than read
        // back out of the call under test.
        assertEquals(0.6, WindModel.orographic(withTheWind, slope, 1.0), 1e-12);

        // Same hill, same speed — the perturbations have only turned the air around, so it is now
        // pouring off that face: -3 x 0.2 x 1 = -0.6, equal and opposite. An unsigned speed (a
        // hypotenuse) would have reported lift in both columns, because the sign would come from
        // the terrain alone.
        assertEquals(-0.6, WindModel.orographic(against, slope, 1.0), 1e-12);

        // Where the two cancel the component is zero, and it crosses zero rather than jumping.
        assertEquals(0.0, WindModel.alongBaseWind(3.0, 0.0, 0.0, 0.0), 1e-12);
        assertEquals(0.0,
                WindModel.alongBaseWind(3.0, 0.0, 1e-6, 0.0)
                        + WindModel.alongBaseWind(3.0, 0.0, -1e-6, 0.0),
                1e-12);
    }

    @Test
    void theAlongBaseWindComponentIsAProjectionOntoTheBaseDirection() {
        // Aligned with a base of (3, 4): the whole length of the local wind, signed as the base is.
        assertEquals(15.0, WindModel.alongBaseWind(3.0, 4.0, 9.0, 12.0), 1e-12);
        assertEquals(-15.0, WindModel.alongBaseWind(3.0, 4.0, -9.0, -12.0), 1e-12);
        // Perpendicular to it: the air crosses the measured axis without traversing it.
        assertEquals(0.0, WindModel.alongBaseWind(3.0, 4.0, -4.0, 3.0), 1e-12);
        // A base with no direction leaves no axis to project onto; the slope is NaN there anyway.
        assertEquals(0.0, WindModel.alongBaseWind(0.0, 0.0, 7.0, -2.0), 0.0);
    }

    @Test
    void leeGustBoostIsOnlyForTheLeeSide() {
        // Windward and level ground get the smooth lift, no extra turbulence.
        assertEquals(0.0, WindModel.leeGustBoost(0.5, 2.0), 1e-12);
        assertEquals(0.0, WindModel.leeGustBoost(0.0, 2.0), 1e-12);
        // A slope of -1 gives the configured boost in full.
        assertEquals(2.0, WindModel.leeGustBoost(-1.0, 2.0), 1e-12);
        // Steeper is clamped, so a one-block cliff cannot run away with it.
        assertEquals(2.0, WindModel.leeGustBoost(-50.0, 2.0), 1e-12);
        // A gentle lee slope gives a gentle boost.
        assertEquals(1.0, WindModel.leeGustBoost(-0.5, 2.0), 1e-12);
    }

    @Test
    void leeBoostRoughensTheGusts() {
        final WindModel.Settings s = new WindModel.Settings(1500, 1200, 1.0, 50.0, 150, 120, 0.35, 0.3, 0.0);
        final WindModel.Result calmLee = evaluate(WindModelTest::pressure, WindModelTest::gust,
                500.0, 250.0, 12.0, BASE, DRY, 0.0, s);
        final WindModel.Result roughLee = evaluate(WindModelTest::pressure, WindModelTest::gust,
                500.0, 250.0, 12.0, BASE, DRY, 1.0, s);

        assertFalse(calmLee.capped, "cap must not engage for this test");
        assertFalse(roughLee.capped, "cap must not engage for this test");

        // Doubling the gust amplitude doubles the gust, and leaves the pressure field alone.
        assertEquals(calmLee.gust.length() * 2.0, roughLee.gust.length(), 1e-9);
        assertEquals(calmLee.field.x, roughLee.field.x, 1e-9);
        assertEquals(calmLee.field.z, roughLee.field.z, 1e-9);
    }

    @Test
    void gustsKeepTheirFullAmplitudeWhereTheWindIsNotCalm() {
        // The calm-point fade has one job: kill the gust where the local wind has cancelled. It must
        // leave ordinary wind alone, or gustAmplitude stops meaning what the config says. So the
        // expected gust here is rebuilt by hand from the same noise calls the model makes, with the
        // fade at exactly 1 — and the test would fail if the fade ever ate into normal wind.
        final WindModel.Settings s = new WindModel.Settings(1500, 1200, 1.0, 50.0, 150, 120, 0.35, 0.3, 0.0);
        final WindModel.Result out = new WindModel.Result();

        // Scan for a point where the local wind is comfortably above the base itself.
        double bestX = 0.0;
        double bestZ = 0.0;
        double bestLen = 0.0;
        for (int i = 0; i < 300; i++) {
            for (int j = 0; j < 300; j++) {
                final double x = i * 13.0;
                final double z = j * 17.0;
                WindModel.evaluate(WindModelTest::pressure, WindModelTest::gust, x, z, 0.0, BASE, DRY, s, out);
                final double len = Math.hypot(BASE.x + out.field.x, BASE.z + out.field.z);
                if (len > bestLen) {
                    bestLen = len;
                    bestX = x;
                    bestZ = z;
                }
            }
        }
        assertTrue(bestLen > BASE.length(), "need a point where the local wind exceeds the base");

        WindModel.evaluate(WindModelTest::pressure, WindModelTest::gust, bestX, bestZ, 0.0, BASE, DRY, s, out);

        final double localX = BASE.x + out.field.x;
        final double localZ = BASE.z + out.field.z;
        final double dirLen = Math.hypot(localX, localZ);
        final double dirX = localX / dirLen;
        final double dirZ = localZ / dirLen;

        final double ngx = bestX / 150.0;
        final double ngz = bestZ / 150.0;
        final double along = gust(ngx, ngz, 0.0);
        final double across = gust(ngx + 5.0, ngz - 2.0, 0.0);
        final double lateral = 0.3 * across;
        final double amp = 0.35 * BASE.length() * DRY;

        assertEquals((dirX * along - dirZ * lateral) * amp, out.gust.x, 1e-12, "gust.x");
        assertEquals((dirZ * along + dirX * lateral) * amp, out.gust.z, 1e-12, "gust.z");
    }

    @Test
    void heightDecayKillsTheRidgeLiftWithAltitude() {
        // Full strength at the ground, one e-fold at the configured falloff, and nothing much above.
        assertEquals(1.0, WindModel.heightDecay(0.0, 50.0), 1e-12);
        assertEquals(Math.exp(-1.0), WindModel.heightDecay(50.0, 50.0), 1e-12);
        assertEquals(0.0, WindModel.heightDecay(2000.0, 50.0), 1e-12);

        // Strictly decreasing: a plane above the ridge always feels less than one below it.
        double previous = 1.0;
        for (double height = 10.0; height <= 500.0; height += 10.0) {
            final double decay = WindModel.heightDecay(height, 50.0);
            assertTrue(decay < previous, "decay must keep falling with altitude, at " + height);
            previous = decay;
        }

        // A negative falloff turns the decay off rather than producing a NaN or a blow-up.
        assertEquals(1.0, WindModel.heightDecay(100.0, 0.0), 1e-12);
        assertEquals(1.0, WindModel.heightDecay(100.0, -5.0), 1e-12);
        // Below ground the effect is at full strength, not inverted.
        assertEquals(1.0, WindModel.heightDecay(-20.0, 50.0), 1e-12);
    }

    @Test
    void approachEasesInsteadOfStepping() {
        // The terrain-derived terms are read from integer heights, so they step; this must not.
        final double target = 3.0;
        double value = 0.0;
        double previous = 0.0;
        for (int tick = 0; tick < 300; tick++) {
            value = WindModel.approach(value, target, 1.5, 1.0 / 20.0);
            assertTrue(value >= previous, "must never overshoot backwards");
            assertTrue(value <= target, "must never overshoot the target");
            previous = value;
        }
        // After fifteen seconds — ten time constants — the gap left is exactly e^-10 of the original,
        // which checks the time constant is honoured rather than merely "nearly there".
        assertEquals(3.0 - 3.0 * Math.exp(-10.0), value, 1e-9);

        // A zero time constant is a pass-through, for tuning it out.
        assertEquals(target, WindModel.approach(0.0, target, 0.0, 1.0 / 20.0), 1e-12);

        // The first step is a small fraction, never a jump to the target.
        final double first = WindModel.approach(0.0, target, 1.5, 1.0 / 20.0);
        assertTrue(first > 0.0 && first < target * 0.1, "first step was " + first);
    }

    @Test
    void extremeTermsNeverLeaveTheBudget() {
        // The red safety net: whatever the parameters and the noise do, the horizontal total stays
        // within the cap. Every term that can be turned up to an absurd value is turned up here.
        final WindModel.Noise3 wild = (x, y, z) -> 1.0e6;
        final WindModel.Settings s = new WindModel.Settings(1e-9, 1e-9, 1e6, 3.0, 1e-9, 1e-9, 1e6, 1.0, 1e6);
        final Vector3d hugeBase = new Vector3d(1.0e6, 0.0, 1.0e6);
        final WindModel.Result out = new WindModel.Result();

        for (int i = 0; i < 2000; i++) {
            WindModel.evaluate(wild, wild, i * 1e5, i * -1e5, i * 1e3, hugeBase, 1.0e6, 1.0e6, s, out);
            assertTrue(out.capped, "expected the cap to engage at " + i);
            assertTrue(out.total.length() <= 3.0 * hugeBase.length() + 1e-6, "escaped the budget at " + i);
            assertTrue(Double.isFinite(out.total.x) && Double.isFinite(out.total.z), "NaN at " + i);
        }
    }

    @Test
    void gustsStayCappedEvenInAStorm() {
        final WindModel.Settings s = new WindModel.Settings(1500, 1200, 1.0, 3.0, 150, 120, 10.0, 0.3, 0.0);
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
    void fieldIsContinuousThroughTheWindCalmPointInAStorm() {
        // The gust is oriented along the local (base + field) wind and its amplitude does not shrink
        // as that vector shortens (WindModel: dirLen is only used to normalise, amp comes from
        // reference). So the risk is where the perturbation nearly cancels the base: there the
        // direction is poorly defined and the gust could swing with it.
        //
        // The question is not whether the default noise happens to cancel — it does not, a scan of
        // 160k storm points bottoms out at |local| = 2.48 against |base| = 3.0 — but what the code
        // does when it does. So the strength is driven up until cancellation is unavoidable, the
        // calm point is located, and a quarter-block line is walked through it.
        final WindModel.Settings s = settings(1500, 1200, 8.0, 3.0);
        final WindModel.Result out = new WindModel.Result();

        // 1. Coarse scan for the smallest local wind a storm can produce at this strength.
        double bestLen = Double.MAX_VALUE;
        double bestX = 0.0;
        double bestZ = 0.0;
        for (int i = 0; i < 400; i++) {
            for (int j = 0; j < 400; j++) {
                final double x = i * 11.0;
                final double z = j * 13.0;
                WindModel.evaluate(WindModelTest::pressure, WindModelTest::gust, x, z, 0.0, BASE, STORM, s, out);
                final double len = Math.hypot(BASE.x + out.field.x, BASE.z + out.field.z);
                if (len < bestLen) {
                    bestLen = len;
                    bestX = x;
                    bestZ = z;
                }
            }
        }

        // 2. Walk a quarter-block line through it and measure the largest single step.
        final Vector3d prev = new Vector3d();
        double maxStep = 0.0;
        double worstAt = 0.0;
        final double span = 60.0;

        WindModel.evaluate(WindModelTest::pressure, WindModelTest::gust, bestX - span, bestZ, 0.0,
                BASE, STORM, s, out);
        prev.set(out.total);
        for (double d = -span + 0.25; d <= span; d += 0.25) {
            WindModel.evaluate(WindModelTest::pressure, WindModelTest::gust, bestX + d, bestZ, 0.0,
                    BASE, STORM, s, out);
            final double step = prev.distance(out.total);
            if (step > maxStep) {
                maxStep = step;
                worstAt = d;
            }
            prev.set(out.total);
        }

        System.out.printf("storm calm-point walk: minLocalWind=%.5f at (%.0f, %.0f); "
                        + "maxStep=%.5f (%.1f%% of base) at d=%.1f%n",
                bestLen, bestX, bestZ, maxStep, 100.0 * maxStep / BASE.length(), worstAt);

        assertTrue(maxStep < 0.05 * BASE.length(),
                "wind jumped " + maxStep + " per quarter-block near the calm point at ("
                        + bestX + ", " + bestZ + "), where |local wind| fell to " + bestLen);
    }

    /**
     * A pressure that grows quadratically, so its gradient - and the field - varies linearly.
     * <p>
     * Note the argument order: {@link WindModel.Noise3} is declared {@code at(x, y, z)} but the model
     * samples it as {@code at(nx, nz, nt)}, so the world-z axis arrives in the <em>second</em>
     * parameter and time in the third. Ramping the third one ramps time and leaves the gradient in
     * z at zero, which produces a field of exactly nothing.
     */
    private static double rampPressure(final double x, final double nz, final double time) {
        return 0.5 * nz * nz;
    }

    /**
     * The z at which {@link #rampPressure} cancels the base wind: a coarse scan to bracket the
     * crossing, then a bisection to pin it down. The field varies linearly in z, so the signed
     * local x changes sign exactly once and bisection converges on it.
     */
    private static double rampCalmZ(final WindModel.Settings s, final double weather) {
        final WindModel.Result out = new WindModel.Result();

        // Coarse scan: find the first z where the local wind has gone negative.
        double lo = 0.0;
        double hi = 0.0;
        double prevZ = 0.0;
        double prevX = BASE.x;
        for (double z = 2.0; z <= 3000.0; z += 2.0) {
            WindModel.evaluate(WindModelTest::rampPressure, WindModelTest::gust, 0.0, z, 0.0, BASE, weather, s, out);
            final double localX = BASE.x + out.field.x;
            if (prevX > 0.0 && localX <= 0.0) {
                lo = prevZ;
                hi = z;
                break;
            }
            prevZ = z;
            prevX = localX;
        }
        assertTrue(hi > lo, "the ramp noise must actually cancel the base; local x never changed sign");

        // Bisect until the crossing is narrower than the model's own zero guard (dirLen > 1e-9).
        for (int i = 0; i < 64; i++) {
            final double mid = 0.5 * (lo + hi);
            WindModel.evaluate(WindModelTest::rampPressure, WindModelTest::gust, 0.0, mid, 0.0, BASE, weather, s, out);
            if (BASE.x + out.field.x > 0.0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    @Test
    void theGustDoesNotJumpWhereTheLocalWindCancels() {
        // The default test noise never cancels the base - a 160k-point storm scan bottoms out at
        // |local| = 2.48 against |base| = 3.0 - so this one forces the crossing by hand. A ramp in z
        // gives a field that varies linearly with z, so there is exactly one z where the local wind
        // is zero, and the walk passes straight through it.
        final WindModel.Settings s = settings(1500, 1200, 1.0, 50.0);
        final WindModel.Result out = new WindModel.Result();
        final double calmZ = rampCalmZ(s, STORM);

        final Vector3d prev = new Vector3d();
        double maxStep = 0.0;
        WindModel.evaluate(WindModelTest::rampPressure, WindModelTest::gust, 0.0, calmZ - 20.0, 0.0,
                BASE, STORM, s, out);
        prev.set(out.total);
        for (double z = calmZ - 20.0 + 0.1; z <= calmZ + 20.0; z += 0.1) {
            WindModel.evaluate(WindModelTest::rampPressure, WindModelTest::gust, 0.0, z, 0.0,
                    BASE, STORM, s, out);
            maxStep = Math.max(maxStep, prev.distance(out.total));
            prev.set(out.total);
        }

        System.out.printf("ramp calm-point walk: calmZ=%.1f maxStep=%.5f (%.1f%% of base)%n",
                calmZ, maxStep, 100.0 * maxStep / BASE.length());

        assertTrue(maxStep < 0.05 * BASE.length(),
                "wind jumped " + maxStep + " per tenth-block crossing the calm point at z=" + calmZ);
    }

    @Test
    void theGustIsZeroAndTheTotalFiniteAtTheCalmPoint() {
        // At the crossing itself the local wind has no direction to speak of. The gust must be
        // exactly zero there, and the total finite - not a NaN built from a normalised zero vector.
        final WindModel.Settings s = settings(1500, 1200, 1.0, 50.0);
        final WindModel.Result out = new WindModel.Result();
        final double calmZ = rampCalmZ(s, STORM);

        WindModel.evaluate(WindModelTest::rampPressure, WindModelTest::gust, 0.0, calmZ, 0.0,
                BASE, STORM, s, out);

        assertEquals(0.0, out.gust.length(), 1e-12, "gust at the calm point");
        assertTrue(Double.isFinite(out.total.x) && Double.isFinite(out.total.y)
                && Double.isFinite(out.total.z), "total must stay finite at the calm point");
    }

    @Test
    void theFieldOccasionallyReversesThePrevailingWind() {
        // fieldStrength = 1.0 means the typical deflection is the same size as the base wind, so the
        // perturbation is bound to overtake the prevailing flow at some points and point against it
        // there. The storm multiplier makes that more frequent; it does not make it possible. The
        // number is quoted in the README, so measure it rather than describe it.
        final WindModel.Settings s = settings(1500, 1200, 1.0, 3.0);
        final WindModel.Result out = new WindModel.Result();

        final int samples = 20000;
        int dryReversed = 0;
        int stormReversed = 0;

        for (int i = 0; i < samples; i++) {
            // A lattice wide enough to cover many field scales, with time advancing, so the samples
            // sweep the noise instead of freezing one slice of it.
            final double x = (i % 141) * 37.0;
            final double z = (i / 141) * 53.0;
            final double t = i * 0.37;

            WindModel.evaluate(WindModelTest::pressure, WindModelTest::gust, x, z, t, BASE, DRY, s, out);
            if (out.total.x * BASE.x + out.total.z * BASE.z < 0.0) {
                dryReversed++;
            }

            WindModel.evaluate(WindModelTest::pressure, WindModelTest::gust, x, z, t, BASE, STORM, s, out);
            if (out.total.x * BASE.x + out.total.z * BASE.z < 0.0) {
                stormReversed++;
            }
        }

        final double dryFraction = dryReversed / (double) samples;
        final double stormFraction = stormReversed / (double) samples;
        System.out.printf("reversal: dry=%.1f%% storm=%.1f%%%n", 100.0 * dryFraction, 100.0 * stormFraction);

        assertTrue(stormFraction > dryFraction,
                "a storm must reverse the wind more often than dry air, not less");
        assertTrue(stormFraction < 0.5,
                "the prevailing direction has to still win most of the time, even in a storm: "
                        + stormFraction);
    }

    @Test
    void fieldIsContinuousOverTime() {
        // The same property as the spatial walk, on the other axis: hold the position and advance
        // game time a second at a time. A step here would read as a gust arriving instantly.
        final WindModel.Settings s = settings(1500, 1200, 1.0, 3.0);
        final WindModel.Result out = new WindModel.Result();
        final Vector3d prev = new Vector3d();
        double maxStep = 0.0;

        WindModel.evaluate(WindModelTest::pressure, WindModelTest::gust, 500.0, -800.0, 0.0, BASE, DRY, s, out);
        for (int second = 1; second <= 6000; second++) {
            WindModel.evaluate(WindModelTest::pressure, WindModelTest::gust, 500.0, -800.0, second, BASE, DRY, s, out);
            if (second > 1) {
                maxStep = Math.max(maxStep, prev.distance(out.total));
            }
            prev.set(out.total);
        }

        assertTrue(maxStep < 0.05 * BASE.length(),
                "wind jumped " + maxStep + " per second (should be smooth)");
    }

    @Test
    void fieldIsContinuousWithTheLeewardGustsOn() {
        // Continuity must survive the stage-4 path too: the leeward roughening is a multiplier on
        // the gusts, so if it were stepped or discontinuous the roughest side of a ridge would jump.
        final WindModel.Settings s = settings(1500, 1200, 1.0, 3.0);
        final WindModel.Result out = new WindModel.Result();
        final Vector3d prev = new Vector3d();
        double maxStep = 0.0;

        WindModel.evaluate(WindModelTest::pressure, WindModelTest::gust, 0.0, 100.0, 0.0, BASE, DRY, 2.0, s, out);
        for (double x = 0.5; x <= 6000.0; x += 0.5) {
            WindModel.evaluate(WindModelTest::pressure, WindModelTest::gust, x, 100.0, 0.0, BASE, DRY, 2.0, s, out);
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
        // No state and nothing stored: the same point, time, noise and settings must give the same
        // value on every call, including down the leeward path.
        final WindModel.Settings s = settings(1500, 1200, 1.0, 3.0);
        final Vector3d first = new Vector3d(
                evaluate(WindModelTest::pressure, WindModelTest::gust, 42.0, 84.0, 12.0, BASE, DRY, 1.7, s).total);

        for (int i = 0; i < 50; i++) {
            final Vector3d again = new Vector3d(
                    evaluate(WindModelTest::pressure, WindModelTest::gust, 42.0, 84.0, 12.0, BASE, DRY, 1.7, s).total);
            assertEquals(first.x, again.x, 0.0);
            assertEquals(first.z, again.z, 0.0);
        }
    }

    @Test
    void flatWaterOverASlopingSeabedIsNotARidge() {
        // The air flows over the sea surface, not the seabed. Model a shallow sea whose floor climbs
        // across the probe distance while the water surface stays level. Reading the seabed invents
        // orographic lift under a boat; reading the surface correctly gives none.
        final double probe = 16.0;
        final WindModel.ColumnHeight seabed = (x, z) -> 40.0 + (x / (2.0 * probe)) * 15.0;
        final WindModel.ColumnHeight seaSurface = (x, z) -> 60.0;

        final double overTheFloor = WindModel.slopeAlongWind(seabed, 0.0, 0.0, 1.0, 0.0, probe);
        final double overTheWater = WindModel.slopeAlongWind(seaSurface, 0.0, 0.0, 1.0, 0.0, probe);

        assertTrue(overTheFloor > 0.4, "the seabed does slope: " + overTheFloor);
        assertEquals(0.0, overTheWater, 1e-12, "flat water must read as flat ground");
    }

    @Test
    void aForestEdgeIsNotACliff() {
        // Leaves are excluded from the surface the air is forced over, so a canopy that ends in a
        // step is flat ground to the wind rather than a cliff manufacturing lift.
        final double probe = 16.0;
        final WindModel.ColumnHeight withLeaves = (x, z) -> x < 0.0 ? 70.0 : 90.0;
        final WindModel.ColumnHeight withoutLeaves = (x, z) -> 70.0;

        final double canopyEdge = WindModel.slopeAlongWind(withLeaves, 0.0, 0.0, 1.0, 0.0, probe);
        final double groundBelow = WindModel.slopeAlongWind(withoutLeaves, 0.0, 0.0, 1.0, 0.0, probe);

        assertTrue(canopyEdge > 0.5, "with leaves the canopy edge reads as a steep cliff: " + canopyEdge);
        assertEquals(0.0, groundBelow, 1e-12, "without leaves there is no hill at all");
    }

    @Test
    void slopeIsMeasuredAlongTheGivenDirectionOnly() {
        // A north-south wall must not read as a slope for a wind blowing east-west, and the
        // direction is normalised so the caller may pass any non-zero vector.
        final WindModel.ColumnHeight wall = (x, z) -> z * 0.5;

        assertEquals(0.5, WindModel.slopeAlongWind(wall, 0.0, 0.0, 0.0, 1.0, 16.0), 1e-12);
        assertEquals(0.5, WindModel.slopeAlongWind(wall, 0.0, 0.0, 0.0, 40.0, 16.0), 1e-12);
        assertEquals(0.0, WindModel.slopeAlongWind(wall, 0.0, 0.0, 1.0, 0.0, 16.0), 1e-12);
    }

    @Test
    void theCapKeepsTheWindHeading() {
        // Clipping each component separately would turn the wind exactly when the cap engages, so
        // the strongest gusts would arrive from the wrong direction. Scaling the whole vector cannot.
        final Vector3d wind = new Vector3d(30.0, 40.0, 0.0);
        final double before = wind.length();

        WindModel.clampToBudget(wind, 10.0, 3.0);

        assertEquals(30.0, wind.length(), 1e-9, "the budget is 3 x the prevailing 10");
        // Same fractions of the original: the heading is preserved bit for bit.
        assertEquals(30.0 / before * 30.0, wind.x, 1e-9);
        assertEquals(40.0 / before * 30.0, wind.y, 1e-9);
        assertEquals(0.0, wind.z, 1e-12);
    }

    @Test
    void theCapLeavesAnInBudgetWindAlone() {
        final Vector3d wind = new Vector3d(3.0, 4.0, 0.0);
        WindModel.clampToBudget(wind, 10.0, 3.0);
        assertEquals(5.0, wind.length(), 1e-12);
        assertEquals(3.0, wind.x, 1e-12);
        assertEquals(4.0, wind.y, 1e-12);
    }

    @Test
    void aNonFiniteWindIsZeroedNotClamped() {
        // Dividing a NaN by its magnitude would still be a NaN, and a NaN velocity is a permanent
        // one-tick force on a rigid body. Zero is the only safe answer.
        final Vector3d wind = new Vector3d(3.0, Double.NaN, 0.0);
        assertEquals(0.0, WindModel.clampToBudget(wind, 10.0, 3.0), 0.0);
        assertEquals(0.0, wind.length(), 0.0);

        wind.set(Double.POSITIVE_INFINITY, 1.0, -1.0);
        assertEquals(0.0, WindModel.clampToBudget(wind, 10.0, 3.0), 0.0);
        assertEquals(0.0, wind.length(), 0.0);
    }

    @Test
    void theJetIsSilentOutsideItsBand() {
        // The band is the core plus or minus the half-width; past that edge there is no jet at all,
        // so a ship at sea level and an aircraft far above the core see the same wind.
        assertEquals(1.0, WindModel.jetFactor(0.0, 220.0, 60.0, 3.0), 1e-12);
        assertEquals(1.0, WindModel.jetFactor(100.0, 220.0, 60.0, 3.0), 1e-12);
        assertEquals(1.0, WindModel.jetFactor(280.0, 220.0, 60.0, 3.0), 1e-12);
        assertEquals(1.0, WindModel.jetFactor(600.0, 220.0, 60.0, 3.0), 1e-12);
    }

    @Test
    void theJetPeaksAtItsCoreAndEasesOut() {
        // 3.0 of strength means the core runs four times the base wind, and the edges none of it.
        assertEquals(4.0, WindModel.jetFactor(220.0, 220.0, 60.0, 3.0), 1e-12);
        // Halfway across the band smoothstep is 0.5, so the multiplier is halfway up the ramp.
        assertEquals(2.5, WindModel.jetFactor(250.0, 220.0, 60.0, 3.0), 1e-12);
        // Symmetric: the same distance below the core gives the same answer.
        assertEquals(2.5, WindModel.jetFactor(190.0, 220.0, 60.0, 3.0), 1e-12);
    }

    @Test
    void aJetWithNoStrengthOrNoBandDoesNothing() {
        assertEquals(1.0, WindModel.jetFactor(220.0, 220.0, 60.0, 0.0), 0.0);
        assertEquals(1.0, WindModel.jetFactor(220.0, 220.0, 0.0, 3.0), 0.0);
    }

    @Test
    void theJetIsContinuousThroughTheBand() {
        // Climbing into the jet is the case that matters: an aircraft crossing the edge must feel a
        // gradient, not a step. The steepest smoothstep gets is 1.5, so across a one-block step the
        // multiplier can move by at most strength * 1.5 / thickness — anything larger is a jump.
        final double limit = 3.0 * 1.5 / 60.0;

        double previous = WindModel.jetFactor(100.0, 220.0, 60.0, 3.0);
        for (double altitude = 101.0; altitude <= 340.0; altitude += 1.0) {
            final double next = WindModel.jetFactor(altitude, 220.0, 60.0, 3.0);
            assertTrue(Math.abs(next - previous) <= limit + 1e-9,
                    "jump of " + (next - previous) + " at y=" + altitude);
            previous = next;
        }
    }

    @Test
    void shearSpeedIsLinearInAltitudeAndFloorsAtZero() {
        assertEquals(1.0, WindModel.shearFactor(0.0, 0.25, 300.0), 1e-12);
        assertEquals(1.5, WindModel.shearFactor(200.0, 0.25, 300.0), 1e-12);
        assertEquals(1.75, WindModel.shearFactor(300.0, 0.25, 300.0), 1e-12);
        // Above the cap it holds rather than growing: the boundary layer settles, it does not keep
        // speeding up for ever. This is what keeps it finite at the top of Sable's atmosphere.
        assertEquals(1.75, WindModel.shearFactor(3000.0, 0.25, 300.0), 1e-12);
        // Below Y 0 the shear reads as ground level rather than running the other way.
        assertEquals(1.0, WindModel.shearFactor(-64.0, 0.25, 300.0), 1e-12);
        // A strong negative rate can still the air, but it can never make the wind blow backwards.
        assertEquals(0.0, WindModel.shearFactor(500.0, -1.0, 300.0), 0.0);
    }

    @Test
    void shearTurnsWithAltitudeAtItsConfiguredRate() {
        assertEquals(0.0, WindModel.shearDegrees(0.0, 15.0, 300.0), 1e-12);
        assertEquals(45.0, WindModel.shearDegrees(300.0, 15.0, 300.0), 1e-12);
        assertEquals(-45.0, WindModel.shearDegrees(300.0, -15.0, 300.0), 1e-12);
        // The turn saturates alongside the speed, so however high the atmosphere reaches the wind
        // can never accumulate into a full reversal. Uncapped this was 360 degrees.
        assertEquals(45.0, WindModel.shearDegrees(2400.0, 15.0, 300.0), 1e-12);
        // And below Y 0 it does not turn backwards either.
        assertEquals(0.0, WindModel.shearDegrees(-64.0, 15.0, 300.0), 1e-12);
    }

    @Test
    void theShearStaysContinuousBoundedAndFiniteAcrossTheWholeAtmosphere() {
        // Sable's atmosphere runs to about 3500 blocks and the world floor is Y -64. Uncapped, shear
        // reached an 8x wind turned 450 degrees at the top, and the clamp bounds magnitude but never
        // heading — so this walks the entire range holding all three properties at once.
        final double perHundred = 0.25;
        final double degreesPerHundred = 15.0;
        final double maxAltitude = 300.0;
        final double maxTurn = Math.abs(degreesPerHundred) * maxAltitude / 100.0;
        final double speedStep = Math.abs(perHundred) / 100.0;
        final double turnStep = Math.abs(degreesPerHundred) / 100.0;

        double previousSpeed = Double.NaN;
        double previousTurn = Double.NaN;
        for (double altitude = -64.0; altitude <= 3500.0; altitude += 1.0) {
            final double speed = WindModel.shearFactor(altitude, perHundred, maxAltitude);
            final double turn = WindModel.shearDegrees(altitude, degreesPerHundred, maxAltitude);

            assertTrue(Double.isFinite(speed) && Double.isFinite(turn),
                    "non-finite shear at y=" + altitude);
            assertTrue(speed >= 0.0, "negative speed multiplier " + speed + " at y=" + altitude);
            assertTrue(Math.abs(turn) <= maxTurn + 1e-9,
                    "turn of " + turn + " degrees at y=" + altitude + " exceeds " + maxTurn);
            assertTrue(speed <= 1.0 + speedStep * maxAltitude + 1e-9,
                    "speed multiplier " + speed + " at y=" + altitude + " is past the cap");

            if (!Double.isNaN(previousSpeed)) {
                assertTrue(Math.abs(speed - previousSpeed) <= speedStep + 1e-9,
                        "speed step of " + (speed - previousSpeed) + " at y=" + altitude);
                assertTrue(Math.abs(turn - previousTurn) <= turnStep + 1e-9,
                        "turn step of " + (turn - previousTurn) + " at y=" + altitude);
            }
            previousSpeed = speed;
            previousTurn = turn;
        }
    }

    @Test
    void aJetMakesTheWindFasterButNotRougher() {
        // A jet stream is laminar: fast, not rough. Both runs size their perturbations against the
        // same reference — the dimension's base wind before the altitude profile — so the field and
        // the gusts come out identical even though the wind itself is three times stronger up there.
        // Sizing them against the profiled wind would have made the core several times stormier than
        // sea level, which is backwards for a river of fast air.
        final WindModel.Settings settings = settings(1500.0, 1200.0, 1.0, 3.0);
        final Vector3d calm = new Vector3d(3.0, 0.0, 0.0);
        final Vector3d jetted = new Vector3d(9.0, 0.0, 0.0);
        final double reference = calm.length();

        final WindModel.Result atSeaLevel = WindModel.evaluate(WindModelTest::pressure,
                WindModelTest::gust, 0.0, 0.0, 0.0, calm, reference, DRY, 0.0, settings,
                new WindModel.Result());
        final WindModel.Result atTheCore = WindModel.evaluate(WindModelTest::pressure,
                WindModelTest::gust, 0.0, 0.0, 0.0, jetted, reference, DRY, 0.0, settings,
                new WindModel.Result());

        assertEquals(atSeaLevel.gust.length(), atTheCore.gust.length(), 1e-12);
        assertEquals(atSeaLevel.field.length(), atTheCore.field.length(), 1e-12);
        // The wind itself, on the other hand, really is three times stronger up there.
        assertTrue(atTheCore.total.length() > atSeaLevel.total.length() * 2.5);
    }

    @Test
    void rotateXZFollowsTheSameSenseAsTheDirectionKeys() {
        // The direction config reads 0 as east (+X) and 90 as south (+Z). A positive rotation has to
        // walk a wind through that same order, or climbing would turn it opposite to the key.
        final Vector3d east = new Vector3d(1.0, 0.0, 0.0);
        WindModel.rotateXZ(east, 90.0);
        assertEquals(0.0, east.x, 1e-12);
        assertEquals(0.0, east.y, 0.0);
        assertEquals(1.0, east.z, 1e-12);

        final Vector3d south = new Vector3d(0.0, 0.0, 1.0);
        WindModel.rotateXZ(south, 90.0);
        assertEquals(-1.0, south.x, 1e-12);
        assertEquals(0.0, south.y, 0.0);
        assertEquals(0.0, south.z, 1e-12);
    }

    @Test
    void rotatingPreservesTheWindSpeedAndLeavesTheVerticalAlone() {
        // Shear changes where the wind points, not how hard it blows, and it must not touch the
        // vertical component, which the thermals and the ridges own between them.
        final Vector3d wind = new Vector3d(3.0, 1.5, 4.0);
        final double before = wind.length();

        WindModel.rotateXZ(wind, 37.0);

        assertEquals(before, wind.length(), 1e-12);
        assertEquals(1.5, wind.y, 1e-12);
    }

    @Test
    void zeroDegreesLeavesTheWindExactlyAsItWas() {
        final Vector3d wind = new Vector3d(1.25, 0.0, -0.75);

        WindModel.rotateXZ(wind, 0.0);

        assertEquals(1.25, wind.x, 0.0);
        assertEquals(0.0, wind.y, 0.0);
        assertEquals(-0.75, wind.z, 0.0);
    }
}
