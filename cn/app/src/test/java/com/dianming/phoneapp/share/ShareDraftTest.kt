package com.dianming.phoneapp.share

import com.dianming.phoneapp.core.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareDraftTest {
    @Test fun unlabelledOcrLineCannotBecomeAnIncomingMessage() {
        assertNull(ShareDraft.messages("我：早上好\n早上好\n对方：收到"))
        assertNull(ShareDraft.messages("对方：   "))
    }

    @Test fun confirmedLabelsKeepOrderAndMessagePunctuation() {
        assertEquals(
            listOf(Msg("me", "几点见？"), Msg("other", "12:30，老地方。")),
            ShareDraft.messages("我: 几点见？\n\n对方：12:30，老地方。")
        )
    }

    @Test fun correctingRoleReplacesLabelWithoutChangingMessage() {
        assertEquals("对方：我：是的", ShareDraft.mark("我：我：是的", "对方"))
    }

}
