//Bluetoothclient.kt
package com.example.idmusic

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.util.UUID
import java.io.IOException

class BluetoothClient(private val context: Context) {

    private var socket: BluetoothSocket? = null
    private var outputStream: java.io.OutputStream? = null
    private var inputStream: java.io.InputStream? = null

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onReceiveMusicList: ((List<Pair<String, String>>) -> Unit)? = null
    var onReceiveMessage: ((String) -> Unit)? = null
    var onDeviceNameReceived: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onProgress: ((Int, Int) -> Unit)? = null

    private val tempList = mutableListOf<Pair<String, String>>()


    companion object {
        var instance: BluetoothClient? = null
    }


    fun connect(device: BluetoothDevice) {
        instance = null

        Thread {
            try {
                socket = device.createRfcommSocketToServiceRecord(
                    UUID.fromString("c8f9f668-17b1-4d3a-ba34-68f397619315")
                )

                BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()

                socket?.let { s ->
                    s.connect()

                    outputStream = s.outputStream
                    inputStream = s.inputStream
                } ?: run {
                    onError?.invoke("ソケット作成失敗")
                    return@Thread
                }

                Thread.sleep(200)

                val myName = android.os.Build.MODEL
                Log.d("BT", "クライアントNAME送信: $myName")
                sendRaw("NAME:$myName")

                CoroutineScope(Dispatchers.Main).launch {
                    onConnected?.invoke()
                }

                listenIncoming()

            } catch (e: IOException) {
                Log.e("BT", "接続失敗(UUID不一致など)", e)

                CoroutineScope(Dispatchers.Main).launch {
                    onError?.invoke("接続できませんでした")
                    onDisconnected?.invoke()
                }

                try { socket?.close() } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e("BT", "その他エラー", e)

                CoroutineScope(Dispatchers.Main).launch {
                    onDisconnected?.invoke()
                }
            }
        }.start()
    }

    fun send(message: String) {
        try {
            outputStream?.write((message + "\n").toByteArray(Charsets.UTF_8))
            outputStream?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    fun sendMessage(message: String) {
        try {
            outputStream?.write(message.toByteArray(Charsets.UTF_8))
            outputStream?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    fun sendDisconnect() {
        Thread {
            sendRaw("DISCONNECT")

            // 少し待ってから切断（超重要）
            Thread.sleep(200)

            closeSocket()

            CoroutineScope(Dispatchers.Main).launch {
                onDisconnected?.invoke()
            }
        }.start()
    }

    // ---------------- 送信 ----------------

    private fun sendRaw(msg: String) {
        try {
            outputStream?.write("$msg\n".toByteArray())
            outputStream?.flush()
            Log.d("BT", "送信: $msg")
        } catch (e: Exception) {
            Log.e("BT", "送信失敗", e)
        }
    }

    fun sendSeek(position: Int) {
        send("SEEK:$position")
    }

    fun sendPlay(uri: String) {
        Thread { sendRaw("PLAY:$uri") }.start()
    }

    fun sendPause() {
        Thread { sendRaw("PAUSE") }.start()
    }

    fun sendResume() {
        Thread { sendRaw("RESUME") }.start()
    }

    // ---------------- 受信 ----------------

    private fun listenIncoming() {
        Thread {
            val buffer = ByteArray(4096)

            try {
                var receiveBuffer = ""

                while (true) {
                    val bytes = inputStream?.read(buffer) ?: -1
                    if (bytes == -1) break

                    val chunk = String(buffer, 0, bytes, java.nio.charset.StandardCharsets.UTF_8)
                    receiveBuffer += chunk

                    val lines = receiveBuffer.split("\n")

                    // 最後は未完成の可能性があるので残す
                    receiveBuffer = lines.last()

                    val completeLines = lines.dropLast(1)

                    for (line in completeLines) {
                        if (line.isNotBlank()) {
                            handleIncoming(line)
                        }
                    }
                }

            } catch (e: Exception) {
                Log.d("BT", "正常切断")
            } finally {
                closeSocket()
                CoroutineScope(Dispatchers.Main).launch {
                    onDisconnected?.invoke()
                }
            }
        }.start()
    }


    private fun handleIncoming(line: String) {
        when {

            // 🔥 これを一番上に追加
            line == "PAUSE" -> {
                MusicService.instance?.pauseFromRemote()
            }

            line == "RESUME" -> {
                MusicService.instance?.resumeFromRemote()
            }

            line.startsWith("NAME:") -> {
                val name = line.removePrefix("NAME:")
                onDeviceNameReceived?.invoke(name)
            }

            line.startsWith("ITEM:") -> {
                val parts = line.removePrefix("ITEM:").split("||")
                if (parts.size == 3) {
                    tempList.add("${parts[0]}/${parts[1]}" to parts[2])
                }
            }

            line.startsWith("PROGRESS:") -> {
                val data = line.removePrefix("PROGRESS:")
                val parts = data.split("||")

                if (parts.size == 2) {
                    val pos = parts[0].toIntOrNull() ?: 0
                    val dur = parts[1].toIntOrNull() ?: 0

                    CoroutineScope(Dispatchers.Main).launch {
                        onProgress?.invoke(pos, dur)
                    }
                }
            }

            line == "END" -> {
                val result = tempList
                    .sortedBy { it.first }
                    .toList()
                tempList.clear()
                CoroutineScope(Dispatchers.Main).launch {
                    onReceiveMusicList?.invoke(result)
                }
            }

            line.startsWith("STATE:") -> {
                onReceiveMessage?.invoke(line)
            }
        }
    }

    fun disconnect() {
        instance = null
        closeSocket()
        CoroutineScope(Dispatchers.Main).launch {
            onDisconnected?.invoke()
        }
    }

    private fun closeSocket() {
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
    }
}