package at.blaulix.blaulixmap;

import at.blaulix.blaulixmap.auth.AuthManager;
import at.blaulix.blaulixmap.claims.ClaimManager;
import at.blaulix.blaulixmap.command.ClaimCommands;
import at.blaulix.blaulixmap.config.BlaulixConfig;
import at.blaulix.blaulixmap.map.TileService;
import at.blaulix.blaulixmap.web.WebServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Blaulix Map & Claims - rein serverseitige Fabric-Mod (Minecraft 26.3, Mojang-Mappings).
 * Vanilla-Clients koennen ohne Mod joinen.
 */
public class BlaulixMap implements ModInitializer {
    public static final String MOD_ID = "blaulixmap";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private BlaulixConfig config;
    private ClaimManager claims;
    private AuthManager auth;
    private Path dataDir;

    private TileService tiles;
    private WebServer web;

    /** Wird alle 20 Ticks auf dem Server-Thread aktualisiert und ist fuer den Web-Thread nur lesbar. */
    private volatile List<PlayerSnapshot> playerSnapshot = List.of();
    private int tickCounter = 0;

    @Override
    public void onInitialize() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        config = BlaulixConfig.load(configDir.resolve("blaulixmap.json"));
        dataDir = configDir.resolve("blaulixmap");
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            LOGGER.error("Datenordner konnte nicht erstellt werden", e);
        }
        claims = new ClaimManager(config, dataDir.resolve("claims.json"));
        auth = new AuthManager(config);

        // Befehle (Brigadier): /claim ... und /map login
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ClaimCommands.register(dispatcher, this));

        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(this::onTick);
    }

    private void onServerStarted(MinecraftServer server) {
        if (!auth.adminEnabled()) {
            LOGGER.warn("admin_password_hash ist leer -> Admin-Login im Web ist deaktiviert (config/blaulixmap.json).");
        }
        Path worldRoot = findWorldRoot();
        LOGGER.info("Welt-Ordner fuer die Karte: {}", worldRoot.toAbsolutePath());
        tiles = new TileService(worldRoot, dataDir.resolve("tiles"), config.render_threads, config.tile_refresh_seconds);
        try {
            web = new WebServer(config, claims, auth, tiles, () -> playerSnapshot);
            web.start();
            LOGGER.info("Blaulix-Webkarte laeuft auf http://<server-ip>:{}/", config.web_port);
        } catch (Exception e) {
            LOGGER.error("Webserver konnte nicht gestartet werden (Port {} belegt?)", config.web_port, e);
        }
    }

    private void onServerStopping(MinecraftServer server) {
        if (web != null) web.stop();
        if (tiles != null) tiles.close();
        claims.shutdown();
        web = null;
        tiles = null;
    }

    /** Alle 20 Ticks (1 s) Spielerdaten kopieren - minimaler Aufwand, kein Lock noetig. */
    private void onTick(MinecraftServer server) {
        if (++tickCounter % 20 != 0) return;
        List<PlayerSnapshot> list = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            list.add(new PlayerSnapshot(p.getName().getString(), p.getUUID().toString(),
                    p.getX(), p.getY(), p.getZ(), dimensionId(p)));
        }
        playerSnapshot = List.copyOf(list);
    }

    /** Weltordner = Spielverzeichnis + level-name aus der server.properties. */
    private Path findWorldRoot() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        String levelName = "world";
        Path props = gameDir.resolve("server.properties");
        if (Files.exists(props)) {
            try (InputStream in = Files.newInputStream(props)) {
                Properties p = new Properties();
                p.load(in);
                levelName = p.getProperty("level-name", "world").trim();
            } catch (IOException ignored) { }
        }
        return gameDir.resolve(levelName).normalize();
    }

    /** Dimension eines Spielers als "overworld" / "nether" / "end" (sonst bereinigter Pfad). */
    public static String dimensionId(ServerPlayer p) {
        var key = p.level().dimension();
        if (key == Level.OVERWORLD) return "overworld";
        if (key == Level.NETHER) return "nether";
        if (key == Level.END) return "end";
        String s = key.toString(); // ResourceKey[minecraft:dimension / namespace:path]
        int i = s.lastIndexOf(" / ");
        if (i >= 0) s = s.substring(i + 3);
        return s.replace("]", "").replaceAll("[^a-z0-9_:.-]", "_");
    }

    public BlaulixConfig config() { return config; }
    public ClaimManager claims() { return claims; }
    public AuthManager auth() { return auth; }
}
