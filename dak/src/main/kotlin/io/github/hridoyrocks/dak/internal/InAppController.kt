package io.github.hridoyrocks.dak.internal

import io.github.hridoyrocks.dak.DakInAppClick
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/** Draws the message the controller picks. Called on the controller's thread; implementations post to the UI. */
internal interface InAppUi {
    /**
     * Shows [message]. [token] names this showing in the controller's
     * callbacks. [again]: it was on screen already (a rotation, back from the
     * background), so it comes back without the entrance animation.
     */
    fun show(message: InAppMessage, token: Int, again: Boolean)

    /** Takes showing [token] off screen, if it still is. */
    fun hide(token: Int)
}

/** What clicks do outside the controller. Called on the controller's thread. */
internal interface InAppActions {
    /** The click listeners. */
    fun clicked(click: DakInAppClick)

    /** A `url` action whose scheme is allowed. */
    fun openUrl(url: String)

    /** A `tags` action; a null value removes the tag. */
    fun setTags(tags: Map<String, String?>)
}

/** Images for the messages in the list: keep [urls] cached, fetch [prefetch] now. */
internal fun interface InAppImagePrefetch {
    fun update(urls: Set<String>, prefetch: List<String>)
}

/**
 * The in-app message logic, a port of the Flutter SDK's controller.dart: it
 * keeps the server's list (also offline), counts sessions, checks triggers
 * and frequency, picks what to show, and reports impressions, clicks and
 * dismissals in batches that survive restarts. [InAppUi] draws.
 *
 * Its state lives on [scheduler]'s thread; network calls run on [network]
 * and report back there, so a slow server never holds a trigger check.
 */
internal class InAppController(
    private val api: DakApi,
    private val store: DakStateStore,
    /** The device's id and secret, or null before it is registered. */
    private val credentials: () -> Pair<String, String>?,
    private val ui: InAppUi,
    private val actions: InAppActions,
    private val scheduler: DakScheduler,
    private val network: (Runnable) -> Unit,
    private val log: DakLog,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Whether an activity of the app is started now. */
    private val isForeground: () -> Boolean = { true },
    /** The server refused the device (401): the client checks it and registers again if needed. */
    private val onUnauthorized: () -> Unit = {},
    private val images: InAppImagePrefetch? = null,
    /** The schemes a `url` action may open. */
    private val urlSchemes: Set<String> = setOf("https", "http"),
    /** Without a successful fetch for this long, the cache is not shown: it may hold stopped messages. */
    private val cacheMaxAgeMs: Long = 7 * DAY_MS,
    /** Back from the background after this long starts a new session. */
    private val sessionTimeoutMs: Long = 5 * 60_000L,
    /** How long a session start waits for the server before showing from the cache. */
    private val fetchWaitMs: Long = 3_000,
    /** Session starts closer together than this use the list already fetched. */
    private val minFetchIntervalMs: Long = 60_000,
    /** Events go this long after the last one; null sends only on flush (tests). */
    private val eventDelayMs: Long? = 2_000,
    /** Waits before sending events again after a failure; the last one repeats. */
    private val retryDelaysMs: List<Long> = listOf(30_000, 120_000, 600_000),
) {
    private class Local(
        var shown: Int = 0,
        var last: Long? = null,
        /** The session it was last shown in (for every_session). */
        var session: Int = 0,
        /** Hidden before the user closed it: it comes back once, and that is not a new impression. */
        var interrupted: Boolean = false,
    ) {
        fun toJson(): JSONObject = JSONObject().put("shown", shown).put("last", last ?: JSONObject.NULL)
            .put("session", session).also { if (interrupted) it.put("interrupted", true) }

        companion object {
            fun fromJson(js: JSONObject) = Local(
                shown = (js.opt("shown") as? Number)?.toInt() ?: 0,
                last = (js.opt("last") as? Number)?.toLong(),
                session = (js.opt("session") as? Number)?.toInt() ?: 0,
                interrupted = js.opt("interrupted") == true,
            )
        }
    }

    // Saved.
    private var raw = JSONArray()
    private var etag: String? = null
    private var fetchedAt: Long? = null
    private var local = LinkedHashMap<String, Local>()
    private var sessions = 0
    private var installedAt: Long? = null
    private var events = ArrayList<JSONObject>()
    private var seq = 0L

    // This run only.
    private var messages: List<InAppMessage> = emptyList()
    private val triggers = LinkedHashMap<String, Any>()
    private var sessionStart = clock()
    private var backgroundAt: Long? = null
    private var started = false

    /** The saved state was read; until then nothing is saved over it. */
    private var canSave = false
    private var loadTried = false
    private var foreground = false

    /** Started in the background: the first session waits for the foreground. */
    private var sessionDue = false
    private var host = false
    private var pending = false
    private var current: InAppMessage? = null
    private var token = 0
    private var displayed = false
    private val failed = HashSet<String>()
    private var timeTimer: Cancelable? = null
    private var eventTimer: Cancelable? = null
    private var retryTimer: Cancelable? = null
    private var eventFailures = 0
    private var fetching = false
    private val afterFetch = ArrayList<() -> Unit>()
    private var sending = false

    @Volatile
    private var paused = false

    /** Blocks that have not ended ([block]); counted, so they nest. */
    private var blocks = 0

    /** Messages are kept away by [setPaused] or a [block]; readable from any thread. */
    val isPaused: Boolean get() = paused || synchronized(this) { blocks > 0 }

    // ---- for tests (read on the scheduler's thread)

    /** Usable messages from the last fetch or the cache, best first. */
    val messageList: List<InAppMessage> get() = messages

    /** Sessions of this install, the current one included. */
    val sessionCount: Int get() = sessions

    /** Impressions, clicks and dismissals waiting to be sent. */
    val pendingEvents: List<JSONObject> get() = events.toList()

    /** The message on screen or about to be. */
    val currentMessage: InAppMessage? get() = current

    /** When a time trigger (session_time, since_install) next comes true. */
    var nextTimeCheck: Long? = null
        private set

    /**
     * Reads the cache, starts the first session and fetches the list. A
     * process started in the background (a push) starts its first session
     * when an activity starts.
     */
    fun start() = scheduler.execute {
        if (started) return@execute
        started = true
        load()
        if (installedAt == null) installedAt = clock()
        foreground = isForeground()
        if (!foreground) {
            sessionDue = true
            save()
            return@execute
        }
        newSession()
        save()
        sessionStarted()
    }

    private fun newSession() {
        sessions++
        sessionStart = clock()
        failed.clear()
    }

    /** Fetches, waiting [fetchWaitMs] at most before showing from the cache; the fresh list is checked when it comes. */
    private fun sessionStarted() {
        var done = false
        val wait = scheduler.schedule(fetchWaitMs) {
            if (!done) evaluate()
        }
        refresh(force = false) {
            done = true
            wait.cancel()
            evaluate()
            flushNow()
        }
    }

    /** Fetches the list (at most once a minute unless [force]); [then] runs when that fetch ends. Never fails. */
    private fun refresh(force: Boolean, then: (() -> Unit)? = null) {
        val c = credentials()
        if (c == null) {
            then?.invoke()
            return
        }
        val now = clock()
        val last = fetchedAt
        if (!force && last != null && now >= last && now - last < minFetchIntervalMs) {
            if (then != null) if (fetching) afterFetch.add(then) else then()
            return
        }
        if (then != null) afterFetch.add(then)
        if (fetching) return
        fetching = true
        val sentEtag = etag
        network(Runnable {
            var list: InAppList? = null
            var error: Exception? = null
            try {
                list = api.fetchInApp(c.first, c.second, sentEtag)
            } catch (e: Exception) {
                error = e
            }
            scheduler.execute { fetched(list, error) }
        })
    }

    private fun fetched(list: InAppList?, error: Exception?) {
        fetching = false
        if (error == null) {
            if (list != null) {
                messages = parse(list.messages)
                raw = list.messages
                etag = list.etag
                updateImages()
            }
            fetchedAt = clock()
            save()
        } else {
            log.d("dak: in-app fetch failed, using the cache: $error")
            if (error is DakApiException && error.isUnauthorized) onUnauthorized()
        }
        val waiting = ArrayList(afterFetch)
        afterFetch.clear()
        waiting.forEach { it() }
    }

    /** The device registered (again): fetch its list now. */
    fun onRegistered() = scheduler.execute {
        if (!started) return@execute
        if (!foreground) {
            flushNow()
            return@execute
        }
        refresh(force = true) {
            evaluate()
            flushNow()
        }
    }

    /** The messages this SDK can show, best first. One it cannot read is skipped, never the whole list. */
    private fun parse(list: JSONArray): List<InAppMessage> {
        val out = ArrayList<InAppMessage>()
        for (i in 0 until list.length()) {
            val m = InAppMessage.parse(list.opt(i))
            if (m == null) log.d("dak: skipping an in-app message this SDK version cannot show") else out.add(m)
        }
        // Highest priority first; ties keep the server's order (a stable sort).
        return out.sortedByDescending { it.priority }
    }

    /** Images of the list stay cached; those of messages that may still show are fetched ahead. */
    private fun updateImages() {
        val images = images ?: return
        val now = clock()
        val urls = messages.mapNotNull { it.content.imageUrl?.takeIf { u -> u.startsWith("https://") } }.toSet()
        val ahead = messages.filter { m ->
            val url = m.content.imageUrl
            url != null && url.startsWith("https://") && (m.endAt == null || now < m.endAt) && !usedUp(m)
        }.mapNotNull { it.content.imageUrl }.distinct().take(PREFETCH_IMAGES)
        images.update(urls, ahead)
    }

    // ---- triggers and pause

    /** Sets triggers (checked by the caller). Triggers live in memory only and never leave the phone. */
    fun addTriggers(values: Map<String, Any>) = scheduler.execute {
        triggers.putAll(values)
        evaluate(queue = true)
    }

    fun removeTriggers(keys: Collection<String>) = scheduler.execute {
        keys.forEach { triggers.remove(it) }
        evaluate(queue = true)
    }

    fun clearTriggers() = scheduler.execute {
        triggers.clear()
        evaluate(queue = true)
    }

    /** Checks the triggers again now (also called by timers). */
    fun check() = scheduler.execute { evaluate(queue = true) }

    /** While paused nothing shows; a message on screen is hidden (not dismissed) and comes back after. */
    fun setPaused(paused: Boolean) {
        this.paused = paused
        scheduler.execute { pauseChanged() }
    }

    /** Keeps messages away until [unblock], like a pause; blocks nest. */
    fun block() {
        synchronized(this) { blocks++ }
        scheduler.execute { pauseChanged() }
    }

    /** Ends one [block]. */
    fun unblock() {
        synchronized(this) { blocks = max(0, blocks - 1) }
        scheduler.execute { pauseChanged() }
    }

    private fun pauseChanged() {
        if (isPaused) interrupt() else evaluate()
    }

    /** Takes the message off screen without the user closing it. */
    private fun interrupt() {
        val m = current ?: return
        if (displayed) {
            local.getOrPut(m.id) { Local() }.interrupted = true
            save()
        }
        displayed = false
        current = null
        ui.hide(token)
    }

    // ---- host

    /** An activity that can show messages is on screen. */
    fun attach() = scheduler.execute {
        host = true
        evaluate()
    }

    /** That activity went away (paused, rotated, another one opened): the message on screen comes back on the next one. */
    fun detach() = scheduler.execute {
        host = false
        interrupt()
    }

    /** Showing [token] is on screen: count the impression. */
    fun didDisplay(token: Int) = scheduler.execute {
        val m = current
        if (m == null || token != this.token || displayed) return@execute
        displayed = true
        val l = local.getOrPut(m.id) { Local() }
        l.session = sessions
        if (l.interrupted) {
            // The same display, back after a pause or a rotation: counted already.
            l.interrupted = false
            save()
            return@execute
        }
        l.shown++
        l.last = clock()
        if (local.size > MAX_LOCAL) {
            local.entries.minByOrNull { it.value.last ?: 0L }?.key?.let { local.remove(it) }
        }
        queueEvent(m.id, "impression")
    }

    /** It could not be drawn (an image-only message whose image did not load): skipped for this session. */
    fun didFail(token: Int) = scheduler.execute {
        val m = current
        if (m == null || token != this.token) return@execute
        failed.add(m.id)
        close()
        evaluate()
    }

    /** A tap on button [buttonId], or on the message itself (null). */
    fun click(token: Int, buttonId: String?) = scheduler.execute {
        val m = current
        if (m == null || token != this.token) return@execute
        val button = buttonId?.let { id -> m.content.buttons.firstOrNull { it.id == id } ?: return@execute }
        val action = button?.action ?: m.content.clickAction ?: return@execute
        queueEvent(m.id, "click", button?.id)
        close()
        actions.clicked(DakInAppClick(m.id, button?.id, action.type, action.url, action.tags))
        try {
            when (action.type) {
                "tags" -> actions.setTags(action.tags.mapValues { (_, v) -> v.ifEmpty { null } })
                "url" -> {
                    val url = action.url.orEmpty()
                    val scheme = url.substringBefore(':', "").lowercase()
                    if (url.contains(':') && scheme in urlSchemes) {
                        actions.openUrl(url)
                    } else {
                        log.w("dak: not opening $url: only ${urlSchemes.joinToString()} URLs are opened")
                    }
                }
            }
        } catch (e: Exception) {
            log.w("dak: in-app action ${action.type} failed", e)
        }
    }

    /** Closed by the X, a tap outside, a swipe, back or a timeout. */
    fun dismiss(token: Int) = scheduler.execute {
        val m = current
        if (m == null || token != this.token) return@execute
        if (displayed) queueEvent(m.id, "dismiss")
        close()
    }

    private fun close() {
        displayed = false
        current = null
        ui.hide(token)
        if (pending) {
            pending = false
            evaluate()
        }
    }

    // ---- lifecycle

    /** An activity started after none was: a new session after [sessionTimeoutMs] away. */
    fun onForeground() = scheduler.execute {
        if (!started) return@execute
        foreground = true
        if (loadTried && !canSave) reload()
        val since = backgroundAt
        backgroundAt = null
        if (sessionDue || (since != null && clock() - since >= sessionTimeoutMs)) {
            sessionDue = false
            newSession()
            save()
            sessionStarted()
        } else {
            evaluate()
        }
    }

    /** The last activity stopped: events go now. */
    fun onBackground() = scheduler.execute {
        if (!started) return@execute
        foreground = false
        if (backgroundAt == null) backgroundAt = clock()
        flushNow()
    }

    // ---- choosing

    /** Shows the best message that is due. With [queue], one due while another is on screen follows when it closes. */
    private fun evaluate(queue: Boolean = false) {
        if (!started) return
        val now = clock()
        scheduleTimer(now)
        val best = if (cacheFresh(now)) messages.firstOrNull { eligible(it, now) } else null
        if (best == null) return
        val cur = current
        if (cur != null) {
            if (queue && cur.id != best.id) pending = true
            return
        }
        if (isPaused || !host || !foreground) return
        // A message back after a pause goes first; what came due meanwhile follows when it closes.
        val again = local[best.id]?.interrupted == true
        if (again) pending = true
        displayed = false
        current = best
        token++
        ui.show(best, token, again)
    }

    /** A cache without a fetch time (written by hand) counts as fresh. */
    private fun cacheFresh(now: Long): Boolean {
        val at = fetchedAt ?: return true
        return now - at <= cacheMaxAgeMs
    }

    /** Shown as often as its frequency allows, in all. */
    private fun usedUp(m: InAppMessage): Boolean {
        val shown = max(local[m.id]?.shown ?: 0, m.shown)
        val maxDisplays = m.frequency.maxDisplays
        return (maxDisplays != null && shown >= maxDisplays) || (m.frequency.type == "once" && shown > 0)
    }

    private fun eligible(m: InAppMessage, now: Long): Boolean {
        if (m.id in failed) return false
        if (m.startAt != null && now < m.startAt) return false
        if (m.endAt != null && now >= m.endAt) return false
        val l = local[m.id]
        if (l != null && l.interrupted) return triggered(m, now)
        if (usedUp(m)) return false
        val last = later(l?.last, m.lastShownAt)
        when (m.frequency.type) {
            "every_session" -> if (l?.session == sessions) return false
            "interval" -> if (last != null && now - last < m.frequency.intervalMs) return false
        }
        return triggered(m, now)
    }

    private fun triggered(m: InAppMessage, now: Long): Boolean =
        m.triggers.isEmpty() || m.triggers.any { group -> group.all { holds(it, now) } }

    private fun holds(c: InAppCondition, now: Long): Boolean = when (c.kind) {
        "session_count" -> compare(sessions.toDouble(), c.op, (c.value as Number).toDouble())
        "session_time" -> compare(((now - sessionStart) / 1000).toDouble(), c.op, (c.value as Number).toDouble())
        "since_install" -> compare(((now - (installedAt ?: now)) / 1000).toDouble(), c.op, (c.value as Number).toDouble())
        "custom" -> {
            val v = triggers[c.key]
            when (c.op) {
                "exists" -> v != null
                "not_exists" -> v == null
                "in" -> v != null && str(v) in c.values
                "=" -> v != null && equal(v, c.value)
                "!=" -> v == null || !equal(v, c.value)
                else -> {
                    val a = num(v)
                    val b = num(c.value)
                    a != null && b != null && compare(a, c.op, b)
                }
            }
        }
        else -> false
    }

    /** Wakes up when a session_time or since_install condition comes true. */
    private fun scheduleTimer(now: Long) {
        timeTimer?.cancel()
        timeTimer = null
        var next: Long? = null
        for (m in messages) {
            for (group in m.triggers) {
                for (c in group) {
                    if (c.op != ">=") continue
                    val ms = (c.value as Number).toLong() * 1000
                    val at = when (c.kind) {
                        "session_time" -> sessionStart + ms
                        "since_install" -> (installedAt ?: now) + ms
                        else -> null
                    }
                    if (at != null && at > now && (next == null || at < next)) next = at
                }
            }
        }
        nextTimeCheck = next
        if (next != null && next - now <= DAY_MS) {
            timeTimer = scheduler.schedule(next - now) {
                timeTimer = null
                evaluate(queue = true)
            }
        }
    }

    // ---- events

    private fun queueEvent(messageId: String, type: String, buttonId: String? = null) {
        val now = clock()
        // Microseconds keep seq growing even if the saved state is lost and the device keeps its id.
        seq = max(seq + 1, now * 1000)
        val e = JSONObject().put("message_id", messageId).put("type", type)
        if (buttonId != null) e.put("button_id", buttonId)
        e.put("seq", seq).put("at", formatTime(now))
        events.add(e)
        while (events.size > MAX_EVENTS) events.removeAt(0)
        save()
        val delay = eventDelayMs ?: return
        eventTimer?.cancel()
        eventTimer = scheduler.schedule(delay) {
            eventTimer = null
            flushNow()
        }
    }

    /** Sends queued events now. Failures keep them for later. */
    fun flushEvents() = scheduler.execute { flushNow() }

    private fun flushNow() {
        if (sending || events.isEmpty()) return
        val c = credentials() ?: return
        sending = true
        val batch = events.take(EVENT_BATCH)
        val body = JSONArray(batch)
        network(Runnable {
            var error: Exception? = null
            try {
                api.sendInAppEvents(c.first, c.second, body)
            } catch (e: Exception) {
                error = e
            }
            scheduler.execute { sent(batch, error) }
        })
    }

    private fun sent(batch: List<JSONObject>, error: Exception?) {
        sending = false
        if (error != null) {
            if (error is DakApiException && error.isPermanent) {
                log.w("dak: in-app events refused, dropping them: $error")
            } else {
                log.d("dak: in-app events not sent yet: $error")
                if (error is DakApiException && error.isUnauthorized) onUnauthorized()
                scheduleEventRetry()
                return
            }
        }
        eventFailures = 0
        retryTimer?.cancel()
        retryTimer = null
        // By identity: the list may have changed meanwhile (a reload merged saved events in front).
        events.removeAll { e -> batch.any { it === e } }
        save()
        if (events.isNotEmpty()) flushNow()
    }

    private fun scheduleEventRetry() {
        if (retryDelaysMs.isEmpty()) return
        retryTimer?.cancel()
        val delay = retryDelaysMs[min(eventFailures, retryDelaysMs.size - 1)]
        eventFailures++
        retryTimer = scheduler.schedule(delay) {
            retryTimer = null
            flushNow()
        }
    }

    // ---- persistence

    /** A read that fails leaves the controller running from memory without saving; the next foreground reads again. */
    private fun load() {
        try {
            val text = store.read()
            canSave = true
            restore(text)
        } catch (e: Exception) {
            log.d("dak: in-app state not readable yet, running from memory: $e")
        }
        loadTried = true
    }

    /** The saved state became readable: it continues, with what happened meanwhile added. */
    private fun reload() {
        val text = try {
            store.read()
        } catch (e: Exception) {
            log.d("dak: in-app state still not readable: $e")
            return
        }
        val memRaw = raw
        val memEtag = etag
        val memFetchedAt = fetchedAt
        val memLocal = local
        val memSessions = sessions
        val memInstalledAt = installedAt
        val memEvents = events
        val memSeq = seq
        reset()
        restore(text)
        val before = sessions
        sessions += memSessions
        for ((id, l) in memLocal) {
            val saved = local[id]
            local[id] = Local(
                shown = (saved?.shown ?: 0) + l.shown,
                last = later(saved?.last, l.last),
                session = if (l.session > 0) l.session + before else saved?.session ?: 0,
                interrupted = l.interrupted || saved?.interrupted == true,
            )
        }
        if (installedAt == null) installedAt = memInstalledAt
        events.addAll(memEvents)
        while (events.size > MAX_EVENTS) events.removeAt(0)
        seq = max(seq, memSeq)
        if (memFetchedAt != null) {
            raw = memRaw
            etag = memEtag
            fetchedAt = memFetchedAt
            messages = parse(raw)
        }
        canSave = true
        save()
        evaluate()
    }

    private fun reset() {
        raw = JSONArray()
        etag = null
        fetchedAt = null
        local = LinkedHashMap()
        sessions = 0
        installedAt = null
        events = ArrayList()
        seq = 0
        messages = emptyList()
    }

    /** Unreadable parts of the saved state are left out, never all of it. */
    private fun restore(text: String?) {
        if (text.isNullOrEmpty()) return
        val js = try {
            JSONObject(text)
        } catch (e: Exception) {
            log.w("dak: ignoring unreadable in-app state", e)
            return
        }
        raw = js.optJSONArray("messages") ?: JSONArray()
        etag = js.opt("etag") as? String
        fetchedAt = (js.opt("fetched_at") as? Number)?.toLong()
        local = LinkedHashMap()
        js.optJSONObject("local")?.let { l ->
            for (k in l.keys()) l.optJSONObject(k)?.let { local[k] = Local.fromJson(it) }
        }
        sessions = (js.opt("sessions") as? Number)?.toInt() ?: 0
        installedAt = (js.opt("installed_at") as? Number)?.toLong()
        events = ArrayList()
        js.optJSONArray("events")?.let { list ->
            for (i in 0 until list.length()) list.optJSONObject(i)?.let { events.add(it) }
        }
        seq = (js.opt("seq") as? Number)?.toLong() ?: 0
        messages = parse(raw)
    }

    /** Saving can fail (a full disk); the state then lives in memory until the next save works. */
    private fun save() {
        if (!canSave) return
        val js = JSONObject()
            .put("v", 1)
            .put("messages", raw)
            .put("etag", etag ?: JSONObject.NULL)
            .put("fetched_at", fetchedAt ?: JSONObject.NULL)
            .put("local", JSONObject().also { o -> local.forEach { (k, v) -> o.put(k, v.toJson()) } })
            .put("sessions", sessions)
            .put("installed_at", installedAt ?: JSONObject.NULL)
            .put("events", JSONArray(events))
            .put("seq", seq)
        try {
            store.write(js.toString())
        } catch (e: Exception) {
            log.w("dak: could not save the in-app state", e)
        }
    }

    companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val MAX_EVENTS = 500
        const val MAX_LOCAL = 500
        const val EVENT_BATCH = 100
        const val PREFETCH_IMAGES = 5

        fun compare(a: Double, op: String, b: Double): Boolean = when (op) {
            "=" -> a == b
            "!=" -> a != b
            ">" -> a > b
            ">=" -> a >= b
            "<" -> a < b
            "<=" -> a <= b
            else -> false
        }

        fun num(v: Any?): Double? = when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        }

        /** Whole numbers without ".0", like the Flutter SDK. */
        fun str(v: Any): String {
            if (v is Double || v is Float) {
                val d = (v as Number).toDouble()
                if (!d.isInfinite() && d == Math.floor(d) && Math.abs(d) < 1e15) return d.toLong().toString()
            }
            return v.toString()
        }

        fun equal(a: Any, b: Any?): Boolean {
            val x = num(a)
            val y = num(b)
            if (x != null && y != null) return x == y
            return b != null && str(a) == str(b)
        }

        private fun later(a: Long?, b: Long?): Long? = when {
            a == null -> b
            b == null -> a
            else -> max(a, b)
        }
    }
}
