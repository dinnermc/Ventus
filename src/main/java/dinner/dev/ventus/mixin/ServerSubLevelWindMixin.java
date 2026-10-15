package dinner.dev.ventus.mixin;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dinner.dev.ventus.WindField;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
 * Sable's universal drag is applied inside the native physics pipeline and cannot be reached from
 * Java, so {@link #ventus$windDrag} instead injects the compensating {@code mass * d * w * dt}
 * impulse that makes that drag act on the air-relative velocity. See the method for details.
 * <p>
 * {@code BlockSubLevelLiftProvider} is an interface and Mixin cannot inject into interface
 * methods without {@code @Overwrite} (forbidden, a third-party patch already owns that method),
 * hence the hook on the concrete caller.
 */
@Mixin(targets = "dev.ryanhcode.sable.sublevel.ServerSubLevel", remap = false)
public abstract class ServerSubLevelWindMixin {

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

    /**
     * The main sub-level lift &amp; drag pass (one call per lift provider).
     */
    @ModifyArg(
            method = PRE_PHYSICS_TICK,
            at = @At(value = "INVOKE", target = CONTRIBUTE_LIFT_AND_DRAG, ordinal = 0),
            index = LINEAR_VELOCITY_ARG
    )
    private static Vector3dc ventus$windOnMainLiftProviders(final Vector3dc linearVelocity) {
        return WindField.airRelative(linearVelocity);
    }

    /**
     * The kinematic-contraption lift &amp; drag pass (one call per contraption lift provider).
     */
    @ModifyArg(
            method = PRE_PHYSICS_TICK,
            at = @At(value = "INVOKE", target = CONTRIBUTE_LIFT_AND_DRAG, ordinal = 1),
            index = LINEAR_VELOCITY_ARG
    )
    private static Vector3dc ventus$windOnContraptionLiftProviders(final Vector3dc linearVelocity) {
        return WindField.airRelative(linearVelocity);
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
     */
    @Inject(method = PRE_PHYSICS_TICK, at = @At("HEAD"))
    private void ventus$windDrag(final SubLevelPhysicsSystem physicsSystem, final RigidBodyHandle handle,
            final double timeStep, final CallbackInfo ci) {
        if (WindField.isCalm()) {
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

        final Vector3d impulse = new Vector3d(WindField.wind()).mul(mass * drag * timeStep);
        handle.applyLinearAndAngularImpulse(impulse, JOMLConversion.ZERO, false);
    }
}
