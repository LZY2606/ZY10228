package app.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** 小规模稠密线性代数：正定 Cholesky、求解、主动集非负最小二乘。 */
object Linalg {

    /** Cholesky 下三角 L（A = L L^T）；非正定返回 null。 */
    fun cholesky(a: Array<DoubleArray>): Array<DoubleArray>? {
        val n = a.size
        val l = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in 0..i) {
                var s = a[i][j]
                for (k in 0 until j) s -= l[i][k] * l[j][k]
                if (i == j) {
                    if (s <= 1e-18 * max(1.0, abs(a[i][i]))) return null
                    l[i][j] = sqrt(s)
                } else {
                    l[i][j] = s / l[j][j]
                }
            }
        }
        return l
    }

    fun solveSPD(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val l = cholesky(a) ?: error("矩阵非正定")
        return solveLLt(l, b)
    }

    /** 给定 L L^T x = b 求解。 */
    fun solveLLt(l: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val y = DoubleArray(n)
        for (i in 0 until n) {
            var s = b[i]
            for (k in 0 until i) s -= l[i][k] * y[k]
            y[i] = s / l[i][i]
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = y[i]
            for (k in i + 1 until n) s -= l[k][i] * x[k]
            x[i] = s / l[i][i]
        }
        return x
    }

    fun matTmat(a: Array<DoubleArray>): Array<DoubleArray> {
        val m = a.size; val p = a[0].size
        val ata = Array(p) { DoubleArray(p) }
        for (i in 0 until p) {
            for (j in i until p) {
                var s = 0.0
                for (r in 0 until m) s += a[r][i] * a[r][j]
                ata[i][j] = s; ata[j][i] = s
            }
        }
        return ata
    }

    fun matTvec(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val m = a.size; val p = a[0].size
        val atb = DoubleArray(p)
        for (j in 0 until p) {
            var s = 0.0
            for (r in 0 until m) s += a[r][j] * b[r]
            atb[j] = s
        }
        return atb
    }

    /** 列子集无约束最小二乘（带微岭），返回该子集坐标。 */
    fun lsColumns(a: Array<DoubleArray>, b: DoubleArray, cols: IntArray): DoubleArray {
        val k = cols.size
        val ata = Array(k) { DoubleArray(k) }
        val atb = DoubleArray(k)
        for (u in 0 until k) {
            for (v in u until k) {
                var s = 0.0
                for (r in a.indices) s += a[r][cols[u]] * a[r][cols[v]]
                ata[u][v] = s; ata[v][u] = s
            }
            var s = 0.0
            for (r in a.indices) s += a[r][cols[u]] * b[r]
            atb[u] = s
        }
        var tr = 0.0
        for (u in 0 until k) tr = max(tr, ata[u][u])
        val ridge = 1e-12 * max(1.0, tr)
        for (u in 0 until k) ata[u][u] += ridge
        val y = solveSPD(ata, atb)
        return y
    }

    /**
     * Lawson–Hanson 主动集 NNLS：min_x>=0 ||A x - b||。
     * p 很小（端元数），直接法即可，结果确定、无随机初始化。
     */
    fun nnls(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val m = a.size; val p = a[0].size
        val x = DoubleArray(p)
        val passive = BooleanArray(p)
        val tol = 1e-12 * (b.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
        while (true) {
            val r = DoubleArray(m) { rr -> b[rr] - (0 until p).sumOf { a[rr][it] * x[it] } }
            var jIn = -1; var wMax = tol
            for (j in 0 until p) {
                if (!passive[j]) {
                    val w = (0 until m).sumOf { a[it][j] * r[it] }
                    if (w > wMax) { wMax = w; jIn = j }
                }
            }
            if (jIn < 0) break
            passive[jIn] = true
            var loopGuard = 0
            while (loopGuard++ < 1000) {
                val cols = (0 until p).filter { passive[it] }.toIntArray()
                val xs = lsColumns(a, b, cols)
                if (xs.all { it >= -1e-10 }) {
                    for (u in cols.indices) x[cols[u]] = max(0.0, xs[u])
                    break
                }
                var alpha = Double.POSITIVE_INFINITY
                for (u in cols.indices) {
                    if (xs[u] <= 0.0) {
                        val old = x[cols[u]]
                        val cand = old / (old - xs[u])
                        if (cand in 0.0..alpha) alpha = cand
                    }
                }
                if (!alpha.isFinite() || alpha <= 0.0) alpha = 0.0
                for (u in cols.indices) {
                    val j = cols[u]
                    x[j] = x[j] + alpha * (xs[u] - x[j])
                }
                for (u in cols.indices) {
                    val j = cols[u]
                    if (passive[j] && x[j] <= 1e-14 && xs[u] <= 0.0) passive[j] = false
                }
            }
        }
        return x
    }
}
