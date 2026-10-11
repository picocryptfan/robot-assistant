package app.heyrobot.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import app.heyrobot.core.RLog

// VOICE PROCESS. free_storage's last step for a photo or video you picked on the card (0.4.0): Android's own
// confirmation (MediaStore.createDeleteRequest), which only an activity can start for a result (the panel can't, as for
// CaptureActivity). Robot never deletes a file itself: Android asks, and deletes only if you allow it there. Nothing
// about the file is logged.

class MediaDeleteActivity : ComponentActivity() {
    private val confirm = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        RLog.i("media_delete", "deleted" to (r.resultCode == RESULT_OK))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated while Android's dialog shows: the dialog's answer still arrives.
        if (savedInstanceState != null) return
        val uri = intent.data?.takeIf { it.scheme == "content" && it.authority == MediaStore.AUTHORITY }
        if (uri == null) return finish()
        try {
            confirm.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(contentResolver, listOf(uri)).intentSender).build())
        } catch (t: Exception) {
            RLog.w("media_delete", t)
            finish()
        }
    }

    companion object {
        fun intent(ctx: Context, uri: Uri): Intent = Intent(ctx, MediaDeleteActivity::class.java).setData(uri)
    }
}
