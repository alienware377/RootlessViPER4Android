package me.timschneeberger.rootlessjamesdsp.matcheq

/**
 * Quadratic Savitzky-Golay smoothing.
 *
 * Every output point is the value, at that point, of the least-squares
 * parabola through the [window] points around it (A. Savitzky and M. J. E.
 * Golay, "Smoothing and differentiation of data by simplified least squares
 * procedures", Analytical Chemistry 36, 1964; R. W. Schafer, "What is a
 * Savitzky-Golay filter?", IEEE Signal Processing Magazine, 2011).
 *
 * In the middle that is a fixed weighted average. Near the ends there are not
 * enough points on one side, so the first and last half-windows are taken from
 * one parabola fitted to the first and last full window. That is the edge
 * handling AutoEq gets from SciPy's default, and it is what keeps a straight
 * or curved edge from being pulled towards the middle.
 */
object SavitzkyGolay {

    /** Centre weights for a quadratic fit over [window] points (odd, at least 3). */
    fun coefficients(window: Int): DoubleArray {
        require(window >= 3 && window % 2 == 1) { "window must be odd and at least 3" }
        val m = (window - 1) / 2
        // Closed form of the quadratic (and cubic) smoothing weights.
        val den = ((2 * m + 3) * (2 * m + 1) * (2 * m - 1)).toDouble()
        val a = 3.0 * (3 * m * m + 3 * m - 1)
        return DoubleArray(window) { i ->
            val j = (i - m).toDouble()
            (a - 15.0 * j * j) / den
        }
    }

    fun smooth(y: DoubleArray, window: Int): DoubleArray {
        val n = y.size
        if (n < 3) return y.copyOf()
        // A window wider than the data cannot be fitted; shrink it to fit.
        var w = window
        if (w > n) w = if (n % 2 == 1) n else n - 1
        if (w < 3) return y.copyOf()
        val m = (w - 1) / 2
        val c = coefficients(w)
        val out = DoubleArray(n)
        for (i in m until n - m) {
            var s = 0.0
            for (k in 0 until w) s += c[k] * y[i - m + k]
            out[i] = s
        }
        val head = fitQuadratic(y, 0, w)
        for (i in 0 until m) out[i] = head(i.toDouble())
        val tail = fitQuadratic(y, n - w, w)
        for (i in n - m until n) out[i] = tail((i - (n - w)).toDouble())
        return out
    }

    /**
     * Least-squares parabola through y[start until start + count] against
     * x = 0 until count. Returns it as a function of x.
     */
    fun fitQuadratic(y: DoubleArray, start: Int, count: Int): (Double) -> Double {
        // Centre x for a well-conditioned 3 by 3 system.
        val xc = (count - 1) / 2.0
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
        for (k in 0 until count) {
            val x = k - xc
            val v = y[start + k]
            val x2 = x * x
            s0 += 1.0; s1 += x; s2 += x2; s3 += x2 * x; s4 += x2 * x2
            t0 += v; t1 += x * v; t2 += x2 * v
        }
        val coef = solve3(
            arrayOf(
                doubleArrayOf(s0, s1, s2),
                doubleArrayOf(s1, s2, s3),
                doubleArrayOf(s2, s3, s4),
            ),
            doubleArrayOf(t0, t1, t2),
        )
        return { xRaw: Double ->
            val x = xRaw - xc
            coef[0] + coef[1] * x + coef[2] * x * x
        }
    }

    /** Gaussian elimination with partial pivoting for a 3 by 3 system. */
    private fun solve3(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = 3
        val m = Array(n) { r -> DoubleArray(n + 1) { c -> if (c < n) a[r][c] else b[r] } }
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (kotlin.math.abs(m[r][col]) > kotlin.math.abs(m[piv][col])) piv = r
            val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            val p = m[col][col]
            if (p == 0.0) return DoubleArray(n)
            for (r in 0 until n) {
                if (r == col) continue
                val f = m[r][col] / p
                for (c in col until n + 1) m[r][c] -= f * m[col][c]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }
}
