package at.blaulix.blaulixmap.auth;

import at.blaulix.blaulixmap.config.BlaulixConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Einmal-Login-Codes (/map login), Web-Sessions und Brute-Force-Schutz.
 * Jeder Code ist fest an UUID + Spielername des Minecraft-Accounts gebunden.
 */
public class AuthManager {
    public static final UUID ADMIN_UUID = new UUID(0L, 0L);
    private static final long CODE_TTL_MS = 5 * 60_000L;
    private static final long SESSION_TTL_MS = 12 * 3600_000L;

    private record LoginCode(UUID uuid, String name, long expires) {}
    public record Session(String token, UUID uuid, String name, boolean admin, long expires) {}

    private final BlaulixConfig cfg;
    private final SecureRandom rnd = new SecureRandom();
    private final Map<String, LoginCode> codes = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<Long>> failures = new ConcurrentHashMap<>();

    public AuthManager(BlaulixConfig cfg) { this.cfg = cfg; }

    /** Erzeugt einen 6-stelligen Code (5 Min gueltig); alte Codes desselben Spielers werden ungueltig. */
    public String createCode(UUID uuid, String name) {
        long now = System.currentTimeMillis();
        codes.entrySet().removeIf(e -> e.getValue().expires < now || e.getValue().uuid.equals(uuid));
        String code;
        do {
            code = String.format("%06d", rnd.nextInt(1_000_000));
        } while (codes.containsKey(code));
        codes.put(code, new LoginCode(uuid, name, now + CODE_TTL_MS));
        return code;
    }

    /** Loest einen Code ein (einmalig) und erstellt eine Session. */
    public Session redeemCode(String code) {
        if (code == null) return null;
        LoginCode lc = codes.remove(code.trim());
        if (lc == null || lc.expires < System.currentTimeMillis()) return null;
        return newSession(lc.uuid, lc.name, false);
    }

    public boolean adminEnabled() { return !cfg.admin_password_hash.isBlank(); }

    /** Prueft das Admin-Passwort (Klartext oder "sha256:<hex>") in konstanter Zeit. */
    public Session adminLogin(String password) {
        if (!adminEnabled() || password == null) return null;
        String conf = cfg.admin_password_hash.trim();
        boolean ok;
        try {
            if (conf.toLowerCase().startsWith("sha256:")) {
                byte[] h = MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8));
                byte[] expected = HexFormat.of().parseHex(conf.substring(7).trim().toLowerCase());
                ok = MessageDigest.isEqual(h, expected);
            } else {
                ok = MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8), conf.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            ok = false;
        }
        return ok ? newSession(ADMIN_UUID, "Admin", true) : null;
    }

    private Session newSession(UUID uuid, String name, boolean admin) {
        byte[] b = new byte[32];
        rnd.nextBytes(b);
        String token = HexFormat.of().formatHex(b);
        Session s = new Session(token, uuid, name, admin, System.currentTimeMillis() + SESSION_TTL_MS);
        sessions.put(token, s);
        return s;
    }

    public Session getSession(String token) {
        if (token == null) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        if (s.expires < System.currentTimeMillis()) {
            sessions.remove(token);
            return null;
        }
        return s;
    }

    public void logout(String token) { if (token != null) sessions.remove(token); }

    // ---------- Brute-Force-Schutz: max. 8 Fehlversuche pro IP und Minute ----------

    public boolean isBlocked(String ip) {
        ArrayDeque<Long> q = failures.get(ip);
        if (q == null) return false;
        synchronized (q) {
            long limit = System.currentTimeMillis() - 60_000L;
            while (!q.isEmpty() && q.peekFirst() < limit) q.pollFirst();
            return q.size() >= 8;
        }
    }

    public void registerFailure(String ip) {
        ArrayDeque<Long> q = failures.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) { q.addLast(System.currentTimeMillis()); }
    }
}
