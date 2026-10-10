# Porting notes: 26.3 + 4064-block world

## Done (version-independent)
- All dimension types: min_y=-2032, height=4064, logical_height=4064.
- noise_settings: noise min_y=-2032, height=4064; bedrock gradient moved to -2028..-2023.
- Sea level and terrain placement unchanged (sea level 63), so existing terrain code is untouched.
  Result: ~2000 blocks of stone below the surface, and max build height 2031.
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

## Gradle 9.4.1 / JDK 26
- gradle-wrapper.properties now points at Gradle 9.4.1 (was 8.14.1). Run `./gradlew wrapper` once to refresh the wrapper jar.
- build.gradle: replaced `project.archivesBaseName` (removed in Gradle 9) with `base.archivesName.get()`.
- Code still compiles with `--release 25`, so JDK 26 can build it. Java 26 support in Gradle 9.4.1 was stated by the user, not verified here.
- Other Gradle 9 removals may surface once the build runs (Loom version must also support Gradle 9). Send the first error output.
