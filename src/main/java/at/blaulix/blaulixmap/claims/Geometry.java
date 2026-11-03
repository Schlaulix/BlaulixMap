package at.blaulix.blaulixmap.claims;

import java.util.List;

/** Geometrie-Hilfen fuer Polygone in der X/Z-Ebene. */
public final class Geometry {
    private Geometry() {}

    /** Flaeche per Shoelace-Formel. */
    public static double area(List<double[]> p) {
        double s = 0;
        for (int i = 0; i < p.size(); i++) {
            double[] a = p.get(i), b = p.get((i + 1) % p.size());
            s += a[0] * b[1] - b[0] * a[1];
        }
        return Math.abs(s) / 2.0;
    }

    /** True, wenn sich zwei nicht benachbarte Kanten schneiden (Polygon ungueltig). */
    public static boolean selfIntersects(List<double[]> p) {
        int n = p.size();
        for (int i = 0; i < n; i++) {
            double[] a1 = p.get(i), a2 = p.get((i + 1) % n);
            for (int j = i + 1; j < n; j++) {
                // benachbarte Kanten teilen einen Eckpunkt -> ueberspringen
                if (j == i + 1 || (i == 0 && j == n - 1)) continue;
                double[] b1 = p.get(j), b2 = p.get((j + 1) % n);
                if (intersect(a1, a2, b1, b2)) return true;
            }
        }
        return false;
    }

    private static double orient(double[] a, double[] b, double[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }

    private static boolean onSeg(double[] a, double[] b, double[] c) {
        return Math.min(a[0], b[0]) <= c[0] && c[0] <= Math.max(a[0], b[0])
                && Math.min(a[1], b[1]) <= c[1] && c[1] <= Math.max(a[1], b[1]);
    }

    private static boolean intersect(double[] p1, double[] p2, double[] p3, double[] p4) {
        double d1 = orient(p3, p4, p1), d2 = orient(p3, p4, p2);
        double d3 = orient(p1, p2, p3), d4 = orient(p1, p2, p4);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return true;
        if (d1 == 0 && onSeg(p3, p4, p1)) return true;
        if (d2 == 0 && onSeg(p3, p4, p2)) return true;
        if (d3 == 0 && onSeg(p1, p2, p3)) return true;
        return d4 == 0 && onSeg(p1, p2, p4);
    }
}
