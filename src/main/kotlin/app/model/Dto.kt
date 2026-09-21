package app.model

import kotlinx.serialization.Serializable

/**
 * 加权口径。等球径假设下数量 n ∝ d^0、面积 a ∝ d^2、体积 v ∝ d^3；
 * 质量 m = rho * v（rho 为密度），故质量与体积同为 d^3 矩，仅差密度因子。
 * 公共形状系数（pi/6 等）在端元比例反演中消去。
 */
@Serializable
enum class Basis(val exponent: Int) {
    NUMBER(0), AREA(2), VOLUME(3), MASS(3)
}

/**
 * 原始“箱”分布。edges 长度 = values.size + 1：
 * 第 i 箱为 [edges[i], edges[i+1]) 左闭右开；最后一箱右端点 edges.last 单独声明。
 * values[i] 为该箱积分量（克、体积份数等，非密度），可含任意非负量纲。
 */
@Serializable
data class RawDistribution(
    val basis: Basis,
    val edges: List<Double>,
    val values: List<Double>,
    val instrument: String
) {
    init {
        require(edges.size == values.size + 1) {
            "edges 长度必须为 values.size+1（箱边界左闭右开，最后一箱右端点单独声明）"
        }
        require(edges.size >= 3) { "至少需要两个箱" }
        require(edges.zipWithNext().all { it.second > it.first }) { "edges 必须严格递增" }
        require(values.all { it.isFinite() && it >= 0.0 }) { "values 必须非负有限" }
    }

    val total: Double get() = values.sum()
}

/** 投影/口径转换后单台仪器视图。 */
@Serializable
data class InstrumentView(
    val instrument: String,
    val sourceBasis: Basis,
    val gridBasis: Basis,
    val bins: List<Double>,
    val coveredBins: List<Boolean>,
    val totalSource: Double,
    val totalOnGridSource: Double,
    val outsideBelowSource: Double,
    val outsideAboveSource: Double,
    val totalGridBasis: Double,
    val outsideBelowGridBasis: Double,
    val outsideAboveGridBasis: Double,
    val binEdges: List<Double>
)

@Serializable
data class Sample(
    val id: String,
    val name: String,
    val instruments: List<RawDistribution>,
    val blendMode: String = "MEAN",
    val note: String = ""
) {
    init {
        require(instruments.isNotEmpty()) { "样品至少需要一台仪器的分布" }
        require(instruments.map { it.instrument }.toSet().size == instruments.size) {
            "同一样品内 instrument 名称不可重复"
        }
    }
}

/**
 * 端元库成员。分布按其自身口径与箱边界给出，求解时再投影到固定网格。
 * relSigma：逐箱相对标准差（长度为 0 时取求解级默认值）；
 * corrLength：对数箱距相关长度（箱数），用于构造端元贡献协方差；0 表示独立（对角）。
 */
@Serializable
data class Endmember(
    val id: String,
    val name: String,
    val distribution: RawDistribution,
    val relSigma: List<Double> = emptyList(),
    val corrLength: Double = 0.0,
    val note: String = ""
)

@Serializable
data class GridSpec(
    val minD: Double,
    val maxD: Double,
    val binsPerDecade: Int
) {
    init {
        require(maxD > minD && minD > 0.0) { "网格边界需满足 0 < minD < maxD" }
        require(binsPerDecade in 1..100) { "binsPerDecade 需在 1..100" }
    }
}

/** 口径转换模型。mass = rho * volume；refractiveIndex 为激光光学假设，每次求解固定记录。 */
@Serializable
data class ConversionModel(
    val fromBasis: Basis,
    val toBasis: Basis,
    val density: Double = 2.65,
    val refractiveIndex: Double = 1.54,
    val modelName: String = "SPHERICAL_MOMENT"
) {
    init {
        require(density > 0.0) { "密度必须为正" }
        require(refractiveIndex > 0.0) { "折射率必须为正" }
    }
}

@Serializable
data class SolveRequest(
    val sampleId: String,
    val endmemberIds: List<String>,
    val grid: GridSpec,
    val conversion: ConversionModel,
    val blendMode: String? = null,
    val maxCandidates: Int = 12,
    val maxSubsetSize: Int = 3,
    val relSigma: Double = 0.06,
    val corrLength: Double = 0.0
)

@Serializable
data class PairCorrelation(
    val a: String,
    val b: String,
    val r: Double,
    val cosine: Double,
    val nearCollinear: Boolean
)

@Serializable
data class Diagnostics(
    val conditionNumber: Double,
    val minPairCosine: Double,
    val nearCollinearPairs: List<PairCorrelation>,
    val candidateSparsity: List<Int>,
    val profileSpread: Double,
    val topGap: Double,
    val identifiable: Boolean,
    val note: String
)

@Serializable
data class Candidate(
    val rank: Int,
    val coeffs: List<Double>,
    val fractions: List<Double>,
    val support: List<Int>,
    val rss: Double,
    val weightedRss: Double,
    val bic: Double,
    val clusterId: Int
)

@Serializable
data class Cluster(
    val id: Int,
    val size: Int,
    val meanFractions: List<Double>,
    val meanWeightedRss: Double,
    val representativeRank: Int
)

@Serializable
data class SolveResult(
    val sampleId: String,
    val sampleName: String,
    val endmemberIds: List<String>,
    val grid: GridSpec,
    val gridEdges: List<Double>,
    val gridCenters: List<Double>,
    val conversion: ConversionModel,
    val blendMode: String,
    val instruments: List<InstrumentView>,
    val blended: List<Double>,
    val coveredBins: List<Boolean>,
    val coverageCount: Int,
    val outsideTotalGridBasis: Double,
    val blendedTotal: Double,
    val observedTotal: Double,
    val fitted: List<Double>,
    val residuals: List<Double>,
    val weightedResiduals: List<Double>,
    val candidates: List<Candidate>,
    val clusters: List<Cluster>,
    val diagnostics: Diagnostics,
    val createdAt: String
)

@Serializable
data class SolveResponse(val solveId: Long, val result: SolveResult)

@Serializable
data class SolveRecord(
    val id: Long,
    val sampleId: String,
    val createdAt: String,
    val requestJson: String,
    val resultJson: String
)

/** 导出/重放包。 */
@Serializable
data class Bundle(
    val version: Int = 1,
    val exportedAt: String,
    val endmembers: List<Endmember>,
    val samples: List<Sample>,
    val solves: List<SolveRecord>
)

@Serializable
data class ReplayReport(
    val solveId: Long,
    val matched: Boolean,
    val maxAbsDiff: Double,
    val fractionsDiff: List<Double>
)
