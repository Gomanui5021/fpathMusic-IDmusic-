//MainActivity.kt
package com.example.idmusic

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.content.Intent
import android.app.PendingIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import coil.compose.rememberAsyncImagePainter
import kotlin.text.Charsets

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        setContent {
            val context = LocalContext.current
            var isPermissionGranted by remember { mutableStateOf(false) }

            // 通常権限（音楽 + Bluetooth）
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { result ->
                val audio = if (Build.VERSION.SDK_INT >= 33) {
                    result[Manifest.permission.READ_MEDIA_AUDIO] == true
                } else {
                    result[Manifest.permission.READ_EXTERNAL_STORAGE] == true
                }

                val bt = if (Build.VERSION.SDK_INT >= 31) {
                    result[Manifest.permission.BLUETOOTH_CONNECT] == true
                } else true

                val scan = if (Build.VERSION.SDK_INT >= 31) {
                    result[Manifest.permission.BLUETOOTH_SCAN] == true
                } else true

                isPermissionGranted = audio && bt && scan
            }

            // 通知権限（後回し）
            val notificationLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                Log.d("Permission", "通知権限: $granted")
            }


            LaunchedEffect(Unit) {

                val neededPermissions = if (Build.VERSION.SDK_INT >= 33) {
                    // Android13+
                    listOf(
                        Manifest.permission.READ_MEDIA_AUDIO,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                    )
                } else {
                    // Android9〜12
                    listOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                }

                val notGranted = neededPermissions.filter {
                    ContextCompat.checkSelfPermission(
                        context,
                        it
                    ) != PackageManager.PERMISSION_GRANTED
                }

                if (notGranted.isNotEmpty()) {
                    launcher.launch(notGranted.toTypedArray())
                } else {
                    isPermissionGranted = true
                }
            }

            if (!isPermissionGranted) {
                Text("権限を許可してください...")
            } else {
                var role by remember { mutableStateOf<String?>(null) }
                when (role) {
                    null -> RoleSelectScreen { role = it }
                    "CLIENT" -> MusicScreen(notificationLauncher) { role = null }
                    "SERVER" -> ServerScreen { role = null }
                }
            }
        }
    }

    // ---------------- Role Select ----------------
    @Composable
    fun RoleSelectScreen(onSelect: (String) -> Unit) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            Button(
                onClick = { onSelect("CLIENT") },
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            ) {
                Text("送信側")
            }
            Button(
                onClick = { onSelect("SERVER") },
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            ) {
                Text("受信側")
            }
        }
    }

    // ---------------- Client ----------------
    @Composable
    fun MusicScreen(
        notificationLauncher: androidx.activity.compose.ManagedActivityResultLauncher<String, Boolean>,
        onDisconnect: () -> Unit
    ) {
        val context = LocalContext.current
        val bluetoothClient = remember { BluetoothClient(context) }
        var connectedDeviceName by remember { mutableStateOf("未接続") }
        var connectionState by remember { mutableStateOf("未接続") }
        var deviceList by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
        var isConnected by remember { mutableStateOf(false) }
        var isPlaying by remember { mutableStateOf(false) }
        var currentSongTitle by remember { mutableStateOf<String?>(null) }
        var currentPosition by remember { mutableStateOf(0) }
        var duration by remember { mutableStateOf(0) }
        var lastPosition by remember { mutableStateOf(0) }
        var lastUpdateTime by remember { mutableStateOf(System.currentTimeMillis()) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        var isMusicScreen by remember { mutableStateOf(false) }

        // 初期は空で、サーバーから受信して更新
        var musicItems by remember { mutableStateOf<List<MusicItem>>(emptyList()) }

        val view = LocalView.current
        SideEffect {
            val window = (view.context as android.app.Activity).window
            window.statusBarColor = android.graphics.Color.WHITE
            WindowCompat.getInsetsController(window, view)?.isAppearanceLightStatusBars = true
        }

        LaunchedEffect(Unit) {
            bluetoothClient.onConnected = {
                isConnected = true
                connectionState = "接続完了"
            }

            bluetoothClient.onDisconnected = {
                isConnected = false
                connectionState = "未接続"
            }

            bluetoothClient.onReceiveMusicList = { list ->
                musicItems = list.map { (folderTitle, uriStr) ->
                    val folder = folderTitle.substringBefore("/")
                    val title = folderTitle.substringAfter("/")
                    MusicItem(title, Uri.parse(uriStr), folder)
                }
            }

            bluetoothClient.onReceiveMessage = { message ->
                if (message.startsWith("STATE:")) {
                    isPlaying = message.removePrefix("STATE:") == "PLAYING"
                }
            }

            bluetoothClient.onProgress = { pos, dur ->
                val now = System.currentTimeMillis()

                if (pos < lastPosition) lastPosition = 0

                if (dur > 1000 && pos >= lastPosition) {
                    currentPosition = pos
                    duration = dur
                    lastPosition = pos
                    lastUpdateTime = now
                }
            }

            bluetoothClient.onError = { msg ->
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = msg,
                        duration = SnackbarDuration.Short
                    )
                }
            }

            val adapter = BluetoothAdapter.getDefaultAdapter()
            deviceList = adapter?.bondedDevices?.toList() ?: emptyList()
        }

        errorMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(8.dp)
            )
        }

        LaunchedEffect(isPlaying, isConnected) {
            while (isPlaying && isConnected) {
                delay(1000)

                val now = System.currentTimeMillis()
                val diff = (now - lastUpdateTime).toInt()

                currentPosition = (currentPosition + diff).coerceIn(0, duration)
                lastUpdateTime = now
            }
        }

        fun formatTime(ms: Int): String {
            val totalSec = ms / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            return "%02d:%02d".format(min, sec)
        }


        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding) // ← これ超重要！
                    .systemBarsPadding()
            ) {

                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {

                    if (!isConnected) {
                        Button(onClick = { onDisconnect() }) {
                            Text("← 戻る")
                        }
                    }

                    Text(connectionState)
                }


                if (!isConnected) {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(deviceList) { device ->
                            Text(
                                text = device.name ?: "不明",
                                modifier = Modifier.fillMaxWidth().padding(12.dp).clickable {
                                    connectionState = "${device.name ?: "不明"}へ接続中..."
                                    connectedDeviceName = device.name ?: "不明"
                                    Thread { bluetoothClient.connect(device) }.start()
                                }
                            )
                        }
                    }
                } else {
                    val grouped = musicItems.groupBy { it.folder }
                    val expandedState = remember { mutableStateMapOf<String, Boolean>() }

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        grouped.forEach { (folder, songs) ->
                            val isExpanded = expandedState[folder] ?: false
                            item {
                                Text(
                                    text = if (isExpanded) "📂 $folder" else "📁 $folder",
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable { expandedState[folder] = !isExpanded }
                                        .padding(8.dp)
                                )
                            }
                            if (isExpanded) {
                                items(songs) { song ->
                                    val isCurrent = song.title == currentSongTitle
                                    Row(
                                        modifier = Modifier.fillMaxWidth().height(56.dp).clickable {
                                            bluetoothClient.sendPlay(song.uri.toString())
                                            currentSongTitle = song.title
                                            isPlaying = true

                                            // 通知権限チェック
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                                ContextCompat.checkSelfPermission(
                                                    context,
                                                    Manifest.permission.POST_NOTIFICATIONS
                                                )
                                                != PackageManager.PERMISSION_GRANTED
                                            ) {
                                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            }

                                            val intent =
                                                Intent(context, MusicService::class.java).apply {
                                                    action = MusicService.ACTION_PLAY
                                                    putExtra("MUSIC_URI", song.uri.toString())
                                                    putExtra("TITLE", song.title)
                                                    putExtra("DEVICE", connectedDeviceName)
                                                }
                                            ContextCompat.startForegroundService(context, intent)
                                        }.padding(horizontal = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (isCurrent) "▶ ${song.title}" else song.title,
                                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (currentSongTitle != null) {
                            Text(
                                text = "♪: $currentSongTitle",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text("接続デバイス: $connectedDeviceName")

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp)
                                .navigationBarsPadding(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Button(
                                onClick = {
                                    val intent = Intent(context, MusicService::class.java)
                                    if (isPlaying) {
                                        bluetoothClient.sendPause()
                                        intent.action = MusicService.ACTION_PAUSE
                                        isPlaying = false
                                    } else {
                                        bluetoothClient.sendResume()
                                        intent.action = MusicService.ACTION_RESUME
                                        isPlaying = true
                                    }
                                    intent.putExtra("TITLE", currentSongTitle)
                                    intent.putExtra("DEVICE", connectedDeviceName)
                                    ContextCompat.startForegroundService(context, intent)
                                }
                            ) { Text(if (isPlaying) "⏸" else "▶") }

                            Button(onClick = {
                                bluetoothClient.sendDisconnect()
                                bluetoothClient.disconnect()

                                isConnected = false
                                isPlaying = false
                                currentSongTitle = null
                                currentPosition = 0
                                duration = 0

                                val intent = Intent(context, MusicService::class.java).apply {
                                    action = MusicService.ACTION_PAUSE
                                }
                                ContextCompat.startForegroundService(context, intent)

                            }) { Text("切断") }
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp) // ← 下に余白
                ) {

                    val sliderValue = if (duration > 0) {
                        currentPosition.toFloat() / duration.toFloat()
                    } else 0f

                    if (musicItems.isNotEmpty() && duration > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {

                            // 左：現在時間
                            Text(
                                text = formatTime(currentPosition),
                                modifier = Modifier.width(50.dp)
                            )

                            // 中央：シークバー
                            Slider(
                                value = sliderValue,
                                onValueChange = {
                                    currentPosition = (it * duration).toInt()
                                },
                                onValueChangeFinished = {
                                    bluetoothClient.sendSeek(currentPosition)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                            )

                            // 右：総時間
                            Text(
                                text = formatTime(duration),
                                modifier = Modifier.width(50.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------------- Server ----------------
    @Composable
    fun ServerScreen(onCancel: () -> Unit) {
        val context = LocalContext.current
        val server = remember { BluetoothServer(context) }

        var clientName by remember { mutableStateOf("未接続") }
        var isConnected by remember { mutableStateOf(false) }
        var playingTitle by remember { mutableStateOf<String?>(null) }
        var isPlaying by remember { mutableStateOf(false) }
        var currentPosition by remember { mutableStateOf(0) }
        var duration by remember { mutableStateOf(0) }
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val musicList = remember { getMusicList(context) }
        var onDisconnected: (() -> Unit)? = null

        fun getAlbumArt(context: Context, musicUri: Uri): Uri? {
            return try {
                val mmr = android.media.MediaMetadataRetriever()
                mmr.setDataSource(context, musicUri)

                val art = mmr.embeddedPicture
                if (art != null) {
                    // バイト配列 → 一時URIに変換
                    val file = kotlin.io.path.createTempFile().toFile()
                    file.writeBytes(art)
                    Uri.fromFile(file)
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        var albumArtUri by remember { mutableStateOf<Uri?>(null) }


        fun formatTime(ms: Int): String {
            val totalSec = ms / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            return "%02d:%02d".format(min, sec)
        }

        LaunchedEffect(Unit) {
            server.setMusicList(musicList)
            server.start()

            server.onDeviceNameReceived = { name ->
                CoroutineScope(Dispatchers.Main).launch {
                    clientName = name
                }
            }

            server.onConnected = {
                CoroutineScope(Dispatchers.Main).launch {
                    isConnected = true
                }
            }

            server.onPlay = { value ->
                CoroutineScope(Dispatchers.Main).launch {
                    when (value) {
                        "PAUSED" -> isPlaying = false
                        "RESUME" -> isPlaying = true
                        else -> {
                            val found = musicList.find { it.uri.toString() == value }
                            playingTitle = found?.title ?: value
                            isPlaying = true

                            // 👇 追加：ジャケット取得
                            albumArtUri = found?.let { getAlbumArt(context, it.uri) }
                        }
                    }
                }
            }
            server.onDisconnected = {
                CoroutineScope(Dispatchers.Main).launch {
                    isConnected = false
                    clientName = "未接続"
                    playingTitle = null
                    isPlaying = false

                    scope.launch {
                        snackbarHostState.showSnackbar("接続が切断されました")
                    }
                    delay(500)
                    onCancel()
                }
            }
        }

        LaunchedEffect(isConnected, isPlaying) {
            while (isConnected) {
                val service = MusicService.instance

                if (service != null && service.getDuration() > 0) {
                    currentPosition = service.getCurrentPosition()
                    duration = service.getDuration()
                }

                kotlinx.coroutines.delay(500)
            }
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(if (isConnected) "接続成功" else "待機中")
                Text("接続相手: $clientName")


                Spacer(modifier = Modifier.height(16.dp))

                Image(
                    painter = rememberAsyncImagePainter(
                        model = albumArtUri ?: R.drawable.no_image // ← fallback
                    ),
                    contentDescription = "Album Art",
                    modifier = Modifier
                        .size(250.dp)
                        .padding(16.dp),
                    contentScale = ContentScale.Fit
                )

                Spacer(modifier = Modifier.height(16.dp))

                playingTitle?.let {
                    Text("再生中: $it")

                    Button(onClick = {
                        if (isPlaying) {
                            server.pauseMusic()
                            server.onPlay?.invoke("PAUSED")
                        } else {
                            server.resumeMusic()
                            server.onPlay?.invoke("RESUME")
                        }
                    }) {
                        Text(if (isPlaying) "⏸" else "▶")
                    }
                }

                if (duration > 0) {
                    val sliderValue = currentPosition.toFloat() / duration.toFloat()

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {

                        // 左：現在時間
                        Text(
                            text = formatTime(currentPosition),
                            modifier = Modifier.width(50.dp)
                        )

                        // 中央：シークバー
                        Slider(
                            value = sliderValue,
                            onValueChange = {
                                currentPosition = (it * duration).toInt()
                            },
                            onValueChangeFinished = {
                                server.seekTo(currentPosition)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )

                        // 右：総時間
                        Text(
                            text = formatTime(duration),
                            modifier = Modifier.width(50.dp)
                        )
                    }
                }

                Button(onClick = {
                    server.stopServer()
                    onCancel()
                }) {
                    Text("切断")
                }
            }
        }
    }


    // ---------------- Music List ----------------
    fun getMusicList(context: Context): List<MusicItem> {
        val list = mutableListOf<MusicItem>()

        val volumes = MediaStore.getExternalVolumeNames(context)

        for (volume in volumes) {

            val collection = MediaStore.Audio.Media.getContentUri(volume)

            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE
            )

            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

            val cursor = context.contentResolver.query(
                collection,
                projection,
                selection,
                null,
                null
            )

            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)

                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val title = it.getString(titleColumn)

                    val contentUri = Uri.withAppendedPath(collection, id.toString())

                    list.add(
                        MusicItem(
                            title = title,
                            uri = contentUri,
                            folder = if (volume == "external_primary") "Music" else "SDカード"
                        )
                    )
                }
            }
        }

        return list
    }
    }