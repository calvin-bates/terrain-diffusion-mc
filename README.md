# Orogeny

Orogeny is a Fabric mod for Minecraft 26.3 that generates realistic, AI-drawn terrain: whole continents, mountain ranges up to Y 2031, rivers that drain real watersheds, lakes, coasts and islands, caves under every kind of ground, and 85 new surface and cave biomes, all built from vanilla blocks.

The terrain itself comes from [Terrain Diffusion](https://github.com/xandergos/terrain-diffusion-mc) by xandergos (SIGGRAPH 2026), a diffusion model trained on real elevation data. Orogeny is a fork of that mod (MIT licensed) that builds a full world on top of it.

## Features

- **Realistic terrain at real scale.** One block is 5 to 30 real metres (you choose the world scale when you create a world). Mountains reach the 2031 build limit; the sea floor drops into a continental shelf, slope and abyss.
- **Rivers and lakes from real hydrology.** Water drains downhill over the generated terrain: streams join into rivers and main rivers that carve their own valleys, and filled basins become lakes. Rivers are wide and deep enough for boats; deltas, oxbow lakes, alluvial fans and waterfalls form where the ground calls for them.
- **Oceans, coasts and islands.** Continental shelves, reefs and atolls, barrier islands and lagoons, sea stacks, sea arches and wave-cut overhangs, spits and tombolos, rift seas, active margins with trenches, icebergs and arctic ice islands.
- **Biomes that follow the climate.** Every biome is placed from the model's temperature and rainfall, not vanilla's noise: boreal forests, temperate and tropical rainforests, cloud forests, redwoods, steppe, tundra, salt flats and glaciers, plus Terralith-style showpieces (Yellowstone with geysers, Yosemite, painted mountains, white cliffs, volcanoes) that only appear where the terrain already suits them.
- **Caves under any terrain.** Orogeny's own noise caves run under every mountain (inside a peak at Y 1500 too), with cave biomes from andesite and tuff caves to fungal grottos, deep dark forests, molten caverns, glowworm grottos and giant geode halls; underground lakes, cenotes and river sinks; and cave formations (columns, flowstone, rimstone pools, crystal rooms, natural bridges).
- **Vanilla structures and mobs fit in.** Villages and outposts sit on levelled ground pads, ancient cities in caverns big enough to hold them, and vanilla's sulfur caves, lush caves and deep dark all appear.
- **A terrain explorer.** `/orogeny-explore` opens an interactive map of the world in your browser (relief, elevation, climate, rivers, biomes and caves).

## Which build should I use?

Three builds are attached to every release. All need the same Minecraft and Fabric versions.

| Build | For | Setup |
|---|---|---|
| **windows** (recommended) | Windows with any modern GPU (DirectML) | None |
| **cuda** | NVIDIA GPUs on Linux (or Windows if you prefer CUDA) | [CUDA + cuDNN install](CUDA_INSTALL.md) |
| **cpu** | Everything else; uses CoreML on Apple Silicon Macs | None |

The CPU build works everywhere but is slow except on Apple Silicon.

## Requirements

- Minecraft 26.3 with [Fabric Loader](https://fabricmc.net/) 0.19.5+ and [Fabric API](https://modrinth.com/mod/fabric-api)
- Java 25 or newer
- A GPU with about 2 GB of free VRAM (2.5 GB to keep all three models loaded, see `inference.offload_models`)
- About 2.5 GB of extra RAM for the models (raise Minecraft's memory allocation if needed)
- An internet connection the first time you launch, to download the models (about 2.5 GB) into `.minecraft/terrain-diffusion-models`

## Getting started

1. Put the jar for your build and Fabric API in your `mods/` folder.
2. Launch Minecraft once while online so the models download.
3. Create a world. **Orogeny** is already selected as the world type; when you press **Create New World**, pick a **World Scale** (2 is recommended).
4. The first chunks take a little longer while the models warm up. Spawn is placed on land near the origin.

### World scale

Each world saves its scale (1 to 6). Scale `n` means one block is `30 / n` metres, so higher scales spread the same terrain over more blocks: taller mountains, longer rivers, bigger everything. Scale 2 is a good balance. Lower scales run the AI model more often (more GPU load); higher scales make taller worlds (more CPU and memory load).

## Commands

| Command | What it does |
|---|---|
| `/orogeny-explore` | Starts the terrain explorer and prints a link (`http://localhost:19801`) to open it in your browser. |
| `/orogeny-find biome <name>` | Teleports you to the nearest patch of a biome. |
| `/orogeny-find cave <name>` | Teleports you to the nearest cave biome or cave site of that kind. |
| `/orogeny-find <feature>` | Teleports you to the nearest landmark: volcanoes, deltas, atolls, iceberg fields and more (press Tab for the list). |
| `/orogeny-column` | Debug: prints what the generator did to the column you're standing in (rivers, lakes, ground levels). |

## Configuration

Settings live in `config/orogeny.properties`, created on first launch with comments for every option. The ones you're most likely to change:

```properties
# "gpu", "cpu", or "auto" (try the GPU, fall back to the CPU).
inference.device=gpu

# Keep only one model on the GPU at a time (about 1.5-2 GB of VRAM). With 4 GB or more free, set this to false:
# all three models stay loaded and tiles build noticeably faster.
inference.offload_models=true

# Build the terrain around you ahead of time while the GPU is otherwise idle.
performance.prefetch_tiles=true

# Size multiplier for the patch biomes (meadow, dark forest, birch forest ...).
biomes.size=2.5
```

Rivers, lakes, oceans, landforms and caves each have their own switches (`rivers.*`, `oceans.*`, `landforms.*`, `caves.*`). Changes only affect newly generated chunks.

## Common issues

**Chunks load slowly at first.** Every new 256-block tile runs the AI model. Leave `performance.prefetch_tiles` on, and on a GPU with room to spare set `inference.offload_models=false`.

**A dynamic link library (DLL) initialization routine failed.** Update Java to the newest release (Java 25 or newer).

**LoadLibrary failed with error 126** *(CUDA build only).* CUDA or cuDNN isn't installed correctly; see [CUDA_INSTALL.md](CUDA_INSTALL.md).

**`Failed to load terrain-diffusion models`.** Usually out of memory: give Minecraft about 2.5 GB more RAM.

**Not compatible with PolyhedralDev's Terra or other world-type mods.** Orogeny replaces the overworld generator for its own world type; other world types are untouched.

Report other problems on the [issue tracker](https://github.com/calvin-bates/terrain-diffusion-mc/issues).

## Building from source

The build needs Java 25 and an internet connection (it fetches the pinned model manifest from Hugging Face).

```
./gradlew build -PuseDml=true    # windows (DirectML), the default
./gradlew build -PuseCuda=true   # cuda
./gradlew build -PuseCpu=true    # cpu (and CoreML on macOS)
./gradlew buildAll               # all three
```

Jars appear in `build/libs/` as `orogeny-<version>-<build>+26.3.jar`. The windows build uses `libs/onnxruntime-dml.jar` from this repository; see the [ONNX Runtime build docs](https://onnxruntime.ai/docs/build/inferencing.html) and the [DirectML requirements](https://onnxruntime.ai/docs/execution-providers/DirectML-ExecutionProvider.html#build) to build it yourself.

## Credits and license

- **Terrain Diffusion** (the AI model and the original mod): [xandergos](https://github.com/xandergos/terrain-diffusion-mc), MIT license. The models are downloaded from [xandergos/terrain-diffusion-30m-onnx](https://huggingface.co/xandergos/terrain-diffusion-30m-onnx).
- Biome and cave styles inspired by Terralith, Biomes O' Plenty, William Wythers' Overhauled Overworld and vanilla Minecraft, rebuilt with vanilla blocks only. No code or assets from those mods are included.
- Orogeny is released under the MIT license; see [LICENSE.txt](LICENSE.txt).
