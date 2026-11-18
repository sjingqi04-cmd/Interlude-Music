package com.metrolist.music.recognition

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * Transparent trampoline for the internal-audio capture consent dialog. Android demands a fresh
 * consent for every capture session, so this cannot be cached or skipped.
 */
class RecognitionProjectionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            finish()
            return
        }
        startActivityForResult(
            getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),
            REQUEST_PROJECTION,
        )
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PROJECTION && resultCode == RESULT_OK && data != null) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, RecognitionForegroundService::class.java).apply {
                    action = RecognitionForegroundService.ACTION_START_PLAYBACK_RECOGNITION
                    putExtra(RecognitionForegroundService.EXTRA_PROJECTION_RESULT_CODE, resultCode)
                    putExtra(RecognitionForegroundService.EXTRA_PROJECTION_RESULT_DATA, data)
                },
            )
        }
        finish()
    }

    private companion object {
        const val REQUEST_PROJECTION = 2001
    }
}