# Blaulix Map & Claims (Fabric, Minecraft 26.3, serverseitig)

Mod-ID `blaulixmap` – Main-Klasse `at.blaulix.blaulixmap.BlaulixMap`. Läuft rein serverseitig, Vanilla-Clients können joinen.

## Build
```
./gradlew build          # Java 25 nötig
```
Jar: `build/libs/blaulixmap-1.0.0.jar` → in `mods/` des Servers (zusammen mit Fabric API).

## Befehle
`/claim create <name>` · `/claim addpoint` · `/claim confirm` · `/claim cancel` · `/claim list` · `/claim remove <name>` · `/map login`

## Config `config/blaulixmap.json`
`max_claims_per_player`, `max_claim_area_blocks`, `web_port`, `admin_password_hash` (Klartext oder `sha256:<hex>`; leer = Admin-Login aus),
`tile_refresh_seconds`, `render_threads`, `admin_players` (Namen/UUIDs, die `/claim remove` für fremde Claims nutzen dürfen; zusätzlich werden OPs per Level 2 erkannt).

## Daten
Claims: `config/blaulixmap/claims.json` · Kachel-Cache: `config/blaulixmap/tiles/`

## Karte
Kacheln werden beim ersten Aufruf asynchron (eigene, niedrig priorisierte Threads) direkt aus den `.mca`-Dateien gerendert – Chunky-Welten funktionieren ohne Spieler-Besuch. Web: `http://<server>:<web_port>/`.
