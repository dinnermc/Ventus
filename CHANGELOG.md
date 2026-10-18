# Changelog

## 1.0.0 — 2026-10-18

First release.

Ventus gives Sable sub-levels a wind field to actually fight: lift, drag and an `d*w` impulse all
read one per-dimension vector instead of the vanilla still air.

### Wind

- **Universal drag (`d*w`) for every sub-level.** The native damping equilibrium moves from `v = 0`
  to `v = w`, so a parked sub-level drifts downwind instead of hanging still.
- **Per-dimension base wind**, set in `ventus-server.toml`, hot-reloadable without a restart.
- **Pressure field** with short-scale **gusts** on top of it, including calm points where gusts fade
  out rather than jumping in.
- **Weather scaling** from the dimension's continuous rain and thunder levels, not the boolean
  raining/thundering flags.
- **Vertical component**: sun-driven **thermals** (gated by daylight, biome warmth and sky clearance)
  and **orographic lift** over terrain, both decaying with altitude.
- **Altitude profile**: a **jet stream** band and **wind shear** that turns the heading with height.
- **Cap** (`maxWindMultiplier`) scales the whole vector rather than clipping components, so
  engaging it changes how hard the wind blows, never which way it points.
- Optional client **wind line**: one line of text in a configurable corner with an eight position
  arrow relative to the player, the speed in m/s and the climb marker above 0.5 m/s.
  `ventus-client.toml` picks the corner; the line hides while the F3 screen is open and clears
  itself on disconnect.

### Terrain

- The terrain-locked terms read `MOTION_BLOCKING_NO_LEAVES`, so a forest edge is not a hill and a
  seabed does not manufacture lift under a boat.
- The height profile deliberately reads `OCEAN_FLOOR` instead, so a boat on the sea and a plane over
  a canopy both count as exposed.
- Ridge lift is measured **along the base wind with its sign**, not as an unsigned speed, so the
  same slope reads as sink once the perturbations have turned the air around.

### Commands

- `/windfield info` and `/windfield info <pos>` — the steady-state breakdown for a point, plus both
  column heights, the sub-level's material velocity and the air-relative velocity Sable's lift &
  drag actually sees. The `<pos>` form needs no player, so it works from a console or RCON.
- `/windfield stats [radius] [step]` and `/windfield stats at <pos> …` — how often the wind points
  against the configured base wind, over a grid swept on the server thread. Capped at 65,000 samples.

### Verification

- All six checks in README *Verification for 1.0.0* are ticked, with the readings recorded.
- 51 unit tests, all passing.

### Known limitations

- **In a storm, with the wind inverted, the lee turbulence can end up on the wrong side of the
  crest.** `leeGustBoost` reads only the slope's sign on the base axis. Fixing it properly changes
  `WindModel.evaluate`'s signature for every caller, and is deliberately left out of 1.0.0.

### Requirements

Minecraft 1.21.1 · NeoForge 21.1.259 · Java 21 · Sable 2.0.6 (pinned to the `2.0.x` line).

### Before publishing

- Move the third mixin pass from `@Redirect` to `@WrapOperation`, so another mod redirecting the
  same `physicsTick` call site composes instead of conflicting. NeoForge 21.1 already bundles
  MixinExtras.
