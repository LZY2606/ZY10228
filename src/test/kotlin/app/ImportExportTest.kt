package app

import app.db.Database
import app.db.Fixtures
import app.domain.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ImportExportTest {
    private lateinit var path: String
    private lateinit var db: Database

    @BeforeEach fun setup() {
        path = Files.createTempFile("grainmix", ".db").toString()
        db = Database(path)
        Fixtures.seed(db)
    }
    @AfterEach fun tearDown() { db.close() }

    @Test
    fun `empty database seeds fixture with grids endmembers and two instruments`() {
        val counts = db.countAll()
        assertEquals(1, counts["grids"])
        assertEquals(3, counts["endmembers"])
        assertEquals(1, counts["samples"])
        assertEquals(2, counts["observations"])
    }

    @Test
    fun `solve through service verifies conservation and stores run`() {
        val service = GrainService(db)
        val sampleId = db.listSamples().first().id
        val resp = service.solve(
            SolveRequest(sampleId = sampleId, gridName = Fixtures.GRID_NAME)
        )
        assertTrue(resp.verification.conservationOk)
        // 两台仪器都有非失量记账
        val belowSum = resp.verification.totalChecks.sumOf { it.below }
        val aboveSum = resp.verification.totalChecks.sumOf { it.above }
        assertTrue(belowSum > 0.0, "激光 below 失量应 >0")
        assertTrue(aboveSum > 0.0, "筛分 above 失量应 >0")
        assertNotNull(resp.runId)
        assertEquals(1, db.countAll()["runs"])
    }

    @Test
    fun `export then clear then reimport reproduces SSE and conservation`() {
        val service = GrainService(db)
        val sampleId = db.listSamples().first().id
        val before = service.solve(SolveRequest(sampleId = sampleId, gridName = Fixtures.GRID_NAME))
        val bundle = ImportExport.exportJson(db)

        // 清空数据库
        db.clearAll()
        assertEquals(0, db.countAll()["endmembers"])
        assertEquals(0, db.countAll()["runs"])

        // 重新导入并复核
        val report = ImportExport.clearImportVerify(db, bundle)
        assertTrue(report.allVerified, report.runVerifications.toString())
        report.runVerifications.forEach { v ->
            assertTrue(v.matched)
            assertTrue(v.conservationOk)
            assertEquals(before.result.best.sse, v.expectedSse, 1e-9)
        }
        assertEquals(3, db.countAll()["endmembers"])
        assertEquals(2, db.countAll()["observations"])
    }

    @Test
    fun `repeat reimport from fully cleared state stays deterministic`() {
        val bundle = ImportExport.exportJson(db)
        val r1 = ImportExport.clearImportVerify(db, bundle)
        val r2 = ImportExport.clearImportVerify(db, bundle)
        assertTrue(r1.allVerified && r2.allVerified)
        assertEquals(r1.runVerifications.map { it.sse }, r2.runVerifications.map { it.sse })
    }
}
