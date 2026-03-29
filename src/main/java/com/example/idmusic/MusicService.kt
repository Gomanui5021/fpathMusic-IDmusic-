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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        Log.d("MusicService", "🔥 onStartCommand 呼ばれた action: ${intent?.action}")


        createNotificationChannel()

        val uriString = intent?.getStringExtra("MUSIC_URI")

        val newTitle = intent?.getStringExtra("TITLE")
        val newDevice = intent?.getStringExtra("DEVICE")

        if (newTitle != null) currentTitle = newTitle
        if (newDevice != null) currentDevice = newDevice

        startForeground(
            1,
            createNotification(
                currentTitle ?: "不明",
                currentDevice ?: "未接続",
                mediaPlayer?.isPlaying == true
            )
        )

        when (intent?.action) {

            ACTION_PLAY -> {
                try {
                    mediaPlayer?.release()

                    if (uriString != null) {
                        val uri = Uri.parse(uriString)

                        val fd = contentResolver.openFileDescriptor(uri, "r")

                        if (fd == null) {
                            Log.e("MusicService", "FD取得失敗: $uri")
                            return START_NOT_STICKY
                        }

                        mediaPlayer = MediaPlayer().apply {
                            setDataSource(fd.fileDescriptor)
                            fd.close()

                            prepare()
                            start()
                        }

                        updateNotification(true)

                        sendControlCommand("STATE:PLAYING")
                    }

                } catch (e: Exception) {
                    Log.e("MusicService", "再生エラー", e)
                }
            }

            ACTION_PAUSE -> {
                Log.d("MusicService", "⏸ PAUSE押された")
                mediaPlayer?.pause()
                updateNotification(false)

                sendControlCommand("STATE:PAUSED")
            }

            ACTION_RESUME -> {
                Log.d("MusicService", "▶ RESUME押された")
                try {
                    mediaPlayer?.start()
                    updateNotification(true)

                    sendControlCommand("STATE:PLAYING")
                } catch (e: Exception) {
                    Log.e("MusicService", "再開エラー", e)
                }
            }

            ACTION_STOP -> {
                Log.d("MusicService", "■ STOP押された")
                mediaPlayer?.stop()
                mediaPlayer?.reset()
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
            BluetoothClient.instance?.sendMessage(command)
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
        val notification = createNotification(
            currentTitle ?: "不明",
            currentDevice ?: "未接続",
            isPlaying
        )

        // 🔥 notifyじゃなくてこれ
        startForeground(1, notification)
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

    private fun createNotification(title: String, device: String, isPlaying: Boolean) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("♪ $title")
            .setContentText("接続: $device")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(createPlayPauseAction(isPlaying))
            .setOngoing(true)
            .build()

    private fun createPlayPauseAction(isPlaying: Boolean): NotificationCompat.Action {

        val action = if (isPlaying)
            MusicService.ACTION_PAUSE
        else
            MusicService.ACTION_PLAY

        val intent = Intent(applicationContext, MusicService::class.java).apply {
            this.action = action
        }

        val pendingIntent = PendingIntent.getService(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val icon = if (isPlaying)
            android.R.drawable.ic_media_pause
        else
            android.R.drawable.ic_media_play

        val title = if (isPlaying) "停止" else "再生"

        return NotificationCompat.Action(icon, title, pendingIntent)
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
        instance = this
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
        instance = null
    }
}