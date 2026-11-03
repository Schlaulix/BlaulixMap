package at.blaulix.blaulixmap.claims;

import java.util.List;

/** Ein Polygon-Claim ueber die gesamte Y-Achse. Wird 1:1 als JSON gespeichert/ausgeliefert. */
public class Claim {
    public String name;
    /** UUID des Besitzers (als String). */
    public String owner;
    public String ownerName;
    /** "overworld", "nether" oder "end". */
    public String dimension;
    /** Eckpunkte als [x, z]. */
    public List<double[]> points;
    /** Grundflaeche in Bloecken. */
    public double area;
    public long created;
}
