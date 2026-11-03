package at.blaulix.blaulixmap.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Konfiguration (config/blaulixmap.json). Feldnamen entsprechen direkt den JSON-Keys.
 */
public class BlaulixConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Maximale Anzahl Claims pro Spieler. */
    public int max_claims_per_player = 3;
    /** Maximale Grundflaeche eines Claims in Bloecken (m²). */
    public int max_claim_area_blocks = 10000;
    /** Port des Webservers. */
    public int web_port = 8123;
    /**
     * Admin-Passwort im Web. Entweder Klartext oder "sha256:<hex>" (SHA-256 des Passworts).
     * Leer = Admin-Login deaktiviert.
     */
    public String admin_password_hash = "";
    /** Mindestabstand (Sekunden), bevor eine bereits gerenderte Kachel neu berechnet wird. */
    public int tile_refresh_seconds = 300;
    /** Anzahl Render-Threads (niedrig halten, damit die Server-CPU geschont wird). */
    public int render_threads = 1;
    /** Optionale Admins (Spielername oder UUID) fuer /claim remove, falls die OP-Erkennung nicht greift. */
    public List<String> admin_players = new ArrayList<>();

    /** Laedt die Config; fehlende Felder werden mit Defaults ergaenzt und zurueckgeschrieben. */
    public static BlaulixConfig load(Path file) {
        BlaulixConfig cfg = null;
        try {
            if (Files.exists(file)) {
                cfg = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), BlaulixConfig.class);
            }
        } catch (Exception e) {
            System.err.println("[blaulixmap] Config konnte nicht gelesen werden, nutze Defaults: " + e);
        }
        if (cfg == null) cfg = new BlaulixConfig();
        if (cfg.admin_players == null) cfg.admin_players = new ArrayList<>();
        if (cfg.admin_password_hash == null) cfg.admin_password_hash = "";
        cfg.render_threads = Math.max(1, cfg.render_threads);
        cfg.save(file);
        return cfg;
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[blaulixmap] Config konnte nicht gespeichert werden: " + e);
        }
    }
}
