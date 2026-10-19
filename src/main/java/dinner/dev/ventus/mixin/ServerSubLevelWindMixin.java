package dinner.dev.ventus.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.physics.floating_block.FloatingBlockController;
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
 * Feeds Sable's lift and drag the velocity of a sub-level relative to the air instead of the
 * world, at the three places in {@code ServerSubLevel.prePhysicsTick} that turn a body velocity
 * into a force. The wind is sampled once per game tick by {@code WindSampler} and stored in the
 * {@code @Unique} fields here; this mixin implements {@link WindCarrier} so the sampler can
 * reach that state on any {@code ServerSubLevel}.
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

    private static final String FLOATING_PHYSICS_TICK =
            "Ldev/ryanhcode/sable/physics/floating_block/FloatingBlockController;physicsTick("
                    + "D"
                    + "D"
                    + "Lorg/joml/Vector3dc;"
                    + "Lorg/joml/Vector3dc;"
                    + "Lorg/joml/Vector3d;"
                    + "Lorg/joml/Vector3d;)V";

    private static final int LINEAR_VELOCITY_ARG = 4;

    private static final boolean DEBUG_WIND = Boolean.getBoolean("ventus.debugWind");
    private static final long[] ventus$debugNext = new long[4];
    private static boolean ventus$hookThreadLogged;

    // Wind state, written once per tick by WindSampler. Left uninitialised: the JVM
    // defaults (null / 0.0) are the right starting state, and this avoids depending on Mixin
    // copying field initialisers.
    @Unique
    private Vector3d ventus$wind;

    @Unique
    private double ventus$fade;

    @Unique
    private Long ventus$firstSample;

    @Unique
    private double ventus$vertical;

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

    @Override
    public double ventus$vertical() {
        return this.ventus$vertical;
    }

    @Override
    public void ventus$setVertical(final double vertical) {
        this.ventus$vertical = vertical;
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
     * The main sub-level lift and drag pass (one call per lift provider).
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
     * The kinematic-contraption lift and drag pass (one call per contraption lift provider).
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
     * The floating-block pass: balloons, rafts, anything made only of floating blocks. These
     * have no lift providers, so they never reach either call above. Handing the controller the
     * air-relative velocity turns its world-space friction into a sail: it damps {@code v - w},
     * which is zero exactly when the sub-level moves with the air. Wrapped rather than redirected
     * so other mods can wrap the same call site too.
     */
    @WrapOperation(
            method = PRE_PHYSICS_TICK,
            at = @At(value = "INVOKE", target = FLOATING_PHYSICS_TICK)
    )
    private void ventus$windOnFloatingBlocks(final FloatingBlockController controller,
            final double partialPhysicsTick, final double timeStep, final Vector3dc linearVelocity,
            final Vector3dc angularVelocity, final Vector3d linearImpulse, final Vector3d angularImpulse,
            final Operation<Void> original) {
        final Vector3dc wind = this.ventus$wind();
        final Vector3dc air = WindField.airRelative(linearVelocity, wind);
        if (ventus$debugDue(3)) {
            Ventus.LOGGER.info("[ventus] floating-pass wind={} vel={} -> air={}",
                    ventus$v(wind), ventus$v(linearVelocity), ventus$v(air));
        }
        original.call(controller, partialPhysicsTick, timeStep, air, angularVelocity, linearImpulse, angularImpulse);
    }

    /**
     * Adds the {@code mass * drag * wind * dt} impulse per tick that makes Sable's native
     * universal drag act on the air-relative velocity instead of the world velocity, shifting its
     * equilibrium from {@code v = 0} to {@code v = w}. Injected at HEAD so sub-levels whose tick
     * body is skipped still get it; a no-op while the wind is calm.
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
