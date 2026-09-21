package app.db

import app.domain.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

class Database(val path: String) {
    val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        Class.forName("org.sqlite.JDBC")
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate("PRAGMA foreign_keys=ON")
        }
        migrate()
    }

    private fun migrate() = conn.createStatement().use { st ->
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS meta(
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL
            )
            """.trimIndent()
        )
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS endmembers(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL UNIQUE,
              basis TEXT NOT NULL,
              density REAL NOT NULL,
              ri_real REAL NOT NULL,
              ri_imag REAL NOT NULL,
              values_json TEXT NOT NULL,
              diag_variance_json TEXT NOT NULL,
              note TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS samples(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL UNIQUE,
              note TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS observations(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              sample_id INTEGER NOT NULL REFERENCES samples(id) ON DELETE CASCADE,
              instrument TEXT NOT NULL,
              basis TEXT NOT NULL,
              density REAL NOT NULL,
              ri_real REAL NOT NULL,
              ri_imag REAL NOT NULL,
              raw_bins_json TEXT NOT NULL
            )
            """.trimIndent()
        )
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS grids(
              name TEXT PRIMARY KEY,
              edges_json TEXT NOT NULL,
              dx REAL NOT NULL
            )
            """.trimIndent()
        )
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS runs(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              created_at TEXT NOT NULL,
              sample_id INTEGER REFERENCES samples(id) ON DELETE SET NULL,
              assumptions_json TEXT NOT NULL,
              result_json TEXT NOT NULL
            )
            """.trimIndent()
        )
        Unit
    }

    // ---------- meta ----------
    fun getMeta(key: String): String? =
        conn.prepareStatement("SELECT value FROM meta WHERE key=?").use { ps ->
            ps.setString(1, key); val rs = ps.executeQuery(); if (rs.next()) rs.getString(1) else null
        }

    fun setMeta(key: String, value: String) =
        conn.prepareStatement("INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")
            .use { ps -> ps.setString(1, key); ps.setString(2, value); ps.executeUpdate() }

    // ---------- endmembers ----------
    fun upsertEndmember(em: Endmember): Long {
        val existing = findEndmemberByName(em.name)
        return if (existing == null) {
            conn.prepareStatement(
                "INSERT INTO endmembers(name,basis,density,ri_real,ri_imag,values_json,diag_variance_json,note) VALUES(?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS
            ).use { ps ->
                ps.setString(1, em.name); ps.setString(2, em.basis.name)
                ps.setDouble(3, em.density); ps.setDouble(4, em.riReal); ps.setDouble(5, em.riImag)
                ps.setString(6, json.encodeToString(ListSerializer(Double.serializer()), em.values))
                ps.setString(7, json.encodeToString(ListSerializer(Double.serializer()), em.diagVariance))
                ps.setString(8, em.note); ps.executeUpdate()
                ps.generatedKeys.use { if (it.next()) it.getLong(1) else 0 }
            }
        } else {
            conn.prepareStatement(
                "UPDATE endmembers SET basis=?,density=?,ri_real=?,ri_imag=?,values_json=?,diag_variance_json=?,note=? WHERE id=?"
            ).use { ps ->
                ps.setString(1, em.basis.name); ps.setDouble(2, em.density)
                ps.setDouble(3, em.riReal); ps.setDouble(4, em.riImag)
                ps.setString(5, json.encodeToString(ListSerializer(Double.serializer()), em.values))
                ps.setString(6, json.encodeToString(ListSerializer(Double.serializer()), em.diagVariance))
                ps.setString(7, em.note); ps.setLong(8, existing.id); ps.executeUpdate()
            }
            existing.id
        }
    }

    fun findEndmemberByName(name: String): Endmember? =
        conn.prepareStatement("SELECT * FROM endmembers WHERE name=?").use { ps ->
            ps.setString(1, name); val rs = ps.executeQuery(); if (rs.next()) rowToEndmember(rs) else null
        }

    fun listEndmembers(): List<Endmember> =
        conn.createStatement().executeQuery("SELECT * FROM endmembers ORDER BY name").use { rs ->
            val out = mutableListOf<Endmember>(); while (rs.next()) out.add(rowToEndmember(rs)); out
        }

    fun deleteEndmember(id: Long) = conn.prepareStatement("DELETE FROM endmembers WHERE id=?").use {
        it.setLong(1, id); it.executeUpdate()
    }

    private fun rowToEndmember(rs: java.sql.ResultSet): Endmember {
        val dbl = ListSerializer(Double.serializer())
        return Endmember(
            id = rs.getLong("id"), name = rs.getString("name"),
            basis = WeightBasis.valueOf(rs.getString("basis")),
            density = rs.getDouble("density"),
            riReal = rs.getDouble("ri_real"), riImag = rs.getDouble("ri_imag"),
            values = json.decodeFromString(dbl, rs.getString("values_json")),
            diagVariance = json.decodeFromString(dbl, rs.getString("diag_variance_json")),
            note = rs.getString("note") ?: ""
        )
    }

    // ---------- samples / observations ----------
    fun upsertSample(name: String, note: String): Long {
        val id = conn.prepareStatement("SELECT id FROM samples WHERE name=?").use { ps ->
            ps.setString(1, name); ps.executeQuery().use { if (it.next()) it.getLong(1) else null }
        }
        return if (id != null) {
            conn.prepareStatement("UPDATE samples SET note=? WHERE id=?").use { ps -> ps.setString(1, note); ps.setLong(2, id); ps.executeUpdate() }
            id
        } else conn.prepareStatement("INSERT INTO samples(name,note) VALUES(?,?)", Statement.RETURN_GENERATED_KEYS).use { ps ->
            ps.setString(1, name); ps.setString(2, note); ps.executeUpdate()
            ps.generatedKeys.use { if (it.next()) it.getLong(1) else 0 }
        }
    }

    fun listSamples(): List<Sample> =
        conn.createStatement().executeQuery("SELECT * FROM samples ORDER BY id").use { rs ->
            val out = mutableListOf<Sample>(); while (rs.next()) out.add(Sample(rs.getLong("id"), rs.getString("name"), rs.getString("note") ?: "")); out
        }

    fun addObservation(o: Observation): Long =
        conn.prepareStatement(
            "INSERT INTO observations(sample_id,instrument,basis,density,ri_real,ri_imag,raw_bins_json) VALUES(?,?,?,?,?,?,?)",
            Statement.RETURN_GENERATED_KEYS
        ).use { ps ->
            ps.setLong(1, o.sampleId); ps.setString(2, o.instrument); ps.setString(3, o.basis.name)
            ps.setDouble(4, o.density); ps.setDouble(5, o.riReal); ps.setDouble(6, o.riImag)
            ps.setString(7, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(RawBin.serializer()), o.rawBins))
            ps.executeUpdate(); ps.generatedKeys.use { if (it.next()) it.getLong(1) else 0 }
        }

    fun observationsOf(sampleId: Long): List<Observation> =
        conn.prepareStatement("SELECT * FROM observations WHERE sample_id=? ORDER BY id").use { ps ->
            ps.setLong(1, sampleId); ps.executeQuery().use { rs ->
                val out = mutableListOf<Observation>()
                while (rs.next()) out.add(
                    Observation(
                        id = rs.getLong("id"), sampleId = rs.getLong("sample_id"),
                        instrument = rs.getString("instrument"), basis = WeightBasis.valueOf(rs.getString("basis")),
                        density = rs.getDouble("density"), riReal = rs.getDouble("ri_real"), riImag = rs.getDouble("ri_imag"),
                        rawBins = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(RawBin.serializer()), rs.getString("raw_bins_json"))
                    )
                )
                out
            }
        }

    // ---------- grids ----------
    fun saveGrid(g: LogGrid) = conn.prepareStatement(
        "INSERT INTO grids(name,edges_json,dx) VALUES(?,?,?) ON CONFLICT(name) DO UPDATE SET edges_json=excluded.edges_json,dx=excluded.dx"
    ).use { ps ->
        ps.setString(1, g.name)
        ps.setString(2, json.encodeToString(ListSerializer(Double.serializer()), g.edgesUm))
        ps.setDouble(3, g.dx); ps.executeUpdate()
    }

    fun listGrids(): List<LogGrid> =
        conn.createStatement().executeQuery("SELECT * FROM grids ORDER BY name").use { rs ->
            val dbl = ListSerializer(Double.serializer())
            val out = mutableListOf<LogGrid>()
            while (rs.next()) out.add(LogGrid(rs.getString("name"), json.decodeFromString(dbl, rs.getString("edges_json")), rs.getDouble("dx")))
            out
        }

    fun findGrid(name: String): LogGrid? = listGrids().firstOrNull { it.name == name }

    // ---------- runs ----------
    fun saveRun(sampleId: Long?, assumptions: SolveAssumptions, result: SolveResult, createdAt: String): Long =
        conn.prepareStatement(
            "INSERT INTO runs(created_at,sample_id,assumptions_json,result_json) VALUES(?,?,?,?)",
            Statement.RETURN_GENERATED_KEYS
        ).use { ps ->
            ps.setString(1, createdAt)
            if (sampleId == null) ps.setNull(2, java.sql.Types.INTEGER) else ps.setLong(2, sampleId)
            ps.setString(3, json.encodeToString(SolveAssumptions.serializer(), assumptions))
            ps.setString(4, json.encodeToString(SolveResult.serializer(), result))
            ps.executeUpdate(); ps.generatedKeys.use { if (it.next()) it.getLong(1) else 0 }
        }

    data class RunRow(val id: Long, val createdAt: String, val sampleId: Long?, val assumptions: SolveAssumptions, val result: SolveResult)

    fun listRuns(): List<RunRow> =
        conn.createStatement().executeQuery("SELECT * FROM runs ORDER BY id DESC").use { rs ->
            val out = mutableListOf<RunRow>()
            while (rs.next()) {
                val sid = rs.getLong("sample_id"); val sampleId = if (rs.wasNull()) null else sid
                out.add(
                    RunRow(
                        rs.getLong("id"), rs.getString("created_at"), sampleId,
                        json.decodeFromString(SolveAssumptions.serializer(), rs.getString("assumptions_json")),
                        json.decodeFromString(SolveResult.serializer(), rs.getString("result_json"))
                    )
                )
            }
            out
        }

    fun getRun(id: Long): RunRow? =
        conn.prepareStatement("SELECT * FROM runs WHERE id=?").use { ps ->
            ps.setLong(1, id); ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                val sid = rs.getLong("sample_id"); val sampleId = if (rs.wasNull()) null else sid
                RunRow(
                    rs.getLong("id"), rs.getString("created_at"), sampleId,
                    json.decodeFromString(SolveAssumptions.serializer(), rs.getString("assumptions_json")),
                    json.decodeFromString(SolveResult.serializer(), rs.getString("result_json"))
                )
            }
        }

    /** 清空全部业务数据（保留空表结构），用于“清空后重新导入复核”。 */
    fun clearAll() = conn.createStatement().use { st ->
        st.executeUpdate("DELETE FROM runs")
        st.executeUpdate("DELETE FROM observations")
        st.executeUpdate("DELETE FROM samples")
        st.executeUpdate("DELETE FROM endmembers")
        st.executeUpdate("DELETE FROM grids")
        st.executeUpdate("DELETE FROM meta")
    }

    fun countAll(): Map<String, Int> {
        fun c(t: String) = conn.createStatement().executeQuery("SELECT COUNT(*) FROM $t").use { it.getInt(1) }
        return mapOf(
            "endmembers" to c("endmembers"), "samples" to c("samples"),
            "observations" to c("observations"), "grids" to c("grids"), "runs" to c("runs")
        )
    }

    fun close() = conn.close()
}
