//MusicService.kt
package com.example.idmusic

import android.app.Service
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import android.app.PendingIntent
import android.app.NotificationManager
import android.app.NotificationChannel
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.graphics.Bitmap
import android.support.v4.media.MediaMetadataCompat
import android.media.MediaMetadataRetriever
import android.graphics.BitmapFactory
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import androidx.media.session.MediaButtonReceiver
import kotlin.text.toLong

class MusicService : Service() {

    companion object {
        const val ACTION_PLAY = "ACTION_PLAY"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"

        const val STATE_PLAYING = "STATE:PLAYING"

        const val STATE_PAUSED = "STATE:PAUSED"


        const val ACTION_STOP = "STOP"
        const val CHANNEL_ID = "music_channel"

        const val ACTION_SEEK = "ACTION_SEEK"

        var instance: MusicService? = null
    }

    private var mediaPlayer: MediaPlayer? = null
    private var currentTitle: String? = null
    private var currentDevice: String? = null
    private var currentBitmap: Bitmap? = null

    private  var mediaSession: MediaSessionCompat? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        Log.d("MusicService", "🔥 onStartCommand 呼ばれた action: ${intent?.action}")

        createNotificationChannel()

        val uriString = intent?.getStringExtra("MUSIC_URI")

        val newTitle = intent?.getStringExtra("TITLE")
        val newDevice = intent?.getStringExtra("DEVICE")

        if (newTitle != null) currentTitle = newTitle
        if (newDevice != null) currentDevice = newDevice


        when (intent?.action) {

// ACTION_PLAY 内の修正版
            ACTION_PLAY -> {

                startForeground(
                    1,
                    createNotification(
                        currentTitle ?: "不明",
                        currentDevice ?: "未接続",
                        true
                    )
                )

                try {
                    // 既存プレイヤーを解放
                    mediaPlayer?.release()

                    if (uriString != null) {

                        val uri = Uri.parse(uriString)
                        val fd = contentResolver.openFileDescriptor(uri, "r")

                        if (fd == null) {
                            Log.e("MusicService", "FD取得失敗: $uri")
                            return START_NOT_STICKY
                        }

                        // MediaPlayer 設定・再生
                        mediaPlayer = MediaPlayer().apply {
                            setDataSource(fd.fileDescriptor)
                            fd.close()
                            prepare()
                            start()
                        }


// 🎵 メタデータ取得（ジャケット）
                        val mmr = MediaMetadataRetriever()
                        mmr.setDataSource(this@MusicService, uri)

                        val art: ByteArray? = mmr.embeddedPicture

                        val bitmap: Bitmap = if (art != null) {
                            BitmapFactory.decodeByteArray(art, 0, art.size)
                        } else {
                            getAlbumArt(uri)
                                ?: BitmapFactory.decodeResource(resources, R.drawable.no_image)
                        }

// 🔥 ここに移動（超重要）
                        val resized = resizeBitmap(bitmap)
                        val byteArray = bitmapToByteArray(resized)

// メタデータ更新
                        updateMetadata(currentTitle ?: "不明", bitmap)
                        currentBitmap = bitmap

// 通知更新
                        Handler(Looper.getMainLooper()).postDelayed({
                            val notification = createNotification(
                                currentTitle ?: "不明",
                                currentDevice ?: "未接続",
                                true,
                                bitmap
                            )
                            startForeground(1, notification)
                        }, 300)

                        // Bluetooth などへ再生通知
                        sendControlCommand("PLAY")
                    }

                } catch (e: Exception) {
                    Log.e("MusicService", "再生エラー", e)
                }
            }

            ACTION_PAUSE -> {
                Log.d("MusicService", "⏸ PAUSE押された")
                mediaPlayer?.pause()
                updateNotification(false)
                sendCommandToPeer("PAUSE")

                sendControlCommand("PAUSE")
            }

            ACTION_RESUME -> {
                Log.d("MusicService", "▶ RESUME押された")
                try {
                    mediaPlayer?.start()
                    updateNotification(true)
                    sendCommandToPeer("RESUME")
                    sendControlCommand("RESUME")
                } catch (e: Exception) {
                    Log.e("MusicService", "再開エラー", e)
                }
            }

            ACTION_STOP -> {
                Log.d("MusicService", "■ STOP押された")
                mediaPlayer?.stop()
                mediaPlayer?.reset()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_SEEK -> {
                val pos = intent.getIntExtra("SEEK_POS", 0)
                seekTo(pos)
            }

        }
        return START_STICKY
    }

    fun pauseFromRemote() {
        mediaPlayer?.pause()
        updateNotification(false)
    }

    fun resumeFromRemote() {
        mediaPlayer?.start()
        updateNotification(true)
    }

    fun sendControlCommand(command: String) {
        try {

            Log.d("BT_DEBUG", "送信コマンド: $command")

            Log.d("BT_DEBUG", "client = ${BluetoothClient.instance}")
            Log.d("BT_DEBUG", "server = ${BluetoothServer.instance}")

            BluetoothClient.instance?.sendMessage(command)
            BluetoothServer.instance?.send(command)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun sendCommandToPeer(command: String) {
        try {
            BluetoothClient.instance?.sendMessage(command)
            BluetoothServer.instance?.send(command)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isPlaying(): Boolean {
        return try {
            mediaPlayer?.isPlaying == true
        } catch (e: IllegalStateException) {
            false
        }
    }

    private fun updateNotification(isPlaying: Boolean) {

        updatePlaybackState(isPlaying)

        updateMetadata(
            currentTitle ?: "不明",
            currentBitmap
        )

        val notification = createNotification(
            currentTitle ?: "不明",
            currentDevice ?: "未接続",
            isPlaying,
            currentBitmap
        )


        startForeground(1, notification)
    }

    private fun updatePlaybackState(isPlaying: Boolean) {
        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE
            )
            .setState(
                if (isPlaying)
                    PlaybackStateCompat.STATE_PLAYING
                else
                    PlaybackStateCompat.STATE_PAUSED,
                mediaPlayer?.currentPosition?.toLong() ?: 0,
                if (isPlaying) 1.0f else 0.0f
            )
            .build()

        mediaSession?.setPlaybackState(state)
    }

    private fun updateMetadata(title: String, bitmap: Bitmap?) {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap ?: BitmapFactory.decodeResource(resources, R.drawable.no_image))
            .build()

        mediaSession?.setMetadata(metadata)
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音楽再生",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, device: String, isPlaying: Boolean, bitmap: Bitmap? = null) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("♪ $title")
            .setContentText("接続: $device")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(bitmap ?: BitmapFactory.decodeResource(resources, R.drawable.no_image)) // 🔹ここ
            .addAction(createPlayPauseAction(isPlaying))
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0)
            )
            .setOngoing(true)
            .build()
    private fun createPlayPauseAction(isPlaying: Boolean): NotificationCompat.Action {

        val action = if (isPlaying)
            PlaybackStateCompat.ACTION_PAUSE
        else
            PlaybackStateCompat.ACTION_PLAY

        val pendingIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this,
            action
        )

        val icon = if (isPlaying)
            android.R.drawable.ic_media_pause
        else
            android.R.drawable.ic_media_play

        val title = if (isPlaying) "停止" else "再生"

        return NotificationCompat.Action(icon, title, pendingIntent)
    }

    private fun getAlbumArt(uri: Uri): Bitmap? {
        val projection = arrayOf(MediaStore.Audio.Media.ALBUM_ID)
        val cursor = contentResolver.query(uri, projection, null, null, null)

        cursor?.use {
            if (it.moveToFirst()) {
                val albumId = it.getLong(0)
                val albumArtUri = Uri.parse("content://media/external/audio/albumart/$albumId")

                return try {
                    contentResolver.openInputStream(albumArtUri)?.use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
        return null
    }

    fun resizeBitmap(bitmap: Bitmap): Bitmap {
        return Bitmap.createScaledBitmap(bitmap, 300, 300, true)
    }

    private fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
        return stream.toByteArray()
    }

    fun getCurrentPosition(): Int {
        return try {
            mediaPlayer?.currentPosition ?: 0
        } catch (e: IllegalStateException) {
            0
        }
    }

    fun getDuration(): Int {
        return try {
            mediaPlayer?.duration ?: 0
        } catch (e: IllegalStateException) {
            0
        }
    }

    fun seekTo(position: Int) {
        try {
            mediaPlayer?.seekTo(position)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreate() {
        super.onCreate()

        mediaSession = MediaSessionCompat(this, "MusicService").apply {

            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            setCallback(object : MediaSessionCompat.Callback() {

                override fun onPlay() {
                    Log.d("MediaSession", "▶ onPlay")
                    resumeFromRemote()
                }

                override fun onPause() {
                    Log.d("MediaSession", "⏸ onPause")
                    pauseFromRemote()
                }

                override fun onStop() {
                    Log.d("MediaSession", "■ onStop")
                    stopSelf()
                }
            })

            isActive = true
        }

        instance = this
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
        instance = null
    }



}