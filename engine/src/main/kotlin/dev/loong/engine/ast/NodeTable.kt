package dev.loong.engine.ast

/**
 * 稳定 ID 节点表：AST 的存储与编辑操作（design.md §4.2/§4.5，契约见 ast-schema-v1.md §6）。
 *
 * - 节点以 ID 为键存于表内，kids 只是 ID 列表；移动/重排子节点是父节点 kids 列表的修改。
 * - 所有编辑操作返回「受影响节点 ID 集合」= 操作节点自身 + 祖先链（至根），
 *   增量编译调度器（M2）据此定位所属模块。
 * - 删除为整棵子树移出节点表；ID 永不复用（ADR-0004）。
 * - 构建过程中允许游离子树（parent = NONE 的非 Module 节点），序列化前必须全部挂接。
 */
class NodeTable(val allocator: IdAllocator = IdAllocator()) {

    private val nodes = LinkedHashMap<NodeId, Node>()

    val watermark: Long get() = allocator.watermark

    val size: Int get() = nodes.size

    val ids: List<NodeId> get() = nodes.keys.toList()

    // ---------- 读 ----------

    /** 读取节点；不存在则抛 [NoSuchElementException]（ID 永不复用意味着不存在 = 从未创建或已删除）。 */
    operator fun get(id: NodeId): Node =
        nodes[id] ?: throw NoSuchElementException("node $id does not exist")

    fun getOrNull(id: NodeId): Node? = nodes[id]

    /** 根节点（parent = NONE）列表。完整模块 AST 应恰有一个 Module；构建期可有多个游离子树。 */
    fun roots(): List<NodeId> = nodes.values.filter { it.parent == NodeId.NONE }.map { it.id }

    fun kidsOf(id: NodeId): List<NodeId> = get(id).kids

    fun find(kind: Kind? = null, predicate: (Node) -> Boolean = { true }): List<NodeId> =
        nodes.values
            .filter { (kind == null || it.kind == kind) && predicate(it) }
            .map { it.id }

    /** 自 id 向上（不含 id）到根的祖先链。 */
    fun ancestors(id: NodeId): Set<NodeId> {
        val result = LinkedHashSet<NodeId>()
        var cur = get(id).parent
        while (cur != NodeId.NONE) {
            result.add(cur)
            cur = get(cur).parent
        }
        return result
    }

    // ---------- 建（游离子树） ----------

    /**
     * 创建游离节点。kids 中的游离子节点被收养（parent 改指新节点），
     * 要求它们此前是游离的——一棵子树任何时刻至多挂在一个父节点上。
     */
    fun create(
        kind: Kind,
        fields: Map<String, FieldValue> = emptyMap(),
        kids: List<NodeId> = emptyList(),
    ): NodeId {
        val id = allocator.allocate()
        require(kids.none { it == id }) { "node $id cannot be its own kid" }
        kids.forEach { kid ->
            require(get(kid).parent == NodeId.NONE) { "kid $kid already attached to ${get(kid).parent}" }
        }
        kids.forEach { kid ->
            nodes[kid] = get(kid).copy(parent = id)
        }
        nodes[id] = Node(id, kind, fields, kids)
        return id
    }

    // ---------- 编辑操作（design.md §4.5） ----------

    /** 在 parent 的第 index 个子位创建并挂接新节点。 */
    fun insert(
        parent: NodeId,
        index: Int,
        kind: Kind,
        fields: Map<String, FieldValue> = emptyMap(),
    ): NodeId {
        val id = create(kind, fields)
        attach(id, parent, index)
        return id
    }

    /** 挂接游离子树。返回受影响集合。 */
    fun attach(id: NodeId, parent: NodeId, index: Int): Set<NodeId> {
        val node = get(id)
        require(parent != NodeId.NONE) { "parent cannot be NONE" }
        require(parent != id) { "node $id cannot attach to itself" }
        require(node.parent == NodeId.NONE) { "node $id is already attached to ${node.parent}" }
        val parentNode = get(parent)
        val newKids = parentNode.kids.toMutableList()
        newKids.add(index.coerceIn(0, newKids.size), id)
        nodes[parent] = parentNode.copy(kids = newKids)
        nodes[id] = node.copy(parent = parent)
        return affected(id)
    }

    /**
     * 原地替换字段与/或子节点：旧子树整体删除，新 kids 必须游离（由本操作收养）。
     * 节点 ID 不变——这正是 AI「修改节点 f3a2」的语义。
     */
    fun replace(
        id: NodeId,
        fields: Map<String, FieldValue>? = null,
        kids: List<NodeId>? = null,
    ): Set<NodeId> {
        val node = get(id)
        if (kids != null) {
            node.kids.forEach { deleteSubtree(it) }
            require(kids.none { it == id }) { "node $id cannot be its own kid" }
            kids.forEach { kid ->
                require(get(kid).parent == NodeId.NONE) { "kid $kid already attached to ${get(kid).parent}" }
            }
            kids.forEach { kid ->
                nodes[kid] = get(kid).copy(parent = id)
            }
        }
        nodes[id] = node.copy(
            fields = fields ?: node.fields,
            kids = kids ?: node.kids,
        )
        return affected(id)
    }

    /** 把已挂接节点移到 newParent 的第 index 个子位；禁止移入自身子树。返回含新旧祖先链。 */
    fun move(id: NodeId, newParent: NodeId, index: Int): Set<NodeId> {
        require(newParent != NodeId.NONE) { "newParent cannot be NONE" }
        require(id != newParent) { "node $id cannot move into itself" }
        val node = get(id)
        require(node.parent != NodeId.NONE) { "cannot move a detached node; use attach" }
        // 防环检查必须先于任何变更：newParent 在 id 子树内 ⟺ 其祖先链经过 id。
        // 被拒绝的操作不得留下任何变更（原子性）。
        check(id !in ancestors(newParent)) { "cannot move a node into its own subtree" }
        val oldAncestors = ancestors(id)
        detachFromParent(id)
        val parentNode = get(newParent)
        val newKids = parentNode.kids.toMutableList()
        newKids.add(index.coerceIn(0, newKids.size), id)
        nodes[newParent] = parentNode.copy(kids = newKids)
        nodes[id] = node.copy(parent = newParent)
        return oldAncestors + setOf(id) + ancestors(id)
    }

    /** 使已挂接节点游离（从父节点 kids 中移除，子树保持完整）。供名字解析等结构重写使用。 */
    fun detach(id: NodeId): Set<NodeId> {
        val node = get(id)
        require(node.parent != NodeId.NONE) { "node $id is already detached" }
        val oldAncestors = ancestors(id)
        detachFromParent(id)
        return oldAncestors
    }

    /** 深拷贝子树（全新 ID，游离根）。编辑器「复制语句/声明」的原语。 */
    fun cloneSubtree(id: NodeId): NodeId {
        val node = get(id)
        val clonedKids = node.kids.map { cloneSubtree(it) }
        return create(node.kind, node.fields, clonedKids)
    }

    /** 整棵子树移出节点表（ID 永不复用）。返回幸存祖先（含直接父节点，其 kids 已变）+ 被删根 ID。 */
    fun delete(id: NodeId): Set<NodeId> {        val node = get(id)
        val affected =
            if (node.parent != NodeId.NONE) {
                val parent = node.parent
                detachFromParent(id)
                setOf(parent) + ancestors(parent)
            } else {
                emptySet()
            }
        deleteSubtree(id)
        return affected + id
    }

    // ---------- 内部 ----------

    private fun affected(id: NodeId): Set<NodeId> = setOf(id) + ancestors(id)

    private fun detachFromParent(id: NodeId) {
        val node = get(id)
        val parent = node.parent
        if (parent == NodeId.NONE) return
        val parentNode = get(parent)
        nodes[parent] = parentNode.copy(kids = parentNode.kids - id)
        nodes[id] = node.copy(parent = NodeId.NONE)
    }

    private fun deleteSubtree(id: NodeId) {
        get(id).kids.forEach { deleteSubtree(it) }
        nodes.remove(id)
    }

    // ---------- 一致性检查 ----------

    /**
     * 校验 ast-schema-v1.md §3 的不变量（测试与 daemon 恢复后调用）。
     * 返回违例描述列表，空列表 = 通过。
     */
    fun checkInvariants(): List<String> {
        val problems = mutableListOf<String>()
        for (node in nodes.values) {
            for (kid in node.kids) {
                val kidNode = getOrNull(kid)
                when {
                    kidNode == null -> problems += "node ${node.id}: kid $kid does not exist"
                    kidNode.parent != node.id ->
                        problems += "kid $kid parent mismatch: ${kidNode.parent} != ${node.id}"
                }
            }
            if (node.kids.toSet().size != node.kids.size) {
                problems += "node ${node.id}: duplicate kids"
            }
            if (node.parent != NodeId.NONE) {
                val parent = getOrNull(node.parent)
                when {
                    parent == null -> problems += "node ${node.id}: parent ${node.parent} does not exist"
                    node.id !in parent.kids ->
                        problems += "node ${node.id} not in parent ${parent.id} kids"
                }
            }
            // 环检测：祖先链步数超过表大小即视为成环
            var steps = 0
            var cur = node
            while (cur.parent != NodeId.NONE) {
                steps++
                if (steps > nodes.size) {
                    problems += "cycle detected on ancestor chain of ${node.id}"
                    break
                }
                cur = get(cur.parent)
            }
        }
        return problems
    }
}
