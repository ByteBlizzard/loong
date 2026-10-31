package dev.loong.daemon.storage

import java.nio.file.Files
import java.nio.file.Path

/**
 * Sigstore cosign 桥接（design §7.6 / plan M6+）：
 * 本机存在 cosign 二进制时委托 `cosign verify-blob` 做签名验证（生态互操作），
 * 否则回退内建 Ed25519（Signing 对象）——同一 StoragePort 合同，两种实现。
 */
object CosignBridge {

    fun available(): Boolean = findBinary() != null

    /** 委托 cosign 验证 blob 签名；不可用返回 null（调用方回退内建验证）。 */
    fun verifyWithCosign(blob: Path, signature: Path, key: Path): Boolean? {
        val bin = findBinary() ?: return null
        return try {
            val proc = ProcessBuilder(
                bin, "verify-blob",
                "--key", key.toString(),
                "--signature", signature.toString(),
                "--insecure-ignore-tlog",
                blob.toString(),
            ).redirectErrorStream(true).start()
            proc.inputStream.readBytes()
            proc.waitFor() == 0
        } catch (e: Exception) {
            null
        }
    }

    private fun findBinary(): String? {
        // PATH 上查找；Windows 尝试 cosign.exe / cosign
        val names = if (System.getProperty("os.name").lowercase().contains("win")) {
            listOf("cosign.exe", "cosign")
        } else {
            listOf("cosign")
        }
        val pathDirs = System.getenv("PATH")?.split(java.io.File.pathSeparator) ?: emptyList()
        for (dir in pathDirs) {
            for (n in names) {
                val f = Path.of(dir, n)
                if (Files.isExecutable(f)) return f.toString()
            }
        }
        return null
    }
}
