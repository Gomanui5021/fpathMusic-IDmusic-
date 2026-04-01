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
import kotlin.text.Charsets
import com.example.idmusic.MusicService

class BluetoothServer(private val context: Context) : Thread() {

    companion object {
        var instance: BluetoothServer? = null
    }

    private val adapter = BluetoothAdapter.getDefaultAdapter()
    private val uuid = UUID.fromString("c8f9f668-17b1-4d3a-ba34-68f397619315")

    private var lastState: String? = null

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    var onConnected: (() -> Unit)? = null
    var onPlay: ((String) -> Unit)? = null
    var onDeviceNameReceived: ((String) -> Unit)? = null

    var onDisconnected: (() -> Unit)? = null

    var onProgress: ((Int, Int) -> Unit)? = null

    private var musicListToSend: List<MusicItem> = emptyList()
    private var receiveBuffer = ""

    fun setMusicList(list: List<MusicItem>) {
        musicListToSend = list
    }

    fun sendMessage(message: String) {
        try {
            outputStream?.write((message + "\n").toByteArray())
            outputStream?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendMusicList(musicList: List<MusicItem>) {
        thread {
            try {
                musicList.forEach {
                    val msg = "ITEM:${it.folder}||${it.title}||${it.uri}||${it.path}||${it.storage}\n"
                    outputStream?.write(msg.toByteArray())
                }
                outputStream?.write("END\n".toByteArray())
                outputStream?.flush()

                Log.d("BT", "全曲送信完了")
            } catch (e: Exception) {
                Log.e("BT", "曲リスト送信失敗", e)
            }
        }
    }

    override fun run() {
        try {
            instance = this
            val serverSocket =
                adapter?.listenUsingRfcommWithServiceRecord("IDMusic", uuid)

            Log.d("BT", "サーバー待機中...")

            socket = serverSocket?.accept() ?: return

            outputStream = socket?.outputStream
            inputStream = socket?.inputStream
            serverSocket?.close()

            Log.d("BT", "接続成功")
            onConnected?.invoke()

            sendMusicList(musicListToSend)

            listenIncoming()

            // 👇 これ追加（超重要）
            startProgressSender()

        } catch (e: Exception) {
            Log.e("BT", "サーバー例外", e)
        }
    }

    fun sendProgress(position: Int, duration: Int) {
        try {
            val msg = "PROGRESS:$position||$duration\n"
            outputStream?.write(msg.toByteArray())
            outputStream?.flush()

            Log.d("BT", "PROGRESS送信: $position / $duration")

        } catch (e: Exception) {
            Log.e("BT", "進捗送信失敗", e)
        }
    }

    private fun listenIncoming() {
        thread {
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    val bytes = inputStream?.read(buffer) ?: break
                    if (bytes <= 0) break
                    receiveBuffer += String(buffer, 0, bytes)
                    while (true) {
                        val newline = receiveBuffer.indexOf("\n")
                        if (newline == -1) break
                        val line = receiveBuffer.substring(0, newline).trim()
                        receiveBuffer = receiveBuffer.substring(newline + 1)
                        if (line.isNotEmpty()) handleMessage(line)
                    }
                }
            } catch (e: Exception) {
                Log.e("BT", "受信エラー", e)

                val intent = Intent(context, MusicService::class.java).apply {
                    action = MusicService.ACTION_STOP
                }
                ContextCompat.startForegroundService(context, intent)

                onDisconnected?.invoke()
                stopServer()
            }
        }
    }

    private fun handleMessage(message: String) {
        Log.d("BT", "受信: $message")

        when {
            // 🔥 これを一番上に追加
            message.startsWith("NAME:") -> {
                val name = message.removePrefix("NAME:")
                Log.d("BT", "名前受信処理: $name")
                onDeviceNameReceived?.invoke(name)
            }

            message.startsWith("PLAY:") -> {
                val path = message.removePrefix("PLAY:")

                sendState("PLAYING")
                onPlay?.invoke(path)

                val intent = Intent(context, MusicService::class.java).apply {
                    action = MusicService.ACTION_PLAY
                    putExtra("MUSIC_URI", path)
                    putExtra("TITLE", path)
                    putExtra("DEVICE", "接続中")
                }
                ContextCompat.startForegroundService(context, intent)
            }

            message.startsWith("PAUSE") -> {
                sendState("PAUSED")
                onPlay?.invoke("PAUSED")

                val intent = Intent(context, MusicService::class.java).apply {
                    action = MusicService.ACTION_PAUSE
                }
                ContextCompat.startForegroundService(context, intent)
            }

            message.startsWith("RESUME") -> {
                sendState("PLAYING")
                onPlay?.invoke("RESUME")

                val intent = Intent(context, MusicService::class.java).apply {
                    action = MusicService.ACTION_RESUME
                }
                ContextCompat.startForegroundService(context, intent)
            }

            message.startsWith("PROGRESS:") -> {
                val data = message.removePrefix("PROGRESS:")
                val parts = data.split("||")

                if (parts.size == 2) {
                    val pos = parts[0].toInt()
                    val dur = parts[1].toInt()

                    onProgress?.invoke(pos, dur)
                }
            }

            message == "DISCONNECT" -> {
                val intent = Intent(context, MusicService::class.java).apply {
                    action = MusicService.ACTION_STOP
                }
                ContextCompat.startForegroundService(context, intent)

                stopServer()
                onDisconnected?.invoke()
            }

            message.startsWith("SEEK:") -> {
                val pos = message.removePrefix("SEEK:").toIntOrNull() ?: 0

                val intent = Intent(context, MusicService::class.java).apply {
                    action = MusicService.ACTION_SEEK
                    putExtra("SEEK_POS", pos)
                }
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    fun send(message: String) {
        try {
            outputStream?.write((message + "\n").toByteArray())
            outputStream?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun pauseMusic() {
        try {
            sendState("PAUSED")
            onPlay?.invoke("PAUSED")

            val intent = Intent(context, MusicService::class.java).apply {
                action = MusicService.ACTION_PAUSE
            }
            ContextCompat.startForegroundService(context, intent)

        } catch (e: Exception) {
            Log.e("BT", "pauseMusicエラー", e)
        }
    }

    fun resumeMusic() {
        try {
            sendState("PLAYING")
            onPlay?.invoke("RESUME")

            val intent = Intent(context, MusicService::class.java).apply {
                action = MusicService.ACTION_RESUME
            }
            ContextCompat.startForegroundService(context, intent)

        } catch (e: Exception) {
            Log.e("BT", "resumeMusicエラー", e)
        }
    }

    fun sendDisconnect() {
        send("DISCONNECT")
    }

    fun seekTo(position: Int) {
        MusicService.instance?.seekTo(position)
        send("SEEK:$position") // ← クライアントへ通知
    }

    fun sendState(state: String) {
        if (state == lastState) return

        lastState = state

        try {
            outputStream?.write("STATE:$state\n".toByteArray())
            outputStream?.flush()
            Log.d("BT", "STATE送信: $state")
        } catch (e: Exception) {
            Log.e("BT", "STATE送信失敗", e)
        }
    }

    private var isRunning = true

    fun startProgressSender() {
        thread {
            while (isRunning) {
                try {
                    val service = MusicService.instance

                    if (service != null &&
                        service.isPlaying() &&
                        service.getDuration() > 1000
                    ) {
                        val pos = service.getCurrentPosition()
                        val dur = service.getDuration()

                        sendProgress(pos, dur)
                    }

                    Thread.sleep(500)

                } catch (e: Exception) {
                    Log.e("BT", "進捗送信エラー", e)
                    break
                }
            }
        }
    }

    fun stopServer() {
        isRunning = false

        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}

        Log.d("BT", "サーバー停止")
    }
}