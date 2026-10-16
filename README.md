# Ventus

Wind field mod for **Minecraft 1.21.1 / NeoForge 21.1.259** that makes Sable sub-levels
(sails, planes, boats) feel the air move.

Ventus hooks into [Sable's](https://github.com/RyanHCode) sub-level physics and feeds its lift and
drag the velocity **relative to the air** instead of relative to the world, plus an impulse that
makes Sable's universal drag act on that same air-relative velocity. Without it, a sub-level only
experiences wind while it is already moving.

## The wind field

The field is sampled once per game tick for every sub-level (`WindSampler`) and stored on the
sub-level, then read by the physics hooks (`ServerSubLevelWindMixin`). It is built from these terms:

| Factor | Where | What |
| --- | --- | --- |
| Base wind | `config/ventus-server.toml` | Magnitude (m/s) + direction (degrees) per dimension. Default: Overworld **3 m/s toward +X (east)**, Nether and End calm. |
| Pressure field | config: `fieldScale`, `fieldPeriod`, `fieldStrength` | A 3D-simplex pressure potential `P(x/L, z/L, t/T)`; its gradient rotated 90° in the XZ plane deflects the base wind so the prevailing direction still dominates but the wind turns and varies across `fieldScale` (1500) blocks and `fieldPeriod` (1200 s). `fieldStrength` scales it (1 = typical deflection equals the base wind). |
| Gusts | config: `gustScale`, `gustPeriod`, `gustAmplitude`, `gustLateralFraction` | A second, shorter-wavelength noise that puffs mostly **along** the wind (with a small lateral fraction), at `gustAmplitude` (35%) of the base wind, over `gustScale` (150) blocks and `gustPeriod` (120 s). |
| Height profile | config: `fadeHeight`, `surfaceCalm` | Wind ramps with altitude above the terrain: `surfaceCalm` (10%) at the surface to full at `fadeHeight` (32 blocks), smoothstep in between. Uses vanilla `OCEAN_FLOOR` heightmap (solid blocks; **water ignored**, **leaves count as terrain**), so a boat floating on the sea sits above its reference and still catches the wind, while a plane parked on the ground feels almost nothing. A **submerged** sample point (centre block holds a fluid) gets **zero** wind. |
| Fade-in | `WindField` | 0 to full over **5 seconds** after a sub-level's first sample, so nothing jolts on world load or when a sub-level enters a windy region. |
| Cap | config: `maxWindMultiplier` | The total never exceeds this multiple (**3×**) of the dimension's base wind magnitude, so an extreme noise value or bad parameter can never produce an absurd wind. |
| Scale | config: `windScale` | Global multiplier applied to the total field. |

If the terrain chunk under a sub-level is not loaded, the last known height factor is reused (a new
sub-level starts from zero) instead of jumping to full wind.

Direction convention: **0° = +X (east)**, **90° = +Z (south)**, in the XZ plane.

The field math lives in `WindModel`, a Minecraft-free class (JOML only) with unit tests in
`src/test`. Later stages: rain/thunder scaling, then registration with Sable's own wind-provider API.

### Tuning

Edit `run/config/ventus-server.toml` while the game is running — it is hot-reloaded, no restart:

```toml
windScale = 1.0
fadeHeight = 32.0
surfaceCalm = 0.1
fieldScale = 1500.0
fieldPeriod = 1200.0
fieldStrength = 1.0
maxWindMultiplier = 3.0
gustScale = 150.0
gustPeriod = 120.0
gustAmplitude = 0.35
gustLateralFraction = 0.3

[dimensions.overworld]
    magnitude = 3.0
    direction = 0.0
```

It is a NeoForge **server** config, so it lives in `<instance>/config/ventus-server.toml` and is
shared by every world in that instance. To override it for a single world, drop the same file in
that world's `<world>/serverconfig/ventus-server.toml` instead — NeoForge reads that folder on top
of the instance config, which is handy for per-world tuning on a dedicated server.

## Debug options

These are **development aids, not configuration**. They bypass the config file.

| Option | Effect |
| --- | --- |
| `-Pventus.wind=x,y,z` | Replaces the **whole** field with a fixed vector: no base wind, no height profile, no `windScale`, no gusts. Debug only. |
| `-Pventus.disableWind=true` | Forces a calm (zero) wind everywhere. |
| `-Pventus.debugWind=true` | Rate-limited (1/s) logging of the wind hooks: sampled wind, passed velocity, resulting air-relative velocity, and the `d*w` impulse. |
| `-Pventus.exportMixin=true` | Dumps mixin-transformed classes to `run/.mixin.out` for inspection. |

Example:

```powershell
.\gradlew runClient -Pventus.wind=15,0,0
.\gradlew runClient -Pventus.debugWind=true
```

These map to the `-Dventus.wind` / `-Dventus.disableWind` / `-Dventus.debugWind` system properties.

## Commands

`/windfield info` (permission level 2) reports, for the sub-level you are standing on or riding:

- the wind sampled for that sub-level, and `windActive` (whether it is non-zero)
- the steady-state breakdown: `base`, `field`, `gust`, `heightFactor`, `capped`, and `total`
- `universalDrag` (the drag coefficient the `d*w` impulse uses) and the sub-level's mass
- the sub-level's material velocity and the **air-relative velocity** Sable's lift & drag actually sees

`/windfield info <pos>` does the same for the sub-level containing `<pos>` — usable from a console
or RCON without a player.

## Testing

Stage 1 checks (client; use `/windfield info` for live values):

1. **Vertical profile** — near the ground the factor is `surfaceCalm` (0.1) and it climbs to 1 at
   `fadeHeight` (32) blocks above the terrain, so the reported `wind` goes from ~0.3 to full (3 m/s
   by default) as you rise.
2. **Over the sea** — a boat far from land catches the full wind (water is not terrain) and drifts.
3. **Over rising terrain** — flying up a hill reduces the wind, since the reference height rises with
   the ground.
4. **Fade-in** — reload the world with a plane already airborne: the wind climbs from zero to full
   over ~5 s instead of appearing at once.
5. **On the ground** — a parked plane no longer slides or weathervanes (near-zero wind at the surface).
6. **Dedicated server** — works with and without the client patch (the patch is client-optional).

`-Pventus.debugWind=true` logs the sampler and hook threads once each (they should both read
`Server thread`) plus rate-limited wind values.

Stage 2 checks:

1. **Smooth, continuous wind (2a)** — fly straight and level for a couple of minutes. The wind should
   change gradually; no jumps. (`/windfield info` shows `field` growing and shrinking smoothly.)
2. **Direction still dominates (2a)** — with `fieldStrength = 1`, the wind should still blow broadly
   the way `[dimensions.overworld] direction` says, only wobbling around it.
3. **The cap holds (2a)** — raise `fieldStrength` high; the total should saturate at
   `maxWindMultiplier × base` rather than exploding (`/windfield info` shows `capped=true`).
4. **Gusts (2b)** — raise `gustAmplitude` (e.g. to `1.0`) and watch the wind speed up and ease off,
   mostly along the same direction. Still no discontinuities.
5. **No beachballing (2b)** — with gusts on, tick time should be unaffected (two extra noise samples
   per sub-level per tick).

## Building & testing

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
.\gradlew build --console=plain
.\gradlew test --console=plain
.\gradlew runClient --console=plain
.\gradlew runServer --console=plain
```

## How the hook works

- `ServerSubLevelWindMixin` targets `ServerSubLevel.prePhysicsTick` and rewrites (via `@ModifyArg`)
  the `linearVelocity` argument of the two `BlockSubLevelLiftProvider.sable$contributeLiftAndDrag`
  call sites to the air-relative velocity. The argument is read-only, so only the forces derived
  from it change.
- The mixin implements `WindCarrier` to expose its `@Unique` wind state to `WindSampler` (the wind
  vector, the last height factor, and the first-sample time). The state is written by `WindSampler`
  and read by the hooks, both on the server thread, so no synchronisation is needed.
- `ventus$windDrag` injects `mass * d * w * dt` at the centre of mass, shifting the native universal
  damping equilibrium from `v = 0` to `v = w` so every ticking sub-level drifts with the wind.

Because the hooks are injected into the concrete caller rather than the `BlockSubLevelLiftProvider`
interface (which Mixin cannot inject into without `@Overwrite`), Ventus pins the Sable dependency to
the `2.0.x` line — see `sable_version_range` in `gradle.properties`.
