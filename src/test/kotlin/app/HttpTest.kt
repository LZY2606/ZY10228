package app

import app.db.Database
import app.db.Fixtures
import app.domain.SolveRequest
import app.web.grainModule
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files

class HttpTest {
    private val path = Files.createTempFile("grainmix-http", ".db").toString()
    private val db = Database(path).also { Fixtures.seed(it) }
    private val json = Json { ignoreUnknownKeys = true }

    @AfterEach fun tearDown() { db.close() }

    @Test
    fun `index page shows title`() = testApplication {
        application { grainModule(db) }
        val resp = client.get("/")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("粒级混合谱"))
    }

    @Test
    fun `health grids endmembers and solve endpoints work`() = testApplication {
        application { grainModule(db) }
        val health = json.parseToJsonElement(client.get("/api/health").bodyAsText()) as kotlinx.serialization.json.JsonObject
        assertEquals(kotlinx.serialization.json.JsonPrimitive(true), health["ok"])
        val grids = json.parseToJsonElement(client.get("/api/grids").bodyAsText()) as kotlinx.serialization.json.JsonArray
        assertEquals(1, grids.size)
        val ems = json.parseToJsonElement(client.get("/api/endmembers").bodyAsText()) as kotlinx.serialization.json.JsonArray
        assertEquals(3, ems.size)

        val sampleId = db.listSamples().first().id
        val req = SolveRequest(sampleId = sampleId, gridName = Fixtures.GRID_NAME)
        val resp = client.post("/api/solve") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(SolveRequest.serializer(), req))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("\"conservationOk\":true"))
        assertTrue(body.contains("nearCollinearPairs"))
        assertTrue(body.contains("candidates"))
    }

}
