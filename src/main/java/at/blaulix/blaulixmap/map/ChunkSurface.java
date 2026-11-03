package at.blaulix.blaulixmap.map;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Berechnet aus den NBT-Daten eines Chunks die Oberflaechen-Farben (Vogelperspektive).
 * Es werden keine Heightmaps benoetigt: jede Spalte wird von oben nach unten abgetastet, bis der erste
 * "feste" Block gefunden wird. Wasser wird nach Tiefe eingefaerbt. Laeuft komplett in Hintergrund-Threads.
 */
final class ChunkSurface {
    private ChunkSurface() {}

    /** Wasserfarbe fuer die Tiefen-Einfaerbung. */
    private static final int WATER_RGB = 0x2D5FD6;

    private record Section(int y, int[] colors, int[] idx) {}

    /**
     * @param maxY  hoechste Y-Hoehe, die betrachtet wird (Nether: unterhalb der Bedrock-Decke)
     * @param px    Ziel-Pixel (ARGB), Ausgabe
     * @param hs    Ziel-Hoehen (fuer die Schattierung); Integer.MIN_VALUE = keine Schattierung
     * @param ox,oz Pixel-Offset des Chunks in der Kachel
     */
    @SuppressWarnings("unchecked")
    static void render(Map<String, Object> chunk, int maxY, int[] px, int[] hs, int ox, int oz, int stride) {
        Object so = chunk.get("sections");
        if (!(so instanceof List<?> list)) return;

        List<Section> sections = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> sm)) continue;
            Map<String, Object> s = (Map<String, Object>) sm;
            if (!(s.get("Y") instanceof Number yN)) continue;
            if (!(s.get("block_states") instanceof Map<?, ?> bsm)) continue;
            Map<String, Object> bs = (Map<String, Object>) bsm;
            if (!(bs.get("palette") instanceof List<?> pal) || pal.isEmpty()) continue;

            int[] colors = new int[pal.size()];
            boolean any = false;
            for (int i = 0; i < colors.length; i++) {
                String name = null;
                if (pal.get(i) instanceof Map<?, ?> pe) {
                    Object nm = pe.get("Name");
                    if (nm == null) nm = pe.get("id"); // neuere Formate
                    if (nm instanceof String str) name = str;
                }
                colors[i] = BlockColors.of(name);
                if (colors[i] != BlockColors.SKIP) any = true;
            }
            if (!any) continue; // reine Luft-Section ueberspringen (schneller)

            int[] idx = null;
            if (colors.length > 1) {
                if (!(bs.get("data") instanceof long[] data)) continue;
                idx = decode(data, colors.length);
                if (idx == null) continue;
            }
            sections.add(new Section(yN.intValue(), colors, idx));
        }
        if (sections.isEmpty()) return;
        sections.sort(Comparator.comparingInt(Section::y).reversed());

        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int solid = 0, top = Integer.MIN_VALUE, waterTop = Integer.MIN_VALUE, depth = 0;
                scan:
                for (Section sec : sections) {
                    for (int ly = 15; ly >= 0; ly--) {
                        int y = sec.y * 16 + ly;
                        if (y > maxY) continue;
                        int c = sec.idx == null ? sec.colors[0] : sec.colors[sec.idx[(ly << 8) | (lz << 4) | lx]];
                        if (c == BlockColors.SKIP) continue;
                        if (c == BlockColors.WATER) {
                            if (waterTop == Integer.MIN_VALUE) waterTop = y;
                            depth++;
                            continue;
                        }
                        solid = c;
                        top = y;
                        break scan;
                    }
                }
                int i = (oz + lz) * stride + ox + lx;
                if (waterTop != Integer.MIN_VALUE) {
                    // Wasser: je tiefer, desto dunkler/blauer; keine Hoehen-Schattierung
                    float f = Math.min(0.92f, 0.30f + 0.06f * depth);
                    px[i] = solid == 0 ? 0xFF000000 | WATER_RGB : mix(solid, 0xFF000000 | WATER_RGB, f);
                    hs[i] = Integer.MIN_VALUE;
                } else if (solid != 0) {
                    px[i] = solid;
                    hs[i] = top;
                }
            }
        }
    }

    /** Dekodiert die gepackten Palette-Indizes (4096 Eintraege, ohne Ueberlauf zwischen Longs). */
    private static int[] decode(long[] data, int paletteSize) {
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
        int perLong = 64 / bits;
        if (data.length < (4096 + perLong - 1) / perLong) return null;
        long mask = (1L << bits) - 1;
        int[] out = new int[4096];
        for (int i = 0; i < 4096; i++) {
            int v = (int) ((data[i / perLong] >>> ((i % perLong) * bits)) & mask);
            out[i] = v < paletteSize ? v : 0;
        }
        return out;
    }

    private static int mix(int a, int b, float f) {
        int r = (int) (((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = (int) (((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = (int) ((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
