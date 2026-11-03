package at.blaulix.blaulixmap.map;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimaler, eigenstaendiger NBT-Parser (nur Lesen). Unabhaengig von Minecraft-Klassen,
 * damit er auch in Hintergrund-Threads gefahrlos laeuft und nicht von API-Aenderungen betroffen ist.
 * Compounds -> Map, Listen -> List, Arrays -> byte[]/int[]/long[], Zahlen -> Byte/Short/Integer/Long/Float/Double.
 */
public final class Nbt {
    private Nbt() {}

    public static Map<String, Object> readRoot(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 1 << 16));
        int type = in.readUnsignedByte();
        if (type != 10) throw new IOException("NBT-Root ist kein Compound");
        in.readUTF(); // Root-Name
        return readCompound(in);
    }

    private static Map<String, Object> readCompound(DataInputStream in) throws IOException {
        Map<String, Object> m = new HashMap<>();
        while (true) {
            int t = in.readUnsignedByte();
            if (t == 0) break;
            String name = in.readUTF();
            m.put(name, readPayload(in, t));
        }
        return m;
    }

    private static Object readPayload(DataInputStream in, int t) throws IOException {
        switch (t) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {
                int n = in.readInt();
                byte[] b = new byte[n];
                in.readFully(b);
                return b;
            }
            case 8: return in.readUTF();
            case 9: {
                int id = in.readUnsignedByte();
                int n = in.readInt();
                List<Object> l = new ArrayList<>(Math.max(0, Math.min(n, 4096)));
                for (int i = 0; i < n; i++) l.add(readPayload(in, id));
                return l;
            }
            case 10: return readCompound(in);
            case 11: {
                int n = in.readInt();
                int[] a = new int[n];
                for (int i = 0; i < n; i++) a[i] = in.readInt();
                return a;
            }
            case 12: {
                int n = in.readInt();
                long[] a = new long[n];
                for (int i = 0; i < n; i++) a[i] = in.readLong();
                return a;
            }
            default: throw new IOException("Unbekannter NBT-Tag: " + t);
        }
    }
}
