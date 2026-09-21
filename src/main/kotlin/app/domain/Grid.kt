package app.domain

import app.model.GridSpec
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.pow

/**
 * 共同对数粒径网格。以 log10(d) 等距布箱；
 * 当 [minD,maxD] 跨度不是 1/binsPerDecade 的整数倍时，
 * 箱数向上取整并把微余宽均摊到每箱，保证首末端点精确等于声明值。
 * 箱约定 [edges[j], edges[j+1])，最后一箱右端点 = edges.last 单独声明。
 */
class Grid(val spec: GridSpec) {
    val nBins: Int
    val edges: DoubleArray
    val centers: DoubleArray

    init {
        val span = log10(spec.maxD) - log10(spec.minD)
        nBins = maxOf(1, ceil(span * spec.binsPerDecade).toInt())
        val step = span / nBins
        edges = DoubleArray(nBins + 1) { j ->
            if (j == nBins) spec.maxD else 10.0.pow(log10(spec.minD) + step * j)
        }
        centers = DoubleArray(nBins) { j ->
            10.0.pow((log10(edges[j]) + log10(edges[j + 1])) / 2.0)
        }
    }
}
