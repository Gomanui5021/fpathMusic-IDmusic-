//Bluetoothclient.kt
package com.example.idmusic

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import android.net.Uri
import kotlinx.coroutines.*
import java.util.UUID
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.BufferedWriter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.File
import java.io.FileOutputStream

class BluetoothClient(private val context: Context) {

    private var socket: BluetoothSocket? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onReceiveMusicList: ((List<MusicItem>) -> Unit)? = null
    var onReceiveMessage: ((String) -> Unit)? = null
    var onClientNameReceived: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onProgress: ((Int, Int) -> Unit)? = null
    var onVolumeReceived: ((Int, Int) -> Unit)? = null
    var onAlbumArtReceived: ((Long, Bitmap) -> Unit)? = null

    private val tempList = mutableListOf<MusicItem>()
    private val cacheDir = File(context.filesDir, "Fpathmusic").apply { if (!exists()) mkdirs() }

    companion object {
        var instance: BluetoothClient? = null
    }

    fun getCachedArt(albumId: Long): Bitmap? {
        val file = File(cacheDir, "$albumId.webp")
        return if (file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath)
        } else null
    }

    private fun saveArtToCache(albumId: Long, bytes: ByteArray) {
        val file = File(cacheDir, "$albumId.webp")
        try {
            FileOutputStream(file).use { it.write(bytes) }
        } catch (e: Exception) {
            Log.e("BT", "Save Art Error", e)
        }
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
                
                writer = BufferedWriter(OutputStreamWriter(socket?.outputStream, Charsets.UTF_8))
                reader = BufferedReader(InputStreamReader(socket?.inputStream, Charsets.UTF_8))

                val myName = android.os.Build.MODEL
                sendRaw("NAME:$myName")

                CoroutineScope(Dispatchers.Main).launch { onConnected?.invoke() }
                listenIncoming()
            } catch (e: Exception) {
                instance = null
                CoroutineScope(Dispatchers.Main).launch {
                    onError?.invoke("接続失敗")
                    onDisconnected?.invoke()
                }
            }
        }.start()
    }

    fun sendMessage(message: String) {
        Thread {
            try {
                synchronized(this) {
                    writer?.write(message + "\n")
                    writer?.flush()
                }
            } catch (e: Exception) {
                Log.e("BT", "送信失敗: $message", e)
            }
        }.start()
    }

    fun sendSeek(position: Int) { sendMessage("SEEK:$position") }
    fun sendPlay(uri: String) { sendMessage("PLAY:$uri") }
    fun sendPause() { sendMessage("PAUSE") }
    fun sendResume() { sendMessage("RESUME") }
    fun sendNext() { sendMessage("NEXT") }
    fun sendPrevious() { sendMessage("PREVIOUS") }
    fun sendStopEachTrack(enabled: Boolean) { sendMessage("SET_STOP_EACH:${if (enabled) "ON" else "OFF"}") }
    fun sendVolumeSet(volume: Int) { sendMessage("VOLUME_SET:$volume") }
    
    fun requestAlbumArt(albumId: Long, uri: String) {
        if (File(cacheDir, "$albumId.webp").exists()) return
        sendMessage("GET_ART:$albumId||$uri")
    }
    
    fun sendDisconnect() { 
        Thread {
            try {
                sendRaw("DISCONNECT")
                Thread.sleep(100)
            } catch (_: Exception) {}
            disconnect()
        }.start()
    }

    private fun sendRaw(msg: String) {
        try {
            synchronized(this) {
                writer?.write(msg + "\n")
                writer?.flush()
            }
        } catch (e: Exception) {}
    }

    private fun listenIncoming() {
        try {
            while (true) {
                val line = reader?.readLine() ?: break
                if (line.isNotBlank()) handleIncoming(line)
            }
        } catch (e: Exception) {
            Log.e("BT", "受信エラー", e)
        } finally {
            disconnect()
        }
    }

    private fun handleIncoming(line: String) {
        when {
            line.startsWith("NAME:") -> onClientNameReceived?.invoke(line.removePrefix("NAME:"))
            line.startsWith("ITEM:") -> {
                val parts = line.removePrefix("ITEM:").split("||")
                if (parts.size >= 6) {
                    tempList.add(MusicItem(
                        title = parts[1],
                        uri = Uri.parse(parts[2]),
                        folder = parts[0],
                        path = parts[3],
                        storage = parts[4],
                        albumId = parts[5].toLongOrNull() ?: 0L
                    ))
                }
            }
            line.startsWith("PROGRESS:") -> {
                val parts = line.removePrefix("PROGRESS:").split("||")
                if (parts.size == 2) {
                    onProgress?.invoke(parts[0].toInt(), parts[1].toInt())
                }
            }
            line.startsWith("VOLUME:") -> {
                val parts = line.removePrefix("VOLUME:").split("||")
                if (parts.size == 2) {
                    val current = parts[0].toIntOrNull() ?: 0
                    val max = parts[1].toIntOrNull() ?: 15
                    onVolumeReceived?.invoke(current, max)
                }
            }
            line.startsWith("ART:") -> {
                val parts = line.removePrefix("ART:").split("||")
                if (parts.size == 2) {
                    val albumId = parts[0].toLongOrNull() ?: -1L
                    val base64 = parts[1]
                    try {
                        val bytes = Base64.decode(base64, Base64.DEFAULT)
                        saveArtToCache(albumId, bytes)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            CoroutineScope(Dispatchers.Main).launch {
                                onAlbumArtReceived?.invoke(albumId, bitmap)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("BT", "Decode Art Error", e)
                    }
                }
            }
            line == "END" -> {
                val result = tempList.sortedWith(compareBy({ it.folder }, { it.title })).toList()
                tempList.clear()
                CoroutineScope(Dispatchers.Main).launch { onReceiveMusicList?.invoke(result) }
                
                // リスト受信後に足りないジャケットを一括リクエスト（バックグラウンドで）
                Thread {
                    result.forEach { 
                        if (it.albumId > 0 && !File(cacheDir, "${it.albumId}.webp").exists()) {
                            sendMessage("GET_ART:${it.albumId}||${it.uri}")
                            Thread.sleep(100) // 負荷軽減
                        }
                    }
                }.start()
            }
            else -> onReceiveMessage?.invoke(line)
        }
    }

    fun disconnect() {
        if (instance == null) return
        instance = null
        Thread {
            closeSocket()
            CoroutineScope(Dispatchers.Main).launch { onDisconnected?.invoke() }
        }.start()
    }

    private fun closeSocket() {
        synchronized(this) {
            try {
                reader?.close()
                writer?.close()
                socket?.close()
            } catch (_: Exception) {}
            reader = null
            writer = null
            socket = null
        }
    }
}