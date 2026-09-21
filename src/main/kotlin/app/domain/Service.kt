package app.domain

import app.db.Database
import kotlinx.serialization.Serializable
import java.time.Instant

/** 一份仪器在共同网格上的转换视图（供页面展示“转换后分布”）。 */
@Serializable
data class InstrumentView(
    val instrument: String,
    val basis: WeightBasis,
    val density: Double,
    val projection: ProjectionResult,
    val converted: List<Double>,
    val sigma: List<Double>
)

@Serializable
data class SolveRequest(
    val sampleId: Long,
    val gridName: String,
    val targetBasis: WeightBasis = WeightBasis.MASS,
    val densityModel: String = "uniform-density",
    val uniformDensity: Double = 2.65,
    val riReal: Double = 1.54,
    val riImag: Double = 0.01,
    val endmemberNames: List<String> = emptyList(),
    val save: Boolean = true
)

@Serializable
data class SolveResponse(
    val runId: Long?,
    val views: List<InstrumentView>,
    val result: SolveResult,
    val verification: VerificationNote
)

@Serializable
data class VerificationNote(
    val conservationOk: Boolean,
    val totalChecks: List<TotalCheck>,
    val message: String
)

@Serializable
data class TotalCheck(
    val instrument: String,
    val totalRaw: Double,
    val onGrid: Double,
    val below: Double,
    val above: Double,
    val lastEdgeUm: Double,
    val conserved: Boolean
)

class GrainService(val db: Database) {

    fun instrumentViews(sampleId: Long, grid: LogGrid, a: SolveAssumptions): List<InstrumentView> {
        return db.observationsOf(sampleId).map { obs ->
            val raw = RawDistribution(obs.instrument, obs.basis, obs.rawBins)
            val proj = projectToGrid(raw, grid)
            val densityTo = if (a.densityModel == "uniform-density") a.uniformDensity else obs.density
            val converted = convertBasis(proj.onGrid, grid, obs.basis, a.targetBasis, obs.density, densityTo)
            val floor = 0.002 * (converted.maxOrNull() ?: 1.0)
            val sigma = converted.map { 0.05 * kotlin.math.abs(it) + floor }
            InstrumentView(obs.instrument, obs.basis, obs.density, proj, converted, sigma)
        }
    }

    fun solve(req: SolveRequest): SolveResponse {
        val grid = db.findGrid(req.gridName) ?: error("未知网格: ${req.gridName}")
        val sample = db.listSamples().firstOrNull { it.id == req.sampleId } ?: error("未知样品: ${req.sampleId}")
        val chosen = if (req.endmemberNames.isEmpty()) db.listEndmembers()
        else req.endmemberNames.map { n -> db.findEndmemberByName(n) ?: error("未知端元: $n") }
        require(chosen.size >= 2) { "至少选择两个端元" }

        val assumptions = SolveAssumptions(
            gridName = req.gridName, targetBasis = req.targetBasis,
            densityModel = req.densityModel, uniformDensity = req.uniformDensity,
            riReal = req.riReal, riImag = req.riImag
        )

        val views = instrumentViews(req.sampleId, grid, assumptions)
        require(views.isNotEmpty()) { "样品没有观测数据" }

        // 双仪器纵向堆叠：每台仪器各自的网格行 + 各自权重，端元矩阵按行复制。
        // 逐仪器计算“支持掩码”：仪器箱在该网格箱内是否有物质覆盖（含真实 0），
        // 未被任何原始箱覆盖的网格箱不参与拟合（避免把“无数据”误当 0 惩罚）。
        data class Piece(val y: DoubleArray, val w: DoubleArray, val uncovered: Double, val totalRaw: Double)
        val pieces = db.observationsOf(req.sampleId).zip(views).map { (obs, v) ->
            val support = supportMask(obs.rawBins, grid)
            val floor = 0.002 * (v.converted.maxOrNull() ?: 1.0)
            val y = DoubleArray(grid.size) { i -> if (support[i]) v.converted[i] else 0.0 }
            val w = DoubleArray(grid.size) { i ->
                if (support[i]) 1.0 / (0.05 * kotlin.math.abs(v.converted[i]) + floor) else 0.0
            }
            Piece(y, w, v.projection.uncovered, v.projection.totalRaw)
        }
        val y = pieces.flatMap { it.y.toList() }.toDoubleArray()
        val wStack = pieces.flatMap { it.w.toList() }.toDoubleArray()
        val sigma = DoubleArray(wStack.size) { i -> if (wStack[i] > 0.0) 1.0 / wStack[i] else 1e9 }
        val stackedObs = PreparedObservation(y, sigma, pieces.sumOf { it.uncovered }, pieces.sumOf { it.totalRaw })

        val pems = chosen.map { prepareEndmember(it, grid, assumptions) }
        val rep = grid.size
        val cols = pems.map { pe ->
            // 对每台仪器重复同一列（端元在共同网格全域有定义）
            DoubleArray(rep * pieces.size) { row -> pe.column[row % rep] }
        }
        val stackedEms = pems.mapIndexed { idx, pe -> PreparedEndmember(pe.name, cols[idx], pe.uncovered, pe.rawTotal) }

        val result = solve(grid, stackedObs, stackedEms, chosen, assumptions)

        val totalChecks = views.map { v ->
            TotalCheck(
                v.instrument, v.projection.totalRaw, v.projection.coveredTotal,
                v.projection.below, v.projection.above, v.projection.lastEdgeUm,
                v.projection.isConserved()
            )
        }
        val conservationOk = totalChecks.all { it.conserved }
        val verification = VerificationNote(
            conservationOk, totalChecks,
            if (conservationOk) "全部仪器总量守恒：totalRaw = onGrid + below + above；未归一化掉失量。"
            else "守恒校验失败，请检查原始箱边界。"
        )

        var runId: Long? = null
        if (req.save) {
            runId = db.saveRun(sample.id, assumptions, result, Instant.now().toString())
        }
        return SolveResponse(runId, views, result, verification)
    }
}
