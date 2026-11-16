# Barricade

Minecraft Java 1.20.1 / Forge 47.3.19 / Java 17.

Production uses one regional packing implementation: regional-8, algorithm 10,
SavedData format 3 and wire field5. It requires OpenGL 4.3 and GPU int64.
The worker compiles only the two stages of `packing.compute`; there is no
historical graph solver, old-layout loader or CPU generation fallback.

Place **Barricade** from the Functional Blocks creative tab, craft eight with
`PPP / CSC / PPP` (planks, cobblestone, stick), or use:

```text
/give @s barricade:barricade
```

## Named Material Tags

Rename a Barricade item in an anvil to a block tag ID such as `minecraft:logs`
or `minecraft:planks`. An optional leading `#` is accepted. Ordinary names
and unnamed items use `barricade:materials`.

Each region freezes its source tag, selected materials and default fallback
snapshot. Different tags stay separate even if their contents happen to match.
Adjacent items with the same tag and matching frozen rules merge normally.
Reloading data packs does not silently change existing region definitions;
new placements use the current rules, and incompatible snapshots stay separate.
`/barricade regenerate <x> <y> <z>` and `/barricade resample` resolve each
affected region's original tag instead of changing every region to the default.

A missing, empty or entirely unsupported custom pool uses the frozen
`barricade:materials` fallback. A partially supported pool uses only its own
supported entries. An empty skeleton layer is allowed and does not borrow
default materials. If the default pool also fails, generation for that
definition stops without repeated attempts or a game crash.

Different material regions do not reserve solid boundary cells against each
other. Their decorative geometry may overlap; overlap is not required.
Native terrain filtering, damage masks, embedding and natural model scales
remain independent of tag selection. Custom names are not retained on
ordinary block drops.

Algorithm 10, the regional-8 parameter fingerprint, SavedData format 3, storage
name `barricade_embedded_v1` and field5 protocol remain unchanged.

## Generation And State

Generation uses a 4x4x4 coarse skeleton, offset by two cells, followed by
independent 3x3x3 fine regions. The CPU prepares bounded candidates and the GPU
evaluates, selects and settles them. Fine regions read their coarse obstacles;
there is no iterative arbitration between neighboring fine regions.
Instance scales remain in `[0.95,1.05]`; models are not shrunk to fill gaps.
Limited exterior overhang is allowed, including across ownership-grid edges.

The server stores current region identities, seeds, selected material rules,
logical cells, destruction masks and embedded instances. It stores neither
generated transforms nor an edit/replay log. Clients reconstruct layout inputs
with the current generator. Only the current data definition is supported;
unreadable data is reported without replacing the store with empty data.
Chunk loading does not scan or repair missing barricade metadata.

Destruction removes decorative objects whose transformed body center lies in
the destroyed cell, not every object whose overhang touches that cell.
The generation baseline includes both live cells and damage cells, so deletion
does not regenerate or refill surviving geometry. Removing a bridge does not
split its stored region identity. Once a stored region has no logical cells
left, its record and damage mask are removed.

Shift-right-click with a supported single-block item to embed a real block.
Embedding is a reversible display overlay, not permanent destruction. Actual
captured mesh geometry suppresses obvious visual penetration while permitting
collision-box overlap and slight contact. Removing the embedded block restores
the original undamaged objects without refill generation. Each insertion has
an independent deterministic 3D pose; state changes retain that pose.
Capture failure leaves the native appearance visible and uses its native shape.

Barricades are ordinary blocks with full-cell gameplay collision and no
BlockEntity. Piston pre-events protect both barricades and embedded instances
against pushes, pulls and crushing.

Barricades are ordinary Blocks with full-cell collision and no BlockEntity.
A centralized client world renderer uses section indexing and transformed mesh bounds.
It captures baked models and buffered block entity renderers once per material.
Opaque/cutout batches render after block entities; translucent batches render
after particles using the particles target in Fabulous graphics.
With Accelerated Rendering enabled, shared static meshes use dense GPU uploads
and instance transforms. Without that mod the same captures replay through
ordinary vertex buffers. No per-frame packing or furniture ticking is performed.
The first accelerated material logs its backend. Generated model parts sample the current world
light at their transformed center. Embedded meshes lazily cache directional face
batches and sample just outside their transformed surfaces, projecting out of the
real opaque host cell when necessary. Both paths read vanilla light without
brightest-neighbor compensation; this is not geometric
self-shadowing or per-vertex ambient occlusion. Render bounds are cached from transformed model
extents, including models that cross their storage cell.

## Model Palette

The block tag `barricade:materials` selects materials. It currently contains 16
vanilla entries and 447 optional Refurbished Furniture entries (`required: false`),
including fridges. Missing optional mods are supported.
Data packs can override `data/barricade/barricade_models/default.json` or add
other JSON files under `data/<namespace>/barricade_models/` for state/weight settings.
By default the client samples `BlockItem.place` in a bounded in-memory Level with
logical server semantics, discovers all written parts, restores NBT and collects
collision shapes. Doors, beds and compatible furniture use the same entry point.
Requests coalesce, failures are cached and sampling runs on the client main thread
with an eight-material/two-millisecond soft tick budget. No real chunks or files
back this sampling environment. Placement callbacks requiring real chunks, a
ServerLevel cast, delayed ticks or nonstandard GPU-only rendering may be unsupported.

```json
{
  "models": [
    {
      "id": "example_slab",
      "block": "minecraft:oak_slab[type=bottom,waterlogged=false]",
      "size": [1.0, 1.0, 1.0],
      "weight": 2,
      "collision_boxes": [
        {"from": [0, 0, 0], "to": [1, 0.5, 1]}
      ]
    }
  ]
}
```

`size` scales the original model along X/Y/Z before the piece rotation. Each
component must be between 0.1 and 1.6. Keep `[1,1,1]` to retain the original
model's proportions and dimensions. Every generated instance has an additional
uniform scale in `[0.95,1.05]`. `weight` controls selection probability.
Collision boxes use original model coordinates, with one unit per block.
Each coordinate must lie in `[-3,4]`; door geometry can extend from Y=0 to Y=2.
Omitting boxes and parts uses automatic placement sampling.
Explicit compound boxes can override sampling for hollow/non-cubic models.
Buffered custom block entity renderers are captured alongside baked models;
an empty auxiliary renderer does not discard the ordinary model. Clients need
the referenced mods, matching placement behavior/configuration and appearance resources.

Optional explicit overrides can use `parts` instead of `block`, with
`collision_boxes` and an optional shared `pivot` (default `[0.5,0.5,0.5]`):

```json
{
  "models": [
    {
      "id": "whole_door",
      "size": [1,1,1],
      "pivot": [0.09375,1,0.5],
      "parts": [
        {
          "block": "minecraft:oak_door[facing=east,half=lower,hinge=left,open=false,powered=false]",
          "offset": [0,0,0]
        },
        {
          "block": "minecraft:oak_door[facing=east,half=upper,hinge=left,open=false,powered=false]",
          "offset": [0,1,0]
        }
      ],
      "collision_boxes": [{"from": [0,0,0], "to": [0.1875,2,1]}]
    }
  ]
}
```

Parts share one rotation, scale, owner and saved instance, so door halves
cannot separate during generation. Offsets are applied before the shared
transform; collision coordinates describe that same shared local space.
Objects have at most 16 parts and 16 boxes. Part offsets lie in `[-3,3]`,
and pivot coordinates in `[-4,4]`. A complete door is selected only when its
actual two-block geometry fits; it is never shrunk into a single-cell pile.

Run `/reload` to apply palette changes to future generation. Existing regions
retain their saved generation inputs until explicit resampling or regeneration;
their geometry is reconstructed by the current generator. This implementation does not run
CoACD in-game or infer arbitrary third-party meshes automatically. Unsupported
placement logic can use explicit overrides. Merely editing the material tag does
not require writing assembly rules.

## Development

The local Gradle cache is `D:\Library\.gradle`.

```powershell
.\gradlew.bat -g D:\Library\.gradle build
.\gradlew.bat -g D:\Library\.gradle runClient
```

The verified build uses JDK 17.0.18+8 with a non-short Unix-domain socket
temporary directory:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.18+8'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:/Library/.gradle/.tmp'
```

`/barricade stats` reports current server state. `/barricade_client stats` reports
sampling, packing and render statistics. `/barricade resample` explicitly
refreshes material selections and samples. `/barricade regenerate <x> <y> <z>`
regenerates the connected region containing the target cell.

GPU regression tests run with `$env:BARRICADE_GPU_TESTS = '1'`.
`pileBenchmark` runs the current GPU full-reconstruction workload, reporting
whole-pile cold times and per-region timings. Its default fine-region size is
three and its default repeat count is three; `BARRICADE_REGION_SIZES` and
`BARRICADE_SCALE_ROUNDS` control offline measurement only. Sampling caches may
already be populated; the report states this separately from cold layout caches.
Historical comparison helpers are test-only and are not included in production
artifacts. Gameplay and same-camera visual validation remain manual.
