package at.blaulix.blaulixmap.claims;

import at.blaulix.blaulixmap.config.BlaulixConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Verwaltet Claims und laufende Entwuerfe (/claim create ... confirm).
 * Thread-sicher: wird vom Server-Thread (Befehle) und vom HTTP-Thread (Web-Editor) genutzt.
 * Das Schreiben der Datei passiert auf einem eigenen Thread, damit der Tick nie blockiert.
 */
public class ClaimManager {
    public record Result(boolean ok, String message, Claim claim) {
        static Result fail(String m) { return new Result(false, m, null); }
    }

    /** Ein Claim-Entwurf, der ingame Punkt fuer Punkt aufgebaut wird. */
    public static class Draft {
        public final String name;
        public final String dimension;
        public final List<double[]> points = new ArrayList<>();
        public Draft(String name, String dimension) { this.name = name; this.dimension = dimension; }
    }

    private static final Pattern NAME = Pattern.compile("[\\p{L}0-9_\\- ]{1,32}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final BlaulixConfig cfg;
    private final Path file;
    private final List<Claim> claims = new ArrayList<>();
    private final Map<UUID, Draft> drafts = new HashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "blaulixmap-claims-io");
        t.setDaemon(true);
        return t;
    });

    public ClaimManager(BlaulixConfig cfg, Path file) {
        this.cfg = cfg;
        this.file = file;
        load();
    }

    private void load() {
        try {
            if (Files.exists(file)) {
                List<Claim> l = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                        new TypeToken<List<Claim>>() {}.getType());
                if (l != null) claims.addAll(l);
            }
        } catch (Exception e) {
            System.err.println("[blaulixmap] claims.json konnte nicht gelesen werden: " + e);
        }
    }

    /** Serialisiert unter Lock und schreibt asynchron (atomar via temp-Datei). */
    private void saveAsync() {
        final String json = GSON.toJson(claims);
        io.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.err.println("[blaulixmap] claims.json konnte nicht gespeichert werden: " + e);
            }
        });
    }

    public synchronized List<Claim> all() { return new ArrayList<>(claims); }

    public synchronized Claim find(String name) {
        if (name == null) return null;
        for (Claim c : claims) if (c.name.equalsIgnoreCase(name.trim())) return c;
        return null;
    }

    public synchronized int countOwned(String ownerUuid) {
        int n = 0;
        for (Claim c : claims) if (c.owner.equals(ownerUuid)) n++;
        return n;
    }

    // ---------- Entwuerfe (Ingame) ----------

    public synchronized Result startDraft(UUID player, String name, String dimension) {
        String n = name == null ? "" : name.trim();
        if (!NAME.matcher(n).matches()) return Result.fail("Ungültiger Name (1-32 Zeichen: Buchstaben, Zahlen, _ - Leerzeichen).");
        if (find(n) != null) return Result.fail("Ein Claim mit diesem Namen existiert bereits.");
        if (countOwned(player.toString()) >= cfg.max_claims_per_player)
            return Result.fail("Du hast das Limit von " + cfg.max_claims_per_player + " Claims erreicht.");
        drafts.put(player, new Draft(n, dimension));
        return new Result(true, "Claim-Entwurf \"" + n + "\" gestartet. Nutze /claim addpoint an den Ecken, dann /claim confirm.", null);
    }

    public synchronized Draft draft(UUID player) { return drafts.get(player); }

    public synchronized void cancelDraft(UUID player) { drafts.remove(player); }

    // ---------- Erstellen / Loeschen ----------

    /** Schliesst einen Entwurf ab (Ingame /claim confirm). */
    public synchronized Result confirmDraft(UUID player, String ownerName) {
        Draft d = drafts.get(player);
        if (d == null) return Result.fail("Kein Entwurf aktiv. Starte mit /claim create <name>.");
        Result r = create(player.toString(), ownerName, d.name, d.dimension, d.points);
        if (r.ok()) drafts.remove(player);
        return r;
    }

    /** Validiert (Limits, Geometrie) und speichert einen neuen Claim. Gemeinsam genutzt von Ingame und Web. */
    public synchronized Result create(String ownerUuid, String ownerName, String name, String dimension, List<double[]> pts) {
        String n = name == null ? "" : name.trim();
        if (!NAME.matcher(n).matches()) return Result.fail("Ungültiger Name (1-32 Zeichen: Buchstaben, Zahlen, _ - Leerzeichen).");
        if (!Set.of("overworld", "nether", "end").contains(dimension)) return Result.fail("Unbekannte Dimension.");
        if (pts == null || pts.size() < 3) return Result.fail("Ein Claim braucht mindestens 3 Eckpunkte.");
        if (pts.size() > 64) return Result.fail("Maximal 64 Eckpunkte erlaubt.");
        if (find(n) != null) return Result.fail("Ein Claim mit diesem Namen existiert bereits.");
        if (countOwned(ownerUuid) >= cfg.max_claims_per_player)
            return Result.fail("Limit von " + cfg.max_claims_per_player + " Claims erreicht.");
        double area = Geometry.area(pts);
        if (area < 1) return Result.fail("Die Fläche des Claims ist zu klein.");
        if (area > cfg.max_claim_area_blocks)
            return Result.fail("Claim zu groß: " + Math.round(area) + " > " + cfg.max_claim_area_blocks + " Blöcke.");
        if (Geometry.selfIntersects(pts)) return Result.fail("Das Polygon überschneidet sich selbst.");

        Claim c = new Claim();
        c.name = n;
        c.owner = ownerUuid;
        c.ownerName = ownerName;
        c.dimension = dimension;
        c.points = new ArrayList<>();
        for (double[] p : pts) c.points.add(new double[]{Math.round(p[0] * 10) / 10.0, Math.round(p[1] * 10) / 10.0});
        c.area = Math.round(area);
        c.created = System.currentTimeMillis();
        claims.add(c);
        saveAsync();
        return new Result(true, "Claim \"" + n + "\" erstellt (" + Math.round(area) + " Blöcke).", c);
    }

    /** Loescht einen Claim; Besitzer duerfen eigene, Admins alle. */
    public synchronized Result remove(String name, String requesterUuid, boolean admin) {
        Claim c = find(name);
        if (c == null) return Result.fail("Claim nicht gefunden.");
        if (!admin && !c.owner.equals(requesterUuid)) return Result.fail("Das ist nicht dein Claim.");
        claims.remove(c);
        saveAsync();
        return new Result(true, "Claim \"" + c.name + "\" gelöscht.", c);
    }

    public void shutdown() { io.shutdown(); }
}
