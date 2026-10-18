package dinner.dev.ventus.client;

import java.util.Locale;

/**
 * Everything about the wind compass HUD that can be decided without Minecraft: the arrow, the
 * figures and the single line that ends up on screen.
 *
 * <p>Kept free of client classes on purpose, so the formatting can be unit tested without a
 * running game. {@link WindHud} owns the drawing and caches whatever this class produces.
 */
public final class WindHudFormat {
    private WindHudFormat() {
    }

    /**
     * The eight directions the arrow can point, in the player's own frame of reference:
     * 0 = the way you are looking, then clockwise as you see it on screen.
     */
    public static final String[] ARROWS = {
        "\u2191", // the way you are looking
        "\u2197", // ahead and to your right
        "\u2192", // to your right
        "\u2198", // behind and to your right
        "\u2193", // behind you
        "\u2199", // behind and to your left
        "\u2190", // to your left
        "\u2196"  // ahead and to your left
    };

    /** Horizontal wind at or below this (m/s) is still air: there is no direction to point at. */
    public static final float CALM_EPSILON = 0.05f;

    /** Vertical wind must pass this (m/s) before the climb marker appears. */
    public static final float CLIMB_EPSILON = 0.5f;

    /** Whether the horizontal wind is light enough to call it still air. */
    public static boolean isCalm(final double horizontalSpeed) {
        return horizontalSpeed <= CALM_EPSILON;
    }

    /**
     * Index into {@link #ARROWS} of the direction the wind blows, measured against the player:
     * {@code along} is the component towards where they are looking, {@code across} the one to
     * their right. Pass {@code -1} instead of an index when there is no wind to point with.
     */
    public static int arrowIndex(final double across, final double along) {
        final double degrees = (Math.toDegrees(Math.atan2(across, along)) + 360.0) % 360.0;
        return (int) Math.round(degrees / 45.0) % ARROWS.length;
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
     * The whole HUD line: the arrow, the horizontal speed in m/s and, when it is worth showing,
     * the climb marker, e.g. {@code ↑ 3.2 m/s ↑1.3}. Still air is just {@code 0.0 m/s}.
     */
    public static String line(final double horizontalSpeed, final double verticalSpeed,
            final int arrowIndex) {
        final String arrow = arrowIndex < 0 ? "" : ARROWS[arrowIndex];
        final String climb = climbLabel(verticalSpeed);
        final String figure = String.format(Locale.ROOT, "%.1f m/s", horizontalSpeed);
        return (arrow.isEmpty() ? "" : arrow + " ") + figure + (climb.isEmpty() ? "" : " " + climb);
    }

    /**
     * Colour of the line, by physical speed in m/s: grey under 1, cyan under 4, amber under 8,
     * orange under 16, red beyond. Still air therefore reads grey without a special case.
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
