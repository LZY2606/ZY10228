package app.db

import app.domain.*
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 固定 fixture：清空数据库后可用同一组数据重放并复核（确定性）。
 *
 * 共同网格：1 um ~ 1000 um，每十倍频程 4 箱（左闭右开，右端点 1000 um 单独声明）。
 *
 * 数据口径一致性（关键）：
 *  - 定义“每单位 ln d 的质量密度” f(d)，三种源均为 lognormal：
 *      EM-A 细（mu=40um, 0.55）、EM-B 与 A 近相关（mu=55um, 0.60）、EM-C 粗（mu=450um, 0.75）。
 *  - 真实混合物质量 = 0.55*A + 0.62*C（同口径系数）。
 *  - 端元谱 = 其 f 在共同网格每个箱上按对数长度积分 f(c)*c*Δln。
 *  - 筛分箱（63~2000 um，质量口径）与激光箱（0.4~500 um，体积口径）都用同一个混合密度
 *    在各自箱上按对数长度积分，因此观测与端元在重叠区域严格同源。
 *
 * 不完全重叠与失量：
 *  - 筛分最后一箱 1000~2000 um 完全超出共同网格右边界 1000 um，产生 above 失量（约一成）。
 *  - 激光含约 5% 黏粒肩（mu=0.8um），其 0.4~1.2 um 箱低于共同网格左边界 1 um，产生 below 失量。
 *  - 黏粒肩不进入 A/B/C 的网格谱（它完全在网格外），因此其失量只在激光观测侧记账，不参与反演。
 */
object Fixtures {
    const val GRID_NAME = "common-log-4per-decade"
    const val SAMPLE_NAME = "sample-1 河流沉积物"

    fun grid(): LogGrid = makeLogGrid(GRID_NAME, 1.0, 1000.0, 4)

    private fun lognorm(d: Double, mu: Double, sigma: Double, amp: Double): Double {
        val z = (ln(d) - mu) / sigma
        return amp * exp(-0.5 * z * z) / d
    }

    // 源成分（每单位 ln d 的质量密度）
    private fun fA(d: Double) = lognorm(d, ln(40.0), 0.55, 1.0)
    private fun fB(d: Double) = lognorm(d, ln(55.0), 0.60, 1.0)
    private fun fC(d: Double) = lognorm(d, ln(450.0), 0.75, 1.0)
    private fun fClay(d: Double) = lognorm(d, ln(0.8), 0.35, 1.0)

    // 真实混合：质量 0.55*A + 0.62*C；激光另加 0.05 黏粒肩（网格外）
    private fun mixMass(d: Double) = 0.55 * fA(d) + 0.62 * fC(d)
    private fun mixLaserVolume(d: Double) = (0.55 * fA(d) + 0.62 * fC(d) + 0.05 * fClay(d)) / 2.65

    /** 在 [lo,hi) 上对“每单位 ln d 的量密度”积分，箱内用中点×对数宽（确定的等对数近似）。 */
    private fun integrate(lo: Double, hi: Double, f: (Double) -> Double): Double {
        val mid = sqrt(lo * hi)
        return f(mid) * mid * (ln(hi) - ln(lo))
    }

    private fun boxes(edges: List<Double>, f: (Double) -> Double) =
        edges.zipWithNext().map { (lo, hi) -> RawBin(lo, hi, integrate(lo, hi, f)) }

    fun sieveBins(): List<RawBin> = boxes(
        listOf(63.0, 90.0, 125.0, 180.0, 250.0, 355.0, 500.0, 710.0, 1000.0, 2000.0),
        ::mixMass
    )

    fun laserBins(): List<RawBin> {
        // 最后一箱上界显式写为 500.0（左闭右开，不与 500 端点重复）
        val edges = listOf(0.4, 0.7, 1.2, 2.0, 3.5, 6.0, 10.0, 18.0, 32.0, 56.0, 100.0, 180.0, 320.0, 500.0)
        return boxes(edges, ::mixLaserVolume)
    }

    private fun gridSpectrum(grid: LogGrid, f: (Double) -> Double): List<Double> =
        grid.centersUm.mapIndexed { k, d -> integrate(grid.edgesUm[k], grid.edgesUm[k + 1], f) }

    fun endmembers(grid: LogGrid): List<Endmember> {
        val a = gridSpectrum(grid, ::fA)
        val b = gridSpectrum(grid, ::fB)
        val c = gridSpectrum(grid, ::fC)
        val relVar = { v: Double -> (0.05 * v + 0.002).let { it * it } }
        return listOf(
            Endmember(
                name = "EM-A 粉砂(细)", basis = WeightBasis.MASS, density = 2.65,
                riReal = 1.54, riImag = 0.01, values = a,
                diagVariance = a.map(relVar), note = "lognormal mu=40um sigma=0.55"
            ),
            Endmember(
                name = "EM-B 粉砂(近相关)", basis = WeightBasis.MASS, density = 2.65,
                riReal = 1.54, riImag = 0.01, values = b,
                diagVariance = b.map(relVar), note = "与 A 近线性相关 mu=55um sigma=0.60"
            ),
            Endmember(
                name = "EM-C 砂(粗)", basis = WeightBasis.MASS, density = 2.65,
                riReal = 1.54, riImag = 0.01, values = c,
                diagVariance = c.map(relVar), note = "lognormal mu=450um sigma=0.75"
            )
        )
    }

    fun seed(db: Database) {
        val grid = grid()
        db.saveGrid(grid)
        endmembers(grid).forEach { db.upsertEndmember(it) }
        val sampleId = db.upsertSample(SAMPLE_NAME, "筛分质量 + 激光体积双仪器，网格不完全重叠")
        if (db.observationsOf(sampleId).isEmpty()) {
            db.addObservation(
                Observation(
                    sampleId = sampleId, instrument = "sieve 筛分", basis = WeightBasis.MASS,
                    density = 2.65, riReal = 1.54, riImag = 0.01, rawBins = sieveBins()
                )
            )
            db.addObservation(
                Observation(
                    sampleId = sampleId, instrument = "laser 激光粒度仪", basis = WeightBasis.VOLUME,
                    density = 2.65, riReal = 1.54, riImag = 0.01, rawBins = laserBins()
                )
            )
        }
        db.setMeta("fixture_version", "1")
    }
}
