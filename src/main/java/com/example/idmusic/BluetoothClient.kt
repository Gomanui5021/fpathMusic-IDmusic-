//Bluetoothclient.kt
package com.example.idmusic

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.util.UUID
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.BufferedWriter

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