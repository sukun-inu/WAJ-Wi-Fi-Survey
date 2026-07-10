package com.opensitesurvey.tool.ui.survey;

import com.opensitesurvey.tool.model.SurveyPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Ordinary Kriging interpolation over survey points, in the same normalized (0..1) coordinate
 * space as {@link IdwInterpolator}. Unlike IDW's fixed inverse-square-distance weighting, Kriging
 * derives its weights from a semivariogram describing how much the *actual measured values*
 * co-vary with distance in this specific survey - it can produce a smoother, statistically
 * better-founded estimate when points are unevenly spaced, at the cost of solving a small linear
 * system (an (n+1)x(n+1) matrix for n contributing points).
 *
 * <p>A spherical variogram model is used, with the range set to half the maximum pairwise distance
 * among contributing points and the (partial) sill set to their sample variance (floored at 1.0
 * dBm² so a set of identical-valued points never produces a degenerate all-zero variogram) - a
 * standard, dependency-free heuristic rather than a properly fitted variogram (this app has no
 * numerics library to lean on for fitting one - see pom.xml), chosen so the algorithm degrades
 * gracefully rather than requiring the user to tune variogram parameters by hand.
 *
 * <p><b>Not thread-safe</b>: caches the last-solved matrix inverse, keyed on the exact point-list
 * snapshot/target combination, since {@link HeatmapRenderer} calls {@link #interpolate} once per
 * heatmap-grid cell (thousands of times per redraw) for the *same* point set - only the per-cell
 * distance vector actually changes between those calls. Without this cache, re-solving the linear
 * system from scratch on every single cell would make a redraw noticeably slow once a survey has
 * more than a few dozen points. Every caller in this app only ever touches survey data from the
 * JavaFX Application thread, so this is safe in practice.
 */
public final class KrigingInterpolator implements Interpolator {

    public static final KrigingInterpolator INSTANCE = new KrigingInterpolator();

    private static final double MIN_RANGE = 0.02;
    private static final double MIN_SILL = 1.0;
    private static final double SINGULAR_PIVOT_THRESHOLD = 1e-12;
    private static final double EXACT_MATCH_DISTANCE = 1e-9;

    private List<SurveyPoint> cachedSnapshot;
    private String cachedTargetBssid;
    private List<SurveyPoint> cachedContributors;
    private double[] cachedValues;
    // null whenever the system was singular (e.g. two contributing points at the exact same
    // location) - interpolate() falls back to IDW in that case rather than propagate garbage.
    private double[][] cachedInverse;
    private double cachedRange;
    private double cachedSill;

    private KrigingInterpolator() {
    }

    @Override
    public Double interpolate(double x, double y, List<SurveyPoint> points, String targetBssid) {
        ensureSolved(points, targetBssid);

        int n = cachedContributors.size();
        if (n == 0) {
            return null;
        }
        if (n == 1) {
            return cachedValues[0];
        }
        if (cachedInverse == null) {
            return IdwInterpolator.INSTANCE.interpolate(x, y, points, targetBssid);
        }

        double[] rhs = new double[n + 1];
        for (int i = 0; i < n; i++) {
            SurveyPoint p = cachedContributors.get(i);
            double dx = p.xNorm - x;
            double dy = p.yNorm - y;
            double h = Math.sqrt(dx * dx + dy * dy);
            if (h < EXACT_MATCH_DISTANCE) {
                return cachedValues[i];
            }
            rhs[i] = variogram(h);
        }
        rhs[n] = 1.0;

        double estimate = 0;
        for (int i = 0; i < n; i++) {
            double lambda = 0;
            for (int j = 0; j <= n; j++) {
                lambda += cachedInverse[i][j] * rhs[j];
            }
            estimate += lambda * cachedValues[i];
        }
        return estimate;
    }

    private double variogram(double h) {
        if (h >= cachedRange) {
            return cachedSill;
        }
        double r = h / cachedRange;
        return cachedSill * (1.5 * r - 0.5 * r * r * r);
    }

    private void ensureSolved(List<SurveyPoint> points, String targetBssid) {
        if (cachedSnapshot != null && cachedSnapshot.equals(points) && Objects.equals(cachedTargetBssid, targetBssid)) {
            return;
        }
        cachedSnapshot = List.copyOf(points);
        cachedTargetBssid = targetBssid;

        List<SurveyPoint> contributors = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (SurveyPoint p : points) {
            Integer v = p.rssiFor(targetBssid);
            if (v != null) {
                contributors.add(p);
                values.add(v.doubleValue());
            }
        }
        cachedContributors = contributors;
        cachedValues = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            cachedValues[i] = values.get(i);
        }
        cachedInverse = null;
        if (contributors.size() < 2) {
            return;
        }

        int n = contributors.size();
        double maxDist = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double dx = contributors.get(i).xNorm - contributors.get(j).xNorm;
                double dy = contributors.get(i).yNorm - contributors.get(j).yNorm;
                maxDist = Math.max(maxDist, Math.sqrt(dx * dx + dy * dy));
            }
        }
        cachedRange = Math.max(maxDist * 0.5, MIN_RANGE);

        double mean = 0;
        for (double v : cachedValues) {
            mean += v;
        }
        mean /= n;
        double variance = 0;
        for (double v : cachedValues) {
            variance += (v - mean) * (v - mean);
        }
        variance /= n;
        cachedSill = Math.max(variance, MIN_SILL);

        // Ordinary kriging system: [Gamma 1; 1^T 0] * [lambda; mu] = [gamma0; 1] - the augmented
        // (n+1)x(n+1) matrix here is the left-hand side, shared by every grid cell regardless of
        // where it is (only the right-hand side gamma0 varies per cell), which is exactly what
        // makes caching its inverse across a whole redraw worthwhile.
        double[][] gamma = new double[n + 1][n + 1];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    double dx = contributors.get(i).xNorm - contributors.get(j).xNorm;
                    double dy = contributors.get(i).yNorm - contributors.get(j).yNorm;
                    gamma[i][j] = variogram(Math.sqrt(dx * dx + dy * dy));
                }
            }
            gamma[i][n] = 1.0;
            gamma[n][i] = 1.0;
        }
        cachedInverse = invert(gamma);
    }

    /** Gauss-Jordan matrix inversion with partial pivoting. @return {@code null} if singular. */
    private static double[][] invert(double[][] m) {
        int n = m.length;
        double[][] a = new double[n][n];
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(m[i], 0, a[i], 0, n);
            inv[i][i] = 1.0;
        }
        for (int col = 0; col < n; col++) {
            int pivotRow = col;
            double best = Math.abs(a[col][col]);
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(a[r][col]) > best) {
                    best = Math.abs(a[r][col]);
                    pivotRow = r;
                }
            }
            if (best < SINGULAR_PIVOT_THRESHOLD) {
                return null;
            }
            if (pivotRow != col) {
                double[] tmp = a[col];
                a[col] = a[pivotRow];
                a[pivotRow] = tmp;
                tmp = inv[col];
                inv[col] = inv[pivotRow];
                inv[pivotRow] = tmp;
            }
            double pivot = a[col][col];
            for (int j = 0; j < n; j++) {
                a[col][j] /= pivot;
                inv[col][j] /= pivot;
            }
            for (int r = 0; r < n; r++) {
                if (r == col) {
                    continue;
                }
                double factor = a[r][col];
                if (factor == 0) {
                    continue;
                }
                for (int j = 0; j < n; j++) {
                    a[r][j] -= factor * a[col][j];
                    inv[r][j] -= factor * inv[col][j];
                }
            }
        }
        return inv;
    }
}
