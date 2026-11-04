package at.blaulix.blaulixmap.map;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Stream;

/**
 * Erzeugt Karten-Kacheln (PNG, 256x256) asynchron aus den Region-Dateien der Welt und cached sie auf der Platte.
 *
 * Zoomstufen z = 0..4: Stufe 4 = 1 Pixel pro Block (Kachel = 256x256 Bloecke), jede Stufe darunter
 * halbiert die Aufloesung (wird aus den 4 Kind-Kacheln zusammengesetzt).
 *
 * Alles laeuft auf eigenen, niedrig priorisierten Threads - der Server-Tick wird nie beruehrt.
 */
public final class TileService implements AutoCloseable {
    public static final int MAX_Z = 4;
    private static final int SIZE = 256;
    /** NEU: Rand (in Pixeln) um die Kachel, damit die Hangschattierung ueber Kachel-/Region-Grenzen stimmt. */
    private static final int PAD = SIZE + 16;
    /** NEU: bei jeder Aenderung am Renderer erhoehen -> alter Kachel-Cache wird verworfen. */
    private static final int RENDER_VERSION = 2;

    /** Moegliche Region-Verzeichnisse je Dimension (altes und neues Welt-Layout). */
    private static final Map<String, String[]> DIM_PATHS = Map.of(
            "overworld", new String[]{"region", "dimensions/minecraft/overworld/region"},
            "nether", new String[]{"DIM-1/region", "dimensions/minecraft/the_nether/region"},
            "end", new String[]{"DIM1/region", "dimensions/minecraft/the_end/region"});

    private final Path worldRoot;
    private final Path cacheDir;
    private final long refreshMs;
    private final ThreadPoolExecutor pool;
    private final Map<String, CompletableFuture<byte[]>> inflight = new ConcurrentHashMap<>();
    private final Map<String, RegionIndex> indices = new ConcurrentHashMap<>();
    private final byte[] emptyPng;

    public TileService(Path worldRoot, Path cacheDir, int threads, int refreshSeconds) {
        this.worldRoot = worldRoot;
        this.cacheDir = cacheDir;
        this.refreshMs = refreshSeconds * 1000L;
        this.pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "blaulixmap-render");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY); // CPU des Servers schonen
            return t;
        });
        for (String d : DIM_PATHS.keySet()) indices.put(d, new RegionIndex(d));
        this.emptyPng = encode(new int[SIZE * SIZE]);
        invalidateOldCache();
    }

    /** NEU: Kacheln, die mit einer aelteren Renderer-Version erzeugt wurden (fehlerhafte Farben), loeschen. */
    private void invalidateOldCache() {
        try {
            Path vf = cacheDir.resolve("render-version.txt");
            String cur = Files.exists(vf) ? Files.readString(vf).trim() : "";
            if (!cur.equals(Integer.toString(RENDER_VERSION))) {
                for (String d : DIM_PATHS.keySet()) deleteTree(cacheDir.resolve(d));
                Files.createDirectories(cacheDir);
                Files.writeString(vf, Integer.toString(RENDER_VERSION));
            }
        } catch (IOException e) {
            System.err.println("[blaulixmap] Cache-Version konnte nicht geprueft werden: " + e);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> w = Files.walk(root)) {
            for (Path p : (Iterable<Path>) w.sorted(java.util.Comparator.reverseOrder())::iterator) Files.deleteIfExists(p);
        }
    }

    /** Dimensionen, fuer die Region-Dateien existieren. */
    public java.util.List<String> availableDimensions() {
        java.util.List<String> l = new java.util.ArrayList<>();
        for (String d : new String[]{"overworld", "nether", "end"}) if (!indices.get(d).files().isEmpty()) l.add(d);
        return l;
    }

    // ------------------------------------------------------------------ oeffentliche API

    /** Liefert die PNG-Bytes einer Kachel (blockiert den aufrufenden HTTP-Thread, nie den Tick-Thread). */
    public byte[] getTile(String dim, int z, int x, int y) {
        if (!DIM_PATHS.containsKey(dim) || z < 0 || z > MAX_Z) return emptyPng;
        String key = dim + '/' + z + '/' + x + '/' + y;
        CompletableFuture<byte[]> f = inflight.computeIfAbsent(key, k ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return renderPng(dim, z, x, y);
                    } catch (Throwable t) {
                        System.err.println("[blaulixmap] Kachel-Fehler " + k + ": " + t);
                        return emptyPng;
                    }
                }, pool).whenComplete((r, t) -> inflight.remove(k)));
        try {
            return f.get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            return emptyPng;
        }
    }

    @Override
    public void close() { pool.shutdownNow(); }

    // ------------------------------------------------------------------ Rendering

    private byte[] renderPng(String dim, int z, int x, int y) throws IOException {
        Path f = tilePath(dim, z, x, y);
        if (isFresh(f, dim, z, x, y)) return Files.readAllBytes(f);
        int[] px = renderPixels(dim, z, x, y);
        if (px == null) return emptyPng;
        return store(f, px);
    }

    /** Pixel einer Kachel (aus Cache oder frisch berechnet); null = dort gibt es keine Weltdaten. */
    private int[] pixels(String dim, int z, int x, int y) throws IOException {
        Path f = tilePath(dim, z, x, y);
        if (isFresh(f, dim, z, x, y)) {
            BufferedImage img = ImageIO.read(f.toFile());
            if (img != null) return img.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
        }
        int[] px = renderPixels(dim, z, x, y);
        if (px != null) store(f, px);
        return px;
    }

    private int[] renderPixels(String dim, int z, int x, int y) throws IOException {
        long span = (long) SIZE << (MAX_Z - z); // Bloecke pro Kachelkante
        long bx = x * span, bz = y * span;
        RegionIndex idx = indices.get(dim);
        if (!idx.has(bx, bz, span)) return null;
        if (z == MAX_Z) return renderBase(dim, bx, bz, idx);

        // Obere Zoomstufen: aus den vier Kind-Kacheln der naechsten Stufe zusammensetzen
        int[][] ch = new int[4][];
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            ch[i] = pixels(dim, z + 1, 2 * x + (i & 1), 2 * y + (i >> 1));
            any |= ch[i] != null;
        }
        return any ? downscale(ch) : null;
    }

    /**
     * Stufe 4: liest 16x16 Chunks und faerbt sie ein.
     * GEAENDERT: Zusaetzlich werden die Nachbar-Chunks links/oberhalb der Kachel gelesen (auch aus
     * benachbarten Region-Dateien) und in einem Puffer mit Rand gerendert. Dadurch hat die Schattierung an
     * Kachel- und Region-Grenzen den richtigen Nachbarn (vorher: sichtbare Nahtlinien).
     * Koordinaten: Welt-Block (X,Z) -> Chunk = X>>4 -> Pixel = (X - Kachel-Ursprung) + 16 Rand.
     */
    private int[] renderBase(String dim, long bx, long bz, RegionIndex idx) throws IOException {
        int rx0 = (int) Math.floorDiv(bx, 512L), rz0 = (int) Math.floorDiv(bz, 512L);
        Path main = idx.regionPath(rx0, rz0);
        if (main == null || !Files.exists(main)) return null;

        int maxY = dim.equals("nether") ? 120 : 10_000; // Nether: Bedrock-Decke ausblenden
        int[] px = new int[PAD * PAD];
        int[] hs = new int[PAD * PAD];
        java.util.Arrays.fill(hs, Integer.MIN_VALUE);

        Map<Long, RegionReader> readers = new HashMap<>();
        int failed = 0;
        try {
            for (int cz = -1; cz < 16; cz++) {
                for (int cx = -1; cx < 16; cx++) {
                    int gcx = (int) (bx >> 4) + cx, gcz = (int) (bz >> 4) + cz;
                    int rx = gcx >> 5, rz = gcz >> 5; // Region des Chunks (floorDiv durch Shift)
                    long key = ((long) rx << 32) | (rz & 0xFFFFFFFFL);
                    RegionReader reader = readers.get(key);
                    if (reader == null && !readers.containsKey(key)) {
                        Path rp = idx.regionPath(rx, rz);
                        try {
                            reader = (rp != null && Files.exists(rp)) ? new RegionReader(rp) : null;
                        } catch (IOException e) {
                            reader = null;
                        }
                        readers.put(key, reader);
                    }
                    if (reader == null) continue;
                    try {
                        Map<String, Object> chunk = reader.readChunk(gcx & 31, gcz & 31);
                        if (chunk != null) ChunkSurface.render(chunk, maxY, px, hs, (cx + 1) * 16, (cz + 1) * 16, PAD);
                    } catch (Exception e) {
                        // kaputten/halb geschriebenen Chunk ueberspringen - aber nicht mehr still: einmal pro Kachel melden
                        if (failed++ == 0) System.err.println("[blaulixmap] Chunk-Fehler in " + dim + " bei Chunk "
                                + gcx + "," + gcz + ": " + e);
                    }
                }
            }
        } finally {
            for (RegionReader r : readers.values()) if (r != null) { try { r.close(); } catch (IOException ignored) { } }
        }
        shade(px, hs);

        // Rand abschneiden -> endgueltige 256x256-Kachel
        int[] out = new int[SIZE * SIZE];
        for (int z = 0; z < SIZE; z++) System.arraycopy(px, (z + 16) * PAD + 16, out, z * SIZE, SIZE);
        return out;
    }

    /** Einfache Hangschattierung: Licht von Nordwesten, Vergleich mit dem Nachbarpixel (inkl. Randpuffer). */
    private static void shade(int[] px, int[] hs) {
        for (int z = 16; z < 16 + SIZE; z++) {
            for (int x = 16; x < 16 + SIZE; x++) {
                int i = z * PAD + x;
                if (px[i] == 0 || hs[i] == Integer.MIN_VALUE) continue;
                int h = hs[i];
                int hn = hs[i - PAD - 1]; // Nachbar nordwestlich (existiert dank Rand immer)
                if (hn == Integer.MIN_VALUE) hn = h;
                int d = Math.max(-4, Math.min(4, h - hn));
                float f = 1f + d * 0.07f;
                int c = px[i];
                int r = Math.min(255, (int) (((c >> 16) & 255) * f));
                int g = Math.min(255, (int) (((c >> 8) & 255) * f));
                int b = Math.min(255, (int) ((c & 255) * f));
                px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }

    /** Setzt 4 Kind-Kacheln zu einer Kachel halber Aufloesung zusammen (2x2-Mittelung). */
    private static int[] downscale(int[][] ch) {
        int[] out = new int[SIZE * SIZE];
        for (int oy = 0; oy < SIZE; oy++) {
            for (int ox = 0; ox < SIZE; ox++) {
                int sx = ox * 2, sy = oy * 2;
                int child = (sy >= SIZE ? 2 : 0) + (sx >= SIZE ? 1 : 0);
                int[] src = ch[child];
                if (src == null) continue;
                int lx = sx & (SIZE - 1), ly = sy & (SIZE - 1);
                int n = 0, r = 0, g = 0, b = 0;
                for (int k = 0; k < 4; k++) {
                    int c = src[(ly + (k >> 1)) * SIZE + lx + (k & 1)];
                    if ((c >>> 24) == 0) continue;
                    n++;
                    r += (c >> 16) & 255;
                    g += (c >> 8) & 255;
                    b += c & 255;
                }
                if (n > 0) out[oy * SIZE + ox] = 0xFF000000 | ((r / n) << 16) | ((g / n) << 8) | (b / n);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ Cache

    private Path tilePath(String dim, int z, int x, int y) {
        return cacheDir.resolve(dim).resolve(Integer.toString(z)).resolve(Integer.toString(x)).resolve(y + ".png");
    }

    /**
     * Eine Kachel ist frisch, wenn keine betroffene Region-Datei neuer ist - oder die Kachel juengeren Alters
     * als das Refresh-Intervall ist (damit laufende Serverwelten die CPU nicht dauerhaft belasten).
     */
    private boolean isFresh(Path f, String dim, int z, int x, int y) {
        try {
            if (!Files.exists(f)) return false;
            long tileTime = Files.getLastModifiedTime(f).toMillis();
            long span = (long) SIZE << (MAX_Z - z);
            long regionTime = indices.get(dim).maxMtime(x * span, y * span, span);
            if (regionTime <= tileTime) return true;
            return System.currentTimeMillis() - tileTime < refreshMs;
        } catch (IOException e) {
            return false;
        }
    }

    private byte[] store(Path f, int[] px) throws IOException {
        byte[] png = encode(px);
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp" + Thread.currentThread().threadId());
        Files.write(tmp, png);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        return png;
    }

    private static byte[] encode(int[] px) {
        try {
            BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, SIZE, SIZE, px, 0, SIZE);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(16384);
            ImageIO.write(img, "png", bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ Region-Index

    /** Gecachte Auflistung der r.X.Z.mca-Dateien einer Dimension (alle 20 s aktualisiert). */
    private final class RegionIndex {
        private final String dim;
        private volatile long loadedAt = 0;
        private volatile Map<Long, Long> files = Map.of();
        private volatile Path dir;

        RegionIndex(String dim) { this.dim = dim; }

        private static long key(int rx, int rz) { return ((long) rx << 32) | (rz & 0xFFFFFFFFL); }

        synchronized Map<Long, Long> files() {
            long now = System.currentTimeMillis();
            if (now - loadedAt < 20_000) return files;
            loadedAt = now;
            Path found = null;
            for (String c : DIM_PATHS.get(dim)) {
                Path p = worldRoot.resolve(c);
                if (Files.isDirectory(p)) { found = p; break; }
            }
            dir = found;
            Map<Long, Long> m = new HashMap<>();
            if (found != null) {
                try (Stream<Path> s = Files.list(found)) {
                    s.forEach(p -> {
                        String n = p.getFileName().toString();
                        if (!n.startsWith("r.") || !n.endsWith(".mca")) return;
                        String[] parts = n.split("\\.");
                        if (parts.length != 4) return;
                        try {
                            int rx = Integer.parseInt(parts[1]), rz = Integer.parseInt(parts[2]);
                            m.put(key(rx, rz), Files.getLastModifiedTime(p).toMillis());
                        } catch (Exception ignored) { }
                    });
                } catch (IOException ignored) { }
            }
            files = m;
            return m;
        }

        Path regionPath(int rx, int rz) {
            files();
            Path d = dir;
            return d == null ? null : d.resolve("r." + rx + "." + rz + ".mca");
        }

        boolean has(long bx, long bz, long span) { return scan(bx, bz, span, false) >= 0; }

        long maxMtime(long bx, long bz, long span) { return Math.max(0, scan(bx, bz, span, true)); }

        /** Gibt die groesste mtime der Regionen im Bereich zurueck, -1 falls keine existiert. */
        private long scan(long bx, long bz, long span, boolean all) {
            Map<Long, Long> m = files();
            int x0 = (int) Math.floorDiv(bx, 512L), x1 = (int) Math.floorDiv(bx + span - 1, 512L);
            int z0 = (int) Math.floorDiv(bz, 512L), z1 = (int) Math.floorDiv(bz + span - 1, 512L);
            long best = -1;
            for (int rx = x0; rx <= x1; rx++) {
                for (int rz = z0; rz <= z1; rz++) {
                    Long t = m.get(key(rx, rz));
                    if (t == null) continue;
                    if (!all) return t;
                    best = Math.max(best, t);
                }
            }
            return best;
        }
    }
}
