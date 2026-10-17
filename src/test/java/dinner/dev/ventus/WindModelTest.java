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
}
