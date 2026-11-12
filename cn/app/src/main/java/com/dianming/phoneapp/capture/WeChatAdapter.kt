package com.dianming.phoneapp.capture

import android.content.res.Resources
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.dianming.phoneapp.core.ChatSnapshot
import com.dianming.phoneapp.core.Msg

/** Reads only message nodes that WeChat exposes to this ordinary accessibility service. */
class WeChatAdapter : ChatAppAdapter {
    override val pkg = WeChatRules.PACKAGE

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        if (root.packageName?.toString() != pkg) return null
        val read = WeChatRules.read(AndroidWeChatNode(root), res.displayMetrics.widthPixels,
            res.displayMetrics.heightPixels) ?: return null
        val title = if (read.structuredChat) read.title else findTitleInActionBar(
            root, read.firstMessageTop, res.displayMetrics.widthPixels, res
        )
        return WeChatRules.snapshot(read, title)
    }

    private class AndroidWeChatNode(private val node: AccessibilityNodeInfo) : WeChatNode {
        override val viewId: String? get() = node.viewIdResourceName
        override val text: String? get() = node.text?.toString()
        override val className: String? get() = node.className?.toString()
        override val editable: Boolean get() = node.isEditable
        override val visible: Boolean get() = node.isVisibleToUser
        override val bounds: IntArray get() = Rect().also(node::getBoundsInScreen).let {
            intArrayOf(it.left, it.top, it.right, it.bottom)
        }
        override val childCount: Int get() = node.childCount
        override fun child(index: Int): WeChatNode? = node.getChild(index)?.let(::AndroidWeChatNode)
    }

}

/** Small, Android-free surface so geometry and filtering can be tested with recorded trees. */
internal interface WeChatNode {
    val viewId: String?
    val text: String?
    val className: String?
    val editable: Boolean
    val visible: Boolean
    val bounds: IntArray // left, top, right, bottom in screen pixels
    val childCount: Int
    fun child(index: Int): WeChatNode?
}

internal object WeChatRules {
    const val PACKAGE = "com.tencent.mm"
    const val INPUT_ID = "$PACKAGE:id/bkk"
    private const val BODY_ID = "$PACKAGE:id/bkl" // observed on WeChat 8.0.80
    private const val LIST_ID = "$PACKAGE:id/bp0"
    private const val ROW_ID = "$PACKAGE:id/bn1"
    private const val AVATAR_ID = "$PACKAGE:id/bk1"
    private const val TITLE_CONTAINER_ID = "$PACKAGE:id/obq"
    private const val MAX_NODES = 5000
    private val GROUP_COUNT = Regex("[（(]\\d+[）)]$")

    data class Reading(
        val messages: List<Msg>,
        val firstMessageTop: Int,
        val structuredChat: Boolean = false,
        val title: String? = null
    )
    private class Row { var avatar: IntArray? = null }
    private data class Visit(
        val node: WeChatNode,
        val row: Row? = null,
        val inList: Boolean = false,
        val inTitle: Boolean = false
    )
    private data class Body(val text: String, val box: IntArray, val row: Row?)

    /** An empty message list preserves a verified page identity for manual OCR.
     * Never substitute a previous title or the first line of message text. */
    fun snapshot(read: Reading, title: String?): ChatSnapshot? {
        val name = title?.trim()?.takeIf { it.isNotEmpty() }
        if (name == null && !read.structuredChat) return null
        if (name != null && (name == "微信" || name == "WeChat" || GROUP_COUNT.containsMatchIn(name))) return null
        if (read.messages.any { it.side == "unknown" }) {
            return ChatSnapshot(name, emptyList(), partialMessages = read.messages)
        }
        return ChatSnapshot(name, read.messages)
    }

    fun read(root: WeChatNode, width: Int, height: Int): Reading? {
        if (width <= 0 || height <= 0) return null
        val stack = ArrayDeque<Visit>()
        stack.add(Visit(root))
        val bodies = ArrayList<Body>()
        var inputTop = Int.MAX_VALUE
        var knownInputTop = Int.MAX_VALUE
        var listBounds: IntArray? = null
        var title: String? = null
        var visited = 0
        while (stack.isNotEmpty()) {
            if (++visited > MAX_NODES) return null // incomplete tree is not a safe snapshot
            val visit = stack.removeLast()
            val n = visit.node
            val id = n.viewId
            val inList = visit.inList || id == LIST_ID
            val row = if (id == ROW_ID && visit.inList) Row() else visit.row
            val inTitle = visit.inTitle || id == TITLE_CONTAINER_ID
            for (i in n.childCount - 1 downTo 0) {
                n.child(i)?.let { stack.addLast(Visit(it, row, inList, inTitle)) }
            }
            // A container can have no usable bounds while its children still do.
            if (!n.visible) continue
            val b = n.bounds
            if (b.size != 4 || b[0] >= b[2] || b[1] >= b[3]) continue
            val cls = n.className.orEmpty()
            if (id == LIST_ID && cls.endsWith("RecyclerView")) listBounds = b
            if (cls.endsWith("EditText")) {
                if (id == INPUT_ID) knownInputTop = minOf(knownInputTop, b[1])
                if (n.editable && b[1] >= height * 0.60 && b[2] - b[0] >= width * 0.25) {
                    inputTop = minOf(inputTop, b[1])
                }
            }
            if (id == AVATAR_ID && cls.endsWith("ImageView")) row?.avatar = b
            if (id == BODY_ID || (inTitle && title == null && cls.endsWith("TextView"))) {
                val text = n.text?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                if (id == BODY_ID) bodies.add(Body(text, b, row)) else title = text
            }
        }
        val list = listBounds?.takeIf { knownInputTop != Int.MAX_VALUE && knownInputTop >= it[3] }
        val structured = list != null
        val bottom = list?.get(3) ?: inputTop
        if (bottom == Int.MAX_VALUE) return null
        val top = list?.get(1) ?: (height * 0.10).toInt()
        val ordered = bodies.filter { body ->
            val b = body.box
            if (list != null) {
                body.row != null && b[1] < bottom && b[3] > top && b[0] < list[2] && b[2] > list[0]
            } else b[1] >= top && b[3] <= bottom
        }
            .sortedWith(compareBy<Body> { it.box[1] }.thenBy { it.box[0] })
        val firstTop = ordered.firstOrNull()?.box?.get(1) ?: bottom
        val left = list?.get(0) ?: 0
        val span = (list?.get(2) ?: width) - left
        val messages = ArrayList<Msg>(ordered.size)
        for (body in ordered) {
            val b = body.box
            val avatar = body.row?.avatar?.takeIf { it[1] < b[3] && it[3] > b[1] }
            val mid = left + span / 2
            val side = when {
                avatar != null && avatar[0] >= maxOf(mid, b[2]) -> "me"
                avatar != null && avatar[2] <= minOf(mid, b[0]) -> "other"
                b[0] - left >= span * 0.38 && b[2] - left >= span * 0.70 -> "me"
                b[0] - left <= span * 0.30 && b[2] - left <= span * 0.62 -> "other"
                // A clipped row can lose its avatar. Keep its text for the manual
                // preview without guessing its role or dropping the other rows.
                structured -> "unknown"
                else -> return Reading(emptyList(), firstTop)
            }
            messages.add(Msg(side, body.text))
        }
        return Reading(messages, firstTop, structured, title)
    }
}
