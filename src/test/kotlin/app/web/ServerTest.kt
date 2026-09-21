package app.web

import app.data.Db
import app.domain.Fixtures
import app.model.Bundle
import app.model.ReplayReport
import app.model.SolveRequest
import app.model.SolveResponse
import kotlinx.serialization.json.Json
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var db: Db
    private var port: Int = 0
    private lateinit var serverThread: Thread

    private fun freePort() = ServerSocket(0).use { it.localPort }

    @BeforeTest
    fun setUp() {
        val file = Files.createTempFile("grainmix-test", ".db").toFile()
        file.deleteOnExit()
        db = Db(file.absolutePath)
        port = freePort()
        serverThread = Thread { GrainServer(db).start(port) }.also { it.isDaemon = true; it.start() }
        Thread.sleep(900)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun get(path: String): String {
        val client = HttpClient.newHttpClient()
        val r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build()
        return client.send(r, HttpResponse.BodyHandlers.ofString()).body()
    }

    private fun post(path: String, body: String? = null): Pair<Int, String> {
        val client = HttpClient.newHttpClient()
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .POST(HttpRequest.BodyPublishers.ofString(body ?: ""))
        if (body != null) builder.header("Content-Type", "application/json")
        val resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return resp.statusCode() to resp.body()
    }

    @Test
    fun `首页标题为粒级混合谱`() {
        val html = get("/")
        assertTrue(html.contains("粒级混合谱"), html.take(120))
    }

    @Test
    fun `求解保存并可导出清空导入重放复核`() {
        assertTrue(get("/api/endmembers").contains("E_SILT"))
        val req = SolveRequest(
            sampleId = Fixtures.sampleMixed.id,
            endmemberIds = Fixtures.endmembers().map { it.id },
            grid = app.model.GridSpec(1.0, 2000.0, 8),
            conversion = app.model.ConversionModel(
                app.model.Basis.MASS, app.model.Basis.MASS, 2.65, 1.54
            )
        )
        val (code, body) = post("/api/solve", json.encodeToString(SolveRequest.serializer(), req))
        assertEquals(200, code, body)
        val resp = json.decodeFromString(SolveResponse.serializer(), body)
        assertTrue(resp.result.candidates.size >= 3)
        assertTrue(resp.result.diagnostics.nearCollinearPairs.isNotEmpty())
        val solveId = resp.solveId

        val bundle = json.decodeFromString(Bundle.serializer(), get("/api/export"))
        assertTrue(bundle.endmembers.size >= 3)
        assertTrue(bundle.solves.any { it.id == solveId })

        db.clearAll()
        assertEquals(0, db.listEndmembers().size)
        assertEquals(0, db.listSolves().size)

        val (icode, ibody) = post(
            "/api/import",
            json.encodeToString(Bundle.serializer(), bundle)
        )
        assertEquals(200, icode, ibody)
        assertEquals(3, db.listEndmembers().size)
        assertTrue(db.getSolve(solveId) != null)

        val (rcode, rbody) = post("/api/solves/$solveId/replay")
        assertEquals(200, rcode, rbody)
        val report = json.decodeFromString(ReplayReport.serializer(), rbody)
        assertTrue(report.matched, "重放应数值一致，maxAbsDiff=${report.maxAbsDiff}")
        assertEquals(0.0, report.maxAbsDiff, 1e-10)
    }

    @Test
    fun `非法边界返回400`() {
        val bad = """{"id":"X","name":"x","instruments":[{"basis":"MASS","edges":[2,1],"values":[1],"instrument":"s"}]}"""
        val (code, _) = post("/api/samples", bad)
        assertEquals(400, code)
    }
}
