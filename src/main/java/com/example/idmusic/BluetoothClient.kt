//Bluetoothclient.kt
package com.example.idmusic

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.UUID
import java.io.IOException

class BluetoothClient(private val context: Context) {

    private var socket: BluetoothSocket? = null
    private var outputStream: java.io.OutputStream? = null
    private var inputStream: java.io.InputStream? = null

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onReceiveMusicList: ((List<MusicItem>) -> Unit)? = null
    var onReceiveMessage: ((String) -> Unit)? = null
    var onDeviceNameReceived: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onProgress: ((Int, Int) -> Unit)? = null

    private val tempList = mutableListOf<MusicItem>()

    companion object {
        var instance: BluetoothClient? = null
    }

    fun connect(device: BluetoothDevice) {
        instance = this
        Thread {
            try {
                socket = device.createRfcommSocketToServiceRecord(
                    UUID.fromString("c8f9f668-17b1-4d3a-ba34-68f397619315")
                )
                BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                socket?.connect()
                outputStream = socket?.outputStream
                inputStream = socket?.inputStream

                val myName = android.os.Build.MODEL
                sendRaw("NAME:$myName")

                CoroutineScope(Dispatchers.Main).launch { onConnected?.invoke() }
                listenIncoming()
            } catch (e: Exception) {
                CoroutineScope(Dispatchers.Main).launch {
                    onError?.invoke("接続失敗")
                    onDisconnected?.invoke()
                }
            }
        }.start()
    }

    fun sendMessage(message: String) {
        try {
            outputStream?.write((message + "\n").toByteArray(Charsets.UTF_8))
            outputStream?.flush()
        } catch (e: Exception) {
            Log.e("BT", "送信失敗: $message", e)
        }
    }

    fun sendSeek(position: Int) { sendMessage("SEEK:$position") }
    fun sendPlay(uri: String) { sendMessage("PLAY:$uri") }
    fun sendPause() { sendMessage("PAUSE") }
    fun sendResume() { sendMessage("RESUME") }
    fun sendDisconnect() { sendMessage("DISCONNECT"); Thread.sleep(200); closeSocket() }

    private fun sendRaw(msg: String) {
        try {
            outputStream?.write((msg + "\n").toByteArray())
            outputStream?.flush()
        } catch (e: Exception) {}
    }

    private fun listenIncoming() {
        Thread {
            val buffer = ByteArray(4096)
            var receiveBuffer = ""
            try {
                while (true) {
                    val bytes = inputStream?.read(buffer) ?: -1
                    if (bytes == -1) break
                    receiveBuffer += String(buffer, 0, bytes, Charsets.UTF_8)
                    val lines = receiveBuffer.split("\n")
                    receiveBuffer = lines.last()
                    for (line in lines.dropLast(1)) {
                        if (line.isNotBlank()) handleIncoming(line)
                    }
                }
            } catch (e: Exception) {
            } finally {
                disconnect()
            }
        }.start()
    }

    private fun handleIncoming(line: String) {
        when {
            line == "PAUSE" -> MusicService.instance?.pauseFromRemote()
            line == "RESUME" -> MusicService.instance?.resumeFromRemote()
            line.startsWith("SEEK:") -> {
                val pos = line.removePrefix("SEEK:").toIntOrNull() ?: 0
                MusicService.instance?.seekTo(pos)
            }
            line.startsWith("NAME:") -> onDeviceNameReceived?.invoke(line.removePrefix("NAME:"))
            line.startsWith("ITEM:") -> {
                val parts = line.removePrefix("ITEM:").split("||")
                if (parts.size >= 5) {
                    tempList.add(MusicItem(parts[1], android.net.Uri.parse(parts[2]), parts[0], parts[3], parts[4]))
                }
            }
            line.startsWith("PROGRESS:") -> {
                val parts = line.removePrefix("PROGRESS:").split("||")
                if (parts.size == 2) {
                    onProgress?.invoke(parts[0].toInt(), parts[1].toInt())
                }
            }
            line == "END" -> {
                val result = tempList.sortedWith(compareBy({ it.folder }, { it.title })).toList()
                tempList.clear()
                CoroutineScope(Dispatchers.Main).launch { onReceiveMusicList?.invoke(result) }
            }
            line.startsWith("STATE:") -> onReceiveMessage?.invoke(line)
        }
    }

    fun disconnect() {
        instance = null
        closeSocket()
        CoroutineScope(Dispatchers.Main).launch { onDisconnected?.invoke() }
    }

    private fun closeSocket() {
        try { inputStream?.close(); outputStream?.close(); socket?.close() } catch (_: Exception) {}
    }
}