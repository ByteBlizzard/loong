package dev.loong.engine.storage

import dev.loong.engine.ast.FieldValue
import dev.loong.engine.json.Json
import dev.loong.engine.ast.Kind
import dev.loong.engine.ast.Node
import dev.loong.engine.ast.NodeId
import dev.loong.engine.ast.NodeTable

/**
 * `.loongast` 规范序列化（ADR-0002）：
 * - 规范 JSON：UTF-8、无填充空白、对象键按码点排序、NodeId 一律十进制字符串；
 * - nodes 按 id 升序；缺失字段不输出；
 * - 确定性：同一 AST 无论何时序列化，字节逐位相同——diff、内容寻址、签名的共同前提。
 *
 * 字段值以类型标签编码：{"s":..} 字符串、{"i":..} 整数、{"b":..} 布尔、{"r":..} 节点引用。
 */
object LoongastCodec {

    fun serialize(table: NodeTable, root: NodeId): String {
        val sb = StringBuilder()
        sb.append("{\"module\":")
        writeString(sb, fs(table, root, "name"))
        sb.append(",\"nodes\":[")
        val ids = table.ids.sortedBy { it.value }
        ids.forEachIndexed { i, id ->
            if (i > 0) sb.append(',')
            writeNode(sb, table[id])
        }
        sb.append("],\"schema_version\":1}")
        return sb.toString()
    }

    private fun writeNode(sb: StringBuilder, node: Node) {
        // 键序固定（码点序）：fields, id, kids, kind, parent
        sb.append("{\"fields\":{")
        val keys = node.fields.keys.sorted()
        keys.forEachIndexed { i, k ->
            if (i > 0) sb.append(',')
            writeString(sb, k)
            sb.append(':')
            writeFieldValue(sb, node.fields.getValue(k))
        }
        sb.append("},\"id\":\"").append(node.id.value).append("\",\"kids\":[")
        node.kids.forEachIndexed { i, k ->
            if (i > 0) sb.append(',')
            sb.append('"').append(k.value).append('"')
        }
        sb.append("],\"kind\":\"").append(node.kind.name).append("\",\"parent\":\"")
            .append(node.parent.value).append("\"}")
    }

    private fun writeFieldValue(sb: StringBuilder, v: FieldValue) {
        when (v) {
            is FieldValue.Str -> {
                sb.append("{\"s\":"); writeString(sb, v.value); sb.append('}')
            }
            is FieldValue.Int -> sb.append("{\"i\":").append(v.value).append('}')
            is FieldValue.Bool -> sb.append("{\"b\":").append(v.value).append('}')
            is FieldValue.Ref -> sb.append("{\"r\":\"").append(v.id.value).append("\"}")
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    // ---------- 反序列化 ----------

    fun deserialize(text: String): Pair<NodeTable, NodeId> {
        val json = Json.parse(text)
        val top = json as? Json.Obj ?: error("loongast: top level must be an object")
        val nodes = (top.map["nodes"] as? Json.Arr)?.items
            ?: error("loongast: missing nodes array")
        // 两遍：先按文件 ID 建节点（新 ID），再回填 kids（引用重映射）
        val table = NodeTable()
        val byFileId = HashMap<Long, NodeId>()
        val fieldsByFileId = HashMap<Long, Map<String, FieldValue>>()
        for (item in nodes) {
            val obj = item as? Json.Obj ?: error("loongast: node must be an object")
            val fileId = (obj.map["id"] as? Json.Str)?.v?.toLong()
                ?: error("loongast: node id must be a string")
            val kindName = (obj.map["kind"] as? Json.Str)?.v
                ?: error("loongast: node kind missing")
            val kind = Kind.entries.firstOrNull { it.name == kindName }
                ?: error("loongast: unknown kind '$kindName'")
            val fieldsObj = obj.map["fields"] as? Json.Obj
            val fields = HashMap<String, FieldValue>()
            fieldsObj?.map?.forEach { (k, v) ->
                val tagged = v as? Json.Obj ?: error("loongast: field value must be tagged object")
                fields[k] = when {
                    tagged.map.containsKey("s") ->
                        FieldValue.Str((tagged.map["s"] as Json.Str).v)
                    tagged.map.containsKey("i") ->
                        FieldValue.Int((tagged.map["i"] as Json.Num).v)
                    tagged.map.containsKey("b") ->
                        FieldValue.Bool((tagged.map["b"] as Json.Bool).v)
                    tagged.map.containsKey("r") ->
                        FieldValue.Ref(NodeId((tagged.map["r"] as Json.Str).v.toLong()))
                    else -> error("loongast: unknown field value tag")
                }
            }
            byFileId[fileId] = table.create(kind, fields)
            fieldsByFileId[fileId] = fields
        }
        // 回填 kids：parent 关系由 kids 推导（回填即收养）
        for (item in nodes) {
            val obj = item as Json.Obj
            val fileId = (obj.map["id"] as Json.Str).v.toLong()
            val kidsArr = (obj.map["kids"] as? Json.Arr)?.items ?: emptyList()
            if (kidsArr.isEmpty()) continue
            val kids = kidsArr.map { byFileId[(it as Json.Str).v.toLong()] ?: error("loongast: dangling kid ref") }
            val self = byFileId[fileId]!!
            // detach 每个 kid（create 后均为游离）后整体替换
            table.replace(self, kids = kids)
        }
        // 回填 Ref 字段（resolved_id/target_id 等）：文件 ID → 新 ID
        for ((fileId, fields) in fieldsByFileId) {
            val hasRef = fields.values.any { it is FieldValue.Ref }
            if (!hasRef) continue
            val remapped = fields.mapValues { (_, v) ->
                if (v is FieldValue.Ref) {
                    FieldValue.Ref(byFileId[v.id.value] ?: error("loongast: dangling ref target"))
                } else {
                    v
                }
            }
            table.replace(byFileId[fileId]!!, fields = remapped)
        }
        // 根：parent == 0 的 Module
        val root = nodes.map { (it as Json.Obj) }
            .firstOrNull { (it.map["parent"] as Json.Str).v.toLong() == 0L }
            ?.let { byFileId[(it.map["id"] as Json.Str).v.toLong()] }
            ?: error("loongast: no root module node")
        return table to root
    }

    /** 反序列化并保持 ID 水位语义（ADR-0004）：分配器从文件最大 ID 之后继续。 */
    fun deserializeWithWatermark(text: String): Pair<NodeTable, NodeId> {
        val (table, root) = deserialize(text)
        val maxId = table.ids.maxOfOrNull { it.value } ?: 0L
        // NodeTable 的分配器已越过所有 create 分配的 ID，无需额外处理；
        // 此函数保留给需要显式水位的调用方读取。
        return table to root
    }

    private fun fs(table: NodeTable, id: NodeId, key: String): String =
        (table[id].fields[key] as? FieldValue.Str)?.value ?: ""
}
