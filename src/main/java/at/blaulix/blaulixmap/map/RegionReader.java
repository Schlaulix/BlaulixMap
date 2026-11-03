package at.blaulix.blaulixmap.map;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Liest Chunks direkt aus .mca-Region-Dateien auf der Festplatte (read-only).
 * Dadurch funktionieren auch mit Chunky vorab generierte Welten, ohne dass je ein Spieler dort war
 * und ohne Zugriff auf das Chunk-System des Servers (kein Eingriff in den Tick).
 */
public final class RegionReader implements Closeable {
    private final RandomAccessFile raf;
    private final int[] header = new int[1024];
    private final long length;

    public RegionReader(Path file) throws IOException {
        raf = new RandomAccessFile(file.toFile(), "r");
        length = raf.length();
        if (length >= 4096) {
            byte[] b = new byte[4096];
            raf.readFully(b);
            ByteBuffer bb = ByteBuffer.wrap(b);
            for (int i = 0; i < 1024; i++) header[i] = bb.getInt();
        }
    }

    /** Liest den Chunk (lokale Koordinaten 0..31) oder null, falls nicht vorhanden. */
    public Map<String, Object> readChunk(int lx, int lz) throws IOException {
        int e = header[(lx & 31) + (lz & 31) * 32];
        if (e == 0) return null;
        long off = (long) (e >>> 8) * 4096L;
        if (off + 5 > length) return null;
        raf.seek(off);
        int len = raf.readInt();
        int comp = raf.readUnsignedByte();
        if (len <= 1 || off + 4 + len > length) return null;
        if ((comp & 0x80) != 0) return null; // extern in .mcc gespeichert -> ignorieren
        byte[] data = new byte[len - 1];
        raf.readFully(data);
        InputStream in = wrap(comp & 0x7F, data);
        if (in == null) return null;
        try (in) {
            return Nbt.readRoot(in);
        }
    }

    private static InputStream wrap(int type, byte[] data) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        switch (type) {
            case 1: return new GZIPInputStream(bais);
            case 2: return new InflaterInputStream(bais);
            case 3: return bais;
            case 4:
                // LZ4 (optionale Region-Kompression): lz4-java wird von Minecraft mitgeliefert -> per Reflection
                try {
                    Class<?> c = Class.forName("net.jpountz.lz4.LZ4BlockInputStream");
                    return (InputStream) c.getConstructor(InputStream.class).newInstance(bais);
                } catch (ReflectiveOperationException ex) {
                    return null;
                }
            default: return null;
        }
    }

    @Override
    public void close() throws IOException { raf.close(); }
}
