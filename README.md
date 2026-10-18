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
| Pressure field | config: `fieldScale`, `fieldPeriod`, `fieldStrength` | A 3D-simplex pressure potential `P(x/L, z/L, t/T)`; its gradient rotated 90° in the XZ plane deflects the base wind so the prevailing direction anchors the flow while the wind turns and varies across `fieldScale` (1500) blocks and `fieldPeriod` (1200 s). `fieldStrength` scales it (1 = typical deflection equals the base wind) — but at 1 a typical deflection already equals the base, and since weather scales this field and never the base, a storm can outweigh the prevailing flow and turn the wind back on itself (measured, see *The storm can reverse the wind*). |
| Gusts | config: `gustScale`, `gustPeriod`, `gustAmplitude`, `gustLateralFraction` | A second, shorter-wavelength noise that puffs mostly **along** the wind (with a small lateral fraction), at `gustAmplitude` (35%) of the base wind, over `gustScale` (150) blocks and `gustPeriod` (120 s). |
| Weather | config: `rainMultiplier`, `thunderMultiplier` | The field and the gusts are scaled by the dimension's **rain and thunder levels**, which vanilla fades in and out over ~5 s: `1 + (rainMultiplier − 1) × rainLevel`, then `1 + (thunderMultiplier − 1) × thunderLevel` on top (1.5 × 1.6 = **2.4** at full storm). Using the continuous levels rather than the raining/thundering flags means no step at the threshold and no per-position biome, heightmap or chunk lookup. The **base wind is not scaled** — only the perturbation and the gusts are. That asymmetry is deliberate, weather makes the air rougher rather than the prevailing flow stronger, but it does mean a storm's perturbation can outgrow the base and turn the combined wind the other way. |
| Thermals | config: `thermalStrength`, `thermalScale`, `thermalPeriod`, `thermalColdTemperature`, `thermalHotTemperature`, `thermalSkyClearance`, `verticalSmoothSeconds` | An **updraft** of up to `thermalStrength` (2.5 m/s) over hot ground in full sun, carved into **bubbles** by its own noise over `thermalScale` (200) blocks and `thermalPeriod` (90 s). Only the positive half of the noise lifts, so the air between bubbles is still rather than sinking. The gates multiply: sun (dawn and dusk ramp over 1500 ticks each), biome warmth from the biome's base temperature (`thermalColdTemperature` 0.8 → no thermals, `thermalHotTemperature` 2.0 → full strength, so deserts and badlands peak and temperate biomes stay out), dryness (`1 − rainLevel`, since an overcast sky has no convection) and **sky clearance** — the `WORLD_SURFACE` height under the point must clear it by `thermalSkyClearance` (8) blocks, ramped in smoothly, so a sample under a canopy, a roof or in a cave gets nothing. Thermals are **not** faded with altitude, because a thermal exists precisely because it rose. |
| Orographic lift | config: `slopeProbe`, `slopeStrength`, `slopeHeightFalloff`, `leeGustBoost` | Air moving at `V` over ground rising at slope `s` climbs at **`V × s`** — ridge lift on a windward face, and the **sink** behind the ridge, since the same signed term covers both. `V` is taken as the local wind's **signed component along the direction `s` was measured in**, so once an inversion has turned the air around, the same face gives sink instead of lift. The slope is the height difference between two columns `slopeProbe` (16) blocks either side of the point **along the base wind**, clamped to a 45° slope. It is measured on `MOTION_BLOCKING_NO_LEAVES`, **not** `OCEAN_FLOOR`: that map counts liquids and excludes leaves, so open water is flat ground (a shallow sea with a sloping seabed no longer invents lift under a boat) and the edge of a forest is not a cliff. Both this and the lee gust roughening are multiplied by **`exp(−h / slopeHeightFalloff)`** (one e-fold at 50 blocks): a ridge lifts the air beside it, not an aircraft crossing the range far above the crest, so both terms die out with altitude while the thermals above do not. On the lee side the gusts are also multiplied by `1 + leeGustBoost` (2× at a 45° lee slope), which is what makes flying near mountains dangerous. |
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
- both heights of the sampled column: `groundHeight` (`MOTION_BLOCKING_NO_LEAVES`, what `slope` is
  measured from) and `floorHeight` (`OCEAN_FLOOR`, what `heightFactor` ramps from). Leaves count for
  the second and not the first, so their difference is the canopy — printed rather than inferred
- `universalDrag` (the drag coefficient the `d*w` impulse uses) and the sub-level's mass
- the sub-level's material velocity and the **air-relative velocity** Sable's lift & drag actually sees

`/windfield info <pos>` does the same for the sub-level containing `<pos>` — usable from a console
or RCON without a player.

`/windfield stats [radius] [step]` sweeps a horizontal grid centred on you (4096 blocks, step 64 by
default = 16641 samples) and reports how often the wind points **against** the configured base wind:

- `total opposes base` — what you would actually feel, the finished wind heading backwards
- `field opposes base` — the perturbation on its own; this should sit near 50%, because the pressure
  gradient's direction is unbiased. If it does not, the window is too small (see below)
- `field stronger than base`, the mean field-to-base ratio, and how often `capped` engaged

`/windfield stats at <pos> [radius] [step]` centres the sweep on a point instead, so it runs from a
console or RCON with no player online.

Both forms need operator permission (level 2) and are capped at **65,000 samples**: the whole sweep
runs on the server thread, and the argument ranges would otherwise allow over four million points,
which locks the game rather than merely taking a while. An over-greedy radius/step pair is refused
before the loop starts, with the count in the message (at radius 4096 the step must be 33 or more).

**Window size matters.** The pressure noise is correlated over `fieldScale` (1500 blocks), so a window
much smaller than that measures a *single patch* of noise rather than the distribution — a 784-block
window first read 85% reversed where the real figure is 50%. The default covers several cells; for a
steadier figure run it at a few far-apart centres and average. It costs nothing to pick a distant
centre: `breakdown` never forces a chunk load, so the sweep is pure arithmetic.

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
2. **Direction dominates, in dry weather (2a)** — with `fieldStrength = 1` in clear, dry weather, the wind should
   still blow broadly the way `[dimensions.overworld] direction` says, only wobbling around it.
   **Under `/weather thunder` this does not hold**: the storm scales the perturbation and not the base
   wind, so the heading can invert. That is accepted behaviour — see *The storm can reverse the wind*
   further down. Run this check dry.
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
14. **The wind keeps its heading when the cap engages (4)** — at the cliff in a storm, compare `total`
    against the *uncapped* sum `base + field + gust`, **not** against `base`: what has to survive is the
    direction of that sum. The cap scales the whole vector rather than clipping components, so engaging
    it changes how hard the wind blows, not which way it points. (It preserves the *sum's* heading,
    which in a storm is not necessarily `direction`'s — see *The storm can reverse the wind*.)
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

Stage 6 checks:

1. **A balloon drifts with the wind (6)** — build a floating-block balloon and rise above `fadeHeight`
   (32 blocks), where the `surfaceCalm` ramp no longer holds it down, then let it sit still. It must
   accelerate downwind and settle at the local wind speed. Give it ~10 s: the coupling rides on
   `universalDrag` (`d = 0.09`, time constant ~11 s), so nothing visible happens in the first second.
   With `-Dventus.debugWind=true` the line `[ventus] floating-pass` proves the hook is live — if it
   never appears, the sub-level is not entering `prePhysicsTick` at all.
2. **The balloon catches the wind with its own friction (6)** — the drift has to come from the
   floating material's friction, which without the fix was a brake towards `v = 0` cancelling the
   `mass * d * w * dt` coupling that pulls towards `v = w`. Ventus adds no size term of its own, so a
   large light balloon and a small dense one both converge on the wind speed, the big one visibly
   faster because its friction coefficients are its sail area.
3. **Aircraft and ships are unchanged (6)** — fly the plane and sail a ship. Anything with lift
   providers takes the two `contributeLiftAndDrag` passes and never relied on the floating-block path;
   `physicsTick` is still called for it (the redirect wraps every call) but returns at its own
   `needsTicking()` guard before writing a single impulse, so the output is identical. This is the
   regression that is easiest to miss, because the new hook sits on a call that ships also reach.
4. **`disableWind` still freezes everything (6)** — with `-Dventus.disableWind=true` a plane, a ship
   and a balloon must all sit dead still. The chain is: `isCalm()` short-circuits `WindSampler` before
   it samples anything, `sample()` would write zeros anyway, `airRelative` returns its input unchanged
   when the wind is zero (so no allocation either), and `ventus$windDrag` bails on
   `lengthSquared() == 0`. All three hooks degrade to exact identities.

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

## Verification for 1.0.0

The checks for 1.0.0, in the order to run them. Tick one only after you have actually
seen the value with `/windfield info` open — the expected figure is repeated here so the list can
be worked through without scrolling back up to the full description. If a check fails, write down
the coordinates, altitude, time of day and weather **before** touching any code; that is enough to
tell a wrong model from a misreading.

- [x] **12. The sea is flat** — low over shallow coastal water, not deep ocean: `slope` must read
      `0.000` and `orographic` `0.000`. The seabed must not manufacture an updraft under a boat.
      *(Verified: `slope=0.000`, `orographic=0.000`, `heightFactor=0.648` at Y 75. `slope` is not
      stuck at zero — the same build reads `1.000` at a cliff and `-0.156`/`-0.313` in open desert.)*
- [x] **13. A forest edge is not a hill** — skim a forest boundary at low altitude, crossing canopy
      to clearing and back: `slope` stays near `0.000`. Leaves are excluded from the surface.
      *(Verified headless over RCON, no client needed. The test world is `level-type=flat`, so the
      ground is flat by construction and reads `groundHeight=-61.0` everywhere — which makes this
      check sharper than a natural forest, since any non-zero `slope` has to come from the canopy.
      A leaves-only canopy (`oak_leaves[persistent]`, y -51…-47) was built with its edge at X 0
      across the probe span, perpendicular to the base wind, which is exactly +X below Y 0. Centre
      at X 0, Y -55: `slope=0.000` while `floorHeight=-47.0` and `groundHeight=-61.0` — the probe
      under the canopy and the probe in the clearing differ by 14 blocks of leaves, so a map that
      counted them would read `0.438`. Controls at X -16 (both columns clear) and X 16 (both under
      canopy) also read `0.000`. The gauge is not stuck: a 5-block dirt step on the same line reads
      `0.156` = 5/32, and in that run the very same column reports `floorHeight=-47.0` with
      `groundHeight=-56.0`. Everything was removed afterwards and the three readings went back to
      `0.000` / `-61.0` / `-61.0`.)*
- [x] **14. The cap keeps the heading of the air it is capping** — sheer cliff, `/weather thunder`,
      `/time set noon`, over a desert. The criterion is **not** that `total` matches `base`: the cap
      scales the whole vector rather than clipping components, so what has to survive is the
      direction of the *uncapped* sum `base + field + gust`. Verified at a cliff: the sum points
      228.8° and `total` points 228.8°, identical; and `|total|` = 8.41 = 3 × 2.804, exactly
      `maxWindMultiplier` × base. The cap did its job. *(The first wording of this check asked for
      `total` to match `base`, which is only true while `field` is small — see *The storm can reverse the
      wind* below.)*
- [x] **15. A parked heavy sub-level does not float** — heavy sub-level left in mid-air under a
      desert thermal at noon: it must settle, not climb. Ceiling is `universalDrag` ×
      `thermalStrength` ≈ 0.23 m/s², far below gravity. *(Verified: falling at 10.5 m/s with
      `thermal=0.506`, and at 9.4 m/s with `thermal=0.695` in a second desert. The check is that it
      must not **climb**; sinking is the correct answer.)*
- [x] **6.3 Aircraft and ships are unchanged** — fly the plane, sail a ship: they behave exactly as
      they did before the balloon fix. Anything with lift providers never used the floating-block
      path; this is the regression easiest to miss, because the new hook sits on a call ships also
      reach.
- [x] **6.4 `disableWind` freezes everything** — with `-Dventus.disableWind=true`: a plane, a ship
      **and** a balloon all sit dead still. All three hooks degrade to exact identities.

**The storm can reverse the wind — decided, and accepted.** Found by check 14: at `weather = 2.40` the
pressure perturbation ran `|field|` = 11.96 against `|base|` = 2.80 — 4.27× — and pointed nearly the
opposite way, so the storm effectively reversed the wind. That is what made the first wording of
check 14 look wrong. The arithmetic is not at fault: `weather` scales `field` and the gusts but never
the base wind, so at storm strength the perturbation legitimately overtakes the prevailing flow, and
the budget (`maxWindMultiplier`) bounds the *magnitude* (3× prevailing), never the heading.

The two places that promised otherwise — `ventus-server.toml`'s `fieldScale` note and stage 2 check 2a
— have been corrected to describe what actually happens. Bounding the perturbation against the base,
so `direction` always wins, remains the fallback if it plays badly in the world; it is deliberately
not done yet, because a config key is easy to add later and awkward to take away.

**Measured, with the world's own noise.** `/windfield stats` sweeps a grid sampled by the dimension's
real `SimplexNoise` at the real seed, under the live rain and thunder levels (four independent
8256-block squares, 16641 samples each). The headline is deliberately rounded, because it is an order
of magnitude rather than a percentage:

- **In clear weather, approximately 1 point in 20** has the wind pointing against `direction`.
- **In a storm, approximately 1 point in 4.**

Both are rounded for good reason. A 4096-block window holds only about seven `fieldScale` (1500)
patches of noise, and four centres give a few dozen in all, so the margin is several points; and every
figure tracks this particular world's seed. An earlier 784-block sweep — under a single patch —
reported most points reversed where the truth is half, which is the same effect at its extreme.

**The number that is not an estimate is the one beside it: the field points against the base about
50% of the time in clear weather and about 50% in a storm.** The pressure gradient's direction carries
no bias at all; weather only makes it *larger*. So `direction` is the **mean** of the wind rather than
what any single reading says — the configured heading is what the wind does on average, not what it is
doing at this instant.

One thing to know before tuning: in a storm the field already outweighs the base at roughly 4 points in
5 (against about 1 in 3 when clear), and the cap (`maxWindMultiplier`) is engaged on about **21%** of
storm points. Raising `rainMultiplier` will be felt nowhere that sits on the ceiling — there the wind
is already at `maxWindMultiplier` and only the cap decides how hard it blows.

None of these figures can come from a unit test: Minecraft is not on the test classpath, so the tests
run on sine/cosine stand-in noise with a different distribution. That gap is what `/windfield stats`
exists to fill.

**Found by reading the code, now fixed.** `WindField` measures the terrain slope *along the base
direction*, but orographic lift is driven by the **local** wind. During an inversion those two point
opposite ways: if the hill rises toward `direction` while the local wind blows the other way, the air
is actually descending and the code handed it lift anyway — a plain speed (a hypotenuse, always
positive) left the sign resting on the terrain alone. `WindModel.alongBaseWind` now supplies the
**signed component** of the local wind along the base axis, so the term turns negative exactly when
the air has reversed and passes through zero continuously at the calm point instead of jumping.
Covered by `anInvertedLocalWindTurnsRidgeLiftIntoSink`.

**What that changes in normal flight — decided deliberately, and worth feeling before tuning.** The
slope is measured in a single direction, so the model says the ground rises *that* way and the
wind's cross-slope part climbs nothing; the old code multiplied by the whole speed anyway. Lift is
now `slope × (total · û_base)` rather than `slope × |total|`, so it is always the same or smaller,
never larger. Because the pressure field carries no directional bias (50% against the base, measured
above), its projection averages out: ridge lift now rests on the prevailing wind plus an oscillation
rather than on the total magnitude. That makes a good windward face steady in clear weather — a
little weaker than before — and makes it flicker with sign in a storm, which is the same inversion
physics already accepted above. If a windward slope feels feeble, raise `slopeStrength` (0.5); do not
drop the projection to buy the magnitude back.

**Known limitation in 1.0.0 — in a storm, with the wind inverted, the lee turbulence can end up on
the wrong side of the crest.** `leeGustBoost` reads only the slope's sign on the base axis, so it
credits its extra roughness to the face the wind is now *climbing*. It cannot take the same one-line
fix: `leeBoost` is an **input** to `WindModel.evaluate` and is consumed where the gust amplitude is
built, which is before the local wind exists. Doing it properly means passing the raw slope (and its
height decay) into `evaluate` instead of the resolved boost, flipping it against the local wind
there, and fading the flip the way the gust calm fade does — so that rotating through perpendicular
does not pop the gust amplitude. That changes `evaluate`'s signature for every caller, and is
deliberately left out of 1.0.0.

The mixin side of 6.3 is already checked structurally: `Ventus 0.1.0` loads with no mixin errors
against the full mod set, the two `@ModifyArg` hooks are untouched, and `physicsTick` returns at its
own `needsTicking()` guard before writing any impulse for a sub-level without floating blocks. What
is left is the behaviour in the world.

All six are done, and the design question the earlier checks raised has been settled. The last one,
**13 (a forest edge is not a hill)**, turned out not to need a client: `/windfield info <pos>` reads
from the server, and with both column heights printed the leaf question is answered from a console
or RCON instead of being worked backwards out of the other numbers.

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
5. **Stage 6 wants a second shape of world: balloons.** `WindField.airRelative` allocates a
   `Vector3d` whenever the wind is non-zero, and stage 6 put a call on the floating-block pass —
   once per sub-level per physics sub-step. Ships reach that redirect too, since it wraps the call
   and only the controller's own `needsTicking()` guard stops it doing any work, so the fleet run
   already samples the cost; but a handful of balloons is the world that actually exercises it.
   Take the same mean/p99 pair over balloons and compare it against the ship run. If the delta is
   visible, the vector has to stop being allocated.

## How the hook works

- `ServerSubLevelWindMixin` targets `ServerSubLevel.prePhysicsTick` and rewrites the
  `linearVelocity` argument at the three places that turn a body velocity into a force, handing each
  the air-relative velocity. Two are the `BlockSubLevelLiftProvider.sable$contributeLiftAndDrag` call
  sites (ships, sails, anything with lift providers), rewritten with `@ModifyArg`. The third is
  `FloatingBlockController.physicsTick` (balloons and any sub-level made of floating blocks), wrapped
  with `@Redirect` — two `double` parameters precede the velocity there, so its argument index differs
  from the other target and a `@ModifyArg` would be easy to miscount. The argument is read-only, so
  only the forces derived from it change.
- The floating-block pass is what makes a balloon drift at all. `FloatingBlockController.applyFriction`
  opposes the body velocity *in world space*, which pulls a balloon towards `v = 0` and cancels the
  coupling below; the two neutralise each other and it never moves. Handed `v - w` instead, the very
  same friction becomes a sail — zero exactly at `v = w`, pushing downwind from rest — with the
  floating material's own coefficients as its area. Nothing in Ventus scales with balloon size.
- **Before publishing:** move that third pass from `@Redirect` to `@WrapOperation`. A `@Redirect` owns
  the call site outright, so if any other mod redirects the same `physicsTick` invocation the two
  mixins conflict and the game refuses to start; `@WrapOperation` composes with them instead. NeoForge
  21.1 already bundles MixinExtras, so this needs no new dependency.
- The mixin implements `WindCarrier` to expose its `@Unique` wind state to `WindSampler` (the wind
  vector, the last height factor, and the first-sample time). The state is written by `WindSampler`
  and read by the hooks, both on the server thread, so no synchronisation is needed.
- `ventus$windDrag` injects `mass * d * w * dt` at the centre of mass, shifting the native universal
  damping equilibrium from `v = 0` to `v = w` so every ticking sub-level drifts with the wind.

Because the hooks are injected into the concrete caller rather than the `BlockSubLevelLiftProvider`
interface (which Mixin cannot inject into without `@Overwrite`), Ventus pins the Sable dependency to
the `2.0.x` line — see `sable_version_range` in `gradle.properties`.
