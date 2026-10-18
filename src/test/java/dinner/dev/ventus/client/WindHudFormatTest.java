package dinner.dev.ventus.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The wind line: angle smoothing, speed and climb marker, formatted without Minecraft. */
class WindHudFormatTest {
    private static final String UP = "\u2191";
    private static final String DOWN = "\u2193";

    @Test
    void normalizeAngleKeepsTheShortWayRound() {
        assertEquals(0.0, WindHudFormat.normalizeAngle(0.0), 1e-12);
        assertEquals(0.0, WindHudFormat.normalizeAngle(2.0 * Math.PI), 1e-9);
        assertEquals(0.0, WindHudFormat.normalizeAngle(-2.0 * Math.PI), 1e-9);
        assertEquals(Math.PI, WindHudFormat.normalizeAngle(Math.PI), 1e-9);
        assertEquals(-Math.PI, WindHudFormat.normalizeAngle(-Math.PI), 1e-9);
        assertTrue(WindHudFormat.normalizeAngle(Math.PI + 0.1) < 0.0, "past the fold goes back");
        assertEquals(Math.PI - 0.1, WindHudFormat.normalizeAngle(-Math.PI - 0.1), 1e-9);
        assertEquals(Math.toRadians(10.0), WindHudFormat.normalizeAngle(Math.toRadians(370.0)), 1e-9);
        assertEquals(Math.toRadians(10.0), WindHudFormat.normalizeAngle(Math.toRadians(-350.0)), 1e-9);
    }

    @Test
    void approachAngleTakesTheShortWayRoundTheDial() {
        final double current = Math.toRadians(350.0);
        final double target = Math.toRadians(10.0);
        final double next = WindHudFormat.approachAngle(current, target, 1.0, 0.15);

        // one long step must land on the target, not 340 degrees the long way round
        assertEquals(0.0, WindHudFormat.normalizeAngle(next - target), 1e-3);
        assertEquals(Math.toRadians(20.0), WindHudFormat.normalizeAngle(next - current), 1e-3);
    }

    @Test
    void approachAngleLeavesTheAngleAloneWhenItIsAlreadyThere() {
        assertEquals(1.25, WindHudFormat.approachAngle(1.25, 1.25, 0.016, 0.15), 1e-12);
        assertEquals(1.25, WindHudFormat.approachAngle(1.25, 1.25, 5.0, 0.15), 1e-12);
    }

    @Test
    void approachAngleSettlesInRealTimeRatherThanInFrames() {
        final double current = 0.0;
        final double target = Math.PI / 2.0;
        final double oneStep = WindHudFormat.approachAngle(current, target, 0.5, 0.15);
        final double twoSteps = WindHudFormat.approachAngle(
                WindHudFormat.approachAngle(current, target, 0.25, 0.15), target, 0.25, 0.15);
        double tenSteps = current;
        for (int i = 0; i < 10; i++) {
            tenSteps = WindHudFormat.approachAngle(tenSteps, target, 0.05, 0.15);
        }
        assertEquals(oneStep, twoSteps, 1e-9);
        assertEquals(oneStep, tenSteps, 1e-9);
        assertTrue(oneStep > 0.0 && oneStep < target, "must move towards the target and stop short");
    }

    @Test
    void climbMarkerNeedsMoreThanATenthOfAMetrePerSecond() {
        assertEquals("", WindHudFormat.climbLabel(0.0));
        assertEquals("", WindHudFormat.climbLabel(WindHudFormat.CLIMB_EPSILON), "exactly the threshold is not past it");
        assertEquals("", WindHudFormat.climbLabel(-WindHudFormat.CLIMB_EPSILON));
        assertEquals(UP + "0.1", WindHudFormat.climbLabel(0.11), "just past the threshold");
        assertEquals(DOWN + "0.1", WindHudFormat.climbLabel(-0.11));
        assertEquals(UP + "0.4", WindHudFormat.climbLabel(0.44), "a light ridge lift is visible now");
        assertEquals(UP + "0.5", WindHudFormat.climbLabel(0.45), "and 0.45 rounds up, as the string must");
        assertEquals(UP + "1.3", WindHudFormat.climbLabel(1.3));
    }

    @Test
    void lineIsTheSpeedThenTheClimb() {
        assertEquals("3.2 m/s", WindHudFormat.line(3.2, 0.0));
        assertEquals("3.2 m/s " + UP + "1.3", WindHudFormat.line(3.2, 1.3));
        assertEquals("3.2 m/s " + DOWN + "2.0", WindHudFormat.line(3.2, -2.0));
        assertEquals("0.0 m/s", WindHudFormat.line(0.0, 0.0));
        assertEquals("0.0 m/s " + UP + "1.3", WindHudFormat.line(0.0, 1.3));
    }

    @Test
    void calmIsAHardThresholdOnTheHorizontalSpeed() {
        assertTrue(WindHudFormat.isCalm(0.0));
        assertTrue(WindHudFormat.isCalm(WindHudFormat.CALM_EPSILON));
        assertFalse(WindHudFormat.isCalm(0.051));
        assertFalse(WindHudFormat.isCalm(4.2));
    }

    @Test
    void speedColorStepsAtThePublishedThresholds() {
        assertEquals(0xFF8C96A6, WindHudFormat.speedColor(0.0));
        assertEquals(0xFF8C96A6, WindHudFormat.speedColor(0.99));
        assertEquals(0xFF5FD9C9, WindHudFormat.speedColor(1.0));
        assertEquals(0xFF5FD9C9, WindHudFormat.speedColor(3.99));
        assertEquals(0xFFF2C14E, WindHudFormat.speedColor(4.0));
        assertEquals(0xFFF2C14E, WindHudFormat.speedColor(7.99));
        assertEquals(0xFFF2792E, WindHudFormat.speedColor(8.0));
        assertEquals(0xFFF2792E, WindHudFormat.speedColor(15.99));
        assertEquals(0xFFF0483C, WindHudFormat.speedColor(16.0));
        assertEquals(0xFFF0483C, WindHudFormat.speedColor(40.0));
    }
}
