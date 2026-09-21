package app

import app.web.startServer

/**
 * 粒级混合谱本地服务入口。
 *
 * 演示：
 *   mvn -q exec:java -Dexec.mainClass=app.MainKt -Dexec.args='--port 5568'
 * 访问 http://127.0.0.1:5568
 */
fun main(args: Array<String>) {
    var port = 5568
    var dbPath = "data/grainmix.db"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> { port = args[i + 1].toInt(); i += 2 }
            "--db" -> { dbPath = args[i + 1]; i += 2 }
            else -> { System.err.println("未知参数: ${args[i]}"); i++ }
        }
    }
    java.io.File(dbPath).absoluteFile.parentFile?.mkdirs()
    println("粒级混合谱：http://127.0.0.1:$port  (db=$dbPath)")
    startServer(port, dbPath)
}
