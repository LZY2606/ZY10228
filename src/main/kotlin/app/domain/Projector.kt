package app.domain

import app.domain.RawBin
import kotlin.math.log10
import kotlin.math.pow

/**
 * 构造等对数间距网格。
 * @param binsPerDecade 每十倍频程箱数
 */
fun makeLogGrid(name: String, loUm: Double, hiUm: Double, binsPerDecade: Int): LogGrid {
    require(hiUm > loUm)
    require(binsPerDecade > 0)
    val dx = 1.0 / binsPerDecade
    val x0 = log10(loUm)
    val xn = log10(hiUm)
    val n = Math.round((xn - x0) / dx).toInt()
    require(n >= 1) { "网格区间内至少一个箱" }
    val edges = (0..n).map { k -> 10.0.pow(x0 + k * dx) }
    return LogGrid(name, edges, dx)
}

/**
 * 把原始仪器箱分布投影到共同对数网格。
 *
 * 转换假设（显式）：
 *  1. 每个原始箱内部，在 log10(d) 上均匀分布（箱内等对数密度），
 *     即把箱内总量按“对数长度占比”切分。
 *  2. 箱边界一律左闭右开；原始箱与网格箱在端点相接处为零测度，不产生重复。
 *  3. 对每个原始箱做三段切分（同一箱宽归一化，保证总量严格守恒）：
 *       below 段 [xl, g0)
 *       网格内段 [cl,ch) 再按网格箱对数长度细分
 *       above 段 [gn, xh)
 *     落在共同网格之外的量计入 below/above，绝不做归一化吞掉失量，
 *     满足 totalRaw = onGrid.sum() + below + above（仅浮点舍入误差）。
 */
fun projectToGrid(dist: RawDistribution, grid: LogGrid): ProjectionResult {
    val n = grid.size
    val on = DoubleArray(n)
    var below = 0.0
    var above = 0.0
    val g0 = grid.edgeLog(0)
    val gn = grid.edgeLog(n)

    for (bin in dist.bins) {
        val xl = log10(bin.loUm)
        val xh = log10(bin.hiUm)
        val width = xh - xl
        if (width <= 0.0) continue

        val cl = maxOf(xl, g0)
        val ch = minOf(xh, gn)

        // 网格外两段，各自按其与失量区间的真实重叠长度/整箱宽度（份额夹在 [0,1]）
        val bLo = xl
        val bHi = minOf(xh, g0)
        if (bHi > bLo) below += bin.value * ((bHi - bLo) / width)
        val aLo = maxOf(xl, gn)
        val aHi = xh
        if (aHi > aLo) above += bin.value * ((aHi - aLo) / width)

        if (ch > cl) {
            // 网格内总份额：重叠长度/整箱宽度。再在该份额内按各网格箱对数长度细分。
            val overlapFraction = (ch - cl) / width
            val first = floorIndex(grid, cl)
            val lastExclusive = ceilIndexExclusive(grid, ch)
            val overlapLen = ch - cl
            for (j in first until lastExclusive) {
                val l = maxOf(cl, grid.edgeLog(j))
                val h = minOf(ch, grid.edgeLog(j + 1))
                if (h > l) on[j] += bin.value * overlapFraction * ((h - l) / overlapLen)
            }
        }
    }

    return ProjectionResult(
        instrument = dist.instrument,
        basis = dist.basis,
        gridName = grid.name,
        onGrid = on.toList(),
        below = below,
        above = above,
        totalRaw = dist.total(),
        lastEdgeUm = grid.edgesUm.last()
    )
}

/** 最大的 i 使 edgeLog(i) <= x（仅在相交索引上使用）。 */
private fun floorIndex(grid: LogGrid, x: Double): Int {
    val i = Math.floor((x - grid.edgeLog(0)) / grid.dx).toInt()
    return minOf(grid.size - 1, maxOf(0, i))
}

/** 最小的 i 使 edgeLog(i) >= x，截断到 size（箱右端索引）。 */
private fun ceilIndexExclusive(grid: LogGrid, x: Double): Int {
    val i = Math.ceil((x - grid.edgeLog(0)) / grid.dx).toInt()
    return minOf(grid.size, maxOf(0, i))
}

/**
 * 仪器在共同网格上的支持掩码：第 j 个网格箱是否与任一原始箱内部相交。
 * 端点相接（零测度）不算覆盖；因此筛分从 63um 起时，其左侧网格箱为 false。
 */
fun supportMask(bins: List<RawBin>, grid: LogGrid): BooleanArray {
    val n = grid.size
    val mask = BooleanArray(n)
    for (bin in bins) {
        val xl = log10(bin.loUm)
        val xh = log10(bin.hiUm)
        val cl = maxOf(xl, grid.edgeLog(0))
        val ch = minOf(xh, grid.edgeLog(n))
        if (ch <= cl) continue
        val first = maxOf(0, Math.floor((cl - grid.edgeLog(0)) / grid.dx).toInt())
        val lastExclusive = minOf(n, Math.ceil((ch - grid.edgeLog(0)) / grid.dx).toInt())
        for (j in first until lastExclusive) {
            val l = maxOf(cl, grid.edgeLog(j))
            val h = minOf(ch, grid.edgeLog(j + 1))
            if (h > l) mask[j] = true
        }
    }
    return mask
}
