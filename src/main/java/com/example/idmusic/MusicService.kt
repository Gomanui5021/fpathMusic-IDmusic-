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
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.graphics.Bitmap
import android.support.v4.media.MediaMetadataCompat
import android.graphics.BitmapFactory
import android.provider.MediaStore

class MusicService : Service() {

    companion object {
        const val ACTION_PLAY = "ACTION_PLAY"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_STOP = "STOP"
        const val ACTION_SEEK = "ACTION_SEEK"
        
        // リモート（Bluetooth）から受信した時専用のアクション（ループ防止）
        const val ACTION_REMOTE_PAUSE = "ACTION_REMOTE_PAUSE"
        const val ACTION_REMOTE_RESUME = "ACTION_REMOTE_RESUME"
        const val ACTION_UPDATE_STATE = "ACTION_UPDATE_STATE"

        const val CHANNEL_ID = "music_channel"
        var instance: MusicService? = null
    }

    private var mediaPlayer: MediaPlayer? = null
    private var currentTitle: String? = null
    private var currentDevice: String? = null
    private var currentBitmap: Bitmap? = null
    private var isPlayingLocal = false

    private var mediaSession: MediaSessionCompat? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        val action = intent?.action
        val newTitle = intent?.getStringExtra("TITLE")
        val newDevice = intent?.getStringExtra("DEVICE")
        
        if (newTitle != null) currentTitle = newTitle
        if (newDevice != null) currentDevice = newDevice

        when (action) {
            ACTION_PLAY -> {
                val uriString = intent?.getStringExtra("MUSIC_URI")
                handlePlay(uriString)
                sendControlCommand("PLAY:$uriString")
            }
            ACTION_PAUSE -> {
                pauseLocal()
                sendControlCommand("PAUSE")
            }
            ACTION_RESUME -> {
                resumeLocal()
                sendControlCommand("RESUME")
            }
            ACTION_REMOTE_PAUSE -> {
                pauseLocal()
            }
            ACTION_REMOTE_RESUME -> {
                resumeLocal()
            }
            ACTION_UPDATE_STATE -> {
                val playing = intent.getBooleanExtra("IS_PLAYING", false)
                val pos = intent.getIntExtra("POSITION", -1)
                isPlayingLocal = playing
                updatePlaybackState(playing, if (pos != -1) pos.toLong() else getCurrentPosition().toLong())
                updateMetadata(currentTitle ?: "不明", currentBitmap)
                updateNotification(playing)
            }
            ACTION_SEEK -> {
                val pos = intent.getIntExtra("SEEK_POS", 0)
                seekTo(pos)
                sendControlCommand("SEEK:$pos")
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun pauseLocal() {
        isPlayingLocal = false
        mediaPlayer?.pause()
        updatePlaybackState(false, getCurrentPosition().toLong())
        updateNotification(false)
    }

    private fun resumeLocal() {
        isPlayingLocal = true
        mediaPlayer?.start()
        updatePlaybackState(true, getCurrentPosition().toLong())
        updateNotification(true)
    }

    private fun handlePlay(uriString: String?) {
        try {
            mediaPlayer?.release()
            uriString?.let {
                val uri = Uri.parse(it)
                contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(fd.fileDescriptor)
                        prepare()
                        start()
                    }
                    isPlayingLocal = true
                    updatePlaybackState(true, 0)
                    updateNotification(true)
                }
            }
        } catch (e: Exception) {
            Log.e("MusicService", "再生エラー", e)
        }
    }

    private fun sendControlCommand(command: String) {
        BluetoothClient.instance?.sendMessage(command)
        BluetoothServer.instance?.send(command)
    }

    fun updatePlaybackState(isPlaying: Boolean, position: Long) {
        val state = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SEEK_TO)
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                position,
                if (isPlaying) 1f else 0f
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

    private fun updateNotification(isPlaying: Boolean) {
        val notification = createNotification(currentTitle ?: "不明", currentDevice ?: "接続中", isPlaying, currentBitmap)
        startForeground(1, notification)
    }

    private fun createNotification(title: String, device: String, isPlaying: Boolean, bitmap: Bitmap?) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(device)
            .setSmallIcon(R.drawable.small_logo)
            .setLargeIcon(bitmap ?: BitmapFactory.decodeResource(resources, R.drawable.no_image))
            .addAction(createPlayPauseAction(isPlaying))
            .setStyle(MediaStyle().setMediaSession(mediaSession?.sessionToken).setShowActionsInCompactView(0))
            .setOngoing(isPlaying)
            .build()

    private fun createPlayPauseAction(isPlaying: Boolean): NotificationCompat.Action {
        val actionIntent = Intent(this, MusicService::class.java).apply {
            action = if (isPlaying) ACTION_PAUSE else ACTION_RESUME
        }
        val pendingIntent = PendingIntent.getService(this, 0, actionIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (isPlaying) "一時停止" else "再生",
            pendingIntent
        )
    }

    fun isPlaying(): Boolean = isPlayingLocal
    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0
    fun getDuration(): Int = mediaPlayer?.duration ?: 0
    fun seekTo(position: Int) { mediaPlayer?.seekTo(position) }

    fun pauseFromRemote() {
        val intent = Intent(this, MusicService::class.java).apply { action = ACTION_REMOTE_PAUSE }
        startService(intent)
    }

    fun resumeFromRemote() {
        val intent = Intent(this, MusicService::class.java).apply { action = ACTION_REMOTE_RESUME }
        startService(intent)
    }

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "MusicService").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    val intent = Intent(this@MusicService, MusicService::class.java).apply { action = ACTION_RESUME }
                    startService(intent)
                }
                override fun onPause() {
                    val intent = Intent(this@MusicService, MusicService::class.java).apply { action = ACTION_PAUSE }
                    startService(intent)
                }
                override fun onSeekTo(pos: Long) {
                    val intent = Intent(this@MusicService, MusicService::class.java).apply {
                        action = ACTION_SEEK
                        putExtra("SEEK_POS", pos.toInt())
                    }
                    startService(intent)
                }
            })
            isActive = true
        }
        instance = this
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "音楽再生", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        mediaSession?.release()
        instance = null
        super.onDestroy()
    }
}