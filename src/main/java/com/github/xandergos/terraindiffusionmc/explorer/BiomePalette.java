package com.github.xandergos.terraindiffusionmc.explorer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Names and map colours for the biome ids the classifier produces, for the explorer's Biomes map. Add a row here
 * whenever a new biome id is added to {@code BiomeClassifier} / {@code TerrainDiffusionBiomeSource}; an id missing here
 * shows in magenta on the map.
 */
public final class BiomePalette {

    /** Pseudo-id for the map's river and lake overlay (rivers carry their parent biome in game). */
    public static final int WATER = 255;

    private static final Map<Integer, Entry> TABLE = new LinkedHashMap<>();

    public record Entry(String name, int rgb) {
    }

    private static void put(int id, String name, int rgb) {
        TABLE.put(id, new Entry(name, rgb));
    }

    static {
        // Grassland
        put(1, "Plains", 0x8DB360);
        put(2, "Sunflower Plains", 0xC9D86A);
        put(29, "Meadow", 0x7FD08C);
        put(31, "Grove (dry grassland)", 0xA9A867);
        put(3, "Snowy Plains", 0xF2F5F8);
        // Forest
        put(8, "Forest", 0x2F7D32);
        put(108, "Sparse Forest", 0x5E9A45);
        put(9, "Flower Forest", 0xC25DA8);
        put(10, "Birch Forest", 0x7FB58A);
        put(12, "Old Growth Birch Forest", 0xA8D3AE);
        put(11, "Dark Forest", 0x2C3D16);
        put(36, "Pale Garden", 0xC9CDC2);
        put(37, "Dappled Forest", 0xD98A2B);
        put(30, "Cherry Grove", 0xF7A8CC);
        // Taiga
        put(15, "Taiga", 0x1E6B5C);
        put(115, "Sparse Taiga", 0x4E8C7B);
        put(13, "Old Growth Pine Taiga", 0x5C6B3A);
        put(14, "Old Growth Spruce Taiga", 0x3E5240);
        put(16, "Snowy Taiga", 0x6E9C94);
        put(116, "Sparse Snowy Taiga", 0x9DBDB6);
        // Warm
        put(5, "Desert", 0xF2B04A);
        put(17, "Savanna", 0xBDB25F);
        put(18, "Savanna Plateau", 0x9C8F4E);
        put(22, "Windswept Savanna", 0xE0D58F);
        put(23, "Jungle", 0x3E8E0B);
        put(24, "Sparse Jungle", 0x7AA52A);
        put(25, "Bamboo Jungle", 0x9DBB1E);
        put(6, "Swamp", 0x4D6B3A);
        put(26, "Badlands", 0xD94515);
        // Mountains
        put(19, "Windswept Hills", 0x707A70);
        put(20, "Windswept Gravelly Hills", 0x9A9A9A);
        put(21, "Windswept Forest", 0x4F6E4A);
        put(32, "Snowy Slopes", 0xDCE6EE);
        put(33, "Frozen Peaks", 0xA9C8E6);
        put(34, "Jagged Peaks", 0xE6E6FF);
        put(35, "Stony Peaks", 0x858585);
        // Real-world biomes (v1.2)
        put(50, "Tundra", 0xA7A983);
        put(51, "Polar Desert", 0xBDB8AE);
        put(52, "Treeline", 0x6F8F6A);
        put(53, "Boreal Forest", 0x2E5E4A);
        put(54, "Siberian Taiga", 0x55734A);
        put(55, "Birch Taiga", 0x6FA07A);
        put(56, "Taiga Mountain", 0x4C6A5C);
        put(57, "Taiga Clearing", 0x9CC27A);
        put(58, "Bog", 0x6B5E3A);
        put(59, "Moorland", 0x8A6F7A);
        put(60, "Temperate Rainforest", 0x1F5A2A);
        put(61, "Aspen Parkland", 0xD6C24A);
        put(62, "Tallgrass Prairie", 0xB4C85A);
        put(63, "Steppe", 0xCFC28A);
        put(64, "Marsh", 0x5E8F6A);
        put(65, "Riverside Forest", 0x3F8F3A);
        put(66, "Floodplain Forest", 0x2F7A50);
        put(67, "Redwoods", 0x8A3A2A);
        put(68, "Mediterranean Scrub", 0xB09A5A);
        put(69, "Forested Highland", 0x4A7A4A);
        put(70, "Temperate Highland", 0x8FB88A);
        put(71, "Rocky Desert", 0xC89A6A);
        put(72, "Cloud Forest", 0x2FA06A);
        put(73, "Tropical Dry Forest", 0xA0A040);
        put(74, "Aspen Grove", 0xE8C840);
        put(75, "Shingle Beach", 0xB8B2A0);
        put(76, "Salt Marsh", 0x7FA06A);
        put(77, "Tidal Flats", 0x8C8466);
        put(78, "White Cliffs", 0xF0EEE4);
        put(79, "Painted Mountains", 0xC8603A);
        put(80, "Desert Oasis", 0x30C0A0);
        put(81, "Blooming Plateau", 0xE070C0);
        put(82, "Blooming Valley", 0xF09AD0);
        put(83, "Lush Valley", 0x40C850);
        put(84, "Salt Flats", 0xFFFFFF);
        put(85, "Arctic", 0xE8F2FA);
        put(133, "Mushroom Fields", 0xA070B0);
        // Shores
        put(130, "Beach", 0xF5E08A);
        put(131, "Snowy Beach", 0xF2EEDC);
        put(132, "Stony Shore", 0xA2A290);
        put(134, "Mangrove Swamp", 0x6B7F2E);
        put(120, "River", 0x3F76E4);
        put(121, "Frozen River", 0x9FB8E8);
        // Oceans
        put(41, "Warm Ocean", 0x2FB8D6);
        put(42, "Lukewarm Ocean", 0x2688BD);
        put(43, "Deep Lukewarm Ocean", 0x175C8C);
        put(44, "Ocean", 0x2152A8);
        put(45, "Deep Ocean", 0x14327A);
        put(46, "Cold Ocean", 0x3D519A);
        put(47, "Deep Cold Ocean", 0x283770);
        put(48, "Frozen Ocean", 0x7894C8);
        put(49, "Deep Frozen Ocean", 0x525E92);
        put(WATER, "Rivers and lakes (overlay)", 0x4A8CFF);
    }

    private BiomePalette() {
    }

    /**
     * Command-style key for a biome id ("Old Growth Birch Forest" → {@code old_growth_birch_forest}; anything in
     * brackets dropped), or null for the water overlay.
     */
    public static String key(int id) {
        Entry e = TABLE.get(id);
        if (e == null || id == WATER) return null;
        return e.name().replaceAll("\\s*\\(.*\\)", "").trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
    }

    /** Key → id for every biome on the map except the water overlay, in table order. */
    public static Map<String, Integer> keys() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Integer id : TABLE.keySet()) {
            String k = key(id);
            if (k != null) out.put(k, id);
        }
        return out;
    }

    /** Display name for an id. */
    public static String name(int id) {
        Entry e = TABLE.get(id);
        return e == null ? "biome " + id : e.name();
    }

    /** Colour as 0xRRGGBB; magenta for an id with no entry. */
    public static int rgb(int id) {
        Entry e = TABLE.get(id);
        return e == null ? 0xFF00FF : e.rgb();
    }

    /** The whole table for the page's legend: id → {name, color "#rrggbb"}. */
    public static Map<String, Map<String, String>> asJson() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, Entry> e : TABLE.entrySet()) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", e.getValue().name());
            m.put("color", String.format("#%06x", e.getValue().rgb()));
            out.put(String.valueOf(e.getKey()), m);
        }
        return out;
    }
}
