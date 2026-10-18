# Ventus

Wind field mod for **Minecraft 1.21.1 / NeoForge 21.1.259** that makes Sable sub-levels
(sails, planes, boats) feel the air move.

Source and issues: <https://github.com/dinnermc/Ventus>

Ventus hooks into [Sable's](https://github.com/ryanhcode/sable) sub-level physics and feeds its lift and
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
`src/test`.

### Tuning

Edit `config/ventus-server.toml` while the game is running — it is hot-reloaded, no restart:

```toml
enabled = true
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

[dimensions.nether]
    magnitude = 0.0
    direction = 0.0

[dimensions.the_end]
    magnitude = 0.0
    direction = 0.0
```

`enabled` is the master switch: with it off the field is zero everywhere, sub-levels drift nowhere,
the `/windfield` commands still work and read zero, and the client HUD disappears. It is the
no-restart way to hand the air to another wind mod (see *Compatibility*) or to run Sable without
wind at all.

It is a NeoForge **server** config, so it lives in `<instance>/config/ventus-server.toml` and is
shared by every world in that instance. To override it for a single world, drop the same file in
that world's `<world>/serverconfig/ventus-server.toml` instead — NeoForge reads that folder on top
of the instance config, which is handy for per-world tuning on a dedicated server. *(Both checked on
a dedicated server: `config/ventus-server.toml` is the file that gets written to and hot-reloaded,
with a key added by a new version appended on the next start; and a copy placed in
`<world>/serverconfig/` wins over it — NeoForge logs `Found config file override in path ...`, and
the world file's overworld base of 7 m/s beat the 3 m/s left in `config/`.)*

### Frozen keys (1.0.0)

These 37 keys, with these names, these defaults and these ranges, are the 1.0.0 contract. Adding a
key later is safe; renaming one, moving one out of a section, or changing a default or a range
breaks every server that has already written its file, so none of that will happen inside 1.x.

| Key | Default | Range |
| --- | --- | --- |
| `enabled` | `true` | boolean |
| `windScale` | `1.0` | `0.0` – `100.0` |
| `fadeHeight` | `32.0` | `1.0` – `512.0` |
| `surfaceCalm` | `0.1` | `0.0` – `1.0` |
| `fieldScale` | `1500.0` | `100.0` – `100000.0` |
| `fieldPeriod` | `1200.0` | `20.0` – `86400.0` |
| `fieldStrength` | `1.0` | `0.0` – `5.0` |
| `maxWindMultiplier` | `3.0` | `1.0` – `10.0` |
| `gustScale` | `150.0` | `20.0` – `10000.0` |
| `gustPeriod` | `120.0` | `5.0` – `86400.0` |
| `gustAmplitude` | `0.35` | `0.0` – `2.0` |
| `gustLateralFraction` | `0.3` | `0.0` – `1.0` |
| `rainMultiplier` | `1.5` | `1.0` – `10.0` |
| `thunderMultiplier` | `1.6` | `1.0` – `10.0` |
| `thermalStrength` | `2.5` | `0.0` – `20.0` |
| `thermalScale` | `200.0` | `20.0` – `10000.0` |
| `thermalPeriod` | `90.0` | `5.0` – `86400.0` |
| `thermalColdTemperature` | `0.8` | `-2.0` – `30.0` |
| `thermalHotTemperature` | `2.0` | `-2.0` – `30.0` |
| `slopeProbe` | `16.0` | `1.0` – `256.0` |
| `slopeStrength` | `0.5` | `-5.0` – `5.0` |
| `slopeHeightFalloff` | `50.0` | `1.0` – `1000.0` |
| `leeGustBoost` | `1.0` | `0.0` – `10.0` |
| `verticalSmoothSeconds` | `1.5` | `0.0` – `60.0` |
| `thermalSkyClearance` | `8.0` | `0.0` – `256.0` |
| `jetAltitude` | `220.0` | `-64.0` – `2048.0` |
| `jetThickness` | `60.0` | `0.0` – `2048.0` |
| `jetStrength` | `2.0` | `0.0` – `100.0` |
| `shearSpeedPer100` | `0.25` | `-1.0` – `100.0` |
| `shearDegreesPer100` | `15.0` | `-360.0` – `360.0` |
| `shearMaxAltitude` | `300.0` | `0.0` – `3500.0` |
| `dimensions.overworld.magnitude` | `3.0` | `0.0` – `100.0` |
| `dimensions.overworld.direction` | `0.0` | `-360.0` – `360.0` |
| `dimensions.nether.magnitude` | `0.0` | `0.0` – `100.0` |
| `dimensions.nether.direction` | `0.0` | `-360.0` – `360.0` |
| `dimensions.the_end.magnitude` | `0.0` | `0.0` – `100.0` |
| `dimensions.the_end.direction` | `0.0` | `-360.0` – `360.0` |

The client has a second, much smaller file of its own, `config/ventus-client.toml`, frozen the same
way: a single key, `windCompass.corner`, whose value is one of `TOP_LEFT`, `TOP_RIGHT`, `BOTTOM_LEFT`
or `BOTTOM_RIGHT`, defaulting to `TOP_LEFT`. It only places the wind compass, and it is described
under *Wind compass (client)* below.

## Compatibility

**Sable** is required, and the accepted range is `[2.0.6, 2.1)`. The wind hooks inject into Sable's
`prePhysicsTick` bytecode, which may move in 2.1, so outside the range the loader stops Ventus with
a plain missing-dependency message rather than a mixin crash.

**Other wind and weather mods.** *Aeronautics: Wind and Weather* and *PMWeather Aeronautics* each
push their own wind onto the bodies they drive. Ventus has no compatibility layer for either: two
mods applying wind to the same sub-level simply add, so a player on a stack that leaves both enabled
feels both. Ventus is the only one of them tied to Sable's physics hooks, so there is nothing here
that can arbitrate. If another mod owns the air on your server, turn Ventus off rather than tuning it
down:

```toml
# config/ventus-server.toml — hot-reloaded, no restart
enabled = false
```

Everything else keeps working with `enabled = false`: the commands still answer (reading zero), the
sub-levels still simulate, and flipping the key back on brings the wind straight back. `enabled` is
the switch — not launch arguments, and not uninstalling the jar.

## Wind compass (client)

A single line sits **in a screen corner** — top left by default:

- a small **needle** that swings to the direction the wind is blowing, in your own frame of
  reference: it points up when the wind blows the way you are looking, right when it blows to your
  right, and anywhere in between. The angle is continuous, not quantised;
- the **speed** in m/s, coloured as the wind gets harder (grey → cyan → amber → red);
- the **climb** once it is worth showing — `↑2.3` for rising air, `↓2.3` behind a ridge — and only
  past 0.1 m/s, because a real variometer reads tenths and a default ridge under a 3 m/s wind only
  lifts about 0.45 m/s. The speed figure itself stays purely **horizontal**, because that is what
  the needle describes.

An example: `3.2 m/s ↑1.3`. Still air is just a grey `0.0 m/s` with no needle.

The server sends the wind five times a second, so the needle keeps its own angle and eases towards
the received one every frame, with a 0.15 s time constant — the same exponential step the field
smoothing uses, taking the short way round the dial and measured against the real frame time, so it
settles the same way at 30 and at 144 fps. The text is **cached**: it is rebuilt only when the
figures it is made of change.

`config/ventus-client.toml` has a single key:

| key | default | meaning |
| --- | --- | --- |
| `windCompass.corner` | `TOP_LEFT` | `TOP_LEFT`, `TOP_RIGHT`, `BOTTOM_LEFT` or `BOTTOM_RIGHT`. The line runs inwards from the corner, so nothing is ever drawn off screen. |

The line **hides while the F3 debug screen is open**, which owns the same corner, and it forgets
its state on disconnect, so a session's wind is never shown against the next server.

**F8** shows/hides it (rebindable under *Options → Controls → Ventus*; it starts visible, and the
toggle does not persist between sessions).

The line is fed by a small clientbound packet, and it always carries the wind you actually feel:

- **on foot** — the field sampled at your eye position, the same one the physics hooks use;
- **standing in, riding or tracking a sub-level** — the vector that sub-level was given this tick,
  not a fresh sample of the world around it. Reading the vehicle is what makes the instrument look
  the same in both cases: it shows the air the plane is in, ramp-in and smoothing included, and it
  agrees with `/windfield info` by construction.

The client cannot evaluate the field itself (the noise is seeded from the world seed, which only the
server has), so the server pushes it at 5 Hz, and only when the value has moved by more than 0.05 m/s:
a player standing still costs almost nothing, and the payload is twelve bytes. The channel is
registered as **optional**, so a client without Ventus can still join a server that has it — it simply
never receives the packet, and the wind line stays hidden.

## Commands

`/windfield info` (permission level 2) reports, for the sub-level you are standing on or riding:

- the wind sampled for that sub-level, and `windActive` (whether it is non-zero)
- the steady-state breakdown: `base`, `field`, `gust`, `heightFactor`, `capped`, and `total`
- both heights of the sampled column: `groundHeight` (`MOTION_BLOCKING_NO_LEAVES`, what `slope` is
  measured from) and `floorHeight` (`OCEAN_FLOOR`, what `heightFactor` ramps from). Leaves count for
  the second and not the first, so their difference is the canopy — printed rather than inferred
- `universalDrag` (the drag coefficient the `d*w` impulse uses) and the sub-level's mass
- the sub-level's material velocity and the **air-relative velocity** Sable's lift & drag actually sees
- when run by a player, the last wind the **HUD** was sent (`hudWind`) with the level and the eye
  position it was sampled at (`hudEye`). Comparing `hudWind.y` against the sub-level's `wind.y` is
  the check for whether the HUD is reading the vehicle or the world around it

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

Every `/windfield` subcommand needs operator permission (level 2), and `stats` is additionally capped
at **65,000 samples**: the whole sweep runs on the server thread, and the argument ranges would
otherwise allow over four million points, which locks the game rather than merely taking a while. An
over-greedy radius/step pair is refused before the loop starts, with the count in the message (at
radius 4096 the step must be 33 or more).

**Window size matters.** The pressure noise is correlated over `fieldScale` (1500 blocks), so a window
much smaller than that measures a *single patch* of noise rather than the distribution — a 784-block
window first read 85% reversed where the real figure is 50%. The default covers several cells; for a
steadier figure run it at a few far-apart centres and average. It costs nothing to pick a distant
centre: `breakdown` never forces a chunk load, so the sweep is pure arithmetic.

`/windfield set` forces the executing dimension's **base** wind to a vector, for the running session
only. Nothing is written to the config, so it dies with the server:

```
/windfield set 10 0 0    # ten metres a second toward +X, here
/windfield set           # what is in force, and what the config would say instead
/windfield set clear     # drop it, back to ventus-server.toml
```

Only the `[dimensions]` entry is replaced — the pressure field, the gusts, the weather scaling and
the altitude profile are still applied on top, so an override behaves like a real wind rather than a
constant bolted into the physics. Everything that reads the base wind goes through one place, so
`/windfield info`, the HUD and the sub-levels cannot disagree about it. The vector is taken
literally: whole numbers are **not** snapped to a block centre the way a position argument would
snap them, so `10 0 0` really is 10 m/s and not 10.5.

`/windfield storm [clear | rain]` sets the same dimension's weather — bare means a full storm. It
goes through vanilla's own weather parameters, with the durations held open, and the rain and
thunder **levels** then ease in over about five seconds exactly as they do in real weather, which is
what the field reads (`weather` climbs to `rainMultiplier × thunderMultiplier` = 2.4 at full storm).
That five-second ramp is the reason the command is worth having: the wind builds the way it does in
a real storm instead of appearing in one tick.

Both are session-only and per-dimension, which makes them the cheap way to dial in a known wind for
a test or for filming without editing a file or waiting for the weather to turn.

## License

Apache License 2.0. See `LICENSE`. Release notes are in `CHANGELOG.md`.
