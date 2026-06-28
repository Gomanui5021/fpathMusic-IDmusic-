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
import android.media.AudioDeviceInfo
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothProfile

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

    private var bluetoothA2dp: BluetoothA2dp? = null
    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) {
                bluetoothA2dp = proxy as BluetoothA2dp
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                bluetoothA2dp = null
            }
        }
    }

    var onConnected: (() -> Unit)? = null
    var onPlay: ((String) -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
    var onAutoSkipSync: ((Boolean) -> Unit)? = null
    var onClientNameReceived: ((String) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onReceiveMessage: ((String) -> Unit)? = null
    var onCodecStatusChanged: ((String) -> Unit)? = null

    private var musicListToSend: List<MusicItem> = emptyList()

    init {
        adapter?.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
    }

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
            else -> onReceiveMessage?.invoke(message)
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
                    val size = 300
                    val scaled = if (it.width > size || it.height > size) {
                        val scale = size.toFloat() / Math.max(it.width, it.height)
                        Bitmap.createScaledBitmap(it, (it.width * scale).toInt(), (it.height * scale).toInt(), true)
                    } else it
                    
                    val out = ByteArrayOutputStream()
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
                    adapter?.closeProfileProxy(BluetoothProfile.A2DP, bluetoothA2dp)
                    serverSocket?.close()
                    socket?.close()
                    reader?.close()
                    writer?.close()
                } catch (_: Exception) {}
                serverSocket = null
                socket = null
                reader = null
                writer = null
                bluetoothA2dp = null
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
            var lastSentOutput = ""
            var lastSentCodec = ""
            
            while (isRunning) {
                try {
                    val service = MusicService.instance
                    if (service != null && service.isPlaying()) {
                        val pos = service.getCurrentPosition()
                        val dur = service.getDuration()
                        if (dur > 0) sendProgress(pos, dur)
                    }

                    val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (currentVol != lastSentVol) {
                        sendCurrentVolume()
                        lastSentVol = currentVol
                    }
                    
                    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    var outputName = "スピーカー"
                    var hasBluetooth = false
                    
                    val btDeviceInfo = devices.find { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                    val usbDeviceInfo = devices.find { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && it.type == AudioDeviceInfo.TYPE_USB_HEADSET) }
                    val wiredDeviceInfo = devices.find { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }

                    if (btDeviceInfo != null) {
                        outputName = btDeviceInfo.productName?.toString() ?: "Bluetoothイヤホン"
                        hasBluetooth = true
                    } else if (usbDeviceInfo != null) {
                        outputName = usbDeviceInfo.productName?.toString()?.ifEmpty { "USBオーディオ" } ?: "USBオーディオ"
                    } else if (wiredDeviceInfo != null) {
                        outputName = "有線イヤホン"
                    }
                    
                    if (outputName != lastSentOutput) {
                        sendMessage("OUTPUT_DEVICE:$outputName")
                        lastSentOutput = outputName
                    }

                    var codecName = "NO connect"
                    if (hasBluetooth) {
                        codecName = getBluetoothCodecName()
                    }

                    if (codecName != lastSentCodec) {
                        sendMessage("AUDIO_CODEC:$codecName")
                        onCodecStatusChanged?.invoke(codecName)
                        lastSentCodec = codecName
                    }

                } catch (e: Exception) {
                    if (isRunning) Log.e("BT", "Status Sender Error", e)
                }
                Thread.sleep(500)
            }
        }
    }

    private fun getBluetoothCodecName(): String {
        val a2dp = bluetoothA2dp ?: return "取得中..."
        
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
        } catch (e: Exception) {}
        return "不明"
    }

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
}
