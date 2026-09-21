package app.domain

import app.model.Basis
import app.model.ConversionModel
import app.model.InstrumentView
import app.model.RawDistribution
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * 投影假设（README 同步说明）：
 * 1. 每个原始箱内在 ln(d) 上对“源口径积分量”均匀（对数箱内均匀，log-uniform within bin）；
 * 2. 口径间按等球径矩转换：目标/源密度比 g(d)=g0*d^(tExp-sExp)，
 *    volume<->mass 仅差密度因子 rho；公共形状系数消去；
 * 3. 激光体积分布已按折射率完成光学反演，refractiveIndex 作为固定假设记录，不再二次 Mie 修正；
 * 4. 箱均为左闭右开，仅以区间相交 (max(l,a), min(r,b)) 分配，端点相接不产生重复量；
 * 5. 投影严格守恒：网格内 + 网格下失量 + 网格上失量 = 源总量，不归一化掉失量。
 */
object Projection {

    private data class Piece(val lo: Double, val hi: Double)

    /** 单台仪器：投影到网格并做口径转换，同时在源口径与目标口径下记账失量。 */
    fun project(
        raw: RawDistribution,
        grid: Grid,
        conv: ConversionModel
    ): InstrumentView {
        require(conv.fromBasis == raw.basis) {
            "转换模型 fromBasis=${conv.fromBasis} 与仪器口径 ${raw.basis} 不一致"
        }
        val n = grid.nBins
        val bins = DoubleArray(n)
        val belowSrc = DoubleArray(1)
        val aboveSrc = DoubleArray(1)
        val belowDst = DoubleArray(1)
        val aboveDst = DoubleArray(1)
        val gMin = grid.edges.first()
        val gMax = grid.edges.last()

        for (i in raw.values.indices) {
            val l = raw.edges[i]
            val r = raw.edges[i + 1]
            val v = raw.values[i]
            if (v <= 0.0) continue
            // 网格下失量片 [l, min(r,gMin))
            addPiece(raw.basis, conv, l, minOf(r, gMin), l, r, v, belowSrc, belowDst)
            // 网格上失量片 [max(l,gMax), r)
            addPiece(raw.basis, conv, maxOf(l, gMax), r, l, r, v, aboveSrc, aboveDst)
            // 与网格内各箱相交
            var j = lowerBinIndex(grid.edges, l)
            while (j < n) {
                val a = maxOf(l, grid.edges[j])
                val b = minOf(r, grid.edges[j + 1])
                if (b > a) {
                    bins[j] += converted(raw.basis, conv, a, b, l, r, v)
                }
                if (grid.edges[j + 1] >= r) break
                j++
            }
        }

        val onGridSource = raw.total - belowSrc[0] - aboveSrc[0]
        val totalDst = bins.sum() + belowDst[0] + aboveDst[0]
        val covered = bins.map { it > 0.0 }
        return InstrumentView(
            instrument = raw.instrument,
            sourceBasis = raw.basis,
            gridBasis = conv.toBasis,
            bins = bins.toList(),
            coveredBins = covered,
            totalSource = raw.total,
            totalOnGridSource = onGridSource,
            outsideBelowSource = belowSrc[0],
            outsideAboveSource = aboveSrc[0],
            totalGridBasis = totalDst,
            outsideBelowGridBasis = belowDst[0],
            outsideAboveGridBasis = aboveDst[0],
            binEdges = grid.edges.toList()
        )
    }

    private fun lowerBinIndex(edges: DoubleArray, x: Double): Int {
        // 找第一个满足 edges[j+1] > x 的 j；x 低于网格时从首箱开始（相交为空即跳过）。
        var lo = -1
        var hi = edges.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (edges[mid + 1] <= x) lo = mid else hi = mid
        }
        return maxOf(0, hi)
    }

    private fun addPiece(
        src: Basis, conv: ConversionModel, a: Double, b: Double,
        bl: Double, br: Double, v: Double,
        srcAcc: DoubleArray, dstAcc: DoubleArray
    ) {
        if (b <= a) return
        val len = ln(br) - ln(bl)
        srcAcc[0] += v * (ln(b) - ln(a)) / len
        dstAcc[0] += converted(src, conv, a, b, bl, br, v)
    }

    /** 源箱 [bl,br) 内对数均匀、总量 v 时，相交段 [a,b) 转换到目标口径的量。 */
    private fun converted(
        src: Basis, conv: ConversionModel, a: Double, b: Double,
        bl: Double, br: Double, v: Double
    ): Double {
        if (b <= a) return 0.0
        val len = ln(br) - ln(bl)
        val q = conv.toBasis.exponent - src.exponent
        val integral = if (q == 0) ln(b) - ln(a) else (b.pow(q) - a.pow(q)) / q
        return v * densityFactor(conv, src) * integral / len
    }

    private fun densityFactor(conv: ConversionModel, src: Basis): Double = when {
        src == Basis.VOLUME && conv.toBasis == Basis.MASS -> conv.density
        src == Basis.MASS && conv.toBasis == Basis.VOLUME -> 1.0 / conv.density
        else -> 1.0
    }

    /**
     * 多仪器在共同网格上的合并。
     * MEAN：覆盖箱取算术平均（口径已统一）；SUM：叠加；FIRST：取最先覆盖者。
     * 网格外失量按同一口径（与合并方式一致）记账，绝不并入网格箱。
     */
    fun blend(
        views: List<InstrumentView>,
        n: Int,
        mode: String
    ): BlendResult {
        val blended = DoubleArray(n)
        val covered = BooleanArray(n)
        for (j in 0 until n) {
            val vals = views.filter { it.bins[j] > 0.0 }
            if (vals.isNotEmpty()) {
                covered[j] = true
                blended[j] = when (mode.uppercase()) {
                    "SUM" -> vals.sumOf { it.bins[j] }
                    "FIRST" -> views.first { it.bins[j] > 0.0 }.bins[j]
                    else -> vals.sumOf { it.bins[j] } / vals.size
                }
            }
        }
        val outside = views.sumOf { it.outsideBelowGridBasis + it.outsideAboveGridBasis }
        val outsideAccounted = when (mode.uppercase()) {
            "SUM" -> outside
            "FIRST" -> views.first().let { it.outsideBelowGridBasis + it.outsideAboveGridBasis }
            else -> if (views.isEmpty()) 0.0 else outside / views.size
        }
        return BlendResult(blended.toList(), covered.toList(), outsideAccounted)
    }

    data class BlendResult(
        val bins: List<Double>,
        val covered: List<Boolean>,
        val outsideGridBasis: Double
    )
}
