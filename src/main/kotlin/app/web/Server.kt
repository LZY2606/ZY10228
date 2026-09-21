package app.web

import app.data.Db
import app.data.JsonCodecs
import app.domain.Fixtures
import app.domain.Solver
import app.model.Bundle
import app.model.Endmember
import app.model.ReplayReport
import app.model.Sample
import app.model.SolveRequest
import app.model.SolveResponse
import app.model.SolveResult
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.File
import java.time.Instant
import kotlin.math.abs

class GrainServer(private val db: Db) {

    private fun seedIfEmpty() {
        if (db.countEndmembers() == 0) {
            Fixtures.endmembers().forEach { db.upsertEndmember(it) }
            Fixtures.samples().forEach { db.upsertSample(it) }
        }
    }

    fun start(port: Int, host: String = "127.0.0.1") {
        seedIfEmpty()
        embeddedServer(CIO, port = port, host = host) {
            install(ContentNegotiation) { json(JsonCodecs.json) }
            install(StatusPages) {
                exception<IllegalArgumentException> { call, cause ->
                    call.respondText(
                        cause.message ?: "bad request",
                        ContentType.Text.Plain,
                        HttpStatusCode.BadRequest
                    )
                }
                exception<NoSuchElementException> { call, cause ->
                    call.respondText(
                        cause.message ?: "not found",
                        ContentType.Text.Plain,
                        HttpStatusCode.NotFound
                    )
                }
            }
            routing {
                get("/api/health") {
                    call.respond(mapOf("status" to "ok", "title" to "粒级混合谱"))
                }

                get("/api/endmembers") { call.respond(db.listEndmembers()) }
                post("/api/endmembers") {
                    val em = call.receive<Endmember>()
                    db.upsertEndmember(em)
                    call.respond(em)
                }
                delete("/api/endmembers/{id}") {
                    val id = call.parameters["id"]!!
                    if (!db.deleteEndmember(id)) throw NoSuchElementException("端元不存在: $id")
                    call.respond(mapOf("deleted" to id))
                }

                get("/api/samples") { call.respond(db.listSamples()) }
                post("/api/samples") {
                    val s = call.receive<Sample>()
                    db.upsertSample(s)
                    call.respond(s)
                }
                delete("/api/samples/{id}") {
                    val id = call.parameters["id"]!!
                    if (!db.deleteSample(id)) throw NoSuchElementException("样品不存在: $id")
                    call.respond(mapOf("deleted" to id))
                }

                get("/api/solves") { call.respond(db.listSolves()) }
                get("/api/solves/{id}") {
                    val id = call.parameters["id"]!!.toLong()
                    val rec = db.getSolve(id) ?: throw NoSuchElementException("运行记录不存在: $id")
                    call.respond(rec)
                }

                post("/api/solve") {
                    val req = call.receive<SolveRequest>()
                    val sample = db.listSamples().firstOrNull { it.id == req.sampleId }
                        ?: throw NoSuchElementException("样品不存在: ${req.sampleId}")
                    val ems = db.listEndmembers()
                    req.endmemberIds.forEach { id ->
                        if (ems.none { it.id == id }) throw NoSuchElementException("端元不存在: $id")
                    }
                    val chosen = ems.filter { it.id in req.endmemberIds }
                    val result = Solver.solve(req, sample, chosen)
                    val id = db.insertSolve(
                        req.sampleId,
                        JsonCodecs.encodeRequest(req),
                        JsonCodecs.encodeResult(result)
                    )
                    call.respond(SolveResponse(id, result))
                }

                /** 重放：以记录的请求重新求解，与存档结果逐字段数值比对（确定性反演）。 */
                post("/api/solves/{id}/replay") {
                    val id = call.parameters["id"]!!.toLong()
                    val rec = db.getSolve(id) ?: throw NoSuchElementException("运行记录不存在: $id")
                    val req = JsonCodecs.decodeRequest(rec.requestJson)
                    val stored = JsonCodecs.decodeResult(rec.resultJson)
                    val sample = db.listSamples().first { it.id == req.sampleId }
                    val ems = db.listEndmembers().filter { it.id in req.endmemberIds }
                    val fresh = Solver.solve(req, sample, ems)
                    var maxDiff = 0.0
                    fresh.blended.forEachIndexed { i, v ->
                        maxDiff = maxOf(maxDiff, abs(v - stored.blended.getOrElse(i) { 0.0 }))
                    }
                    val topStored = stored.candidates.firstOrNull()?.fractions ?: emptyList()
                    val topFresh = fresh.candidates.firstOrNull()?.fractions ?: emptyList()
                    val fracDiff = topStored.indices.map { i ->
                        abs(topFresh.getOrElse(i) { 0.0 } - topStored[i])
                    }
                    fracDiff.forEach { maxDiff = maxOf(maxDiff, it) }
                    call.respond(
                        ReplayReport(
                            solveId = id,
                            matched = maxDiff < 1e-9,
                            maxAbsDiff = maxDiff,
                            fractionsDiff = fracDiff
                        )
                    )
                }

                get("/api/export") {
                    val bundle = Bundle(
                        exportedAt = Instant.now().toString(),
                        endmembers = db.listEndmembers(),
                        samples = db.listSamples(),
                        solves = db.listSolves()
                    )
                    call.response.headers.append(
                        io.ktor.http.HttpHeaders.ContentDisposition,
                        "attachment; filename=\"grainmix-bundle.json\""
                    )
                    call.respond(bundle)
                }

                post("/api/import") {
                    val bundle = call.receive<Bundle>()
                    db.replaceAll(bundle)
                    call.respond(
                        mapOf(
                            "importedEndmembers" to bundle.endmembers.size,
                            "importedSamples" to bundle.samples.size,
                            "importedSolves" to bundle.solves.size
                        )
                    )
                }

                post("/api/reset") {
                    db.clearAll()
                    Fixtures.endmembers().forEach { db.upsertEndmember(it) }
                    Fixtures.samples().forEach { db.upsertSample(it) }
                    call.respond(
                        mapOf(
                            "endmembers" to db.listEndmembers().size,
                            "samples" to db.listSamples().size
                        )
                    )
                }

                get("/web/app.js") {
                    val js = GrainServer::class.java.classLoader
                        .getResource("web/app.js")?.readText()
                        ?: throw NoSuchElementException("app.js 未打包")
                    call.respondText(js, ContentType.Application.JavaScript)
                }

                get("/") {
                    val html = GrainServer::class.java.classLoader
                        .getResource("web/index.html")?.readText()
                        ?: error("index.html 未打包到资源")
                    call.respondText(html, ContentType.Text.Html)
                }
            }
        }.start(wait = true)
    }
}

