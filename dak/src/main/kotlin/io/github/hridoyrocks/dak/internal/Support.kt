package io.github.hridoyrocks.dak.internal

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Something scheduled that can still be called off. */
internal fun interface Cancelable {
    fun cancel()
}

/**
 * The SDK's one worker: everything that reads or changes the client's state
 * runs here, in order, so there is no locking and a change made while a
 * sync runs simply waits for the next round.
 */
internal interface DakScheduler {
    fun execute(task: () -> Unit)

    fun schedule(delayMs: Long, task: () -> Unit): Cancelable
}

/** [DakScheduler] on a background thread. */
internal class ThreadScheduler(private val log: DakLog) : DakScheduler {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "dak-worker").apply { isDaemon = true }
    }

    override fun execute(task: () -> Unit) {
        executor.execute { guarded(task) }
    }

    override fun schedule(delayMs: Long, task: () -> Unit): Cancelable {
        val future = executor.schedule({ guarded(task) }, delayMs, TimeUnit.MILLISECONDS)
        return Cancelable { future.cancel(false) }
    }

    private fun guarded(task: () -> Unit) {
        try {
            task()
        } catch (e: Exception) {
            log.w("unexpected error", e)
        }
    }
}

/** Logs with the tag "Dak": debug lines only with DakOptions(debugLogs). */
internal class DakLog(
    private val debug: Boolean,
    private val sink: (priority: Int, message: String, error: Throwable?) -> Unit = { p, m, e ->
        if (e == null) Log.println(p, TAG, m) else Log.println(p, TAG, "$m\n${Log.getStackTraceString(e)}")
    },
) {
    fun d(message: String) {
        if (debug) sink(Log.DEBUG, message, null)
    }

    /** Setup mistakes and failures the developer should see, always logged. */
    fun w(message: String, error: Throwable? = null) = sink(Log.WARN, message, error)

    companion object {
        const val TAG = "Dak"
    }
}

/** The server counts lengths in UTF-8 bytes. */
internal fun utf8Length(s: String): Int = s.toByteArray(Charsets.UTF_8).size

/** [s] cut to at most [max] UTF-8 bytes, never inside a character. */
internal fun clampUtf8(s: String, max: Int): String {
    if (utf8Length(s) <= max) return s
    val out = StringBuilder()
    var used = 0
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val ch = String(Character.toChars(cp))
        val n = utf8Length(ch)
        if (used + n > max) break
        out.append(ch)
        used += n
        i += Character.charCount(cp)
    }
    return out.toString()
}
