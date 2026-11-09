package com.invigilo.allocation.engine;

import java.util.Arrays;

/**
 * Minimum-cost assignment of n rows to m columns (n ≤ m), O(n²m). Deterministic: ties are
 * broken by the lowest column index. Classic potentials formulation (Kuhn–Munkres).
 */
final class Hungarian {

    private Hungarian() {}

    /** @return for each row, the column assigned to it */
    static int[] solve(long[][] cost) {
        int n = cost.length;
        if (n == 0) return new int[0];
        int m = cost[0].length;
        if (m < n) throw new IllegalArgumentException("More rows than columns");
        long[] u = new long[n + 1];
        long[] v = new long[m + 1];
        int[] p = new int[m + 1];   // p[j] = row (1-based) matched to column j
        int[] way = new int[m + 1];
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            long[] minv = new long[m + 1];
            Arrays.fill(minv, Long.MAX_VALUE);
            boolean[] used = new boolean[m + 1];
            do {
                used[j0] = true;
                int i0 = p[j0];
                long delta = Long.MAX_VALUE;
                int j1 = 0;
                for (int j = 1; j <= m; j++) {
                    if (!used[j]) {
                        long cur = cost[i0 - 1][j - 1] - u[i0] - v[j];
                        if (cur < minv[j]) {
                            minv[j] = cur;
                            way[j] = j0;
                        }
                        if (minv[j] < delta) {
                            delta = minv[j];
                            j1 = j;
                        }
                    }
                }
                for (int j = 0; j <= m; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }
        int[] rowToCol = new int[n];
        for (int j = 1; j <= m; j++) {
            if (p[j] != 0) rowToCol[p[j] - 1] = j - 1;
        }
        return rowToCol;
    }
}
