# Ventus development

Build and test (PowerShell):

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
.\gradlew build --console=plain
.\gradlew test --console=plain
.\gradlew runClient --console=plain
.\gradlew runServer --console=plain
```

Debug switches — development aids only, they bypass the config file and map to `-Dventus.*` system properties:

| switch | effect |
| --- | --- |
| `-Pventus.wind=x,y,z` | replaces the whole field with a fixed vector: no base wind, no height profile, no gusts |
| `-Pventus.disableWind=true` | calm (zero) wind everywhere |
| `-Pventus.debugWind=true` | 1/s logging of the wind hooks: sampled wind, passed velocity, air-relative velocity, the `d*w` impulse |
| `-Pventus.exportMixin=true` | dumps mixin-transformed classes to `run/.mixin.out` for inspection |

e.g. `.\gradlew runClient -Pventus.wind=15,0,0`

How the mixin hook works:

- `ServerSubLevelWindMixin` targets `ServerSubLevel.prePhysicsTick` and hands the force call sites the air-relative velocity (`v - w`). Two sites are the `sable$contributeLiftAndDrag` calls (`@ModifyArg`); the third is `FloatingBlockController.physicsTick`, wrapped with `@WrapOperation` because two `double`s precede the velocity there and the argument index is easy to miscount — a wrap also composes with other mods' mixins where a `@Redirect` would collide.
- The floating pass is what makes balloons drift: `applyFriction` opposes world velocity and pulls towards `v = 0`, so it only sails when handed `v - w`. Ships and aircraft take the lift-provider path and are unaffected.
- `ventus$windDrag` injects `mass * d * w * dt` at the centre of mass, moving the universal damping equilibrium from `v = 0` to `v = w`.
- The mixin implements `WindCarrier` for the per-tick wind state: `WindSampler` writes it, the hooks read it, both on the server thread.

The hooks go into the concrete caller rather than the `BlockSubLevelLiftProvider` interface, so Sable is pinned to `[2.0.6, 2.1)` in `gradle.properties`.

Player-facing docs live in the [wiki](https://github.com/dinnermc/Ventus/wiki); the short [README](../README.md) is the front door.
