package app.domain

import app.model.Basis
import app.model.Endmember
import app.model.RawDistribution
import app.model.Sample
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 固定夹具（确定性、随包交付）：
 *  - 网格统一演示区间 [0.5,2000] um，8 bins/decade；
 *  - 筛分（mass 口径，箱左闭右开，最后一个 pan 右端点 4000 单独声明）；
 *  - 激光粒度仪（volume 口径，0.3..600 um，与筛分不完全重叠）；
 *  - E1/E2 为近线性相关端元，验收保留多组稀疏候选。
 */
object Fixtures {

    const val DEFAULT_MIN_D = 1.0
    const val DEFAULT_MAX_D = 2000.0
    const val DEFAULT_BPD = 8
    const val DEFAULT_DENSITY = 2.65
    const val DEFAULT_RI = 1.54

    private val sieveEdges = listOf(
        0.063, 0.125, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0,
        16.0, 32.0, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0
    )

    private val laserEdges: List<Double> = run {
        val lo = log10(0.3)
        val hi = log10(600.0)
        val n = 33
        (0..n).map { 10.0.pow(lo + (hi - lo) * it / n) }
    }

    private val emEdges: List<Double> = run {
        val lo = log10(0.2)
        val hi = log10(3000.0)
        val n = 36
        (0..n).map { 10.0.pow(lo + (hi - lo) * it / n) }
    }

    /** 在给定对数箱上按对数正态生成“质量”积分量（sum=total）。 */
    private fun lognormalMass(
        edges: List<Double>, mu: Double, sigma: Double, total: Double
    ): List<Double> {
        fun cdf(x: Double): Double {
            val z = (ln(x) - mu) / (sigma * sqrt(2.0))
            return 0.5 * (1.0 + erf(z))
        }
        val raw = edges.zipWithNext().map { (l, r) -> cdf(r) - cdf(l) }
        val s = raw.sum()
        return raw.map { it / s * total }
    }

    private fun erf(x: Double): Double {
        // Abramowitz & Stegun 7.1.26，精度 ~1.2e-7，生成夹具足够
        val sign = if (x < 0) -1.0 else 1.0
        val ax = kotlin.math.abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val poly = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t
                - 0.284496736) * t + 0.254829592) * t * exp(-ax * ax)
        return sign * poly
    }

    val endmemberSand: Endmember
        get() = Endmember(
            id = "E_SAND",
            name = "粗砂（河道砂）",
            distribution = RawDistribution(
                basis = Basis.MASS,
                edges = emEdges,
                values = lognormalMass(emEdges, ln(450.0), 0.55, 100.0),
                instrument = "fixture-endmember"
            ),
            relSigma = listOf(0.06),
            corrLength = 1.0,
            note = "对数正态 median=450um sigma=0.55"
        )

    val endmemberSiltFine: Endmember
        get() = Endmember(
            id = "E_SILT",
            name = "粉砂（黄土风成）",
            distribution = RawDistribution(
                basis = Basis.MASS,
                edges = emEdges,
                values = lognormalMass(emEdges, ln(62.0), 0.50, 100.0),
                instrument = "fixture-endmember"
            ),
            relSigma = listOf(0.07),
            corrLength = 1.0,
            note = "对数正态 median=62um，与细砂在覆盖网格上近相关"
        )

    val endmemberSandFine: Endmember
        get() = Endmember(
            id = "E_FINESAND",
            name = "细砂（岸滩）",
            distribution = RawDistribution(
                basis = Basis.MASS,
                edges = emEdges,
                values = lognormalMass(emEdges, ln(66.0), 0.50, 100.0),
                instrument = "fixture-endmember"
            ),
            relSigma = listOf(0.07),
            corrLength = 1.0,
            note = "对数正态 median=74um，sigma=0.50，与粉砂(62um)在覆盖网格上近线性相关"
        )

    fun endmembers(): List<Endmember> =
        listOf(endmemberSand, endmemberSiltFine, endmemberSandFine)

    private val sieveMix = run {
        // 0.45 粗砂 + 0.35 细砂 + 0.20 粉砂 的质量混合
        val a = lognormalMass(sieveEdges, ln(450.0), 0.55, 30.0)
        val b = lognormalMass(sieveEdges, ln(66.0), 0.50, 35.0)
        val c = lognormalMass(sieveEdges, ln(62.0), 0.70, 35.0)
        (0 until sieveEdges.size - 1).map { i -> a[i] + b[i] + c[i] }
    }

    private val laserMix = run {
        // 激光体积（=质量/密度）：0.10 粗砂 + 0.55 细砂 + 0.35 粉砂（仪器口径差异，仅部分粒径覆盖）
        val a = lognormalMass(laserEdges, ln(450.0), 0.55, 8.0)
        val b = lognormalMass(laserEdges, ln(66.0), 0.50, 50.0)
        val c = lognormalMass(laserEdges, ln(62.0), 0.70, 42.0)
        (0 until laserEdges.size - 1).map { i -> a[i] + b[i] + c[i] }
    }

    val sampleMixed: Sample
        get() = Sample(
            id = "S_MIXED",
            name = "混合沉积物 S1（筛分+激光）",
            instruments = listOf(
                RawDistribution(Basis.MASS, sieveEdges, sieveMix, "sieve"),
                RawDistribution(Basis.VOLUME, laserEdges, laserMix, "laser")
            ),
            blendMode = "MEAN",
            note = "筛分 0.063..4000um（质量），激光 0.3..600um（体积），网格不完全重叠"
        )

    fun samples(): List<Sample> = listOf(sampleMixed)
}
