package at.blaulix.blaulixmap.map;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Berechnet aus den NBT-Daten eines Chunks die Oberflaechen-Farben (Vogelperspektive).
 * Jede Spalte wird von oben nach unten abgetastet, bis der erste "feste" Block gefunden wird.
 * Wasser wird nach Tiefe eingefaerbt. Laeuft komplett in Hintergrund-Threads.
 *
 * GEAENDERT (Fehlerbehebung) gegenueber der ersten Version:
 *  - Paletteneintraege werden robust gelesen (String, Compound mit Name/id/name, kompakte Form mit "" -Key).
 *  - Alte Formate (Sections[].Palette/BlockStates, Level-Wrapper) werden ebenfalls verstanden.
 *  - Bit-Dekodierung erkennt anhand der Array-Laenge, ob mit oder ohne Long-Grenzen-Ueberlauf gepackt wurde.
 *  - Ungueltige Indizes ergeben "Luft" (uebersprungen) statt Palettenindex 0 (das erzeugte falsche Farben).
 *  - Unfertige Chunks (Status z. B. "noise"/"carvers"/"features") werden nicht gerendert: Sie enthalten
 *    nur rohes Stein-Terrain ohne Oberflaeche/Wasser und waren die Ursache grosser fehlerhafter Flaechen.
 *  - Sections mit nicht dekodierbaren Daten werden nicht mehr stillschweigend verworfen, sondern
 *    der ganze Chunk wird uebersprungen (kein falsches Durchscheinen tieferer Schichten).
 */
final class ChunkSurface {
    private ChunkSurface() {}

    /** Wasserfarbe fuer die Tiefen-Einfaerbung. */
    private static final int WATER_RGB = 0x2D5FD6;

    /** Chunk-Status, ab denen Terrain + Oberflaeche vollstaendig generiert sind (nach "features"). */
    private static final java.util.Set<String> DONE_STATUS =
            java.util.Set.of("full", "spawn", "light", "initialize_light");

    /** colors hat paletteSize+1 Eintraege; der letzte (SKIP) faengt ungueltige Indizes ab. */
    private record Section(int y, int[] colors, int[] idx) {}

    /**
     * @param maxY  hoechste Y-Hoehe, die betrachtet wird (Nether: unterhalb der Bedrock-Decke)
     * @param px    Ziel-Pixel (ARGB), Ausgabe
     * @param hs    Ziel-Hoehen (fuer die Schattierung); Integer.MIN_VALUE = keine Schattierung
     * @param ox,oz Pixel-Offset des Chunks im Zielarray
     * @param stride Zeilenbreite des Zielarrays
     * @return true, wenn der Chunk gezeichnet wurde
     */
    @SuppressWarnings("unchecked")
    static boolean render(Map<String, Object> chunk, int maxY, int[] px, int[] hs, int ox, int oz, int stride) {
        // Legacy: alte Chunks (< 1.18) haben alles unter "Level"
        if (chunk.get("Level") instanceof Map<?, ?> lvl) chunk = (Map<String, Object>) lvl;

        if (!isGenerated(chunk)) return false;

        Object so = chunk.get("sections");
        if (so == null) so = chunk.get("Sections");
        if (!(so instanceof List<?> list)) return false;

        List<Section> sections = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> sm)) continue;
            Map<String, Object> s = (Map<String, Object>) sm;
            if (!(s.get("Y") instanceof Number yN)) continue;

            // Palette + Daten: modernes Format (block_states) oder Legacy (Palette/BlockStates)
            Object palObj, dataObj;
            if (s.get("block_states") instanceof Map<?, ?> bsm) {
                Map<String, Object> bs = (Map<String, Object>) bsm;
                palObj = bs.get("palette");
                dataObj = bs.get("data");
            } else {
                palObj = s.get("Palette");
                dataObj = s.get("BlockStates");
            }
            if (!(palObj instanceof List<?> pal) || pal.isEmpty()) continue; // Section ohne Bloecke

            // Lokale Palette: jeder Eintrag -> Block-Name -> Farbe. (Globale numerische IDs gibt es im
            // Anvil-Format nicht; unlesbare Eintraege werden als Luft behandelt.)
            int[] colors = new int[pal.size() + 1];
            boolean any = false;
            for (int i = 0; i < pal.size(); i++) {
                colors[i] = BlockColors.of(nameOf(pal.get(i)));
                if (colors[i] != BlockColors.SKIP) any = true;
            }
            colors[pal.size()] = BlockColors.SKIP; // Index fuer ungueltige Werte
            if (!any) continue; // reine Luft-Section ueberspringen (schneller)

            int[] idx = null;
            if (pal.size() > 1) { // bei genau einem Eintrag gibt es kein Datenarray: ganze Section = Eintrag 0
                long[] data = toLongs(dataObj);
                if (data == null) return false; // beschaedigt -> Chunk nicht zeichnen statt falsch zeichnen
                idx = decode(data, pal.size());
                if (idx == null) return false;
            }
            sections.add(new Section(yN.intValue(), colors, idx));
        }
        if (sections.isEmpty()) return true; // Chunk existiert, ist aber komplett leer
        sections.sort(Comparator.comparingInt(Section::y).reversed()); // oben zuerst (auch negative Y)

        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int solid = 0, top = Integer.MIN_VALUE, waterTop = Integer.MIN_VALUE, depth = 0;
                scan:
                for (Section sec : sections) {
                    for (int ly = 15; ly >= 0; ly--) {
                        int y = sec.y * 16 + ly;
                        if (y > maxY) continue;
                        // Anvil-Reihenfolge der Bloecke: YZX
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
        return true;
    }

    /** True, wenn der Chunk fertig generiert ist (fehlender Status = akzeptieren). */
    private static boolean isGenerated(Map<String, Object> chunk) {
        Object st = chunk.get("Status");
        if (st == null) st = chunk.get("status");
        if (!(st instanceof String s)) return true;
        int c = s.indexOf(':');
        return DONE_STATUS.contains(c >= 0 ? s.substring(c + 1) : s);
    }

    /**
     * Liest den Block-Namen aus einem Paletteneintrag. Unterstuetzt:
     *  - "minecraft:stone"                       (String)
     *  - {Name:"minecraft:stone", Properties:{}} (klassisch)
     *  - {id:"minecraft:stone", properties:{}}   (neuere Schreibweise)
     *  - {"": "minecraft:stone"}                 (kompakte Form mit leerem Schluessel)
     */
    private static String nameOf(Object e) {
        if (e instanceof String s) return s;
        if (e instanceof Map<?, ?> m) {
            for (String k : new String[]{"Name", "name", "id", "Id", "block"}) {
                Object v = m.get(k);
                if (v instanceof String s) return s;
                if (v instanceof Map<?, ?>) return nameOf(v);
            }
            Object v = m.get("");
            if (v != null) return nameOf(v);
        }
        return null;
    }

    /** Daten-Array als long[] (akzeptiert auch eine Liste von Zahlen). */
    private static long[] toLongs(Object o) {
        if (o instanceof long[] a) return a;
        if (o instanceof List<?> l) {
            long[] a = new long[l.size()];
            for (int i = 0; i < a.length; i++) {
                if (!(l.get(i) instanceof Number n)) return null;
                a[i] = n.longValue();
            }
            return a;
        }
        return null;
    }

    /**
     * Dekodiert die gepackten Palette-Indizes (4096 Eintraege).
     * Bits pro Eintrag = max(4, ceil(log2(Palettengroesse))).
     * Seit 1.16 liegen Eintraege NICHT ueber Long-Grenzen hinweg; aeltere Welten packen lueckenlos.
     * Welche Variante vorliegt, wird an der Array-Laenge erkannt.
     */
    private static int[] decode(long[] data, int paletteSize) {
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
        int perLong = 64 / bits;
        int packedLen = (4096 + perLong - 1) / perLong;   // ohne Ueberlauf
        int spanLen = (4096 * bits + 63) / 64;            // lueckenlos (Legacy)
        boolean spanning = data.length != packedLen && data.length == spanLen;
        if (!spanning && data.length < packedLen) return null;

        long mask = (1L << bits) - 1;
        int[] out = new int[4096];
        for (int i = 0; i < 4096; i++) {
            long v;
            if (!spanning) {
                v = (data[i / perLong] >>> ((i % perLong) * bits)) & mask;
            } else {
                long bitIndex = (long) i * bits;
                int w = (int) (bitIndex >>> 6), off = (int) (bitIndex & 63);
                v = data[w] >>> off;
                if (off + bits > 64) v |= data[w + 1] << (64 - off); // Wert reicht ins naechste Long
                v &= mask;
            }
            out[i] = v < paletteSize ? (int) v : paletteSize; // ungueltig -> SKIP-Eintrag
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
