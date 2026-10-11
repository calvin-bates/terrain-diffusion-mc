# Porting notes: 26.3 + tall world

## Height layout (current)
- All dimension types: min_y=-64, height=2096, logical_height=2096 (build range y -64..2031).
- noise_settings: noise min_y=-64, height=2096. Bedrock follows the floor via the vanilla `minecraft:bedrock_floor` material rule.
- Sea level stays 63, so there are ~127 blocks from sea level down to bedrock, like vanilla. Mountains can reach the 2031 cap.
- Vanilla ore/feature height ranges line up again because the floor is back at -64.
- Worlds created with the earlier -2032 layout are incompatible; make a new world.

## Done (version-independent)
- Sea level and terrain placement unchanged (sea level 63), so existing terrain code is untouched.
- TerrainDiffusionDensityFunction min/max bounds widened for the taller column.
- gradle.properties: minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.160.5+26.3. Java target 25.

## Not done (needs a machine with Java 25 and network access)
1. build.gradle: switch to the Loom plugin for unobfuscated Minecraft (the 26.x
   `net.fabricmc.fabric-loom` plugin), remove the `mappings` line, replace
   `modImplementation` with `implementation`. Check the current Loom version at fabricmc.net/develop.
2. Rename every Yarn class/method to Mojang names (14 files, ~50 classes), e.g.
   net.minecraft.util.math.BlockPos -> net.minecraft.core.BlockPos,
   ServerWorld -> ServerLevel, Identifier, Text -> Component, RegistryKeys -> Registries, etc.
   Both mixins (BiomeMixin, MinecraftServerMixin) target Yarn method names and signatures; re-check them.
3. Re-verify the dimension_type JSON schema and the 'terrain_diffusion' density function codec against 26.3.
4. ONNX Runtime artifacts, model manifest revision and Java 25 compatibility are unverified.
5. Test: a 4064-tall world is heavy on memory and chunk saves. Expect slower generation.

## Gradle 9.5.0 / JDK 26
- gradle-wrapper.properties now points at Gradle 9.5.0 (was 8.14.1; Loom 1.17.x requires 9.5.0). Run `./gradlew wrapper` once to refresh the wrapper jar.
- build.gradle: replaced `project.archivesBaseName` (removed in Gradle 9) with `base.archivesName.get()`.
- Code still compiles with `--release 25`, so JDK 26 can build it. Java 26 support in Gradle 9.4.1 was stated by the user, not verified here.
- Other Gradle 9 removals may surface once the build runs (Loom version must also support Gradle 9). Send the first error output.

## Status update
- Client `CreateWorldScreen$WorldTab` mixin ported (uses `this$0`, `customizeTypeButton`, `openPresetEditor`).
- `noise_settings/terrain_diffusion.json` rewritten in the 26.3 format; surface rule moved to `worldgen/material_rule/terrain_diffusion.json` (uses vanilla `minecraft:bedrock_floor`, which follows the new world floor).
- dimension_type JSONs gained `default_clock`, `has_ender_dragon_fight`, straw bed rule, ambient light colour.
- Not yet compiled/run: expect further compile or datapack-load errors.
