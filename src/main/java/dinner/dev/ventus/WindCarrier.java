package dinner.dev.ventus;

import org.joml.Vector3d;

/**
 * Mixed into Sable's {@code ServerSubLevel} (via {@code ServerSubLevelWindMixin}) to hold the
 * wind sampled for it this tick: the vector itself, the height profile factor, the first-sample
 * time behind the fade-in, and the smoothed vertical wind. Written and read on the server thread.
 */
public interface WindCarrier {
    /**
     * @return the live wind vector for this sub-level, in blocks/second, never {@code null}
     */
    Vector3d ventus$wind();

    /**
     * @return the last height profile factor applied to this sub-level ({@code 0} until first computed)
     */
    double ventus$fade();

    void ventus$setFade(double fade);

    /**
     * @return the game time this sub-level was first sampled, or {@code null} if it never was
     */
    Long ventus$firstSample();

    void ventus$setFirstSample(Long gameTime);

    /**
     * @return the smoothed vertical wind written by the last sample ({@code 0} until first sampled)
     */
    double ventus$vertical();

    void ventus$setVertical(double vertical);
}
