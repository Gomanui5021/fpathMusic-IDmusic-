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
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import kotlin.concurrent.thread
import android.media.MediaFormat
import android.media.MediaExtractor
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioDeviceCallback
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothProfile

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

        // フォアグラウンド状態更新用
        const val ACTION_SET_FOREGROUND = "ACTION_SET_FOREGROUND"
        const val EXTRA_IS_FOREGROUND = "EXTRA_IS_FOREGROUND"

        const val CHANNEL_ID = "music_channel"
        var instance: MusicService? = null
        
        // UI側に曲終了を通知するためのコールバック
        var onTrackEnded: (() -> Unit)? = null
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
    private var currentAudioFormat: String = "未取得"
    private var currentAudioCodec: String = "不明"

    private var mediaSession: MediaSessionCompat? = null
    private var bluetoothA2dp: BluetoothA2dp? = null

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

    // タイムアウト監視用
    private var lastInteractionTime = System.currentTimeMillis()
    private var isAppInForeground = false 
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = object : Runnable {
        override fun run() {
            // 再生中は「操作中」とみなしてタイマーを常にリセットする
            if (isPlayingLocal || (mediaPlayer?.isPlaying == true)) {
                updateLastInteractionTime()
            }
            checkTimeout()
            timeoutHandler.postDelayed(this, 30000) // 30秒ごとにチェック
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            notifyStatusChange()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            notifyStatusChange()
        }
    }

    private val bluetoothProfileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.A2DP) {
                bluetoothA2dp = proxy as BluetoothA2dp
                notifyStatusChange()
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                bluetoothA2dp = null
                notifyStatusChange()
            }
        }
    }

    private fun notifyStatusChange() {
        sendControlCommand("OUTPUT_DEVICE:${getCurrentOutputDevice()}")
        sendControlCommand("AUDIO_CODEC:${getCurrentAudioCodec()}")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateLastInteractionTime() {
        lastInteractionTime = System.currentTimeMillis()
    }

    private fun checkTimeout() {
        val currentTime = System.currentTimeMillis()
        val tenMinutes = 10 * 60 * 1000
        // 実際に再生中かどうかを判定
        val isActuallyPlaying = isPlayingLocal || (mediaPlayer?.isPlaying == true)
        
        // バックグラウンド かつ 非再生 かつ 10分間無操作 の場合に切断
        if (!isAppInForeground && !isActuallyPlaying && (currentTime - lastInteractionTime >= tenMinutes)) {
            Log.d("MusicService", "10分間無操作かつ非再生（バックグラウンド）のため、自動切断します")
            BluetoothClient.instance?.disconnect()
            BluetoothServer.instance?.stopServer()
            stopForeground(true)
            stopPlayer()
            stopSelf()
        }
    }

    fun updateCurrentBitmap(bitmap: Bitmap) {
        currentBitmap = bitmap
        updateMetadata(currentTitle ?: "不明", currentBitmap, currentDuration, currentAlbumId)
        updateNotification(isPlayingLocal)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        if (action != null && action != ACTION_UPDATE_STATE && action != ACTION_SET_FOREGROUND) {
            updateLastInteractionTime()
        }

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
                    if (currentAlbumId == newAlbumId && bitmap != null) {
                        currentBitmap = bitmap
                        updateMetadata(currentTitle ?: "不明", currentBitmap, currentDuration, currentAlbumId)
                        updateNotification(isPlayingLocal)
                    }
                }
            }
        }

        when (action) {
            ACTION_SET_FOREGROUND -> {
                isAppInForeground = intent?.getBooleanExtra(EXTRA_IS_FOREGROUND, false) ?: false
                if (isAppInForeground) {
                    updateLastInteractionTime()
                }
            }
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
                stopForeground(true)
                stopPlayer()
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

    fun pauseLocal() {
        updateLastInteractionTime()
        isPlayingLocal = false
        try { mediaPlayer?.pause() } catch (_: Exception) {}
        updatePlaybackState(false, getCurrentPosition().toLong())
        updateNotification(false)
    }

    fun resumeLocal() {
        updateLastInteractionTime()
        isPlayingLocal = true
        try { mediaPlayer?.start() } catch (_: Exception) {}
        updatePlaybackState(true, getCurrentPosition().toLong())
        updateNotification(true)
    }

    private fun stopPlayer() {
        isPlayingLocal = false
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (_: Exception) {}
    }

    private fun handlePlay(uriString: String?) {
        try {
            stopPlayer()
            uriString?.let {
                val uri = Uri.parse(it)
                
                // オーディオフォーマット情報の取得
                thread {
                    currentAudioFormat = getAudioFormatInfo(uri)
                    sendControlCommand("AUDIO_FORMAT:$currentAudioFormat")
                    sendControlCommand("AUDIO_CODEC:${getCurrentAudioCodec()}")
                }

                contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(fd.fileDescriptor)
                        prepare()
                        currentDuration = duration.toLong()
                        setOnCompletionListener {
                            Handler(Looper.getMainLooper()).postDelayed({ 
                                isPlayingLocal = false
                                updatePlaybackState(false, currentDuration)
                                updateNotification(false)
                                sendControlCommand("STATE:PAUSED")
                                onTrackEnded?.invoke()
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

    private fun getAudioFormatInfo(uri: Uri): String {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(this, uri, null)
            val format = extractor.getTrackFormat(0)
            
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "不明"
            currentAudioCodec = mime.substringAfterLast("/").uppercase()

            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
            
            // ビット深度の取得 (FLACなどの場合)
            var bitDepth = 16 // デフォルト
            if (format.containsKey("bits-per-sample")) {
                bitDepth = format.getInteger("bits-per-sample")
            } else if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                val encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                bitDepth = when (encoding) {
                    android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
                    android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
                    android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
                    else -> 16
                }
            }
            
            val srText = if (sampleRate >= 1000) "${sampleRate / 1000}kHz" else "${sampleRate}Hz"
            return "$srText/${bitDepth}bit"
        } catch (e: Exception) {
            currentAudioCodec = "不明"
            return "不明"
        } finally {
            extractor.release()
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(1, notification)
        }
    }

    private fun createNotification(title: String, device: String, isPlaying: Boolean, bitmap: Bitmap?): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(device)
            .setSmallIcon(R.drawable.small_logo)
            .setLargeIcon(bitmap ?: noImageBitmap)
            .setContentIntent(pendingIntent)
            .addAction(createPreviousAction())
            .addAction(createPlayPauseAction(isPlaying))
            .addAction(createNextAction())
            .setStyle(MediaStyle().setMediaSession(mediaSession?.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .setOngoing(isPlaying)
            .build()
    }

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
    
    fun seekTo(position: Int) {
        updateLastInteractionTime()
        try { mediaPlayer?.seekTo(position) } catch (e: Exception) { Log.e("MusicService", "Seek Error", e) }
    }

    fun getCurrentAudioFormat(): String = currentAudioFormat
    
    private fun mapCodecTypeToString(type: Int): String {
        return when (type) {
            0 -> "SBC"
            1 -> "AAC"
            2 -> "aptX"
            3 -> "aptX HD"
            4 -> "LDAC"
            5 -> "aptX Adaptive"
            6 -> "Opus"
            7 -> "LC3 (LE Audio)"
            // 一部の端末やQualcomm系で 8 以降に aptX TWS+ などが入る場合があります
            else -> "Unknown ($type)"
        }
    }

    fun getCurrentAudioCodec(): String {
        if (!isExternalOutputConnected()) {
            return "No connect"
        }
        
        // Bluetooth A2DPコーデック情報の取得を試みる
        bluetoothA2dp?.let { a2dp ->
            try {
                val getCodecStatusMethod = a2dp.javaClass.getMethod("getCodecStatus", android.bluetooth.BluetoothDevice::class.java)
                val getActiveDeviceMethod = a2dp.javaClass.getMethod("getActiveDevice")
                val activeDevice = getActiveDeviceMethod.invoke(a2dp) as? android.bluetooth.BluetoothDevice
                
                if (activeDevice != null) {
                    val codecStatus = getCodecStatusMethod.invoke(a2dp, activeDevice)
                    if (codecStatus != null) {
                        val getCodecConfigMethod = codecStatus.javaClass.getMethod("getCodecConfig")
                        val codecConfig = getCodecConfigMethod.invoke(codecStatus)
                        if (codecConfig != null) {
                            val getCodecTypeMethod = codecConfig.javaClass.getMethod("getCodecType")
                            val type = getCodecTypeMethod.invoke(codecConfig) as Int
                            return mapCodecTypeToString(type)
                        }
                    }
                }
            } catch (e: Exception) {
                // Log.e("MusicService", "Failed to get BT codec via reflection", e)
            }
        }

        // Bluetooth以外、または取得失敗時はソースファイルのコーデックを表示
        return if (currentAudioCodec == "RAW") "PCM" else currentAudioCodec
    }

    private fun isExternalOutputConnected(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET -> return true
            }
        }
        return false
    }

    fun getCurrentOutputDevice(): String {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET -> {
                    return device.productName?.toString() ?: "外部出力"
                }
            }
        }
        return "端末スピーカー"
    }

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
        
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
        
        @Suppress("DEPRECATION")
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        btAdapter?.getProfileProxy(this, bluetoothProfileListener, BluetoothProfile.A2DP)

        instance = this
        updatePlaybackState(false, 0)
        timeoutHandler.post(timeoutRunnable)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "音楽再生", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        timeoutHandler.removeCallbacks(timeoutRunnable)
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        
        @Suppress("DEPRECATION")
        BluetoothAdapter.getDefaultAdapter()?.closeProfileProxy(BluetoothProfile.A2DP, bluetoothA2dp)

        stopPlayer()
        mediaSession?.isActive = false
        mediaSession?.release()
        instance = null
        super.onDestroy()
    }
}