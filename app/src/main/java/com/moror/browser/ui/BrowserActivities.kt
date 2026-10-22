package com.moror.browser.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.moror.browser.R
import com.moror.browser.data.ProfileManager

class MainActivity : AppCompatActivity() {

    private var isForwarded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val targetIntent = Intent(this, BaseBrowserActivity::class.java).apply {
            data = intent?.data
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        findViewById<View>(R.id.rootSplash).setOnClickListener {
            forwardToBrowser(targetIntent)
        }

        findViewById<View>(R.id.rootSplash).postDelayed({
            forwardToBrowser(targetIntent)
        }, 1400L)
    }

    private fun forwardToBrowser(intent: Intent) {
        if (isForwarded) return
        isForwarded = true
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}

object BrowserSlotRegistry {
    fun getActivityClassForSlot(slot: Int): Class<out BaseBrowserActivity> {
        return BaseBrowserActivity::class.java
    }
}
