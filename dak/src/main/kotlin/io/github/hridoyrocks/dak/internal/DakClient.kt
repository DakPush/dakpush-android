package io.github.hridoyrocks.dak.internal

import io.github.hridoyrocks.dak.DakCallback
import io.github.hridoyrocks.dak.DakException
import org.json.JSONArray
import org.json.JSONObject
import java.util.Random
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.min

/** Where the client reports to the app (the facade passes them on, on the main thread). */
internal interface DakEvents {
    /** The device has an id: the saved one once read, or a new registration. */
    fun deviceId(id: String)

    /** The server refused a change. */
    fun error(error: DakException)
}

/**
 * The SDK's logic: it keeps the state the app wants (token, user, tags,
 * permission) and the state the server last accepted, and sends the
 * difference whenever they differ. Everything is saved, so changes made
 * offline are sent on a later start.
 *
 * All state lives on [scheduler]'s one thread. Calls from other threads post
 * a task there; a change made while a sync runs is sent by the next round.
 */
internal class DakClient(
    private val api: DakApi,
    private val store: DakStateStore,
    private val pending: DakPendingStore,
    private val push: DakPushSource,
    private val platform: DakPlatformInfo,
    private val scheduler: DakScheduler,
    /** Where [DakCallback]s run: the main thread in the app. */
    private val callbacks: Executor,
    private val events: DakEvents,
    private val log: DakLog,
    private val sdkVersion: String,
    /** Send the phone's UTC offset, for local-time delivery and quiet hours. */
    private val shareTimezoneOffset: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random(),
    /** Runs the push token fetch, which blocks for up to ~30 s, off the worker. */
    private val background: (Runnable) -> Unit = { Thread(it, "dak-token").start() },
    /** Waits between retries of a failed sync; the last one repeats. Empty: no retries (tests). */
    private val retryDelaysMs: List<Long> = listOf(5_000, 15_000, 60_000, 300_000),
    private val lastSeenIntervalMs: Long = 12 * HOUR_MS,
) {
    // Saved state.
    private var deviceId: String? = null
    private var secret: String? = null
    private var token: String? = null
    private var tokenType = DakPushToken.TYPE_TOKEN
    private var externalId: String? = null
    private var authHash: String? = null
    private var tags: Map<String, String> = emptyMap()
    private var subscribed = true
    private var reported: Map<String, Any?> = emptyMap()
    private var lastSeenSent: Long? = null
    private var opens: List<PendingOpen> = emptyList()
    private var receipts: List<String> = emptyList()

    // Read at start.
    private var permission = false
    private var appVersion = ""
    private var osVersion = ""
    private var language = ""
    private var offset: Int? = null

    private var started = false
    private var loaded = false
    private var loadFailures = 0
    private var disposed = false

    /** What came in before the saved state was read: applied after, so the saved state cannot overwrite it. */
    private val waiting = ArrayDeque<() -> Unit>()
    private var syncQueued = false
    private val syncCallbacks = ArrayList<DakCallback>()
    private var again = false
    private var reRegistered = false
    private var failures = 0
    private var notBefore: Long? = null
    private var retryTimer: Cancelable? = null
    private var loadTimer: Cancelable? = null
    private var tokenEvents = 0

    /** The device id, readable from any thread; null before the device registers. */
    @Volatile
    var currentDeviceId: String? = null
        private set

    /** The device's id and secret, for the SDK's other parts (in-app messages). */
    @Volatile
    var credentials: Pair<String, String>? = null
        private set

    /**
     * Reads the saved state and returns; the push token and the first sync
     * follow in the background, so a slow network never holds the app's start.
     */
    fun start() = scheduler.execute {
        if (started || disposed) return@execute
        started = true
        readPlatform()
        load()
        whenLoaded { connect() }
    }

    fun login(externalId: String, authHash: String?, callback: DakCallback?) = change(callback) {
        this.externalId = externalId
        this.authHash = authHash
    }

    /**
     * Unlinks the user and removes the device's tags, so the next user of this
     * phone starts without them. Push permission and the subscription stay.
     */
    fun logout(callback: DakCallback?) = change(callback) {
        externalId = null
        authHash = null
        tags = emptyMap()
    }

    /** [changes] are checked by the caller; a null or empty value removes the tag. */
    fun setTags(changes: Map<String, String?>, callback: DakCallback?) = scheduler.execute {
        whenLoaded {
            val next = LinkedHashMap(tags)
            changes.forEach { (k, v) -> if (v.isNullOrEmpty()) next.remove(k) else next[k] = v }
            if (next.size > MAX_TAGS) {
                val error = DakException("a device can have at most $MAX_TAGS tags", "invalid", "tags")
                callback?.let { c -> callbacks.execute { c.onComplete(error) } }
                return@whenLoaded
            }
            tags = next
            save()
            requestSync(callback)
        }
    }

    /** Opts this device out of (false) or back into (true) all pushes. */
    fun setSubscribed(subscribed: Boolean, callback: DakCallback?) = change(callback) {
        this.subscribed = subscribed
    }

    /** Notification permission may have changed (a prompt, system settings). */
    fun refreshPermission() = change(null) {
        permission = orElse(permission, "permission") { platform.notificationsEnabled() }
    }

    /** A new FCM registration token (FirebaseMessagingService.onNewToken). */
    fun onNewToken(value: String) = scheduler.execute {
        whenLoaded {
            // With an installation ID the token is not what pushes go to.
            if (tokenType == DakPushToken.TYPE_FID) return@whenLoaded
            tokenEvents++
            setToken(DakPushToken(value))
        }
    }

    /** A new Firebase Installation ID (FirebaseMessagingService.onRegistered). */
    fun onRegistered(fid: String) = scheduler.execute {
        whenLoaded {
            tokenEvents++
            setToken(DakPushToken(fid, DakPushToken.TYPE_FID))
        }
    }

    /**
     * The app came to the foreground: picks up permission changes made in
     * system settings, receipts and taps kept meanwhile, a new UTC offset, and
     * keeps last_seen fresh.
     */
    fun onResume() = scheduler.execute {
        if (!started || disposed) return@execute
        whenLoaded {
            permission = orElse(permission, "permission") { platform.notificationsEnabled() }
            if (shareTimezoneOffset) offset = orElse(offset, "UTC offset") { platform.utcOffsetMinutes() }
            collectPending()
            requestSync(null)
        }
    }

    /** Takes the receipts and taps that others wrote to the pending store, and sends them. */
    fun takePending() = scheduler.execute {
        if (!started || disposed) return@execute
        whenLoaded {
            collectPending()
            requestSync(null)
        }
    }

    /** Sends anything pending now. */
    fun flush(callback: DakCallback? = null) = scheduler.execute {
        whenLoaded { requestSync(callback) }
    }

    fun dispose() = scheduler.execute {
        disposed = true
        retryTimer?.cancel()
        loadTimer?.cancel()
    }

    private fun change(callback: DakCallback?, apply: () -> Unit) = scheduler.execute {
        whenLoaded {
            apply()
            save()
            requestSync(callback)
        }
    }

    private fun setToken(t: DakPushToken) {
        token = t.value
        tokenType = t.type
        save()
        requestSync(null)
    }

    /** The network half of [start]. */
    private fun connect() {
        collectPending()
        save()
        val seen = tokenEvents
        background(Runnable {
            val t = try {
                push.getPushToken()
            } catch (e: Exception) {
                log.d("dak: could not get a push token yet: $e")
                null
            }
            scheduler.execute {
                // A refresh that came meanwhile is newer.
                if (t != null && tokenEvents == seen) {
                    token = t.value
                    tokenType = t.type
                    log.d("dak: push target is a ${t.type}")
                }
                save()
                requestSync(null)
            }
        })
    }

    /** Each detail on its own, so one failure costs only that one. Versions are cut to the server's 32 bytes. */
    private fun readPlatform() {
        appVersion = clampUtf8(orElse("", "app version") { platform.appVersion() }, 32)
        osVersion = clampUtf8(orElse("", "OS version") { platform.osVersion() }, 32)
        language = orElse("", "language") { platform.language() }
        offset = if (shareTimezoneOffset) orElse(null, "UTC offset") { platform.utcOffsetMinutes() } else null
        permission = orElse(false, "permission") { platform.notificationsEnabled() }
    }

    private fun <T> orElse(fallback: T, what: String, read: () -> T): T = try {
        read()
    } catch (e: Exception) {
        log.d("dak: could not read the $what: $e")
        fallback
    }

    private fun whenLoaded(task: () -> Unit) {
        if (loaded) task() else waiting.addLast(task)
    }

    /**
     * Adds what the receiver and the tap activity kept, saves, and only then
     * deletes it there, so a crash in between loses nothing.
     */
    private fun collectPending() {
        val kept = try {
            pending.read()
        } catch (e: Exception) {
            log.d("dak: could not read the kept receipts and taps: $e")
            return
        }
        if (kept.isEmpty) return
        for (id in kept.receipts) if (id !in receipts) receipts = receipts + id
        if (receipts.size > MAX_RECEIPTS) receipts = receipts.takeLast(MAX_RECEIPTS)
        for (o in kept.opens) if (opens.none { it.id == o.id }) opens = opens + o
        if (opens.size > MAX_OPENS) opens = opens.takeLast(MAX_OPENS)
        if (save()) {
            try {
                pending.remove(kept.receipts, kept.opens.map { it.id })
            } catch (e: Exception) {
                log.d("dak: could not clear the kept receipts and taps: $e")
            }
        }
    }

    // ---- sync

    private fun desired(): Map<String, Any?> = linkedMapOf(
        "token" to token,
        "token_type" to tokenType,
        "platform" to "android",
        "app_version" to appVersion,
        "os_version" to osVersion,
        "sdk_version" to sdkVersion,
        "language" to language,
        "push_permission" to permission,
        "subscribed" to subscribed,
        "external_id" to externalId,
        "utc_offset_minutes" to offset,
        "tags" to LinkedHashMap(tags),
    )

    /**
     * Runs a sync after the tasks already queued, so changes made together go
     * in one round; every caller hears the result of the round that includes
     * its change.
     */
    private fun requestSync(callback: DakCallback?) {
        if (callback != null) syncCallbacks.add(callback)
        if (syncQueued) return
        syncQueued = true
        scheduler.execute {
            syncQueued = false
            val waitingCallbacks = ArrayList(syncCallbacks)
            syncCallbacks.clear()
            val refused = runSync()
            for (c in waitingCallbacks) callbacks.execute { c.onComplete(refused) }
        }
    }

    /** One round. While the server's Retry-After lasts, changes are only saved. */
    private fun runSync(): DakException? {
        if (disposed || !loaded) return null
        val wait = notBefore
        if (wait != null && clock() < wait) {
            if (retryTimer == null) retryAt(wait - clock())
            return null
        }
        notBefore = null
        var refused: DakException? = null
        val reverted = HashSet<String>()
        reRegistered = false
        again = true
        while (again && !disposed) {
            again = false
            try {
                syncOnce()
                sendOpens()
                sendReceipts()
                failures = 0
                retryTimer?.cancel()
                retryTimer = null
            } catch (e: DakApiException) {
                if (!e.isPermanent) {
                    log.d("dak: sync failed, will retry: $e")
                    scheduleRetry(e.retryAfterMs)
                    break
                }
                val error = DakException(e.message ?: e.code, e.code, e.field, e.status)
                if (refused == null) refused = error
                log.w("dak: the server refused a change: $error")
                events.error(error)
                // The server refused something the app set (a wrong auth hash,
                // a bad tag): go back to what it last accepted for that field
                // alone, so the rest (an opt-out above all) still gets through.
                val field = revertible(e) ?: break
                if (!reverted.add(field)) break
                revert(field)
                save()
                again = true
            } catch (e: Exception) {
                log.d("dak: sync failed, will retry: $e")
                scheduleRetry(null)
                break
            }
        }
        return refused
    }

    private fun revert(field: String) {
        when (field) {
            "external_id" -> {
                externalId = reported["external_id"] as? String
                if (externalId == null) authHash = null
            }
            "tags" -> tags = (reported["tags"] as? Map<*, *>)
                ?.entries?.mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }?.toMap()
                ?: emptyMap()
        }
    }

    private fun scheduleRetry(retryAfterMs: Long?) {
        if (disposed || retryDelaysMs.isEmpty()) return
        val delay = retryDelay(failures, retryAfterMs)
        failures++
        if (retryAfterMs != null) notBefore = clock() + delay
        retryAt(delay)
    }

    private fun retryAt(delayMs: Long) {
        retryTimer?.cancel()
        if (disposed) return
        retryTimer = scheduler.schedule(delayMs) {
            retryTimer = null
            notBefore = null
            requestSync(null)
        }
    }

    /**
     * The wait before retry number [failures] + 1: the step from
     * [retryDelaysMs], of which a random half, so phones that failed together
     * do not come back together; never shorter than the server's Retry-After
     * (up to an hour).
     */
    fun retryDelay(failures: Int, retryAfterMs: Long?): Long {
        val step = retryDelaysMs[min(failures, retryDelaysMs.size - 1)]
        val jittered = (step * (0.5 + 0.5 * random.nextDouble())).toLong()
        if (retryAfterMs == null) return jittered
        return max(min(retryAfterMs, MAX_RETRY_AFTER_MS), jittered)
    }

    private fun syncOnce() {
        val desired = desired()
        val token = token ?: return // nothing to register until FCM gives a token
        val id = deviceId
        val sec = secret
        if (id == null || sec == null) {
            // The whole state, nulls included: the server may hand over an old
            // row for this token, whose user and offset must not stay.
            val body = toJson(desired)
            if (externalId != null && authHash != null) body.put("auth_hash", authHash)
            val reg = api.register(body)
            deviceId = reg.deviceId
            secret = reg.deviceSecret
            reported = desired
            lastSeenSent = clock()
            save()
            publishDeviceId()
            log.d("dak: registered as ${reg.deviceId}")
            return
        }

        val patch = JSONObject()
        // A token always goes with its type, which the server would otherwise
        // reset to "token".
        if (token != reported["token"] || tokenType != (reported["token_type"] ?: DakPushToken.TYPE_TOKEN)) {
            patch.put("token", token)
            patch.put("token_type", tokenType)
        }
        for (f in PLAIN_FIELDS) if (desired[f] != reported[f]) patch.put(f, desired[f] ?: JSONObject.NULL)
        if (externalId != reported["external_id"]) {
            patch.put("external_id", externalId ?: JSONObject.NULL)
            if (externalId != null && authHash != null) patch.put("auth_hash", authHash)
        }
        val had = (reported["tags"] as? Map<*, *>) ?: emptyMap<String, Any?>()
        val tagChanges = JSONObject()
        tags.forEach { (k, v) -> if (had[k] != v) tagChanges.put(k, v) }
        for (k in had.keys) if (k is String && k !in tags) tagChanges.put(k, JSONObject.NULL)
        if (tagChanges.length() > 0) patch.put("tags", tagChanges)

        val stale = lastSeenSent.let { it == null || clock() - it >= lastSeenIntervalMs }
        if (patch.length() == 0 && !stale) return
        try {
            api.update(id, sec, patch)
        } catch (e: DakApiException) {
            if (!e.isUnauthorized || reRegistered) throw e
            // The server no longer knows this device (deleted after a dead
            // token or a long absence): register again with the full state.
            forgetDevice()
            return
        }
        reported = desired
        lastSeenSent = clock()
        save()
    }

    /** Drops the id and secret, so the next round registers again. Once per sync, so a server that keeps refusing cannot loop it. */
    private fun forgetDevice() {
        log.d("dak: the server does not know this device any more; registering again")
        deviceId = null
        secret = null
        currentDeviceId = null
        credentials = null
        reported = emptyMap()
        reRegistered = true
        save()
        again = true
    }

    /** A 401 means the device must register again; the open is kept for the new registration. */
    private fun sendOpens() {
        while (opens.isNotEmpty()) {
            val id = deviceId ?: return
            val sec = secret ?: return
            val o = opens.first()
            try {
                api.opened(id, sec, o.id, o.action)
            } catch (e: DakApiException) {
                if (e.isUnauthorized && !reRegistered) return forgetDevice()
                if (!e.isPermanent && !e.isUnauthorized) throw e
            }
            opens = opens.drop(1)
            save()
        }
    }

    private fun sendReceipts() {
        while (receipts.isNotEmpty()) {
            val id = deviceId ?: return
            val sec = secret ?: return
            val batch = receipts.take(RECEIPT_BATCH)
            try {
                api.delivered(id, sec, batch)
            } catch (e: DakApiException) {
                if (e.isUnauthorized && !reRegistered) return forgetDevice()
                if (!e.isPermanent && !e.isUnauthorized) throw e
            }
            receipts = receipts.drop(batch.size)
            save()
        }
    }

    private fun publishDeviceId() {
        val id = deviceId ?: return
        currentDeviceId = id
        credentials = secret?.let { id to it }
        events.deviceId(id)
    }

    // ---- persistence

    /**
     * Reads the saved state. A read that fails is tried again later, and
     * nothing is saved or sent until one works, so the stored state is never
     * replaced by an empty one; after a few tries the SDK starts over.
     */
    private fun load() {
        if (loaded || disposed) return
        val raw = try {
            store.read()
        } catch (e: Exception) {
            loadFailures++
            if (loadFailures >= LOAD_TRIES || retryDelaysMs.isEmpty()) {
                log.w("dak: the saved state cannot be read; starting over", e)
                loadedNow()
            } else {
                log.d("dak: saved state not readable yet, will try again: $e")
                loadTimer = scheduler.schedule(retryDelaysMs[min(loadFailures - 1, retryDelaysMs.size - 1)]) { load() }
            }
            return
        }
        restore(raw)
        loadedNow()
    }

    private fun loadedNow() {
        loaded = true
        if (deviceId != null) publishDeviceId() // the saved one, for listeners from before the load
        while (waiting.isNotEmpty()) waiting.removeFirst().invoke()
    }

    private fun restore(raw: String?) {
        if (raw.isNullOrEmpty()) return
        val js = try {
            JSONObject(raw)
        } catch (e: Exception) {
            log.w("dak: ignoring unreadable saved state", e)
            return
        }
        externalId = js.optStringOrNull("external_id")
        authHash = js.optStringOrNull("auth_hash")
        tags = js.optJSONObject("tags").stringMap()
        subscribed = js.opt("subscribed") as? Boolean ?: true
        token = js.optStringOrNull("token")
        tokenType = js.optStringOrNull("token_type") ?: DakPushToken.TYPE_TOKEN
        if (js.optStringOrNull("app_id") != api.appId) {
            // Another Dak app: its device is not this app's. The user's choices stay.
            log.d("dak: the saved device belongs to another app id; registering again")
            return
        }
        deviceId = js.optStringOrNull("device_id")
        secret = js.optStringOrNull("secret")
        reported = js.optJSONObject("reported")?.let { fromJson(it) } ?: emptyMap()
        lastSeenSent = js.optLong("last_seen_sent", -1).takeIf { it >= 0 }
        val list = js.optJSONArray("opens") ?: JSONArray()
        opens = (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            o.optStringOrNull("id")?.let { PendingOpen(it, o.optStringOrNull("action")) }
        }
        receipts = js.optJSONArray("receipts").strings()
    }

    /** Saving can fail (a full disk); the state then lives in memory until the next save works. */
    private fun save(): Boolean {
        if (!loaded) return false
        return try {
            store.write(encode())
            true
        } catch (e: Exception) {
            log.w("dak: could not save the state", e)
            false
        }
    }

    private fun encode(): String {
        val js = JSONObject()
            .put("v", 1)
            // For the receiver, which sends receipts without a client.
            .put("base_url", api.baseUrl)
            .put("app_id", api.appId)
            .put("device_id", deviceId ?: JSONObject.NULL)
            .put("secret", secret ?: JSONObject.NULL)
            .put("token", token ?: JSONObject.NULL)
            .put("token_type", tokenType)
            .put("external_id", externalId ?: JSONObject.NULL)
            .put("auth_hash", authHash ?: JSONObject.NULL)
            .put("tags", JSONObject(tags as Map<*, *>))
            .put("subscribed", subscribed)
            .put("reported", toJson(reported))
            .put("opens", JSONArray(opens.map { o -> JSONObject().put("id", o.id).put("action", o.action ?: JSONObject.NULL) }))
            .put("receipts", JSONArray(receipts))
        lastSeenSent?.let { js.put("last_seen_sent", it) }
        return js.toString()
    }

    companion object {
        const val MAX_TAGS = 50
        const val MAX_RECEIPTS = 200
        const val MAX_OPENS = 50
        const val RECEIPT_BATCH = 50
        const val LOAD_TRIES = 3
        const val HOUR_MS = 60 * 60 * 1000L

        /** The longest the SDK honours a Retry-After. */
        const val MAX_RETRY_AFTER_MS = HOUR_MS

        private val PLAIN_FIELDS = listOf(
            "platform", "app_version", "os_version", "sdk_version",
            "language", "push_permission", "subscribed", "utc_offset_minutes",
        )

        /**
         * The app-set field a refusal is about, if it can be rolled back.
         * `subscribed` never is: the user's choice stays.
         */
        fun revertible(e: DakApiException): String? {
            if (e.code == "identity_verification_failed") return "external_id"
            return when (e.field) {
                "external_id", "auth_hash" -> "external_id"
                "tags" -> "tags"
                else -> null
            }
        }

        /** The state as JSON; nulls stay (JSONObject.put drops a plain null). */
        fun toJson(map: Map<String, Any?>): JSONObject {
            val js = JSONObject()
            for ((k, v) in map) {
                js.put(k, if (v is Map<*, *>) JSONObject(v) else v ?: JSONObject.NULL)
            }
            return js
        }

        /** The saved "reported" map: strings, booleans, whole numbers as Int, tags as a map. */
        fun fromJson(js: JSONObject): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            for (k in js.keys()) {
                out[k] = when (val v = js.opt(k)) {
                    JSONObject.NULL, null -> null
                    is JSONObject -> v.stringMap()
                    is Number -> v.toInt()
                    else -> v
                }
            }
            return out
        }

        private fun JSONObject?.stringMap(): Map<String, String> {
            if (this == null) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (k in keys()) (opt(k) as? String)?.let { out[k] = it }
            return out
        }
    }
}
