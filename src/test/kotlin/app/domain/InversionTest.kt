package app.domain

import app.model.Basis
import app.model.ConversionModel
import app.model.Endmember
import app.model.GridSpec
import app.model.RawDistribution
import app.model.Sample
import app.model.SolveRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InversionTest {

    private val grid = GridSpec(1.0, 2000.0, 8)
    private val conv = ConversionModel(Basis.MASS, Basis.MASS, 2.65, 1.54)

    @Test
    fun `端元近线性相关时保留多组稀疏候选与解簇`() {
        val ems = Fixtures.endmembers()
        val result = Solver.solve(
            SolveRequest(
                sampleId = Fixtures.sampleMixed.id,
                endmemberIds = ems.map { it.id },
                grid = grid, conversion = conv,
                maxCandidates = 12, maxSubsetSize = 3
            ),
            Fixtures.sampleMixed, ems
        )
        // E_SILT 与 E_FINESAND 在覆盖网格上近相关
        val pair = result.diagnostics.nearCollinearPairs.firstOrNull {
            (it.a == "E_SILT" && it.b == "E_FINESAND") ||
                    (it.a == "E_FINESAND" && it.b == "E_SILT")
        }
        assertTrue(pair != null && kotlin.math.abs(pair.cosine) >= Inversion.NEAR_COSINE,
            "粉砂/细砂应被标记近相关，实际 pairs=${result.diagnostics.nearCollinearPairs}")
        assertTrue(result.candidates.size >= 3, "近相关时应保留多组候选，实际 ${result.candidates.size}")
        assertTrue(result.clusters.size >= 1)
        // 至少两个不同支持集结构出现
        val supports = result.candidates.map { it.support.sorted() }.distinct()
        assertTrue(supports.size >= 2, "应存在不同稀疏结构，实际 $supports")
    }

    @Test
    fun `合成混合可恢复端元比例_残差小`() {
        val ems = Fixtures.endmembers()
        val g = Grid(grid)
        val cols = ems.map { Projection.project(it.distribution, g, conv).bins.toDoubleArray() }
        // 0.6 粗砂 + 0.0 细砂 + 0.4 粉砂，按端元总量 100
        val truth = doubleArrayOf(60.0, 0.0, 40.0)
        val y = DoubleArray(g.nBins) { r -> (0..2).sumOf { cols[it][r] * truth[it] / 100.0 } }
        val outcome = Inversion.invert(
            y, cols, ems, BooleanArray(g.nBins) { true },
            relSigma = 0.06, corrLength = 1.0, maxSubsetSize = 3, maxCandidates = 12
        )
        val top = outcome.candidates.first()
        val rel = DoubleArray(3) { top.coeffs[it] / top.coeffs.sum() }
        assertEquals(0.6, rel[0], 0.05, "粗砂比例: ${rel.toList()}")
        assertEquals(0.0, rel[1], 0.08)
        assertEquals(0.4, rel[2], 0.05)
        assertTrue(outcome.candidates.first().rss < 1e-6, "rss=${outcome.candidates.first().rss}")
    }

    @Test
    fun `唯一可辨识情形诊断为可辨识`() {
        val ems = listOf(Fixtures.endmemberSand, Fixtures.endmemberSiltFine)
        val g = Grid(grid)
        val cols = ems.map { Projection.project(it.distribution, g, conv).bins.toDoubleArray() }
        val y = DoubleArray(g.nBins) { r -> cols[0][r] * 0.7 + cols[1][r] * 0.3 }
        val outcome = Inversion.invert(
            y, cols, ems, BooleanArray(g.nBins) { true },
            0.06, 1.0, 2, 8
        )
        // 粗砂与粉砂中值相距远，不应被标记为近相关
        assertTrue(outcome.diagnostics.nearCollinearPairs.isEmpty())
    }

    @Test
    fun `求解使用样品全部仪器并记账网格外失量`() {
        val ems = Fixtures.endmembers()
        val result = Solver.solve(
            SolveRequest(
                sampleId = Fixtures.sampleMixed.id,
                endmemberIds = ems.map { it.id },
                grid = grid, conversion = conv
            ),
            Fixtures.sampleMixed, ems
        )
        assertEquals(2, result.instruments.size)
        result.instruments.forEach { iv ->
            assertEquals(
                iv.totalSource,
                iv.totalOnGridSource + iv.outsideBelowSource + iv.outsideAboveSource,
                1e-9
            )
        }
        // fixture 中筛分 pan 到 4000 > maxD 2000：必有网格上失量（>0.09 质量单位）
        val sieve = result.instruments.first { it.instrument == "sieve" }
        assertTrue(sieve.outsideAboveSource > 0.09, "筛分 pan 网格上失量=${sieve.outsideAboveSource}")
        assertTrue(result.instruments.sumOf { it.outsideAboveSource + it.outsideBelowSource } > 0.09)
        assertTrue(result.outsideTotalGridBasis > 0.0)
        // 两台仪器覆盖的箱集合不完全相同（激光下限 0.3、上限 600，与筛分边界不同）
        val covSets = result.instruments.map { iv -> iv.coveredBins.indices.filter { iv.coveredBins[it] }.toSet() }
        assertTrue(covSets[0] != covSets[1], "两台仪器覆盖箱集合必须不同（不完全重叠）")
        assertTrue(result.coverageCount in 1 until result.blended.size + 1)
    }
}
