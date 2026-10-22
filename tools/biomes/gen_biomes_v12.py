#!/usr/bin/env python3
"""
Generates the data for Terra's v1.2 biomes (real-world land biomes, biomes plan v1.2): biome files, their trees and
ground cover (configured and placed features), biome tags and the surface rules in Terra's material rule.

Every biome starts from a vanilla 26.3 biome (its mob spawns, music, sky, carvers and every feature step except
vegetation) and gets its own temperature, colours, vegetation and surface. Tags: each biome joins every vanilla biome
tag its base biome is listed in directly (so structures, villages, mob variants and animal variants follow the base),
plus any extra tags given here. Only vanilla blocks are used.

Usage (from the repo root):
    python3 tools/biomes/gen_biomes_v12.py <vanilla data dir>    # e.g. a misode/mcmeta checkout's data/minecraft

Re-running is safe: it rewrites its own files and replaces its own entries in tags and the material rule.
"""
import json
import os
import sys

VANILLA = sys.argv[1] if len(sys.argv) > 1 else "/home/claude/tc/vdata/data/minecraft"
RES = "src/main/resources/data"
TERRA = f"{RES}/terra/worldgen"
MC_TAGS = f"{RES}/minecraft/tags/worldgen/biome"
MATERIAL_RULE = f"{TERRA}/material_rule/terrain_diffusion.json"

AIR_FILTER = {"type": "minecraft:block_predicate_filter",
              "predicate": {"type": "minecraft:matching_block_tag", "tag": "minecraft:air"}}


def tri(n, plateau=0):
    return {"type": "minecraft:trapezoid", "max": n, "min": -n, "plateau": plateau}


def leaves(block):
    return {"id": block, "properties": {"distance": "7", "persistent": "false", "waterlogged": "false"}}


def log(block):
    return {"id": block, "properties": {"axis": "y"}}


def uniform(a, b):
    return {"type": "minecraft:uniform", "max_inclusive": b, "min_inclusive": a}


def survive(sapling):
    return {"type": "minecraft:block_predicate_filter",
            "predicate": {"type": "minecraft:would_survive", "state": sapling}}


# ---------------------------------------------------------------------------------------------------------------
# Configured features (data/terra/worldgen/feature)
# ---------------------------------------------------------------------------------------------------------------

FEATURES = {
    # Krummholz: stunted, wind-cut spruce at the treeline, a low bush of spruce needles on a stub of trunk.
    "krummholz": {
        "type": "minecraft:tree", "below_trunk_provider": "minecraft:soil_beneath_tree", "decorators": [],
        "foliage_placer": {"type": "minecraft:bush_foliage_placer", "height": 2, "offset": 1, "radius": uniform(1, 2)},
        "foliage_provider": leaves("minecraft:spruce_leaves"), "ignore_vines": True,
        "minimum_size": {"type": "minecraft:two_layers_feature_size", "limit": 0, "upper_size": 0},
        "trunk_placer": {"type": "minecraft:straight_trunk_placer", "base_height": 1, "height_rand_a": 1, "height_rand_b": 0},
        "trunk_provider": log("minecraft:spruce_log")},
    # Stunted bog spruce: short and thin.
    "stunted_spruce": {
        "type": "minecraft:tree", "below_trunk_provider": "minecraft:soil_beneath_tree", "decorators": [],
        "foliage_placer": {"type": "minecraft:spruce_foliage_placer", "offset": uniform(0, 1), "radius": uniform(1, 2),
                           "trunk_height": uniform(0, 1)},
        "foliage_provider": leaves("minecraft:spruce_leaves"), "ignore_vines": True,
        "minimum_size": {"type": "minecraft:two_layers_feature_size", "limit": 1, "upper_size": 1},
        "trunk_placer": {"type": "minecraft:straight_trunk_placer", "base_height": 3, "height_rand_a": 2, "height_rand_b": 0},
        "trunk_provider": log("minecraft:spruce_log")},
    # Oak shrub for scrub and riverbanks.
    "oak_bush": {
        "type": "minecraft:tree", "below_trunk_provider": "minecraft:soil_beneath_tree", "decorators": [],
        "foliage_placer": {"type": "minecraft:bush_foliage_placer", "height": 2, "offset": 1, "radius": 2},
        "foliage_provider": leaves("minecraft:oak_leaves"), "ignore_vines": True,
        "minimum_size": {"type": "minecraft:two_layers_feature_size", "limit": 0, "upper_size": 0},
        "trunk_placer": {"type": "minecraft:straight_trunk_placer", "base_height": 1, "height_rand_a": 0, "height_rand_b": 0},
        "trunk_provider": log("minecraft:oak_log")},
    # Redwood (Biomes O' Plenty style, vanilla blocks): a 2x2 trunk of reddish mangrove log 30-50 blocks tall under
    # a narrow conical spruce crown, on podzol.
    "redwood": {
        "type": "minecraft:tree", "below_trunk_provider": "minecraft:soil_beneath_tree",
        "decorators": [{"type": "minecraft:alter_ground", "provider": "minecraft:podzol_beneath_tree"}],
        "foliage_placer": {"type": "minecraft:mega_pine_foliage_placer", "crown_height": uniform(14, 20), "offset": 0,
                           "radius": 0},
        "foliage_provider": leaves("minecraft:spruce_leaves"), "ignore_vines": True,
        "minimum_size": {"type": "minecraft:two_layers_feature_size", "lower_size": 1, "upper_size": 2},
        "trunk_placer": {"type": "minecraft:giant_trunk_placer", "base_height": 26, "height_rand_a": 8, "height_rand_b": 12},
        "trunk_provider": log("minecraft:mangrove_log")},
    # Pools for marsh and bog: vanilla's lake feature with water, lined with mud; the water sits flush with the ground.
    "wetland_pool": {
        "type": "minecraft:lake", "barrier": {"id": "minecraft:mud"}, "can_place_feature": {"type": "minecraft:true"},
        "can_replace_with_air_or_fluid": {"type": "minecraft:not", "predicate": {
            "type": "minecraft:matching_block_tag", "tag": "minecraft:features_cannot_replace"}},
        "can_replace_with_barrier": {"type": "minecraft:not", "predicate": {
            "type": "minecraft:matching_block_tag", "tag": "minecraft:lava_pool_stone_cannot_replace"}},
        "fluid": {"id": "minecraft:water", "properties": {"level": "0"}}},
    # Ground cover.
    "tundra_vegetation": {"type": "minecraft:simple_block", "to_place": {"type": "minecraft:weighted", "entries": [
        {"data": "minecraft:short_grass", "weight": 4}, {"data": "minecraft:bush", "weight": 2},
        {"data": "minecraft:moss_carpet", "weight": 3}, {"data": "minecraft:fern", "weight": 1},
        {"data": "minecraft:short_dry_grass", "weight": 1}]}},
    "heath_vegetation": {"type": "minecraft:simple_block", "to_place": {"type": "minecraft:weighted", "entries": [
        {"data": "minecraft:bush", "weight": 4}, {"data": "minecraft:red_shrub", "weight": 2},
        {"data": "minecraft:short_grass", "weight": 3}, {"data": "minecraft:short_dry_grass", "weight": 1}]}},
    "bog_vegetation": {"type": "minecraft:simple_block", "to_place": {"type": "minecraft:weighted", "entries": [
        {"data": "minecraft:moss_carpet", "weight": 5}, {"data": "minecraft:short_grass", "weight": 2},
        {"data": "minecraft:fern", "weight": 2}, {"data": "minecraft:bush", "weight": 1}]}},
    "moss_carpet": {"type": "minecraft:simple_block", "to_place": {"id": "minecraft:moss_carpet"}},
    "polar_vegetation": {"type": "minecraft:simple_block", "to_place": {"type": "minecraft:weighted", "entries": [
        {"data": "minecraft:short_dry_grass", "weight": 2}, {"data": "minecraft:dead_bush", "weight": 1}]}},
    "scrub_vegetation": {"type": "minecraft:simple_block", "to_place": {"type": "minecraft:weighted", "entries": [
        {"data": "minecraft:short_dry_grass", "weight": 3}, {"data": "minecraft:bush", "weight": 3},
        {"data": "minecraft:short_grass", "weight": 2}, {"data": "minecraft:tall_dry_grass", "weight": 1}]}},
}

# Tree mixes: random selectors over placed trees (vanilla's "*_checked" ones test the soil).
SELECTORS = {
    "trees_treeline": ("terra:krummholz_checked", [("minecraft:spruce_checked", 0.15), ("minecraft:pine_checked", 0.08)]),
    "trees_boreal": ("minecraft:spruce_checked", [("minecraft:pine_checked", 0.3), ("minecraft:birch_checked", 0.12),
                                                 ("minecraft:fallen_spruce_tree", 0.0125)]),
    "trees_siberian": ("minecraft:pine_checked", [("minecraft:mega_pine_checked", 0.08), ("minecraft:spruce_checked", 0.25),
                                                 ("minecraft:birch_checked", 0.05), ("minecraft:fallen_spruce_tree", 0.01)]),
    "trees_birch_taiga": ("minecraft:spruce_checked", [("minecraft:birch_checked", 0.5), ("minecraft:pine_checked", 0.15),
                                                      ("minecraft:fallen_birch_tree", 0.01)]),
    "trees_taiga_mountain": ("minecraft:spruce_checked", [("minecraft:pine_checked", 0.4),
                                                         ("terra:krummholz_checked", 0.1)]),
    "trees_bog": ("terra:stunted_spruce_checked", [("minecraft:birch_checked", 0.05)]),
    "trees_rainforest": ("minecraft:spruce_checked", [("minecraft:mega_spruce_checked", 0.35),
                                                     ("minecraft:fancy_oak_checked", 0.15),
                                                     ("minecraft:fallen_spruce_tree", 0.02), ("minecraft:fallen_oak_tree", 0.01)]),
    "trees_redwoods": ("terra:redwood_checked", [("minecraft:mega_spruce_checked", 0.2), ("minecraft:spruce_checked", 0.15),
                                                ("minecraft:fallen_spruce_tree", 0.01)]),
    "trees_aspen": ("minecraft:yellow_poplar", [("minecraft:birch_checked", 0.3)]),
    "trees_mediterranean": ("minecraft:oak_checked", [("terra:oak_bush_checked", 0.45), ("minecraft:fancy_oak_checked", 0.08)]),
    "trees_highland": ("minecraft:spruce_checked", [("minecraft:oak_checked", 0.3), ("minecraft:birch_checked", 0.2),
                                                   ("minecraft:fancy_oak_checked", 0.05), ("minecraft:fallen_oak_tree", 0.01)]),
    "trees_cloud_forest": ("minecraft:jungle_tree", [("minecraft:jungle_bush", 0.35), ("minecraft:oak_checked", 0.15)]),
    "trees_tropical_dry": ("minecraft:acacia_checked", [("minecraft:jungle_bush", 0.35), ("minecraft:jungle_tree", 0.1)]),
    "trees_floodplain": ("minecraft:oak_checked", [("terra:swamp_oak_checked", 0.25), ("minecraft:fancy_oak_checked", 0.2),
                                                  ("minecraft:birch_checked", 0.15), ("minecraft:fallen_oak_tree", 0.02)]),
    "trees_riverside": ("minecraft:oak_checked", [("minecraft:yellow_poplar", 0.25), ("terra:oak_bush_checked", 0.25),
                                                 ("minecraft:fancy_oak_checked", 0.1)]),
}

# Placed features (data/terra/worldgen/placed_feature).
def trees(feature, counts, water_depth=0, sapling=None):
    """counts: an int, or [(count, weight), ...]; sapling: also test that this sapling would survive (for a single
    configured tree rather than a selector of checked trees)."""
    count = counts if isinstance(counts, int) else {"type": "minecraft:weighted_list", "distribution": [
        {"data": c, "weight": w} for c, w in counts]}
    return {"feature": feature, "placement": [
        {"type": "minecraft:count", "count": count}, {"type": "minecraft:in_square"},
        {"type": "minecraft:surface_water_depth_filter", "max_water_depth": water_depth},
        {"type": "minecraft:heightmap", "heightmap": "OCEAN_FLOOR"}, {"type": "minecraft:biome"}]
        + ([survive(sapling)] if sapling else [])}


def grove_trees(feature, above, below=0):
    """Trees in groves: vanilla's biome-info noise (~200-block blobs) decides where a chunk gets them."""
    return {"feature": feature, "placement": [
        {"type": "minecraft:noise_threshold_count", "above_noise": above, "below_noise": below, "noise_level": 0.0},
        {"type": "minecraft:in_square"}, {"type": "minecraft:surface_water_depth_filter", "max_water_depth": 0},
        {"type": "minecraft:heightmap", "heightmap": "OCEAN_FLOOR"}, {"type": "minecraft:biome"}]}


def patch(feature, count, spread=7, chance=None, heightmap="MOTION_BLOCKING"):
    p = []
    if chance:
        p.append({"type": "minecraft:rarity_filter", "chance": chance})
    p += [{"type": "minecraft:in_square"}, {"type": "minecraft:heightmap", "heightmap": heightmap},
          {"type": "minecraft:biome"}, {"type": "minecraft:count", "count": count},
          {"type": "minecraft:offset", "x": tri(spread), "y": tri(3), "z": tri(spread)}, AIR_FILTER]
    return {"feature": feature, "placement": p}


PLACED = {
    "krummholz_checked": {"feature": "terra:krummholz", "placement": [survive("minecraft:spruce_sapling")]},
    "stunted_spruce_checked": {"feature": "terra:stunted_spruce", "placement": [survive("minecraft:spruce_sapling")]},
    "oak_bush_checked": {"feature": "terra:oak_bush", "placement": [survive("minecraft:oak_sapling")]},
    "redwood_checked": {"feature": "terra:redwood", "placement": [survive("minecraft:spruce_sapling")]},
    "swamp_oak_checked": {"feature": "minecraft:swamp_oak", "placement": [survive("minecraft:oak_sapling")]},
    "trees_treeline": trees("terra:trees_treeline", [(1, 3), (2, 2), (0, 2)]),
    "trees_tundra": trees("terra:krummholz", [(0, 12), (1, 1)], sapling="minecraft:spruce_sapling"),
    "trees_boreal": trees("terra:trees_boreal", [(10, 9), (11, 1)]),
    "trees_siberian": trees("terra:trees_siberian", [(5, 9), (6, 1)]),
    "trees_birch_taiga": trees("terra:trees_birch_taiga", [(8, 9), (9, 1)]),
    "trees_taiga_mountain": trees("terra:trees_taiga_mountain", [(3, 9), (4, 1)]),
    "trees_taiga_clearing": trees("terra:trees_boreal", [(0, 3), (1, 1)]),
    "trees_bog": trees("terra:trees_bog", [(1, 3), (2, 1), (0, 1)], water_depth=1),
    "trees_rainforest": trees("terra:trees_rainforest", [(12, 9), (14, 1)]),
    "trees_redwoods": trees("terra:trees_redwoods", [(6, 9), (7, 1)]),
    "trees_aspen": grove_trees("terra:trees_aspen", above=7, below=0),
    "trees_prairie": trees("minecraft:oak", [(0, 39), (1, 1)], sapling="minecraft:oak_sapling"),
    "trees_moorland": trees("terra:oak_bush", [(0, 9), (1, 1)], sapling="minecraft:oak_sapling"),
    "trees_mediterranean": trees("terra:trees_mediterranean", [(2, 3), (3, 1), (1, 1)]),
    "trees_forested_highland": trees("terra:trees_highland", [(8, 9), (9, 1)]),
    "trees_temperate_highland": trees("terra:trees_highland", [(0, 2), (1, 2), (2, 1)]),
    "trees_cloud_forest": trees("terra:trees_cloud_forest", [(10, 9), (12, 1)]),
    "trees_tropical_dry": trees("terra:trees_tropical_dry", [(3, 3), (4, 1), (2, 1)]),
    "trees_floodplain": trees("terra:trees_floodplain", [(9, 9), (10, 1)], water_depth=1),
    "trees_riverside": trees("terra:trees_riverside", [(6, 9), (7, 1)]),
    "trees_marsh": trees("minecraft:swamp_oak", [(0, 15), (1, 1)], water_depth=1, sapling="minecraft:oak_sapling"),
    "patch_tundra": patch("terra:tundra_vegetation", 48),
    "patch_heath": patch("terra:heath_vegetation", 48),
    "patch_bog": patch("terra:bog_vegetation", 64),
    "patch_moss_carpet": patch("terra:moss_carpet", 32, chance=2),
    "patch_polar": patch("terra:polar_vegetation", 6, chance=4),
    "patch_scrub": patch("terra:scrub_vegetation", 48),
    "wetland_pool": {"feature": "terra:wetland_pool", "placement": [
        {"type": "minecraft:rarity_filter", "chance": 2}, {"type": "minecraft:in_square"},
        {"type": "minecraft:heightmap", "heightmap": "WORLD_SURFACE_WG"}, {"type": "minecraft:biome"}]},
    "forest_rock_sparse": {"feature": "minecraft:forest_rock", "placement": [
        {"type": "minecraft:rarity_filter", "chance": 3}, {"type": "minecraft:in_square"},
        {"type": "minecraft:heightmap", "heightmap": "MOTION_BLOCKING"}, {"type": "minecraft:biome"}]},
}

# ---------------------------------------------------------------------------------------------------------------
# Surface rules
# ---------------------------------------------------------------------------------------------------------------

def noise(n, lo, hi=None):
    return {"type": "minecraft:noise_threshold", "noise": f"minecraft:{n}",
            "min_threshold": lo, "max_threshold": 1.7976931348623157e308 if hi is None else hi}


def block(b):
    return {"type": "minecraft:block", "result_state": b}


GRAVEL = "minecraft:overworld/gravel_or_stone_if_ceiling"


def rule(*pairs):
    """(condition or None, result) pairs → a sequence; a result is a block id or a named rule (contains '/')."""
    seq = []
    for cond, res in pairs:
        r = res if "/" in res else block(res)
        seq.append(r if cond is None else {"type": "minecraft:condition", "if_true": cond, "then_run": r})
    return {"type": "minecraft:sequence", "sequence": seq}


# ---------------------------------------------------------------------------------------------------------------
# The biomes
# ---------------------------------------------------------------------------------------------------------------
# id: Terra's biome id (BiomeClassifier), base: vanilla biome to start from, temp/downfall: vanilla climate values
# (snow falls below 0.15), precip: weather on/off, colors: effects, veg: the vegetation step, lakes: extra lake-step
# features, top/under: surface rules (top above water unless top_wet), tags: extra tags, extra_steps: other steps.

B = {}


def biome(name, **kw):
    B[name] = kw


COLD_WATER = "#3d57d6"

biome("tundra", id=50, base="snowy_plains", temp=0.3, downfall=0.5,
      colors={"grass_color": "#93a35e", "foliage_color": "#7f9a50", "dry_foliage_color": "#8a6b45"},
      veg=["terra:trees_tundra", "terra:patch_tundra", "terra:patch_moss_carpet", "minecraft:patch_berry_rare",
           "minecraft:flower_default", "terra:forest_rock_sparse"],
      top=rule((noise("surface", 0.42), "minecraft:snow_block"), (noise("surface", 0.18), "minecraft:moss_block"),
               (noise("surface_secondary", 0.35), "minecraft:podzol"), (noise("surface", -1e9, -0.3), "minecraft:coarse_dirt")),
      tags=["has_structure/village_snowy", "has_structure/igloo"])
biome("polar_desert", id=51, base="snowy_plains", temp=-0.7, downfall=0.0, precip=False, freeze=False,
      colors={"grass_color": "#8c9c84", "foliage_color": "#7d8c76", "water_color": COLD_WATER},
      veg=["terra:patch_polar"],
      top=rule((noise("surface_secondary", 0.55), "minecraft:snow_block"), (noise("surface", 0.25), "minecraft:stone"),
               (noise("surface", -0.35), GRAVEL), (None, "minecraft:coarse_dirt")),
      under=rule((noise("surface", 0.25), "minecraft:stone"), (None, GRAVEL)), top_wet=True)
biome("treeline", id=52, base="grove", temp=0.2, downfall=0.6,
      colors={"grass_color": "#80a462", "foliage_color": "#5f8a4c"},
      veg=["terra:trees_treeline", "minecraft:patch_grass_meadow", "minecraft:flower_meadow", "terra:patch_heath",
           "terra:forest_rock_sparse", "minecraft:patch_berry_rare"],
      top=rule((noise("surface", 0.3), "minecraft:stone"), (noise("surface", 0.05), "minecraft:coarse_dirt")),
      under=rule((noise("surface", 0.3), "minecraft:stone")),
      tags=["is_forest"])
biome("boreal_forest", id=53, base="taiga", temp=0.2, downfall=0.8,
      colors={"grass_color": "#6c9a5c", "foliage_color": "#4f7e47"},
      veg=["minecraft:patch_large_fern", "terra:trees_boreal", "minecraft:patch_grass_taiga", "terra:patch_moss_carpet",
           "minecraft:brown_mushroom_taiga", "minecraft:red_mushroom_taiga", "minecraft:patch_berry_common",
           "minecraft:patch_firefly_bush_near_water"],
      top=rule((noise("surface", 0.0), "minecraft:podzol"), (noise("surface_secondary", 0.5), "minecraft:moss_block")))
biome("siberian_taiga", id=54, base="taiga", temp=0.15, downfall=0.4,
      colors={"grass_color": "#88a060", "foliage_color": "#5d8048"},
      veg=["terra:trees_siberian", "minecraft:patch_grass_taiga_2", "minecraft:patch_large_fern",
           "minecraft:brown_mushroom_taiga", "minecraft:patch_berry_common"],
      top=rule((noise("surface", 0.21), "minecraft:coarse_dirt"), (noise("surface", -0.12), "minecraft:podzol")))
biome("birch_taiga", id=55, base="taiga", temp=0.3, downfall=0.8,
      colors={"grass_color": "#79a85f", "foliage_color": "#62994c"},
      veg=["minecraft:patch_large_fern", "terra:trees_birch_taiga", "minecraft:patch_grass_taiga_2",
           "minecraft:flower_default", "minecraft:brown_mushroom_taiga", "minecraft:patch_berry_common",
           "minecraft:patch_leaf_litter", "minecraft:patch_firefly_bush_near_water"],
      top=rule((noise("surface", 0.3), "minecraft:podzol")))
biome("taiga_mountain", id=56, base="taiga", temp=0.15, downfall=0.6,
      colors={"grass_color": "#7a9a62", "foliage_color": "#55804a"},
      veg=["terra:trees_taiga_mountain", "minecraft:patch_grass_taiga_2", "minecraft:patch_large_fern",
           "terra:forest_rock_sparse", "minecraft:patch_berry_rare"],
      top=rule((noise("surface", 0.28), "minecraft:stone"), (noise("surface", 0.05), "minecraft:coarse_dirt"),
               (noise("surface", -0.15), "minecraft:podzol")),
      under=rule((noise("surface", 0.28), "minecraft:stone")))
biome("taiga_clearing", id=57, base="taiga", temp=0.3, downfall=0.8,
      colors={"grass_color": "#82b061", "foliage_color": "#5c8f4b"},
      veg=["terra:trees_taiga_clearing", "minecraft:patch_tall_grass_2", "minecraft:patch_grass_meadow",
           "minecraft:flower_meadow", "minecraft:patch_large_fern", "minecraft:patch_berry_common",
           "minecraft:fallen_spruce_tree", "minecraft:patch_firefly_bush_near_water"],
      top=None)
biome("bog", id=58, base="swamp", temp=0.25, downfall=0.9,
      colors={"grass_color": "#7f8f4d", "foliage_color": "#5f7a3c", "water_color": "#4a5640"},
      lakes=["terra:wetland_pool"],
      veg=["terra:trees_bog", "terra:patch_bog", "minecraft:patch_waterlily", "minecraft:brown_mushroom_swamp",
           "minecraft:patch_berry_rare", "minecraft:patch_firefly_bush_swamp"],
      top=rule((noise("surface", 0.15), "minecraft:moss_block"), (noise("surface", -0.25), "minecraft:podzol"),
               (None, "minecraft:mud")),
      tags=[])
biome("moorland", id=59, base="windswept_hills", temp=0.3, downfall=0.8,
      colors={"grass_color": "#8f9a55", "foliage_color": "#6f7f45", "dry_foliage_color": "#8b5a3a"},
      veg=["terra:trees_moorland", "terra:patch_heath", "minecraft:patch_red_shrub", "minecraft:patch_grass_plain",
           "terra:forest_rock_sparse"],
      top=rule((noise("surface", 0.3), "minecraft:coarse_dirt"), (noise("surface_secondary", 0.25), "minecraft:podzol")),
      tags=["has_structure/village_plains"])
biome("temperate_rainforest", id=60, base="old_growth_spruce_taiga", temp=0.4, downfall=1.0,
      colors={"grass_color": "#4e9a3a", "foliage_color": "#3f8a2f"},
      veg=["minecraft:patch_large_fern", "terra:trees_rainforest", "minecraft:patch_grass_taiga",
           "terra:patch_moss_carpet", "minecraft:brown_mushroom_old_growth", "minecraft:red_mushroom_old_growth",
           "minecraft:patch_berry_common", "minecraft:patch_firefly_bush_near_water"],
      top=rule((noise("surface", 0.1), "minecraft:podzol"), (noise("surface", -1e9, -0.3), "minecraft:moss_block")))
biome("aspen_parkland", id=61, base="plains", temp=0.4, downfall=0.5,
      colors={"grass_color": "#9cb85a", "foliage_color": "#b9b241"},
      veg=["terra:trees_aspen", "minecraft:patch_tall_grass_2", "minecraft:patch_grass_plain", "minecraft:flower_plains",
           "minecraft:patch_bush", "minecraft:patch_leaf_litter", "minecraft:patch_firefly_bush_near_water"],
      top=None, tags=["is_forest"])
biome("tallgrass_prairie", id=62, base="plains", temp=0.8, downfall=0.6,
      colors={"grass_color": "#a2bb52", "foliage_color": "#86a53e"},
      veg=["minecraft:patch_tall_grass", "minecraft:patch_tall_grass_2", "terra:trees_prairie",
           "minecraft:flower_plains", "minecraft:wildflowers_meadow", "minecraft:patch_grass_plain",
           "minecraft:patch_sunflower", "minecraft:patch_firefly_bush_near_water"],
      top=None)
biome("steppe", id=63, base="plains", temp=0.7, downfall=0.15,
      colors={"grass_color": "#b5b067", "foliage_color": "#9c9a58", "dry_foliage_color": "#a78a52"},
      veg=["minecraft:patch_dry_grass_desert", "minecraft:patch_grass_badlands", "minecraft:patch_bush",
           "minecraft:patch_dead_bush"],
      top=rule((noise("surface", 0.2), "minecraft:coarse_dirt")))
biome("marsh", id=64, base="swamp", temp=0.7, downfall=0.9,
      colors={"grass_color": "#6c9a40", "foliage_color": "#5e8a38", "water_color": "#4f7a62"},
      lakes=["terra:wetland_pool"],
      veg=["terra:trees_marsh", "minecraft:patch_tall_grass", "minecraft:patch_grass_normal",
           "minecraft:patch_sugar_cane_swamp", "minecraft:patch_waterlily", "minecraft:flower_swamp",
           "minecraft:patch_firefly_bush_swamp", "minecraft:seagrass_swamp"],
      top=rule((noise("surface_swamp", 0.25), "minecraft:mud")))
biome("riverside_forest", id=65, base="forest", temp=0.8, downfall=0.6,
      colors={"grass_color": "#79ad4a", "foliage_color": "#5c9a3a"},
      veg=["terra:trees_riverside", "minecraft:patch_tall_grass_2", "minecraft:patch_grass_forest",
           "minecraft:patch_sugar_cane", "minecraft:patch_bush", "minecraft:patch_firefly_bush_near_water"],
      top=None)
biome("floodplain_forest", id=66, base="forest", temp=0.7, downfall=0.9,
      colors={"grass_color": "#5f9a3c", "foliage_color": "#4d8a30", "water_color": "#3a6f8a"},
      veg=["terra:trees_floodplain", "minecraft:patch_grass_forest", "minecraft:patch_large_fern",
           "minecraft:patch_sugar_cane", "terra:patch_moss_carpet", "minecraft:brown_mushroom_swamp",
           "minecraft:patch_leaf_litter", "minecraft:patch_firefly_bush_near_water"],
      top=rule((noise("surface_swamp", 0.3), "minecraft:mud")))
biome("redwoods", id=67, base="old_growth_spruce_taiga", temp=0.5, downfall=0.9,
      colors={"grass_color": "#5a9a45", "foliage_color": "#3f7f38"},
      veg=["minecraft:patch_large_fern", "terra:trees_redwoods", "minecraft:patch_grass_taiga",
           "terra:patch_moss_carpet", "minecraft:brown_mushroom_old_growth", "minecraft:patch_berry_common"],
      top=rule((noise("surface", 0.45), "minecraft:coarse_dirt"), (noise("surface", -0.4), "minecraft:podzol")))
biome("mediterranean_scrub", id=68, base="plains", temp=1.2, downfall=0.25,
      colors={"grass_color": "#a6a85a", "foliage_color": "#6e8a3e", "dry_foliage_color": "#9a7b4a"},
      veg=["terra:trees_mediterranean", "terra:patch_scrub", "minecraft:flower_warm", "minecraft:patch_dry_grass_desert"],
      top=rule((noise("surface", 0.05), "minecraft:coarse_dirt"), (noise("surface_secondary", 0.55), "minecraft:gravel")))
biome("forested_highland", id=69, base="taiga", temp=0.4, downfall=0.7,
      colors={"grass_color": "#72a35a", "foliage_color": "#55904a"},
      veg=["terra:trees_forested_highland", "minecraft:patch_grass_forest", "minecraft:patch_large_fern",
           "minecraft:flower_default", "terra:forest_rock_sparse", "minecraft:patch_berry_common"],
      top=rule((noise("surface", 0.45), "minecraft:stone"), (noise("surface", 0.25), "minecraft:coarse_dirt")),
      under=rule((noise("surface", 0.45), "minecraft:stone")), tags=["is_forest"])
biome("temperate_highland", id=70, base="meadow", temp=0.5, downfall=0.7,
      colors={"grass_color": "#86b25e", "foliage_color": "#62994c"},
      veg=["terra:trees_temperate_highland", "minecraft:patch_tall_grass_2", "minecraft:patch_grass_meadow",
           "minecraft:flower_meadow", "minecraft:wildflowers_meadow", "terra:forest_rock_sparse"],
      top=rule((noise("surface", 0.45), "minecraft:stone")), under=rule((noise("surface", 0.45), "minecraft:stone")))
biome("rocky_desert", id=71, base="desert", temp=2.0, downfall=0.0,
      colors={},
      veg=["minecraft:patch_dead_bush_2", "minecraft:patch_dry_grass_desert", "minecraft:patch_cactus_desert"],
      top=rule((noise("surface", 0.2), "minecraft:stone"), (noise("surface", -0.15), GRAVEL),
               (noise("surface_secondary", 0.3), "minecraft:coarse_dirt"), (None, "minecraft:sand")),
      under=rule((noise("surface", 0.2), "minecraft:stone"), (noise("surface", -0.15), GRAVEL),
                 (None, "minecraft:sandstone")), top_wet=True)
biome("cloud_forest", id=72, base="jungle", temp=0.7, downfall=1.0,
      colors={"grass_color": "#4fa83e", "foliage_color": "#3c9a30"},
      veg=["terra:trees_cloud_forest", "minecraft:patch_large_fern", "minecraft:patch_grass_jungle",
           "terra:patch_moss_carpet", "minecraft:vines", "minecraft:brown_mushroom_normal"],
      top=rule((noise("surface", 0.15), "minecraft:moss_block")))
biome("tropical_dry_forest", id=73, base="savanna", temp=1.5, downfall=0.3,
      colors={"grass_color": "#a6a84a", "foliage_color": "#8c9a3a", "dry_foliage_color": "#9c7a42"},
      veg=["terra:trees_tropical_dry", "minecraft:patch_dry_grass_desert", "minecraft:patch_grass_savanna",
           "minecraft:patch_bush", "minecraft:flower_warm", "minecraft:patch_melon_sparse"],
      top=rule((noise("surface", 0.3), "minecraft:coarse_dirt")))

V12 = [f"terra:{n}" for n in B]


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def load(path):
    with open(path) as f:
        return json.load(f)


def main():
    for name, f in FEATURES.items():
        write(f"{TERRA}/feature/{name}.json", f)
    for name, (default, options) in SELECTORS.items():
        write(f"{TERRA}/feature/{name}.json", {"type": "minecraft:random_selector", "default": default,
                                              "features": [{"chance": c, "feature": f} for f, c in options]})
    for name, p in PLACED.items():
        write(f"{TERRA}/placed_feature/{name}.json", p)
    check_references()

    # Biome files.
    generated = {}
    for name, d in B.items():
        base = load(f"{VANILLA}/worldgen/biome/{d['base']}.json")
        out = dict(base)
        out["temperature"] = d["temp"]
        out["downfall"] = d["downfall"]
        out["has_precipitation"] = d.get("precip", True)
        eff = dict(base.get("effects", {}))
        for k in ("grass_color", "foliage_color", "dry_foliage_color", "grass_color_modifier"):
            eff.pop(k, None)
        eff.setdefault("water_color", "#3f76e4")
        eff.update(d["colors"])
        out["effects"] = eff
        steps = [list(s) for s in base["features"]]
        steps[1] = steps[1] + d.get("lakes", [])
        steps[9] = ["minecraft:glow_lichen"] + d["veg"]
        if not d.get("freeze", True):
            steps[10] = [f for f in steps[10] if f != "minecraft:freeze_top_layer"]
        out["features"] = steps
        generated[name] = out
    # Minecraft runs each step's features in one global order across every biome of the world (FeatureSorter), and
    # refuses to generate if two biomes disagree. Sort each generated step into an order consistent with all the
    # other biomes Terra uses, then check the whole set the way the game does.
    fixed = fixed_biomes()
    for name, out in generated.items():
        out["features"] = [sort_step(k, out["features"][k], fixed) for k in range(len(out["features"]))]
        write(f"{TERRA}/biome/{name}.json", out)
    check_feature_order(list(fixed.values()) + [o["features"] for o in generated.values()])

    # Tags: the base biome's direct memberships, plus extras.
    members = {}
    tag_root = f"{VANILLA}/tags/worldgen/biome"
    for root, _, files in os.walk(tag_root):
        for fn in files:
            rel = os.path.relpath(os.path.join(root, fn), tag_root)[:-5]
            vals = load(os.path.join(root, fn)).get("values", [])
            for name, d in B.items():
                if f"minecraft:{d['base']}" in vals:
                    members.setdefault(rel, set()).add(f"terra:{name}")
    for name, d in B.items():
        for t in ["is_overworld"] + d.get("tags", []):
            members.setdefault(t, set()).add(f"terra:{name}")
    # Rewrite every tag file this script owns or adds to, keeping other entries (e.g. terra:dark_forest).
    for root, _, files in os.walk(MC_TAGS):
        for fn in files:
            rel = os.path.relpath(os.path.join(root, fn), MC_TAGS)[:-5]
            members.setdefault(rel, set())
    for rel, vals in sorted(members.items()):
        path = f"{MC_TAGS}/{rel}.json"
        keep = []
        if os.path.exists(path):
            keep = [v for v in load(path).get("values", []) if v not in V12]
        allv = keep + sorted(vals)
        if not allv:
            os.remove(path)
            continue
        write(path, {"replace": False, "values": allv})

    # Surface rules: replace this script's entries in Terra's material rule.
    mr = load(MATERIAL_RULE)
    top_seq, under_seq = find_surface_sequences(mr)
    strip(top_seq)
    strip(under_seq)
    top_rules, under_rules = [], []
    for name, d in B.items():
        bid = f"terra:{name}"
        if d.get("top"):
            body = d["top"]
            if not d.get("top_wet"):
                body = {"type": "minecraft:condition", "if_true": {
                    "type": "minecraft:y_above", "anchor": {"absolute": 62}, "surface_depth_multiplier": 0,
                    "add_stone_depth": False}, "then_run": body}
            top_rules.append(biome_rule(bid, body))
        if d.get("under"):
            under_rules.append(biome_rule(bid, d["under"]))
    # Before the final grass/dirt defaults.
    top_seq[-2:-2] = top_rules
    under_seq[-1:-1] = under_rules
    write(MATERIAL_RULE, mr)
    print(f"{len(B)} biomes, {len(FEATURES) + len(SELECTORS)} features, {len(PLACED)} placed features, "
          f"{len(members)} tag files")


# Vanilla biomes Terra's biome source uses (TerrainDiffusionBiomeSource), and Terra's own non-generated biomes.
VANILLA_USED = """badlands bamboo_jungle beach birch_forest cherry_grove cold_ocean dappled_forest deep_cold_ocean
deep_frozen_ocean deep_lukewarm_ocean deep_ocean desert flower_forest forest frozen_ocean frozen_peaks frozen_river
grove jagged_peaks jungle lukewarm_ocean mangrove_swamp meadow ocean old_growth_birch_forest old_growth_pine_taiga
old_growth_spruce_taiga pale_garden plains river savanna savanna_plateau snowy_beach snowy_plains snowy_slopes
snowy_taiga sparse_jungle stony_peaks stony_shore sunflower_plains swamp taiga warm_ocean windswept_forest
windswept_gravelly_hills windswept_hills windswept_savanna""".split()
TERRA_FIXED = ["dark_forest", "forest_sparse", "taiga_sparse", "snowy_taiga_sparse"]


def fixed_biomes():
    """Feature steps of every biome in the world that this script doesn't write (overrides in the mod win)."""
    out = {}
    for b in VANILLA_USED:
        override = f"{RES}/minecraft/worldgen/biome/{b}.json"
        out[b] = load(override if os.path.exists(override) else f"{VANILLA}/worldgen/biome/{b}.json")["features"]
    for b in TERRA_FIXED:
        out["terra:" + b] = load(f"{TERRA}/biome/{b}.json")["features"]
    return out


def is_tree(f):
    return f.startswith("terra:trees_")


def sort_step(k, feats, fixed):
    """Order one step's features: features other biomes also use by a topological order of those biomes' lists
    (ties by first appearance); this script's trees right after glow lichen; everything else new at the end, in a
    fixed global order, so every generated biome agrees with every other."""
    order, edges, indeg = [], {}, {}
    for steps in fixed.values():
        lst = steps[k] if k < len(steps) else []
        for f in lst:
            if f not in indeg:
                indeg[f] = 0
                edges[f] = set()
                order.append(f)
        for a, b in zip(lst, lst[1:]):
            if b not in edges[a]:
                edges[a].add(b)
                indeg[b] += 1
    rank, ready = {}, [f for f in order if indeg[f] == 0]
    deg = dict(indeg)
    while ready:
        ready.sort(key=order.index)
        f = ready.pop(0)
        rank[f] = len(rank)
        for g in edges[f]:
            deg[g] -= 1
            if deg[g] == 0:
                ready.append(g)
    if len(rank) != len(order):
        raise SystemExit(f"step {k}: the fixed biomes already disagree on feature order")
    new = sorted({f for d in B.values() for f in d["veg"] + d.get("lakes", [])} - set(rank))
    lichen = rank.get("minecraft:glow_lichen", -1)

    def key(f):
        if f in rank:
            return rank[f]
        i = new.index(f)
        return lichen + 0.5 + i * 1e-4 if is_tree(f) else len(rank) + i
    return sorted(feats, key=key)


def check_feature_order(all_steps):
    """The game's test: nodes (step, feature), an edge between consecutive features of each biome; no cycles."""
    edges = {}
    for steps in all_steps:
        flat = [(k, f) for k, lst in enumerate(steps) for f in lst]
        for a, b in zip(flat, flat[1:]):
            edges.setdefault(a, set()).add(b)
            edges.setdefault(b, set())
    state = {}

    def visit(n):
        stack = [(n, iter(edges[n]))]
        state[n] = 1
        while stack:
            node, it = stack[-1]
            nxt = next(it, None)
            if nxt is None:
                state[node] = 2
                stack.pop()
            elif state.get(nxt) == 1:
                raise SystemExit(f"feature order cycle through {nxt}")
            elif nxt not in state:
                state[nxt] = 1
                stack.append((nxt, iter(edges[nxt])))
    for n in edges:
        if n not in state:
            visit(n)


def biome_rule(bid, body):
    return {"type": "minecraft:condition", "if_true": {"type": "minecraft:biome", "biome_is": [bid]}, "then_run": body}


def strip(seq):
    seq[:] = [r for r in seq if not (isinstance(r, dict) and r.get("type") == "minecraft:condition"
                                     and r["if_true"].get("type") == "minecraft:biome"
                                     and set(r["if_true"]["biome_is"]) <= set(V12))]


def find_surface_sequences(mr):
    """The two per-biome sequences in Terra's rule: under stone_depth offset 0 (top) and offset 4 (under)."""
    found = {}
    for r in mr["sequence"]:
        if isinstance(r, dict) and r.get("type") == "minecraft:condition" and r["if_true"].get("type") == "minecraft:stone_depth":
            found[r["if_true"]["offset"]] = r["then_run"]["sequence"]
    return found[0], found[4]


def check_references():
    """Every terra: and minecraft: feature this script names must exist."""
    def exists(ref, kind):
        ns, path = ref.split(":")
        if ns == "terra":
            return os.path.exists(f"{TERRA}/{kind}/{path}.json")
        return os.path.exists(f"{VANILLA}/worldgen/{kind}/{path}.json")
    missing = []
    for name, (default, options) in SELECTORS.items():
        for f in [default] + [o for o, _ in options]:
            if not exists(f, "placed_feature"):
                missing.append(f"{name}: placed {f}")
    for name, p in PLACED.items():
        if not exists(p["feature"], "feature"):
            missing.append(f"{name}: feature {p['feature']}")
    for name, d in B.items():
        for f in d["veg"] + d.get("lakes", []):
            if not exists(f, "placed_feature"):
                missing.append(f"{name}: placed {f}")
        if not os.path.exists(f"{VANILLA}/worldgen/biome/{d['base']}.json"):
            missing.append(f"{name}: base {d['base']}")
    if missing:
        raise SystemExit("missing references:\n  " + "\n  ".join(missing))


if __name__ == "__main__":
    main()
