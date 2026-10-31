package dev.loong.engine.pkg

import dev.loong.engine.ast.FieldValue
import dev.loong.engine.ast.Kind
import dev.loong.engine.ast.NodeId
import dev.loong.engine.storage.LoongastCodec
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** 发布物清单（design §7.2 三形态中的元数据）。 */
class PackageManifest(
    val module: String,     // 规范全名（含 /vN）
    val version: String,    // 语义版本 x.y.z
    val astSha256: String,  // 接口 AST 哈希
    val classesSha256: String,
) {
    fun render(): String =
        "{\"module\":\"$module\",\"version\":\"$version\",\"ast_sha256\":\"$astSha256\",\"classes_sha256\":\"$classesSha256\"}"
}

/** 依赖来源：注册表或工作区。 */
interface DepSource {
    /** 可用版本（升序）。 */
    fun versions(module: String): List<String>

    /** 拉取接口 AST 文本与已编译字节码目录（若有）。 */
    fun fetch(module: String, version: String): FetchedDep
}

class FetchedDep(val manifest: PackageManifest, val interfaceAst: String, val classesDir: Path?)

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * 包构件与本地注册表（design §7.5「静态文件服务起步」）。
 * 布局：`<root>/<模块名，'/'→'.'>/<版本>/` 下 `manifest.json` + `interface.loongast` + `classes/`。
 */
object Packages {

    /** 接口 AST：仅保留 public 声明（函数剥体）+ depends 声明（供 MVS 遍历）。 */
    fun interfaceAstText(table: dev.loong.engine.ast.NodeTable, root: NodeId): String {
        val (t, r) = LoongastCodec.deserialize(LoongastCodec.serialize(table, root))
        for (kid in t[r].kids.toList()) {
            val node = t[kid]
            val keep = when (node.kind) {
                Kind.Depends -> true
                Kind.Function -> {
                    if (fs(t, kid, "visibility") == "public") {
                        val body = t[kid].kids.firstOrNull { t[it].kind == Kind.Block }
                        if (body != null) t.delete(body)
                        true
                    } else {
                        false
                    }
                }
                Kind.Struct, Kind.Interface, Kind.Enum, Kind.Const ->
                    fs(t, kid, "visibility") == "public"
                else -> false
            }
            if (!keep) t.delete(kid)
        }
        return LoongastCodec.serialize(t, r)
    }

    /** 发布到本地注册表目录；返回清单。signing 非空时对 manifest 做 Ed25519 签名。 */
    fun publish(
        registryRoot: Path,
        table: dev.loong.engine.ast.NodeTable,
        root: NodeId,
        version: String,
        classes: Map<String, ByteArray>,
        signing: java.security.PrivateKey? = null,
    ): PackageManifest {
        val moduleName = fs(table, root, "name")
        val major = moduleName.substringAfter("/v")
        require(version.startsWith("$major.")) { "version $version must match major $major" }
        val astText = interfaceAstText(table, root)
        val dir = registryRoot.resolve(moduleName.replace('/', '.')).resolve(version)
        Files.createDirectories(dir)
        val astBytes = astText.toByteArray(Charsets.UTF_8)
        Files.write(dir.resolve("interface.loongast"), astBytes)
        val classesDir = dir.resolve("classes")
        classes.forEach { (internal, bytes) ->
            val f = classesDir.resolve(internal + ".class")
            Files.createDirectories(f.parent)
            Files.write(f, bytes)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        classesDir.toFile().walkTopDown().filter { it.isFile }.sortedBy { it.path }
            .forEach { digest.update(it.readBytes()) }
        val manifest = PackageManifest(
            moduleName, version,
            sha256(astBytes),
            digest.digest().joinToString("") { "%02x".format(it) },
        )
        val manifestBytes = manifest.render().toByteArray(Charsets.UTF_8)
        Files.write(dir.resolve("manifest.json"), manifestBytes)
        // SBOM（CycloneDX 精简形态）与 SLSA 风格 provenance（M6；Sigstore cosign 等价物后置）
        val deps = table.find(Kind.Depends).joinToString(",") {
            """"${"\""}name${"\""}:"${"\""}${fs(table, it, "module_name")}${"\""}""""
        }.let { if (it.isEmpty()) "" else it }
        Files.write(
            dir.resolve("sbom.json"),
            ("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.4\",\"components\":[" + deps + "]}")
                .toByteArray(Charsets.UTF_8),
        )
        Files.write(
            dir.resolve("provenance.json"),
            ("{\"builder\":\"loong-publish\",\"inputs\":{\"ast_sha256\":\"" + manifest.astSha256 +
                "\",\"classes_sha256\":\"" + manifest.classesSha256 + "\"}}").toByteArray(Charsets.UTF_8),
        )
        if (signing != null) {
            Files.write(dir.resolve("manifest.sig"), Signing.sign(signing, manifestBytes))
        }
        return manifest
    }

    private fun fs(table: dev.loong.engine.ast.NodeTable, id: NodeId, key: String): String =
        (table[id].fields[key] as? FieldValue.Str)?.value ?: ""
}

/** Ed25519 签名（JDK 内建，无外部依赖；Sigstore cosign 生态对接后置）。 */
object Signing {
    fun generate(): java.security.KeyPair =
        java.security.KeyPairGenerator.getInstance("Ed25519").genKeyPair()

    fun sign(privateKey: java.security.PrivateKey, data: ByteArray): ByteArray =
        java.security.Signature.getInstance("Ed25519").apply { initSign(privateKey); update(data) }.sign()

    fun verify(publicKey: java.security.PublicKey, data: ByteArray, sig: ByteArray): Boolean =
        runCatching {
            java.security.Signature.getInstance("Ed25519").apply {
                initVerify(publicKey); update(data)
            }.verify(sig)
        }.getOrDefault(false)
}

/** 本地文件注册表。verifyKey 非空时强制验签：未签名/签名不符的发布物被拒绝。 */
class LocalRegistry(private val root: Path, private val verifyKey: java.security.PublicKey? = null) : DepSource {

    override fun versions(module: String): List<String> {
        val dir = root.resolve(module.replace('/', '.'))
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { s ->
            s.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.sorted().toList()
        }
    }

    /** 验签入口：cosign 可用时优先委托（生态互操作），否则内建 Ed25519。 */
    fun verifyWithBridge(blob: Path, sig: Path, key: Path?): Boolean? =
        if (key != null) null else null // 占位：daemon 层 CosignBridge 负责委托；engine 侧保持内建

    override fun fetch(module: String, version: String): FetchedDep {
        val dir = root.resolve(module.replace('/', '.')).resolve(version)
        require(Files.isDirectory(dir)) { "module '$module' version $version not found in registry" }
        val manifestBytes = Files.readAllBytes(dir.resolve("manifest.json"))
        val manifestText = manifestBytes.decodeToString()
        val astBytes = Files.readAllBytes(dir.resolve("interface.loongast"))
        val manifest = parseManifest(manifestText)
        if (verifyKey != null) {
            val sigFile = dir.resolve("manifest.sig")
            require(Files.exists(sigFile)) { "unsigned package rejected: $module@$version (no manifest.sig)" }
            require(Signing.verify(verifyKey, manifestBytes, Files.readAllBytes(sigFile))) {
                "signature verification failed: $module@$version"
            }
        }
        val actual = sha256(astBytes)
        require(actual == manifest.astSha256) { "interface AST hash mismatch for $module@$version" }
        val classesDir = dir.resolve("classes")
        return FetchedDep(manifest, astBytes.decodeToString(), if (Files.isDirectory(classesDir)) classesDir else null)
    }

    private fun parseManifest(text: String): PackageManifest {
        var module = ""; var version = ""; var ast = ""; var cls = ""
        Regex("\"(module|version|ast_sha256|classes_sha256)\":\"([^\"]*)\"").findAll(text).forEach {
            when (it.groupValues[1]) {
                "module" -> module = it.groupValues[2]
                "version" -> version = it.groupValues[2]
                "ast_sha256" -> ast = it.groupValues[2]
                "classes_sha256" -> cls = it.groupValues[2]
            }
        }
        return PackageManifest(module, version, ast, cls)
    }
}

/** 工作区依赖源（workspace: 协议）：直接取本工作区模块，版本取其最新发布态（无版本约束语义）。 */
class WorkspaceSource(private val lookup: (String) -> Pair<dev.loong.engine.ast.NodeTable, NodeId>?) : DepSource {

    override fun versions(module: String): List<String> =
        if (lookup(module) != null) listOf("0.0.0") else emptyList()

    /** 验签入口：cosign 可用时优先委托（生态互操作），否则内建 Ed25519。 */
    fun verifyWithBridge(blob: Path, sig: Path, key: Path?): Boolean? =
        if (key != null) null else null // 占位：daemon 层 CosignBridge 负责委托；engine 侧保持内建

    override fun fetch(module: String, version: String): FetchedDep {
        val (table, root) = lookup(module) ?: error("module '$module' not in workspace")
        return FetchedDep(
            PackageManifest(fs(table, root, "name"), "0.0.0", "", ""),
            Packages.interfaceAstText(table, root),
            null,
        )
    }

    private fun fs(t: dev.loong.engine.ast.NodeTable, id: NodeId, key: String): String =
        (t[id].fields[key] as? FieldValue.Str)?.value ?: ""
}
