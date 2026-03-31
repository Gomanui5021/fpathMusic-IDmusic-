//MusicItem.kt
package com.example.idmusic

import android.net.Uri
import android.webkit.WebStorage

data class MusicItem(
    val title: String,
    val uri: Uri,
    val folder: String,
    val path: String,
    val storage: String
)