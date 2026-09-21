package com.aikeyboardmobile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: pull down the notification shade, tap "AI Reply",
 * and the reply panel opens over whatever app is on screen.
 * No restricted permissions involved — just the normal overlay permission.
 */
class ReplyTileService : TileService() {

    override fun onClick() {
        super.onClick()
        if (!Settings.canDrawOverlays(this)) {
            launchApp()
            return
        }
        val i = Intent(this, BubbleService::class.java).setAction(BubbleService.ACTION_SHOW_PANEL)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i)
        } else {
            startService(i)
        }
    }

    private fun launchApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 31) {
            startActivityAndCollapse(i)
        } else {
            val pi = PendingIntent.getActivity(
                this, 0, i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            @Suppress("DEPRECATION")
            startActivityAndCollapse(pi)
        }
    }
}
