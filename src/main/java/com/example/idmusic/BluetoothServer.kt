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
            message == "DISCONNECT" -> stopServer()
        }
    }

    fun send(message: String) = sendMessage(message)

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