# Ventus development

How to build the mod, the switches used while debugging it, the regression checklist to run against
a new release of Sable, and how the mixin hook is wired.

Player-facing documentation lives in the [wiki](https://github.com/dinnermc/Ventus/wiki); the short
[README](../README.md) is the front door.

## Building & testing

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
.\gradlew build --console=plain
.\gradlew test --console=plain
.\gradlew runClient --console=plain
.\gradlew runServer --console=plain
```


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


## Testing

If a check fails, write down the coordinates, altitude, time of day and weather **before** touching any code; that is enough to tell a wrong model from a misreading.

Height profile checks (use `/windfield info` for live values):

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

Field and gust checks:

1. **Smooth, continuous wind** — fly straight and level for a couple of minutes. The wind should
   change gradually; no jumps. (`/windfield info` shows `field` growing and shrinking smoothly.)
2. **Direction dominates, in dry weather** — with `fieldStrength = 1` in clear, dry weather, the wind should
   still blow broadly the way `[dimensions.overworld] direction` says, only wobbling around it.
   **Under `/weather thunder` this does not hold**: the storm scales the perturbation and not the base
   wind, so the heading can invert. That is accepted behaviour — see *The storm can reverse the wind*
   further down. Run this check dry.
3. **The cap holds** — raise `fieldStrength` high; the total should saturate at
   `maxWindMultiplier × base` rather than exploding (`/windfield info` shows `capped=true`).
4. **Gusts** — raise `gustAmplitude` (e.g. to `1.0`) and watch the wind speed up and ease off,
   mostly along the same direction. Still no discontinuities.
5. **No beachballing** — with gusts on, tick time should be unaffected (two extra noise samples
   per sub-level per tick).

Weather checks:

1. **Rain roughens the field** — run `/weather rain` and immediately start sampling
   `/windfield info` once a second: `weather` must **climb** from `1.00` towards `1.50` over the ~5 s
   vanilla takes to fade the rain in, never jump. `field` and `gust` grow by that factor while `base`
   stays put.
2. **Storm is stronger** — `/weather thunder` → `weather` eases up to `2.40` (1.5 × 1.6).
3. **Indoors is not a shelter** — the weather is global to the dimension, so a closed hangar
   reports the same `weather` as open sky; only changing dimension (Nether/End, whose rain level is
   always 0) brings it back to `1.00`.

Terrain, thermals and budget checks:

1. **Thermals need sun** — `/time set noon` over a desert, then compare `/windfield info` in the
   open against `/time set midnight`: `thermal` should be a positive `total.y` by day and exactly
   `0.000` at night. Dawn and dusk must ramp over ~75 s each rather than switch.
2. **Thermals need heat** — the same desert versus a snowy biome at noon: the desert `thermal`
   should be at the ceiling (`thermalStrength`), the snowy one exactly `0.000`.
3. **Thermals are bubbles** — hold altitude over a desert: `thermal` should rise and fall as you
   drift through them, not sit at a constant value. Circling a strong one should hold you up.
4. **Rain kills thermals** — `/weather rain` over the desert at noon → `thermal` eases to `0.000`
   while the horizontal wind stays up (it is only convection that needs the sun).
5. **Ridge lift** — fly straight at a hill face-on: `orographic` should go positive (`wind.y`
   positive, you feel yourself rise) and `slope` should read positive.
6. **Sink and rough air behind the ridge** — cross the crest: `orographic` flips negative,
   `leeBoost` becomes non-zero and the gusts visibly roughen. That is the dangerous side, on purpose.
7. **A wall is not a launch pad** — fly at a sheer cliff: `slope` clamps at `1.000` and
   `orographic` saturates instead of producing an absurd updraft.
8. **The ridge lets go with altitude** — over the windward face, climb 50 blocks and re-run
   `/windfield info`: `orographic` must drop by a factor of `e` (2.72), and the `leeBoost` roughness
   fades with it. At 500 blocks both are essentially gone. Thermals are the exception — they do **not**
   fade, so the same climb over a desert leaves `thermal` untouched.
9. **Nothing lifts under a canopy** — under a tree, inside a hangar or in a cave at noon in a hot
   biome: `thermal` reads exactly `0.000` even though the biome is warm and the sun is up. Step out
   into the open and it comes back.
10. **No jolt at the ridge line** — fly past a ridge at a constant altitude: `thermal` and
    `orographic` must **ease** over ~1.5 s as the terrain columns change, never step in one tick. The
    `gust` component may still jump; only the terrain-derived terms are smoothed.
11. **The clamp holds** — stand at a sheer cliff in a thunderstorm at noon over a desert and check
    `/windfield info`: `total` stays within `maxWindMultiplier` × base × `windScale` (9 m/s with the
    defaults) even though every term is near its own ceiling. And the same command works with **no
    sub-level underfoot**, reporting `base`/`field`/`gust`/`thermal`/`orographic` for the position —
    so you can walk or fly around sampling biomes without boarding anything.
12. **The sea is flat** — fly low over open water with a sloping seabed underneath (shallow
    coastal water is easiest): `slope` must read `0.000` and `orographic` `0.000` — a sloping
    seabed must not manufacture an updraft under a boat. Deep ocean reads clean either way;
    shallow water is the case that catches it.
13. **A forest edge is not a hill** — skim the boundary of a forest at low altitude, crossing from
    clearing to canopy and back: `slope` must stay near `0.000`. Only real ground counts; leaves are
    excluded from the surface the air is forced over.
14. **The wind keeps its heading when the cap engages** — at the cliff in a storm, compare `total`
    against the *uncapped* sum `base + field + gust`, **not** against `base`: what has to survive is the
    direction of that sum. The cap scales the whole vector rather than clipping components, so engaging
    it changes how hard the wind blows, not which way it points. (It preserves the *sum's* heading,
    which in a storm is not necessarily `direction`'s — see *The storm can reverse the wind*.)
15. **A parked heavy sub-level does not float** — park a heavy sub-level in mid-air under a
    thermal at noon in a desert, and leave it. It must settle, not climb. The drag impulse is 3D, so
    a thermal does push up on it — at most `universalDrag` × `thermalStrength` ≈ 0.09 × 2.5 =
    0.23 m/s², and only 0.023 m/s² near the ground where `surfaceCalm` applies — which is far below
    gravity, but this is the check that proves it.
16. **A boat near the shore is not becalmed** — at water level, compare `/windfield info` over
    open ocean against the same spot just off a shallow beach. The height profile measures against
    `OCEAN_FLOOR`, which ignores water, so `heightFactor` there is the depth under the hull rather
    than anything about the sea surface: with `surfaceCalm` 0.1 and `fadeHeight` 32 the ramp is
    `0.1 + 0.9 × smoothstep(depth/32)`, about **0.83** in 23 blocks of water and about **0.12** in
    three, so sailing inshore bleeds the wind away smoothly rather than becalming the boat. Measured
    in the flat test world over two water columns 23 and 3 blocks deep on a floor at Y -61, sampled
    one block above each surface: `heightFactor` `0.859` and `0.139`, with `floorHeight=-61.0` in
    both — the seabed, not the water. A sample taken **in** the surface block itself reads
    `heightFactor=0.000` and zero wind, because a submerged point gets nothing.

Jet and shear checks:

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
   so everything below the jet band is exactly the ground-level wind.
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

Balloon and vehicle checks:

1. **A balloon drifts with the wind** — build a floating-block balloon and rise above `fadeHeight`
   (32 blocks), where the `surfaceCalm` ramp no longer holds it down, then let it sit still. It must
   accelerate downwind and settle at the local wind speed. Give it ~10 s: the coupling rides on
   `universalDrag` (`d = 0.09`, time constant ~11 s), so nothing visible happens in the first second.
   With `-Dventus.debugWind=true` the line `[ventus] floating-pass` proves the hook is live — if it
   never appears, the sub-level is not entering `prePhysicsTick` at all.
2. **The balloon catches the wind with its own friction** — the drift has to come from the
   floating material's friction; left alone it brakes towards `v = 0`, cancelling the
   `mass * d * w * dt` coupling that pulls towards `v = w`. Ventus adds no size term of its own, so a
   large light balloon and a small dense one both converge on the wind speed, the big one visibly
   faster because its friction coefficients are its sail area.
 3. **Aircraft and ships are unchanged** — fly the plane and sail a ship. Anything with lift
    providers takes the two `contributeLiftAndDrag` passes and never relied on the floating-block path;
    `physicsTick` is still called for it (the wrap covers every call) but returns at its own
    `needsTicking()` guard before writing a single impulse, so the output is identical. This is the
    regression that is easiest to miss, because the new hook sits on a call that ships also reach.
 4. **`disableWind` still freezes everything** — with `-Dventus.disableWind=true` a plane, a ship
    and a balloon must all sit dead still. The chain is: `isCalm()` short-circuits `WindSampler` before
    it samples anything, `sample()` would write zeros anyway, `airRelative` returns its input unchanged
    when the wind is zero (so no allocation either), and `ventus$windDrag` bails on
    `lengthSquared() == 0`. All three hooks degrade to exact identities.

Client checks:

1. **Wind line** — F8 hides/shows it; turn in place and the needle swings with the view while the
   figure stays put; fly up to `fadeHeight` and the speed climbs; the needle must never jump.
2. **Lift and sink are readable** — climb a windward face at noon: the line should grow a `↑`
   marker with the vertical component, and `↓` behind the ridge. The speed figure itself must still
   describe the **horizontal** wind only, so it does not jump when you cross a thermal.
3. **Rebind** — Options → Controls → Ventus → *Toggle Wind Compass* accepts another key.
4. **The needle tracks the shear** — climb from sea level to the jet core: the needle must sweep
   smoothly through the ~33° of turn the defaults give, and the speed figure must climb into the high
   teens. The fade and the jet are both smooth, so there is nothing for the needle to step on at
   `fadeHeight` or at the edge of the band.
5. **Placement** — F3 takes the line away, and `ventus-client.toml` moves it to any of the four
   corners.
6. **Stale data** — on a server with Ventus, then on one without: the line must not survive the
   switch.
7. **The same instrument in the air and on the ground** — aboard a sub-level, `/windfield info` must
   print a `hudWind` identical to that sub-level's `wind`; on foot it is a fresh sample at the eye.
   The climb marker must appear in the plane over a desert at noon exactly as it does on the ground
   under the same column of air.

Configuration checks:

1. **`enabled = false` freezes everything, with no restart** — on a running dedicated server, set
   `enabled = false` in `config/ventus-server.toml`. `/windfield info <pos>` must report `base`,
   `field`, `gust` and `total` all at zero, the jet and shear multipliers back to `1.00` and
   `slope=n/a`; flip it back and the wind returns. The key is also appended to an existing config
   file on the next start, which is what an upgrading server will see.
2. **`set` and `storm` dial in a known wind** — `/windfield set 10 0 0` must move `base` to
   `(10, 0, 0)` at sea level and the profiled `base` to 10 × shear; `/windfield set clear` must
   restore the configured `(3, 0, 0)`; `/windfield storm`, `rain` and `clear` must read `weather`
   `2.40`, `1.50` and `1.00` once the ~5 s ramp has run. The vector is parsed with `vec3(false)`,
   because the default `vec3()` snaps whole numbers to a block centre and would turn `10 0 0` into
   `(10.5, 0, 0.5)`.

## Findings from the 1.0.0 verification

**The storm can reverse the wind — decided, and accepted.** At `weather = 2.40` the
pressure perturbation ran `|field|` = 11.96 against `|base|` = 2.80 — 4.27× — and pointed nearly the
opposite way, so the storm effectively reversed the wind. The arithmetic is not at fault: `weather`
scales `field` and the gusts but never the base wind, so at storm strength the perturbation
legitimately overtakes the prevailing flow, and the budget (`maxWindMultiplier`) bounds the
*magnitude* (3× prevailing), never the heading.

`ventus-server.toml`'s `fieldScale` note and the check *Direction dominates, in dry weather* both
describe the heading as coming from `direction`; the storm caveat above is what qualifies them.
Bounding the perturbation against the base, so `direction` always wins, remains the fallback if it
plays badly in the world; it is deliberately not done yet, because a config key is easy to add later
and awkward to take away.

**Measured, with the world's own noise.** `/windfield stats` sweeps a grid sampled by the dimension's
real `SimplexNoise` at the real seed, under the live rain and thunder levels (four independent
8256-block squares, 16641 samples each). The headline is deliberately rounded, because it is an order
of magnitude rather than a percentage:

- **In clear weather, approximately 1 point in 20** has the wind pointing against `direction`.
- **In a storm, approximately 1 point in 4.**

Both are rounded for good reason. A 4096-block window holds only about seven `fieldScale` (1500)
patches of noise, and four centres give a few dozen in all, so the margin is several points; and every
figure tracks this particular world's seed. A 784-block sweep — a single patch — would report
most points reversed where the truth is half, which is the same effect at its extreme.

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

`WindField` measures the terrain slope *along the base
direction*, but orographic lift is driven by the **local** wind. During an inversion those two point
opposite ways: if the hill rises toward `direction` while the local wind blows the other way, the air
is actually descending and the code handed it lift anyway — a plain speed (a hypotenuse, always
positive) left the sign resting on the terrain alone. `WindModel.alongBaseWind` supplies the
**signed component** of the local wind along the base axis, so the term turns negative exactly when
the air has reversed and passes through zero continuously at the calm point instead of jumping.
Covered by `anInvertedLocalWindTurnsRidgeLiftIntoSink`.

**What that changes in normal flight — decided deliberately, and worth feeling before tuning.** The
slope is measured in a single direction, so the model says the ground rises *that* way and the
wind's cross-slope part climbs nothing; multiplying by the whole wind speed credits it anyway.
Lift is now `slope × (total · û_base)` rather than `slope × |total|`, so it is always the same or smaller,
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

The mixin side of *Aircraft and ships are unchanged* is checked structurally: the mod loads with
no mixin errors against the full mod set, the two `@ModifyArg` hooks are untouched, and
`physicsTick` returns at its own `needsTicking()` guard before writing any impulse for a sub-level
without floating blocks.

**The forest-edge check needs no client.** `/windfield info <pos>` reads from the server, and with
both column heights printed the leaf question is answered from a console or RCON instead of being
worked backwards out of the other numbers.


## Performance baseline

Ventus samples the field **once per sub-level per tick**, plus a terrain height read (twice, for
the two slope probes), a biome read, a sky-height read and a smoothstep to each of those. Whether
that costs anything is a measurement, not an argument, so there is a baseline to compare later
changes against.

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
   later change, and the delta between the two pairs is the useful figure — a single absolute number
   on its own says nothing, because Sable, Create and the world dominate it.
5. **A second shape of world: balloons.** `WindField.airRelative` allocates a `Vector3d` whenever
    the wind is non-zero, and the floating-block pass carries a call into it —
    once per sub-level per physics sub-step. Ships reach that wrap too, since it wraps the call
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
  with `@WrapOperation` — two `double` parameters precede the velocity there, so its argument index
  differs from the other target and a `@ModifyArg` would be easy to miscount. The argument is
  read-only, so only the forces derived from it change.
- The floating-block pass is what makes a balloon drift at all. `FloatingBlockController.applyFriction`
  opposes the body velocity *in world space*, which pulls a balloon towards `v = 0` and cancels the
  coupling below; the two neutralise each other and it never moves. Handed `v - w` instead, the very
  same friction becomes a sail — zero exactly at `v = w`, pushing downwind from rest — with the
  floating material's own coefficients as its area. Nothing in Ventus scales with balloon size.
- That third pass is a `@WrapOperation` and not a `@Redirect` on purpose. A `@Redirect` owns the call
  site outright, so if any other mod redirects the same `physicsTick` invocation the two mixins
  conflict and the game refuses to start; a wrap composes with them instead, and the original is
  still called explicitly so nothing is lost. NeoForge 21.1 already bundles MixinExtras, so this
  costs no extra dependency. The server still boots with the full mod set (Create, Copycats,
  Endless Gravity, Aeronautics) loaded.
- The mixin implements `WindCarrier` to expose its `@Unique` wind state to `WindSampler` (the wind
  vector, the last height factor, and the first-sample time). The state is written by `WindSampler`
  and read by the hooks, both on the server thread, so no synchronisation is needed.
- `ventus$windDrag` injects `mass * d * w * dt` at the centre of mass, shifting the native universal
  damping equilibrium from `v = 0` to `v = w` so every ticking sub-level drifts with the wind.

Because the hooks are injected into the concrete caller rather than the `BlockSubLevelLiftProvider`
interface (which Mixin cannot inject into without `@Overwrite`), Ventus pins Sable to
`[2.0.6, 2.1)` — see `sable_version_range` in `gradle.properties`. **Checked both ends:** 2.0.6 is
the newest published Sable, so the top of the range is current; and with 2.0.2 in the folder the
loader refuses Ventus before any mixin is applied, with
`Mod ventus requires sable 2.0.6 or above, and below 2.1` — a dependency message, not a mixin crash.
