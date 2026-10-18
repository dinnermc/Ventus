# Ventus

Wind field mod for **Minecraft 1.21.1 / NeoForge 21.1.259** that makes Sable sub-levels — sails,
planes, boats, balloons — feel the air move.

Ventus hooks into [Sable](https://github.com/ryanhcode/sable)'s sub-level physics and feeds its
lift and drag the velocity **relative to the air** instead of relative to the world, plus an
impulse that makes Sable's universal drag act on that same air-relative velocity. Without it, a
sub-level only experiences wind while it is already moving.

## What you get

- A per-dimension **wind field**: prevailing base wind, pressure field, gusts, weather scaling,
  thermals over hot ground, ridge lift and sink, a jet stream and wind shear — tuned from
  `config/ventus-server.toml`, hot-reloaded, no restart.
- Wind that ramps in with altitude: a parked plane sits still, a sail at 30 blocks catches the
  real air, and a submerged hull gets none.
- A **wind compass** in a screen corner (**F8**, rebindable): direction needle, speed in m/s, and a
  climb marker for rising and sinking air.
- `/windfield info | stats | set | storm` to inspect the field and dial in a known wind.
- `enabled = false` hands the air to another wind mod (or turns it off entirely) without a restart.

**What it is not:** Ventus adds no blocks or items — it is only the air Sable's vessels fly in. It
has no compatibility layer with other wind mods; two mods pushing wind on the same sub-level simply
add, so pick one.

## Requirements and install

- Minecraft **1.21.1**, NeoForge **21.1.259**, Java 21
- [Sable](https://github.com/ryanhcode/sable) `[2.0.6, 2.1)` — required
- Drop `ventus-1.0.0.jar` into `mods/` next to Sable

Ventus on the client is optional: players without it can join, they just never see the wind HUD.

## Configuration

Server: `config/ventus-server.toml` (per-world override: `<world>/serverconfig/ventus-server.toml`).
Client: `config/ventus-client.toml`, which holds a `[windCompass]` section with
`corner = "TOP_LEFT"` — allowed values `TOP_LEFT`, `TOP_RIGHT`, `BOTTOM_LEFT`, `BOTTOM_RIGHT`.
The 37 server keys are frozen for 1.x: names, defaults and ranges will not change inside 1.x.

## Documentation

Full docs live in the **[wiki](https://github.com/dinnermc/Ventus/wiki)** — installation and
compatibility, every config key, how the wind works, commands, the HUD, and the FAQ (including the
storm behaviour that can turn the wind back on itself).

- Source and issues: <https://github.com/dinnermc/Ventus>
- Release notes: [`CHANGELOG.md`](CHANGELOG.md)

## Credits

Sable sub-level physics by [RyanHCode](https://github.com/ryanhcode); the mod and the docs by
dinner. Ventus is an unofficial addon — it is not affiliated with Sable, Create or Create
Aeronautics.

## License

Apache License 2.0. See `LICENSE`.

[![Modrinth](https://img.shields.io/modrinth/v/ventus-mod?label=Modrinth&color=00AF5C)](https://modrinth.com/mod/ventus-mod)
[![Build](https://github.com/dinnermc/Ventus/actions/workflows/build.yml/badge.svg)](https://github.com/dinnermc/Ventus/actions/workflows/build.yml)
[![License](https://img.shields.io/github/license/dinnermc/Ventus)](LICENSE)
