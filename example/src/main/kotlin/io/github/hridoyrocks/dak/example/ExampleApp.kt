package io.github.hridoyrocks.dak.example

import android.app.Application
import android.util.Log
import io.github.hridoyrocks.dak.Dak
import io.github.hridoyrocks.dak.DakOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DAK_APP_ID.isEmpty()) {
            Events.add("Set dak.exampleAppId in local.properties, then build again")
            return
        }
        // In Application.onCreate, so a tap that starts the app is seen.
        Dak.init(
            this,
            appId = BuildConfig.DAK_APP_ID,
            baseUrl = BuildConfig.DAK_BASE_URL,
            options = DakOptions(shareTimezoneOffset = true, debugLogs = true),
        )
        // Taps, the one that started the app included. A real app routes
        // n.url here (or the button's own URL, which n.url already is).
        Dak.addOpenedListener { n ->
            Events.add("opened ${n.id} button=${n.actionId} url=${n.url} data=${n.data}")
        }
        // Android shows nothing while the app is open; this shows it anyway.
        Dak.addForegroundListener { n ->
            Events.add("arrived while open: ${n.title} / ${n.body}")
            if (!n.isSilent) Dak.showNotification(n)
        }
        Dak.addErrorListener { e -> Events.add("refused: $e") }
        Dak.addDeviceIdListener { id -> Events.add("device id $id") }
    }
}

/** A short log the screen shows. Main thread only. */
object Events {
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    var onChange: (() -> Unit)? = null

    val text: String get() = lines.joinToString("\n")

    fun add(line: String) {
        Log.i("DakExample", line)
        lines.addFirst("${time.format(Date())} $line")
        while (lines.size > 50) lines.removeLast()
        onChange?.invoke()
    }
}
