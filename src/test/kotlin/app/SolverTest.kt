package app

import app.db.Database
import app.db.Fixtures
import app.domain.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.math.abs

class SolverTest {

    private val path = Files.createTempFile("grainmix-solver", ".db").toString()
    private val db = Database(path).also { Fixtures.seed(it) }

    @AfterEach fun tearDown() { db.close() }

    private fun runSolve(): SolveResult {
        val service = GrainService(db)
        val sid = db.listSamples().first().id
        return service.solve(SolveRequest(sampleId = sid, gridName = Fixtures.GRID_NAME)).result
    }

    @Test
    fun `near-collinear pair A B is detected`() {
        val r = runSolve()
        val hasAB = r.identifiability.nearCollinearPairs.any {
            it.a.startsWith("EM-A") && it.b.startsWith("EM-B")
        }
        assertTrue(hasAB, "应识别 A、B 近相关：${r.identifiability.nearCollinearPairs}")
        assertTrue(r.identifiability.minAbsCorrelation <= 0.95)
    }

    @Test
    fun `multiple sparse candidates across distinct clusters are retained`() {
        val r = runSolve()
        assertTrue(r.candidates.size >= 2, "应保留多个候选")
        r.candidates.forEach { assertTrue(it.activeNames.size <= 2, "候选应稀疏：${it.activeNames}") }
        // 关键验收：至少两个簇，一个主要用 A，另一个主要用 B（近相关导致的替代稀疏解）
        assertTrue(r.clusters.size >= 2, "候选应归为多个簇：${r.clusters.size}")
        val usesA = r.candidates.any { c -> c.activeNames.any { it.startsWith("EM-A") } && c.proportions.values.any { v -> v > 0.1 } }
        val usesB = r.candidates.any { c -> c.activeNames.any { it.startsWith("EM-B") } && c.proportions.values.any { v -> v > 0.1 } }
        assertTrue(usesA && usesB, "应同时保留以 A 和以 B 解释细粒端的候选")
    }

    @Test
    fun `best fit recovers truth proportions and residual is unbiased`() {
        val r = runSolve()
        val a = r.best.proportions.entries.first { it.key.startsWith("EM-A") }.value
        val c = r.best.proportions.entries.first { it.key.startsWith("EM-C") }.value
        assertEquals(0.55, a, 0.10, "A 系数应接近 0.55，实际=$a")
        assertEquals(0.62, c, 0.12, "C 系数应接近 0.62，实际=$c")
        // 最佳解 SSE 为所有候选最小
        assertEquals(r.candidates.minOf { it.sse }, r.best.sse, 1e-9)
        // 残差均值近似无偏（支持箱上）
        assertTrue(abs(r.residual.average()) < 0.05, "mean residual=${r.residual.average()}")
    }
}
