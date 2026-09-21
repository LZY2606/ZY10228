package app.domain

import app.model.Basis
import app.model.ConversionModel
import app.model.GridSpec
import app.model.RawDistribution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectionTest {

    private val grid = Grid(GridSpec(1.0, 100.0, 4))
    private val massConv = { b: Basis ->
        ConversionModel(fromBasis = b, toBasis = Basis.MASS, density = 2.0, refractiveIndex = 1.5)
    }

    private fun raw(basis: Basis, edges: List<Double>, values: List<Double>) =
        RawDistribution(basis, edges, values, "inst")

    @Test
    fun `箱为左闭右开且最后一箱右端点单独声明_投影严格守恒`() {
        val edges = listOf(0.1, 0.5, 1.0, 10.0, 100.0, 500.0)
        val values = listOf(5.0, 7.0, 30.0, 50.0, 8.0)
        val v = raw(Basis.MASS, edges, values)
        val view = Projection.project(v, grid, massConv(Basis.MASS))

        assertEquals(100.0, view.totalSource, 1e-9)
        assertEquals(
            view.totalSource,
            view.totalOnGridSource + view.outsideBelowSource + view.outsideAboveSource,
            1e-9
        )
        assertEquals(12.0, view.outsideBelowSource, 1e-9)   // 5+7：全部在网格下
        assertEquals(8.0, view.outsideAboveSource, 1e-9)    // [100,500) 全在网格上
        assertEquals(view.bins.sum(), view.totalOnGridSource, 1e-9) // mass->mass 不变
        // 目标口径记账同样守恒
        assertEquals(
            view.totalGridBasis,
            view.bins.sum() + view.outsideBelowGridBasis + view.outsideAboveGridBasis,
            1e-9
        )
        // 网格边：左闭右开，第一网格边严格等于声明 minD，最后一端点等于 maxD
        assertEquals(1.0, view.binEdges.first(), 1e-12)
        assertEquals(100.0, view.binEdges.last(), 1e-12)
    }

    @Test
    fun `边界相接不重复计量_恰好对齐网格边的源箱`() {
        val edges = listOf(1.0, 10.0, 100.0)
        val values = listOf(40.0, 60.0)
        val view = Projection.project(raw(Basis.MASS, edges, values), grid, massConv(Basis.MASS))
        assertEquals(0.0, view.outsideBelowSource, 1e-12)
        assertEquals(0.0, view.outsideAboveSource, 1e-12)
        assertEquals(100.0, view.bins.sum(), 1e-9)
    }

    @Test
    fun `对数箱内均匀_与网格对齐的箱矩积分守恒`() {
        // 源箱即网格箱的分组：4 个源箱合并投影应回到相同网格箱
        val g = Grid(GridSpec(1.0, 100.0, 2))
        val v = raw(Basis.MASS, g.edges.toList(), DoubleArray(g.nBins) { (it + 1).toDouble() }.toList())
        val view = Projection.project(v, g, massConv(Basis.MASS))
        v.values.forEachIndexed { i, x -> assertEquals(x, view.bins[i], 1e-9) }
        assertEquals(v.total, view.bins.sum(), 1e-9)
    }

    @Test
    fun `体积到质量按密度换算_总量乘rho`() {
        val edges = listOf(1.0, 10.0, 100.0)
        val v = raw(Basis.VOLUME, edges, listOf(25.0, 75.0))
        val view = Projection.project(v, grid, massConv(Basis.VOLUME)) // rho=2
        assertEquals(200.0, view.totalGridBasis, 1e-9)
        assertEquals(0.0, view.outsideBelowGridBasis, 1e-9)
    }

    @Test
    fun `数量到质量按d三次矩转换_粗粒端放大`() {
        val g = Grid(GridSpec(1.0, 1000.0, 3))
        // 等宽（对数）两段等数量
        val edges = listOf(1.0, 10.0, 1000.0)
        val v = raw(Basis.NUMBER, edges, listOf(50.0, 50.0))
        val view = Projection.project(v, g, massConv(Basis.NUMBER))
        // 数量守恒
        assertEquals(100.0, view.totalSource, 1e-9)
        // 质量矩守恒（转换只重排，不丢量；这里源口径与目标口径量纲不同，比较比例单调性）
        assertEquals(
            view.totalGridBasis,
            view.bins.sum() + view.outsideBelowGridBasis + view.outsideAboveGridBasis,
            1e-7
        )
        // 粗粒段产生的质量矩必须远大于细粒段
        val fine = view.bins.take(3).sum()
        val coarse = view.bins.drop(3).sum()
        assertTrue(coarse > fine * 50.0, "粗粒段质量矩应占绝对主导: fine=$fine coarse=$coarse")
    }

    @Test
    fun `网格外失量不被归一化_部分超出网格的分布`() {
        val edges = listOf(0.01, 0.1, 1.0, 100.0, 1000.0)
        val values = listOf(10.0, 10.0, 40.0, 40.0)
        val view = Projection.project(raw(Basis.MASS, edges, values), grid, massConv(Basis.MASS))
        // 网格内 [1,100) 40；下失量 20（全部），上失量 40（全部）
        assertEquals(40.0, view.bins.sum(), 1e-9)
        assertEquals(20.0, view.outsideBelowSource, 1e-9)
        assertEquals(40.0, view.outsideAboveSource, 1e-9)
        assertEquals(100.0, view.bins.sum() + view.outsideBelowSource + view.outsideAboveSource, 1e-9)
    }
}
