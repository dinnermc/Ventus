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
 * The wind field for one sub-level sample, in blocks per second (Sable's physics unit,
 * {@code 1 block/s = 1 m/s}). Configured per-dimension base wind, a pressure field, gusts,
 * weather, thermals and orographic lift, scaled by the height profile and the entry ramp; a
 * submerged sample point yields zero wind. {@code -Dventus.wind=x,y,z} overrides the whole
 * field, {@code -Dventus.disableWind=true} forces calm.
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

    /**
     * Both kill switches: the {@code -Dventus.disableWind} system property (frozen at class-load)
     * and the hot-reloadable {@code enabled} config key. Every entry point into the field goes
     * through here so the two can never disagree.
     */
    private static boolean windEnabled() {
        return ENABLED && VentusConfig.enabled();
    }

    /**
     * Session-only base-wind overrides, keyed by dimension, set by {@code /windfield set} and
     * never written to the config file. Everything downstream reads them through
     * {@link #baseWind}, so an override looks like a configured base wind.
     */
    private static final Map<ResourceKey<Level>, Vector3d> BASE_OVERRIDE = new HashMap<>();

    /**
     * Forces this dimension's base wind to {@code wind} until the server restarts or
     * {@link #clearBaseOverride} is called.
     */
    public static void setBaseOverride(final ResourceKey<Level> dimension, final Vector3dc wind) {
        BASE_OVERRIDE.put(dimension, new Vector3d(wind));
    }

    /**
     * Drops this dimension's override.
     *
     * @return {@code true} if there was one to drop
     */
    public static boolean clearBaseOverride(final ResourceKey<Level> dimension) {
        return BASE_OVERRIDE.remove(dimension) != null;
    }

    /** The override in force for this dimension, or {@code null} when the config is used. */
    public static Vector3d baseOverride(final ResourceKey<Level> dimension) {
        final Vector3d override = BASE_OVERRIDE.get(dimension);
        return override == null ? null : new Vector3d(override);
    }

    /**
     * The base wind a dimension actually blows: the {@code /windfield set} override if there is
     * one, otherwise the configured {@code [dimensions]} entry. Both {@link #sample} and
     * {@link #breakdown} go through this one place.
     */
    private static Vector3d baseWind(final ServerLevel level, final Vector3d dest) {
        final Vector3d override = BASE_OVERRIDE.get(level.dimension());
        return override == null ? VentusConfig.baseWind(level.dimension(), dest) : dest.set(override);
    }

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
     * Rain/storm scaling for the perturbations, taken from the dimension's continuous weather
     * levels — two float reads, no chunk/heightmap/biome lookup.
     */
    private static double weatherMultiplier(final ServerLevel level) {
        return WindModel.weatherScale(VentusConfig.rainMultiplier(), VentusConfig.thunderMultiplier(),
                level.getRainLevel(1.0F), level.getThunderLevel(1.0F));
    }

    /** Cached biome warmth, keyed by biome id: a biome's temperature never changes at runtime. */
    private static final Map<ResourceLocation, Double> WARMTH = new HashMap<>();

    /**
     * The height of the ground at a column, or {@link Double#NaN} when its chunk is not loaded;
     * never forces a chunk load. Uses {@link Heightmap.Types#MOTION_BLOCKING_NO_LEAVES}, so water
     * and leaves are not ground.
     */
    private static double surfaceAt(final ServerLevel level, final int x, final int z) {
        return columnAt(level, x, z, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
    }

    private static double columnAt(final ServerLevel level, final int x, final int z,
            final Heightmap.Types type) {
        final LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) {
            return Double.NaN;
        }
        return chunk.getHeight(type, x & 15, z & 15);
    }

    /**
     * How far past dawn and short of dusk the sun is, in {@code [0, 1]}: 0 at night, 1 at midday,
     * ramping over {@link #TWILIGHT_TICKS} ticks. Times {@link #skyAt}, so a roof or a skyless
     * dimension gets no thermal.
     */
    private static double daylightAt(final ServerLevel level, final Vector3dc worldPos) {
        final long dayTime = Math.floorMod(level.getDayTime(), 24000L);
        if (dayTime >= 12000L) {
            return 0.0;
        }

        final double sun = smoothstep(dayTime / (double) TWILIGHT_TICKS)
                * smoothstep((12000L - dayTime) / (double) TWILIGHT_TICKS);
        return sun * skyAt(level, worldPos);
    }

    /**
     * How much of the sun reaches a point, in {@code [0, 1]}: zero without sky light, ramping in
     * over {@link VentusConfig#thermalSkyClearance()} blocks above the terrain
     * ({@link Heightmap.Types#WORLD_SURFACE}, so a canopy shades like a roof). Unknown columns get
     * no sun rather than loading the chunk.
     */
    private static double skyAt(final ServerLevel level, final Vector3dc worldPos) {
        if (!level.dimensionType().hasSkyLight()) {
            return 0.0;
        }

        final int x = Mth.floor(worldPos.x());
        final int z = Mth.floor(worldPos.z());
        final LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) {
            return 0.0;
        }

        final double ceiling = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        final double clearance = VentusConfig.thermalSkyClearance();
        if (clearance <= 0.0) {
            return worldPos.y() >= ceiling ? 1.0 : 0.0;
        }
        return smoothstep((worldPos.y() - ceiling) / clearance);
    }

    /**
     * How hot the biome at a point is, in {@code [0, 1]}: 0 at or below
     * {@link VentusConfig#thermalColdTemperature()}, 1 at {@link VentusConfig#thermalHotTemperature()}.
     * Cached by biome id; an unloaded chunk yields 0.
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
     * The rise-over-run of the ground along the base wind direction, or {@link Double#NaN} when
     * a probe column is not loaded: positive = windward, negative = lee. The probing and the
     * 45&deg; clamp live in {@link WindModel#slopeAlongWind}.
     */
    private static double slopeAlongWind(final ServerLevel level, final Vector3dc worldPos,
            final double dirX, final double dirZ) {
        return WindModel.slopeAlongWind((x, z) -> surfaceAt(level, Mth.floor(x), Mth.floor(z)),
                worldPos.x(), worldPos.z(), dirX, dirZ, VentusConfig.slopeProbe());
    }

    /**
     * Fills {@code out} with the vertical air speed at a point — thermals plus orographic lift,
     * kept apart so the diagnostics command can report each one. Computed before the height profile
     * and the entry ramp, since the caller scales the whole wind vector by those afterwards.
     */
    private static void verticalAt(final NoiseSet noise, final ServerLevel level, final Vector3dc worldPos,
            final double horizontalSpeed, final double slope, final double decay, final Vertical out) {
        out.thermal = 0.0;
        out.orographic = 0.0;

        final double thermalStrength = VentusConfig.thermalStrength();
        if (thermalStrength > 0.0) {
            final double nt = level.getGameTime() / 20.0 / VentusConfig.thermalPeriod();
            final double bubble = noise.thermal().getValue(
                    worldPos.x() / VentusConfig.thermalScale(), worldPos.z() / VentusConfig.thermalScale(), nt);
            final BlockPos pos = new BlockPos(Mth.floor(worldPos.x()), Mth.floor(worldPos.y()),
                    Mth.floor(worldPos.z()));
            out.thermal = WindModel.thermal(bubble, daylightAt(level, worldPos), warmthAt(level, pos),
                    1.0 - level.getRainLevel(1.0F), thermalStrength);
        }

        final double slopeStrength = VentusConfig.slopeStrength();
        if (slopeStrength > 0.0 && !Double.isNaN(slope)) {
            out.orographic = WindModel.orographic(horizontalSpeed, slope, slopeStrength) * decay;
        }
    }

    /** The two vertical terms (thermal + orographic) at one sample point. */
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
     * @return {@code true} when {@code -Dventus.disableWind} is on, in which case the field is
     * zero everywhere and no work needs doing
     */
    public static boolean isCalm() {
        return !ENABLED;
    }

    /**
     * Applies the jet stream and the wind shear to a dimension's base wind, in place. Both key on
     * absolute altitude (a jet is a band of fast air, shear a gradient through the atmosphere), and
     * this runs before the budget is sized, so the clamp tracks the air that actually blows here.
     * {@code record} notes the individual multipliers, or is {@code null} on the hot path.
     */
    private static void applyAltitude(final Vector3d dest, final double altitude, final Breakdown record) {
        final double maxAltitude = VentusConfig.shearMaxAltitude();
        final double jet = WindModel.jetFactor(altitude, VentusConfig.jetAltitude(),
                VentusConfig.jetThickness(), VentusConfig.jetStrength());
        final double shear = WindModel.shearFactor(altitude, VentusConfig.shearSpeedPer100(),
                maxAltitude);
        final double turn = WindModel.shearDegrees(altitude, VentusConfig.shearDegreesPer100(),
                maxAltitude);

        if (record != null) {
            record.jet = jet;
            record.shear = shear;
            record.shearDegrees = turn;
        }

        WindModel.rotateXZ(dest, turn);
        if (jet * shear != 1.0) {
            dest.mul(jet * shear);
        }
    }

    /**
     * Samples the wind for one sub-level, writing it into the carrier's
     * {@link WindCarrier#ventus$wind()} and updating the carrier's cached fade and first-sample time.
     */
    public static void sample(final ServerLevel level, final Vector3dc worldPos, final WindCarrier carrier) {
        final Vector3d dest = carrier.ventus$wind();

        if (!windEnabled()) {
            carrier.ventus$setFade(0.0);
            dest.set(0.0, 0.0, 0.0);
            return;
        }

        if (FIXED_OVERRIDE != null) {
            carrier.ventus$setFade(1.0);
            dest.set(FIXED_OVERRIDE);
            return;
        }

        baseWind(level, dest);
        if (dest.lengthSquared() == 0.0) {
            carrier.ventus$setFade(0.0);
            return;
        }

        // Gusts are sized from the base wind before the altitude profile: a jet is fast, not rough.
        final double referenceMag = dest.length();
        applyAltitude(dest, worldPos.y(), null);

        final double fade = fadeAt(level, worldPos, carrier.ventus$fade());
        carrier.ventus$setFade(fade);

        // After the jet and shear this is the wind here: slope runs along it, its magnitude is the budget.
        final double baseMag = dest.length();
        final double windScale = VentusConfig.windScale();
        final double entry = entryRamp(level, carrier);

        // Budget = the prevailing wind *here* (profile, fade-in, windScale), so a jet raises the cap with it.
        final double scale = fade * entry * windScale;
        final double prevailing = baseMag * scale;

        final NoiseSet noise = noiseSet(level);
        final WindModel.Settings settings = VentusConfig.windModelSettings();

        // Terrain-locked terms decay with altitude; thermals do not (a thermal exists because it rose).
        final double terrain = surfaceAt(level, Mth.floor(worldPos.x()), Mth.floor(worldPos.z()));
        final double decay = Double.isNaN(terrain)
                ? 1.0 : WindModel.heightDecay(worldPos.y() - terrain, VentusConfig.slopeHeightFalloff());

        final double slope = slopeAlongWind(level, worldPos, dest.x(), dest.z());
        final double leeBoost = Double.isNaN(slope)
                ? 0.0 : WindModel.leeGustBoost(slope, settings.leeGustBoost()) * decay;

        WindModel.evaluate(noise.pressure()::getValue, noise.gust()::getValue, worldPos.x(), worldPos.z(),
                level.getGameTime() / 20.0, dest, referenceMag, weatherMultiplier(level), leeBoost,
                settings, MODEL_RESULT);

        verticalAt(noise, level, worldPos,
                WindModel.alongBaseWind(dest.x(), dest.z(), MODEL_RESULT.total.x, MODEL_RESULT.total.z),
                slope, decay, VERTICAL);

        // Ease the terrain-derived terms (integer heightmaps would step them); gusts stay unsmoothed.
        final double vertical = WindModel.approach(carrier.ventus$vertical(), VERTICAL.total(),
                VentusConfig.verticalSmoothSeconds(), 1.0 / 20.0);
        carrier.ventus$setVertical(vertical);

        dest.set(MODEL_RESULT.total).mul(scale);
        dest.y = vertical * scale;

        clampToBudget(dest, prevailing);
    }

    /**
     * Caps the finished wind at {@code maxWindMultiplier} times the prevailing wind here (the base
     * after the height profile, fade-in and {@code windScale}), and guards against a NaN ever
     * reaching a rigid body.
     */
    private static void clampToBudget(final Vector3d wind, final double prevailing) {
        WindModel.clampToBudget(wind, prevailing, VentusConfig.maxWindMultiplier());
    }

    /**
     * A one-off, steady-state sample at a point, for diagnostics (the {@code /windfield} command).
     */
    public static Vector3d sampleAt(final ServerLevel level, final Vector3dc worldPos, final Vector3d dest) {
        return breakdown(level, worldPos, BREAKDOWN).into(dest);
    }

    /**
     * Fills {@code dest} with the separated components of the steady-state wind at a point. Unlike
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
        dest.jet = 1.0;
        dest.shear = 1.0;
        dest.shearDegrees = 0.0;

        // Read both heightmaps before any early return: /windfield info prints them verbatim.
        final int columnX = Mth.floor(worldPos.x());
        final int columnZ = Mth.floor(worldPos.z());
        dest.groundHeight = surfaceAt(level, columnX, columnZ);
        dest.floorHeight = columnAt(level, columnX, columnZ, Heightmap.Types.OCEAN_FLOOR);

        if (!windEnabled()) {
            return dest;
        }

        if (FIXED_OVERRIDE != null) {
            dest.base.set(FIXED_OVERRIDE);
            dest.total.set(FIXED_OVERRIDE);
            return dest;
        }

        baseWind(level, dest.base);
        if (dest.base.lengthSquared() == 0.0) {
            return dest;
        }
        final double referenceMag = dest.base.length();
        applyAltitude(dest.base, worldPos.y(), dest);

        final double fade = fadeAt(level, worldPos, 1.0);
        dest.heightFactor = fade;

        // dest.base is the wind here; once scaled below, its magnitude is the clamp's budget.
        final double baseMag = dest.base.length();
        final double windScale = VentusConfig.windScale();

        final NoiseSet noise = noiseSet(level);
        final double weather = weatherMultiplier(level);
        dest.weather = weather;

        final WindModel.Settings settings = VentusConfig.windModelSettings();
        final double terrain = dest.groundHeight;
        final double decay = Double.isNaN(terrain)
                ? 1.0 : WindModel.heightDecay(worldPos.y() - terrain, VentusConfig.slopeHeightFalloff());

        final double slope = slopeAlongWind(level, worldPos, dest.base.x(), dest.base.z());
        dest.slope = slope;
        final double leeBoost = Double.isNaN(slope)
                ? 0.0 : WindModel.leeGustBoost(slope, settings.leeGustBoost()) * decay;
        dest.leeBoost = leeBoost;

        WindModel.evaluate(noise.pressure()::getValue, noise.gust()::getValue, worldPos.x(), worldPos.z(),
                level.getGameTime() / 20.0, dest.base, referenceMag, weather, leeBoost, settings,
                MODEL_RESULT);

        verticalAt(noise, level, worldPos,
                WindModel.alongBaseWind(dest.base.x(), dest.base.z(), MODEL_RESULT.total.x,
                        MODEL_RESULT.total.z),
                slope, decay, VERTICAL);
        dest.thermal = VERTICAL.thermal;
        dest.orographic = VERTICAL.orographic;

        final double scale = fade * windScale;
        dest.base.mul(scale);
        dest.field.set(MODEL_RESULT.field).mul(scale);
        dest.gust.set(MODEL_RESULT.gust).mul(scale);
        dest.total.set(MODEL_RESULT.total).mul(scale);
        dest.total.y = (dest.thermal + dest.orographic) * scale;
        dest.capped = MODEL_RESULT.capped;
        clampToBudget(dest.total, baseMag * scale);
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
        /** The thermal updraft at this point, before the height profile. */
        public double thermal;
        /** The orographic up- or downdraft at this point, before the height profile. */
        public double orographic;
        /** The jet stream's multiplier on the base wind at this altitude (1 = outside the band). */
        public double jet;
        /** The wind shear's speed multiplier at this altitude (1 = no shear here). */
        public double shear;
        /** How far the wind's heading has turned at this altitude, in degrees. */
        public double shearDegrees;
        /** Ground height, or {@code NaN} if unloaded: MOTION_BLOCKING_NO_LEAVES — what {@link #slope} uses. */
        public double groundHeight;
        /** Same column as OCEAN_FLOOR (leaves in, liquids out): what {@link #heightFactor} ramps from. */
        public double floorHeight;
        public boolean capped;

        /** Copies {@link #total} into {@code dest} and returns it. */
        public Vector3d into(final Vector3d dest) {
            return dest.set(this.total);
        }
    }

    /**
     * The height profile factor at a point: {@link VentusConfig#surfaceCalm()} at or below the
     * terrain, ramping to 1 at {@link VentusConfig#fadeHeight()} blocks above it, 0 if submerged.
     * {@code unknownChunkFade} is what an unloaded terrain returns (the caller's last factor).
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

        // OCEAN_FLOOR here (shelter — water doesn't count); terrain terms use MOTION_BLOCKING_NO_LEAVES.
        final double terrain = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x & 15, z & 15);
        final double surfaceCalm = VentusConfig.surfaceCalm();
        return surfaceCalm + (1.0 - surfaceCalm)
                * smoothstep((worldPos.y() - terrain) / VentusConfig.fadeHeight());
    }

    /**
     * The temporal fade-in factor: 0 on a sub-level's first sample, ramping to 1 over
     * {@link #ENTRY_RAMP_SECONDS} seconds of game time. The first call records the sample time and
     * returns 0.
     */
    private static double entryRamp(final ServerLevel level, final WindCarrier carrier) {
        final long now = level.getGameTime();
        final Long first = carrier.ventus$firstSample();

        if (first == null) {
            carrier.ventus$setFirstSample(now);
            return 0.0;
        }

        // A backwards clock (world reload) restarts the ramp.
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
