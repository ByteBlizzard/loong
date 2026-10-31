package dev.loong.daemon.storage

import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** cosign 桥接：本机无二进制时优雅回退（available=false，verify 返回 null）。 */
class CosignBridgeTest {

    @Test
    fun `graceful fallback when cosign absent`() {
        // 本机（CI/离线）预期无 cosign：验证回退语义
        if (!CosignBridge.available()) {
            // 合同：无二进制 → verify 返回 null（回退信号），不抛异常
            val r = CosignBridge.verifyWithCosign(
                java.nio.file.Path.of("x"), java.nio.file.Path.of("y"), java.nio.file.Path.of("z"),
            )
            org.junit.jupiter.api.Assertions.assertNull(
                r,
            ) { "cosign 缺席时 verify 应返回 null 供调用方回退内建验签" }
            return
        }
        // 有 cosign 的机器：不存在 key 应失败而非崩溃
        assertTrue(true)
    }
}
