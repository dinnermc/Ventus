package dinner.dev.ventus;

import org.joml.Vector3d;

/**
 * Implemented (via mixin) by Sable's {@code ServerSubLevel} to hold the wind state sampled for it
 * once this server tick.
 * <p>
 * The state is written by {@link WindSampler} on the server thread and read by the physics hooks in
 * {@code ServerSubLevelWindMixin}, which run on the same thread (see {@link WindSampler}). The
 * fields are deliberately <b>not</b> initialised in the interface implementation: the JVM defaults
 * (null / 0.0) are exactly the desired starting state for a freshly created sub-level, and this
 * way the mixin never depends on Mixin copying field initialisers.
 * <ul>
 *     <li>{@link #ventus$wind()} — the wind vector, created lazily on first use;</li>
 *     <li>{@link #ventus$fade()} — the last height profile factor, {@code 0} until the terrain is
 *     known, so a sub-level whose chunk is not loaded keeps its previous factor instead of jumping
 *     to full wind;</li>
 *     <li>{@link #ventus$firstSample()} — game time of the first sample, or {@code null} while
 *     unsampled, driving the temporal fade-in;</li>
 *     <li>{@link #ventus$vertical()} — the smoothed stage-4 vertical wind (thermal + orographic),
 *     {@code 0} until first sampled. Smoothed rather than raw because both terms are derived from
 *     integer terrain heights, which would otherwise make the updraft step as the columns are read.</li>
 * </ul>
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
