package app.domain

import kotlin.math.abs
import kotlin.math.sqrt

/** SPD Cholesky 求解（用于 NNLS 自由集上的法方程）。 */
fun solveSPD(A: Array<DoubleArray>, b: DoubleArray): DoubleArray {
    val n = A.size
    val L = Array(n) { DoubleArray(n) }
    for (i in 0 until n) {
        for (j in 0..i) {
            var s = A[i][j]
            for (k in 0 until j) s -= L[i][k] * L[j][k]
            if (i == j) {
                if (s <= 1e-14) throw IllegalArgumentException("矩阵非正定")
                L[i][j] = sqrt(s)
            } else L[i][j] = s / L[j][j]
        }
    }
    val y = DoubleArray(n)
    for (i in 0 until n) {
        var s = b[i]
        for (k in 0 until i) s -= L[i][k] * y[k]
        y[i] = s / L[i][i]
    }
    val x = DoubleArray(n)
    for (i in n - 1 downTo 0) {
        var s = y[i]
        for (k in i + 1 until n) s -= L[k][i] * x[k]
        x[i] = s / L[i][i]
    }
    return x
}

/**
 * 加权非负最小二乘：min_{x>=0} ||W(Ax-b)||^2，W=diag(w)。
 * 标准 Lawson–Hanson 活跃集算法。
 */
fun weightedNNLS(
    A: Array<DoubleArray>,
    b: DoubleArray,
    w: DoubleArray,
    maxIter: Int = 400
): DoubleArray {
    val m = A.size
    val p = if (m == 0) 0 else A[0].size
    if (m == 0 || p == 0) return DoubleArray(0)

    val Wa = Array(m) { i -> DoubleArray(p) { j -> w[i] * A[i][j] } }
    val wb = DoubleArray(m) { i -> w[i] * b[i] }
    val x = DoubleArray(p)
    val free = BooleanArray(p) // true = 自由（>0 候选）
    val ridge = 1e-10

    fun residual() = DoubleArray(m) { i ->
        var s = wb[i]
        for (j in 0 until p) s -= Wa[i][j] * x[j]
        s // r = Wb - Wa x（注意符号，梯度用）
    }

    fun grad(): DoubleArray {
        // f=0.5||Wa x - Wb||^2 -> grad = Wa^T(Wa x - Wb) = -Wa^T r
        val r = residual()
        return DoubleArray(p) { j ->
            var g = 0.0
            for (i in 0 until m) g -= Wa[i][j] * r[i]
            g
        }
    }

    fun freeSolve(activeCols: IntArray): DoubleArray {
        val q = activeCols.size
        val AtA = Array(q) { DoubleArray(q) }
        val Atb = DoubleArray(q)
        for (u in 0 until q) for (v in 0 until q) {
            val cu = activeCols[u]; val cv = activeCols[v]
            var s = 0.0
            for (i in 0 until m) s += Wa[i][cu] * Wa[i][cv]
            AtA[u][v] = s + if (u == v) ridge else 0.0
        }
        for (u in 0 until q) {
            val cu = activeCols[u]
            var s = 0.0
            for (i in 0 until m) s += Wa[i][cu] * wb[i]
            Atb[u] = s
        }
        return solveSPD(AtA, Atb)
    }

    var outer = 0
    while (outer++ < maxIter) {
        // 1) 在被约束为 0 的变量中，选梯度最负者加入自由集
        val g = grad()
        var jIn = -1
        var best = -1e-13
        for (j in 0 until p) {
            if (!free[j] && g[j] < best) { best = g[j]; jIn = j }
        }
        if (jIn < 0) break
        free[jIn] = true

        var inner = 0
        while (inner++ < maxIter) {
            val cols = (0 until p).filter { free[it] }.toIntArray()
            val s = freeSolve(cols)
            if (s.all { it >= -1e-10 }) {
                cols.indices.forEach { u -> x[cols[u]] = maxOf(0.0, s[u]) }
                break
            }
            // 2) 内插：把会变负的自由变量按最大步长 alpha 移回约束集
            var alpha = Double.POSITIVE_INFINITY
            var jOut = -1
            for (u in cols.indices) {
                if (s[u] < 0.0) {
                    val xj = x[cols[u]]
                    val denom = xj - s[u]
                    if (denom > 0.0) {
                        val t = xj / denom
                        if (t in 0.0..alpha) { alpha = t; jOut = cols[u] }
                    }
                }
            }
            if (jOut < 0) { // 退化兜底
                val neg = cols.firstOrNull { s[it] < 0.0 }?.let { cols[it] } ?: cols[0]
                free[neg] = false
                if (neg == jIn) break
                continue
            }
            for (u in cols.indices) {
                val col = cols[u]
                x[col] = x[col] + alpha * (s[u] - x[col])
                if (abs(x[col]) < 1e-13) x[col] = 0.0
            }
            free[jOut] = false
            if (jOut == jIn) break
            x[jIn] = maxOf(0.0, x[jIn])
        }
    }
    return x
}

fun weightedSSE(A: Array<DoubleArray>, b: DoubleArray, w: DoubleArray, x: DoubleArray): Double {
    var sse = 0.0
    for (i in A.indices) {
        var pred = 0.0
        for (j in x.indices) pred += A[i][j] * x[j]
        val e = w[i] * (pred - b[i])
        sse += e * e
    }
    return sse
}
