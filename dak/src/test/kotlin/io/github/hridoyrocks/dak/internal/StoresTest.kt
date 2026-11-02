package io.github.hridoyrocks.dak.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class StoresTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_700_000_000_000L
    private fun files() = DakFiles(File(folder.root, "dak_push"), clock = { now })

    @Test
    fun stateSurvivesAndAMissingFileIsNull() {
        val store = files().state
        assertNull(store.read())
        store.write("""{"device_id":"d1"}""")
        assertEquals("""{"device_id":"d1"}""", files().state.read())
    }

    @Test
    fun pendingReceiptsAndTaps() {
        val pending = files().pending
        pending.addReceipt("m1")
        pending.addReceipt("m1")
        pending.addReceipt("m2")
        pending.addOpen("m1", "accept")
        pending.addOpen("m1", null)
        val kept = files().pending.read()
        assertEquals(listOf("m1", "m2"), kept.receipts)
        assertEquals(listOf("m1" to "accept"), kept.opens.map { it.id to it.action })
        pending.remove(listOf("m1"), listOf("m1"))
        assertEquals(listOf("m2"), pending.read().receipts)
        assertTrue(pending.read().opens.isEmpty())
    }

    @Test
    fun pendingIsBounded() {
        val pending = files().pending
        repeat(FilePendingStore.MAX_RECEIPTS + 10) { pending.addReceipt("m$it") }
        val receipts = pending.read().receipts
        assertEquals(FilePendingStore.MAX_RECEIPTS, receipts.size)
        assertEquals("m10", receipts.first())
    }

    @Test
    fun aNonceWorksOnce() {
        val taps = files().taps
        val nonce = taps.issueNonce()
        assertEquals(32, nonce.length)
        assertFalse(taps.redeemNonce("made-up"))
        assertFalse(taps.redeemNonce(null))
        assertTrue(files().taps.redeemNonce(nonce))
        assertFalse(taps.redeemNonce(nonce))
    }

    @Test
    fun aFirebaseMessageIdWorksOnceWithWhatWasKept() {
        val taps = files().taps
        taps.recordReceived("0:123", ReceivedPush("Title", "Body", null))
        assertNull(taps.redeemReceived("0:999"))
        val kept = taps.redeemReceived("0:123")!!
        assertEquals("Title", kept.title)
        assertEquals("Body", kept.body)
        assertNull(kept.image)
        assertNull(taps.redeemReceived("0:123"))
    }

    @Test
    fun oldAndExtraNoncesMakeRoom() {
        val taps = files().taps
        val old = taps.issueNonce()
        now += DakTapStore.MAX_AGE_MS + 1
        val fresh = taps.issueNonce()
        assertFalse(taps.redeemNonce(old))
        assertTrue(taps.redeemNonce(fresh))

        val first = taps.issueNonce()
        repeat(DakTapStore.MAX) {
            now++
            taps.issueNonce()
        }
        assertFalse(taps.redeemNonce(first))
    }

    @Test
    fun utf8LengthsAreBytes() {
        assertEquals(3, utf8Length("ক"))
        assertEquals("কখ", clampUtf8("কখগ", 7))
        assertEquals("ab", clampUtf8("ab😀", 5))
        assertEquals("ab😀", clampUtf8("ab😀", 6))
    }

    @Test
    fun languageCodesAsTheServerWantsThem() {
        assertEquals("bn", AndroidPlatformInfo.languageOf(Locale.forLanguageTag("bn-BD")))
        assertEquals("en", AndroidPlatformInfo.languageOf(Locale.US))
        assertEquals("id", AndroidPlatformInfo.languageOf(Locale.forLanguageTag("id-ID")))
        assertEquals("", AndroidPlatformInfo.languageOf(Locale.ROOT))
    }
}
