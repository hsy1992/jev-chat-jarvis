package com.dianming.phoneapp.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class WeChatRulesTest {
    private class Node(
        override val viewId: String? = null,
        override val text: String? = null,
        override val className: String? = null,
        override val editable: Boolean = false,
        override val visible: Boolean = true,
        override val bounds: IntArray = box(0, 0, 1200, 2600),
        private val children: List<Node> = emptyList()
    ) : WeChatNode {
        override val childCount: Int get() = children.size
        override fun child(index: Int): WeChatNode? = children.getOrNull(index)
    }

    @Test fun readsClearlyAlignedMessagesInScreenOrder() {
        val root = screen(listOf(
            body("我发的", box(680, 520, 1030, 620)),
            body("对方发的", box(160, 300, 510, 400))
        ))
        val reading = WeChatRules.read(root, 1200, 2600)!!
        assertEquals(listOf("other\t对方发的", "me\t我发的"),
            reading.messages.map { "${it.side}\t${it.text}" })
    }

    @Test fun classifiesRightAnchoredMediumBubble() {
        // Geometry follows an observed bkl node; its real sender was not recorded.
        // This verifies the rule, not its accuracy on a live chat.
        val reading = WeChatRules.read(screen(listOf(body("OCR_TEST_123",
            box(586, 2292, 1026, 2420))), inputTop = 2500), 1200, 2600)!!
        assertEquals("me", reading.messages.single().side)
    }

    @Test fun observedChatStructureKeepsTextWhenTitleContainerIsEmpty() {
        val reading = WeChatRules.read(observedChat(), 1200, 2670)!!
        val snapshot = WeChatRules.snapshot(reading, reading.title)!!
        assertNull(snapshot.title)
        assertEquals(listOf("me\tOCR_TEST_123"), snapshot.messages.map { "${it.side}\t${it.text}" })
    }

    @Test fun readOnlyPreviewRequiresObservedInputAndListStructure() {
        val reading = WeChatRules.read(screen(listOf(body("消息", box(160, 300, 510, 400)))), 1200, 2600)!!
        assertNull(WeChatRules.snapshot(reading, null))
    }

    @Test fun clippedUnknownRowDoesNotDiscardTheFollowingLongIncomingMessage() {
        val clipped = Node(viewId = "com.tencent.mm:id/bn1", bounds = box(0, 210, 1200, 690),
            children = listOf(body("头像滚出屏幕的长消息", box(174, 210, 1014, 672))))
        val picture = Node(viewId = "com.tencent.mm:id/bn1", bounds = box(0, 690, 1200, 1329),
            children = listOf(Node(viewId = "com.tencent.mm:id/bkg", bounds = box(189, 940, 789, 1311))))
        val incoming = Node(viewId = "com.tencent.mm:id/bn1", bounds = box(0, 1329, 1200, 2435), children = listOf(
            Node(viewId = "com.tencent.mm:id/bk1", className = "android.widget.ImageView",
                bounds = box(18, 1329, 174, 1485)),
            body("1000多点", box(174, 1407, 1014, 2417))
        ))
        val read = WeChatRules.read(observedChat(listOf(clipped, picture, incoming)), 1200, 2670)!!
        val snapshot = WeChatRules.snapshot(read, read.title)!!
        assertEquals(listOf("unknown\t头像滚出屏幕的长消息", "other\t1000多点"),
            snapshot.partialMessages.map { "${it.side}\t${it.text}" })
        assertTrue(snapshot.messages.isEmpty())
    }

    // Minimal, sanitized shape of the WeChat 8.0.80 UIAutomator sample.
    private fun observedChat(rows: List<Node>? = null): Node {
        val row = Node(viewId = "com.tencent.mm:id/bn1", bounds = box(0, 2105, 1200, 2435), children = listOf(
            Node(bounds = box(0, 0, 0, 0), children = listOf(body("OCR_TEST_123", box(586, 2292, 1026, 2420)))),
            Node(viewId = "com.tencent.mm:id/bk1", className = "android.widget.ImageView", bounds = box(1026, 2277, 1182, 2433))
        ))
        return Node(children = listOf(
            Node(viewId = "com.tencent.mm:id/obq", bounds = box(441, 172, 759, 239)),
            Node(viewId = "com.tencent.mm:id/bp0", className = "androidx.recyclerview.widget.RecyclerView",
                bounds = box(0, 278, 1200, 2453), children = rows ?: listOf(row)),
            Node(viewId = "com.tencent.mm:id/bkk", className = "android.widget.EditText",
                bounds = box(144, 2472, 936, 2604))
        ))
    }

    @Test fun preservesPageIdentityWhenMessagesAreAmbiguousOrUnavailable() {
        assertTrue(WeChatRules.read(screen(listOf(body("长消息", box(180, 300, 1020, 400)))), 1200, 2600)!!.messages.isEmpty())
        assertNull(WeChatRules.read(Node(children = listOf(body("消息", box(160, 300, 500, 400)))), 1200, 2600))
        assertTrue(WeChatRules.read(screen(emptyList()), 1200, 2600)!!.messages.isEmpty())
    }

    @Test fun rejectsTextOutsideConversation() {
        assertTrue(WeChatRules.read(screen(listOf(body("输入框里的字", box(700, 2390, 1000, 2450)))), 1200, 2600)!!.messages.isEmpty())
        assertTrue(WeChatRules.read(screen(listOf(body("标题里的字", box(200, 50, 500, 100)))), 1200, 2600)!!.messages.isEmpty())
    }

    @Test fun manualOcrSnapshotRequiresCurrentTitleAndDoesNotIncludePartialMessages() {
        val read = WeChatRules.read(screen(listOf(
            body("可判定", box(160, 300, 500, 400)),
            body("长消息", box(180, 500, 1020, 600))
        )), 1200, 2600)!!
        val snapshot = WeChatRules.snapshot(read, "测试联系人")!!
        assertEquals("测试联系人", snapshot.title)
        assertTrue(snapshot.messages.isEmpty())
        assertNull(WeChatRules.snapshot(read, null))
        assertNull(WeChatRules.snapshot(read, " "))
        assertNull(WeChatRules.snapshot(read, "微信"))
        assertNull(WeChatRules.snapshot(read, "WeChat"))
        assertNull(WeChatRules.snapshot(read, "测试群(3)"))
    }

    @Test fun emptyMessageSnapshotKeepsOcrSessionButDifferentChatInvalidatesIt() {
        val session = ConversationSession()
        val read = WeChatRules.read(screen(emptyList()), 1200, 2600)!!
        val snapshot = WeChatRules.snapshot(read, "联系人甲")!!
        val target = ConversationSession.Target(WeChatRules.PACKAGE, 7, snapshot.title)
        session.observe(target)
        val screenshotToken = session.token()!!
        // Repeated content-change checks must not cancel a manually requested OCR.
        assertFalse(session.observe(target))
        assertTrue(session.accepts(screenshotToken))
        session.observe(target.copy(title = "联系人乙"))
        assertFalse(session.accepts(screenshotToken))
        session.observe(null)
        assertNull(session.token())
    }

    @Test fun exposesObservedInputIdForGuardedFill() {
        assertEquals("com.tencent.mm:id/bkk", WeChatRules.INPUT_ID)
    }

    @Test fun structuredChatWithoutTitleMaintainsStableTargetIdentity() {
        val session = ConversationSession()
        val read = WeChatRules.read(observedChat(), 1200, 2670)!!
        val snapshot = WeChatRules.snapshot(read, null)!!
        assertNull(snapshot.title)
        val target = ConversationSession.Target(WeChatRules.PACKAGE, 99, snapshot.title)
        session.observe(target)
        val token = session.begin()!!
        assertTrue(session.accepts(token))
        assertEquals(target, session.target)
    }

    private fun screen(messages: List<Node>, inputTop: Int = 2300) = Node(children = messages + Node(
        className = "android.widget.EditText", editable = true,
        bounds = box(200, inputTop, 1100, inputTop + 100)))

    private fun body(text: String, bounds: IntArray) = Node(
        viewId = "com.tencent.mm:id/bkl", text = text, bounds = bounds)

    private companion object {
        fun box(left: Int, top: Int, right: Int, bottom: Int) = intArrayOf(left, top, right, bottom)
    }
}
