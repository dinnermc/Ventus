package dinner.dev.ventus.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The single wind line: arrow, speed and climb marker, formatted without Minecraft. */
class WindHudFormatTest {
    private static final String AHEAD = "\u2191";
    private static final String RIGHT = "\u2192";
    private static final String BEHIND = "\u2193";
    private static final String UP = "\u2191";
    private static final String DOWN = "\u2193";

    @Test
    void arrowPointsInEightDirectionsRelativeToThePlayer() {
        assertEquals(0, WindHudFormat.arrowIndex(0.0, 1.0), "the way you are looking");
        assertEquals(1, WindHudFormat.arrowIndex(1.0, 1.0), "ahead right");
        assertEquals(2, WindHudFormat.arrowIndex(1.0, 0.0), "right");
        assertEquals(3, WindHudFormat.arrowIndex(1.0, -1.0), "behind right");
        assertEquals(4, WindHudFormat.arrowIndex(0.0, -1.0), "behind");
        assertEquals(5, WindHudFormat.arrowIndex(-1.0, -1.0), "behind left");
        assertEquals(6, WindHudFormat.arrowIndex(-1.0, 0.0), "left");
        assertEquals(7, WindHudFormat.arrowIndex(-1.0, 1.0), "ahead left");
    }

    @Test
    void arrowEitherSideOfAheadStaysAheadUntilItLeavesTheSector() {
        assertEquals(0, WindHudFormat.arrowIndex(0.001, 1.0), "just right of ahead");
        assertEquals(0, WindHudFormat.arrowIndex(-0.001, 1.0), "just left of ahead");
        assertEquals(7, WindHudFormat.arrowIndex(-0.5, 1.0), "ahead left");
        assertEquals(2, WindHudFormat.arrowIndex(1.0, -0.001), "just behind the right");
    }

    @Test
    void climbMarkerNeedsMoreThanHalfAMetrePerSecond() {
        assertEquals("", WindHudFormat.climbLabel(0.0));
        assertEquals("", WindHudFormat.climbLabel(WindHudFormat.CLIMB_EPSILON), "exactly 0.5 is not past it");
        assertEquals("", WindHudFormat.climbLabel(-WindHudFormat.CLIMB_EPSILON));
        assertEquals(UP + "0.5", WindHudFormat.climbLabel(0.51), "just past the threshold");
        assertEquals(DOWN + "0.5", WindHudFormat.climbLabel(-0.51));
        assertEquals(UP + "1.3", WindHudFormat.climbLabel(1.3));
    }

    @Test
    void lineIsTheArrowThenTheSpeedThenTheClimb() {
        assertEquals(AHEAD + " 3.2 m/s", WindHudFormat.line(3.2, 0.0, 0));
        assertEquals(RIGHT + " 3.2 m/s " + UP + "1.3", WindHudFormat.line(3.2, 1.3, 2));
        assertEquals(BEHIND + " 3.2 m/s " + DOWN + "2.0", WindHudFormat.line(3.2, -2.0, 4));
    }

    @Test
    void lineWithoutAnArrowIsJustTheFigure() {
        assertEquals("0.0 m/s", WindHudFormat.line(0.0, 0.0, -1));
        assertEquals("0.0 m/s " + UP + "1.3", WindHudFormat.line(0.0, 1.3, -1));
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
