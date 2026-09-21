package app.web

import app.db.Database
import app.db.Fixtures
import app.domain.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun startServer(port: Int, dbPath: String) {
    val db = Database(dbPath)
    if (db.listGrids().isEmpty() && db.listEndmembers().isEmpty()) Fixtures.seed(db)

    embeddedServer(Netty, port = port, host = "127.0.0.1") {
        grainModule(db)
    }.start(wait = true)
}

@Serializable
data class HealthResponse(val ok: Boolean, val title: String)

@Serializable
data class ProjectPreviewDTO(
    val gridName: String, val edgesUm: List<Double>, val centersUm: List<Double>,
    val projection: ProjectionResult
)

@Serializable
data class ResetResponse(val reset: Boolean, val counts: Map<String, Int>)

@Serializable
data class ObservationDTO(
    val id: Long, val instrument: String, val basis: String,
    val density: Double, val riReal: Double, val riImag: Double,
    val bins: List<RawBinDTO>
)

@Serializable
data class SampleDTO(val id: Long, val name: String, val note: String, val observations: List<ObservationDTO>)

@Serializable
data class RunDetailDTO(
    val id: Long, val createdAt: String, val sampleId: Long?,
    val assumptions: SolveAssumptions, val result: SolveResult
)

@Serializable
data class RunSummaryDTO(
    val id: Long, val createdAt: String, val sampleId: Long?,
    val assumptions: SolveAssumptions, val best: Candidate,
    val identifiability: Identifiability,
    val obsUncovered: Double, val obsTotalRaw: Double, val clusterCount: Int
)

@Serializable
data class EndmemberUpsert(
    val id: Long = 0,
    val name: String,
    val basis: WeightBasis,
    val density: Double = 2.65,
    val riReal: Double = 1.54,
    val riImag: Double = 0.01,
    val values: List<Double>,
    val diagVariance: List<Double>? = null,
    val note: String = ""
)

@Serializable
data class RawBinDTO(val loUm: Double, val hiUm: Double, val value: Double)

@Serializable
data class ObservationUpsert(
    val sampleName: String,
    val sampleNote: String = "",
    val instrument: String,
    val basis: WeightBasis,
    val density: Double = 2.65,
    val riReal: Double = 1.54,
    val riImag: Double = 0.01,
    val bins: List<RawBinDTO>
)

@Serializable
data class GridUpsert(val name: String, val loUm: Double, val hiUm: Double, val binsPerDecade: Int)

fun Application.grainModule(db: Database) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true })
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "bad request")))
        }
        exception<IllegalStateException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "bad state")))
        }
    }
    routing { configureRoutes(db) }
}

private fun Routing.configureRoutes(db: Database) {
    val service = GrainService(db)

    get("/api/health") { call.respond(HealthResponse(true, "粒级混合谱")) }

    get("/api/grids") { call.respond(db.listGrids()) }
    post("/api/grids") {
        val req = call.receive<GridUpsert>()
        val g = makeLogGrid(req.name, req.loUm, req.hiUm, req.binsPerDecade)
        db.saveGrid(g); call.respond(g)
    }

    get("/api/endmembers") { call.respond(db.listEndmembers()) }
    post("/api/endmembers") {
        val req = call.receive<EndmemberUpsert>()
        require(req.values.isNotEmpty()) { "端元分布不能为空" }
        val variance = req.diagVariance
            ?: req.values.map { v -> (0.05 * v + 0.002).let { it * it } }
        require(variance.size == req.values.size) { "协方差长度需与分布一致" }
        val id = db.upsertEndmember(
            Endmember(
                id = req.id, name = req.name, basis = req.basis, density = req.density,
                riReal = req.riReal, riImag = req.riImag, values = req.values,
                diagVariance = variance, note = req.note
            )
        )
        call.respond(mapOf("id" to id))
    }
    delete("/api/endmembers/{id}") {
        val id = call.parameters["id"]!!.toLong()
        db.deleteEndmember(id); call.respond(mapOf("deleted" to id))
    }

    get("/api/samples") {
        call.respond(db.listSamples().map { sm ->
            SampleDTO(
                sm.id, sm.name, sm.note,
                db.observationsOf(sm.id).map { o ->
                    ObservationDTO(
                        o.id, o.instrument, o.basis.name, o.density, o.riReal, o.riImag,
                        o.rawBins.map { RawBinDTO(it.loUm, it.hiUm, it.value) }
                    )
                }
            )
        })
    }
    post("/api/observations") {
        val req = call.receive<ObservationUpsert>()
        val sid = db.upsertSample(req.sampleName, req.sampleNote)
        val id = db.addObservation(
            Observation(
                sampleId = sid, instrument = req.instrument, basis = req.basis,
                density = req.density, riReal = req.riReal, riImag = req.riImag,
                rawBins = req.bins.map { RawBin(it.loUm, it.hiUm, it.value) }
            )
        )
        call.respond(mapOf("sampleId" to sid, "observationId" to id))
    }

    post("/api/project") {
        val req = call.receive<ObservationUpsert>()
        val grid = db.listGrids().let { gs -> gs.firstOrNull { it.name == Fixtures.GRID_NAME } ?: gs.first() }
        val raw = RawDistribution(req.instrument, req.basis, req.bins.map { RawBin(it.loUm, it.hiUm, it.value) })
        val proj = projectToGrid(raw, grid)
        call.respond(ProjectPreviewDTO(grid.name, grid.edgesUm, grid.centersUm, proj))
    }

    post("/api/solve") {
        val req = call.receive<SolveRequest>()
        call.respond(service.solve(req))
    }

    get("/api/runs") {
        call.respond(db.listRuns().map { r ->
            RunSummaryDTO(
                r.id, r.createdAt, r.sampleId, r.assumptions, r.result.best,
                r.result.identifiability, r.result.obsUncovered, r.result.obsTotalRaw,
                r.result.clusters.size
            )
        })
    }
    get("/api/runs/{id}") {
        val id = call.parameters["id"]!!.toLong()
        val row = db.getRun(id)
        if (row == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "run not found"))
        else call.respond(
            RunDetailDTO(row.id, row.createdAt, row.sampleId, row.assumptions, row.result)
        )
    }

    get("/api/export") {
        val text = ImportExport.exportJson(db)
        call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"grainmix-export.json\"")
        call.respondText(text, ContentType.Application.Json)
    }
    post("/api/reimport") {
        val text = call.receiveText()
        val report = ImportExport.clearImportVerify(db, text)
        call.respond(report)
    }
    post("/api/reset-fixtures") {
        db.clearAll(); Fixtures.seed(db)
        call.respond(ResetResponse(true, db.countAll()))
    }
    get("/api/counts") { call.respond(db.countAll()) }

    get("/") {
        val html = object {}.javaClass.getResource("/static/index.html")?.readText()
            ?: java.io.File("src/main/resources/static/index.html").takeIf { it.exists() }?.readText()
            ?: error("index.html missing")
        call.respondText(html, ContentType.Text.Html)
    }
}
