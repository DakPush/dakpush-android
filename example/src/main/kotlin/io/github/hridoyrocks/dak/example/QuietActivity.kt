package io.github.hridoyrocks.dak.example

import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity
import io.github.hridoyrocks.dak.DakInAppBlocker

/**
 * A screen in-app messages never cover, like a call or a payment screen. A
 * message that was showing comes back when the user goes back.
 */
class QuietActivity : ComponentActivity(), DakInAppBlocker {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "In-app messages wait while this screen is open.\n\nGo back to see them again."
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        })
    }
}
