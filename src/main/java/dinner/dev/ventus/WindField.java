package dinner.dev.ventus;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Prototype wind field (plan step 2).
 * <p>
 * The dumbest possible implementation: a constant wind, independent of position, time and
 * dimension. Step 6 replaces this with a real position-dependent field backed by a config.
 * <p>
 * Wind is expressed in <b>blocks per second</b> (Sable's physics unit, {@code 1 block/s = 1 m/s}).
 * For scale: a light breeze is 2-6, a strong wind is 10-15 and a storm is 20+.
 * <p>
 * Testing without recompiling:
 * <ul>
 *     <li>{@code -Dventus.wind=15,0,0} sets the wind vector (x,y,z); the default is {@code 5,0,0}.</li>
 *     <li>{@code -Dventus.disableWind=true} forces a calm (zero) wind regardless of the above.</li>
 * </ul>
 */
public final class WindField {
    private WindField() {
    }

    private static final Vector3d DEFAULT_WIND = new Vector3d(5.0, 0.0, 0.0);

    private static final Vector3d WIND = parse(System.getProperty("ventus.wind"));

    private static final boolean ENABLED = !Boolean.getBoolean("ventus.disableWind");

    private static Vector3d parse(final String spec) {
        if (spec == null) {
            return new Vector3d(DEFAULT_WIND);
        }

        final String[] parts = spec.split(",");
        if (parts.length != 3) {
            return new Vector3d(DEFAULT_WIND);
        }

        try {
            return new Vector3d(
                    Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()));
        } catch (final NumberFormatException e) {
            return new Vector3d(DEFAULT_WIND);
        }
    }

    /**
     * @return the constant wind vector for this session, in blocks/second
     */
    public static Vector3d wind() {
        return WIND;
    }

    public static boolean isCalm() {
        return !ENABLED || WIND.lengthSquared() == 0.0;
    }

    /**
     * @param worldVelocity a sub-level's material velocity in world space, in blocks/second
     * @return the velocity relative to the air (i.e. {@code worldVelocity - wind}), never {@code null}
     */
    public static Vector3dc airRelative(final Vector3dc worldVelocity) {
        if (isCalm()) {
            return worldVelocity;
        }
        return new Vector3d(worldVelocity).sub(WIND);
    }
}
