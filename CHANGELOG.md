# Changelog

## 1.0.0 (Minecraft 26.3)

First public release of Orogeny, a fork of [Terrain Diffusion](https://github.com/xandergos/terrain-diffusion-mc) by xandergos.

### Terrain
- Ported to Minecraft 26.3 with a build range of Y -64 to 2031.
- World scale (1 to 6) picked when you create a world, saved with it.
- Shelf, slope and abyss sea floor; summits eased under the build limit instead of cut flat.

### Rivers and lakes
- Hydrology from the generated terrain: streams, navigable rivers and main rivers carving their own valleys; lakes in filled basins, sitting flush with their shores.
- Deltas, oxbow lakes, alluvial fans, waterfalls, river sinks into caves; level water steps on steep ground; salt flats where arid lakes dry out.

### Oceans and coasts
- Volcanic islands drawn by the AI model, from young islands through flooded calderas and barrier-reef islands to atolls; model-drawn archipelagos; mushroom islands and mirage isles.
- Reefs, lagoons, carbonate banks, seamounts and guyots, trenches, rift seas and active margins, hydrothermal vents.
- Spits, tombolos, cuspate forelands, barrier islands, sea stacks, sea arches, sea caves and wave-cut overhangs.
- Orogeny's own iceberg fields and arctic ice islands (vanilla icebergs are off), sea ice in cold seas.

### Biomes
- 85 new surface and cave biomes placed by climate: vanilla-style patch biomes (meadow, cherry grove, dark forest, rare large pale gardens, old growth forests, dappled forest), real-world biomes (boreal forest, Siberian taiga, temperate and tropical rainforests, cloud forest, redwoods, aspen groves, steppe, prairie, tundra, bog, marsh, salt marsh, tidal flats and more), and showpieces (volcanoes and lava fields, glaciers, canyons, badlands mesas, hoodoos, dunes, karst towers in the tropics, Yellowstone with geysers, sulfur and cinnabar, Yosemite, painted mountains, white cliffs, geothermal basins).
- Landforms only appear where the terrain already suits them.

### Caves
- Orogeny's own noise caves under any terrain, measured from the ground above, with a solid roof.
- 36 cave biomes in common, uncommon, rare and very rare tiers, including vanilla's lush caves, deep dark and sulfur caves (with sulfur cubes, under volcanic ground and Yellowstone).
- Giant halls (geode hall, deep dark forest, selenite beams), ancient cities in caverns that hold them, underground lakes and lava, cenotes, blue holes, ravines, deep shafts, collapse domes and cave mouths.
- Cave formations: columns, stalactites and stalagmites, flowstone, rimstone pools, crystal rooms, boxwork, natural bridges, rock shelters, travertine terraces.

### Structures and gameplay
- Villages and outposts sit on ground pads; vanilla structures spawn everywhere except ancient cities, which stay under mountains.
- Axolotls in high alpine lakes; fish in lakes at any height; boats float back up to the surface.

### Tools and performance
- `/orogeny-explore`: browser map with relief, elevation, climate, rivers, biomes and caves views.
- `/orogeny-find`: teleport to the nearest biome, cave or landmark.
- Tiles around you are built ahead of time (`performance.prefetch_tiles`); prioritised inference queue; faster cave field.
