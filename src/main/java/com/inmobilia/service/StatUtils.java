package com.inmobilia.service;

/**
 * Estadistica descriptiva. Replica np.mean / np.std / np.percentile / skew / kurtosis.
 */
public final class StatUtils {
    private StatUtils() {}

    public static double mean(double[] v) {
        if (v == null || v.length == 0) return 0;
        double s = 0;
        for (double x : v) s += x;
        return s / v.length;
    }

    public static double std(double[] v) {
        if (v == null || v.length == 0) return 0;
        double m = mean(v);
        double sq = 0;
        for (double x : v) sq += (x - m) * (x - m);
        return Math.sqrt(sq / v.length);
    }

    /** Percentil lineal como np.percentile. v debe estar ordenado ASC. */
    public static double percentile(double[] v, double p) {
        if (v == null || v.length == 0) return 0;
        double[] sorted = v.clone();
        java.util.Arrays.sort(sorted);
        double rank = p / 100.0 * (sorted.length - 1);
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        if (low == high) return sorted[low];
        double frac = rank - low;
        return sorted[low] + frac * (sorted[high] - sorted[low]);
    }

    public static double skewness(double[] v) {
        if (v == null || v.length < 3) return 0;
        double m = mean(v);
        double s = std(v);
        if (s == 0) return 0;
        double acc = 0;
        for (double x : v) acc += Math.pow((x - m) / s, 3);
        return acc / v.length;
    }

    public static double kurtosis(double[] v) {
        if (v == null || v.length < 4) return 0;
        double m = mean(v);
        double s = std(v);
        if (s == 0) return 0;
        double acc = 0;
        for (double x : v) acc += Math.pow((x - m) / s, 4);
        return acc / v.length - 3; // exceso de kurtosis
    }
}
