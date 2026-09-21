package app

import app.data.Db
import app.web.GrainServer
import java.io.File

/**
 * 粒级混合谱：本地服务入口。
 * 参数：--port 5568 [--db path/to/grainmix.db]
 */
fun main(args: Array<String>) {
    val port = args.indexOf("--port").let { idx ->
        if (idx >= 0 && idx + 1 < args.size) args[idx + 1].toInt() else 5568
    }
    val dbPath = args.firstOrNull { it.startsWith("--db=") }
        ?.substringAfter("=")
        ?: System.getenv("GRAINMIX_DB")
        ?: "data/grainmix.db"
    File(dbPath).absoluteFile.parentFile?.mkdirs()
    println("粒级混合谱启动：http://127.0.0.1:$port  数据库=$dbPath")
    Db(dbPath).use { db -> GrainServer(db).start(port) }
}
