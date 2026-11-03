package at.blaulix.blaulixmap.map;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ordnet Block-IDs Kartenfarben zu. Rueckgabewerte: {@link #SKIP} (wird uebersprungen, z. B. Luft, Gras,
 * Glas), {@link #WATER} (Wasser, wird je nach Tiefe eingefaerbt) oder 0xFFRRGGBB fuer feste Bloecke.
 */
public final class BlockColors {
    public static final int SKIP = 0;
    public static final int WATER = 1;

    private static final Map<String, Integer> EXACT = new HashMap<>();
    private static final Map<String, Integer> CACHE = new ConcurrentHashMap<>();

    private static void c(String name, int rgb) { EXACT.put(name, 0xFF000000 | rgb); }

    static {
        c("grass_block", 0x62A044); c("dirt", 0x866043); c("coarse_dirt", 0x77553B); c("podzol", 0x5B4426);
        c("mycelium", 0x6F6265); c("rooted_dirt", 0x90664A); c("dirt_path", 0x947A44); c("farmland", 0x6E4A2E);
        c("mud", 0x3C3A3F); c("packed_mud", 0x8A6A52); c("clay", 0xA0A6B3); c("gravel", 0x837F7E);
        c("sand", 0xDBCF9A); c("suspicious_sand", 0xDBCF9A); c("red_sand", 0xBE6621);
        c("sandstone", 0xDCCF9B); c("red_sandstone", 0xB5601F);
        c("stone", 0x7D7D7D); c("cobblestone", 0x797979); c("mossy_cobblestone", 0x667A5E);
        c("deepslate", 0x505055); c("tuff", 0x6C6D64); c("andesite", 0x888888); c("diorite", 0xBDBDBD);
        c("granite", 0x9A6B58); c("calcite", 0xE0E0DA); c("dripstone_block", 0x866B5C); c("basalt", 0x4B4B52);
        c("smooth_basalt", 0x48484E); c("blackstone", 0x2C2831); c("bedrock", 0x333333);
        c("netherrack", 0x6F3535); c("crimson_nylium", 0x8A2A2A); c("warped_nylium", 0x2B7567);
        c("soul_sand", 0x514033); c("soul_soil", 0x4A3A2F); c("magma_block", 0x8E3F1A);
        c("nether_wart_block", 0x7A1010); c("warped_wart_block", 0x167E86); c("shroomlight", 0xF2994A);
        c("end_stone", 0xDDDFA5); c("obsidian", 0x15121F); c("crying_obsidian", 0x2A0B55);
        c("snow", 0xF8FEFE); c("snow_block", 0xF8FEFE); c("powder_snow", 0xF8FEFE);
        c("ice", 0x9DBEFA); c("packed_ice", 0x8DB0F0); c("blue_ice", 0x74A8FD); c("frosted_ice", 0x9DBEFA);
        c("moss_block", 0x596D2D); c("moss_carpet", 0x596D2D); c("mangrove_roots", 0x4E3D28);
        c("muddy_mangrove_roots", 0x4A4033); c("lava", 0xE5560E); c("hay_block", 0xC79C10);
        c("pumpkin", 0xC77716); c("carved_pumpkin", 0xC77716); c("jack_o_lantern", 0xE3A32A);
        c("melon", 0x6F9A1E); c("cactus", 0x0F7A24); c("sponge", 0xC3C148); c("wet_sponge", 0xA1A33A);
        c("glowstone", 0xFFBC5E); c("sea_lantern", 0xDDE8DA); c("amethyst_block", 0x8561B8);
        c("budding_amethyst", 0x8561B8); c("slime_block", 0x70C05C); c("honey_block", 0xE8A62C);
        c("bone_block", 0xE1DDC8); c("tnt", 0xC03A2B); c("bookshelf", 0xA0814F);
        c("mushroom_stem", 0xCFC9BD); c("red_mushroom_block", 0xC83B38); c("brown_mushroom_block", 0x947157);
        c("spawner", 0x1B2733); c("pale_moss_block", 0x7A8571); c("pale_moss_carpet", 0x7A8571);
        c("resin_block", 0xE6781D); c("creaking_heart", 0x6B5A4B);
    }

    /** Holzarten (Reihenfolge wichtig: laengere/spezifischere Namen zuerst). */
    private static final String[][] WOODS = {
            {"dark_oak", "4B3319"}, {"pale_oak", "E4D9D3"}, {"oak", "B8945F"}, {"mangrove", "76363C"},
            {"cherry", "E3B3A9"}, {"spruce", "7A5A34"}, {"birch", "D7C186"}, {"jungle", "B88764"},
            {"acacia", "BA683B"}, {"bamboo", "C9B04D"}, {"crimson", "7E3A56"}, {"warped", "3A8E8C"},
    };

    private static final String[][] LEAVES = {
            {"dark_oak", "3C6B1F"}, {"pale_oak", "8FA086"}, {"oak", "4C7A28"}, {"mangrove", "5E8A2D"},
            {"cherry", "F0AFC4"}, {"spruce", "3B5F3A"}, {"birch", "6A8F3F"}, {"jungle", "3F8F1E"},
            {"acacia", "5F8A24"}, {"azalea", "607B2D"},
    };

    /** Farbnamen (light_* vor den Grundfarben pruefen). */
    private static final String[][] DYES = {
            {"light_blue", "3AAFD9"}, {"light_gray", "8E8E86"}, {"white", "E9ECEC"}, {"orange", "F07613"},
            {"magenta", "BD44B3"}, {"yellow", "F8C627"}, {"lime", "70B919"}, {"pink", "ED8DAC"},
            {"gray", "3E4447"}, {"cyan", "158991"}, {"purple", "792AAC"}, {"blue", "35399D"},
            {"brown", "724728"}, {"green", "546D1B"}, {"red", "A12722"}, {"black", "141519"},
    };

    /** Nicht darzustellende Bloecke (Pflanzen, Deko, Transparentes). */
    private static final String[] SKIP_WORDS = {
            "fern", "flower", "poppy", "dandelion", "tulip", "orchid", "allium", "bluet", "cornflower",
            "lily", "rose", "peony", "lilac", "sapling", "torch", "rail", "ladder", "vine", "fire", "lantern",
            "sign", "button", "lever", "tripwire", "bush", "dripleaf", "sweet_berry", "petals", "wildflowers",
            "leaf_litter", "hanging_roots", "spore_blossom", "chain", "bell", "cobweb", "string", "barrier",
            "structure_void", "skull", "_head", "glass", "wheat", "carrots", "potatoes", "beetroots",
            "melon_stem", "pumpkin_stem", "nether_wart", "pointed_dripstone", "candle", "flower_pot", "pressure_plate", "sugar_cane",
            "bamboo_sapling", "frogspawn", "turtle_egg", "sniffer_egg", "end_rod", "lightning_rod", "banner",
            "redstone_wire", "repeater", "comparator", "cocoa", "firefly", "eyeblossom", "twisting_vines",
            "weeping_vines", "crimson_roots", "warped_roots", "nether_sprouts", "open_eyeblossom",
    };

    private BlockColors() {}

    /** Klassifiziert einen Block-Namen (mit oder ohne "minecraft:"-Praefix). */
    public static int of(String id) {
        if (id == null) return SKIP;
        return CACHE.computeIfAbsent(id, BlockColors::compute);
    }

    private static int compute(String id) {
        String n = id.toLowerCase(Locale.ROOT);
        int colon = n.indexOf(':');
        if (colon >= 0) n = n.substring(colon + 1);

        Integer exact = EXACT.get(n);
        if (exact != null) return exact;

        if (n.equals("air") || n.equals("cave_air") || n.equals("void_air") || n.equals("light")) return SKIP;
        if (n.equals("water") || n.equals("bubble_column") || n.contains("kelp") || n.contains("seagrass")) return WATER;
        if (n.equals("grass_block")) return 0xFF62A044;
        if (n.equals("bamboo") ) return SKIP;
        if (n.contains("mushroom") && !n.contains("block") && !n.contains("stem")) return SKIP;
        if (n.equals("short_grass") || n.equals("tall_grass") || n.equals("grass") || n.equals("dead_bush")) return SKIP;

        // Blaetter
        if (n.endsWith("_leaves")) {
            for (String[] l : LEAVES) if (n.contains(l[0])) return 0xFF000000 | Integer.parseInt(l[1], 16);
            return 0xFF4C7A28;
        }
        if (n.contains("azalea")) return 0xFF000000 | 0x607B2D;

        for (String w : SKIP_WORDS) if (n.contains(w)) return SKIP;

        // Holz
        if (n.contains("planks") || n.contains("log") || n.contains("wood") || n.contains("hyphae")
                || n.contains("stem") || n.endsWith("_stairs") || n.endsWith("_slab") || n.endsWith("_fence")
                || n.endsWith("_door") || n.endsWith("_trapdoor") || n.endsWith("_fence_gate")) {
            for (String[] w : WOODS) if (n.contains(w[0])) return 0xFF000000 | Integer.parseInt(w[1], 16);
        }

        // Farbige Bloecke (Wolle, Beton, Terrakotta, ...)
        if (n.contains("wool") || n.contains("concrete") || n.contains("terracotta") || n.contains("carpet")
                || n.contains("shulker") || n.contains("bed")) {
            for (String[] d : DYES) {
                if (n.contains(d[0])) {
                    int rgb = Integer.parseInt(d[1], 16);
                    if (n.contains("terracotta")) rgb = darken(rgb, 0.7f);
                    return 0xFF000000 | rgb;
                }
            }
            if (n.contains("terracotta")) return 0xFF985F45;
        }

        // Material-Schluesselwoerter (spezifische vor allgemeinen Namen)
        if (n.contains("deepslate")) return 0xFF505055;
        if (n.contains("nether_brick")) return 0xFF2D1519;
        if (n.contains("blackstone")) return 0xFF2C2831;
        if (n.contains("end_stone")) return 0xFFDDDFA5;
        if (n.contains("sandstone")) return 0xFFDCCF9B;
        if (n.contains("redstone")) return 0xFFAA0F01;
        if (n.contains("stone") || n.contains("cobble")) return 0xFF7D7D7D;
        if (n.contains("brick")) return 0xFF96594B;
        if (n.contains("copper")) return 0xFFC06B4E;
        if (n.contains("iron")) return 0xFFD8D8D8;
        if (n.contains("gold")) return 0xFFF6D03C;
        if (n.contains("diamond")) return 0xFF62EDE4;
        if (n.contains("emerald")) return 0xFF2CC85A;
        if (n.contains("lapis")) return 0xFF1F4FA8;
        if (n.contains("coal")) return 0xFF2B2B2B;
        if (n.contains("quartz")) return 0xFFEBE5DE;
        if (n.contains("prismarine")) return 0xFF63A096;
        if (n.contains("purpur")) return 0xFFA97DA9;
        if (n.contains("sand")) return 0xFFDBCF9A;
        if (n.contains("dirt")) return 0xFF866043;
        if (n.contains("ice")) return 0xFF9DBEFA;
        if (n.contains("snow")) return 0xFFF8FEFE;
        if (n.contains("lava")) return 0xFFE5560E;

        // Fallback: stabile Farbe aus dem Namen
        int h = n.hashCode();
        float hue = ((h >>> 3) & 0x3FF) / 1024f;
        return 0xFF000000 | (java.awt.Color.HSBtoRGB(hue, 0.35f, 0.65f) & 0xFFFFFF);
    }

    private static int darken(int rgb, float f) {
        int r = (int) (((rgb >> 16) & 255) * f), g = (int) (((rgb >> 8) & 255) * f), b = (int) ((rgb & 255) * f);
        return (r << 16) | (g << 8) | b;
    }
}
