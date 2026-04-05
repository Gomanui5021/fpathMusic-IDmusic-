//MusicService.kt
package com.example.idmusic

import android.app.Service
import android.content.Intent
import android.content.Context
import android.content.pm.ServiceInfo
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
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.content.ContentUris
import android.util.Size
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import kotlin.concurrent.thread

class MusicService : Service() {

    companion object {
        const val ACTION_PLAY = "ACTION_PLAY"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_STOP = "STOP"
        const val ACTION_SEEK = "ACTION_SEEK"
        const val ACTION_NEXT = "ACTION_NEXT"
        const val ACTION_PREVIOUS = "ACTION_PREVIOUS"
        
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
    private var currentDuration = 0L
    private var isPlayingLocal = false
    private var currentAlbumId: Long = -1L
    private var currentUri: String? = null
    private var noImageBitmap: Bitmap? = null

    private var mediaSession: MediaSessionCompat? = null

    // メタデータ更新用のキャッシュ
    private var lastMetadataTitle: String? = null
    private var lastMetadataDuration: Long = -1L
    private var lastMetadataAlbumId: Long = -1L
    private var lastMetadataBitmap: Bitmap? = null

    // 通知更新用の独立したキャッシュ
    private var lastNotifiedTitle: String? = null
    private var lastNotifiedIsPlaying: Boolean? = null
    private var lastNotifiedDevice: String? = null
    private var lastNotifiedAlbumId: Long = -1L
    
    private var lastPlaybackState: Int = -1
    private var lastPlaybackPosition: Long = -1L
    private var lastPlaybackUpdateTime: Long = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val newTitle = intent?.getStringExtra("TITLE")
        val newDevice = intent?.getStringExtra("DEVICE")
        val newAlbumId = intent?.getLongExtra("ALBUM_ID", -1L) ?: -1L
        
        if (newTitle != null) currentTitle = newTitle
        if (newDevice != null) currentDevice = newDevice
        
        if (newAlbumId != -1L && currentAlbumId != newAlbumId) {
            currentAlbumId = newAlbumId
            currentBitmap = null 
            
            val musicUri = intent?.getStringExtra("MUSIC_URI")
            thread {
                val bitmap = getAlbumArtBitmap(newAlbumId, musicUri)
                Handler(Looper.getMainLooper()).post {
                    if (currentAlbumId == newAlbumId) {
                        currentBitmap = bitmap
                        updateMetadata(currentTitle ?: "不明", currentBitmap, currentDuration, currentAlbumId)
                        updateNotification(isPlayingLocal)
                    }
                }
            }
        }

        when (action) {
            ACTION_PLAY -> {
                val uriString = intent?.getStringExtra("MUSIC_URI")
                currentUri = uriString
                handlePlay(uriString)
                sendControlCommand("PLAY:$uriString")
                sendControlCommand("STATE:PLAYING")
            }
            ACTION_PAUSE -> {
                pauseLocal()
                sendControlCommand("PAUSE")
                sendControlCommand("STATE:PAUSED")
            }
            ACTION_RESUME -> {
                resumeLocal()
                sendControlCommand("RESUME")
                sendControlCommand("STATE:PLAYING")
            }
            ACTION_NEXT -> sendControlCommand("NEXT")
            ACTION_PREVIOUS -> sendControlCommand("PREVIOUS")
            ACTION_REMOTE_PAUSE -> pauseLocal()
            ACTION_REMOTE_RESUME -> resumeLocal()
            ACTION_UPDATE_STATE -> {
                val playing = intent.getBooleanExtra("IS_PLAYING", false)
                val pos = intent.getIntExtra("POSITION", -1)
                val dur = intent.getIntExtra("DURATION", -1)
                
                isPlayingLocal = playing
                if (dur != -1 && dur > 0) currentDuration = dur.toLong()
                
                updatePlaybackState(playing, if (pos != -1) pos.toLong() else getCurrentPosition().toLong())
                updateMetadata(currentTitle ?: "不明", currentBitmap, currentDuration, currentAlbumId)
                updateNotification(playing)
            }
            ACTION_SEEK -> {
                val pos = intent.getIntExtra("SEEK_POS", 0)
                seekTo(pos)
                sendControlCommand("SEEK:$pos")
                lastPlaybackState = -1 
                updatePlaybackState(isPlayingLocal, pos.toLong())
                updateNotification(isPlayingLocal)
            }
            ACTION_STOP -> {
                pauseLocal()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun getAlbumArtBitmap(albumId: Long, uriString: String?): Bitmap? {
        var bitmap: Bitmap? = null
        if (albumId > 0) {
            try {
                val artworkUri = Uri.parse("content://media/external/audio/albumart")
                val uri = ContentUris.withAppendedId(artworkUri, albumId)
                contentResolver.openInputStream(uri)?.use { 
                    bitmap = BitmapFactory.decodeStream(it)
                }
            } catch (e: Exception) {}
        }
        
        if (bitmap == null && uriString != null) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, Uri.parse(uriString))
                val art = retriever.embeddedPicture
                if (art != null) {
                    bitmap = BitmapFactory.decodeByteArray(art, 0, art.size)
                }
                retriever.release()
            } catch (e: Exception) {}
        }
        
        return bitmap?.let {
            val maxSize = 512
            if (it.width > maxSize || it.height > maxSize) {
                val scale = maxSize.toFloat() / Math.max(it.width, it.height)
                Bitmap.createScaledBitmap(it, (it.width * scale).toInt(), (it.height * scale).toInt(), true)
            } else it
        }
    }

    private fun pauseLocal() {
        isPlayingLocal = false
        try { mediaPlayer?.pause() } catch (_: Exception) {}
        updatePlaybackState(false, getCurrentPosition().toLong())
        updateNotification(false)
    }

    private fun resumeLocal() {
        isPlayingLocal = true
        try { mediaPlayer?.start() } catch (_: Exception) {}
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
                        currentDuration = duration.toLong()
                        setOnCompletionListener {
                            Handler(Looper.getMainLooper()).postDelayed({ 
                                // 曲が終わったら一旦停止状態にしてから次へ送る
                                isPlayingLocal = false
                                updatePlaybackState(false, currentDuration)
                                updateNotification(false)
                                sendControlCommand("NEXT") 
                            }, 500)
                        }
                        start()
                    }
                    isPlayingLocal = true
                    updatePlaybackState(true, 0)
                    updateMetadata(currentTitle ?: "不明", currentBitmap, currentDuration, currentAlbumId)
                    updateNotification(true)
                }
            }
        } catch (e: Exception) {
            Log.e("MusicService", "再生エラー", e)
        }
    }

    private fun sendControlCommand(command: String) {
        if (BluetoothClient.instance != null) BluetoothClient.instance?.sendMessage(command)
        if (BluetoothServer.instance != null) BluetoothServer.instance?.send(command)
    }

    fun updatePlaybackState(isPlaying: Boolean, position: Long) {
        val newState = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val now = System.currentTimeMillis()

        if (newState == lastPlaybackState && newState == PlaybackStateCompat.STATE_PLAYING) {
            val expectedPos = lastPlaybackPosition + (now - lastPlaybackUpdateTime)
            if (Math.abs(position - expectedPos) < 2000) return
        } else if (newState == lastPlaybackState && newState == PlaybackStateCompat.STATE_PAUSED) {
            if (Math.abs(position - lastPlaybackPosition) < 1000) return
        }

        lastPlaybackState = newState
        lastPlaybackPosition = position
        lastPlaybackUpdateTime = now

        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or 
                PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or 
                PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or 
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(newState, position, if (isPlaying) 1f else 0f, SystemClock.elapsedRealtime())
            .build()
        mediaSession?.setPlaybackState(state)
    }

    private fun updateMetadata(title: String, bitmap: Bitmap?, duration: Long, albumId: Long) {
        if (title == lastMetadataTitle && duration == lastMetadataDuration && 
            albumId == lastMetadataAlbumId && bitmap == lastMetadataBitmap) return
            
        lastMetadataTitle = title
        lastMetadataDuration = duration
        lastMetadataAlbumId = albumId
        lastMetadataBitmap = bitmap

        val art = bitmap ?: noImageBitmap
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentDevice ?: "Unknown")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, art)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)
            .build()
        mediaSession?.setMetadata(metadata)
    }

    private fun updateNotification(isPlaying: Boolean) {
        if (currentTitle == lastNotifiedTitle && isPlaying == lastNotifiedIsPlaying && 
            currentDevice == lastNotifiedDevice && currentAlbumId == lastNotifiedAlbumId) return

        lastNotifiedIsPlaying = isPlaying
        lastNotifiedDevice = currentDevice
        lastNotifiedTitle = currentTitle
        lastNotifiedAlbumId = currentAlbumId

        val notification = createNotification(currentTitle ?: "不明", currentDevice ?: "接続中", isPlaying, currentBitmap)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(1, notification)
        }
    }

    private fun createNotification(title: String, device: String, isPlaying: Boolean, bitmap: Bitmap?) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(device)
            .setSmallIcon(R.drawable.small_logo)
            .setLargeIcon(bitmap ?: noImageBitmap)
            .addAction(createPreviousAction())
            .addAction(createPlayPauseAction(isPlaying))
            .addAction(createNextAction())
            .setStyle(MediaStyle().setMediaSession(mediaSession?.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .setOngoing(isPlaying)
            .build()

    private fun createPlayPauseAction(isPlaying: Boolean): NotificationCompat.Action {
        val actionIntent = Intent(this, MusicService::class.java).apply { action = if (isPlaying) ACTION_PAUSE else ACTION_RESUME }
        val pendingIntent = PendingIntent.getService(this, 1, actionIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (isPlaying) "一時停止" else "再生", pendingIntent)
    }

    private fun createNextAction(): NotificationCompat.Action {
        val actionIntent = Intent(this, MusicService::class.java).apply { action = ACTION_NEXT }
        val pendingIntent = PendingIntent.getService(this, 2, actionIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action(android.R.drawable.ic_media_next, "次へ", pendingIntent)
    }

    private fun createPreviousAction(): NotificationCompat.Action {
        val actionIntent = Intent(this, MusicService::class.java).apply { action = ACTION_PREVIOUS }
        val pendingIntent = PendingIntent.getService(this, 3, actionIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action(android.R.drawable.ic_media_previous, "前へ", pendingIntent)
    }

    fun isPlaying(): Boolean = isPlayingLocal
    fun getCurrentPosition(): Int = try { mediaPlayer?.currentPosition ?: 0 } catch (e: Exception) { 0 }
    fun getDuration(): Int = try { val d = mediaPlayer?.duration ?: 0; if (d < 0) 0 else d } catch (e: Exception) { 0 }
    fun seekTo(position: Int) = try { mediaPlayer?.seekTo(position) } catch (e: Exception) { Log.e("MusicService", "Seek Error", e) }

    override fun onCreate() {
        super.onCreate()
        noImageBitmap = BitmapFactory.decodeResource(resources, R.drawable.no_image)
        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, "MusicService").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { startService(Intent(this@MusicService, MusicService::class.java).apply { action = ACTION_RESUME }) }
                override fun onPause() { startService(Intent(this@MusicService, MusicService::class.java).apply { action = ACTION_PAUSE }) }
                override fun onSkipToNext() { startService(Intent(this@MusicService, MusicService::class.java).apply { action = ACTION_NEXT }) }
                override fun onSkipToPrevious() { startService(Intent(this@MusicService, MusicService::class.java).apply { action = ACTION_PREVIOUS }) }
                override fun onStop() { startService(Intent(this@MusicService, MusicService::class.java).apply { action = ACTION_STOP }) }
                override fun onSeekTo(pos: Long) { startService(Intent(this@MusicService, MusicService::class.java).apply { action = ACTION_SEEK; putExtra("SEEK_POS", pos.toInt()) }) }
            })
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            isActive = true
        }
        instance = this
        updatePlaybackState(false, 0)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "音楽再生", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaSession?.isActive = false
        mediaSession?.release()
        instance = null
        super.onDestroy()
    }
}