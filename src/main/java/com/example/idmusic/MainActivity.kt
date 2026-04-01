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
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import java.io.File


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
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = "権限を許可してください...",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(y = (-180).dp) // ← 少し上にずらす
                    )
                }
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

        val view = LocalView.current
        SideEffect {
            val window = (view.context as android.app.Activity).window
            window.statusBarColor = android.graphics.Color.WHITE
            WindowCompat.getInsetsController(window, view)
                ?.isAppearanceLightStatusBars = true
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // 🔽 画像（アプリ名ロゴ）
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = "App Logo",
                modifier = Modifier
                    .size(500.dp)
                    .padding(bottom = 32.dp)
            )

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

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { onSelect("SERVER") },
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                ) {
                    Text("受信側")
                }
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
        var deviceList by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
        var isConnected by remember { mutableStateOf(false) }
        var isPlaying by remember { mutableStateOf(false) }
        var currentSongTitle by remember { mutableStateOf<String?>(null) }
        var currentSongUri by remember { mutableStateOf<String?>(null) }
        var currentPosition by remember { mutableStateOf(0) }
        var duration by remember { mutableStateOf(0) }
        var lastPosition by remember { mutableStateOf(0) }
        var lastUpdateTime by remember { mutableStateOf(System.currentTimeMillis()) }
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        var connectingDevice by remember { mutableStateOf<BluetoothDevice?>(null) }

        // 初期は空で、サーバーから受信して更新
        var musicItems by remember { mutableStateOf<List<MusicItem>>(emptyList()) }

        val view = LocalView.current
        SideEffect {
            val window = (view.context as android.app.Activity).window
            window.statusBarColor = android.graphics.Color.WHITE
            WindowCompat.getInsetsController(window, view)?.isAppearanceLightStatusBars = true
        }

        DisposableEffect(Unit) {
            onDispose {
                bluetoothClient.disconnect()
            }
        }

        LaunchedEffect(Unit) {

            bluetoothClient.onConnected = {
                scope.launch {
                    isConnected = true
                    connectingDevice = null
                    snackbarHostState.showSnackbar("接続成功")
                }
            }


            bluetoothClient.onDisconnected = {
                isConnected = false
                val intent = Intent(context, MusicService::class.java).apply {
                    action = MusicService.ACTION_STOP
                }
                ContextCompat.startForegroundService(context, intent)

                scope.launch {
                    snackbarHostState.showSnackbar("接続終了")
                }
            }

            bluetoothClient.onReceiveMusicList = { list: List<MusicItem> ->
                musicItems = list.map { it.copy() }
            }

            bluetoothClient.onReceiveMessage = { message ->
                if (message.startsWith("STATE:")) {
                    val playing = message.removePrefix("STATE:") == "PLAYING"
                    isPlaying = playing

                    val intent = Intent(context, MusicService::class.java).apply {
                        action = MusicService.ACTION_UPDATE_STATE
                        putExtra("IS_PLAYING", playing)
                        putExtra("POSITION", currentPosition)
                        putExtra("DURATION", duration)
                        putExtra("TITLE", currentSongTitle)
                        putExtra("DEVICE", connectedDeviceName)
                    }
                    ContextCompat.startForegroundService(context, intent)
                }
            }

            bluetoothClient.onProgress = { pos, dur ->
                val now = System.currentTimeMillis()
                
                // シークバーが表示されるようにdurationを更新
                if (dur > 0) {
                    duration = dur
                    currentPosition = pos
                    lastPosition = pos
                    lastUpdateTime = now
                    
                    val intent = Intent(context, MusicService::class.java).apply {
                        action = MusicService.ACTION_UPDATE_STATE
                        putExtra("IS_PLAYING", isPlaying)
                        putExtra("POSITION", pos)
                        putExtra("DURATION", dur)
                        putExtra("TITLE", currentSongTitle)
                        putExtra("DEVICE", connectedDeviceName)
                    }
                    ContextCompat.startForegroundService(context, intent)
                }
            }

            bluetoothClient.onError = { msg ->
                scope.launch {
                    snackbarHostState.showSnackbar(message = msg)
                }
            }

            val adapter = BluetoothAdapter.getDefaultAdapter()
            deviceList = adapter?.bondedDevices?.toList() ?: emptyList()
        }

        LaunchedEffect(isConnected) {
            while (isConnected) {
                val service = MusicService.instance
                if (service != null) {
                    isPlaying = service.isPlaying()
                    if (isPlaying) {
                        val now = System.currentTimeMillis()
                        val diff = (now - lastUpdateTime).toInt()
                        currentPosition = (currentPosition + diff).coerceIn(0, duration)
                        lastUpdateTime = now
                    }
                }
                delay(500)
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
                    .padding(padding)
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
                }

                val expandedState = remember { mutableStateMapOf<String, Boolean>() }

                if (!isConnected) {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(deviceList) { device ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        connectingDevice = device
                                        connectedDeviceName = device.name ?: "不明"
                                        scope.launch(Dispatchers.IO) {
                                            bluetoothClient.connect(device)
                                        }
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = device.name ?: "不明", modifier = Modifier.weight(1f))
                                if (connectingDevice == device) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
                    }
                } else {
                    val grouped = musicItems.groupBy { it.folder }

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        grouped.forEach { (folder, songs) ->
                            val isExpanded = expandedState[folder] ?: false
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(width = 1.dp, Color.Black)
                                        .height(56.dp)
                                        .clickable { expandedState[folder] = !isExpanded }
                                        .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        text = if (isExpanded) "📂 $folder" else "📁 $folder"
                                    )
                                }
                            }
                            if (isExpanded) {
                                items(songs) { song ->
                                    val isCurrent = song.uri.toString() == currentSongUri
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(1.dp, Color.Black, RoundedCornerShape(2.dp))
                                            .clickable {
                                                bluetoothClient.sendPlay(song.uri.toString())
                                                currentSongTitle = song.title
                                                currentSongUri = song.uri.toString()
                                                isPlaying = true
                                                duration = 0 // 新しい曲なので一旦リセット
                                                currentPosition = 0

                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                                ) {
                                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                }

                                                val intent = Intent(context, MusicService::class.java).apply {
                                                    action = MusicService.ACTION_UPDATE_STATE
                                                    putExtra("IS_PLAYING", true)
                                                    putExtra("TITLE", song.title)
                                                    putExtra("DEVICE", connectedDeviceName)
                                                    putExtra("DURATION", 0)
                                                }
                                                ContextCompat.startForegroundService(context, intent)
                                            }
                                            .padding(12.dp)
                                    ) {
                                        Text(
                                            text = if (isCurrent) "▶ ${song.title}" else song.title,
                                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                                        )
                                        Text(
                                            text = "[${song.storage}] ${song.path}",
                                            fontSize = 12.sp,
                                            color = Color.Gray.copy(alpha = 0.7f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    HorizontalDivider(thickness = 2.dp, color = Color.Gray)
                                }
                            }
                        }
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        elevation = CardDefaults.cardElevation(8.dp)
                    ) {
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
                                modifier = Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Button(
                                    onClick = {
                                        if (isPlaying) {
                                            bluetoothClient.sendPause()
                                            isPlaying = false
                                        } else {
                                            bluetoothClient.sendResume()
                                            isPlaying = true
                                        }
                                        val intent = Intent(context, MusicService::class.java).apply {
                                            action = MusicService.ACTION_UPDATE_STATE
                                            putExtra("IS_PLAYING", isPlaying)
                                            putExtra("TITLE", currentSongTitle)
                                            putExtra("DEVICE", connectedDeviceName)
                                            putExtra("DURATION", duration)
                                        }
                                        ContextCompat.startForegroundService(context, intent)
                                    }
                                ) { Text(if (isPlaying) "⏸" else "▶") }

                                Button(onClick = {
                                    bluetoothClient.sendDisconnect()
                                    // isConnected = false // これは bluetoothClient.onDisconnected で行う
                                }) { Text("切断") }
                            }
                        }

                        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                            // durationが0 poolでもスライダーを表示するか、あるいはdurationが届くのを待つ
                            val sliderValue = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f
                            
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            ) {
                                Text(text = formatTime(currentPosition), modifier = Modifier.width(50.dp))
                                Slider(
                                    value = sliderValue,
                                    onValueChange = { if (duration > 0) currentPosition = (it * duration).toInt() },
                                    onValueChangeFinished = { if (duration > 0) bluetoothClient.sendSeek(currentPosition) },
                                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                    enabled = duration > 0
                                )
                                Text(text = formatTime(duration), modifier = Modifier.width(50.dp))
                            }
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
        var currentSongUri by remember { mutableStateOf<String?>(null) }
        var isPlaying by remember { mutableStateOf(false) }
        var currentPosition by remember { mutableStateOf(0) }
        var duration by remember { mutableStateOf(0) }
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val musicList = remember { getMusicList(context) }

        fun getAlbumArt(context: Context, musicUri: Uri): Uri? {
            return try {
                val mmr = android.media.MediaMetadataRetriever()
                mmr.setDataSource(context, musicUri)
                val art = mmr.embeddedPicture
                if (art != null) {
                    val file = File.createTempFile("albumart", ".jpg", context.cacheDir)
                    file.writeBytes(art)
                    Uri.fromFile(file)
                } else null
            } catch (e: Exception) {
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

        DisposableEffect(Unit) {
            onDispose {
                server.stopServer()
            }
        }

        LaunchedEffect(Unit) {
            server.setMusicList(musicList)
            server.start()
            server.onClientNameReceived = { name -> CoroutineScope(Dispatchers.Main).launch { clientName = name } }
            server.onConnected = { CoroutineScope(Dispatchers.Main).launch { isConnected = true } }
            server.onPlay = { value ->
                CoroutineScope(Dispatchers.Main).launch {
                    if (value == "PAUSED") {
                        isPlaying = false
                        return@launch
                    }
                    if (value == "RESUME") {
                        isPlaying = true
                        return@launch
                    }
                    val found = musicList.find { it.uri.toString() == value }
                    if (found != null) {
                        val intent = Intent(context, MusicService::class.java).apply {
                            action = MusicService.ACTION_PLAY
                            putExtra("MUSIC_URI", found.uri.toString())
                            putExtra("TITLE", found.title)
                            putExtra("DEVICE", "Client")
                        }
                        ContextCompat.startForegroundService(context, intent)
                        currentSongUri = found.uri.toString()
                        albumArtUri = getAlbumArt(context, found.uri)
                        playingTitle = found.title
                        isPlaying = true
                    }
                }
            }
            server.onDisconnected = {
                CoroutineScope(Dispatchers.Main).launch {
                    isConnected = false
                    clientName = "未接続"
                    playingTitle = null
                    currentSongUri = null
                    isPlaying = false
                    snackbarHostState.showSnackbar("接続が切断されました")
                    
                    // MusicServiceを停止
                    val stopIntent = Intent(context, MusicService::class.java).apply {
                        action = MusicService.ACTION_STOP
                    }
                    context.stopService(stopIntent)
                    
                    delay(500)
                    onCancel()
                }
            }
        }

        LaunchedEffect(isConnected) {
            while (isConnected) {
                val service = MusicService.instance
                if (service != null) {
                    isPlaying = service.isPlaying()
                    if (service.getDuration() > 0) {
                        currentPosition = service.getCurrentPosition()
                        duration = service.getDuration()
                    }
                }
                delay(500)
            }
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(if (isConnected) "接続成功" else "待機中")
                Text("接続相手: $clientName")
                Spacer(modifier = Modifier.height(16.dp))
                Image(
                    painter = rememberAsyncImagePainter(model = albumArtUri ?: R.drawable.no_image),
                    contentDescription = "Album Art",
                    modifier = Modifier.size(250.dp).padding(16.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.height(16.dp))
                playingTitle?.let {
                    Text("再生中: $it")
                    Button(
                        enabled = currentSongUri != null,
                        onClick = {
                        if (isPlaying) {
                            server.pauseMusic()
                        } else {
                            server.resumeMusic()
                        }
                    }) { Text(if (isPlaying) "⏸" else "▶") }
                }
                if (duration > 0) {
                    val sliderValue = currentPosition.toFloat() / duration.toFloat()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        Text(text = formatTime(currentPosition), modifier = Modifier.width(50.dp))
                        Slider(
                            value = sliderValue,
                            onValueChange = { currentPosition = (it * duration).toInt() },
                            onValueChangeFinished = { server.seekTo(currentPosition) },
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        Text(text = formatTime(duration), modifier = Modifier.width(50.dp))
                    }
                }
                Button(onClick = { 
                    server.stopServer()
                    // MusicServiceを明示的に停止
                    val stopIntent = Intent(context, MusicService::class.java).apply {
                        action = MusicService.ACTION_STOP
                    }
                    context.stopService(stopIntent)
                    onCancel() 
                }) { Text("切断") }
            }
        }
    }


    // ---------------- Music List ----------------
    fun getMusicList(context: Context): List<MusicItem> {
        val list = mutableListOf<MusicItem>()
        val volumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(context)
        } else setOf("external")

        for (volume in volumes) {
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            val collection = MediaStore.Audio.Media.getContentUri(volume)
            val storageType = if (volume == "external_primary" || volume == "external") "内部" else "SD"
            
            // APIレベルに応じたプロジェクションの設定
            val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.RELATIVE_PATH
                )
            } else {
                arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.DATA
                )
            }
            
            val cursor = context.contentResolver.query(collection, projection, selection, null, null)
            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)

                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val title = it.getString(titleColumn)
                    
                    val fileName: String
                    val folderPath: String
                    val relativePathForGrouping: String

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        fileName = it.getString(it.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)) ?: ""
                        folderPath = it.getString(it.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)) ?: ""
                        relativePathForGrouping = folderPath
                    } else {
                        val fullPath = it.getString(it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)) ?: ""
                        fileName = fullPath.substringAfterLast("/")
                        val dir = fullPath.substringBeforeLast("/", "")
                        folderPath = if (dir.isNotEmpty()) "$dir/" else ""
                        relativePathForGrouping = dir
                    }

                    // 要望の形式: フォルダ/ファイル名/曲名
                    val displayPath = "${folderPath}${fileName}/${title}"

                    val contentUri = Uri.withAppendedPath(collection, id.toString())
                    val folder = relativePathForGrouping.removeSuffix("/").substringAfterLast("/", "Unknown")
                    
                    list.add(MusicItem(
                        title = title, 
                        uri = contentUri, 
                        folder = folder.ifEmpty { "Root" }, 
                        path = displayPath, 
                        storage = storageType
                    ))
                }
            }
        }
        return list
    }
}