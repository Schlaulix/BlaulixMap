package at.blaulix.blaulixmap.web;

import at.blaulix.blaulixmap.PlayerSnapshot;
import at.blaulix.blaulixmap.auth.AuthManager;
import at.blaulix.blaulixmap.claims.Claim;
import at.blaulix.blaulixmap.claims.ClaimManager;
import at.blaulix.blaulixmap.config.BlaulixConfig;
import at.blaulix.blaulixmap.map.TileService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Minimaler HTTP-Server (JDK com.sun.net.httpserver) fuer Karte, API und Frontend.
 * Laeuft auf eigenen Threads; greift nie direkt auf Minecraft-Objekte zu.
 */
public class WebServer {
    private static final Gson GSON = new Gson();
    private static final int MAX_BODY = 64 * 1024;

    private final BlaulixConfig cfg;
    private final ClaimManager claims;
    private final AuthManager auth;
    private final TileService tiles;
    private final Supplier<List<PlayerSnapshot>> players;
    private final HttpServer server;
    private final ExecutorService executor;
    private final byte[] indexHtml;

    public WebServer(BlaulixConfig cfg, ClaimManager claims, AuthManager auth, TileService tiles,
                     Supplier<List<PlayerSnapshot>> players) throws IOException {
        this.cfg = cfg;
        this.claims = claims;
        this.auth = auth;
        this.tiles = tiles;
        this.players = players;
        try (InputStream in = WebServer.class.getResourceAsStream("/web/index.html")) {
            if (in == null) throw new IOException("web/index.html fehlt im Mod-Jar");
            this.indexHtml = in.readAllBytes();
        }
        this.server = HttpServer.create(new InetSocketAddress(cfg.web_port), 64);
        this.executor = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "blaulixmap-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() { server.start(); }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ Routing

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            ex.getResponseHeaders().add("X-Content-Type-Options", "nosniff");

            if (path.equals("/") || path.equals("/index.html")) {
                if (!method.equals("GET")) { error(ex, 405, "Method not allowed"); return; }
                send(ex, 200, "text/html; charset=utf-8", indexHtml);
            } else if (path.startsWith("/tiles/")) {
                tile(ex, path);
            } else if (path.startsWith("/api/")) {
                api(ex, path, method);
            } else {
                error(ex, 404, "Not found");
            }
        } catch (Throwable t) {
            try { error(ex, 500, "Interner Fehler"); } catch (Throwable ignored) { }
        } finally {
            ex.close();
        }
    }

    private void api(HttpExchange ex, String path, String method) throws IOException {
        switch (path) {
            case "/api/players" -> {
                if (!method.equals("GET")) { error(ex, 405, "Method not allowed"); return; }
                json(ex, 200, players.get());
            }
            case "/api/claims" -> {
                if (!method.equals("GET")) { error(ex, 405, "Method not allowed"); return; }
                json(ex, 200, claims.all());
            }
            case "/api/config" -> {
                if (!method.equals("GET")) { error(ex, 405, "Method not allowed"); return; }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("maxClaimsPerPlayer", cfg.max_claims_per_player);
                m.put("maxClaimAreaBlocks", cfg.max_claim_area_blocks);
                m.put("adminLoginEnabled", auth.adminEnabled());
                m.put("dimensions", tiles.availableDimensions());
                json(ex, 200, m);
            }
            case "/api/session" -> {
                if (!method.equals("GET")) { error(ex, 405, "Method not allowed"); return; }
                AuthManager.Session s = auth.getSession(token(ex));
                json(ex, 200, sessionDto(s));
            }
            case "/api/auth/code" -> {
                if (!method.equals("POST")) { error(ex, 405, "Method not allowed"); return; }
                String ip = ip(ex);
                if (auth.isBlocked(ip)) { error(ex, 429, "Zu viele Fehlversuche. Bitte eine Minute warten."); return; }
                JsonObject body = body(ex);
                AuthManager.Session s = auth.redeemCode(str(body, "code"));
                if (s == null) { auth.registerFailure(ip); error(ex, 401, "Code ungültig oder abgelaufen."); return; }
                json(ex, 200, withToken(s));
            }
            case "/api/auth/admin" -> {
                if (!method.equals("POST")) { error(ex, 405, "Method not allowed"); return; }
                String ip = ip(ex);
                if (auth.isBlocked(ip)) { error(ex, 429, "Zu viele Fehlversuche. Bitte eine Minute warten."); return; }
                JsonObject body = body(ex);
                AuthManager.Session s = auth.adminLogin(str(body, "password"));
                if (s == null) { auth.registerFailure(ip); error(ex, 401, "Admin-Login fehlgeschlagen."); return; }
                json(ex, 200, withToken(s));
            }
            case "/api/auth/logout" -> {
                if (!method.equals("POST")) { error(ex, 405, "Method not allowed"); return; }
                auth.logout(token(ex));
                json(ex, 200, Map.of("ok", true));
            }
            case "/api/claims/create-web" -> {
                if (!method.equals("POST")) { error(ex, 405, "Method not allowed"); return; }
                AuthManager.Session s = auth.getSession(token(ex));
                if (s == null) { error(ex, 401, "Nicht eingeloggt."); return; }
                JsonObject body = body(ex);
                List<double[]> pts = parsePoints(body.get("points"));
                if (pts == null) { error(ex, 400, "Ungültige Punkte."); return; }
                ClaimManager.Result r = claims.create(s.uuid().toString(), s.name(), str(body, "name"),
                        str(body, "dimension"), pts);
                if (!r.ok()) { error(ex, 400, r.message()); return; }
                json(ex, 200, r.claim());
            }
            case "/api/claims/delete-web" -> {
                if (!method.equals("DELETE")) { error(ex, 405, "Method not allowed"); return; }
                AuthManager.Session s = auth.getSession(token(ex));
                if (s == null) { error(ex, 401, "Nicht eingeloggt."); return; }
                String name = query(ex, "name");
                if (name == null) name = str(body(ex), "name");
                ClaimManager.Result r = claims.remove(name, s.uuid().toString(), s.admin());
                if (!r.ok()) { error(ex, r.message().contains("nicht dein") ? 403 : 404, r.message()); return; }
                json(ex, 200, Map.of("ok", true, "message", r.message()));
            }
            default -> error(ex, 404, "Not found");
        }
    }

    /** GET /tiles/{dim}/{z}/{x}/{y}.png */
    private void tile(HttpExchange ex, String path) throws IOException {
        String[] p = path.split("/");
        if (p.length != 6 || !p[5].endsWith(".png") || !ex.getRequestMethod().equals("GET")) { error(ex, 404, "Not found"); return; }
        int z, x, y;
        try {
            z = Integer.parseInt(p[3]);
            x = Integer.parseInt(p[4]);
            y = Integer.parseInt(p[5].substring(0, p[5].length() - 4));
        } catch (NumberFormatException e) {
            error(ex, 400, "Bad tile");
            return;
        }
        byte[] png = tiles.getTile(p[2], z, x, y);
        ex.getResponseHeaders().add("Cache-Control", "public, max-age=30");
        send(ex, 200, "image/png", png);
    }

    // ------------------------------------------------------------------ Hilfen

    private Map<String, Object> sessionDto(AuthManager.Session s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loggedIn", s != null);
        if (s != null) {
            m.put("name", s.name());
            m.put("uuid", s.uuid().toString());
            m.put("admin", s.admin());
        }
        return m;
    }

    private Map<String, Object> withToken(AuthManager.Session s) {
        Map<String, Object> m = sessionDto(s);
        m.put("token", s.token());
        return m;
    }

    private static String token(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        return (h != null && h.startsWith("Bearer ")) ? h.substring(7).trim() : null;
    }

    private static String ip(HttpExchange ex) { return ex.getRemoteAddress().getAddress().getHostAddress(); }

    private static String query(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return null;
        for (String part : q.split("&")) {
            int i = part.indexOf('=');
            if (i > 0 && part.substring(0, i).equals(key)) {
                return URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static JsonObject body(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (b.length == 0 || b.length > MAX_BODY) return new JsonObject();
        try {
            JsonElement e = JsonParser.parseString(new String(b, StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e != null && e.isJsonPrimitive()) ? e.getAsString() : null;
    }

    private static List<double[]> parsePoints(JsonElement e) {
        if (e == null || !e.isJsonArray()) return null;
        JsonArray arr = e.getAsJsonArray();
        List<double[]> out = new ArrayList<>();
        for (JsonElement pe : arr) {
            if (!pe.isJsonArray() || pe.getAsJsonArray().size() != 2) return null;
            try {
                double x = pe.getAsJsonArray().get(0).getAsDouble(), z = pe.getAsJsonArray().get(1).getAsDouble();
                if (!Double.isFinite(x) || !Double.isFinite(z) || Math.abs(x) > 3e7 || Math.abs(z) > 3e7) return null;
                out.add(new double[]{x, z});
            } catch (Exception ex) {
                return null;
            }
        }
        return out;
    }

    private static void json(HttpExchange ex, int code, Object o) throws IOException {
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        send(ex, code, "application/json; charset=utf-8", GSON.toJson(o).getBytes(StandardCharsets.UTF_8));
    }

    private static void error(HttpExchange ex, int code, String msg) throws IOException {
        json(ex, code, Map.of("error", msg));
    }

    private static void send(HttpExchange ex, int code, String type, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, data.length == 0 ? -1 : data.length);
        if (data.length > 0) {
            try (OutputStream os = ex.getResponseBody()) { os.write(data); }
        }
    }
}
