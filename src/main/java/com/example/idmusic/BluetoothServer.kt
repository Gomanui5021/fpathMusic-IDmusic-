//BluetoothServer.kt
package com.example.idmusic

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import kotlin.concurrent.thread
import com.example.idmusic.MusicService
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import android.media.AudioManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.content.ContentUris
import android.util.Base64
import java.io.ByteArrayOutputStream
import android.media.MediaMetadataRetriever
import android.os.Build

class BluetoothServer(private val context: Context) : Thread() {

    companion object {
        var instance: BluetoothServer? = null
    }

    private val adapter = BluetoothAdapter.getDefaultAdapter()
    private val uuid = UUID.fromString("c8f9f668-17b1-4d3a-ba34-68f397619315")
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var lastState: String? = null
    private var isRunning = true

    private var serverSocket: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    
    private val sendExecutor = Executors.newSingleThreadExecutor()
    private val sentAlbumArts = Collections.synchronizedSet(mutableSetOf<Long>())

    var onConnected: (() -> Unit)? = null
    var onPlay: ((String) -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
    var onAutoSkipSync: ((Boolean) -> Unit)? = null // 設定同期用
    var onClientNameReceived: ((String) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    private var musicListToSend: List<MusicItem> = emptyList()

    fun setMusicList(list: List<MusicItem>) {
        musicListToSend = list
    }

    fun sendMessage(message: String) {
        if (!isRunning) return
        sendExecutor.execute {
            try {
                synchronized(this) {
                    writer?.let {
                        it.write(message + "\n")
                        it.flush()
                    }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e("BT", "sendMessage Error", e)
            }
        }
    }

    private fun sendMusicList(musicList: List<MusicItem>) {
        sendExecutor.execute {
            try {
                musicList.forEach {
                    if (!isRunning) return@execute
                    val msg = "ITEM:${it.folder}||${it.title}||${it.uri}||${it.path}||${it.storage}||${it.albumId}"
                    synchronized(this) {
                        writer?.write(msg + "\n")
                    }
                }
                if (!isRunning) return@execute
                synchronized(this) {
                    writer?.write("END\n")
                    writer?.flush()
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e("BT", "sendMusicList Error", e)
                    stopServer()
                }
            }
        }
    }

    override fun run() {
        try {
            instance = this
            serverSocket = adapter?.listenUsingRfcommWithServiceRecord("IDMusic", uuid)
            socket = serverSocket?.accept() ?: return
            
            synchronized(this) {
                writer = BufferedWriter(OutputStreamWriter(socket?.outputStream, Charsets.UTF_8))
                reader = BufferedReader(InputStreamReader(socket?.inputStream, Charsets.UTF_8))
            }
            
            try { serverSocket?.close() } catch(_: Exception) {}
            serverSocket = null

            onConnected?.invoke()
            sentAlbumArts.clear()
            
            // 接続時にサーバー側の音量を送信
            sendCurrentVolume()
            
            sendMusicList(musicListToSend)
            listenIncoming()
            startStatusSender()
        } catch (e: Exception) {
            if (isRunning) Log.e("BT", "Server Run Error", e)
            stopServer()
        }
    }

    fun sendProgress(position: Int, duration: Int) {
        if (duration <= 0) return
        sendMessage("PROGRESS:$position||$duration")
    }

    fun sendCurrentVolume() {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        sendMessage("VOLUME:$current||$max")
    }

    private fun listenIncoming() {
        thread {
            try {
                while (isRunning) {
                    val line = reader?.readLine() ?: break
                    if (line.isNotEmpty()) handleMessage(line)
                }
            } catch (e: Exception) {
                Log.e("BT", "Listen Error", e)
            } finally {
                onDisconnected?.invoke()
                stopServer()
            }
        }
    }

    private fun handleMessage(message: String) {
        when {
            message.startsWith("NAME:") -> {
                val clientName = message.removePrefix("NAME:")
                onClientNameReceived?.invoke(clientName)
            }
            message.startsWith("PLAY:") -> {
                val path = message.removePrefix("PLAY:")
                sendState("PLAYING")
                val intent = Intent(context, MusicService::class.java).apply {
                    action = MusicService.ACTION_PLAY
                    putExtra("MUSIC_URI", path)
                }
                context.startService(intent)
                onPlay?.invoke(path)
                
                // 再生開始時にアルバムアートを送信（未送信の場合）
                musicListToSend.find { it.uri.toString() == path }?.let {
                    sendAlbumArt(it.albumId, it.uri.toString())
                }
            }
            message == "PAUSE" -> {
                sendState("PAUSED")
                val intent = Intent(context, MusicService::class.java).apply { action = MusicService.ACTION_REMOTE_PAUSE }
                context.startService(intent)
                onPlay?.invoke("PAUSED")
            }
            message == "RESUME" -> {
                sendState("PLAYING")
                val intent = Intent(context, MusicService::class.java).apply { action = MusicService.ACTION_REMOTE_RESUME }
                context.startService(intent)
                onPlay?.invoke("RESUME")
            }
            message == "NEXT" -> onNext?.invoke()
            message == "PREVIOUS" -> onPrevious?.invoke()
            message.startsWith("SET_STOP_EACH:") -> {
                val enabled = message.removePrefix("SET_STOP_EACH:") == "ON"
                onAutoSkipSync?.invoke(enabled)
            }
            message.startsWith("SEEK:") -> {
                val pos = message.removePrefix("SEEK:").toIntOrNull() ?: 0
                val intent = Intent(context, MusicService::class.java).apply {
                    action = MusicService.ACTION_SEEK
                    putExtra("SEEK_POS", pos)
                }
                context.startService(intent)
            }
            message.startsWith("VOLUME_SET:") -> {
                val vol = message.removePrefix("VOLUME_SET:").toIntOrNull() ?: return
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
            }
            message.startsWith("GET_ART:") -> {
                val parts = message.removePrefix("GET_ART:").split("||")
                if (parts.size == 2) {
                    sendAlbumArt(parts[0].toLongOrNull() ?: -1L, parts[1])
                }
            }
            message == "DISCONNECT" -> stopServer()
        }
    }

    fun send(message: String) = sendMessage(message)

    fun sendAlbumArt(albumId: Long, uriString: String?) {
        if (albumId <= 0 && uriString == null) return
        if (sentAlbumArts.contains(albumId) && albumId > 0) return

        sendExecutor.execute {
            try {
                var bitmap: Bitmap? = null
                if (albumId > 0) {
                    try {
                        val artworkUri = Uri.parse("content://media/external/audio/albumart")
                        val uri = ContentUris.withAppendedId(artworkUri, albumId)
                        context.contentResolver.openInputStream(uri)?.use { 
                            bitmap = BitmapFactory.decodeStream(it)
                        }
                    } catch (e: Exception) {}
                }
                
                if (bitmap == null && uriString != null) {
                    try {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(context, Uri.parse(uriString))
                        val art = retriever.embeddedPicture
                        if (art != null) {
                            bitmap = BitmapFactory.decodeByteArray(art, 0, art.size)
                        }
                        retriever.release()
                    } catch (e: Exception) {}
                }

                bitmap?.let {
                    // 画像を圧縮 (300x300程度にリサイズ)
                    val size = 300
                    val scaled = if (it.width > size || it.height > size) {
                        val scale = size.toFloat() / Math.max(it.width, it.height)
                        Bitmap.createScaledBitmap(it, (it.width * scale).toInt(), (it.height * scale).toInt(), true)
                    } else it
                    
                    val out = ByteArrayOutputStream()
                    // WebP または JPEG で圧縮 (容量を減らす)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, 75, out)
                    } else {
                        scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
                    }
                    val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    
                    sendMessage("ART:$albumId||$base64")
                    if (albumId > 0) sentAlbumArts.add(albumId)
                }
            } catch (e: Exception) {
                Log.e("BT", "Send Art Error", e)
            }
        }
    }

    fun pauseMusic() {
        sendState("PAUSED")
        val intent = Intent(context, MusicService::class.java).apply { action = MusicService.ACTION_PAUSE }
        context.startService(intent)
    }

    fun resumeMusic() {
        sendState("PLAYING")
        val intent = Intent(context, MusicService::class.java).apply { action = MusicService.ACTION_RESUME }
        context.startService(intent)
    }

    fun stopServer() {
        if (!isRunning) return
        isRunning = false
        instance = null
        
        thread {
            synchronized(this) {
                try {
                    serverSocket?.close()
                    socket?.close()
                    reader?.close()
                    writer?.close()
                } catch (_: Exception) {}
                serverSocket = null
                socket = null
                reader = null
                writer = null
            }
            try { sendExecutor.shutdownNow() } catch(_: Exception) {}
        }
    }

    fun seekTo(position: Int) {
        val intent = Intent(context, MusicService::class.java).apply {
            action = MusicService.ACTION_SEEK
            putExtra("SEEK_POS", position)
        }
        context.startService(intent)
        send("SEEK:$position")
    }

    fun sendState(state: String) {
        if (state == lastState) return
        lastState = state
        sendMessage("STATE:$state")
    }

    private fun startStatusSender() {
        thread {
            var lastSentVol = -1
            while (isRunning) {
                try {
                    // 進捗送信
                    val service = MusicService.instance
                    if (service != null && service.isPlaying()) {
                        val pos = service.getCurrentPosition()
                        val dur = service.getDuration()
                        if (dur > 0) sendProgress(pos, dur)
                    }

                    // 音量同期
                    val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (currentVol != lastSentVol) {
                        sendCurrentVolume()
                        lastSentVol = currentVol
                    }
                } catch (e: Exception) {
                    if (isRunning) Log.e("BT", "Status Sender Error", e)
                }
                Thread.sleep(500)
            }
        }
    }
}