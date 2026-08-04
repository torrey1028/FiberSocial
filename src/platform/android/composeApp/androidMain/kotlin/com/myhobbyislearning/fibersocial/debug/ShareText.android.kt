package com.myhobbyislearning.fibersocial.debug

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberShareText(): (text: String) -> Unit {
    val context = LocalContext.current
    return { text ->
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "Share debug log"))
    }
}
