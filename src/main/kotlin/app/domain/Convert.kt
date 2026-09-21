package app.domain

import kotlin.math.pow

/**
 * 加权口径转换（显式假设）：
 *  - 颗粒数 N(d)、面积 A(d) ∝ N d^2、体积 V(d) ∝ N d^3、质量 M(d) = rho V(d)。
 *  - 每个网格箱用其对数几何中心粒径 centersUm 代表该箱转换时的 d；
 *    这是在固定对数网格上做口径换算的标准一阶假设，避免箱内形状模型歧义。
 *  - 密度模型由求解假设统一决定（uniform-density 使用同一密度，
 *    则 V→M 只是整体缩放，不改变形状；endmember-density 才出现端元间差异）。
 *
 * 转换只乘正权重，因此保持总量（在新口径下）与逐箱非负性，不产生失量。
 */
fun convertBasis(
    values: List<Double>,
    grid: LogGrid,
    from: WeightBasis,
    to: WeightBasis,
    densityFrom: Double,
    densityTo: Double = densityFrom
): List<Double> {
    if (from == to) return values
    val d = grid.centersUm
    return values.indices.map { i ->
        val di = d[i]
        val v = values[i]
        val asVolume = when (from) {
            WeightBasis.NUMBER -> v * di.pow(3)
            WeightBasis.AREA -> v * di
            WeightBasis.VOLUME -> v
            WeightBasis.MASS -> v / densityFrom
        }
        when (to) {
            WeightBasis.NUMBER -> asVolume / di.pow(3)
            WeightBasis.AREA -> asVolume / di
            WeightBasis.VOLUME -> asVolume
            WeightBasis.MASS -> asVolume * densityTo
        }
    }
}

/** 两个口径之间在给定密度下的转换权重（供端元矩阵逐列使用）。 */
fun basisWeightFactor(
    from: WeightBasis,
    to: WeightBasis,
    dUm: Double,
    densityFrom: Double,
    densityTo: Double
): Double {
    val vol = when (from) {
        WeightBasis.NUMBER -> dUm.pow(3)
        WeightBasis.AREA -> dUm
        WeightBasis.VOLUME -> 1.0
        WeightBasis.MASS -> 1.0 / densityFrom
    }
    return when (to) {
        WeightBasis.NUMBER -> vol / dUm.pow(3)
        WeightBasis.AREA -> vol / dUm
        WeightBasis.VOLUME -> vol
        WeightBasis.MASS -> vol * densityTo
    }
}
