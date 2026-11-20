# String lights verification

From the repository root, run the unit suite and the isolated Forge dedicated-server tests:

```powershell
.\gradlew.bat test --offline --console=plain
.\gradlew.bat -I src/gametest/string-lights.init.gradle runGameTestServer --offline --console=plain
```

Omit `--offline` when the dependencies are not cached. The Forge log must report that all required GameTests passed. The init script creates a fresh run directory under `build/verification`, excludes the client-only Accelerated Rendering mod on the dedicated server, and does not use the normal `run` directory.

For a client check, run `runClient` with the same init script. Create a Creative Superflat world, then run `/stringlights_fixture build` and `/stringlights_fixture connect`. `/stringlights_fixture camera 0` returns to the overview, `1` looks near the chunk boundary, and `3` aims at the feeder box from the floor. Use `/time set midnight` to inspect the ambient lighting. The fixture commands are present only in the verification run, not in the packaged mod.
