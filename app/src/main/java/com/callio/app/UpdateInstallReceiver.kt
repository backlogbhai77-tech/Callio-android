package com.callio.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Receives the PackageInstaller session result for in-app updates. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android needs one confirmation tap (first self-update, or OEM policy).
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                try {
                    confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                } catch (_: Exception) {
                    listener?.invoke(PackageInstaller.STATUS_FAILURE, "Tap the update notification to finish installing")
                }
            }
            else -> listener?.invoke(status, message)
        }
    }

    companion object {
        const val ACTION = "com.callio.app.UPDATE_INSTALL_RESULT"
        @Volatile var listener: ((Int, String?) -> Unit)? = null
    }
}
