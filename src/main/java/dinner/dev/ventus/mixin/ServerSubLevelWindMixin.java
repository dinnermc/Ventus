package dinner.dev.ventus.mixin;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dinner.dev.ventus.Ventus;
import dinner.dev.ventus.WindCarrier;
import dinner.dev.ventus.WindField;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;

/**
 * Feeds Sable's lift &amp; drag the velocity of a sub-level <em>relative to the air</em> instead
 * of relative to the world, by rewriting the {@code linearVelocity} argument of the two
 * {@code BlockSubLevelLiftProvider.sable$contributeLiftAndDrag} calls in
 * {@code ServerSubLevel.prePhysicsTick}.
 * <p>
 * Why this call site: the providers compute their aerodynamic forces from the velocity they are
 * handed, so removing the wind makes a flying sub-level accelerate until it matches the air speed.
 * The argument is read-only ({@code Vector3dc}) and never written back to the body, so we only
 * affect the forces derived from it.
 * <p>
 * The wind is sampled once per game tick by {@code WindSampler} and stored in this mixin's
 * {@code @Unique} fields; the hooks here only read them. This mixin implements {@link WindCarrier}
 * so the sampler can reach that state on any {@code ServerSubLevel}.
 * <p>
 * Sable's universal drag is applied inside the native physics pipeline and cannot be reached from
 * Java, so {@link #ventus$windDrag} instead injects the compensating {@code mass * d * w * dt}
 * impulse that makes that drag act on the air-relative velocity. See the method for details.
 * <p>
 * {@code BlockSubLevelLiftProvider} is an interface and Mixin cannot inject into interface
 * methods without {@code @Overwrite} (forbidden, a third-party patch already owns that method),
 * hence the hook on the concrete caller.
 */
@Mixin(targets = "dev.ryanhcode.sable.sublevel.ServerSubLevel", remap = false)
public abstract class ServerSubLevelWindMixin implements WindCarrier {

    private static final String PRE_PHYSICS_TICK =
            "prePhysicsTick(Ldev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem;"
                    + "Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;D)V";

    private static final String CONTRIBUTE_LIFT_AND_DRAG =
            "Ldev/ryanhcode/sable/api/block/BlockSubLevelLiftProvider;sable$contributeLiftAndDrag("
                    + "Ldev/ryanhcode/sable/api/block/BlockSubLevelLiftProvider$LiftProviderContext;"
                    + "Ldev/ryanhcode/sable/sublevel/ServerSubLevel;"
                    + "Ldev/ryanhcode/sable/companion/math/Pose3d;"
                    + "D"
                    + "Lorg/joml/Vector3dc;"
                    + "Lorg/joml/Vector3dc;"
                    + "Lorg/joml/Vector3d;"
                    + "Lorg/joml/Vector3d;"
                    + "Ldev/ryanhcode/sable/api/block/BlockSubLevelLiftProvider$LiftProviderGroup;)V";

    private static final int LINEAR_VELOCITY_ARG = 4;

    private static final boolean DEBUG_WIND = Boolean.getBoolean("ventus.debugWind");
    private static final long[] ventus$debugNext = new long[3];
    private static boolean ventus$hookThreadLogged;

    // Wind state, written once per tick by WindSampler. Left uninitialised on purpose: the JVM
    // defaults (null / 0.0) are the right starting state, and this avoids depending on Mixin
    // copying field initialisers.
    @Unique
    private Vector3d ventus$wind;

    @Unique
    private double ventus$fade;

    @Unique
    private Long ventus$firstSample;

    @Override
    public Vector3d ventus$wind() {
        if (this.ventus$wind == null) {
            this.ventus$wind = new Vector3d();
        }
        return this.ventus$wind;
    }

    @Override
    public double ventus$fade() {
        return this.ventus$fade;
    }

    @Override
    public void ventus$setFade(final double fade) {
        this.ventus$fade = fade;
    }

    @Override
    public Long ventus$firstSample() {
        return this.ventus$firstSample;
    }

    @Override
    public void ventus$setFirstSample(final Long gameTime) {
        this.ventus$firstSample = gameTime;
    }

    private static boolean ventus$debugDue(final int slot) {
        if (!DEBUG_WIND) {
            return false;
        }
        final long now = System.nanoTime();
        if (now - ventus$debugNext[slot] < 1_000_000_000L) {
            return false;
        }
        ventus$debugNext[slot] = now;
        return true;
    }

    private static String ventus$v(final Vector3dc v) {
        return String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)", v.x(), v.y(), v.z());
    }

    /**
     * The main sub-level lift &amp; drag pass (one call per lift provider).
     */
    @ModifyArg(
            method = PRE_PHYSICS_TICK,
            at = @At(value = "INVOKE", target = CONTRIBUTE_LIFT_AND_DRAG, ordinal = 0),
            index = LINEAR_VELOCITY_ARG
    )
    private Vector3dc ventus$windOnMainLiftProviders(final Vector3dc linearVelocity) {
        final Vector3dc wind = this.ventus$wind();
        final Vector3dc air = WindField.airRelative(linearVelocity, wind);
        if (ventus$debugDue(0)) {
            Ventus.LOGGER.info("[ventus] main-pass wind={} vel={} -> air={}",
                    ventus$v(wind), ventus$v(linearVelocity), ventus$v(air));
        }
        return air;
    }

    /**
     * The kinematic-contraption lift &amp; drag pass (one call per contraption lift provider).
     */
    @ModifyArg(
            method = PRE_PHYSICS_TICK,
            at = @At(value = "INVOKE", target = CONTRIBUTE_LIFT_AND_DRAG, ordinal = 1),
            index = LINEAR_VELOCITY_ARG
    )
    private Vector3dc ventus$windOnContraptionLiftProviders(final Vector3dc linearVelocity) {
        final Vector3dc wind = this.ventus$wind();
        final Vector3dc air = WindField.airRelative(linearVelocity, wind);
        if (ventus$debugDue(1)) {
            Ventus.LOGGER.info("[ventus] contraption-pass wind={} vel={} -> air={}",
                    ventus$v(wind), ventus$v(linearVelocity), ventus$v(air));
        }
        return air;
    }

    /**
     * Emulates Sable's universal drag acting on the velocity <em>relative to the air</em> instead of
     * the world velocity, by adding the missing {@code mass * d * w * dt} impulse once per physics
     * tick.
     * <p>
     * Sable applies its universal linear damping inside the native Rapier scene (initialised with
     * {@code universalDrag}), where it damps the body's world velocity ({@code v *= 1 / (1 + dt * d)}).
     * Wind has to damp the velocity relative to the moving air, i.e. the net change should be
     * {@code -d * (v - w) * dt}. Injecting {@code +m * d * w * dt} here shifts the damping equilibrium
     * from {@code v = 0} to {@code v = w}, so every ticking sub-level drifts with the wind.
     * <p>
     * The drag coefficient is measured: Sable's universal decay is exponential with {@code d = 0.09}
     * (five points, {@code t = 1..8s}, error &lt; 0.3%). Applied at the centre of mass, so it produces
     * no torque (linear only, matching the native linear damping).
     * <p>
     * Injected at {@code HEAD} on purpose: the body of {@code prePhysicsTick} is skipped for
     * sub-levels without lift providers, contraptions, reaction wheels or floating-block ticking
     * (a plain block), but those bodies are still simulated and dragged natively. Applying the
     * impulse before that guard makes the wind drag global, exactly like the native universal drag.
     * <p>
     * The per-tick wind is already zero where the field is calm, near the ground, or during the
     * fade-in, so this is a no-op for those sub-levels.
     */
    @Inject(method = PRE_PHYSICS_TICK, at = @At("HEAD"))
    private void ventus$windDrag(final SubLevelPhysicsSystem physicsSystem, final RigidBodyHandle handle,
            final double timeStep, final CallbackInfo ci) {
        if (DEBUG_WIND && !ventus$hookThreadLogged) {
            ventus$hookThreadLogged = true;
            Ventus.LOGGER.info("[ventus] hook thread={} (see sampler thread above)", Thread.currentThread().getName());
        }

        final Vector3d wind = this.ventus$wind();
        if (wind.lengthSquared() == 0.0) {
            return;
        }

        final ServerSubLevel subLevel = (ServerSubLevel) (Object) this;
        final MassData massData = subLevel.getMassTracker();
        if (massData == null) {
            return;
        }

        final double mass = massData.getMass();
        if (mass <= 0.0 || Double.isNaN(mass) || Double.isInfinite(mass)) {
            return;
        }

        final double drag = DimensionPhysicsData.getUniversalDrag(subLevel.getLevel());
        if (drag <= 0.0) {
            return;
        }

        final Vector3d impulse = new Vector3d(wind).mul(mass * drag * timeStep);
        handle.applyLinearAndAngularImpulse(impulse, JOMLConversion.ZERO, false);

        if (ventus$debugDue(2)) {
            Ventus.LOGGER.info("[ventus] impulse sub={} mass={} drag={} dt={} wind={} v={}",
                    subLevel.getUniqueId(),
                    String.format(Locale.ROOT, "%.3f", mass),
                    String.format(Locale.ROOT, "%.4f", drag),
                    String.format(Locale.ROOT, "%.4f", timeStep),
                    ventus$v(wind),
                    ventus$v(handle.getLinearVelocity(new Vector3d())));
        }
    }
}
