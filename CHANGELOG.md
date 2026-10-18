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
- Optional client **wind line**: one line of text in a configurable corner with a small needle for
  the direction of the wind relative to the player, the speed in m/s and the climb marker above
  0.1 m/s. The needle eases towards the direction the server sent rather than stepping with the
  packet. `ventus-client.toml` picks the corner; the line hides while the F3 screen is open and
  clears itself on disconnect.
- The HUD reads the wind you actually feel: the field at your eye on foot, and the wind already
  applied to the sub-level you are standing in, riding or tracking — so the instrument looks the
  same in the air as on the ground, and agrees with `/windfield info` by construction.

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
  drag actually sees. Run by a player it also prints `hudWind`/`hudEye`, what the client HUD is being
  fed. The `<pos>` form needs no player, so it works from a console or RCON.
- `/windfield stats [radius] [step]` and `/windfield stats at <pos> …` — how often the wind points
  against the configured base wind, over a grid swept on the server thread. Capped at 65,000 samples.
- `/windfield set [<wind>]` and `/windfield set clear` — force a dimension's **base** wind for the
  running session, with the field, gusts, weather and altitude profile still applied on top. Nothing
  is written to the config. Whole numbers are taken literally rather than snapped to a block centre.
- `/windfield storm [clear | rain]` — set a dimension's weather, bare meaning a full storm, held open
  until something else changes it. The levels ease in over ~5 seconds, so the wind builds as it does
  in a real storm.

### Verification

- Every check in README *Verification for 1.0.0* is ticked, with the readings recorded — including
  the two added for `enabled` and for `set`/`storm`.
- 52 unit tests, all passing.

### Known limitations

- **In a storm, with the wind inverted, the lee turbulence can end up on the wrong side of the
  crest.** `leeGustBoost` reads only the slope's sign on the base axis. Fixing it properly changes
  `WindModel.evaluate`'s signature for every caller, and is deliberately left out of 1.0.0.

### Configuration

- **`enabled`**, the master switch, as the first key of `ventus-server.toml`. Off means zero wind
  everywhere: sub-levels drift nowhere, the commands still answer and read zero, and the HUD
  disappears. Hot-reloaded like everything else, so a server that already gets its weather from
  another mod can turn Ventus off in a second — no launch arguments, no uninstalling the jar.
- The whole key set is frozen: 37 names, defaults and ranges, listed in README *Frozen keys (1.0.0)*.
  New keys may still appear inside 1.x; existing ones will not be renamed or retuned.

### Requirements

Minecraft 1.21.1 · NeoForge 21.1.259 · Java 21 · Sable `[2.0.6, 2.1)`. Outside that range the loader
stops Ventus with a plain dependency message before any mixin is applied (checked against 2.0.2),
and 2.0.6 is the newest published Sable.

### Compatibility

- No arbitration layer for *Aeronautics: Wind and Weather* or *PMWeather Aeronautics*: two mods
  applying wind to the same sub-level simply add. `enabled = false` is the switch.

### Release notes

- Config path checked on a dedicated server: `config/ventus-server.toml`, with per-world overrides
  in `<world>/serverconfig/`.
- The floating-block hook is a `@WrapOperation` rather than a `@Redirect`, so another mod touching
  the same `physicsTick` call composes instead of colliding. The server still boots with the full
  mod set (Create, Copycats, Endless Gravity, Aeronautics) loaded.
- `/windfield set` and `/windfield storm` were exercised over RCON against a live server: the
  override moves `base` and `clear` restores the config value, and `weather` reads 1.00 / 1.50 /
  2.40 for clear / rain / storm.
- The mod icon ships at `assets/ventus/icon.png`, referenced by `logoFile`.
