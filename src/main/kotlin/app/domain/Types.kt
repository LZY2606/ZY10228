package app.domain

import kotlinx.serialization.Serializable

/** 分布的加权口径。数量 N、面积 A、体积 V（粒径立方体）、质量 M（=密度×体积）。 */
@Serializable
enum class WeightBasis { NUMBER, AREA, VOLUME, MASS }

/**
 * 共同的对数粒径网格。
 *
 * 网格箱全部为左闭右开：[edges[i], edges[i+1])，最后一箱右端点 edges[n] 单独声明。
 * edges 为 log10(粒径/um)，等对数间距 dx。
 */
@Serializable
data class LogGrid(
    val name: String,
    val edgesUm: List<Double>,
    val dx: Double
) {
    init {
        require(edgesUm.size >= 2) { "网格至少需要两个边" }
        require(dx > 0) { "dx 必须为正" }
        require(edgesUm.zipWithNext().all { it.second > it.first }) { "边必须严格递增" }
    }

    val size: Int get() = edgesUm.size - 1

    /** 各箱几何中心粒径（um），对数中点。 */
    val centersUm: List<Double>
        get() = (0 until size).map { i -> Math.sqrt(edgesUm[i] * edgesUm[i + 1]) }

    fun edgeLog(i: Int): Double = Math.log10(edgesUm[i])
}

/**
 * 原始仪器箱。左闭右开 [lo, hi)；最后一箱用其自身 hi 作为显式右端点。
 * [value] 是该箱在 [basis] 口径下的量（如筛余质量 g、激光体积占比×总体积）。
 */
@Serializable
data class RawBin(
    val loUm: Double,
    val hiUm: Double,
    val value: Double
) {
    init {
        require(hiUm > loUm) { "箱右端点必须大于左端点: $loUm >= $hiUm" }
        require(value >= 0.0) { "箱值不允许为负" }
    }
}

/** 一份仪器原始分布。 */
@Serializable
data class RawDistribution(
    val instrument: String,
    val basis: WeightBasis,
    val bins: List<RawBin>
) {
    fun total(): Double = bins.sumOf { it.value }
}

/**
 * 投影结果。守恒是硬约束：
 *   totalRaw == onGrid.sum() + below + above
 * 不做归一化；网格外失量保留在 below/above 中。
 */
@Serializable
data class ProjectionResult(
    val instrument: String,
    val basis: WeightBasis,
    val gridName: String,
    val onGrid: List<Double>,
    val below: Double,
    val above: Double,
    val totalRaw: Double,
    val lastEdgeUm: Double
) {
    val uncovered: Double get() = below + above
    val coveredTotal: Double get() = onGrid.sum()
    fun isConserved(tol: Double = 1e-9): Boolean {
        val lhs = coveredTotal + below + above
        return if (totalRaw == 0.0) kotlin.math.abs(lhs) <= tol
        else kotlin.math.abs(lhs - totalRaw) <= tol * maxOf(1.0, totalRaw)
    }
}

/** 端元：已投影到共同网格、在某一基准口径下的谱，以及逐箱协方差（对角或全协方差的 JSON 表示）。 */
@Serializable
data class Endmember(
    val id: Long = 0,
    val name: String,
    val basis: WeightBasis,
    /** 密度 g/cm^3；端元各自密度，默认 2.65（石英）。数量/面积/体积转质量时使用。 */
    val density: Double,
    /** 折射率实部/虚部，仅作为激光口径的固定假设记录。 */
    val riReal: Double,
    val riImag: Double,
    /** 网格上的分布值，口径为 basis。 */
    val values: List<Double>,
    /** 对角逐箱方差（长度与 values 相同）；>0。 */
    val diagVariance: List<Double>,
    val note: String = ""
)

@Serializable
data class Sample(
    val id: Long = 0,
    val name: String,
    val note: String = ""
)

@Serializable
data class Observation(
    val id: Long = 0,
    val sampleId: Long,
    val instrument: String,
    val basis: WeightBasis,
    val density: Double,
    val riReal: Double,
    val riImag: Double,
    val rawBins: List<RawBin>
)

/** 求解时固定的假设。一次求解内网格、折射率、密度模型全部冻结。 */
@Serializable
data class SolveAssumptions(
    val gridName: String,
    val targetBasis: WeightBasis = WeightBasis.MASS,
    /** uniform-density：所有端元/样品用同一密度；endmember-density：各端元用自报密度。 */
    val densityModel: String = "uniform-density",
    val uniformDensity: Double = 2.65,
    /** 激光口径固定折射率（记录与假设一致性核对用）。 */
    val riReal: Double = 1.54,
    val riImag: Double = 0.01
)
