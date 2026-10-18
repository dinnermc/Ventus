# Tested versions

Only runs that actually happened — nothing guessed.

| Ventus | Minecraft / NeoForge | Sable | Create Aeronautics | Lift Patch | What was exercised |
| --- | --- | --- | --- | --- | --- |
| 1.0.0 | 1.21.1 / 21.1.259 | 2.0.6 | Loaded on the release server build; the aircraft/ship checks from the 1.0.0 test run passed | Not exercised | The full 1.0.0 test run: server boot, `/windfield info` and `stats` readings, config hot-reload, per-world server override, ship/plane/balloon behaviour, wind HUD, commands |
| 1.0.0 | 1.21.1 / 21.1.259 | 2.0.6 | — | — | `./gradlew build` and the unit suite (field maths, config keys, commands), locally and on GitHub Actions |
| 1.0.0 | 1.21.1 / 21.1.259 | 2.0.2 | — | — | Refused at load by the `[2.0.6, 2.1)` dependency range, as intended |

Not tested: Sable 2.1 (outside the accepted range), other Minecraft or NeoForge versions, and any
combination involving the Lift Patch.
