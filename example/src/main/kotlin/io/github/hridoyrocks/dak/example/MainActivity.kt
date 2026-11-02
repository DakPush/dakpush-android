package io.github.hridoyrocks.dak.example

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import io.github.hridoyrocks.dak.Dak
import io.github.hridoyrocks.dak.DakCallback
import io.github.hridoyrocks.dak.DakSubscription

class MainActivity : ComponentActivity() {
    private var deviceIds: DakSubscription? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val events = findViewById<TextView>(R.id.events)
        Events.onChange = { events.text = Events.text }
        events.text = Events.text
        findViewById<TextView>(R.id.server).text = "Server: ${BuildConfig.DAK_BASE_URL}\nApp: ${BuildConfig.DAK_APP_ID}"
        if (!Dak.isInitialized) return

        val device = findViewById<TextView>(R.id.device)
        device.text = "Device: not registered yet"
        deviceIds = Dak.addDeviceIdListener { id -> device.text = "Device: $id" }

        findViewById<Button>(R.id.ask_permission).setOnClickListener {
            Dak.requestPermission { granted ->
                Events.add("notifications allowed: $granted")
                showPermission()
            }
        }
        findViewById<Switch>(R.id.subscribed).setOnCheckedChangeListener { _, on ->
            Dak.setSubscribed(on, report(if (on) "subscribed" else "opted out"))
        }

        val userId = findViewById<EditText>(R.id.user_id)
        val authHash = findViewById<EditText>(R.id.auth_hash)
        findViewById<Button>(R.id.login).setOnClickListener {
            val id = userId.text.toString().trim()
            try {
                Dak.login(id, authHash.text.toString().trim(), report("logged in as $id"))
            } catch (e: IllegalArgumentException) {
                Events.add("not sent: ${e.message}")
            }
        }
        findViewById<Button>(R.id.logout).setOnClickListener { Dak.logout(report("logged out")) }

        val key = findViewById<EditText>(R.id.tag_key)
        val value = findViewById<EditText>(R.id.tag_value)
        findViewById<Button>(R.id.set_tag).setOnClickListener {
            val k = key.text.toString().trim()
            try {
                Dak.setTag(k, value.text.toString(), report("tag $k set"))
            } catch (e: IllegalArgumentException) {
                Events.add("not sent: ${e.message}")
            }
        }
        findViewById<Button>(R.id.delete_tag).setOnClickListener {
            val k = key.text.toString().trim()
            try {
                Dak.deleteTag(k, report("tag $k deleted"))
            } catch (e: IllegalArgumentException) {
                Events.add("not sent: ${e.message}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Dak.isInitialized) showPermission()
    }

    override fun onDestroy() {
        deviceIds?.remove()
        Events.onChange = null
        super.onDestroy()
    }

    private fun showPermission() {
        findViewById<TextView>(R.id.permission).text = "Notifications allowed: ${Dak.hasPermission()}"
    }

    /** Network problems never fail: the change is kept and sent later. */
    private fun report(done: String) = DakCallback { error ->
        Events.add(if (error == null) done else "refused: $error")
    }
}
