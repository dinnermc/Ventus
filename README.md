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
| Weather | config: `rainMultiplier`, `thunderMultiplier` | The field and the gusts are scaled by the dimension's **rain and thunder levels**, which vanilla fades in and out over ~5 s: `1 + (rainMultiplier − 1) × rainLevel`, then `1 + (thunderMultiplier − 1) × thunderLevel` on top (1.5 × 1.6 = **2.4** at full storm). Using the continuous levels rather than the raining/thundering flags means no step at the threshold and no per-position biome, heightmap or chunk lookup. The **base wind is not scaled** — weather roughens the air without moving the prevailing direction. |
| Thermals | config: `thermalStrength`, `thermalScale`, `thermalPeriod`, `thermalColdTemperature`, `thermalHotTemperature`, `thermalSkyClearance`, `verticalSmoothSeconds` | An **updraft** of up to `thermalStrength` (2.5 m/s) over hot ground in full sun, carved into **bubbles** by its own noise over `thermalScale` (200) blocks and `thermalPeriod` (90 s). Only the positive half of the noise lifts, so the air between bubbles is still rather than sinking. The gates multiply: sun (dawn and dusk ramp over 1500 ticks each), biome warmth from the biome's base temperature (`thermalColdTemperature` 0.8 → no thermals, `thermalHotTemperature` 2.0 → full strength, so deserts and badlands peak and temperate biomes stay out), dryness (`1 − rainLevel`, since an overcast sky has no convection) and **sky clearance** — the `WORLD_SURFACE` height under the point must clear it by `thermalSkyClearance` (8) blocks, ramped in smoothly, so a sample under a canopy, a roof or in a cave gets nothing. Thermals are **not** faded with altitude, because a thermal exists precisely because it rose. |
| Orographic lift | config: `slopeProbe`, `slopeStrength`, `slopeHeightFalloff`, `leeGustBoost` | Air moving at `V` over ground rising at slope `s` climbs at **`V × s`** — ridge lift on a windward face, and the **sink** behind the ridge, since the same signed term covers both. The slope is the height difference between two columns `slopeProbe` (16) blocks either side of the point **along the base wind**, clamped to a 45° slope. It is measured on `MOTION_BLOCKING_NO_LEAVES`, **not** `OCEAN_FLOOR`: that map counts liquids and excludes leaves, so open water is flat ground (a shallow sea with a sloping seabed no longer invents lift under a boat) and the edge of a forest is not a cliff. Both this and the lee gust roughening are multiplied by **`exp(−h / slopeHeightFalloff)`** (one e-fold at 50 blocks): a ridge lifts the air beside it, not an aircraft crossing the range far above the crest, so both terms die out with altitude while the thermals above do not. On the lee side the gusts are also multiplied by `1 + leeGustBoost` (2× at a 45° lee slope), which is what makes flying near mountains dangerous. |
| Vertical smoothing | config: `verticalSmoothSeconds` | The thermal and the orographic term are read from integer terrain heights and biome ids, so both **step** when the sampled column changes. They ease towards their target with a 1.5 s time constant instead, so flying past a ridge does not jolt. The gusts are deliberately left unsmoothed — they are meant to be quick. |
| Height profile | config: `fadeHeight`, `surfaceCalm` | Wind ramps with altitude above the terrain: `surfaceCalm` (10%) at the surface to full at `fadeHeight` (32 blocks), smoothstep in between. Uses vanilla `OCEAN_FLOOR` heightmap (solid blocks; **water ignored**, **leaves count as terrain**), so a boat floating on the sea sits above its reference and still catches the wind, while a plane parked on the ground feels almost nothing. This is the **only** place `OCEAN_FLOOR` is used — the terrain-locked terms above deliberately use `MOTION_BLOCKING_NO_LEAVES` instead, because there the question is what the air is being forced over, and to the air neither the sea surface nor a canopy exists. A **submerged** sample point (centre block holds a fluid) gets **zero** wind. |
| Jet stream | config: `jetAltitude`, `jetThickness`, `jetStrength` | A band of fast air at an **absolute** world Y (`jetAltitude` 220), not a height above the terrain — a jet is a property of the atmosphere and has nothing to do with the ground beneath it. The base wind is multiplied by up to `1 + jetStrength` (**3×** by default, so a 9 m/s core before the shear adds to it), easing smoothly to 1 at `jetAltitude ± jetThickness` (60) and to nothing outside. It rides on the dimension's prevailing direction and never turns it, and it does **not** scale the pressure field or the gusts: a jet is laminar — fast, not rough — so those are sized against the base wind *before* the altitude profile and the core comes out a smooth river of air rather than a storm. The jet still raises the clamp budget with it, because that budget is a ceiling and not a target. It does **not** touch the vertical terms: a jet is horizontal wind. |
| Wind shear | config: `shearSpeedPer100`, `shearDegreesPer100`, `shearMaxAltitude` | The other half of altitude: how the wind differs between one height and another, again keyed on **absolute** world Y. Speed scales additively by `1 + shearSpeedPer100 × y / 100` (1.75× at Y 300 by default, floored at zero so a negative rate can still the air but never make it blow backwards), and the heading turns by `shearDegreesPer100 × y / 100` degrees (45° at Y 300), clockwise from above, the same sense the direction keys already use. Both saturate at `shearMaxAltitude` (300) and hold that value rather than vanishing — uncapped the rates ran to the top of Sable's atmosphere, where the default would give an 8× wind turned 450°, and the clamp bounds magnitude but never heading. Below Y 0 the shear reads as ground level, so the deep dark does not turn the wind the other way. The turn is applied to the base wind **before** anything measures the terrain, so the ridge lift at altitude is taken along the wind that actually crosses the ridge there. |
| Fade-in | `WindField` | 0 to full over **5 seconds** after a sub-level's first sample, so nothing jolts on world load or when a sub-level enters a windy region. |
| Cap | config: `maxWindMultiplier` | The last thing before the wind is stored: it is checked for finitude (a non-finite component zeroes the whole vector rather than reaching a rigid body), then kept within this multiple (**3×**) of the **prevailing wind at that point** — the base wind *after* the jet, the shear, the height profile, the fade-in and `windScale`, not the dimension's sea-level figure. Measuring the budget against the local prevailing wind means anything that raises the base at altitude raises the budget with it, so the cap keeps acting on the perturbations instead of clipping a number that belongs to a different altitude. The vector is scaled **as a whole**, never clipped per component, so the wind keeps its heading when the cap engages. Every term is bounded on its own, but they now **sum** — base, field, gusts (roughened by weather and by the lee side), thermal and orographic — so with the defaults the horizontal tops out at 3 × 3 = 9 m/s and the vertical at 2.5 + 9 × 0.5 = 7 m/s, about **11.4 m/s** before this clamp, and proportionally more at altitude where the prevailing wind itself is stronger. |
| Scale | config: `windScale` | Global multiplier applied to the total field. |

If the terrain chunk under a sub-level is not loaded, the last known height factor is reused (a new
sub-level starts from zero) instead of jumping to full wind.

`jetAltitude` and `shearMaxAltitude` are **absolute** world Y, deliberately — a jet is a property of
the atmosphere rather than of the ground under it, so no terrain lookup is needed and none is made.
On the standard Overworld (sea level 62) the defaults land where they mean to: the core at 220, the
shear saturating at 300. A datapack that raises the sea level moves those relative to the sky without
moving them relative to the surface, which is the wrong way round. **When these become datapack
driven, they should accept an offset from sea level (`sea_level + n`) alongside a bare Y**, so a
raised world can raise its jet with it. Nothing to do for the standard Overworld.

Direction convention: **0° = +X (east)**, **90° = +Z (south)**, in the XZ plane.

The field math lives in `WindModel`, a Minecraft-free class (JOML only) with unit tests in
`src/test`. The one remaining stage on the roadmap is registration with Sable's own wind-provider API.

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
rainMultiplier = 1.5
thunderMultiplier = 1.6
thermalStrength = 2.5
thermalScale = 200.0
thermalPeriod = 90.0
thermalColdTemperature = 0.8
thermalHotTemperature = 2.0
slopeProbe = 16.0
slopeStrength = 0.5
slopeHeightFalloff = 50.0
leeGustBoost = 1.0
verticalSmoothSeconds = 1.5
thermalSkyClearance = 8.0
jetAltitude = 220.0
jetThickness = 60.0
jetStrength = 2.0
shearSpeedPer100 = 0.25
shearDegreesPer100 = 15.0
shearMaxAltitude = 300.0

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

## Wind compass (client)

A small circular compass sits at the **top centre of the HUD**:

- the rim is marked **N / E / S / W** in world terms, so it rotates with your view like a real
  compass (north is red);
- the **needle points downwind**, and its colour tracks the speed (grey → cyan → amber → red);
- underneath it shows the speed (e.g. `3.2 m/s`) and the heading the wind is blowing **towards**
  (e.g. `090° E`), or `CALM` when there is none;
- once there is a meaningful **vertical** component the speed line grows an up/down marker
  (`3.2 m/s ↑2.3`), so a thermal or ridge lift is visible without leaving the cockpit. The speed
  figure itself stays horizontal, because that is what the needle and the dial describe.

**F8** shows/hides it (rebindable under *Options → Controls → Ventus*; it starts visible, and the
toggle does not persist between sessions).

The compass is fed by a small clientbound packet carrying the wind at your own eye position — the same
field the physics hooks use, sampled at you rather than at a sub-level. The client cannot evaluate the
field itself (the noise is seeded from the world seed, which only the server has), so the server
pushes it at 5 Hz, and only when the value has moved by more than 0.05 m/s: a player standing still
costs almost nothing, and the payload is twelve bytes. The channel is registered as **optional**, so a
client without Ventus can still join a server that has it — it simply never receives the packet, and
the compass stays hidden.

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

Stage 3 checks:

1. **Rain roughens the field (3)** — run `/weather rain` and immediately start sampling
   `/windfield info` once a second: `weather` must **climb** from `1.00` towards `1.50` over the ~5 s
   vanilla takes to fade the rain in, never jump. `field` and `gust` grow by that factor while `base`
   stays put.
2. **Storm is stronger (3)** — `/weather thunder` → `weather` eases up to `2.40` (1.5 × 1.6).
3. **Indoors is not a shelter (3)** — the weather is global to the dimension, so a closed hangar
   reports the same `weather` as open sky; only changing dimension (Nether/End, whose rain level is
   always 0) brings it back to `1.00`.

Stage 4 checks:

1. **Thermals need sun (4)** — `/time set noon` over a desert, then compare `/windfield info` in the
   open against `/time set midnight`: `thermal` should be a positive `total.y` by day and exactly
   `0.000` at night. Dawn and dusk must ramp over ~75 s each rather than switch.
2. **Thermals need heat (4)** — the same desert versus a snowy biome at noon: the desert `thermal`
   should be at the ceiling (`thermalStrength`), the snowy one exactly `0.000`.
3. **Thermals are bubbles (4)** — hold altitude over a desert: `thermal` should rise and fall as you
   drift through them, not sit at a constant value. Circling a strong one should hold you up.
4. **Rain kills thermals (4)** — `/weather rain` over the desert at noon → `thermal` eases to `0.000`
   while the horizontal wind stays up (it is only convection that needs the sun).
5. **Ridge lift (4)** — fly straight at a hill face-on: `orographic` should go positive (`wind.y`
   positive, you feel yourself rise) and `slope` should read positive.
6. **Sink and rough air behind the ridge (4)** — cross the crest: `orographic` flips negative,
   `leeBoost` becomes non-zero and the gusts visibly roughen. That is the dangerous side, on purpose.
7. **A wall is not a launch pad (4)** — fly at a sheer cliff: `slope` clamps at `1.000` and
   `orographic` saturates instead of producing an absurd updraft.
8. **The ridge lets go with altitude (4)** — over the windward face, climb 50 blocks and re-run
   `/windfield info`: `orographic` must drop by a factor of `e` (2.72), and the `leeBoost` roughness
   fades with it. At 500 blocks both are essentially gone. Thermals are the exception — they do **not**
   fade, so the same climb over a desert leaves `thermal` untouched.
9. **Nothing lifts under a canopy (4)** — under a tree, inside a hangar or in a cave at noon in a hot
   biome: `thermal` reads exactly `0.000` even though the biome is warm and the sun is up. Step out
   into the open and it comes back.
10. **No jolt at the ridge line (4)** — fly past a ridge at a constant altitude: `thermal` and
    `orographic` must **ease** over ~1.5 s as the terrain columns change, never step in one tick. The
    `gust` component may still jump; only the terrain-derived terms are smoothed.
11. **The clamp holds (4)** — stand at a sheer cliff in a thunderstorm at noon over a desert and check
    `/windfield info`: `total` stays within `maxWindMultiplier` × base × `windScale` (9 m/s with the
    defaults) even though every term is near its own ceiling. And the same command works with **no
    sub-level underfoot**, reporting `base`/`field`/`gust`/`thermal`/`orographic` for the position —
    so you can walk or fly around sampling biomes without boarding anything.
12. **The sea is flat (4)** — fly low over open water with a sloping seabed underneath (shallow
    coastal water is easiest): `slope` must read `0.000` and `orographic` `0.000`. Before this was
    fixed the seabed would have manufactured an updraft under a boat. Deep ocean always read clean;
    it is shallow water that catches this.
13. **A forest edge is not a hill (4)** — skim the boundary of a forest at low altitude, crossing from
    clearing to canopy and back: `slope` must stay near `0.000`. Only real ground counts; leaves are
    excluded from the surface the air is forced over.
14. **The wind keeps its heading when the cap engages (4)** — at the cliff in a storm the reported
    `total` must point the same way as `base`; the cap scales the whole vector rather than clipping
    components, so the roughest air never arrives from a new direction.
15. **A parked heavy sub-level does not float (5)** — park a heavy sub-level in mid-air under a
    thermal at noon in a desert, and leave it. It must settle, not climb. The drag impulse is 3D, so
    a thermal does push up on it — at most `universalDrag` × `thermalStrength` ≈ 0.09 × 2.5 =
    0.23 m/s², and only 0.023 m/s² near the ground where `surfaceCalm` applies — which is far below
    gravity, but this is the check that proves it.
16. **A boat near the shore is not becalmed (5)** — at water level, compare `/windfield info` over
    open ocean against the same spot just off a shallow beach. The height profile measures against
    `OCEAN_FLOOR`, which ignores water, so `heightFactor` there is the depth under the hull rather
    than anything about the sea surface: at `surfaceCalm` 0.1 and `fadeHeight` 32, a boat in 23
    blocks of water gets `smoothstep(23/32)` → about **83%** of full wind, but one in 3 blocks
    (hull almost on the sand) gets `smoothstep(3/32)` → about **12%**, and sailing inshore bleeds it
    away smoothly. **If this reads as described**, the fix is a separate water floor: when the
    sample point sits on a fluid surface, use a `surfaceCalmWater` (0.6–0.8) instead of measuring
    against the seabed at all. Water is smooth and the wind at its level really is stronger than over
    rough ground, so that is both simpler and more realistic.

Stage 5 checks:

1. **The jet is a band, not a ladder** — climb straight up at one spot, running `/windfield info` as
   you go. `jet` must read `1.000` at sea level, ease up to `3.000` exactly at `jetAltitude` (220),
   ease back down to `1.000` at 160 and at 280 (the core ± `jetThickness`), and stay at `1.000`
   above 280. It must ramp, never step.
2. **The jet does not turn the wind** — the direction of `base` at the core must be the same heading
   as at sea level, only larger. The jet multiplies; it never redirects.
3. **Climbing turns the wind** — with the default 15° per 100 blocks, `turn` must read `45.0` at Y
   300 and `33.0` at the jet core, and `base` must have rotated by exactly that much from its
   configured heading. This is the check that the shear and the direction keys share a sense: a
   positive rate has to turn the wind the same way a larger `direction` would.
4. **Climbing speeds the wind up** — `shear` must read `1.75` at Y 300, and `base`'s magnitude must
   grow to match. With the defaults the core runs 3 (base) × 3 (jet) × 1.55 (shear) = **13.95 m/s**,
   which is a strong headwind but still one an aircraft can fly against.
5. **The ground is untouched** — at sea level `jet` and `shear` both read `1.000` and `turn` `0.0`,
   so everything below the jet band is exactly as it was before this stage.
6. **The shear saturates instead of running away** — climb past Y 300: `shear` must hold `1.75` and
   `turn` must hold `45.0` all the way to the top of Sable's atmosphere, not keep climbing. Then
   drop below Y 0: `shear` must read `1.000` and `turn` `0.0`, not run backwards. Uncapped, Y 3000
   gave an 8× wind turned 450°, and nothing downstream was going to catch it — the clamp bounds
   magnitude but never heading.
7. **The core is smooth, not stormy** — on flat ground, compare `/windfield info` at Y 40 (above the
   fade, outside the jet band) against Y 220 (the core). `base` is three times bigger at the core,
   but **`gust` must read the same magnitude at both**. That is the whole point of the jet being
   laminar: the perturbations are sized against the wind before the altitude profile, so a jet is
   fast air rather than rough air. If `gust` scales up with `base`, the coupling has come back.
8. **The budget follows the jet up** — at the jet core in a thunderstorm, `total` must be allowed up
   to 3 × the local prevailing wind (about 42 m/s with the defaults) rather than the 9 m/s available
   at sea level, because the cap is a ceiling scaled to the air actually blowing here. The **vertical**
   terms are the exception: `thermal` and `orographic` are not scaled by the jet, since a jet is
   horizontal wind.

Client checks:

1. **Compass** — F8 hides/shows it; turn in place and the rim letters move while the needle stays
   fixed on the wind; fly up to `fadeHeight` and the speed climbs; the needle must never jump.
2. **Lift and sink are readable (4)** — climb a windward face at noon: the speed line should grow a
   `↑` marker with the vertical component, and `↓` behind the ridge. The speed figure itself must
   still describe the **horizontal** wind only, so it does not jump when you cross a thermal.
3. **Rebind** — Options → Controls → Ventus → *Toggle Wind Compass* accepts another key.
4. **The compass tracks the shear** — climb from sea level to the jet core: the needle must sweep
   smoothly through the ~33° of turn the defaults give, and the speed figure must climb into the high
   teens. The fade and the jet are both smooth, so there is nothing for the needle to step on at
   `fadeHeight` or at the edge of the band.

## Building & testing

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
.\gradlew build --console=plain
.\gradlew test --console=plain
.\gradlew runClient --console=plain
.\gradlew runServer --console=plain
```

## Performance baseline

Ventus samples the field **once per sub-level per tick**, and stage 4 added a terrain height read
(twice, for the two slope probes), a biome read, a sky-height read and a smoothstep to each of those.
Whether that costs anything is a measurement, not an argument, so there is a baseline to compare
later stages against.

`spark` is in `run/mods` (`spark-1.10.124-neoforge.jar`, from Modrinth). The procedure:

1. Build a world with **30–50 sub-levels** — a fleet of ships is the easiest way, since that is the
   shape of load the mod actually ships with.
2. `/spark profiler start --thread server --timeout 60`, leave it alone for the minute, and note the
   **tick duration mean and p99** from the report it prints at the end.
3. Repeat with the wind switched off: `.\gradlew runServer -Pventus.disableWind=true`. This is the
   control, and it is a real control — `WindField.sample` returns before touching the heightmap, the
   biome cache or the noise (`WindField.java:350`), so the difference between the two runs is
   Ventus's cost and not Sable's.
4. **Tick mean and p99, with and without, is the number.** The same pair is taken again after any
   later stage, and the delta between the two pairs is the useful figure — a single absolute number
   on its own says nothing, because Sable, Create and the world dominate it.

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
