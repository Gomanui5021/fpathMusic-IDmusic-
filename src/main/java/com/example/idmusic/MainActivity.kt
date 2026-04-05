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
import android.content.ContentUris

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        setContent {
            val context = LocalContext.current
            var isPermissionGranted by remember { mutableStateOf(false) }
            val snackbarHostState = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()

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
                            .offset(y = (-180).dp)
                    )
                }
            } else {
                var role by remember { mutableStateOf<String?>(null) }
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        when (role) {
                            null -> RoleSelectScreen { role = it }
                            "CLIENT" -> MusicScreen(notificationLauncher, snackbarHostState) { role = null }
                            "SERVER" -> ServerScreen(isBluetoothEnabled = true, snackbarHostState, onStopCommunication = { role = "LOCAL" }) { role = null }
                            "LOCAL" -> ServerScreen(isBluetoothEnabled = false, snackbarHostState, onConnectBluetooth = { role = "SERVER" }) { role = null }
                        }
                    }
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

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { onSelect("SERVER") },
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                ) {
                    Text("受信側")
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { onSelect("LOCAL") },
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                ) {
                    Text("ローカル再生")
                }
            }
        }
    }

    // ---------------- Client ----------------
    @Composable
    fun MusicScreen(
        notificationLauncher: androidx.activity.compose.ManagedActivityResultLauncher<String, Boolean>,
        snackbarHostState: SnackbarHostState,
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
        val scope = rememberCoroutineScope()
        var connectingDevice by remember { mutableStateOf<BluetoothDevice?>(null) }

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

        fun playSong(song: MusicItem) {
            bluetoothClient.sendPlay(song.uri.toString())
            currentSongTitle = song.title
            currentSongUri = song.uri.toString()
            isPlaying = true
            duration = 0
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
                putExtra("ALBUM_ID", song.albumId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun skipNext() {
            val currentIndex = musicItems.indexOfFirst { it.uri.toString() == currentSongUri }
            if (currentIndex != -1 && currentIndex < musicItems.size - 1) {
                playSong(musicItems[currentIndex + 1])
            }
        }

        fun skipPrevious() {
            val currentIndex = musicItems.indexOfFirst { it.uri.toString() == currentSongUri }
            if (currentIndex > 0) {
                playSong(musicItems[currentIndex - 1])
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
                // 即座に停止と遷移を行う
                isConnected = false
                val intent = Intent(context, MusicService::class.java)
                context.stopService(intent)
                
                scope.launch {
                    onDisconnect()
                    snackbarHostState.showSnackbar("接続終了")
                }
            }

            bluetoothClient.onReceiveMusicList = { list: List<MusicItem> ->
                musicItems = list.map { it.copy() }
            }

            bluetoothClient.onReceiveMessage = { message ->
                scope.launch {
                    when {
                        message.startsWith("STATE:") -> {
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
                            context.startService(intent)
                        }
                        message.startsWith("PLAY:") -> {
                            val uri = message.removePrefix("PLAY:")
                            val song = musicItems.find { it.uri.toString() == uri }
                            if (song != null) {
                                currentSongTitle = song.title
                                currentSongUri = song.uri.toString()
                                isPlaying = true
                                duration = 0
                                currentPosition = 0

                                val intent = Intent(context, MusicService::class.java).apply {
                                    action = MusicService.ACTION_UPDATE_STATE
                                    putExtra("IS_PLAYING", true)
                                    putExtra("TITLE", song.title)
                                    putExtra("DEVICE", connectedDeviceName)
                                    putExtra("DURATION", 0)
                                    putExtra("ALBUM_ID", song.albumId)
                                }
                                context.startService(intent)
                            }
                        }
                        message == "NEXT" -> skipNext()
                        message == "PREVIOUS" -> skipPrevious()
                    }
                }
            }

            bluetoothClient.onProgress = { pos, dur ->
                val now = System.currentTimeMillis()
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
                    context.startService(intent)
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


        Column(
            modifier = Modifier
                .fillMaxSize()
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
                                Text(text = if (isExpanded) "📂 $folder" else "📁 $folder")
                            }
                        }
                        if (isExpanded) {
                            items(songs) { song ->
                                val isCurrent = song.uri.toString() == currentSongUri
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, Color.Black, RoundedCornerShape(2.dp))
                                        .clickable { playSong(song) }
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
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
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { skipPrevious() }) {
                                Text("⏮", fontSize = 24.sp)
                            }
                            Spacer(modifier = Modifier.width(16.dp))
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
                                    context.startService(intent)
                                }
                            ) { Text(if (isPlaying) "⏸" else "▶", fontSize = 20.sp) }
                            Spacer(modifier = Modifier.width(16.dp))
                            IconButton(onClick = { skipNext() }) {
                                Text("⏭", fontSize = 24.sp)
                            }
                        }

                        val sliderValue = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        ) {
                            Text(text = formatTime(currentPosition), modifier = Modifier.width(45.dp), fontSize = 12.sp)
                            Slider(
                                value = sliderValue,
                                onValueChange = { if (duration > 0) currentPosition = (it * duration).toInt() },
                                onValueChangeFinished = { if (duration > 0) bluetoothClient.sendSeek(currentPosition) },
                                modifier = Modifier.weight(1f),
                                enabled = duration > 0
                            )
                            Text(text = formatTime(duration), modifier = Modifier.width(45.dp), fontSize = 12.sp)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = {
                                bluetoothClient.sendDisconnect()
                            }) { Text("切断") }
                        }
                    }
                }
            }
        }
    }

    // ---------------- Server / Local ----------------
    @Composable
    fun ServerScreen(
        isBluetoothEnabled: Boolean, 
        snackbarHostState: SnackbarHostState,
        onConnectBluetooth: (() -> Unit)? = null, 
        onStopCommunication: (() -> Unit)? = null,
        onCancel: () -> Unit
    ) {
        val context = LocalContext.current
        val server = remember { if (isBluetoothEnabled) BluetoothServer(context) else null }

        var clientName by remember { mutableStateOf(if (isBluetoothEnabled) "未接続" else "ローカル") }
        var isConnected by remember { mutableStateOf(!isBluetoothEnabled) }
        var playingTitle by remember { mutableStateOf<String?>(null) }
        var currentSongUri by remember { mutableStateOf<String?>(null) }
        var isPlaying by remember { mutableStateOf(false) }
        var currentPosition by remember { mutableStateOf(0) }
        var duration by remember { mutableStateOf(0) }
        val musicList = remember { getMusicList(context) }
        var selectedTab by remember { mutableStateOf(0) }
        val scope = rememberCoroutineScope()

        fun getAlbumArtUri(albumId: Long): Uri {
            val artworkUri = Uri.parse("content://media/external/audio/albumart")
            return ContentUris.withAppendedId(artworkUri, albumId)
        }

        var albumArtUri by remember { mutableStateOf<Uri?>(null) }

        fun formatTime(ms: Int): String {
            val totalSec = ms / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            return "%02d:%02d".format(min, sec)
        }

        fun playSong(uri: String) {
            val found = musicList.find { it.uri.toString() == uri }
            if (found != null) {
                val intent = Intent(context, MusicService::class.java).apply {
                    action = MusicService.ACTION_PLAY
                    putExtra("MUSIC_URI", found.uri.toString())
                    putExtra("TITLE", found.title)
                    putExtra("DEVICE", if (isBluetoothEnabled) "[FpathMusic]" else "Local")
                    putExtra("ALBUM_ID", found.albumId)
                }
                ContextCompat.startForegroundService(context, intent)
                currentSongUri = found.uri.toString()
                albumArtUri = getAlbumArtUri(found.albumId)
                playingTitle = found.title
                isPlaying = true
                
                server?.sendMessage("PLAY:${found.uri}")
                server?.sendMessage("STATE:PLAYING")
            }
        }

        fun skipNext() {
            val currentIndex = musicList.indexOfFirst { it.uri.toString() == currentSongUri }
            if (currentIndex != -1 && currentIndex < musicList.size - 1) {
                val nextSong = musicList[currentIndex + 1]
                playSong(nextSong.uri.toString())
            }
        }

        fun skipPrevious() {
            val currentIndex = musicList.indexOfFirst { it.uri.toString() == currentSongUri }
            if (currentIndex > 0) {
                val prevSong = musicList[currentIndex - 1]
                playSong(prevSong.uri.toString())
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                server?.stopServer()
            }
        }

        LaunchedEffect(Unit) {
            if (isBluetoothEnabled && server != null) {
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
                        playSong(value)
                    }
                }
                server.onNext = { CoroutineScope(Dispatchers.Main).launch { skipNext() } }
                server.onPrevious = { CoroutineScope(Dispatchers.Main).launch { skipPrevious() } }
                server.onDisconnected = {
                    CoroutineScope(Dispatchers.Main).launch {
                        isConnected = false
                        // 停止処理を最優先
                        val stopIntent = Intent(context, MusicService::class.java)
                        context.stopService(stopIntent)
                        
                        // 画面を戻す
                        onCancel()
                        
                        // スナックバー表示
                        snackbarHostState.showSnackbar("接続が切断されました")
                    }
                }
            }
        }

        LaunchedEffect(isConnected) {
            while (true) {
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
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(painterResource(android.R.drawable.ic_media_play), "再生メディア") },
                        label = { Text("再生メディア") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(painterResource(android.R.drawable.ic_menu_agenda), "曲リスト") },
                        label = { Text("曲リスト") }
                    )
                }
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (selectedTab == 0) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (!isBluetoothEnabled && onConnectBluetooth != null) {
                            Button(onClick = onConnectBluetooth, modifier = Modifier.padding(bottom = 16.dp)) {
                                Text("デバイスと通信する")
                            }
                        } else if (isBluetoothEnabled && !isConnected && onStopCommunication != null) {
                            Button(onClick = {
                                server?.stopServer()
                                onStopCommunication()
                            }, modifier = Modifier.padding(bottom = 16.dp)) {
                                Text("通信停止")
                            }
                        }

                        Text(if (isBluetoothEnabled) (if (isConnected) "接続相手: $clientName" else "接続待機中...") else "ローカル再生モード")
                        Spacer(modifier = Modifier.height(16.dp))
                        Image(
                            painter = rememberAsyncImagePainter(
                                model = albumArtUri ?: R.drawable.no_image,
                                error = painterResource(R.drawable.no_image),
                                fallback = painterResource(R.drawable.no_image)
                            ),
                            contentDescription = "Album Art",
                            modifier = Modifier.size(250.dp).padding(16.dp),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        playingTitle?.let {
                            Text("再生中: $it", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                IconButton(onClick = { skipPrevious() }) {
                                    Text("⏮", fontSize = 28.sp)
                                }
                                Spacer(modifier = Modifier.width(24.dp))
                                Button(
                                    onClick = {
                                        if (isPlaying) {
                                            if (isBluetoothEnabled) server?.pauseMusic() else {
                                                val intent = Intent(context, MusicService::class.java).apply { action = MusicService.ACTION_PAUSE }
                                                context.startService(intent)
                                            }
                                        } else {
                                            if (isBluetoothEnabled) server?.resumeMusic() else {
                                                val intent = Intent(context, MusicService::class.java).apply { action = MusicService.ACTION_RESUME }
                                                context.startService(intent)
                                            }
                                        }
                                    }
                                ) { Text(if (isPlaying) "⏸" else "▶", fontSize = 22.sp) }
                                Spacer(modifier = Modifier.width(24.dp))
                                IconButton(onClick = { skipNext() }) {
                                    Text("⏭", fontSize = 28.sp)
                                }
                            }
                        }
                        
                        if (duration > 0) {
                            val sliderValue = currentPosition.toFloat() / duration.toFloat()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
                            ) {
                                Text(text = formatTime(currentPosition), modifier = Modifier.width(45.dp), fontSize = 12.sp)
                                Slider(
                                    value = sliderValue,
                                    onValueChange = { currentPosition = (it * duration).toInt() },
                                    onValueChangeFinished = { 
                                        if (isBluetoothEnabled) server?.seekTo(currentPosition) else {
                                            val intent = Intent(context, MusicService::class.java).apply {
                                                action = MusicService.ACTION_SEEK
                                                putExtra("SEEK_POS", currentPosition)
                                            }
                                            context.startService(intent)
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                Text(text = formatTime(duration), modifier = Modifier.width(45.dp), fontSize = 12.sp)
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                            Button(onClick = { 
                                server?.stopServer()
                                val stopIntent = Intent(context, MusicService::class.java)
                                context.stopService(stopIntent)
                                onCancel() 
                            }) { Text(if (isBluetoothEnabled) "切断" else "終了") }
                        }
                    }
                } else {
                    val grouped = musicList.groupBy { it.folder }
                    val expandedState = remember { mutableStateMapOf<String, Boolean>() }

                    LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                                    Text(text = if (isExpanded) "📂 $folder" else "📁 $folder")
                                }
                            }
                            if (isExpanded) {
                                items(songs) { song ->
                                    val isCurrent = song.uri.toString() == currentSongUri
                                    
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(1.dp, Color.Black, RoundedCornerShape(2.dp))
                                            .clickable { playSong(song.uri.toString()) }
                                            .padding(12.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(modifier = Modifier.weight(1f)) {
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
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Image(
                                                painter = rememberAsyncImagePainter(
                                                    model = getAlbumArtUri(song.albumId),
                                                    error = painterResource(R.drawable.no_image),
                                                    placeholder = painterResource(R.drawable.no_image)
                                                ),
                                                contentDescription = "Song Album Art",
                                                modifier = Modifier.size(48.dp).border(0.5.dp, Color.LightGray),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                    }
                                    HorizontalDivider(thickness = 2.dp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
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
            
            val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    MediaStore.Audio.Media.ALBUM_ID
                )
            } else {
                arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.ALBUM_ID
                )
            }
            
            val cursor = context.contentResolver.query(collection, projection, selection, null, null)
            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val albumIdColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val title = it.getString(titleColumn)
                    val albumId = it.getLong(albumIdColumn)
                    
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

                    val displayPath = "${folderPath}${fileName}/${title}"

                    val contentUri = Uri.withAppendedPath(collection, id.toString())
                    val folder = relativePathForGrouping.removeSuffix("/").substringAfterLast("/", "Unknown")
                    
                    list.add(MusicItem(
                        title = title, 
                        uri = contentUri, 
                        folder = folder.ifEmpty { "Root" }, 
                        path = displayPath, 
                        storage = storageType,
                        albumId = albumId
                    ))
                }
            }
        }
        return list.sortedWith(compareBy({ it.folder }, { it.title }))
    }
}