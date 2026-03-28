//MusicItem.kt
package com.example.idmusic

import android.net.Uri

data class MusicItem(
    val title: String,
    val uri: Uri,
    val folder: String = "Unknown"
)