package dev.loong.daemon

import dev.loong.daemon.server.LoongdServer
import java.nio.file.Path

/** loongd：语言守护进程入口（design §8.1）。用法：loongd [port] [dbPath]。 */
object Loongd {
    const val VERSION = "0.1.0"

    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.getOrNull(0)?.toIntOrNull() ?: 8765
        val dbPath = args.getOrNull(1)?.let { Path.of(it) } ?: Path.of("loong-project", "workspace.db")
        val registry = args.getOrNull(2)?.let { Path.of(it) }
        LoongdServer(port, dbPath, registry).use { server ->
            println("loongd ${VERSION} listening on port ${server.port()} (db: ${dbPath.toAbsolutePath()})")
            Runtime.getRuntime().addShutdownHook(Thread { runCatching { server.close() } })
            server.awaitTermination()
        }
    }
}
