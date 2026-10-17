package dinner.dev.ventus;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The wind field (stage 1).
 * <p>
 * Wind is expressed in <b>blocks per second</b> (Sable's physics unit, {@code 1 block/s = 1 m/s}).
 * For scale: a light breeze is 2-6, a strong wind is 10-15 and a storm is 20+.
 * <p>
 * The field is the product of four factors:
 * <ol>
 *     <li>a per-dimension <b>base wind</b> (magnitude + direction) from {@link VentusConfig};</li>
 *     <li>a <b>height profile</b> (see {@link #fadeAt}): the wind ramps from
 *     {@link VentusConfig#surfaceCalm()} at the terrain surface up to full at
 *     {@link VentusConfig#fadeHeight()} blocks above it. The reference surface is vanilla's
 *     {@link Heightmap.Types#OCEAN_FLOOR} (solid blocks, <b>water and leaves ignored edge cases
 *     aside</b>): a boat floating on the sea sits <em>above</em> its reference and still catches the
 *     wind, while a plane parked on the ground feels almost nothing. Leaves count as solid terrain,
 *     so a plane skimming a forest sees the canopy as ground (trees shelter the wind);</li>
 *     <li>a <b>temporal fade-in</b> (see {@link #entryRamp}): the wind ramps from 0 to full over
 *     {@link #ENTRY_RAMP_SECONDS} seconds after a sub-level's first sample, so nothing jolts when a
 *     world loads or a sub-level enters a windy region;</li>
 *     <li>the global {@link VentusConfig#windScale()} multiplier.</li>
 * </ol>
 * A submerged sample point (the centre block holds a fluid) yields zero wind, so a diving
 * sub-level feels nothing.
 * <p>
 * Stages 2 and 3 add a pressure field, gusts, and rain/thunder, all on top of this base.
 * <p>
 * <b>Debug override.</b> {@code -Dventus.wind=x,y,z} replaces the whole field with a fixed vector
 * (no base wind, no height profile, no fade-in, no scale) so a specific vector can be dialled in
 * from the command line. {@code -Dventus.disableWind=true} forces a calm (zero) wind. Both are
 * development aids.
 */
public final class WindField {
    private WindField() {
    }

    /** Seconds a sub-level takes to ram from no wind to full wind after its first sample. */
    private static final double ENTRY_RAMP_SECONDS = 5.0;

    /** Game ticks of dawn and of dusk over which thermals fade in and out. */
    private static final long TWILIGHT_TICKS = 1500L;

    private static final boolean ENABLED = !Boolean.getBoolean("ventus.disableWind");

    /** Fixed debug override from {@code -Dventus.wind}, or {@code null} when the field is used. */
    private static final Vector3d FIXED_OVERRIDE = parseOverride(System.getProperty("ventus.wind"));

    private static Vector3d parseOverride(final String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }

        final String[] parts = spec.split(",");
        if (parts.length != 3) {
            return null;
        }

        try {
            return new Vector3d(
                    Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()));
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /** Per-dimension noise, rebuilt whenever the world seed changes. Server-thread only. */
    private static final Map<ResourceKey<Level>, NoiseSet> NOISE = new HashMap<>();

    private record NoiseSet(long seed, SimplexNoise pressure, SimplexNoise gust, SimplexNoise thermal) {
    }

    private static NoiseSet noiseSet(final ServerLevel level) {
        final ResourceKey<Level> dimension = level.dimension();
        final long seed = level.getSeed();

        NoiseSet set = NOISE.get(dimension);
        if (set == null || set.seed() != seed) {
            // Mix the world seed with the dimension's id so each dimension gets its own weather.
            final long mixed = seed * 0x9E3779B97F4A7C15L + dimension.location().hashCode();
            // Each further noise uses a different seed so the fields are independent.
            final long gustSeed = mixed + 0x9E3779B97F4A7C15L;
            final long thermalSeed = gustSeed + 0x9E3779B97F4A7C15L;
            set = new NoiseSet(seed,
                    new SimplexNoise(RandomSource.create(mixed)),
                    new SimplexNoise(RandomSource.create(gustSeed)),
                    new SimplexNoise(RandomSource.create(thermalSeed)));
            NOISE.put(dimension, set);
        }

        return set;
    }

    /** Scratch result reused by every sample; the server thread is the only caller. */
    private static final WindModel.Result MODEL_RESULT = new WindModel.Result();

    /** Scratch breakdown reused by the diagnostics command. */
    private static final Breakdown BREAKDOWN = new Breakdown();

    /**
     * Rain/storm scaling for the perturbations, taken from the dimension's continuous weather levels
     * rather than the raining/thundering flags — see {@link WindModel#weatherScale}. Global to the
     * dimension, so this costs two float reads and no chunk, heightmap or biome lookup.
     */
    private static double weatherMultiplier(final ServerLevel level) {
        return WindModel.weatherScale(VentusConfig.rainMultiplier(), VentusConfig.thunderMultiplier(),
                level.getRainLevel(1.0F), level.getThunderLevel(1.0F));
    }

    /** Cached biome warmth, keyed by biome id: a biome's temperature never changes at runtime. */
    private static final Map<ResourceLocation, Double> WARMTH = new HashMap<>();

    /**
     * The terrain height at a column, or {@link Double#NaN} when its chunk is not loaded. Like the
     * rest of the field, this never forces a chunk load.
     */
    private static double terrainAt(final ServerLevel level, final int x, final int z) {
        final LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) {
            return Double.NaN;
        }
        return chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x & 15, z & 15);
    }

    /**
     * How far past dawn and short of dusk the sun is, in {@code [0, 1]}: 0 through the night, 1 at
     * midday. Dawn and dusk each get {@link #TWILIGHT_TICKS} ticks to ramp, so thermals breathe in
     * and out with the day instead of switching on at sunrise.
     *
     * <p>Uses the level's own day clock, so {@code /time set} produces a ramp rather than a jump.
     */
    private static double daylightAt(final ServerLevel level) {
        final long dayTime = Math.floorMod(level.getDayTime(), 24000L);
        if (dayTime >= 12000L) {
            return 0.0;
        }

        return smoothstep(dayTime / (double) TWILIGHT_TICKS)
                * smoothstep((12000L - dayTime) / (double) TWILIGHT_TICKS);
    }

    /**
     * How hot the biome at a point is, in {@code [0, 1]}: 0 at or below
     * {@link VentusConfig#thermalColdTemperature()} and 1 at
     * {@link VentusConfig#thermalHotTemperature()}. Deserts and badlands sit at 1, savanna part way
     * up and temperate biomes at 0, which is roughly how real convection behaves.
     *
     * <p>Cached by biome id, because the answer only changes when the biome does. Requires the chunk
     * to be loaded (a biome lookup would otherwise load it) and yields 0 when it is not.
     */
    private static double warmthAt(final ServerLevel level, final BlockPos pos) {
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            return 0.0;
        }

        final Holder<Biome> biome = level.getBiome(pos);
        final Optional<ResourceKey<Biome>> key = biome.unwrapKey();
        final ResourceLocation id = key.map(ResourceKey::location).orElse(null);

        if (id != null) {
            final Double cached = WARMTH.get(id);
            if (cached != null) {
                return cached;
            }
        }

        final double value = warmFraction(biome.value().getBaseTemperature());
        if (id != null) {
            WARMTH.put(id, value);
        }
        return value;
    }

    private static double warmFraction(final double temperature) {
        final double cold = VentusConfig.thermalColdTemperature();
        final double hot = VentusConfig.thermalHotTemperature();
        if (hot <= cold) {
            return temperature >= hot ? 1.0 : 0.0;
        }
        return smoothstep((temperature - cold) / (hot - cold));
    }

    /**
     * The rise-over-run of the terrain along the <b>base wind</b> direction, or {@link Double#NaN}
     * when either probe column is not loaded. Positive means the wind is about to climb a windward
     * face, negative that it is pouring off one.
     *
     * <p>Deliberately measured along the prevailing base wind rather than the local (base + field)
     * wind: a mountain's lee side is a fixed place, and it should not flip because the field deflected
     * the wind a few degrees this tick. Two columns, {@link VentusConfig#slopeProbe()} blocks apart
     * either side, so a single-block cliff is averaged away instead of towering over the answer; and
     * the result is clamped to a 45&deg; slope, which is both the physical maximum worth modelling
     * and the slope at which {@link WindModel#leeGustBoost} reaches its ceiling.
     */
    private static double slopeAlongWind(final ServerLevel level, final Vector3dc worldPos,
            final double dirX, final double dirZ) {
        if (dirX == 0.0 && dirZ == 0.0) {
            return Double.NaN;
        }

        final double probe = VentusConfig.slopeProbe();
        final double downwind = terrainAt(level,
                Mth.floor(worldPos.x() + dirX * probe), Mth.floor(worldPos.z() + dirZ * probe));
        final double upwind = terrainAt(level,
                Mth.floor(worldPos.x() - dirX * probe), Mth.floor(worldPos.z() - dirZ * probe));

        if (Double.isNaN(downwind) || Double.isNaN(upwind)) {
            return Double.NaN;
        }

        // Clamped to a 45 degree slope: Minecraft terrain can be a vertical wall, and letting that
        // through would turn orographic lift into a 45 m/s updraft. It is also the slope at which
        // leeGustBoost is defined to reach its ceiling.
        return Mth.clamp((downwind - upwind) / (2.0 * probe), -1.0, 1.0);
    }

    /**
     * The stage-4 vertical air speed at a point: thermals plus orographic lift, kept apart so the
     * diagnostics command can report each one.
     *
     * <p>Both are returned before the height profile and the entry ramp, because the caller scales the
     * whole wind vector by those afterwards — and both <em>should</em> be weaker near the ground.
     *
     * @param noise           the per-dimension noise set
     * @param level           the level, for the sun, the rain and the biome
     * @param worldPos        the sample point
     * @param horizontalSpeed the XZ wind speed, which is what drives orographic lift
     * @param slope           the terrain slope along the base wind, or {@code NaN} if unknown
     * @param out             the terms to fill (reused)
     */
    private static void verticalAt(final NoiseSet noise, final ServerLevel level, final Vector3dc worldPos,
            final double horizontalSpeed, final double slope, final Vertical out) {
        out.thermal = 0.0;
        out.orographic = 0.0;

        final double thermalStrength = VentusConfig.thermalStrength();
        if (thermalStrength > 0.0) {
            final double nt = level.getGameTime() / 20.0 / VentusConfig.thermalPeriod();
            final double bubble = noise.thermal().getValue(
                    worldPos.x() / VentusConfig.thermalScale(), worldPos.z() / VentusConfig.thermalScale(), nt);
            final BlockPos pos = new BlockPos(Mth.floor(worldPos.x()), Mth.floor(worldPos.y()),
                    Mth.floor(worldPos.z()));
            out.thermal = WindModel.thermal(bubble, daylightAt(level), warmthAt(level, pos),
                    1.0 - level.getRainLevel(1.0F), thermalStrength);
        }

        final double slopeStrength = VentusConfig.slopeStrength();
        if (slopeStrength > 0.0 && !Double.isNaN(slope)) {
            out.orographic = WindModel.orographic(horizontalSpeed, slope, slopeStrength);
        }
    }

    /** The two stage-4 vertical terms at one sample point. */
    private static final class Vertical {
        double thermal;
        double orographic;

        double total() {
            return this.thermal + this.orographic;
        }
    }

    /** Scratch vertical terms reused by every sample; the server thread is the only caller. */
    private static final Vertical VERTICAL = new Vertical();

    /**
     * @return {@code true} when the global kill switch ({@code -Dventus.disableWind}) is on, in
     * which case the field is zero everywhere and no work needs doing
     */
    public static boolean isCalm() {
        return !ENABLED;
    }

    /**
     * Samples the wind for one sub-level, writing it into the carrier's {@link WindCarrier#ventus$wind()}
     * and updating the carrier's cached fade and first-sample time.
     *
     * @param level    the level the sub-level is in
     * @param worldPos the sub-level's sample point, in world space
     * @param carrier  the sub-level's wind state
     */
    public static void sample(final ServerLevel level, final Vector3dc worldPos, final WindCarrier carrier) {
        final Vector3d dest = carrier.ventus$wind();

        if (!ENABLED) {
            carrier.ventus$setFade(0.0);
            dest.set(0.0, 0.0, 0.0);
            return;
        }

        if (FIXED_OVERRIDE != null) {
            carrier.ventus$setFade(1.0);
            dest.set(FIXED_OVERRIDE);
            return;
        }

        VentusConfig.baseWind(level.dimension(), dest);
        if (dest.lengthSquared() == 0.0) {
            carrier.ventus$setFade(0.0);
            return;
        }

        final double fade = fadeAt(level, worldPos, carrier.ventus$fade());
        carrier.ventus$setFade(fade);

        // base wind + pressure field + gusts (scaled by weather), then the vertical terms, then the
        // height profile, the fade-in and windScale. The slope is measured along the base wind, which
        // is still in dest at this point.
        final NoiseSet noise = noiseSet(level);
        final WindModel.Settings settings = VentusConfig.windModelSettings();
        final double slope = slopeAlongWind(level, worldPos, dest.x(), dest.z());
        final double leeBoost = Double.isNaN(slope) ? 0.0 : WindModel.leeGustBoost(slope, settings.leeGustBoost());

        WindModel.evaluate(noise.pressure()::getValue, noise.gust()::getValue, worldPos.x(), worldPos.z(),
                level.getGameTime() / 20.0, dest, weatherMultiplier(level), leeBoost, settings, MODEL_RESULT);

        dest.set(MODEL_RESULT.total);
        verticalAt(noise, level, worldPos, Math.hypot(dest.x, dest.z), slope, VERTICAL);
        dest.y = VERTICAL.total();
        dest.mul(fade * entryRamp(level, carrier) * VentusConfig.windScale());
    }

    /**
     * A one-off, steady-state sample at a point, for diagnostics (the {@code /windfield} command).
     */
    public static Vector3d sampleAt(final ServerLevel level, final Vector3dc worldPos, final Vector3d dest) {
        return breakdown(level, worldPos, BREAKDOWN).into(dest);
    }

    /**
     * Fills {@code dest} with the separated components of the steady-state wind at a point: the base
     * wind, the pressure-field perturbation, the height factor and the capped total. Unlike
     * {@link #sample} it touches no sub-level state, applies no temporal fade-in, and assumes full
     * wind when the terrain is unknown.
     */
    public static Breakdown breakdown(final ServerLevel level, final Vector3dc worldPos, final Breakdown dest) {
        dest.base.set(0.0, 0.0, 0.0);
        dest.field.set(0.0, 0.0, 0.0);
        dest.gust.set(0.0, 0.0, 0.0);
        dest.total.set(0.0, 0.0, 0.0);
        dest.heightFactor = 1.0;
        dest.weather = 1.0;
        dest.slope = Double.NaN;
        dest.leeBoost = 0.0;
        dest.thermal = 0.0;
        dest.orographic = 0.0;
        dest.capped = false;

        if (!ENABLED) {
            return dest;
        }

        if (FIXED_OVERRIDE != null) {
            dest.base.set(FIXED_OVERRIDE);
            dest.total.set(FIXED_OVERRIDE);
            return dest;
        }

        VentusConfig.baseWind(level.dimension(), dest.base);
        if (dest.base.lengthSquared() == 0.0) {
            return dest;
        }

        final double fade = fadeAt(level, worldPos, 1.0);
        dest.heightFactor = fade;

        final NoiseSet noise = noiseSet(level);
        final double weather = weatherMultiplier(level);
        dest.weather = weather;

        final WindModel.Settings settings = VentusConfig.windModelSettings();
        final double slope = slopeAlongWind(level, worldPos, dest.base.x(), dest.base.z());
        dest.slope = slope;
        final double leeBoost = Double.isNaN(slope) ? 0.0 : WindModel.leeGustBoost(slope, settings.leeGustBoost());
        dest.leeBoost = leeBoost;

        WindModel.evaluate(noise.pressure()::getValue, noise.gust()::getValue, worldPos.x(), worldPos.z(),
                level.getGameTime() / 20.0, dest.base, weather, leeBoost, settings, MODEL_RESULT);

        verticalAt(noise, level, worldPos, Math.hypot(MODEL_RESULT.total.x, MODEL_RESULT.total.z), slope,
                VERTICAL);
        dest.thermal = VERTICAL.thermal;
        dest.orographic = VERTICAL.orographic;

        final double scale = fade * VentusConfig.windScale();
        dest.base.mul(scale);
        dest.field.set(MODEL_RESULT.field).mul(scale);
        dest.gust.set(MODEL_RESULT.gust).mul(scale);
        dest.total.set(MODEL_RESULT.total).mul(scale);
        dest.total.y = (dest.thermal + dest.orographic) * scale;
        dest.capped = MODEL_RESULT.capped;
        return dest;
    }

    /** The separated wind components at a point, for the {@code /windfield} command. */
    public static final class Breakdown {
        public final Vector3d base = new Vector3d();
        public final Vector3d field = new Vector3d();
        public final Vector3d gust = new Vector3d();
        public final Vector3d total = new Vector3d();
        public double heightFactor;
        /** Rain/storm scaling applied to {@link #field} and {@link #gust} (1 = dry). */
        public double weather;
        /** Terrain rise-over-run along the base wind (positive = windward), or {@code NaN} if unknown. */
        public double slope;
        /** Extra gust amplitude on the lee side, as a fraction of the base gust (0 = none). */
        public double leeBoost;
        /** The thermal updraft at this point, before the height profile (stage 4). */
        public double thermal;
        /** The orographic up- or downdraft at this point, before the height profile (stage 4). */
        public double orographic;
        public boolean capped;

        /** Copies {@link #total} into {@code dest} and returns it. */
        public Vector3d into(final Vector3d dest) {
            return dest.set(this.total);
        }
    }

    /**
     * The height profile factor at a point: {@link VentusConfig#surfaceCalm()} at or below the
     * terrain, ramping smoothly to 1 at {@link VentusConfig#fadeHeight()} blocks above it, and 0 if
     * the sample point is submerged.
     *
     * @param unknownChunkFade the factor to return when the terrain is not loaded (the caller's last
     *                         known factor, or 0 / 1 depending on the caller)
     */
    private static double fadeAt(final ServerLevel level, final Vector3dc worldPos, final double unknownChunkFade) {
        final int x = Mth.floor(worldPos.x());
        final int y = Mth.floor(worldPos.y());
        final int z = Mth.floor(worldPos.z());

        final LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) {
            // Terrain unknown: reuse the last known factor rather than jumping to full wind.
            return unknownChunkFade;
        }

        if (!chunk.getBlockState(new BlockPos(x, y, z)).getFluidState().isEmpty()) {
            // Submerged: no wind.
            return 0.0;
        }

        final double terrain = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x & 15, z & 15);
        final double surfaceCalm = VentusConfig.surfaceCalm();
        return surfaceCalm + (1.0 - surfaceCalm)
                * smoothstep((worldPos.y() - terrain) / VentusConfig.fadeHeight());
    }

    /**
     * The temporal fade-in factor: 0 on a sub-level's first sample, ramping to 1 over
     * {@link #ENTRY_RAMP_SECONDS} seconds of game time. Times the sub-level's first sample on the
     * first call.
     */
    private static double entryRamp(final ServerLevel level, final WindCarrier carrier) {
        final long now = level.getGameTime();
        final Long first = carrier.ventus$firstSample();

        if (first == null) {
            carrier.ventus$setFirstSample(now);
            return 0.0;
        }

        // A backwards clock (world reload) restarts the ramp, which is what we want.
        return smoothstep((now - first) / (ENTRY_RAMP_SECONDS * 20.0));
    }

    private static double smoothstep(final double u) {
        if (u <= 0.0) {
            return 0.0;
        }
        if (u >= 1.0) {
            return 1.0;
        }
        return u * u * (3.0 - 2.0 * u);
    }

    /**
     * @param worldVelocity a sub-level's material velocity in world space, in blocks/second
     * @param wind          the wind to subtract, in blocks/second
     * @return the velocity relative to the air ({@code worldVelocity - wind}), never {@code null}
     */
    public static Vector3dc airRelative(final Vector3dc worldVelocity, final Vector3dc wind) {
        if (wind.lengthSquared() == 0.0) {
            return worldVelocity;
        }
        return new Vector3d(worldVelocity).sub(wind);
    }
}
