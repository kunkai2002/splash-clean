// Node adapter for the GKD selector engine, adapted from gkd-kit/gkd `A11yContext.kt` (GPL-3.0).
// Simplification: caches live for one query cycle only (a new QueryContext per cycle),
// so no expiry bookkeeping is needed.
package io.github.kunkai2002.splashclean.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.github.kunkai2002.splashclean.rule.ResolvedRule
import li.gkd.selector.FastQuery
import li.gkd.selector.MatchOptions
import li.gkd.selector.NodeAdapter
import li.gkd.selector.Selector
import li.gkd.selector.TraversalCandidate
import li.gkd.selector.relation.RelationExpression

const val MAX_CHILD_SIZE = 512
const val MAX_DESCENDANTS_SIZE = 4096

fun AccessibilityNodeInfo.getVid(): CharSequence? {
    val id = viewIdResourceName ?: return null
    val appId = packageName ?: return null
    if (id.startsWith(appId) && id.startsWith(":id/", appId.length)) {
        return id.subSequence(appId.length + ":id/".length, id.length)
    }
    return null
}

fun AccessibilityNodeInfo.boundsInScreen(): Rect = Rect().also { getBoundsInScreen(it) }

class QueryContext(private val rootProvider: () -> AccessibilityNodeInfo?) {
    private val childCache = HashMap<Pair<AccessibilityNodeInfo, Int>, AccessibilityNodeInfo>()
    private val indexCache = HashMap<AccessibilityNodeInfo, Int>()
    private val parentCache = HashMap<AccessibilityNodeInfo, AccessibilityNodeInfo>()
    private val boundsCache = HashMap<AccessibilityNodeInfo, Rect>()
    private var root: AccessibilityNodeInfo? = null
    private var rootLoaded = false

    fun root(): AccessibilityNodeInfo? {
        if (!rootLoaded) {
            root = rootProvider()
            rootLoaded = true
        }
        return root
    }

    private fun bounds(node: AccessibilityNodeInfo): Rect =
        boundsCache.getOrPut(node) { node.boundsInScreen() }

    private fun getCacheParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root() == node) return null
        parentCache[node]?.let { return it }
        return node.parent?.also { parentCache[node] = it }
    }

    private fun getCacheChild(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? {
        if (index !in 0 until node.childCount) return null
        return childCache[node to index] ?: node.getChild(index)?.also { child ->
            indexCache[child] = index
            parentCache[child] = node
            childCache[node to index] = child
        }
    }

    private fun getCacheIndex(node: AccessibilityNodeInfo): Int {
        indexCache[node]?.let { return it }
        getCacheChildren(getCacheParent(node)).forEachIndexed { index, child ->
            if (child == node) {
                indexCache[node] = index
                return index
            }
        }
        return 0
    }

    private fun getCacheDepth(node: AccessibilityNodeInfo): Int? {
        val visited = mutableSetOf<AccessibilityNodeInfo>()
        var p: AccessibilityNodeInfo = node
        var depth = 0
        while (visited.add(p)) {
            p = getCacheParent(p) ?: return depth
            depth++
        }
        return null
    }

    private fun getCacheChildren(node: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> {
        if (node == null) return emptySequence()
        return sequence {
            repeat(node.childCount.coerceAtMost(MAX_CHILD_SIZE)) { index ->
                val child = getCacheChild(node, index) ?: return@sequence
                yield(child)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun nodeAttr(node: AccessibilityNodeInfo, name: String): Any? = when (name) {
        "id" -> node.viewIdResourceName
        "vid" -> node.getVid()
        "name" -> node.className
        "text" -> node.text
        "desc" -> node.contentDescription
        "clickable" -> node.isClickable
        "focusable" -> node.isFocusable
        "checkable" -> node.isCheckable
        "checked" -> node.isChecked
        "editable" -> node.isEditable
        "longClickable" -> node.isLongClickable
        "visibleToUser" -> node.isVisibleToUser
        "left" -> bounds(node).left
        "top" -> bounds(node).top
        "right" -> bounds(node).right
        "bottom" -> bounds(node).bottom
        "width" -> bounds(node).width()
        "height" -> bounds(node).height()
        "index" -> getCacheIndex(node)
        "depth" -> getCacheDepth(node)
        "childCount" -> node.childCount
        "parent" -> getCacheParent(node)
        else -> null
    }

    private fun getFastQueryNodes(node: AccessibilityNodeInfo, fastQuery: FastQuery): List<AccessibilityNodeInfo> =
        when (fastQuery) {
            is FastQuery.Id -> node.findAccessibilityNodeInfosByViewId(fastQuery.value)
            is FastQuery.Text -> node.findAccessibilityNodeInfosByText(fastQuery.value)
            is FastQuery.Vid -> node.findAccessibilityNodeInfosByViewId("${node.packageName}:id/${fastQuery.value}")
        } ?: emptyList()

    private inner class A11yNodeAdapter : NodeAdapter<AccessibilityNodeInfo>() {
        override fun getAttr(target: Any, name: String): Any? = when (target) {
            is AccessibilityNodeInfo -> nodeAttr(target, name)
            else -> null
        }

        override fun getInvoke(target: Any, name: String, args: List<Any>): Any? = when (target) {
            is AccessibilityNodeInfo -> when (name) {
                "getChild" -> getCacheChild(target, args[0] as Int)
                else -> null
            }

            else -> null
        }

        override fun getName(node: AccessibilityNodeInfo): String? = node.className?.toString()
        override fun getChildCount(node: AccessibilityNodeInfo): Int = node.childCount.coerceAtMost(MAX_CHILD_SIZE)
        override fun getChild(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? = getCacheChild(node, index)
        override fun getParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? = getCacheParent(node)
        override fun getNodeKey(node: AccessibilityNodeInfo): Any = node
        override fun getRoot(node: AccessibilityNodeInfo): AccessibilityNodeInfo? = root()

        override fun getDescendants(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
            val visited = mutableSetOf(node)
            val stack = getCacheChildren(node).toMutableList()
            if (stack.isEmpty()) return@sequence
            stack.reverse()
            val temp = mutableListOf<AccessibilityNodeInfo>()
            do {
                val top = stack.removeAt(stack.lastIndex)
                if (!visited.add(top)) continue
                yield(top)
                for (c in getCacheChildren(top)) temp.add(c)
                if (temp.isNotEmpty()) {
                    for (i in temp.size - 1 downTo 0) stack.add(temp[i])
                    temp.clear()
                }
            } while (stack.isNotEmpty())
        }.take(MAX_DESCENDANTS_SIZE)

        override fun traverseChildren(
            node: AccessibilityNodeInfo,
            relationExpression: RelationExpression,
        ): Sequence<TraversalCandidate<AccessibilityNodeInfo>> = sequence {
            repeat(node.childCount.coerceAtMost(MAX_CHILD_SIZE)) { offset ->
                relationExpression.maxOffset?.let { if (offset > it) return@sequence }
                if (relationExpression.checkOffset(offset)) {
                    getCacheChild(node, offset)?.let { yield(TraversalCandidate(it, offset)) }
                }
            }
        }

        override fun traversePreviousSiblings(
            node: AccessibilityNodeInfo,
            relationExpression: RelationExpression,
        ): Sequence<TraversalCandidate<AccessibilityNodeInfo>> = sequence {
            val parentVal = getCacheParent(node) ?: return@sequence
            val index = indexCache[node]
            if (index != null) {
                var i = index - 1
                var offset = 0
                while (0 <= i && i < parentVal.childCount) {
                    relationExpression.maxOffset?.let { if (offset > it) return@sequence }
                    if (relationExpression.checkOffset(offset)) {
                        getCacheChild(parentVal, i)?.let { yield(TraversalCandidate(it, offset)) }
                    }
                    i--
                    offset++
                }
            } else {
                val list = getCacheChildren(parentVal).takeWhile { it != node }.toMutableList()
                list.reverse()
                for ((offset, sibling) in list.withIndex()) {
                    if (relationExpression.maxOffset?.let { offset > it } == true) break
                    if (relationExpression.checkOffset(offset)) yield(TraversalCandidate(sibling, offset))
                }
            }
        }

        override fun traverseFollowingSiblings(
            node: AccessibilityNodeInfo,
            relationExpression: RelationExpression,
        ): Sequence<TraversalCandidate<AccessibilityNodeInfo>> {
            val parentVal = getCacheParent(node) ?: return emptySequence()
            val index = indexCache[node]
            return if (index != null) {
                sequence {
                    var i = index + 1
                    var offset = 0
                    while (0 <= i && i < parentVal.childCount) {
                        relationExpression.maxOffset?.let { if (offset > it) return@sequence }
                        if (relationExpression.checkOffset(offset)) {
                            getCacheChild(parentVal, i)?.let { yield(TraversalCandidate(it, offset)) }
                        }
                        i++
                        offset++
                    }
                }
            } else {
                sequence {
                    getCacheChildren(parentVal).dropWhile { it != node }.drop(1)
                        .forEachIndexed { offset, sibling ->
                            if (relationExpression.maxOffset?.let { offset > it } == true) return@sequence
                            if (relationExpression.checkOffset(offset)) yield(TraversalCandidate(sibling, offset))
                        }
                }
            }
        }

        override fun traverseDescendants(
            node: AccessibilityNodeInfo,
            relationExpression: RelationExpression,
        ): Sequence<TraversalCandidate<AccessibilityNodeInfo>> = sequence {
            val visited = mutableSetOf(node)
            val stack = getCacheChildren(node).toMutableList()
            if (stack.isEmpty()) return@sequence
            stack.reverse()
            val temp = mutableListOf<AccessibilityNodeInfo>()
            var offset = 0
            do {
                val top = stack.removeAt(stack.lastIndex)
                if (!visited.add(top)) continue
                if (relationExpression.checkOffset(offset)) yield(TraversalCandidate(top, offset))
                offset++
                if (offset > MAX_DESCENDANTS_SIZE) return@sequence
                relationExpression.maxOffset?.let { if (offset > it) return@sequence }
                for (c in getCacheChildren(top)) temp.add(c)
                if (temp.isNotEmpty()) {
                    for (i in temp.size - 1 downTo 0) stack.add(temp[i])
                    temp.clear()
                }
            } while (stack.isNotEmpty())
        }

        override fun getFastQueryDescendants(
            node: AccessibilityNodeInfo,
            fastQueryList: List<FastQuery>,
        ): Sequence<AccessibilityNodeInfo> = sequence {
            val yielded = mutableSetOf<Any>(node)
            for (fastQuery in fastQueryList) {
                for (child in getFastQueryNodes(node, fastQuery)) {
                    if (yielded.add(child)) yield(child)
                }
            }
        }
    }

    private val adapter = A11yNodeAdapter()

    fun querySelfOrSelector(node: AccessibilityNodeInfo, selector: Selector, options: MatchOptions): AccessibilityNodeInfo? {
        if (selector.isMatchRoot) {
            return selector.match(root() ?: return null, adapter, options)
        }
        selector.match(node, adapter, options)?.let { return it }
        return adapter.querySelector(node, selector, options)
    }

    fun queryAll(node: AccessibilityNodeInfo, selector: Selector, options: MatchOptions): List<AccessibilityNodeInfo> =
        adapter.querySelectorAll(node, selector, options).toList()

    /** Same order and semantics as GKD's A11yContext.queryRule. */
    fun queryRule(rule: ResolvedRule, node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queryNode = (if (rule.matchRoot) root() else node) ?: return null
        var resultNode: AccessibilityNodeInfo? = null
        if (rule.anyMatches.isNotEmpty()) {
            for (selector in rule.anyMatches) {
                resultNode = querySelfOrSelector(queryNode, selector, rule.matchOptions)
                if (resultNode != null) break
            }
            if (resultNode == null) return null
        }
        for (selector in rule.matches) {
            resultNode = querySelfOrSelector(queryNode, selector, rule.matchOptions) ?: return null
        }
        for (selector in rule.excludeMatches) {
            querySelfOrSelector(queryNode, selector, rule.matchOptions)?.let { return null }
        }
        if (rule.excludeAllMatches.isNotEmpty()) {
            val allExclude = rule.excludeAllMatches.all {
                querySelfOrSelector(queryNode, it, rule.matchOptions) == null
            }
            if (!allExclude) return null
        }
        return resultNode
    }
}
