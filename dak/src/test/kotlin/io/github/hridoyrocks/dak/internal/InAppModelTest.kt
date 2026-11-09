package io.github.hridoyrocks.dak.internal

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InAppModelTest {
    @Test
    fun readsAMessageAsTheServerSendsIt() {
        val js = JSONObject(
            """
            {"id":"01a1","layout":"card","priority":10,"shown":2,"last_shown_at":"2026-11-08T10:00:00.123456Z",
             "start_at":"2026-11-01T00:00:00Z","end_at":"2026-12-01T06:00:00+06:00","updated_at":"2026-11-01T00:00:00Z",
             "frequency":{"type":"interval","interval_seconds":86400,"max_displays":3},
             "triggers":[[{"kind":"session_count","op":">=","value":3},{"kind":"custom","key":"screen","op":"=","value":"home"}],
                         [{"kind":"custom","key":"plan","op":"in","values":["pro","team"]}]],
             "content":{"title":"নতুন চ্যালেঞ্জ","body":"আজকের বিষয়: ভ্রমণ","image_url":"https://cdn.example.com/a.png",
               "background_color":"#101820","title_color":"#80FFFFFF","close_button":false,"auto_dismiss_seconds":8,
               "click_action":{"type":"deep_link","url":"amarpartner://x"},
               "buttons":[{"id":"join","text":"শুরু","action":{"type":"tags","tags":{"joined":"yes","old":""}},"background_color":"#FFCC00"},
                          {"id":"later","text":"পরে","action":{"type":"dismiss"}}]}}
            """,
        )
        val m = InAppMessage.parse(js)!!
        assertEquals(InAppLayout.CARD, m.layout)
        assertEquals(10, m.priority)
        assertEquals(2, m.shown)
        assertEquals(parseTime("2026-11-08T10:00:00.123Z"), m.lastShownAt)
        assertEquals(parseTime("2026-12-01T00:00:00Z"), m.endAt)
        assertEquals("interval", m.frequency.type)
        assertEquals(86_400_000L, m.frequency.intervalMs)
        assertEquals(3, m.frequency.maxDisplays)
        assertEquals(2, m.triggers.size)
        assertEquals(listOf("pro", "team"), m.triggers[1][0].values)
        val c = m.content
        assertEquals("নতুন চ্যালেঞ্জ", c.title)
        assertEquals(0xFF101820.toInt(), c.backgroundColor)
        assertEquals(0x80FFFFFF.toInt(), c.titleColor)
        assertFalse(c.closeButton)
        assertEquals(8_000L, c.autoDismissMs)
        assertEquals("deep_link", c.clickAction!!.type)
        assertEquals(2, c.buttons.size)
        assertEquals(mapOf("joined" to "yes", "old" to ""), c.buttons[0].action.tags)
        assertEquals(0xFFFFCC00.toInt(), c.buttons[0].backgroundColor)
        assertNull(c.buttons[1].backgroundColor)
        assertTrue(c.hasText)
        assertFalse(c.withoutImage().hasImage)
    }

    @Test
    fun defaults() {
        val m = InAppMessage.parse(JSONObject().put("id", "x").put("layout", "banner_top").put("frequency", JSONObject().put("type", "once"))
            .put("content", JSONObject().put("body", "Hi").put("auto_dismiss_seconds", 0)))!!
        assertTrue(m.layout.isBanner)
        assertTrue(m.content.closeButton)
        assertNull(m.content.autoDismissMs)
        assertTrue(m.triggers.isEmpty())
        assertEquals(0, m.priority)
        assertNull(m.startAt)
    }

    @Test
    fun skipsWhatItCannotShowRight() {
        fun base() = JSONObject().put("id", "x").put("layout", "modal").put("frequency", JSONObject().put("type", "once"))
            .put("content", JSONObject().put("title", "T"))
        assertNotNull(InAppMessage.parse(base()))
        assertNull(InAppMessage.parse(base().put("layout", "sidebar")))
        assertNull(InAppMessage.parse(base().put("frequency", JSONObject().put("type", "hourly"))))
        assertNull(InAppMessage.parse(base().put("triggers", JSONArray().put(JSONArray()))))
        assertNull(InAppMessage.parse(base().put("triggers", JSONArray().put(JSONArray().put(JSONObject().put("kind", "session_time").put("op", "=").put("value", 3))))))
        assertNull(InAppMessage.parse(base().put("triggers", JSONArray().put(JSONArray().put(JSONObject().put("kind", "custom").put("op", "=").put("value", 3))))))
        assertNull(InAppMessage.parse(base().put("triggers", JSONArray().put(JSONArray().put(JSONObject().put("kind", "session_count").put("op", ">").put("value", "3"))))))
        assertNull(InAppMessage.parse(base().also { it.getJSONObject("content").put("click_action", JSONObject().put("type", "share")) }))
        assertNull(InAppMessage.parse(base().also { it.getJSONObject("content").put("buttons", JSONArray().put(JSONObject().put("id", "a").put("action", JSONObject().put("type", "dismiss")))) }))
        assertNull(InAppMessage.parse(base().also { it.getJSONObject("content").put("close_button", "no") }))
        assertNull(InAppMessage.parse(base().put("priority", "high")))
        assertNull(InAppMessage.parse("not json"))
        // An unknown colour is only left out.
        assertNull(InAppMessage.parse(base().also { it.getJSONObject("content").put("background_color", "red") })!!.content.backgroundColor)
    }

    @Test
    fun colours() {
        assertEquals(0xFF336699.toInt(), parseColor("#336699"))
        assertEquals(0x80336699.toInt(), parseColor("#80336699"))
        assertNull(parseColor("336699"))
        assertNull(parseColor("#3366"))
        assertNull(parseColor("#33669G"))
        assertNull(parseColor(0x336699))
    }

    @Test
    fun times() {
        val utc = parseTime("2026-11-09T10:15:30Z")!!
        assertEquals(utc, parseTime("2026-11-09T16:15:30+06:00"))
        assertEquals(utc, parseTime("2026-11-09T05:15:30-0500"))
        assertEquals(utc + 120, parseTime("2026-11-09T10:15:30.12Z"))
        assertEquals(utc + 999, parseTime("2026-11-09T10:15:30.999999999Z"))
        assertEquals("2026-11-09T10:15:30.000Z", formatTime(utc))
        assertNull(parseTime("2026-02-30T00:00:00Z"))
        assertNull(parseTime("yesterday"))
        assertNull(parseTime(null))
    }

    @Test
    fun readableColours() {
        assertEquals(android.graphics.Color.WHITE, readableOn(0xFF101820.toInt()))
        assertEquals(0xDE000000.toInt(), readableOn(0xFFFFCC00.toInt()))
        assertTrue(contrast(android.graphics.Color.BLACK, android.graphics.Color.WHITE) > 20)
        assertEquals(1.0, contrast(0x00FFFFFF, android.graphics.Color.BLACK), 0.01)
    }
}
