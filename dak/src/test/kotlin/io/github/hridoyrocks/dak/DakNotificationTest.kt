package io.github.hridoyrocks.dak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DakNotificationTest {
    private val buttons = """[{"id":"accept","text":"Accept","url":"app://accept"},{"id":"later","text":"Later"}]"""

    @Test
    fun readsDaksKeysAndKeepsTheAppsData() {
        val n = DakNotification.fromData(
            mapOf(
                "_dak_id" to "m1",
                "_dak_url" to "app://inbox",
                "_dak_buttons" to buttons,
                "_dak_title" to "Hello",
                "_dak_body" to "From Dak",
                "_dak_image" to "https://cdn.example.com/a.png",
                "type" to "friend_request",
            ),
        )
        assertEquals("m1", n.id)
        assertEquals("app://inbox", n.url)
        assertEquals("Hello", n.title)
        assertEquals("From Dak", n.body)
        assertEquals("https://cdn.example.com/a.png", n.imageUrl)
        assertEquals(mapOf("type" to "friend_request"), n.data)
        assertEquals(listOf("accept", "later"), n.buttons.map { it.id })
        assertEquals("app://accept", n.buttons[0].url)
        assertNull(n.buttons[1].url)
        assertNull(n.actionId)
        assertFalse(n.isSilent)
        assertEquals("m1", n.raw["_dak_id"])
    }

    @Test
    fun aButtonsOwnUrlWins() {
        val data = mapOf("_dak_id" to "m1", "_dak_url" to "app://inbox", "_dak_buttons" to buttons)
        assertEquals("app://accept", DakNotification.fromData(data, actionId = "accept").url)
        assertEquals("app://inbox", DakNotification.fromData(data, actionId = "later").url)
        assertEquals("accept", DakNotification.fromData(data).withAction("accept").actionId)
    }

    @Test
    fun firebasesNotificationFieldsWinOverTheData() {
        val n = DakNotification.fromData(mapOf("_dak_title" to "data"), title = "notification", body = "text")
        assertEquals("notification", n.title)
        assertEquals("text", n.body)
    }

    @Test
    fun silentAndForeignPushes() {
        val silent = DakNotification.fromData(mapOf("_dak_id" to "m1", "sync" to "1"))
        assertTrue(silent.isSilent)
        val foreign = DakNotification.fromData(mapOf("from_another_sdk" to "1"), title = "Hi")
        assertNull(foreign.id)
        assertTrue(foreign.buttons.isEmpty())
    }

    @Test
    fun badButtonsAreSkipped() {
        assertTrue(DakNotification.parseButtons("not json").isEmpty())
        assertTrue(DakNotification.parseButtons(null).isEmpty())
        val parsed = DakNotification.parseButtons("""[{"text":"no id"},{"id":"ok","text":"OK","url":null},7]""")
        assertEquals(listOf("ok"), parsed.map { it.id })
        assertNull(parsed[0].url)
    }
}
