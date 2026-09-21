package app.domain

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** 单个候选解。 */
@Serializable
data class Candidate(
    val proportions: Map<String, Double>,
    val activeNames: List<String>,
    val sse: Double,
    val rmse: Double,
    val bic: Double,
    val clusterId: Int = 0
)

/** 可识别性诊断。 */
@Serializable
data class Identifiability(
    val minAbsCorrelation: Double,
    val nearCollinearPairs: List<CorrPair>,
    val conditionNumber: Double,
    val note: String
)

@Serializable
data class CorrPair(val a: String, val b: String, val corr: Double)

/** 反演结果。 */
@Serializable
data class SolveResult(
    val assumptions: SolveAssumptions,
    val gridName: String,
    val gridCentersUm: List<Double>,
    val targetBasis: WeightBasis,
    val observedConverted: List<Double>,
    val fitted: List<Double>,
    val residual: List<Double>,
    val obsUncovered: Double,
    val obsTotalRaw: Double,
    val endmemberUncovered: Map<String, Double>,
    val weights: Map<String, DoubleArrayDTO>,
    val candidates: List<Candidate>,
    val clusters: List<Cluster>,
    val best: Candidate,
    val identifiability: Identifiability
)

@Serializable
data class DoubleArrayDTO(val v: List<Double>)

@Serializable
data class Cluster(
    val id: Int,
    val memberIndices: List<Int>,
    val centroid: Map<String, Double>,
    val spread: Double,
    val minSse: Double
)

/** 观测在共同网格上的已转换分布及其逐箱标准差（目标口径）。 */
data class PreparedObservation(
    val y: DoubleArray,
    val sigma: DoubleArray,
    val uncovered: Double,
    val totalRaw: Double
)

/** 准备观测：投影 + 口径转换 + 观测权重（仪器方差或默认 5% 相对误差地板）。 */
fun prepareObservation(
    raw: RawDistribution,
    grid: LogGrid,
    assumptions: SolveAssumptions,
    density: Double,
    relSigma: Double = 0.05,
    absSigmaFloorFraction: Double = 0.002
): PreparedObservation {
    val proj = projectToGrid(raw, grid)
    val converted = convertBasis(proj.onGrid, grid, raw.basis, assumptions.targetBasis, density, assumptions.uniformDensity)
    val floor = absSigmaFloorFraction * (converted.maxOrNull() ?: 1.0)
    val sigma = converted.map { (relSigma * abs(it) + floor) }.toDoubleArray()
    return PreparedObservation(converted.toDoubleArray(), sigma, proj.uncovered, proj.totalRaw)
}

/** 端元矩阵列：端元投影到共同网格并转换到目标口径；记录其网格外失量。 */
data class PreparedEndmember(
    val name: String,
    val column: DoubleArray,
    val uncovered: Double,
    val rawTotal: Double
)

fun prepareEndmember(em: Endmember, grid: LogGrid, assumptions: SolveAssumptions): PreparedEndmember {
    // 端元以原始口径给值：视为覆盖 [grid 全域] 的网格谱，故网格外为 0；
    // 但允许端元只定义在较窄范围，用其自身值的零点不视为失量。
    val densityTo = if (assumptions.densityModel == "uniform-density") assumptions.uniformDensity else em.density
    val col = convertBasis(em.values, grid, em.basis, assumptions.targetBasis, em.density, densityTo)
    return PreparedEndmember(em.name, col.toDoubleArray(), 0.0, em.values.sum())
}

private fun matrixFrom(cols: List<DoubleArray>): Array<DoubleArray> {
    val m = cols[0].size
    return Array(m) { i -> DoubleArray(cols.size) { j -> cols[j][i] } }
}

/** 端元间加权相关矩阵，用于近线性相关诊断。 */
fun weightedCorrelation(A: Array<DoubleArray>, w: DoubleArray): Array<DoubleArray> {
    val p = A[0].size
    val C = Array(p) { DoubleArray(p) }
    val norm = DoubleArray(p)
    for (j in 0 until p) {
        var s = 0.0
        for (i in A.indices) { val z = w[i] * A[i][j]; s += z * z }
        norm[j] = sqrt(s).coerceAtLeast(1e-300)
    }
    for (a in 0 until p) for (b in 0 until p) {
        var s = 0.0
        for (i in A.indices) s += (w[i] * A[i][a]) * (w[i] * A[i][b])
        C[a][b] = s / (norm[a] * norm[b])
    }
    return C
}

/** 反演主入口。保留多个稀疏候选并做可识别性诊断。 */
fun solve(
    grid: LogGrid,
    observation: PreparedObservation,
    endmembers: List<PreparedEndmember>,
    rawEndmembers: List<Endmember>,
    assumptions: SolveAssumptions
): SolveResult {
    require(endmembers.size >= 2) { "至少需要两个端元" }
    val names = endmembers.map { it.name }
    val A = matrixFrom(endmembers.map { it.column })
    val m = A.size
    val p = A[0].size
    val w = DoubleArray(m) { i -> 1.0 / observation.sigma[i] }
    val y = observation.y

    // 权重（每列 WNNLS 中实际使用的逐箱权重）
    val weightDto = mapOf("w" to DoubleArrayDTO(w.toList()))

    // ---- 多候选：全列 + 每个端元被剔除的子集（覆盖近相关时的替代稀疏解）----
    val candidates = mutableListOf<Candidate>()
    fun runSubset(cols: List<Int>) {
        val Asub = Array(m) { i -> DoubleArray(cols.size) { k -> A[i][cols[k]] } }
        val xsub = weightedNNLS(Asub, y, w)
        val x = DoubleArray(p)
        for ((k, j) in cols.withIndex()) x[j] = xsub[k]
        val sse = weightedSSE(A, y, w, x)
        val active = cols.filter { x[it] > 1e-7 }
        val rmse = sqrt(sse / m)
        val kEff = active.size
        val bic = m * ln(sse / m + 1e-300) + kEff * ln(m.toDouble())
        candidates.add(
            Candidate(
                proportions = names.indices.associateWith { x[it] }.mapKeys { names[it.key] },
                activeNames = active.map { names[it] },
                sse = sse, rmse = rmse, bic = bic
            )
        )
    }
    runSubset((0 until p).toList())
    for (j in 0 until p) runSubset((0 until p).filterNot { it == j })
    // 成对子集（两个端元），尤其针对“两个近相关端元各自主导”的两个稀疏候选
    for (a in 0 until p) for (b in a + 1 until p) runSubset(listOf(a, b))

    // 去重 + 排序：按 SSE 升序
    val unique = dedupCandidates(candidates, names)
    val ranked = unique.sortedWith(compareBy({ it.sse }, { it.activeNames.size }))

    // ---- 候选聚类 ----
    val clusters = clusterCandidates(ranked, names)
    val candidateWithCluster = ranked.mapIndexed { idx, c -> c.copy(clusterId = clusterOf(idx, clusters, ranked)) }
    val clusteredList = reconcileClusters(clusters, ranked, names)
    val best = candidateWithCluster.first()

    // 拟合/残差用最佳解
    val xb = DoubleArray(p) { j -> best.proportions[names[j]] ?: 0.0 }
    val fitted = DoubleArray(m) { i -> var s=0.0; for(j in 0 until p) s += A[i][j]*xb[j]; s }
    val residual = DoubleArray(m) { i -> fitted[i] - y[i] }

    // ---- 可识别性（在共同网格上、未掩码的端元原型之间计算）----
    val rep = grid.size
    val proto = Array(rep) { i -> DoubleArray(p) { j -> endmembers[j].column[i] } }
    val unitW = DoubleArray(rep) { 1.0 }
    val corr = weightedCorrelation(proto, unitW)
    var minAbs = 1.0
    val nearPairs = mutableListOf<CorrPair>()
    for (a in 0 until p) for (b in a + 1 until p) {
        val cc = corr[a][b]
        if (abs(cc) < minAbs) minAbs = abs(cc)
        if (abs(cc) >= 0.90) nearPairs.add(CorrPair(names[a], names[b], cc))
    }
    val cond = approxConditionNumber(A, w)
    val note = when {
        nearPairs.isNotEmpty() ->
            "存在近线性相关端元对（|相关|>=0.90），比例不可唯一识别；请并列查看多个稀疏候选与其簇，勿只取单一比例。"
        cond > 1e4 ->
            "端元矩阵条件数偏大，比例估计对噪声敏感，建议关注候选稳定性。"
        else -> "端元间区分度良好，比例可较稳定识别。"
    }

    return SolveResult(
        assumptions = assumptions,
        gridName = grid.name,
        gridCentersUm = grid.centersUm,
        targetBasis = assumptions.targetBasis,
        observedConverted = y.toList(),
        fitted = fitted.toList(),
        residual = residual.toList(),
        obsUncovered = observation.uncovered,
        obsTotalRaw = observation.totalRaw,
        endmemberUncovered = endmembers.associate { it.name to it.uncovered },
        weights = weightDto,
        candidates = candidateWithCluster,
        clusters = clusteredList,
        best = best,
        identifiability = Identifiability(minAbs, nearPairs, cond, note)
    )
}

private fun approxConditionNumber(A: Array<DoubleArray>, w: DoubleArray): Double {
    // 用加权 Gram 矩阵的迹/最小对角占裕近似，避免引入特征值库；对近相关诊断足够。
    val p = A[0].size
    val G = Array(p) { DoubleArray(p) }
    for (a in 0 until p) for (b in 0 until p) {
        var s = 0.0
        for (i in A.indices) s += (w[i] * A[i][a]) * (w[i] * A[i][b])
        G[a][b] = s
    }
    var tr = 0.0; var mind = Double.POSITIVE_INFINITY
    for (a in 0 until p) { tr += G[a][a]; mind = minOf(mind, G[a][a]) }
    val avg = tr / p
    return avg / mind.coerceAtLeast(1e-300)
}

private fun candidateVector(c: Candidate, names: List<String>): DoubleArray =
    DoubleArray(names.size) { c.proportions[names[it]] ?: 0.0 }

private fun dedupCandidates(list: List<Candidate>, names: List<String>): List<Candidate> {
    val out = mutableListOf<Candidate>()
    for (c in list) {
        val dup = out.any { d ->
            val v1 = candidateVector(c, names); val v2 = candidateVector(d, names)
            var diff = 0.0; var norm = 0.0
            for (i in v1.indices) { diff += (v1[i]-v2[i]).let{it*it}; norm += v2[i]*v2[i] }
            diff <= 1e-10 * (norm + 1e-12)
        }
        if (!dup) out.add(c)
    }
    return out
}

private fun clusterCandidates(ranked: List<Candidate>, names: List<String>): List<MutableList<Int>> {
    val clusters = mutableListOf<MutableList<Int>>()
    for (idx in ranked.indices) {
        val v = candidateVector(ranked[idx], names)
        val norm = sqrt(v.sumOf { it * it }).coerceAtLeast(1e-300)
        var placed = false
        for (cl in clusters) {
            val rep = candidateVector(ranked[cl.first()], names)
            val repNorm = sqrt(rep.sumOf { it*it }).coerceAtLeast(1e-300)
            var dot = 0.0
            for (i in v.indices) dot += v[i]*rep[i]
            val cos = dot/(norm*repNorm)
            if (cos >= 0.985) { cl.add(idx); placed = true; break }
        }
        if (!placed) clusters.add(mutableListOf(idx))
    }
    return clusters
}

// 先计算候选->簇映射后，再构造对外簇对象
private fun clusterOf(candidateIndexInRanked: Int, raw: List<MutableList<Int>>, ranked: List<Candidate>): Int {
    for ((ci, cl) in raw.withIndex()) if (candidateIndexInRanked in cl) return ci
    return 0
}

private fun reconcileClusters(raw: List<MutableList<Int>>, ranked: List<Candidate>, names: List<String>): List<Cluster> {
    return raw.mapIndexed { ci, members ->
        val centroid = DoubleArray(names.size)
        for (mi in members) {
            val v = candidateVector(ranked[mi], names)
            for (i in v.indices) centroid[i] += v[i]
        }
        for (i in centroid.indices) centroid[i] /= members.size
        var spread = 0.0
        for (mi in members) {
            val v = candidateVector(ranked[mi], names)
            for (i in v.indices) spread += (v[i]-centroid[i]).let{it*it}
        }
        spread = sqrt(spread / members.size)
        Cluster(
            id = ci,
            memberIndices = members,
            centroid = names.indices.associateWith { centroid[it] }.mapKeys { names[it.key] },
            spread = spread,
            minSse = members.minOf { ranked[it].sse }
        )
    }
}
