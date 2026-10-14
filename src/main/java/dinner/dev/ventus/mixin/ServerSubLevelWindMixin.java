package dinner.dev.ventus.mixin;

import dinner.dev.ventus.WindField;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Feeds Sable's lift &amp; drag the velocity of a sub-level <em>relative to the air</em> instead
 * of relative to the world, by rewriting the {@code linearVelocity} argument of the two
 * {@code BlockSubLevelLiftProvider.sable$contributeLiftAndDrag} calls in
 * {@code ServerSubLevel.prePhysicsTick}.
 * <p>
 * Why this call site: the providers compute their aerodynamic forces from the velocity they are
 * handed, so removing the wind makes a flying sub-level accelerate until it matches the air speed.
 * The argument is read-only ({@code Vector3dc}) and never written back to the body, so we only
 * affect the forces derived from it. It is scoped to the lift/drag calls on purpose:
 * {@code floatingBlockController.physicsTick} and the universal drag (applied inside Sable's
 * native physics pipeline) are left untouched.
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
}
