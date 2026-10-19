package dinner.dev.ventus.client;

import dinner.dev.ventus.WindModel;
import java.util.Locale;

/**
 * Everything about the wind HUD that can be decided without Minecraft: the angle smoothing, the
 * figures and the single line that ends up on screen. Kept free of client classes so the
 * formatting and the smoothing can be unit tested; {@link WindHud} owns the drawing.
 */
public final class WindHudFormat {
    private WindHudFormat() {
    }

    /** Horizontal wind at or below this (m/s) is still air: there is no direction to point at. */
    public static final float CALM_EPSILON = 0.05f;

    /**
     * Vertical wind must pass this (m/s) before the climb marker appears. Kept low: a real
     * variometer reads tenths, and with the defaults a ridge under a 3 m/s wind only lifts about
     * 0.45 m/s, so anything above a tenth is worth showing.
     */
    public static final float CLIMB_EPSILON = 0.1f;

    /** Whether the horizontal wind is light enough to call it still air. */
    public static boolean isCalm(final double horizontalSpeed) {
        return horizontalSpeed <= CALM_EPSILON;
    }

    /** Wraps a difference of angles into {@code (-π, π]}, so the short way round is always taken. */
    public static double normalizeAngle(final double radians) {
        return Math.atan2(Math.sin(radians), Math.cos(radians));
    }

    /**
     * Steps the arrow's angle towards the one the server sent, with the same exponential
     * {@link WindModel#approach} step the field smoothing uses. Takes the short way round the dial,
     * and {@code deltaSeconds} rather than a per-frame factor, so 30 and 144 fps settle in the same
     * real time.
     */
    public static double approachAngle(final double current, final double target,
            final double deltaSeconds, final double timeConstant) {
        final double difference = normalizeAngle(target - current);
        return WindModel.approach(current, current + difference, timeConstant, deltaSeconds);
    }

    /**
     * The climb marker: empty while the vertical component stays within {@link #CLIMB_EPSILON},
     * otherwise {@code ↑1.3} or {@code ↓1.3}.
     */
    public static String climbLabel(final double verticalSpeed) {
        if (Math.abs(verticalSpeed) <= CLIMB_EPSILON) {
            return "";
        }
        return String.format(Locale.ROOT, verticalSpeed > 0.0 ? "\u2191%.1f" : "\u2193%.1f",
                Math.abs(verticalSpeed));
    }

    /**
     * The text beside the arrow: the horizontal speed in m/s and, when it is worth showing, the
     * climb marker, e.g. {@code 3.2 m/s ↑1.3}. Still air is just {@code 0.0 m/s}.
     */
    public static String line(final double horizontalSpeed, final double verticalSpeed) {
        final String climb = climbLabel(verticalSpeed);
        final String figure = String.format(Locale.ROOT, "%.1f m/s", horizontalSpeed);
        return climb.isEmpty() ? figure : figure + " " + climb;
    }

    /**
     * Colour of the line and the arrow, by physical speed in m/s: grey under 1, cyan under 4,
     * amber under 8, orange under 16, red beyond. Still air therefore reads grey without a special
     * case.
     */
    public static int speedColor(final double speedMetresPerSecond) {
        if (speedMetresPerSecond < 1.0) {
            return 0xFF8C96A6;
        }
        if (speedMetresPerSecond < 4.0) {
            return 0xFF5FD9C9;
        }
        if (speedMetresPerSecond < 8.0) {
            return 0xFFF2C14E;
        }
        if (speedMetresPerSecond < 16.0) {
            return 0xFFF2792E;
        }
        return 0xFFF0483C;
    }
}
