package io.github.hridoyrocks.dak.internal

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.SecureRandom

/** One string kept between app starts. */
internal interface DakStateStore {
    /** The stored string, or null when nothing is stored. Throws when it cannot be read. */
    fun read(): String?

    fun write(value: String)
}

/**
 * The SDK's files, in the app's no-backup folder: kept out of Auto Backup (a
 * restored copy would make two phones one device) and deleted with the app.
 * One lock for all of them: the worker, the FCM receiver and the tap activity
 * use them from different threads.
 */
internal class DakFiles(dir: File, clock: () -> Long = System::currentTimeMillis) {
    val state: DakStateStore = FileStateStore(File(dir, "state.json"))
    val pending: DakPendingStore = FilePendingStore(File(dir, "pending.json"))
    val taps = DakTapStore(FileStateStore(File(dir, "taps.json")), clock)

    /** In-app messages: the cached list, what this device showed, events not yet sent. */
    val inApp: DakStateStore = FileStateStore(File(dir, "in_app.json"))

    companion object {
        val lock = Any()

        fun of(context: Context): DakFiles = DakFiles(File(context.noBackupFilesDir, "dak_push"))
    }
}

/** A JSON file written atomically (a temporary file, then a rename). */
internal class FileStateStore(file: File) : DakStateStore {
    private val atomic = AtomicFile(file)
    private val dir = file.parentFile

    override fun read(): String? = synchronized(DakFiles.lock) {
        try {
            String(atomic.readFully(), Charsets.UTF_8)
        } catch (e: FileNotFoundException) {
            null
        }
    }

    override fun write(value: String): Unit = synchronized(DakFiles.lock) {
        if (dir != null && !dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val out = atomic.startWrite()
        try {
            out.write(value.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: IOException) {
            atomic.failWrite(out)
            throw e
        }
    }
}

/** Keeps the state in memory only (tests). */
internal class MemoryStateStore(var value: String? = null) : DakStateStore {
    var failReads = false

    override fun read(): String? {
        if (failReads) throw IOException("not readable")
        return value
    }

    override fun write(value: String) {
        this.value = value
    }
}

internal class PendingOpen(val id: String, val action: String?)

/** Opens and receipts recorded outside the client, kept until it takes them. */
internal class DakPending(val receipts: List<String>, val opens: List<PendingOpen>) {
    val isEmpty: Boolean get() = receipts.isEmpty() && opens.isEmpty()
}

/**
 * Receipts from the FCM receiver and taps from notifications, written before
 * anything else happens with them, so a process that dies right after (a push
 * in the background) loses none. The client takes them over on start, on
 * resume, and as they come.
 */
internal interface DakPendingStore {
    fun addReceipt(messageId: String)

    fun addOpen(messageId: String, actionId: String?)

    fun read(): DakPending

    /** Forgets what the client has saved in its own state. */
    fun remove(receipts: Collection<String>, opens: Collection<String>)
}

internal class FilePendingStore(file: File) : DakPendingStore {
    private val store = FileStateStore(file)

    override fun addReceipt(messageId: String) = update { receipts, _ ->
        if (messageId !in receipts) receipts.add(messageId)
        while (receipts.size > MAX_RECEIPTS) receipts.removeAt(0)
    }

    override fun addOpen(messageId: String, actionId: String?) = update { _, opens ->
        if (opens.none { it.id == messageId }) opens.add(PendingOpen(messageId, actionId))
        while (opens.size > MAX_OPENS) opens.removeAt(0)
    }

    override fun read(): DakPending = synchronized(DakFiles.lock) { decode(readQuietly()) }

    override fun remove(receipts: Collection<String>, opens: Collection<String>) {
        if (receipts.isEmpty() && opens.isEmpty()) return
        update { r, o ->
            r.removeAll(receipts.toSet())
            o.removeAll { it.id in opens }
        }
    }

    private fun update(change: (MutableList<String>, MutableList<PendingOpen>) -> Unit) = synchronized(DakFiles.lock) {
        val now = decode(readQuietly())
        val receipts = now.receipts.toMutableList()
        val opens = now.opens.toMutableList()
        change(receipts, opens)
        store.write(encode(receipts, opens))
    }

    private fun readQuietly(): String? = try {
        store.read()
    } catch (e: IOException) {
        null
    }

    private fun decode(raw: String?): DakPending {
        if (raw.isNullOrEmpty()) return DakPending(emptyList(), emptyList())
        return try {
            val js = JSONObject(raw)
            val receipts = js.optJSONArray("receipts").strings()
            val opens = ArrayList<PendingOpen>()
            val list = js.optJSONArray("opens") ?: JSONArray()
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isNotEmpty()) opens.add(PendingOpen(id, o.optStringOrNull("action")))
            }
            DakPending(receipts, opens)
        } catch (e: Exception) {
            DakPending(emptyList(), emptyList())
        }
    }

    private fun encode(receipts: List<String>, opens: List<PendingOpen>): String = JSONObject()
        .put("receipts", JSONArray(receipts))
        .put("opens", JSONArray(opens.map { o -> JSONObject().put("id", o.id).also { if (o.action != null) it.put("action", o.action) } }))
        .toString()

    companion object {
        const val MAX_RECEIPTS = 200
        const val MAX_OPENS = 50
    }
}

/** [DakPendingStore] in memory (tests). */
internal class MemoryPendingStore : DakPendingStore {
    val receipts = mutableListOf<String>()
    val opens = mutableListOf<PendingOpen>()

    @Synchronized override fun addReceipt(messageId: String) {
        if (messageId !in receipts) receipts.add(messageId)
    }

    @Synchronized override fun addOpen(messageId: String, actionId: String?) {
        if (opens.none { it.id == messageId }) opens.add(PendingOpen(messageId, actionId))
    }

    @Synchronized override fun read(): DakPending = DakPending(receipts.toList(), opens.toList())

    @Synchronized override fun remove(receipts: Collection<String>, opens: Collection<String>) {
        this.receipts.removeAll(receipts.toSet())
        this.opens.removeAll { it.id in opens }
    }
}

/** What the receiver kept about a push that Firebase shows itself. */
internal class ReceivedPush(val title: String?, val body: String?, val image: String?)

/**
 * Proof that a tap came from a notification of ours.
 *
 * - Notifications the SDK shows carry a random nonce, issued here before the
 *   notification is posted. Their tap activity is not exported, and a nonce
 *   works once, so a tap the system replays is not a second open.
 * - Notifications Firebase shows open the app's launcher activity, which any
 *   app can start with made-up extras. Only a Firebase message id the receiver
 *   saw counts, once.
 */
internal class DakTapStore(private val store: DakStateStore, private val clock: () -> Long) {
    private val random = SecureRandom()

    /** A new nonce for a notification about to be posted. Call it off the main thread: it writes to disk. */
    fun issueNonce(): String {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        val nonce = bytes.joinToString("") { "%02x".format(it) }
        update { nonces, _ -> nonces.put(nonce, JSONObject().put("at", clock())) }
        return nonce
    }

    /** True once for a nonce [issueNonce] gave; it is used up. */
    fun redeemNonce(nonce: String?): Boolean {
        if (nonce.isNullOrEmpty()) return false
        var found = false
        update { nonces, _ -> found = nonces.remove(nonce) != null }
        return found
    }

    fun recordReceived(firebaseId: String, push: ReceivedPush) = update { _, received ->
        val entry = JSONObject().put("at", clock())
        push.title?.let { entry.put("t", it) }
        push.body?.let { entry.put("b", it) }
        push.image?.let { entry.put("i", it) }
        received.put(firebaseId, entry)
    }

    /** What was kept about [firebaseId], once; null for an id the receiver never saw. */
    fun redeemReceived(firebaseId: String?): ReceivedPush? {
        if (firebaseId.isNullOrEmpty()) return null
        var out: ReceivedPush? = null
        update { _, received ->
            val entry = received.remove(firebaseId) as? JSONObject ?: return@update
            out = ReceivedPush(entry.optStringOrNull("t"), entry.optStringOrNull("b"), entry.optStringOrNull("i"))
        }
        return out
    }

    private fun update(change: (nonces: JSONObject, received: JSONObject) -> Unit) = synchronized(DakFiles.lock) {
        val raw = try {
            store.read()
        } catch (e: IOException) {
            null
        }
        val js = try {
            if (raw.isNullOrEmpty()) JSONObject() else JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
        val nonces = js.optJSONObject("nonces") ?: JSONObject()
        val received = js.optJSONObject("received") ?: JSONObject()
        change(nonces, received)
        prune(nonces)
        prune(received)
        store.write(JSONObject().put("nonces", nonces).put("received", received).toString())
    }

    /** Old entries (notifications long gone or swiped away) make room. */
    private fun prune(entries: JSONObject) {
        val now = clock()
        val kept = ArrayList<Pair<String, Long>>()
        for (key in entries.keys().asSequence().toList()) {
            val at = entries.optJSONObject(key)?.optLong("at", -1) ?: -1
            if (at < 0 || at > now || now - at > MAX_AGE_MS) entries.remove(key) else kept.add(key to at)
        }
        if (kept.size > MAX) kept.sortedBy { it.second }.take(kept.size - MAX).forEach { entries.remove(it.first) }
    }

    companion object {
        const val MAX = 200
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    }
}

internal fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> (opt(i) as? String)?.takeIf { it.isNotEmpty() } }
}

internal fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else (opt(key) as? String)
