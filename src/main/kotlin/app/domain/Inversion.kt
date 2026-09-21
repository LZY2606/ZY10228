package app.domain

import app.model.Candidate
import app.model.Cluster
import app.model.Diagnostics
import app.model.Endmember
import app.model.PairCorrelation
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 端元组合反演。测量模型 y = A c，c >= 0：
 *   A 的列为“网格覆盖行上”的端元向量（统一口径、不先归一化，保留各自总量）。
 *
 * 为应对端元近线性相关（非可辨识），不做唯一小数位稳定的比例，而是生成一族
 * 确定性、可复现的稀疏候选：
 *   1) 全端元加权 NNLS；
 *   2) 所有列子集（大小 1..maxSubsetSize）的非负最小二乘；
 *   3) Lasso 坐标下降路径上的一组 lambda 断点；
 * 候选经支持集/系数去重，按 BIC 排序，再按系数距离聚类成“候选解簇”。
 */
object Inversion {

    const val NEAR_COSINE = 0.985

    data class Outcome(
        val candidates: List<Candidate>,
        val clusters: List<Cluster>,
        val diagnostics: Diagnostics,
        val fitted: DoubleArray,
        val residuals: DoubleArray,
        val weightedResiduals: DoubleArray
    )

    private data class RawCand(
        val coeffs: DoubleArray,
        val support: List<Int>,
        val rss: Double,
        val weightedRss: Double,
        val bic: Double
    )

    fun invert(
        y: DoubleArray,
        endColumns: List<DoubleArray>,
        endmembers: List<Endmember>,
        covered: BooleanArray,
        relSigma: Double,
        corrLength: Double,
        maxSubsetSize: Int,
        maxCandidates: Int
    ): Outcome {
        val rows = covered.indices.filter { covered[it] }
        val m = rows.size
        val p = endColumns.size
        require(m > 0) { "共同网格上没有任何覆盖行，无法反演" }
        require(p >= 1) { "至少需要一个端元" }

        val aFull = Array(y.size) { r -> DoubleArray(p) { c -> endColumns[c][r] } }
        val a = Array(m) { i -> DoubleArray(p) { c -> aFull[rows[i]][c] } }
        val b = DoubleArray(m) { i -> y[rows[i]] }

        // 测量协方差：逐端元相对标准差与指数相关长度（对数箱距）合成，
        // C = sum_j a_ij a_kj sig_ij sig_kj exp(-|i-k|/corr_j) + 地板方差，
        // Cholesky 白化后做加权 NNLS；相关长度为 0 时退化为对角加权。
        val emSigma = DoubleArray(p) { c ->
            val rs = endmembers[c].relSigma.filter { it > 0.0 }
            if (rs.isNotEmpty()) rs.average() else relSigma
        }
        val emCorr = DoubleArray(p) { c ->
            if (endmembers[c].corrLength > 0.0) endmembers[c].corrLength else corrLength
        }
        // 参考尺度：观测 RMS，作为小信号箱方差地板，避免 1/y 型权重在低值箱爆炸。
        val obsScale = sqrt(b.sumOf { it * it } / max(1, m))
        val cov = Array(m) { DoubleArray(m) }
        for (i in 0 until m) {
            var predicted = 0.0
            for (c in 0 until p) predicted += a[i][c] * a[i][c]
            cov[i][i] = (0.02 * obsScale) * (0.02 * obsScale)
            for (c in 0 until p) {
                val sig = max(emSigma[c], 1e-6)
                cov[i][i] += a[i][c] * a[i][c] * sig * sig
            }
            for (k in i + 1 until m) {
                var s = 0.0
                for (c in 0 until p) {
                    val sig = max(emSigma[c], 1e-6)
                    val rho = if (emCorr[c] <= 0.0) 0.0 else exp(-abs(i - k) / emCorr[c])
                    s += a[i][c] * a[k][c] * sig * sig * rho
                }
                cov[i][k] = s; cov[k][i] = s
            }
        }
        var tr = 0.0
        for (i in 0 until m) tr = max(tr, cov[i][i])
        var lchol: Array<DoubleArray>? = null
        var jitter = 1e-12 * max(1.0, tr)
        var guard = 0
        while (guard++ < 10 && lchol == null) {
            for (i in 0 until m) cov[i][i] += jitter
            lchol = Linalg.cholesky(cov)
            jitter *= 10.0
        }
        val lc = lchol ?: run {
            for (i in 0 until m) for (k in 0 until m) cov[i][k] = if (i == k) max(1.0, tr) else 0.0
            Linalg.cholesky(cov)!!
        }
        fun whiten(v: DoubleArray): DoubleArray {
            val z = DoubleArray(m)
            for (i in 0 until m) {
                var s = v[i]
                for (k in 0 until i) s -= lc[i][k] * z[k]
                z[i] = s / lc[i][i]
            }
            return z
        }
        // 候选生成采用对角近似加权（保持主动集/Lasso 的简洁与确定性），
        // 最终排序残差 wrss 与展示残差使用完整相关协方差白化。
        val winvSqrt = DoubleArray(m) { i -> if (cov[i][i] > 0) 1.0 / sqrt(cov[i][i]) else 1.0 }
        val aw = Array(m) { i -> DoubleArray(p) { c -> a[i][c] * winvSqrt[i] } }
        val bw = DoubleArray(m) { i -> b[i] * winvSqrt[i] }

        fun score(coeffs: DoubleArray): RawCand {
            var rss = 0.0
            var wrss = 0.0
            val wres = whiten(DoubleArray(m) { i -> b[i] - (0 until p).sumOf { a[i][it] * coeffs[it] } })
            for (i in 0 until m) {
                val e = b[i] - (0 until p).sumOf { a[i][it] * coeffs[it] }
                rss += e * e
                wrss += wres[i] * wres[i]
            }
            val k = coeffs.count { it > 1e-12 }
            // 以“相对测量方差”归一化似然，使 BIC 量纲稳定且随残差增大而增大；
            // 支持集规模项奖励稀疏，同量级拟合下少端元候选优先。
            val refVar = max(b.sumOf { it * it } / max(1, m), 1e-300)
            val nll = m * ln(max(rss / max(1, m), 1e-12 * refVar))
            val bic = nll + k * ln(m.toDouble())
            return RawCand(coeffs, coeffs.indices.filter { coeffs[it] > 1e-10 }, rss, wrss, bic)
        }

        val raws = LinkedHashMap<String, RawCand>()
        fun add(coeffs: DoubleArray) {
            val c = DoubleArray(p) { max(0.0, coeffs[it]) }
            if (c.all { it <= 1e-12 }) return
            val s = score(c)
            // 去重键：支持集 + 比例（两位小数桶），同桶只留 BIC 最优，
            // 避免 Lasso 路径上的微小漂移刷满候选列表、淹没不同稀疏结构。
            val frac = fractions(c)
            val key = s.support.joinToString(",") { it.toString() } + "|" +
                    frac.joinToString(",") { "%.2f".format(it) }
            val old = raws[key]
            if (old == null || s.bic < old.bic) raws[key] = s
        }

        // 1) 全端元 NNLS
        add(Linalg.nnls(aw, bw))
        // 2) 子集枚举（保持列顺序，组合确定性）
        val subLimit = min(maxSubsetSize, p)
        for (size in 1..subLimit) combinations(IntArray(0), 0, p, size) { cols ->
            val sub = Array(m) { DoubleArray(cols.size) { u -> a[it][cols[u]] * winvSqrt[it] } }
            val x = Linalg.nnls(sub, bw)
            val full = DoubleArray(p)
            for (u in cols.indices) full[cols[u]] = x[u]
            add(full)
        }
        // 3) Lasso 路径（坐标下降，软阈值；不做最终去偏，仅提供稀疏结构候选）
        addAllLasso(aw, bw, ::add)

        val ranked = raws.values.sortedWith(compareBy({ it.bic }, { it.weightedRss }))

        // 多样性选择：按支持集分组；轮转输出，让不同稀疏结构尽早出现。
        // 每个支持集组内先按 BIC 取代表，同结构最多保留 perGroup 个，
        // 保证近线性相关时可同时看到 {粗砂,粉砂} 与 {粗砂,细砂} 等组合。
        val keepTarget = maxCandidates
        val perGroup = 3
        val bySupport = LinkedHashMap<String, MutableList<RawCand>>()
        for (rc in ranked) {
            val key = rc.support.joinToString(",")
            val list = bySupport.getOrPut(key) { ArrayList() }
            if (list.size < perGroup) list.add(rc)
        }
        val supportGroups = bySupport.values.sortedBy { it.first().bic }
        val top = ArrayList<RawCand>()
        var round = 0
        while (top.size < keepTarget) {
            var added = false
            for (list in supportGroups) {
                if (round < list.size && top.size < keepTarget) { top.add(list[round]); added = true }
            }
            if (!added) break
            round++
        }

        // 聚类：比例向量距离 <= 0.08 归为一簇
        val clustersAssign = IntArray(top.size) { -1 }
        val clusterRows = ArrayList<ArrayList<Int>>()
        for (i in top.indices) {
            val fi = fractions(top[i].coeffs)
            var cid = -1
            for (k in clusterRows.indices) {
                val rep = top[clusterRows[k][0]].coeffs
                val fr = fractions(rep)
                val dist = sqrt((fi.indices.sumOf { (fi[it] - fr[it]) * (fi[it] - fr[it]) }))
                if (dist <= 0.08) { cid = k; break }
            }
            if (cid < 0) { cid = clusterRows.size; clusterRows.add(ArrayList()) }
            clustersAssign[i] = cid
            clusterRows[cid].add(i)
        }

        val clusters = clusterRows.mapIndexed { cid, members ->
            val mean = DoubleArray(p)
            for (idx in members) {
                val f = fractions(top[idx].coeffs)
                for (c in 0 until p) mean[c] += f[c] / members.size
            }
            val rep = members.minByOrNull { top[it].weightedRss }!!
            Cluster(
                id = cid,
                size = members.size,
                meanFractions = mean.toList(),
                meanWeightedRss = members.sumOf { top[it].weightedRss } / members.size,
                representativeRank = members.minOf { it } + 1
            )
        }

        val chosenCount = min(maxCandidates, top.size)
        val candidates = (0 until chosenCount).map { i ->
            val rc = top[i]
            Candidate(
                rank = i + 1,
                coeffs = rc.coeffs.toList(),
                fractions = fractions(rc.coeffs).toList(),
                support = rc.support,
                rss = rc.rss,
                weightedRss = rc.weightedRss,
                bic = rc.bic,
                clusterId = clustersAssign[i]
            )
        }

        // 可识别性诊断
        val norm = DoubleArray(p) { c -> sqrt((0 until m).sumOf { a[it][c] * a[it][c] }).coerceAtLeast(1e-300) }
        val pairs = ArrayList<PairCorrelation>()
        var minCos = 1.0
        for (i in 0 until p) for (j in i + 1 until p) {
            var dot = 0.0; var ni = 0.0; var nj = 0.0
            for (r in 0 until m) { dot += a[r][i] * a[r][j]; ni += a[r][i] * a[r][i]; nj += a[r][j] * a[r][j] }
            val cos = dot / sqrt(ni * nj).coerceAtLeast(1e-300)
            // Pearson 相关（去均值）
            val mi = (0 until m).sumOf { a[it][i] } / m
            val mj = (0 until m).sumOf { a[it][j] } / m
            var s1 = 0.0; var s2 = 0.0; var s3 = 0.0
            for (r in 0 until m) {
                val xi = a[r][i] - mi; val xj = a[r][j] - mj
                s1 += xi * xj; s2 += xi * xi; s3 += xj * xj
            }
            val pearson = if (s2 <= 0 || s3 <= 0) cos else s1 / sqrt(s2 * s3)
            minCos = min(minCos, abs(cos))
            if (abs(cos) >= NEAR_COSINE || abs(pearson) >= NEAR_COSINE) {
                pairs.add(PairCorrelation(endmembers[i].id, endmembers[j].id, pearson, cos, true))
            }
        }
        val ata = Linalg.matTmat(aw)
        var ataTrace = 0.0
        for (i in 0 until p) ataTrace = max(ataTrace, abs(ata[i][i]))
        for (i in 0 until p) ata[i][i] += 1e-10 * max(1.0, ataTrace)
        val eigApprox = eigenMinMax(ata)
        val cond = eigApprox.second / eigApprox.first.coerceAtLeast(1e-300)

        val topFractions = candidates.take(min(8, candidates.size)).map { it.fractions.toDoubleArray() }
        var spread = 0.0
        for (i in topFractions.indices) for (j in i + 1 until topFractions.size) {
            spread = max(spread, sqrt(topFractions[i].indices.sumOf {
                (topFractions[i][it] - topFractions[j][it]).let { d -> d * d }
            }))
        }
        val gap = if (ranked.size >= 2) kotlin.math.abs(ranked[1].weightedRss - ranked[0].weightedRss) /
                max(1e-300, ranked[0].weightedRss) else Double.POSITIVE_INFINITY
        val sparsities = candidates.map { it.support.size }
        val near = pairs.isNotEmpty()
        val identifiable = !near && cond < 1e8 && spread < 0.05
        val note = when {
            near -> "存在近线性相关端元（|cos|>=${NEAR_COSINE}）：比例不可唯一辨识，请查看多个稀疏候选与解簇"
            cond >= 1e8 -> "设计矩阵条件数过大，端元贡献在覆盖网格上接近退化"
            spread >= 0.05 -> "多个候选比例差异明显但拟合相当，端元可识别性弱"
            else -> "端元在覆盖网格上可区分，首位候选比例稳定"
        }
        val diag = Diagnostics(
            conditionNumber = if (cond.isFinite()) cond else Double.POSITIVE_INFINITY,
            minPairCosine = minCos,
            nearCollinearPairs = pairs,
            candidateSparsity = sparsities,
            profileSpread = spread,
            topGap = gap,
            identifiable = identifiable,
            note = note
        )

        val best = candidates.first().coeffs.toDoubleArray()
        val fittedAll = DoubleArray(y.size)
        val residAll = DoubleArray(y.size)
        val wresidAll = DoubleArray(y.size)
        for (i in 0 until m) {
            val row = rows[i]
            fittedAll[row] = (0 until p).sumOf { a[i][it] * best[it] }
            residAll[row] = b[i] - fittedAll[row]
            wresidAll[row] = 0.0 // filled below
        }

        val wFinal = whiten(DoubleArray(m) { i -> b[i] - fittedAll[rows[i]] })
        for (i in 0 until m) wresidAll[rows[i]] = wFinal[i]
        return Outcome(candidates, clusters, diag, fittedAll, residAll, wresidAll)
    }

    private fun fractions(c: DoubleArray): DoubleArray {
        val s = c.sum()
        return if (s <= 1e-300) DoubleArray(c.size) else DoubleArray(c.size) { c[it] / s }
    }

    private fun addAllLasso(aw: Array<DoubleArray>, bw: DoubleArray, add: (DoubleArray) -> Unit) {
        val m = aw.size; val p = aw[0].size
        var lambdaMax = 0.0
        for (j in 0 until p) {
            lambdaMax = max(lambdaMax, abs((0 until m).sumOf { aw[it][j] * bw[it] }) / m)
        }
        val lams = (0..24).map { lambdaMax * exp(-it * 0.35) }.filter { it > 1e-14 }.distinct()
        var x = DoubleArray(p)
        for (lam in lams) {
            x = lassoCoordinateDescent(aw, bw, lam, x)
            add(x)
        }
    }

    private fun lassoCoordinateDescent(
        a: Array<DoubleArray>, b: DoubleArray, lam: Double, warm: DoubleArray
    ): DoubleArray {
        val m = a.size; val p = a[0].size
        val x = warm.copyOf()
        val colNorm = DoubleArray(p) { j -> (0 until m).sumOf { a[it][j] * a[it][j] } }
        repeat(2000) {
            var maxDelta = 0.0
            for (j in 0 until p) {
                val n2 = colNorm[j]
                if (n2 <= 1e-300) continue
                var rho = 0.0
                for (i in 0 until m) {
                    val residual = b[i] - (0 until p).sumOf { k -> if (k == j) 0.0 else a[i][k] * x[k] }
                    rho += a[i][j] * residual
                }
                val soft = (abs(rho) - m * lam).coerceAtLeast(0.0)
                val nx = if (rho > 0) soft / n2 else -soft / n2
                maxDelta = max(maxDelta, abs(nx - x[j]))
                x[j] = if (nx > 1e-12) nx else 0.0
            }
            if (maxDelta < 1e-9) return x
        }
        return x
    }

    private fun combinations(prefix: IntArray, start: Int, n: Int, k: Int,
                             emit: (IntArray) -> Unit) {
        if (k == 0) { emit(prefix); return }
        for (i in start..n - k) {
            combinations(prefix + i, i + 1, n, k - 1, emit)
        }
    }

    /** Gershgorin 圆盘给出的最小/最大特征值近似（仅用于条件数量级诊断）。 */
    private fun eigenMinMax(a: Array<DoubleArray>): Pair<Double, Double> {
        var lo = Double.POSITIVE_INFINITY
        var hi = 0.0
        for (i in a.indices) {
            val off = (a[i].indices).filter { it != i }.sumOf { abs(a[i][it]) }
            lo = min(lo, a[i][i] - off)
            hi = max(hi, a[i][i] + off)
        }
        return max(lo, 1e-300) to max(hi, 1e-300)
    }
}
