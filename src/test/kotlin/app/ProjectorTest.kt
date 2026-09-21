package app

import app.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs

class ProjectorTest {

    private val grid = makeLogGrid("g", 1.0, 1000.0, 4) // 1..1000 um

    @Test
    fun `bins are half-open and last right edge is declared`() {
        // 两个相邻箱在端点相接：[1,2) 与 [2,4)，值不重复
        val dist = RawDistribution(
            "x", WeightBasis.MASS,
            listOf(RawBin(1.0, 2.0, 1.0), RawBin(2.0, 4.0, 1.0))
        )
        val p = projectToGrid(dist, grid)
        assertEquals(1000.0, p.lastEdgeUm, 1e-12)
        assertTrue(p.isConserved())
        assertEquals(2.0, p.coveredTotal, 1e-9)
    }

    @Test
    fun `fully outside grid is all uncovered`() {
        val dist = RawDistribution(
            "x", WeightBasis.MASS,
            listOf(RawBin(1000.0, 2000.0, 5.0)) // 完全在右侧（左闭端点相接不重叠）
        )
        val p = projectToGrid(dist, grid)
        assertEquals(5.0, p.above, 1e-9)
        assertEquals(0.0, p.coveredTotal, 1e-9)
        assertTrue(p.isConserved())
    }

    @Test
    fun `partially overlapping keeps below and above losses without renormalization`() {
        // 0.5 .. 2000 um 单箱，跨越整个网格
        val dist = RawDistribution("x", WeightBasis.VOLUME, listOf(RawBin(0.5, 2000.0, 10.0)))
        val p = projectToGrid(dist, grid)
        assertTrue(p.below > 0.0, "应有 below 失量")
        assertTrue(p.above > 0.0, "应有 above 失量")
        assertEquals(10.0, p.coveredTotal + p.below + p.above, 1e-9)
        // 未归一化：网格内严格小于总量
        assertTrue(p.coveredTotal < 10.0)
    }

    @Test
    fun `uniform log interpolation splits a bin linearly in log length`() {
        // 箱 [1,10) 与网格前 4 箱（dx=0.25）整 4 箱对齐，应均分成 4 份
        val dist = RawDistribution("x", WeightBasis.MASS, listOf(RawBin(1.0, 10.0, 4.0)))
        val p = projectToGrid(dist, grid)
        val first4 = p.onGrid.take(4)
        first4.forEach { assertEquals(1.0, it, 1e-9) }
        assertEquals(0.0, p.onGrid.drop(4).sum(), 1e-12)
    }

    @Test
    fun `sieve loses coarse mass above grid, laser loses fines below grid`() {
        val sieve = RawDistribution("sieve", WeightBasis.MASS, app.db.Fixtures.sieveBins())
        val laser = RawDistribution("laser", WeightBasis.VOLUME, app.db.Fixtures.laserBins())
        val ps = projectToGrid(sieve, grid)
        val pl = projectToGrid(laser, grid)
        assertTrue(ps.above > 0.0)            // 筛分 1000~2000 超出右界
        assertEquals(0.0, ps.below, 1e-9)
        assertTrue(pl.below > 0.0)            // 激光 0.4~1.2 低于左界
        assertEquals(0.0, pl.above, 1e-9)
        assertTrue(ps.isConserved() && pl.isConserved())
        // 总失量占比合理（非零且非全部）
        assertTrue(ps.uncovered / ps.totalRaw in 0.001..0.5)
        assertTrue(pl.uncovered / pl.totalRaw in 0.001..0.5)
    }

    @Test
    fun `basis conversion preserves shape under uniform density`() {
        val mass = List(grid.size) { 1.0 }
        val vol = convertBasis(mass, grid, WeightBasis.MASS, WeightBasis.VOLUME, 2.65, 2.65)
        val back = convertBasis(vol, grid, WeightBasis.VOLUME, WeightBasis.MASS, 2.65, 2.65)
        mass.indices.forEach { assertEquals(1.0, back[it], 1e-9) }
    }
}
