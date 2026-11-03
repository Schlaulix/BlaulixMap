package at.blaulix.blaulixmap;

/** Unveraenderlicher Schnappschuss eines Online-Spielers (wird auf dem Server-Thread erzeugt, vom Web-Thread gelesen). */
public record PlayerSnapshot(String name, String uuid, double x, double y, double z, String dimension) {}
