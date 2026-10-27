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
    # Forested highland (Terralith style, Calvin's reference): conifer-shaped dark oaks whose leaves take the biome's
    # red foliage colour, among green pines (spruce leaves are never tinted), and a few orange ones (poplar leaves).
    "highland_dark_oak": {
        "type": "minecraft:tree", "below_trunk_provider": "minecraft:soil_beneath_tree", "decorators": [],
        "foliage_placer": {"type": "minecraft:spruce_foliage_placer", "offset": uniform(0, 2), "radius": uniform(2, 3),
                           "trunk_height": uniform(1, 2)},
        "foliage_provider": leaves("minecraft:dark_oak_leaves"), "ignore_vines": True,
        "minimum_size": {"type": "minecraft:two_layers_feature_size", "limit": 2, "upper_size": 2},
        "trunk_placer": {"type": "minecraft:straight_trunk_placer", "base_height": 7, "height_rand_a": 3, "height_rand_b": 1},
        "trunk_provider": log("minecraft:dark_oak_log")},
    "highland_orange_conifer": {
        "type": "minecraft:tree", "below_trunk_provider": "minecraft:soil_beneath_tree", "decorators": [],
        "foliage_placer": {"type": "minecraft:spruce_foliage_placer", "offset": uniform(0, 2), "radius": uniform(2, 3),
                           "trunk_height": uniform(1, 2)},
        "foliage_provider": leaves("minecraft:orange_poplar_leaves"), "ignore_vines": True,
        "minimum_size": {"type": "minecraft:two_layers_feature_size", "limit": 2, "upper_size": 2},
        "trunk_placer": {"type": "minecraft:straight_trunk_placer", "base_height": 6, "height_rand_a": 3, "height_rand_b": 1},
        "trunk_provider": log("minecraft:dark_oak_log")},
    # Siberian taiga (Calvin's Terralith reference): larches, tall bare dark trunks topped by tiers of needles that
    # take the biome's orange foliage colour (dark oak leaves), among tall-trunked green spruces in tiers.
    "larch": {
        "type": "minecraft:tree", "below_trunk_provider": "minecraft:soil_beneath_tree", "decorators": [],
        "foliage_placer": {"type": "minecraft:spruce_foliage_placer", "offset": uniform(0, 1), "radius": uniform(2, 3),
                           "trunk_height": uniform(7, 10)},
        "foliage_provider": leaves("minecraft:dark_oak_leaves"), "ignore_vines": True,
        "minimum_size": {"type": "minecraft:two_layers_feature_size", "limit": 2, "upper_size": 2},
        "trunk_placer": {"type": "minecraft:straight_trunk_placer", "base_height": 13, "height_rand_a": 5, "height_rand_b": 2},
        "trunk_provider": log("minecraft:dark_oak_log")},
    "tall_spruce": {
        "type": "minecraft:tree", "below_trunk_provider": "minecraft:soil_beneath_tree", "decorators": [],
        "foliage_placer": {"type": "minecraft:spruce_foliage_placer", "offset": uniform(0, 1), "radius": uniform(2, 3),
                           "trunk_height": uniform(6, 9)},
        "foliage_provider": leaves("minecraft:spruce_leaves"), "ignore_vines": True,
        "minimum_size": {"type": "minecraft:two_layers_feature_size", "limit": 2, "upper_size": 2},
        "trunk_placer": {"type": "minecraft:straight_trunk_placer", "base_height": 12, "height_rand_a": 4, "height_rand_b": 2},
        "trunk_provider": log("minecraft:spruce_log")},
    # Boreal forest (Calvin's reference): golden birches among dark spruce; birch trunks with oak leaves, which take the
    # biome's golden foliage colour (birch leaves are never tinted).
    "golden_birch": {
        "type": "minecraft:tree", "below_trunk_provider": "minecraft:soil_beneath_tree", "decorators": [],
        "foliage_placer": {"type": "minecraft:blob_foliage_placer", "height": 3, "offset": 0, "radius": 2},
        "foliage_provider": leaves("minecraft:oak_leaves"), "ignore_vines": True,
        "minimum_size": {"type": "minecraft:two_layers_feature_size"},
        "trunk_placer": {"type": "minecraft:straight_trunk_placer", "base_height": 5, "height_rand_a": 3, "height_rand_b": 0},
        "trunk_provider": log("minecraft:birch_log")},
    # 2x2 giants of the trees above (Calvin, Oct 22: about 15% of each kind in boreal forest, forested highland and
    # Siberian taiga; vanilla's giant spruce and giant pine are the other two).
    "giant_highland_dark_oak": {
        "type": "minecraft:tree", "below_trunk_provider": "minecraft:soil_beneath_tree", "decorators": [],
        "foliage_placer": {"type": "minecraft:mega_pine_foliage_placer", "crown_height": uniform(9, 13), "offset": 0, "radius": 0},
        "foliage_provider": leaves("minecraft:dark_oak_leaves"), "ignore_vines": True,
        "minimum_size": {"type": "minecraft:two_layers_feature_size", "lower_size": 1, "upper_size": 2},
        "trunk_placer": {"type": "minecraft:giant_trunk_placer", "base_height": 12, "height_rand_a": 2, "height_rand_b": 6},
        "trunk_provider": log("minecraft:dark_oak_log")},
    "giant_highland_orange_conifer": {
        "type": "minecraft:tree", "below_trunk_provider": "minecraft:soil_beneath_tree", "decorators": [],
        "foliage_placer": {"type": "minecraft:mega_pine_foliage_placer", "crown_height": uniform(8, 12), "offset": 0, "radius": 0},
        "foliage_provider": leaves("minecraft:orange_poplar_leaves"), "ignore_vines": True,
        "minimum_size": {"type": "minecraft:two_layers_feature_size", "lower_size": 1, "upper_size": 2},
        "trunk_placer": {"type": "minecraft:giant_trunk_placer", "base_height": 11, "height_rand_a": 2, "height_rand_b": 5},
        "trunk_provider": log("minecraft:dark_oak_log")},
    "giant_larch": {
        "type": "minecraft:tree", "below_trunk_provider": "minecraft:soil_beneath_tree", "decorators": [],
        "foliage_placer": {"type": "minecraft:mega_pine_foliage_placer", "crown_height": uniform(7, 10), "offset": 0, "radius": 0},
        "foliage_provider": leaves("minecraft:dark_oak_leaves"), "ignore_vines": True,
        "minimum_size": {"type": "minecraft:two_layers_feature_size", "lower_size": 1, "upper_size": 2},
        "trunk_placer": {"type": "minecraft:giant_trunk_placer", "base_height": 18, "height_rand_a": 3, "height_rand_b": 6},
        "trunk_provider": log("minecraft:dark_oak_log")},
    "giant_tall_spruce": {
        "type": "minecraft:tree", "below_trunk_provider": "minecraft:soil_beneath_tree", "decorators": [],
        "foliage_placer": {"type": "minecraft:mega_pine_foliage_placer", "crown_height": uniform(8, 11), "offset": 0, "radius": 0},
        "foliage_provider": leaves("minecraft:spruce_leaves"), "ignore_vines": True,
        "minimum_size": {"type": "minecraft:two_layers_feature_size", "lower_size": 1, "upper_size": 2},
        "trunk_placer": {"type": "minecraft:giant_trunk_placer", "base_height": 16, "height_rand_a": 3, "height_rand_b": 6},
        "trunk_provider": log("minecraft:spruce_log")},
    "giant_golden_birch": {
        "type": "minecraft:tree", "below_trunk_provider": "minecraft:soil_beneath_tree", "decorators": [],
        "foliage_placer": {"type": "minecraft:blob_foliage_placer", "height": 4, "offset": 0, "radius": 3},
        "foliage_provider": leaves("minecraft:oak_leaves"), "ignore_vines": True,
        "minimum_size": {"type": "minecraft:two_layers_feature_size", "lower_size": 1, "upper_size": 2},
        "trunk_placer": {"type": "minecraft:giant_trunk_placer", "base_height": 7, "height_rand_a": 2, "height_rand_b": 2},
        "trunk_provider": log("minecraft:birch_log")},
    # Desert oasis pond: the lake feature with water, lined with sand.
    "oasis_pool": {
        "type": "minecraft:lake", "barrier": {"id": "minecraft:sand"}, "can_place_feature": {"type": "minecraft:true"},
        "can_replace_with_air_or_fluid": {"type": "minecraft:not", "predicate": {
            "type": "minecraft:matching_block_tag", "tag": "minecraft:features_cannot_replace"}},
        "can_replace_with_barrier": {"type": "minecraft:not", "predicate": {
            "type": "minecraft:matching_block_tag", "tag": "minecraft:lava_pool_stone_cannot_replace"}},
        "fluid": {"id": "minecraft:water", "properties": {"level": "0"}}},
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

def aspen(leaf_block):
    """Aspen (Calvin, Oct 22): vanilla 26.3's poplar shape (trunk with short branches, tall narrow crown, shelf
    mushrooms) on a birch trunk, with the given leaves."""
    with open(f"{VANILLA}/worldgen/feature/yellow_poplar.json") as f:
        t = json.load(f)
    t["trunk_provider"] = log("minecraft:birch_log")
    t["foliage_provider"] = leaves(leaf_block)
    return t


for _colour, _leaf in [("yellow", "minecraft:yellow_poplar_leaves"), ("orange", "minecraft:orange_poplar_leaves"),
                       ("red", "minecraft:red_poplar_leaves"), ("green", "minecraft:birch_leaves")]:
    FEATURES[f"aspen_{_colour}"] = aspen(_leaf)


def mix(default, shares):
    """A random selector whose options end up with these overall shares (the default takes the rest). The selector
    tries its options in order, each with its own chance, so each chance is the share over what is left."""
    out, left = [], 1.0
    for f, share in shares:
        out.append((f, round(share / left, 5)))
        left -= share
    assert left > 0
    return default, out


GIANT = 0.15   # share of each tree kind grown as a 2x2 giant in the giant-tree forests


def with_giants(default, default_share, kinds):
    """kinds: [(tree, giant tree, share of all trees)]; the default kind (default_share) and each kind give GIANT of
    their share to their giant."""
    shares = []
    for tree, giant, share in kinds:
        shares += [(tree, share * (1 - GIANT)), (giant, share * GIANT)]
    return shares


# Tree mixes: random selectors over placed trees (vanilla's "*_checked" ones test the soil).
SELECTORS = {
    "trees_treeline": ("terra:krummholz_checked", [("minecraft:spruce_checked", 0.15), ("minecraft:pine_checked", 0.08)]),
    # Spruce 60%, golden birch 25%, pine 15%; each 15% giant.
    "trees_boreal": mix("minecraft:spruce_checked", [("minecraft:mega_spruce_checked", 0.6 * GIANT)] + with_giants(None, 0, [
        ("terra:golden_birch_checked", "terra:giant_golden_birch_checked", 0.25),
        ("minecraft:pine_checked", "minecraft:mega_pine_checked", 0.15)]) + [("minecraft:fallen_spruce_tree", 0.0125)]),
    # Larch 50%, tall spruce 40%, pine 10%; each 15% giant.
    "trees_siberian": mix("terra:larch_checked", [("terra:giant_larch_checked", 0.5 * GIANT)] + with_giants(None, 0, [
        ("terra:tall_spruce_checked", "terra:giant_tall_spruce_checked", 0.4),
        ("minecraft:pine_checked", "minecraft:mega_pine_checked", 0.1)]) + [("minecraft:fallen_spruce_tree", 0.01)]),
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
    # Aspens in several colours: mostly yellow, some orange, still-green (birch leaves) and a few red.
    "trees_aspen": mix("terra:aspen_yellow_checked", [("terra:aspen_orange_checked", 0.22),
                                                      ("terra:aspen_green_checked", 0.2), ("terra:aspen_red_checked", 0.1)]),
    "trees_mediterranean": ("minecraft:oak_checked", [("terra:oak_bush_checked", 0.45), ("minecraft:fancy_oak_checked", 0.08)]),
    # Red dark oak 42%, pine 40%, orange 10%, spruce 8%; each 15% giant.
    "trees_forested_highland": mix("terra:highland_dark_oak_checked", [
        ("terra:giant_highland_dark_oak_checked", 0.42 * GIANT)] + with_giants(None, 0, [
            ("minecraft:pine_checked", "minecraft:mega_pine_checked", 0.4),
            ("terra:highland_orange_conifer_checked", "terra:giant_highland_orange_conifer_checked", 0.1),
            ("minecraft:spruce_checked", "minecraft:mega_spruce_checked", 0.08)]) + [("minecraft:fallen_spruce_tree", 0.01)]),
    # Oasis palms: small jungle trees and jungle bushes.
    "trees_oasis": mix("minecraft:jungle_tree", [("minecraft:jungle_bush", 0.4)]),
    # Mirage isles: tall teal-leaved jungle and fancy oaks (both leaves take the biome's foliage colour), giant red and
    # brown mushrooms between them.
    "trees_mirage": mix("minecraft:jungle_tree", [("minecraft:fancy_oak_checked", 0.3), ("terra:huge_red_mushroom", 0.12),
                                                 ("terra:huge_brown_mushroom", 0.06), ("minecraft:mega_jungle_tree_checked", 0.08)]),
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
    "larch_checked": {"feature": "terra:larch", "placement": [survive("minecraft:spruce_sapling")]},
    **{f"aspen_{c}_checked": {"feature": f"terra:aspen_{c}", "placement": [survive("minecraft:birch_sapling")]}
       for c in ("yellow", "orange", "red", "green")},
    "giant_larch_checked": {"feature": "terra:giant_larch", "placement": [survive("minecraft:spruce_sapling")]},
    "giant_tall_spruce_checked": {"feature": "terra:giant_tall_spruce", "placement": [survive("minecraft:spruce_sapling")]},
    "giant_golden_birch_checked": {"feature": "terra:giant_golden_birch", "placement": [survive("minecraft:birch_sapling")]},
    "giant_highland_dark_oak_checked": {"feature": "terra:giant_highland_dark_oak",
                                        "placement": [survive("minecraft:dark_oak_sapling")]},
    "giant_highland_orange_conifer_checked": {"feature": "terra:giant_highland_orange_conifer",
                                              "placement": [survive("minecraft:dark_oak_sapling")]},
    "tall_spruce_checked": {"feature": "terra:tall_spruce", "placement": [survive("minecraft:spruce_sapling")]},
    "golden_birch_checked": {"feature": "terra:golden_birch", "placement": [survive("minecraft:birch_sapling")]},
    "highland_dark_oak_checked": {"feature": "terra:highland_dark_oak", "placement": [survive("minecraft:dark_oak_sapling")]},
    "highland_orange_conifer_checked": {"feature": "terra:highland_orange_conifer",
                                        "placement": [survive("minecraft:dark_oak_sapling")]},
    "swamp_oak_checked": {"feature": "minecraft:swamp_oak", "placement": [survive("minecraft:oak_sapling")]},
    "trees_treeline": trees("terra:trees_treeline", [(1, 3), (2, 2), (0, 2)]),
    "trees_tundra": trees("terra:krummholz", [(0, 12), (1, 1)], sapling="minecraft:spruce_sapling"),
    "trees_boreal": trees("terra:trees_boreal", [(10, 9), (11, 1)]),
    "trees_siberian": trees("terra:trees_siberian", [(7, 9), (8, 1)]),
    "trees_birch_taiga": trees("terra:trees_birch_taiga", [(8, 9), (9, 1)]),
    "trees_taiga_mountain": trees("terra:trees_taiga_mountain", [(3, 9), (4, 1)]),
    "trees_taiga_clearing": trees("terra:trees_boreal", [(0, 3), (1, 1)]),
    "trees_bog": trees("terra:trees_bog", [(1, 3), (2, 1), (0, 1)], water_depth=1),
    "trees_rainforest": trees("terra:trees_rainforest", [(12, 9), (14, 1)]),
    "trees_redwoods": trees("terra:trees_redwoods", [(6, 9), (7, 1)]),
    "trees_aspen": grove_trees("terra:trees_aspen", above=7, below=0),
    "trees_oasis": trees("terra:trees_oasis", [(3, 3), (5, 1)]),
    "trees_mirage": trees("terra:trees_mirage", [(9, 9), (11, 1)]),
    "huge_red_mushroom": {"feature": "minecraft:huge_red_mushroom", "placement": []},
    "huge_brown_mushroom": {"feature": "minecraft:huge_brown_mushroom", "placement": []},
    "trees_blooming": trees("minecraft:fancy_oak", [(0, 9), (1, 1)], sapling="minecraft:oak_sapling"),
    "trees_lush_valley": trees("terra:trees_highland", [(7, 9), (9, 1)]),
    "oasis_pool": {"feature": "terra:oasis_pool", "placement": [
        {"type": "minecraft:count", "count": 1}, {"type": "minecraft:in_square"},
        {"type": "minecraft:heightmap", "heightmap": "WORLD_SURFACE_WG"}, {"type": "minecraft:biome"}]},
    "trees_aspen_grove": trees("terra:trees_aspen", [(11, 9), (13, 1)]),
    "trees_prairie": trees("minecraft:oak", [(0, 39), (1, 1)], sapling="minecraft:oak_sapling"),
    "trees_moorland": trees("terra:oak_bush", [(0, 9), (1, 1)], sapling="minecraft:oak_sapling"),
    "trees_mediterranean": trees("terra:trees_mediterranean", [(2, 3), (3, 1), (1, 1)]),
    "trees_forested_highland": trees("terra:trees_forested_highland", [(9, 9), (11, 1)]),
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
# Vanilla's steep-slope test (as on jagged peaks): bare rock on cliffs and steep faces.
STEEP = {"type": "minecraft:steep"}


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
      colors={"grass_color": "#6c9a5c", "foliage_color": "#d4a72c"},
      veg=["minecraft:patch_large_fern", "terra:trees_boreal", "minecraft:patch_grass_taiga", "terra:patch_moss_carpet",
           "minecraft:brown_mushroom_taiga", "minecraft:red_mushroom_taiga", "minecraft:patch_berry_common",
           "minecraft:patch_firefly_bush_near_water"],
      top=rule((noise("surface", 0.0), "minecraft:podzol"), (noise("surface_secondary", 0.5), "minecraft:moss_block")))
biome("siberian_taiga", id=54, base="taiga", temp=0.15, downfall=0.4,
      colors={"grass_color": "#88a060", "foliage_color": "#d9772c", "dry_foliage_color": "#c0662a"},
      veg=["terra:trees_siberian", "minecraft:patch_grass_taiga_2", "minecraft:patch_large_fern",
           "minecraft:patch_leaf_litter", "minecraft:brown_mushroom_taiga", "minecraft:patch_berry_common"],
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
# Dense stands of aspen on cool mountainsides (Rocky Mountain aspen groves); the same aspens as the parkland.
biome("aspen_grove", id=74, base="birch_forest", temp=0.4, downfall=0.7,
      colors={"grass_color": "#7fae55", "foliage_color": "#d6b43a"},
      veg=["terra:trees_aspen_grove", "minecraft:patch_tall_grass_2", "minecraft:patch_grass_forest",
           "minecraft:flower_meadow", "minecraft:patch_large_fern", "minecraft:patch_leaf_litter",
           "minecraft:patch_berry_rare"],
      top=None)
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
      colors={"grass_color": "#72a35a", "foliage_color": "#d9502c", "dry_foliage_color": "#b5482a"},
      veg=["terra:trees_forested_highland", "minecraft:patch_grass_forest", "minecraft:patch_large_fern",
           "minecraft:flower_default", "terra:forest_rock_sparse", "minecraft:patch_berry_common"],
      top=rule((STEEP, "minecraft:stone"), (noise("surface", 0.45), "minecraft:stone"),
               (noise("surface", 0.25), "minecraft:coarse_dirt")),
      under=rule((STEEP, "minecraft:stone"), (noise("surface", 0.45), "minecraft:stone")), tags=["is_forest"])
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


# ---- v1.3 round A: shores (placed by CoastShaper) and rare land biomes (RegionalBiomes, salt flats by the carver) ----
CHALK = rule((noise("surface", 0.25), "minecraft:diorite"), (None, "minecraft:calcite"))
biome("shingle_beach", id=75, base="beach", temp=0.5, downfall=0.4, colors={},
      veg=[], top=rule((noise("surface", 0.45), "minecraft:cobblestone"), (noise("surface", -0.45), GRAVEL),
                       (None, "minecraft:sand")),
      under=rule((None, GRAVEL)), top_wet=True)
biome("salt_marsh", id=76, base="swamp", temp=0.6, downfall=0.8,
      colors={"grass_color": "#8aa452", "foliage_color": "#6f8f42", "water_color": "#4d7a72"},
      lakes=["terra:wetland_pool"],
      veg=["minecraft:patch_tall_grass", "minecraft:patch_grass_normal", "minecraft:patch_sugar_cane_swamp",
           "minecraft:seagrass_swamp", "minecraft:patch_dead_bush"],
      top=rule((noise("surface_swamp", -0.1), "minecraft:mud"), (None, "minecraft:grass_block")), top_wet=True)
biome("tidal_flats", id=77, base="beach", temp=0.6, downfall=0.6, colors={"water_color": "#5a7f86"},
      veg=["minecraft:seagrass_swamp"],
      top=rule((noise("surface", 0.4), "minecraft:sand"), (noise("surface_secondary", 0.4), "minecraft:clay"),
               (None, "minecraft:mud")),
      under=rule((None, "minecraft:mud")), top_wet=True)
biome("white_cliffs", id=78, base="plains", temp=0.6, downfall=0.6,
      colors={"grass_color": "#86b25a", "foliage_color": "#6a9a48"},
      veg=["minecraft:patch_grass_plain", "minecraft:flower_default", "minecraft:patch_bush"],
      top=None, under=None, deep=(28, CHALK, "after"))
biome("painted_mountains", id=79, base="badlands", temp=2.0, downfall=0.0,
      colors={"grass_color": "#90814d", "foliage_color": "#9e814d"},
      veg=["minecraft:patch_dead_bush_badlands", "minecraft:patch_dry_grass_badlands"],
      top=None, deep=(60, {"type": "minecraft:bandlands"}, "before"))
biome("desert_oasis", id=80, base="desert", temp=1.6, downfall=0.4,
      colors={"grass_color": "#6fb04a", "foliage_color": "#4fa83a"},
      lakes=["terra:oasis_pool"],
      veg=["terra:trees_oasis", "minecraft:patch_grass_jungle", "minecraft:patch_tall_grass",
           "minecraft:patch_sugar_cane_desert", "minecraft:flower_warm"],
      top=rule((noise("surface", 0.5), "minecraft:sand")), top_wet=True)
biome("blooming_plateau", id=81, base="meadow", temp=0.6, downfall=0.8,
      colors={"grass_color": "#7cc25a", "foliage_color": "#62a84a"},
      veg=["terra:trees_blooming", "minecraft:flower_flower_forest", "minecraft:wildflowers_meadow",
           "minecraft:flower_meadow", "minecraft:patch_tall_grass_2", "minecraft:patch_grass_meadow"],
      top=None)
biome("blooming_valley", id=82, base="plains", temp=0.8, downfall=0.7,
      colors={"grass_color": "#82c45a", "foliage_color": "#66aa48"},
      veg=["terra:trees_blooming", "minecraft:flower_flower_forest", "minecraft:flower_plains",
           "minecraft:wildflowers_meadow", "minecraft:patch_tall_grass", "minecraft:patch_sunflower",
           "minecraft:patch_firefly_bush_near_water"],
      top=None)
biome("lush_valley", id=83, base="forest", temp=0.8, downfall=0.9,
      colors={"grass_color": "#5fb046", "foliage_color": "#48a038"},
      veg=["terra:trees_lush_valley", "minecraft:patch_large_fern", "minecraft:patch_tall_grass",
           "minecraft:flower_forest_flowers", "terra:patch_moss_carpet", "minecraft:patch_berry_common",
           "minecraft:patch_leaf_litter", "minecraft:patch_firefly_bush_near_water"],
      top=rule((noise("surface", 0.45), "minecraft:moss_block")))
biome("salt_flats", id=84, base="desert", temp=2.0, downfall=0.0, precip=False,
      colors={}, veg=[],
      top=rule((noise("surface", 0.3), "minecraft:calcite"), (None, "minecraft:white_terracotta")),
      under=rule((None, "minecraft:white_terracotta")), top_wet=True)

# Ice-capped islands in frozen seas (v1.3 round C): snow over packed ice, bare ice in patches, polar bears and snow
# from snowy plains; no villages or outposts on the ice cap.
biome("arctic", id=85, base="snowy_plains", temp=-0.7, downfall=0.4,
      colors={"grass_color": "#c8d8d0", "foliage_color": "#a8c0b8", "water_color": "#3d57d6"},
      veg=[],
      top=rule((noise("surface", 0.35), "minecraft:packed_ice"), (noise("surface_secondary", 0.55), "minecraft:blue_ice"),
               (None, "minecraft:snow_block")),
      under=rule((None, "minecraft:packed_ice")), top_wet=True,
      no_tags=["has_structure/village_snowy", "has_structure/pillager_outpost"])

V12 = [f"terra:{n}" for n in B]
# Vanilla biomes whose surface rules this script also owns (Terra places them; vanilla's rule tree isn't used).
MANAGED = V12 + ["minecraft:mushroom_fields"]
EXTRA_TOP = [("minecraft:mushroom_fields", {"type": "minecraft:block", "result_state": "minecraft:mycelium"})]

# ---- v1.4 round A: volcanoes (set by the tile from the landform stage's tags; pipeline/Landforms) ----
# Bare upper slopes of stratovolcanoes and shield summits: ash and scoria (tuff, andesite, gravel), basalt outcrops.
biome("volcano", id=86, base="windswept_gravelly_hills", temp=0.8, downfall=0.2,
      colors={"grass_color": "#8f9a6a", "foliage_color": "#7d8a5c"},
      veg=["minecraft:patch_dead_bush_badlands"],
      top=rule((noise("surface", 0.35), "minecraft:basalt"), (noise("surface", 0.05), "minecraft:tuff"),
               (noise("surface_secondary", 0.2), GRAVEL), (None, "minecraft:andesite")),
      under=rule((noise("surface", 0.35), "minecraft:basalt"), (None, "minecraft:tuff")), top_wet=True,
      no_tags=["has_structure/village_plains", "has_structure/pillager_outpost"])
# Lava flows: bare blackstone and basalt (VolcanicSurfaces lays the exact flows, live lava and magma on top).
biome("lava_fields", id=87, base="windswept_gravelly_hills", temp=1.0, downfall=0.1,
      colors={"grass_color": "#7f8a5e", "foliage_color": "#6f7a50"},
      veg=[],
      top=rule((noise("surface", 0.3), "minecraft:smooth_basalt"), (noise("surface", -0.15), "minecraft:basalt"),
               (None, "minecraft:blackstone")),
      under=rule((noise("surface", 0.2), "minecraft:basalt"), (None, "minecraft:blackstone")), top_wet=True,
      no_tags=["has_structure/village_plains", "has_structure/pillager_outpost"])

# ---- v1.4 round B: land glaciers (set by the tile where GlacierShaper lays ice; world/Glaciers places the ice) ----
# The ground under and between the ice: scoured rock and gravel; snow and polar bears from frozen peaks, no trees.
biome("glacier", id=88, base="frozen_peaks", temp=-0.7, downfall=0.6,
      colors={"grass_color": "#b8ccc4", "foliage_color": "#9cb4ac", "water_color": "#3d57d6"},
      veg=[],
      top=rule((noise("surface", 0.25), GRAVEL), (None, "minecraft:stone")),
      under=rule((None, "minecraft:stone")), top_wet=True,
      no_tags=["has_structure/village_snowy", "has_structure/pillager_outpost"])


# ---- v1.4 round C: dry-land landforms and karst (set by the tile: pipeline/AridLandforms, FanPlan, KarstTowers) ----
TOP_BLOCK = {"type": "minecraft:stone_depth", "offset": 0, "surface_type": "floor", "add_surface_depth": False,
             "secondary_depth_range": 0}
NOT_STEEP = {"type": "minecraft:not", "invert": STEEP}
BADLANDS_VEG = ["minecraft:patch_grass_badlands", "minecraft:patch_dry_grass_badlands", "minecraft:patch_dead_bush_badlands",
                "minecraft:patch_cactus_decorated"]
# Canyon walls and floors: banded terracotta 80 blocks down every column (the cliffs show the bands).
biome("canyon", id=89, base="badlands", temp=2.0, downfall=0.0,
      colors={"grass_color": "#90814d", "foliage_color": "#9e814d"},
      veg=BADLANDS_VEG,
      top=None, deep=(80, {"type": "minecraft:bandlands"}, "before"),
      no_tags=["has_structure/village_plains", "has_structure/village_desert"])
# Badlands mesas: banded terracotta layers, red sand on the flat tops and benches.
biome("badlands_mesa", id=90, base="badlands", temp=2.0, downfall=0.0,
      colors={"grass_color": "#90814d", "foliage_color": "#9e814d"},
      veg=BADLANDS_VEG,
      top=None, deep=(70, {"type": "minecraft:sequence", "sequence": [
          {"type": "minecraft:condition", "if_true": NOT_STEEP, "then_run": {
              "type": "minecraft:condition", "if_true": TOP_BLOCK, "then_run": block("minecraft:red_sand")}},
          {"type": "minecraft:bandlands"}]}, "before"),
      no_tags=["has_structure/village_plains", "has_structure/village_desert"])
# Alluvial fans: gravel, coarse dirt and sand spread from desert valley mouths.
biome("alluvial_fan", id=91, base="desert", temp=1.8, downfall=0.0, precip=False,
      colors={"grass_color": "#bfb77a", "foliage_color": "#aea06a"},
      veg=["minecraft:patch_dead_bush_2", "minecraft:patch_dry_grass_desert"],
      top=rule((noise("surface", 0.25), GRAVEL), (noise("surface", -0.2), "minecraft:coarse_dirt"), (None, "minecraft:sand")),
      under=rule((noise("surface", 0.0), GRAVEL), (None, "minecraft:sandstone")), top_wet=True,
      no_tags=["has_structure/village_desert", "has_structure/desert_pyramid"])
# Karst towers: bare limestone (stone and calcite) on the cliffs, moss and jungle on the tops.
KARST_ROCK = {"type": "minecraft:sequence", "sequence": [
    {"type": "minecraft:condition", "if_true": noise("surface", 0.15), "then_run": block("minecraft:calcite")},
    block("minecraft:stone")]}
biome("karst", id=92, base="jungle", temp=0.95, downfall=0.9,
      colors={"grass_color": "#59c93c", "foliage_color": "#30bb0b"},
      veg=["minecraft:trees_sparse_jungle", "minecraft:flower_warm", "minecraft:patch_grass_jungle",
           "minecraft:patch_sugar_cane", "minecraft:vines", "terra:patch_moss_carpet"],
      top={"type": "minecraft:sequence", "sequence": [
          {"type": "minecraft:condition", "if_true": STEEP, "then_run": KARST_ROCK},
          {"type": "minecraft:condition", "if_true": noise("surface", 0.3), "then_run": block("minecraft:moss_block")}]},
      under={"type": "minecraft:condition", "if_true": STEEP, "then_run": KARST_ROCK}, top_wet=True,
      no_tags=["has_structure/jungle_temple"])

# ---- v1.4 round D: showpieces (set by the tile: Showpieces tags, AridLandforms yosemite cut) ----
# Geothermal basin floors (Yellowstone): white sinter (calcite, white terracotta) with grey tuff, orange and yellow
# runoff stains, sulfur crusts and specks of cinnabar; world/Geothermal lays the hot spring pools and the geysers.
# Bright turquoise water.
biome("geothermal_basin", id=93, base="taiga", temp=0.3, downfall=0.6,
      colors={"grass_color": "#8aa05a", "foliage_color": "#5f8a4c", "water_color": "#22c6e0"},
      veg=["minecraft:patch_dead_bush_2"],
      top=rule((noise("surface_secondary", 0.62), "minecraft:sulfur"),
               (noise("surface", -1e9, -0.6), "minecraft:cinnabar"),
               (noise("surface_secondary", 0.5), "minecraft:orange_terracotta"),
               (noise("surface_secondary", 0.38), "minecraft:yellow_terracotta"),
               (noise("surface", -1e9, -0.35), "minecraft:tuff"),
               (noise("surface", 0.3), "minecraft:calcite"),
               (None, "minecraft:white_terracotta")),
      under=rule((noise("surface", 0.0), "minecraft:calcite"), (None, "minecraft:white_terracotta")), top_wet=True,
      no_tags=["has_structure/village_taiga", "has_structure/pillager_outpost"])
# Yosemite valleys: sheer white granite walls (calcite and diorite down every column, so the cliff faces show it),
# pine forest on the flat floor and benches.
WHITE_GRANITE = {"type": "minecraft:sequence", "sequence": [
    {"type": "minecraft:condition", "if_true": noise("surface", 0.2), "then_run": block("minecraft:calcite")},
    block("minecraft:diorite")]}
biome("yosemite", id=94, base="old_growth_pine_taiga", temp=0.35, downfall=0.8,
      colors={"grass_color": "#79a95a", "foliage_color": "#5f8f4a"},
      veg=["minecraft:trees_old_growth_pine_taiga", "minecraft:patch_large_fern", "minecraft:patch_grass_taiga",
           "minecraft:flower_default", "minecraft:patch_berry_common"],
      top={"type": "minecraft:sequence", "sequence": [
          {"type": "minecraft:condition", "if_true": STEEP, "then_run": WHITE_GRANITE},
          {"type": "minecraft:condition", "if_true": noise("surface", 0.45), "then_run": block("minecraft:coarse_dirt")}]},
      under={"type": "minecraft:condition", "if_true": STEEP, "then_run": WHITE_GRANITE},
      deep=(40, WHITE_GRANITE, "after"),
      no_tags=["has_structure/village_taiga", "has_structure/pillager_outpost"])
# Mirage isles: sheer pale cliffs in bands (calcite, white and pink terracotta), teal jungle and giant mushrooms on top.
# Like mushroom fields: no monsters, mooshrooms (red and brown, see MirageMooshroomMixin; grass counts for them).
MIRAGE_BANDS = ["minecraft:calcite", "minecraft:white_terracotta", "minecraft:pink_terracotta", "minecraft:white_terracotta",
                "minecraft:calcite", "minecraft:smooth_sandstone", "minecraft:white_terracotta", "minecraft:terracotta"]
MIRAGE_STRATA = {"type": "minecraft:sequence", "sequence": [
    {"type": "minecraft:condition", "if_true": {"type": "minecraft:y_above", "anchor": {"absolute": y},
                                                  "surface_depth_multiplier": 0, "add_stone_depth": False},
     "then_run": block(MIRAGE_BANDS[(y // 2) % len(MIRAGE_BANDS)])}
    for y in range(160, 20, -2)] + [block("minecraft:calcite")]}
biome("mirage_isles", id=95, base="sparse_jungle", temp=0.9, downfall=0.8,
      colors={"grass_color": "#5fd2b0", "foliage_color": "#36d1bd", "water_color": "#43c8e0"},
      veg=["terra:trees_mirage", "minecraft:patch_grass_jungle", "minecraft:flower_warm", "minecraft:vines"],
      top=None, deep=(60, MIRAGE_STRATA, "after"), spawns_from="mushroom_fields",
      no_tags=["has_structure/jungle_temple"])


# ---- v1.5 part one: cave biomes (set by the biome source below the cave layer's start depth; pipeline/CaveBiomes) ----
# Andesite, diorite, granite and tuff caves: vanilla's dripstone caves (cave mob spawns, mineshaft, trial chamber and
# stronghold tags) without its dripstone and without its music. world/CaveStone turns the rock in these biomes into
# their stone; no material rules (the stone is painted in Java, so no biome lookup per stone block).
STONE_CAVE = dict(base="dripstone_caves", temp=0.8, downfall=0.4, colors={}, veg=[], top=None,
                  steps={2: ["minecraft:amethyst_geode"], 7: []}, drop_attrs=["minecraft:audio/background_music"],
                  cave=True)
biome("andesite_caves", id=140, **STONE_CAVE)
biome("diorite_caves", id=141, **STONE_CAVE)
biome("granite_caves", id=142, **STONE_CAVE)
biome("tuff_caves", id=143, **STONE_CAVE)
# id 144 is vanilla's dripstone_caves, used as is (in VANILLA_USED below).

# ---- v1.5 part two round A: the dry uncommon caves (CaveBiomes; lush and thermal caves are vanilla's) ----
# Placement of Terra's cave features: tries per chunk column per 256 blocks of its cave layer (terra:cave_layer picks
# Y between the bottom of the world and where cave biomes start, so caves inside mountains get features too).
AIR = {"type": "minecraft:matching_block_tag", "tag": "minecraft:air"}
SCAN_FLOOR = [{"type": "minecraft:environment_scan", "allowed_search_condition": AIR, "direction_of_search": "down",
               "max_steps": 12, "target_condition": {"type": "minecraft:solid"}}, {"type": "minecraft:offset", "x": 0, "y": 1, "z": 0}]
SCAN_CEILING = [{"type": "minecraft:environment_scan", "allowed_search_condition": AIR, "direction_of_search": "up",
                 "max_steps": 12, "target_condition": {"type": "minecraft:solid"}}, {"type": "minecraft:offset", "x": 0, "y": -1, "z": 0}]


def cave_placed(feature, count, scan=(), pre=(), post=()):
    """count: tries per chunk per 256 blocks of cave layer (int, or a dict count provider)."""
    lead = list(pre) if pre else [{"type": "minecraft:count", "count": count}]
    return {"feature": feature, "placement": lead + [{"type": "minecraft:in_square"}, {"type": "terra:cave_layer"},
                                                   {"type": "minecraft:biome"}] + list(scan) + list(post)}


def weighted(*pairs):
    return {"type": "minecraft:weighted", "entries": [{"data": b, "weight": w} for b, w in pairs]}


def inline(feature):
    return {"feature": feature, "placement": []}


def patch_cfg(ground, surface, veg, veg_chance, depth=1, radius=(4, 7), replaceable="#minecraft:moss_replaceable",
              edge=0.3, kind="minecraft:vegetation_patch", bottom=0.0):
    return {"type": kind, "depth": depth if isinstance(depth, int) else uniform(*depth),
            "extra_bottom_block_chance": bottom, "extra_edge_column_chance": edge,
            "ground_state": ground if isinstance(ground, dict) else {"id": ground}, "replaceable": replaceable,
            "surface": surface, "vegetation_chance": veg_chance, "vegetation_feature": inline(veg), "vertical_range": 5,
            "xz_radius": uniform(*radius)}


def column(direction, *layers):
    """layers: (block id or provider, height or (min, max)), from the attachment point outwards."""
    return {"type": "minecraft:block_column", "allowed_placement": AIR, "direction": direction, "prioritize_tip": True,
            "layers": [{"height": h if isinstance(h, int) else uniform(*h),
                        "provider": b if isinstance(b, dict) else {"id": b}} for b, h in layers]}


def ore(state, size, deep_state=None):
    stone = {"predicate_type": "minecraft:tag_match", "tag": "minecraft:base_stone_overworld"}
    targets = [{"state": state, "target": stone}]
    if deep_state:
        targets = [{"state": deep_state, "target": {"predicate_type": "minecraft:block_match", "block": "minecraft:deepslate"}},
                   {"state": state, "target": stone}]
    return {"type": "minecraft:ore", "discard_chance_on_air_exposure": 0.0, "size": size, "targets": targets}


def fungus(stem, hat):
    return {"type": "minecraft:huge_fungus", "valid_base_block": "minecraft:moss_block", "stem_state": stem,
            "hat_state": hat, "decor_state": "minecraft:shroomlight", "planted": False,
            "replaceable_blocks": {"type": "minecraft:matching_blocks", "blocks": [
                "minecraft:crimson_roots", "minecraft:warped_roots", "minecraft:brown_mushroom", "minecraft:red_mushroom",
                "minecraft:moss_carpet", "minecraft:crimson_fungus", "minecraft:warped_fungus", "minecraft:short_grass"]}}


def blob(state):
    return {"type": "minecraft:block_blob", "state": state,
            "can_place_on": {"type": "minecraft:matching_block_tag", "tag": "minecraft:forest_rock_can_place_on"}}


CAVE_FEATURES = {
    # Glow lichen on every cave rock Terra uses (vanilla's only covers stone up to Y 256).
    "cave_glow_lichen": {"type": "minecraft:multiface_growth", "block": "minecraft:glow_lichen", "can_place_on_ceiling": True,
                         "can_place_on_wall": True, "search_range": 20, "can_be_placed_on": [
        "minecraft:stone", "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:tuff",
        "minecraft:deepslate", "minecraft:calcite", "minecraft:dripstone_block", "minecraft:white_terracotta",
        "minecraft:pink_terracotta", "minecraft:moss_block", "minecraft:mud", "minecraft:packed_mud",
        "minecraft:cobblestone", "minecraft:mossy_cobblestone", "minecraft:copper_ore", "minecraft:packed_ice"]},
    "cave_moss_carpet": {"type": "minecraft:simple_block", "to_place": {"id": "minecraft:moss_carpet"}},
    # Fungal caves: moss and mycelium floors with roots and mushrooms, moss ceilings studded with shroomlight and wart
    # with weeping vines, giant crimson and warped fungi (wart caps lit by shroomlight), huge red and brown mushrooms.
    # Oct 27: far fewer little mushrooms (Calvin): mostly roots and moss, the odd mushroom.
    "cave_fungal_plants": {"type": "minecraft:simple_block", "to_place": weighted(
        ("minecraft:crimson_roots", 3), ("minecraft:warped_roots", 3), ("minecraft:brown_mushroom", 1),
        ("minecraft:red_mushroom", 1), ("minecraft:moss_carpet", 3))},
    "cave_weeping_vines": column("down", ("minecraft:weeping_vines_plant", (0, 6)),
                                 ({"id": "minecraft:weeping_vines", "properties": {"age": "25"}}, 1)),
    "cave_twisting_vines": column("up", ("minecraft:twisting_vines_plant", (0, 5)),
                                  ({"id": "minecraft:twisting_vines", "properties": {"age": "25"}}, 1)),
    "cave_warped_giant": fungus("minecraft:warped_stem", "minecraft:warped_wart_block"),
    "cave_crimson_giant": fungus("minecraft:crimson_stem", "minecraft:nether_wart_block"),
    # Underground jungle: mossy floors thick with grass and ferns, bamboo, jungle bushes, vines and glow berries.
    "cave_jungle_plants": {"type": "minecraft:simple_block", "to_place": weighted(
        ("minecraft:short_grass", 6), ("minecraft:fern", 5), ("minecraft:large_fern", 2), ("minecraft:bush", 3),
        ("minecraft:tall_grass", 1))},
    # Icy caves: ice in the walls, snow and ice floors, icicles and ice spikes. Frostfire adds magma.
    "cave_snow_layer": {"type": "minecraft:simple_block", "to_place": {"id": "minecraft:snow"}},
    "cave_icicle": column("down", ("minecraft:packed_ice", (0, 1)), ("minecraft:ice", (1, 3))),
    "cave_ice_spike": column("up", ("minecraft:packed_ice", (1, 2)), ("minecraft:ice", (0, 2))),
    "cave_ore_packed_ice": ore("minecraft:packed_ice", 48),
    "cave_ore_ice": ore("minecraft:ice", 30),
    "cave_ore_blue_ice": ore("minecraft:blue_ice", 14),
    "cave_ore_snow": ore("minecraft:snow_block", 24),
    "cave_ore_magma": ore("minecraft:magma_block", 24),
    # Infested caves: silverfish in the walls, rubble on the floor, more monster rooms.
    "cave_ore_infested": ore("minecraft:infested_stone", 33, "minecraft:infested_deepslate"),
    "cave_ore_infested_cobble": ore("minecraft:infested_cobblestone", 20),
    "cave_cobweb": {"type": "minecraft:simple_block", "to_place": {"id": "minecraft:cobweb"}},
    "cave_rubble": {"type": "minecraft:simple_block", "to_place": weighted(("minecraft:cobblestone", 2), ("minecraft:stone", 1))},
    # Copper caves: rich copper veins with raw copper and green-blue stains of weathered and oxidized copper; moss.
    "cave_ore_copper": ore("minecraft:copper_ore", 28, "minecraft:deepslate_copper_ore"),
    "cave_ore_raw_copper": ore("minecraft:raw_copper_block", 8),
    "cave_ore_oxidized_copper": ore("minecraft:oxidized_copper", 12),
    "cave_ore_weathered_copper": ore("minecraft:weathered_copper", 12),
    # Salt caves (rock painted by world/CaveStone): salt stalagmites and stalactites.
    "cave_salt_spike": column("up", (weighted(("minecraft:calcite", 3), ("minecraft:white_terracotta", 1)), (1, 3))),
    "cave_salt_stalactite": column("down", (weighted(("minecraft:calcite", 3), ("minecraft:white_terracotta", 1)), (1, 3))),
    # Mud caves: mud and packed mud floors, shallow muddy pools, clay pools with dripleaves, mangrove roots overhead.
    "cave_mud_plants": {"type": "minecraft:simple_block", "to_place": weighted(("minecraft:brown_mushroom", 2), ("minecraft:red_mushroom", 1))},
    "cave_mangrove_roots": column("down", ("minecraft:mangrove_roots", (1, 4))),
    "cave_hanging_roots": {"type": "minecraft:simple_block", "to_place": {"id": "minecraft:hanging_roots"}},
    # Breakdown chambers: boulders of fallen rock on gravel and rubble floors, a broken ceiling.
    "cave_boulder_cobble": blob("minecraft:cobblestone"),
    "cave_boulder_stone": blob("minecraft:stone"),
    "cave_boulder_andesite": blob("minecraft:andesite"),
    "cave_boulder_mossy": blob("minecraft:mossy_cobblestone"),
}
CAVE_FEATURES.update({
    "cave_fungal_floor": patch_cfg(weighted(("minecraft:moss_block", 4), ("minecraft:mycelium", 1)), "floor",
                                   "terra:cave_fungal_plants", 0.12, radius=(4, 8)),
    "cave_fungal_ceiling": patch_cfg(weighted(("minecraft:moss_block", 7), ("minecraft:shroomlight", 1),
                                              ("minecraft:nether_wart_block", 1), ("minecraft:warped_wart_block", 1)),
                                     "ceiling", "terra:cave_weeping_vines", 0.12),
    "cave_jungle_floor": patch_cfg("minecraft:moss_block", "floor", "terra:cave_jungle_plants", 0.6, depth=(1, 2), radius=(4, 8)),
    "cave_jungle_ceiling": patch_cfg("minecraft:moss_block", "ceiling", "minecraft:cave_vine_in_moss", 0.12, depth=(1, 2)),
    "cave_icy_floor": patch_cfg(weighted(("minecraft:snow_block", 3), ("minecraft:packed_ice", 2), ("minecraft:ice", 1)),
                                "floor", "terra:cave_snow_layer", 0.5, depth=(1, 2), replaceable="#minecraft:base_stone_overworld"),
    "cave_icy_ceiling": patch_cfg(weighted(("minecraft:packed_ice", 3), ("minecraft:ice", 2), ("minecraft:blue_ice", 1)),
                                  "ceiling", "terra:cave_icicle", 0.25, replaceable="#minecraft:base_stone_overworld"),
    "cave_frostfire_floor": patch_cfg(weighted(("minecraft:packed_ice", 3), ("minecraft:blue_ice", 2), ("minecraft:magma_block", 1)),
                                      "floor", "terra:cave_snow_layer", 0.0, depth=(1, 2), replaceable="#minecraft:base_stone_overworld"),
    "cave_rubble_floor": patch_cfg(weighted(("minecraft:gravel", 3), ("minecraft:cobblestone", 2), ("minecraft:mossy_cobblestone", 1)),
                                   "floor", "terra:cave_rubble", 0.15, depth=(1, 2), replaceable="#minecraft:base_stone_overworld"),
    "cave_broken_ceiling": patch_cfg(weighted(("minecraft:cobblestone", 2), ("minecraft:stone", 2), ("minecraft:andesite", 1)),
                                     "ceiling", "terra:cave_rubble", 0.0, depth=(1, 3), replaceable="#minecraft:base_stone_overworld"),
    "cave_mud_floor": patch_cfg(weighted(("minecraft:mud", 4), ("minecraft:packed_mud", 2), ("minecraft:muddy_mangrove_roots", 2)),
                                "floor", "terra:cave_mud_plants", 0.08, depth=(1, 2), radius=(4, 8)),
    "cave_mud_pool": patch_cfg("minecraft:mud", "floor", "terra:cave_moss_carpet", 0.0, depth=2, edge=0.6, bottom=0.6,
                               kind="minecraft:waterlogged_vegetation_patch", replaceable="#minecraft:lush_ground_replaceable"),
})
FEATURES.update(CAVE_FEATURES)

COBWEB_DROP = {"type": "minecraft:offset", "x": 0, "y": uniform(-3, 0), "z": 0}
CAVE_PLACED = {
    "cave_glow_lichen": cave_placed("terra:cave_glow_lichen", 90),
    "cave_glow_lichen_sparse": cave_placed("terra:cave_glow_lichen", 30),
    "cave_fungal_floor": cave_placed("terra:cave_fungal_floor", 70, SCAN_FLOOR),
    "cave_fungal_ceiling": cave_placed("terra:cave_fungal_ceiling", 55, SCAN_CEILING),
    # Oct 27: many more giants (Calvin: more big mushrooms).
    "cave_warped_giants": cave_placed("terra:cave_warped_giant", 0, SCAN_FLOOR, pre=[
        {"type": "minecraft:noise_threshold_count", "noise_level": 0.0, "below_noise": 6, "above_noise": 34}]),
    "cave_crimson_giants": cave_placed("terra:cave_crimson_giant", 0, SCAN_FLOOR, pre=[
        {"type": "minecraft:noise_threshold_count", "noise_level": 0.0, "below_noise": 34, "above_noise": 6}]),
    "cave_huge_brown_mushrooms": cave_placed("minecraft:huge_brown_mushroom", 18, SCAN_FLOOR),
    "cave_huge_red_mushrooms": cave_placed("minecraft:huge_red_mushroom", 15, SCAN_FLOOR),
    "cave_twisting_vines": cave_placed("terra:cave_twisting_vines", 20, SCAN_FLOOR),
    "cave_jungle_floor": cave_placed("terra:cave_jungle_floor", 80, SCAN_FLOOR),
    "cave_jungle_ceiling": cave_placed("terra:cave_jungle_ceiling", 55, SCAN_CEILING),
    "cave_jungle_bamboo": cave_placed("minecraft:bamboo_no_podzol", 24, SCAN_FLOOR),
    "cave_jungle_bushes": cave_placed("minecraft:jungle_bush", 14, SCAN_FLOOR, post=[survive("minecraft:jungle_sapling")]),
    "cave_jungle_vines": cave_placed("minecraft:vines", 256),
    "cave_icy_floor": cave_placed("terra:cave_icy_floor", 60, SCAN_FLOOR),
    "cave_icy_ceiling": cave_placed("terra:cave_icy_ceiling", 50, SCAN_CEILING),
    "cave_icicles": cave_placed("terra:cave_icicle", 60, SCAN_CEILING),
    "cave_ice_spikes": cave_placed("terra:cave_ice_spike", 25, SCAN_FLOOR),
    "cave_ore_packed_ice": cave_placed("terra:cave_ore_packed_ice", 10),
    "cave_ore_ice": cave_placed("terra:cave_ore_ice", 6),
    "cave_ore_blue_ice": cave_placed("terra:cave_ore_blue_ice", 5),
    "cave_ore_snow": cave_placed("terra:cave_ore_snow", 4),
    "cave_ore_magma": cave_placed("terra:cave_ore_magma", 9),
    "cave_frostfire_floor": cave_placed("terra:cave_frostfire_floor", 50, SCAN_FLOOR),
    "cave_ore_infested": cave_placed("terra:cave_ore_infested", 16),
    "cave_ore_infested_cobble": cave_placed("terra:cave_ore_infested_cobble", 6),
    "cave_rubble_floor": cave_placed("terra:cave_rubble_floor", 45, SCAN_FLOOR),
    "cave_monster_rooms": cave_placed("minecraft:monster_room", 5),
    "cave_cobwebs_ceiling": cave_placed("terra:cave_cobweb", 220, SCAN_CEILING, post=[COBWEB_DROP, AIR_FILTER]),
    "cave_cobwebs_floor": cave_placed("terra:cave_cobweb", 70, SCAN_FLOOR),
    "cave_cobwebs_sparse": cave_placed("terra:cave_cobweb", 25, SCAN_CEILING),
    "cave_ore_copper": cave_placed("terra:cave_ore_copper", 26),
    "cave_ore_raw_copper": cave_placed("terra:cave_ore_raw_copper", 4),
    "cave_ore_oxidized_copper": cave_placed("terra:cave_ore_oxidized_copper", 7),
    "cave_ore_weathered_copper": cave_placed("terra:cave_ore_weathered_copper", 7),
    "cave_moss_floor": cave_placed("minecraft:moss_patch", 25, SCAN_FLOOR),
    "cave_moss_ceiling": cave_placed("minecraft:moss_patch_ceiling", 15, SCAN_CEILING),
    "cave_salt_spikes": cave_placed("terra:cave_salt_spike", 30, SCAN_FLOOR),
    "cave_salt_stalactites": cave_placed("terra:cave_salt_stalactite", 40, SCAN_CEILING),
    "cave_mud_floor": cave_placed("terra:cave_mud_floor", 60, SCAN_FLOOR),
    "cave_mud_pools": cave_placed("terra:cave_mud_pool", 30, SCAN_FLOOR),
    "cave_clay_pools": cave_placed("minecraft:clay_pool_with_dripleaves", 10, SCAN_FLOOR),
    "cave_mangrove_roots": cave_placed("terra:cave_mangrove_roots", 45, SCAN_CEILING),
    "cave_hanging_roots": cave_placed("terra:cave_hanging_roots", 45, SCAN_CEILING),
    "cave_boulders_cobble": cave_placed("terra:cave_boulder_cobble", 7, SCAN_FLOOR),
    "cave_boulders_stone": cave_placed("terra:cave_boulder_stone", 7, SCAN_FLOOR),
    "cave_boulders_andesite": cave_placed("terra:cave_boulder_andesite", 4, SCAN_FLOOR),
    "cave_boulders_mossy": cave_placed("terra:cave_boulder_mossy", 3, SCAN_FLOOR),
    "cave_broken_ceiling": cave_placed("terra:cave_broken_ceiling", 40, SCAN_CEILING),
}
PLACED.update(CAVE_PLACED)

# The cave biomes (part two round A). As the stone caves: dripstone caves' spawns and tags without its dripstone or music.
def cave_biome(name, cid, temp, under=(), ores=(), veg=(), **kw):
    biome(name, id=cid, base="dripstone_caves", temp=temp, downfall=kw.pop("downfall", 0.5), colors=kw.pop("colors", {}),
          veg=list(veg), top=None, steps={2: ["minecraft:amethyst_geode"], 7: list(under)}, add_steps={6: list(ores)},
          drop_attrs=["minecraft:audio/background_music"], cave=True, **kw)


cave_biome("fungal_caves", 146, 0.8, downfall=0.9,
           under=["terra:cave_fungal_floor", "terra:cave_fungal_ceiling"],
           veg=["terra:cave_crimson_giants", "terra:cave_warped_giants", "terra:cave_huge_brown_mushrooms",
                "terra:cave_huge_red_mushrooms", "terra:cave_twisting_vines", "terra:cave_glow_lichen"])
cave_biome("underground_jungle", 147, 0.95, downfall=0.9,
           under=["terra:cave_jungle_floor", "terra:cave_jungle_ceiling"],
           veg=["terra:cave_jungle_bamboo", "terra:cave_jungle_bushes", "terra:cave_jungle_vines", "terra:cave_glow_lichen"])
cave_biome("icy_caves", 148, -0.5, downfall=0.4,
           under=["terra:cave_icy_floor", "terra:cave_icy_ceiling", "terra:cave_icicles", "terra:cave_ice_spikes"],
           ores=["terra:cave_ore_packed_ice", "terra:cave_ore_ice", "terra:cave_ore_blue_ice", "terra:cave_ore_snow"],
           spawns_from="snowy_plains")
# Frostfire caves (Oct 27: "exactly like the Terralith version"): Terralith's design rebuilt from vanilla features.
# Deepslate and blue ice patches on floors and ceilings, soul fire on the floor patches (on soul soil, the only ground
# soul fire stays lit on), blue ice icicles and the odd chain hanging a soul lantern from the ceiling, huge blue ice
# pillars (vanilla large dripstone, turned to blue ice by world/FrostfireCaves after features), heavy sculk with its
# sensors, no lava (also world/FrostfireCaves), skeletons and strays, black fog and cyan-to-blue motes. Soul fire and
# soul lanterns are the one exception to "cave light only from glow lichen, shroomlight and froglights".
SCAN_FLOOR_32 = [{"type": "minecraft:environment_scan", "allowed_search_condition": AIR, "direction_of_search": "down",
                  "max_steps": 32, "target_condition": {"type": "minecraft:solid"}}]
SCAN_CEILING_30 = [{"type": "minecraft:environment_scan", "allowed_search_condition": AIR, "direction_of_search": "up",
                    "max_steps": 30, "target_condition": {"type": "minecraft:solid"}}]
FEATURES.update({
    # Soul fire on a soul soil block set into the floor.
    "cave_frostfire_flame": {"type": "minecraft:sequence", "features": [
        {"feature": {"type": "minecraft:simple_block", "to_place": {"id": "minecraft:soul_soil"}},
         "placement": [{"type": "minecraft:offset", "x": 0, "y": -1, "z": 0}]},
        {"feature": {"type": "minecraft:simple_block", "to_place": {"id": "minecraft:soul_fire"}}, "placement": []}]},
    "cave_frostfire_floor": patch_cfg(weighted(("minecraft:deepslate", 2), ("minecraft:blue_ice", 2)), "floor",
                                      "terra:cave_frostfire_flame", 0.05, depth=(1, 2), radius=(2, 5), edge=0.5,
                                      bottom=0.5, replaceable="#minecraft:base_stone_overworld"),
    "cave_frostfire_icicle": {"type": "minecraft:block_column", "allowed_placement": {"type": "minecraft:true"},
        "direction": "down", "prioritize_tip": False,
        "layers": [{"height": {"type": "minecraft:biased_to_bottom", "min_inclusive": 1, "max_inclusive": 5},
                    "provider": {"id": "minecraft:blue_ice"}}]},
    "cave_frostfire_lantern": {"type": "minecraft:block_column", "allowed_placement": AIR, "direction": "down",
        "prioritize_tip": True,
        "layers": [{"height": {"type": "minecraft:clamped_normal", "mean": 4.0, "deviation": 12.0, "min_inclusive": 4,
                               "max_inclusive": 50},
                    "provider": {"id": "minecraft:iron_chain", "properties": {"axis": "y", "waterlogged": "false"}}},
                   {"height": 1, "provider": {"id": "minecraft:soul_lantern",
                                              "properties": {"hanging": "true", "waterlogged": "false"}}}]},
    "cave_frostfire_hang": {"type": "minecraft:random_selector", "default": inline("terra:cave_frostfire_icicle"),
                            "features": [{"chance": 0.07, "feature": inline("terra:cave_frostfire_lantern")}]},
    "cave_frostfire_ceiling": patch_cfg(weighted(("minecraft:deepslate", 4), ("minecraft:blue_ice", 2)), "ceiling",
                                        "terra:cave_frostfire_hang", 0.4, depth=(1, 2), radius=(2, 5), edge=0.5,
                                        bottom=0.5, replaceable="#minecraft:base_stone_overworld"),
    "cave_frostfire_pillar": {"type": "minecraft:large_dripstone", "replaceable_blocks": "#minecraft:dripstone_replaceable_blocks",
        "floor_to_ceiling_search_range": 60, "column_radius": uniform(1, 2),
        "height_scale": {"type": "minecraft:uniform", "min_inclusive": 5.0, "max_exclusive": 13.0},
        "max_column_radius_to_cave_height_ratio": 1.0, "min_bluntness_for_wind": 0.0, "min_radius_for_wind": 0,
        "stalactite_bluntness": {"type": "minecraft:uniform", "min_inclusive": 0.9, "max_exclusive": 1.5},
        "stalagmite_bluntness": {"type": "minecraft:uniform", "min_inclusive": 0.9, "max_exclusive": 1.3},
        "wind_speed": {"type": "minecraft:uniform", "min_inclusive": 0.0, "max_exclusive": 0.4}},
    "cave_frostfire_pillar_huge": {"type": "minecraft:large_dripstone", "replaceable_blocks": "#minecraft:dripstone_replaceable_blocks",
        "floor_to_ceiling_search_range": 80, "column_radius": uniform(5, 13),
        "height_scale": {"type": "minecraft:uniform", "min_inclusive": 1.35, "max_exclusive": 4.0},
        "max_column_radius_to_cave_height_ratio": 0.4, "min_bluntness_for_wind": 0.0, "min_radius_for_wind": 0,
        "stalactite_bluntness": {"type": "minecraft:uniform", "min_inclusive": 0.2, "max_exclusive": 1.5},
        "stalagmite_bluntness": {"type": "minecraft:uniform", "min_inclusive": 0.2, "max_exclusive": 1.0},
        "wind_speed": {"type": "minecraft:uniform", "min_inclusive": 0.0, "max_exclusive": 0.3}},
    "cave_frostfire_columns": {"type": "minecraft:random_selector", "default": inline("terra:cave_frostfire_pillar_huge"),
                               "features": [{"chance": 0.965, "feature": inline("terra:cave_frostfire_pillar")}]},
    "cave_frostfire_sculk": {"type": "minecraft:sequence", "features": [
        {"feature": {"type": "minecraft:sculk_patch", "amount_per_charge": 500, "charge_count": 1, "growth_rounds": 8,
                     "spread_attempts": 64, "spread_rounds": 1}, "placement": []},
        {"feature": {"type": "minecraft:simple_block", "to_place": {"id": "minecraft:sculk_catalyst",
                                                                   "properties": {"bloom": "false"}}},
         "placement": [{"type": "minecraft:random_chance", "chance": 0.425},
                       {"type": "minecraft:block_predicate_filter", "predicate": {"type": "minecraft:has_sturdy_face",
                        "direction": "up", "offset": [0, -1, 0]}}]}]},
})
# Terralith's counts were per chunk over Y -64..30 (94 blocks); these are per 256 blocks of cave layer.
PLACED.update({
    "cave_frostfire_floor": cave_placed("terra:cave_frostfire_floor", 46, SCAN_FLOOR_32),
    "cave_frostfire_ceiling": cave_placed("terra:cave_frostfire_ceiling", 33, SCAN_CEILING_30),
    "cave_frostfire_columns": cave_placed("terra:cave_frostfire_columns", 7),
    "cave_frostfire_sculk": cave_placed("terra:cave_frostfire_sculk", 440),
})
biome("frostfire_caves", id=149, base="dripstone_caves", temp=0.8, downfall=0.4, colors={"water_color": "#1b46d0"},
      veg=[], top=None, cave=True, drop_attrs=["minecraft:audio/background_music"],
      steps={1: [], 2: ["minecraft:amethyst_geode", "minecraft:large_dripstone"],
             7: ["terra:cave_frostfire_columns", "terra:cave_frostfire_sculk", "terra:cave_frostfire_floor",
                 "terra:cave_frostfire_ceiling"]},
      spawns={"monster": [{"type": "minecraft:skeleton", "count": 4, "weight": 100},
                          {"type": "minecraft:stray", "count": 4, "weight": 50}],
              "underground_water_creature": [{"type": "minecraft:glow_squid", "count": uniform(4, 6), "weight": 10}]},
      attrs={"minecraft:visual/fog_color": "#000000",
             "minecraft:visual/ambient_particles": {"argument": [{"particle": {"type": "minecraft:dust_color_transition",
                 "from_color": [0.5, 1.0, 1.0], "to_color": [0.0, 0.0, 1.0], "scale": 1.0}, "probability": 0.001}],
                 "modifier": "append"}})
cave_biome("infested_caves", 151, 0.7,
           under=["terra:cave_rubble_floor", "terra:cave_monster_rooms"],
           ores=["terra:cave_ore_infested", "terra:cave_ore_infested_cobble"],
           veg=["terra:cave_cobwebs_sparse"])
cave_biome("spider_nest", 152, 0.7,
           under=["terra:cave_cobwebs_ceiling", "terra:cave_cobwebs_floor"],
           spawns={"monster": [{"type": "minecraft:spider", "count": 4, "weight": 200},
                               {"type": "minecraft:cave_spider", "count": 3, "weight": 160},
                               {"type": "minecraft:skeleton", "count": 4, "weight": 40},
                               {"type": "minecraft:zombie", "count": 4, "weight": 30},
                               {"type": "minecraft:creeper", "count": 4, "weight": 30}]})
cave_biome("copper_caves", 153, 0.8,
           under=["terra:cave_moss_floor", "terra:cave_moss_ceiling"],
           ores=["terra:cave_ore_copper", "terra:cave_ore_raw_copper", "terra:cave_ore_oxidized_copper",
                 "terra:cave_ore_weathered_copper"],
           veg=["terra:cave_glow_lichen_sparse"])
cave_biome("salt_caves", 154, 1.2, downfall=0.0,
           under=["terra:cave_salt_spikes", "terra:cave_salt_stalactites"],
           veg=["terra:cave_glow_lichen"])
cave_biome("mud_caves", 155, 0.8, downfall=0.9,
           under=["terra:cave_mud_floor", "terra:cave_mud_pools", "terra:cave_clay_pools", "terra:cave_mangrove_roots",
                  "terra:cave_hanging_roots"],
           veg=["terra:cave_glow_lichen_sparse"])
cave_biome("breakdown_chambers", 156, 0.7,
           under=["terra:cave_rubble_floor", "terra:cave_broken_ceiling", "terra:cave_boulders_cobble",
                  "terra:cave_boulders_stone", "terra:cave_boulders_andesite", "terra:cave_boulders_mossy"])
# ids 145 (lush caves) and 150 (thermal caves = vanilla sulfur caves) are vanilla's biomes, used as is.

# ---- v1.5 part two round B: water caves (world/CaveWater fills them to their region's water level, lays gravel, clay
# and sand lake beds; pipeline/CaveSites links some to rivers and opens cenotes over karst caves) ----
AXOLOTLS = [{"type": "minecraft:axolotl", "count": uniform(4, 6), "weight": 10}]
FEATURES.update({
    # Glowworms: thick glow lichen on cave ceilings only.
    "cave_glowworms": {"type": "minecraft:multiface_growth", "block": "minecraft:glow_lichen", "can_place_on_ceiling": True,
                       "can_place_on_wall": False, "search_range": 12, "chance_of_spreading": 0.9,
                       "can_be_placed_on": CAVE_FEATURES["cave_glow_lichen"]["can_be_placed_on"]},
    # Flowstone: calcite columns rising from karst cave floors and hanging from their ceilings.
    "cave_flowstone_up": column("up", ("minecraft:calcite", (1, 4))),
    "cave_flowstone_down": column("down", ("minecraft:calcite", (1, 3)), ({"id": "minecraft:pointed_dripstone",
        "properties": {"thickness": "tip", "vertical_direction": "down", "waterlogged": "false"}}, (0, 1))),
})
POINTED_SPREAD = {"type": "minecraft:offset", "x": {"type": "minecraft:clamped_normal", "deviation": 3.0, "max_inclusive": 10,
                  "mean": 0.0, "min_inclusive": -10}, "y": {"type": "minecraft:clamped_normal", "deviation": 0.6,
                  "max_inclusive": 2, "mean": 0.0, "min_inclusive": -2}, "z": {"type": "minecraft:clamped_normal",
                  "deviation": 3.0, "max_inclusive": 10, "mean": 0.0, "min_inclusive": -10}}
PLACED.update({
    "cave_glowworms": cave_placed("terra:cave_glowworms", 320, SCAN_CEILING),
    "cave_lake_moss_ceiling": cave_placed("minecraft:moss_patch_ceiling", 6, SCAN_CEILING),
    "cave_lake_clay_pools": cave_placed("minecraft:clay_pool_with_dripleaves", 6, SCAN_FLOOR),
    "cave_karst_dripstone_clusters": cave_placed("minecraft:dripstone_cluster", uniform(30, 60)),
    "cave_karst_large_dripstone": cave_placed("minecraft:large_dripstone", uniform(8, 30)),
    "cave_karst_pointed_dripstone": cave_placed("minecraft:pointed_dripstone", uniform(120, 180),
                                                post=[{"type": "minecraft:count", "count": uniform(1, 5)}, POINTED_SPREAD]),
    "cave_flowstone_up": cave_placed("terra:cave_flowstone_up", 30, SCAN_FLOOR),
    "cave_flowstone_down": cave_placed("terra:cave_flowstone_down", 40, SCAN_CEILING),
})
cave_biome("underground_lakes", 157, 0.6, downfall=0.9,
           under=["terra:cave_lake_moss_ceiling", "terra:cave_lake_clay_pools"],
           veg=["terra:cave_glow_lichen_sparse"], spawns={"axolotls": AXOLOTLS})
cave_biome("glowworm_grotto", 158, 0.6, downfall=1.0,
           under=["terra:cave_lake_clay_pools"],
           veg=["terra:cave_glowworms"], spawns={"axolotls": AXOLOTLS})
cave_biome("karst_caves", 159, 0.9, downfall=0.9,
           under=["terra:cave_karst_dripstone_clusters", "terra:cave_karst_large_dripstone",
                  "terra:cave_karst_pointed_dripstone", "terra:cave_flowstone_up", "terra:cave_flowstone_down",
                  "terra:cave_lake_clay_pools"],
           veg=["terra:cave_glow_lichen_sparse"], spawns={"axolotls": AXOLOTLS})

# ---- v1.5 part three round A: rare and very rare caves (CaveBiomes; shapes in CaveShapes, rock in world/CaveStone,
# lava in world/CaveWater, ancient cities kept to the deep dark under mountains) ----
def basalt(h):
    return ({"id": "minecraft:basalt", "properties": {"axis": "y"}}, h)


def amethyst(facing, *pairs):
    return {"type": "minecraft:weighted", "entries": [
        {"data": {"id": b, "properties": {"facing": facing, "waterlogged": "false"}}, "weight": w} for b, w in pairs]}


AMETHYST_KINDS = (("minecraft:amethyst_cluster", 3), ("minecraft:large_amethyst_bud", 2),
                  ("minecraft:medium_amethyst_bud", 1), ("minecraft:small_amethyst_bud", 1))
FEATURES.update({
    "cave_basalt_up": column("up", basalt((2, 8))),
    "cave_basalt_down": column("down", basalt((2, 7))),
    "cave_tall_basalt_up": column("up", basalt((4, 14))),
    "cave_tall_basalt_down": column("down", basalt((4, 12))),
    "cave_lavacicle": column("down", basalt((1, 2))),
    "cave_magma_floor": patch_cfg(weighted(("minecraft:magma_block", 2), ("minecraft:blackstone", 2), ("minecraft:basalt", 1)),
                                  "floor", "terra:cave_moss_carpet", 0.0, depth=(1, 2), replaceable="#minecraft:base_stone_overworld"),
    "cave_ore_blackstone": ore("minecraft:blackstone", 30),
    "cave_ore_amethyst": ore("minecraft:amethyst_block", 16),
    "cave_amethyst_floor": {"type": "minecraft:simple_block", "to_place": amethyst("up", *AMETHYST_KINDS)},
    "cave_amethyst_ceiling": {"type": "minecraft:simple_block", "to_place": amethyst("down", *AMETHYST_KINDS)},
    "cave_calcite_spike_up": column("up", ("minecraft:calcite", (3, 9)), (amethyst("up", ("minecraft:amethyst_cluster", 1)), 1)),
    "cave_calcite_spike_down": column("down", ("minecraft:calcite", (2, 7)), (amethyst("down", ("minecraft:amethyst_cluster", 1)), 1)),
    "cave_sand_floor": patch_cfg(weighted(("minecraft:sand", 3), ("minecraft:red_sand", 1), ("minecraft:sandstone", 1)),
                                 "floor", "terra:cave_moss_carpet", 0.0, depth=(1, 2), radius=(3, 6),
                                 replaceable="#minecraft:base_stone_overworld"),
})
PLACED.update({
    "cave_magma_floor": cave_placed("terra:cave_magma_floor", 50, SCAN_FLOOR),
    "cave_magma_floor_sparse": cave_placed("terra:cave_magma_floor", 15, SCAN_FLOOR),
    "cave_basalt_up": cave_placed("terra:cave_basalt_up", 30, SCAN_FLOOR),
    "cave_basalt_down": cave_placed("terra:cave_basalt_down", 30, SCAN_CEILING),
    "cave_tall_basalt_up": cave_placed("terra:cave_tall_basalt_up", 40, SCAN_FLOOR),
    "cave_tall_basalt_down": cave_placed("terra:cave_tall_basalt_down", 40, SCAN_CEILING),
    "cave_lavacicles": cave_placed("terra:cave_lavacicle", 60, SCAN_CEILING),
    "cave_ore_blackstone": cave_placed("terra:cave_ore_blackstone", 9),
    "cave_ore_magma_rich": cave_placed("terra:cave_ore_magma", 14),
    "cave_ore_amethyst": cave_placed("terra:cave_ore_amethyst", 6),
    "cave_crystal_geodes": cave_placed("minecraft:amethyst_geode", 2),
    "cave_amethyst_floor": cave_placed("terra:cave_amethyst_floor", 120, SCAN_FLOOR),
    "cave_amethyst_ceiling": cave_placed("terra:cave_amethyst_ceiling", 80, SCAN_CEILING),
    "cave_amethyst_floor_dense": cave_placed("terra:cave_amethyst_floor", 220, SCAN_FLOOR),
    "cave_amethyst_ceiling_dense": cave_placed("terra:cave_amethyst_ceiling", 160, SCAN_CEILING),
    "cave_calcite_spikes_up": cave_placed("terra:cave_calcite_spike_up", 30, SCAN_FLOOR),
    "cave_calcite_spikes_down": cave_placed("terra:cave_calcite_spike_down", 30, SCAN_CEILING),
    "cave_sand_floor": cave_placed("terra:cave_sand_floor", 60, SCAN_FLOOR),
})
MAGMA_CUBES = [{"type": "minecraft:magma_cube", "count": uniform(1, 3), "weight": 60}]
cave_biome("molten_cavern", 160, 2.0, downfall=0.0,
           under=["terra:cave_magma_floor", "terra:cave_basalt_up", "terra:cave_basalt_down"],
           ores=["terra:cave_ore_blackstone"], add_spawns={"monster": MAGMA_CUBES})
cave_biome("mantle_cave", 161, 2.0, downfall=0.0,
           under=["terra:cave_tall_basalt_up", "terra:cave_tall_basalt_down", "terra:cave_magma_floor_sparse"],
           ores=["terra:cave_ore_magma_rich", "terra:cave_ore_blackstone"], add_spawns={"monster": MAGMA_CUBES})
cave_biome("crystalline_chasm", 162, 0.8,
           under=["terra:cave_crystal_geodes", "terra:cave_amethyst_floor", "terra:cave_amethyst_ceiling",
                  "terra:cave_calcite_spikes_up", "terra:cave_calcite_spikes_down"],
           ores=["terra:cave_ore_amethyst"], veg=["terra:cave_glow_lichen_sparse"])
cave_biome("lava_tubes", 165, 1.5, downfall=0.0,
           under=["terra:cave_magma_floor_sparse", "terra:cave_lavacicles"], add_spawns={"monster": MAGMA_CUBES})
cave_biome("slot_canyon_caves", 166, 1.8, downfall=0.0, under=["terra:cave_sand_floor"])
cave_biome("glacier_caves", 167, -0.7, downfall=0.5,
           under=["terra:cave_icy_floor", "terra:cave_icicles", "terra:cave_ice_spikes"], spawns_from="snowy_plains")
cave_biome("giant_geode_hall", 173, 0.8,
           under=["terra:cave_amethyst_floor_dense", "terra:cave_amethyst_ceiling_dense"])
# Deep dark away from mountains: vanilla's deep dark, without ancient cities (they only generate under mountains, in
# vanilla's own deep_dark biome, id 163).
# The ancient city caverns' deep dark (Oct 27): the only biome ancient cities may start in (see TAG_REPLACE below), set
# by the biome source inside the giant cavern around every city site under a mountain (pipeline/CaveHalls.city).
biome("deep_dark_city", id=176, base="deep_dark", temp=0.8, downfall=0.4, colors={}, veg=[], top=None, cave=True)
biome("deep_dark_caves", id=175, base="deep_dark", temp=0.8, downfall=0.4, colors={}, veg=[], top=None, cave=True,
      no_tags=["has_structure/ancient_city"])

# ---- v1.5 part three round B: surface cuts (pipeline/CaveSites); these biomes fill the region under each site ----
FEATURES.update({
    "cave_sinkhole_floor": patch_cfg(weighted(("minecraft:grass_block", 3), ("minecraft:moss_block", 2)), "floor",
                                     "terra:cave_jungle_plants", 0.6, depth=(1, 2), radius=(5, 9)),
})
PLACED.update({
    "cave_sinkhole_floor": cave_placed("terra:cave_sinkhole_floor", 90, SCAN_FLOOR),
    "cave_sinkhole_mega_trees": cave_placed("minecraft:mega_jungle_tree", 3, SCAN_FLOOR, post=[survive("minecraft:jungle_sapling")]),
    "cave_sinkhole_trees": cave_placed("minecraft:jungle_tree", 10, SCAN_FLOOR, post=[survive("minecraft:jungle_sapling")]),
    "cave_wall_vines": cave_placed("minecraft:vines", 200),
})
cave_biome("canyon_ravine", 168, 0.7, under=["terra:cave_rubble_floor", "terra:cave_moss_floor"],
           veg=["terra:cave_wall_vines", "terra:cave_glow_lichen_sparse"])
cave_biome("sinkhole_jungle", 169, 0.95, downfall=0.9,
           under=["terra:cave_sinkhole_floor", "terra:cave_jungle_ceiling"],
           veg=["terra:cave_sinkhole_mega_trees", "terra:cave_sinkhole_trees", "terra:cave_jungle_bamboo",
                "terra:cave_jungle_bushes", "terra:cave_jungle_vines", "terra:cave_glow_lichen_sparse"],
           spawns_from="jungle")
cave_biome("blue_hole", 170, 0.8)
cave_biome("deep_shaft", 171, 0.7, under=["terra:cave_moss_ceiling"],
           veg=["terra:cave_wall_vines", "terra:cave_glow_lichen_sparse"],
           add_spawns={"ambient": [{"type": "minecraft:bat", "count": uniform(4, 8), "weight": 40}]})

# ---- v1.5 part three round C: the deep dark forest, the deep dark incursion and the giant selenite chamber. Each is a
# giant hall (pipeline/CaveHalls: exact, inside its region cell, its biome follows it); world/CaveStone paints the
# incursion's bare pillars (polished basalt, which sculk doesn't spread onto) and the selenite beams (calcite). ----
NO_SOIL = {"type": "minecraft:rule_based", "rules": []}   # trees keep the sculk and rock under their trunks
FOREST_GROUND = ["minecraft:sculk", "minecraft:deepslate", "minecraft:cobbled_deepslate", "minecraft:tuff",
                 "minecraft:stone", "minecraft:sculk_catalyst", "minecraft:gravel"]
# On the forest floor, not on a canopy: the floor block is ground, and 4 blocks down is still rock.
ON_FOREST_FLOOR = {"type": "minecraft:block_predicate_filter", "predicate": {"type": "minecraft:all_of", "predicates": [
    {"type": "minecraft:matching_blocks", "offset": [0, -1, 0], "blocks": FOREST_GROUND},
    {"type": "minecraft:solid", "offset": [0, -4, 0]}]}}
FEATURES.update({
    # Warped-style giant: a tall branching warped stem with broad layered teal canopies, lit here and there by
    # shroomlight.
    "cave_dd_warped_tree": {"type": "minecraft:tree", "below_trunk_provider": NO_SOIL, "decorators": [],
        "foliage_placer": {"type": "minecraft:fancy_foliage_placer", "height": 4, "offset": 4, "radius": 3},
        "foliage_provider": weighted(("minecraft:warped_wart_block", 14), ("minecraft:shroomlight", 1)),
        "ignore_vines": True,
        "minimum_size": {"type": "minecraft:two_layers_feature_size", "limit": 0, "min_clipped_height": 8, "upper_size": 0},
        "trunk_placer": {"type": "minecraft:fancy_trunk_placer", "base_height": 10, "height_rand_a": 8, "height_rand_b": 0},
        "trunk_provider": {"id": "minecraft:warped_stem", "properties": {"axis": "y"}}},
    # Dead pale tree: a bare pale oak trunk (vanilla's pale oak shape, no leaves), hanging pale moss from its stubs and
    # no moss carpet; a creaking heart inside some.
    "cave_dd_dead_pale_tree": {"type": "minecraft:tree", "below_trunk_provider": NO_SOIL,
        "decorators": [{"type": "minecraft:pale_moss", "leaves_probability": 0.0, "trunk_probability": 0.5,
                        "ground_probability": 0.0},
                       {"type": "minecraft:creaking_heart", "probability": 0.35}],
        "foliage_placer": {"type": "minecraft:blob_foliage_placer", "height": 0, "offset": 0, "radius": 0},
        "foliage_provider": {"id": "minecraft:pale_oak_log", "properties": {"axis": "y"}},
        "ignore_vines": True, "minimum_size": {"type": "minecraft:three_layers_feature_size", "upper_size": 2},
        "trunk_placer": {"type": "minecraft:dark_oak_trunk_placer", "base_height": 8, "height_rand_a": 3, "height_rand_b": 2},
        "trunk_provider": {"id": "minecraft:pale_oak_log", "properties": {"axis": "y"}}},
    # Sculk-canopy tree: a dark oak trunk under a flat deepslate-toned canopy of sculk, deepslate and tuff, verdant
    # froglights hanging beneath.
    "cave_dd_sculk_tree": {"type": "minecraft:tree", "below_trunk_provider": NO_SOIL,
        "decorators": [{"type": "minecraft:attached_to_leaves", "probability": 0.14, "exclusion_radius_xz": 1,
                        "exclusion_radius_y": 0, "required_empty_blocks": 1, "directions": ["down"],
                        "block_provider": {"id": "minecraft:verdant_froglight", "properties": {"axis": "y"}}}],
        "foliage_placer": {"type": "minecraft:dark_oak_foliage_placer", "offset": 0, "radius": 0},
        "foliage_provider": weighted(("minecraft:sculk", 6), ({"id": "minecraft:deepslate", "properties": {"axis": "y"}}, 3),
                                     ("minecraft:tuff", 1)),
        "ignore_vines": True, "minimum_size": {"type": "minecraft:three_layers_feature_size", "upper_size": 2},
        "trunk_placer": {"type": "minecraft:dark_oak_trunk_placer", "base_height": 7, "height_rand_a": 3, "height_rand_b": 2},
        "trunk_provider": {"id": "minecraft:dark_oak_log", "properties": {"axis": "y"}}},
    # The forest floor: sculk carpets over the deepslate.
    "cave_dd_forest_floor": patch_cfg("minecraft:sculk", "floor", "terra:cave_moss_carpet", 0.0, depth=(1, 2), radius=(4, 8),
                                      replaceable="#minecraft:sculk_replaceable_world_gen"),
    # Shriekers that can summon wardens (vanilla's world generation only makes harmless ones outside ancient cities).
    "cave_summoning_shrieker": {"type": "minecraft:simple_block", "to_place": {"id": "minecraft:sculk_shrieker",
        "properties": {"can_summon": "true", "shrieking": "false", "waterlogged": "false"}}},
})
ON_SCULK = {"type": "minecraft:block_predicate_filter",
            "predicate": {"type": "minecraft:matching_blocks", "offset": [0, -1, 0], "blocks": ["minecraft:sculk"]}}
PLACED.update({
    "cave_dd_forest_floor": cave_placed("terra:cave_dd_forest_floor", 70, SCAN_FLOOR),
    "cave_dd_warped_trees": cave_placed("terra:cave_dd_warped_tree", 30, SCAN_FLOOR, post=[ON_FOREST_FLOOR]),
    "cave_dd_sculk_trees": cave_placed("terra:cave_dd_sculk_tree", 30, SCAN_FLOOR, post=[ON_FOREST_FLOOR]),
    "cave_dd_dead_pale_trees": cave_placed("terra:cave_dd_dead_pale_tree", 20, SCAN_FLOOR, post=[ON_FOREST_FLOOR]),
    "cave_incursion_sculk": cave_placed("minecraft:sculk_patch_deep_dark", 200),
    "cave_summoning_shriekers": cave_placed("terra:cave_summoning_shrieker", 40, SCAN_FLOOR, post=[ON_SCULK]),
})
# Deep dark forest and incursion: vanilla's deep dark (sculk, music, no mob spawns) without ancient cities. No
# Terra-placed sensors or shriekers in the forest; the incursion's shriekers can summon wardens (no natural wardens).
biome("deep_dark_forest", id=164, base="deep_dark", temp=0.8, downfall=0.4, colors={}, top=None, cave=True,
      veg=["terra:cave_dd_warped_trees", "terra:cave_dd_sculk_trees", "terra:cave_dd_dead_pale_trees"],
      add_steps={7: ["terra:cave_dd_forest_floor"]}, no_tags=["has_structure/ancient_city"])
biome("deep_dark_incursion", id=172, base="deep_dark", temp=0.8, downfall=0.4, colors={}, top=None, cave=True,
      veg=["terra:cave_summoning_shriekers"], add_steps={7: ["terra:cave_incursion_sculk"]},
      no_tags=["has_structure/ancient_city"])
# Giant selenite chamber: hot and dark (no glow lichen), magma patches and amethyst on the floor and on the beams.
biome("giant_selenite_beams", id=174, base="dripstone_caves", temp=2.0, downfall=0.0, colors={}, veg=[], top=None,
      steps={2: ["minecraft:amethyst_geode"], 7: ["terra:cave_magma_floor_sparse", "terra:cave_amethyst_floor"], 9: []},
      add_steps={6: ["terra:cave_ore_amethyst"]}, drop_attrs=["minecraft:audio/background_music"], cave=True)

# Tags this script replaces outright (vanilla's own members dropped): ancient cities only in the city caverns.
TAG_REPLACE = {"has_structure/ancient_city": ["terra:deep_dark_city"]}


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def load(path):
    with open(path) as f:
        return json.load(f)


def main():
    global V12, MANAGED
    V12 = [f"terra:{n}" for n in B]
    MANAGED = V12 + ["minecraft:mushroom_fields"]
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
        if d.get("spawns_from"):
            # Mob spawns (and the no-patrols flag) from another vanilla biome, e.g. mushroom fields' mooshrooms only.
            src = load(f"{VANILLA}/worldgen/biome/{d['spawns_from']}.json").get("attributes", {})
            attrs = dict(out.get("attributes", {}))
            for key in ("minecraft:gameplay/natural_mob_spawns", "minecraft:gameplay/can_pillager_patrol_spawn"):
                if key in src:
                    attrs[key] = src[key]
                else:
                    attrs.pop(key, None)
            out["attributes"] = attrs
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
        for k, lst in d.get("steps", {}).items():
            steps[k] = list(lst)
        for k, lst in d.get("add_steps", {}).items():
            steps[k] = steps[k] + [f for f in lst if f not in steps[k]]
        if d.get("spawns") or d.get("add_spawns"):
            attrs = json.loads(json.dumps(out.get("attributes", {})))
            cats = attrs["minecraft:gameplay/natural_mob_spawns"]["argument"]["spawns_by_category"]
            cats.update(d.get("spawns", {}))
            for cat, extra in d.get("add_spawns", {}).items():
                cats[cat] = cats.get(cat, []) + extra
            out["attributes"] = attrs
        if d.get("drop_attrs"):
            out["attributes"] = {k: v for k, v in out.get("attributes", {}).items() if k not in d["drop_attrs"]}
        if d.get("attrs"):
            out["attributes"] = {**out.get("attributes", {}), **d["attrs"]}
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
        for t in d.get("no_tags", []):
            members.get(t, set()).discard(f"terra:{name}")
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
        if rel in TAG_REPLACE:
            write(path, {"replace": True, "values": TAG_REPLACE[rel]})
            continue
        if not allv:
            if os.path.exists(path):
                os.remove(path)
            continue
        write(path, {"replace": False, "values": allv})

    # Surface rules: replace this script's entries in Terra's material rule.
    mr = load(MATERIAL_RULE)
    top_seq, under_seq = find_surface_sequences(mr)
    strip(top_seq)
    strip(under_seq)
    strip(mr["sequence"])
    top_rules, under_rules, deep_before, deep_after = [], [], [], []
    for bid, body in EXTRA_TOP:
        top_rules.append(biome_rule(bid, y_guard(body)))
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
        if d.get("deep"):
            # The top `depth` blocks of every column (so cliff faces show it too): "before" replaces the surface as
            # well (painted mountains), "after" only what lies under the usual surface and soil (white cliffs).
            depth, body, where = d["deep"]
            rule_ = biome_rule(bid, {"type": "minecraft:condition", "if_true": {
                "type": "minecraft:stone_depth", "offset": depth, "surface_type": "floor", "add_surface_depth": False,
                "secondary_depth_range": 0}, "then_run": body})
            (deep_before if where == "before" else deep_after).append(rule_)
    # Before the final grass/dirt defaults.
    top_seq[-2:-2] = top_rules
    under_seq[-1:-1] = under_rules
    seq = mr["sequence"]
    i0 = next(i for i, r in enumerate(seq) if is_depth(r, 0))
    seq[i0:i0] = deep_before
    i4 = next(i for i, r in enumerate(seq) if is_depth(r, 4))
    seq[i4 + 1:i4 + 1] = deep_after
    write(MATERIAL_RULE, mr)
    # Vanilla's icebergs are replaced by Terra's iceberg fields (world/Icebergs): never place them.
    for f in ("iceberg_packed", "iceberg_blue"):
        write(f"{RES}/minecraft/worldgen/placed_feature/{f}.json",
              {"feature": f"minecraft:{f}", "placement": [{"type": "minecraft:count", "count": 0}]})
    print(f"{len(B)} biomes, {len(FEATURES) + len(SELECTORS)} features, {len(PLACED)} placed features, "
          f"{len(members)} tag files")


# Vanilla biomes Terra's biome source uses (TerrainDiffusionBiomeSource), and Terra's own non-generated biomes.
VANILLA_USED = """badlands bamboo_jungle beach birch_forest cherry_grove cold_ocean dappled_forest deep_cold_ocean
deep_frozen_ocean deep_lukewarm_ocean deep_ocean desert flower_forest forest frozen_ocean frozen_peaks frozen_river
grove jagged_peaks jungle lukewarm_ocean mangrove_swamp meadow ocean old_growth_birch_forest old_growth_pine_taiga
old_growth_spruce_taiga pale_garden plains river savanna savanna_plateau snowy_beach snowy_plains snowy_slopes
snowy_taiga sparse_jungle stony_peaks stony_shore sunflower_plains swamp taiga warm_ocean windswept_forest
windswept_gravelly_hills windswept_hills windswept_savanna mushroom_fields dripstone_caves lush_caves
sulfur_caves deep_dark""".split()
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
    new = sorted({f for d in B.values() for f in d["veg"] + d.get("lakes", [])
                  + [g for lst in d.get("steps", {}).values() for g in lst]
                  + [g for lst in d.get("add_steps", {}).values() for g in lst]} - set(rank))
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


def is_depth(r, offset):
    return (isinstance(r, dict) and r.get("type") == "minecraft:condition"
            and r["if_true"].get("type") == "minecraft:stone_depth" and r["if_true"]["offset"] == offset)


def y_guard(body):
    return {"type": "minecraft:condition", "if_true": {"type": "minecraft:y_above", "anchor": {"absolute": 62},
            "surface_depth_multiplier": 0, "add_stone_depth": False}, "then_run": body}


def strip(seq):
    seq[:] = [r for r in seq if not (isinstance(r, dict) and r.get("type") == "minecraft:condition"
                                     and r["if_true"].get("type") == "minecraft:biome"
                                     and set(r["if_true"]["biome_is"]) <= set(MANAGED))]


ABOVE_PRELIMINARY = {"type": "minecraft:above_preliminary_surface"}


def find_surface_sequences(mr):
    """The two per-biome sequences in Terra's rule: under stone_depth offset 0 (top) and offset 4 (under). Each sits
    behind an above_preliminary_surface guard (added here if missing) so grass, dirt, sand and the other surface
    layers stay off cave floors (caves plan v1.5; the surface level is reported 3 blocks down so the guard reaches the
    deepest dirt layer, see TerrainDiffusionSurfaceLevelFunction)."""
    found = {}
    for r in mr["sequence"]:
        if isinstance(r, dict) and r.get("type") == "minecraft:condition" and r["if_true"].get("type") == "minecraft:stone_depth" \
                and r["if_true"]["offset"] in (0, 4):
            body = r["then_run"]
            if not (body.get("type") == "minecraft:condition" and body["if_true"] == ABOVE_PRELIMINARY):
                body = {"type": "minecraft:condition", "if_true": ABOVE_PRELIMINARY, "then_run": body}
                r["then_run"] = body
            found[r["if_true"]["offset"]] = body["then_run"]["sequence"]
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
        for f in d["veg"] + d.get("lakes", []) + [g for lst in list(d.get("steps", {}).values())
                                                  + list(d.get("add_steps", {}).values()) for g in lst]:
            if not exists(f, "placed_feature"):
                missing.append(f"{name}: placed {f}")
        if not os.path.exists(f"{VANILLA}/worldgen/biome/{d['base']}.json"):
            missing.append(f"{name}: base {d['base']}")
    if missing:
        raise SystemExit("missing references:\n  " + "\n  ".join(missing))


if __name__ == "__main__":
    main()
