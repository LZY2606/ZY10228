package app.domain

import app.db.Database
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ExportBundle(
    val format: String = "grainmix-bundle",
    val version: Int = 1,
    val exportedAt: String,
    val grids: List<LogGrid>,
    val endmembers: List<Endmember>,
    val samples: List<Sample>,
    val observations: List<Observation>,
    val runs: List<ExportRun>
)

@Serializable
data class ExportRun(
    val id: Long,
    val createdAt: String,
    val sampleName: String?,
    val assumptions: SolveAssumptions,
    val result: SolveResult
)

@Serializable
data class ImportReport(
    val imported: Map<String, Int>,
    val runVerifications: List<RunVerification>,
    val allVerified: Boolean
)

@Serializable
data class RunVerification(
    val runId: Long?,
    val sse: Double,
    val expectedSse: Double,
    val matched: Boolean,
    val conservationOk: Boolean
)

object ImportExport {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun export(db: Database): ExportBundle {
        val samples = db.listSamples()
        val obs = samples.flatMap { db.observationsOf(it.id) }
        val runs = db.listRuns().map { r ->
            ExportRun(r.id, r.createdAt, samples.firstOrNull { it.id == r.sampleId }?.name, r.assumptions, r.result)
        }
        return ExportBundle(
            exportedAt = java.time.Instant.now().toString(),
            grids = db.listGrids(),
            endmembers = db.listEndmembers(),
            samples = samples,
            observations = obs,
            runs = runs
        )
    }

    fun exportJson(db: Database): String = json.encodeToString(ExportBundle.serializer(), export(db))

    /** 清空后导入并重算每条 run，与导出中的 SSE/守恒结果核对。 */
    fun clearImportVerify(db: Database, bundleJson: String, relTol: Double = 1e-6): ImportReport {
        val bundle = json.decodeFromString(ExportBundle.serializer(), bundleJson)
        db.clearAll()

        bundle.grids.forEach { db.saveGrid(it) }
        bundle.endmembers.forEach { db.upsertEndmember(it) }
        val sampleIdByName = mutableMapOf<String, Long>()
        bundle.samples.forEach { s -> sampleIdByName[s.name] = db.upsertSample(s.name, s.note) }
        var obsCount = 0
        bundle.observations.forEach { o ->
            val sid = sampleIdByName[bundle.samples.first { it.id == o.sampleId }.name]!!
            db.addObservation(o.copy(sampleId = sid)); obsCount++
        }

        val service = GrainService(db)
        val verifs = mutableListOf<RunVerification>()
        var runCount = 0
        bundle.runs.forEach { er ->
            val sampleName = er.sampleName ?: bundle.samples.firstOrNull()?.name
                ?: error("run ${er.id} 缺少样品关联")
            val sampleNewId = sampleIdByName[sampleName]
                ?: error("样品 $sampleName 不在导入集合中")
            val a = er.assumptions
            val emNames = er.result.candidates
                .flatMap { c -> c.proportions.keys }
                .distinct()
                .filter { name -> bundle.endmembers.any { it.name == name } }
            val req = SolveRequest(
                sampleId = sampleNewId,
                gridName = a.gridName, targetBasis = a.targetBasis, densityModel = a.densityModel,
                uniformDensity = a.uniformDensity, riReal = a.riReal, riImag = a.riImag,
                endmemberNames = emNames,
                save = true
            )
            val resp = service.solve(req)
            runCount++
            val expected = er.result.best.sse
            val got = resp.result.best.sse
            val scale = maxOf(1.0, expected)
            val matched = kotlin.math.abs(got - expected) <= relTol * scale
            val conservationOk = resp.verification.conservationOk
            verifs.add(RunVerification(resp.runId, got, expected, matched, conservationOk))
        }

        return ImportReport(
            imported = mapOf(
                "grids" to bundle.grids.size,
                "endmembers" to bundle.endmembers.size,
                "samples" to bundle.samples.size,
                "observations" to obsCount,
                "runs" to runCount
            ),
            runVerifications = verifs,
            allVerified = verifs.all { it.matched && it.conservationOk }
        )
    }

    fun reportJson(r: ImportReport): String = json.encodeToString(ImportReport.serializer(), r)
    fun parseReport(s: String): ImportReport = json.decodeFromString(ImportReport.serializer(), s)
}
