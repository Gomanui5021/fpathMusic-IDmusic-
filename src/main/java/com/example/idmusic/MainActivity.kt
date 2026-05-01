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
import android.widget.Toast
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
import androidx.compose.ui.draw.scale
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import com.example.idmusic.ui.theme.IDmusicTheme
import androidx.compose.ui.graphics.ColorFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.ui.text.style.TextAlign

class MainActivity : ComponentActivity() {

    companion object {
        fun getMusicListStatic(context: Context): List<MusicItem> {
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

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        setContent {
            IDmusicTheme {
                val context = LocalContext.current
                var isPermissionGranted by remember { mutableStateOf(false) }
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
                    Scaffold { padding ->
                        Box(modifier = Modifier.padding(padding)) {
                            when (role) {
                                null -> RoleSelectScreen { role = it }
                                "CLIENT" -> MusicScreen(notificationLauncher) { role = null }
                                "SERVER" -> ServerScreen(isBluetoothEnabled = true, onStopCommunication = { role = "LOCAL" }) { role = null }
                                "LOCAL" -> ServerScreen(isBluetoothEnabled = false, onConnectBluetooth = { role = "SERVER" }) { role = null }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        notifyForegroundStatus(true)
    }

    override fun onStop() {
        super.onStop()
        notifyForegroundStatus(false)
    }

    private fun notifyForegroundStatus(isForeground: Boolean) {
        if (MusicService.instance != null) {
            val intent = Intent(this, MusicService::class.java).apply {
                action = MusicService.ACTION_SET_FOREGROUND
                putExtra(MusicService.EXTRA_IS_FOREGROUND, isForeground)
            }
            startService(intent)
        }
    }

    // ---------------- Role Select ----------------
    @Composable
    fun RoleSelectScreen(onSelect: (String) -> Unit) {
        val context = LocalContext.current
        val view = LocalView.current
        
        // バージョン名の取得
        val versionName = remember {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
                } else {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }
            } catch (e: Exception) {
                "Unknown"
            }
        }

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

            Box(contentAlignment = Alignment.BottomEnd) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = "App Logo",
                    modifier = Modifier
                        .size(500.dp)
                        .padding(bottom = 32.dp)
                )
                Text(
                    text = "Ver $versionName",
                    modifier = Modifier.padding(bottom = 48.dp, end = 32.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.Gray
                )
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center
            ) {
                OutlinedButton(
                    onClick = { onSelect("CLIENT") },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    border = BorderStroke(1.dp, Color.Black),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color.Black)
                ) {
                    Text("送信側")
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { onSelect("SERVER") },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    border = BorderStroke(1.dp, Color.Black),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color.Black)
                ) {
                    Text("受信側")
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { onSelect("LOCAL") },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    border = BorderStroke(1.dp, Color.Black),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color.Black)
                ) {
                    Text("ローカル再生")
                }
            }
        }
    }

    // ---------------- Client ----------------
    @OptIn(ExperimentalMaterial3Api::class)
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
        val scope = rememberCoroutineScope()
        var connectingDevice by remember { mutableStateOf<BluetoothDevice?>(null) }

        var musicItems by remember { mutableStateOf<List<MusicItem>>(emptyList()) }
        var isRepeatEnabled by remember { mutableStateOf(false) }
        var isShuffleEnabled by remember { mutableStateOf(false) }
        var selectedTab by remember { mutableStateOf(0) }
        var serverOutputDevice by remember { mutableStateOf("取得中...") }
        var serverAudioFormat by remember { mutableStateOf("未取得") }
        var serverAudioCodec by remember { mutableStateOf("取得中...") }
        
        // 音量同期用
        var serverVolume by remember { mutableStateOf(0) }
        var serverMaxVolume by remember { mutableStateOf(15) }
        
        // アルバムアートキャッシュ管理用カウンター (再描画トリガー)
        var artUpdateCounter by remember { mutableStateOf(0) }
        val cacheFolder = remember { File(context.filesDir, "Fpathmusic") }

        val view = LocalView.current
        SideEffect {
            val window = (view.context as android.app.Activity).window
            window.statusBarColor = android.graphics.Color.WHITE
            WindowCompat.getInsetsController(window, view)?.isAppearanceLightStatusBars = true
        }

        fun playSong(song: MusicItem) {
            bluetoothClient.sendPlay(song.uri.toString())
            currentSongTitle = song.title
            currentSongUri = song.uri.toString()
            isPlaying = true
            duration = 0
            currentPosition = 0
            
            // 再生開始時に、もしキャッシュがあればMusicServiceに即適用
            if (song.albumId > 0) {
                bluetoothClient.getCachedArt(song.albumId)?.let {
                    MusicService.instance?.updateCurrentBitmap(it)
                }
            }

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
            val currentSong = musicItems.find { it.uri.toString() == currentSongUri }
            if (isShuffleEnabled && currentSong != null) {
                val folderSongs = musicItems.filter { it.folder == currentSong.folder }
                if (folderSongs.isNotEmpty()) {
                    playSong(folderSongs.random())
                    return
                }
            }
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

        LaunchedEffect(isRepeatEnabled, isShuffleEnabled, musicItems, currentSongUri) {
            MusicService.onTrackEnded = {
                if (isRepeatEnabled) {
                    val currentSong = musicItems.find { it.uri.toString() == currentSongUri }
                    if (currentSong != null) {
                        scope.launch { playSong(currentSong) }
                    } else {
                        scope.launch { skipNext() }
                    }
                } else {
                    scope.launch { skipNext() }
                }
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                bluetoothClient.disconnect()
                MusicService.onTrackEnded = null
            }
        }

        LaunchedEffect(Unit) {

            bluetoothClient.onConnected = {
                scope.launch {
                    isConnected = true
                    connectingDevice = null
                    Toast.makeText(context, "接続成功", Toast.LENGTH_SHORT).show()
                    
                    val intent = Intent(context, MusicService::class.java).apply {
                        action = MusicService.ACTION_UPDATE_STATE
                        putExtra("IS_PLAYING", false)
                        putExtra("TITLE", "接続済み")
                        putExtra("DEVICE", connectedDeviceName)
                    }
                    context.startService(intent)
                }
            }


            bluetoothClient.onDisconnected = {
                isConnected = false
                connectingDevice = null
                val intent = Intent(context, MusicService::class.java)
                context.stopService(intent)
                
                scope.launch {
                    Toast.makeText(context, "切断されました", Toast.LENGTH_SHORT).show()
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
                                
                                // 受信側での再生開始に合わせてキャッシュがあれば適用
                                bluetoothClient.getCachedArt(song.albumId)?.let {
                                    MusicService.instance?.updateCurrentBitmap(it)
                                }

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
                        message.startsWith("SET_REPEAT:") -> {
                            isRepeatEnabled = message.removePrefix("SET_REPEAT:") == "ON"
                        }
                        message.startsWith("SET_SHUFFLE:") -> {
                            isShuffleEnabled = message.removePrefix("SET_SHUFFLE:") == "ON"
                        }
                        message.startsWith("OUTPUT_DEVICE:") -> {
                            serverOutputDevice = message.removePrefix("OUTPUT_DEVICE:")
                        }
                        message.startsWith("AUDIO_FORMAT:") -> {
                            serverAudioFormat = message.removePrefix("AUDIO_FORMAT:")
                        }
                        message.startsWith("AUDIO_CODEC:") -> {
                            serverAudioCodec = message.removePrefix("AUDIO_CODEC:")
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
            
            bluetoothClient.onVolumeReceived = { current, max ->
                serverVolume = current
                serverMaxVolume = max
            }
            
            bluetoothClient.onAlbumArtReceived = { albumId, bitmap ->
                // 新しい画像が保存されたらリストを更新させる
                artUpdateCounter++
                // 再生中の曲なら即適用
                if (musicItems.find { it.uri.toString() == currentSongUri }?.albumId == albumId) {
                    MusicService.instance?.updateCurrentBitmap(bitmap)
                }
            }

            bluetoothClient.onError = { msg ->
                scope.launch {
                    connectingDevice = null
                    val intent = Intent(context, MusicService::class.java)
                    context.stopService(intent)
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
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
            bottomBar = {
                if (isConnected) {
                    NavigationBar(containerColor = Color.White) {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = { Icon(painterResource(R.drawable.play), "再生メディア", tint = if(selectedTab==0) Color.Black else Color.Gray, modifier = Modifier.size(35.dp)) },
                            label = { Text("再生メディア", color = if(selectedTab==0) Color.Black else Color.Gray) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = Color.LightGray)
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = { Icon(painterResource(R.drawable.lists), "曲リスト", tint = if(selectedTab==1) Color.Black else Color.Gray, modifier = Modifier.size(35.dp)) },
                            label = { Text("曲リスト", color = if(selectedTab==1) Color.Black else Color.Gray) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = Color.LightGray)
                        )
                        NavigationBarItem(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            icon = { Icon(painterResource(android.R.drawable.ic_menu_info_details), "再生ステータス", tint = if(selectedTab==2) Color.Black else Color.Gray, modifier = Modifier.size(35.dp)) },
                            label = { Text("再生ステータス", color = if(selectedTab==2) Color.Black else Color.Gray) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = Color.LightGray)
                        )
                    }
                }
            }
        ) { padding ->
            val expandedState = remember { mutableStateMapOf<String, Boolean>() }
            
            // 共通の再生コントロール（メニューボックス）
            val playbackControlBox = @Composable { showSettings: Boolean ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    elevation = CardDefaults.cardElevation(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                        if (currentSongTitle != null) {
                            Text(
                                text = "♪: $currentSongTitle",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                                )
                        }
                        
                        Text("接続デバイス: $connectedDeviceName", color = Color.Black)

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { skipPrevious() }) {
                                Icon(
                                    painter = painterResource(R.drawable.skip_left),
                                    contentDescription = "Skip Previous", 
                                    tint = Color.Black,
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            // 再生・一時停止ボタン
                            OutlinedButton(
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
                                },
                                modifier = Modifier.width(90.dp).height(56.dp),
                                shape = RoundedCornerShape(percent = 50),
                                border = BorderStroke(1.dp, Color.Black),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(
                                    painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                                    contentDescription = "Play/Pause",
                                    tint = Color.Black,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            IconButton(onClick = { skipNext() }) {
                                Icon(
                                    painter = painterResource(R.drawable.skip_right),
                                    contentDescription = "Skip Next", 
                                    tint = Color.Black,
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                        }

                        val sliderValue = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        ) {
                            Text(text = formatTime(currentPosition), modifier = Modifier.width(45.dp), fontSize = 12.sp, color = Color.Black)
                            Slider(
                                value = sliderValue,
                                onValueChange = { if (duration > 0) currentPosition = (it * duration).toInt() },
                                onValueChangeFinished = { if (duration > 0) bluetoothClient.sendSeek(currentPosition) },
                                modifier = Modifier.weight(1f),
                                enabled = duration > 0,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.Black, 
                                    activeTrackColor = Color.Black,
                                    inactiveTrackColor = Color.LightGray
                                ),
                                thumb = {
                                    Surface(
                                        modifier = Modifier.size(12.dp),
                                        shape = CircleShape,
                                        color = Color.Black
                                    ) {}
                                },
                                track = { sliderState ->
                                    SliderDefaults.Track(
                                        sliderState = sliderState,
                                        modifier = Modifier.height(2.dp),
                                        colors = SliderDefaults.colors(
                                            activeTrackColor = Color.Black,
                                            inactiveTrackColor = Color.LightGray
                                        )
                                    )
                                }
                            )
                            Text(text = formatTime(duration), modifier = Modifier.width(45.dp), fontSize = 12.sp, color = Color.Black)
                        }
                        
                        // サーバー音量操作 (プラスマイナスボタンとスライダー)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        ) {
                            IconButton(onClick = { 
                                if (serverVolume > 0) {
                                    serverVolume--
                                    bluetoothClient.sendVolumeSet(serverVolume)
                                }
                            }, modifier = Modifier.size(45.dp)) {
                                Icon(
                                    painter = painterResource(R.drawable.volume_down),
                                    contentDescription = "Volume Down", 
                                    modifier = Modifier.fillMaxSize(),
                                    tint = Color.Black
                                )
                            }
                            
                            Spacer(modifier = Modifier.width(16.dp))

                            Slider(
                                value = serverVolume.toFloat(),
                                onValueChange = { 
                                    serverVolume = it.toInt()
                                    bluetoothClient.sendVolumeSet(serverVolume)
                                },
                                valueRange = 0f..serverMaxVolume.toFloat(),
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.Black, 
                                    activeTrackColor = Color.Black,
                                    inactiveTrackColor = Color.LightGray
                                ),
                                thumb = {
                                    Surface(
                                        modifier = Modifier.size(12.dp),
                                        shape = CircleShape,
                                        color = Color.Black
                                    ) {}
                                },
                                track = { sliderState ->
                                    SliderDefaults.Track(
                                        sliderState = sliderState,
                                        modifier = Modifier.height(2.dp),
                                        colors = SliderDefaults.colors(
                                            activeTrackColor = Color.Black,
                                            inactiveTrackColor = Color.LightGray
                                        )
                                    )
                                }
                            )
                            
                            Spacer(modifier = Modifier.width(16.dp))

                            IconButton(onClick = { 
                                if (serverVolume < serverMaxVolume) {
                                    serverVolume++
                                    bluetoothClient.sendVolumeSet(serverVolume)
                                }
                            }, modifier = Modifier.size(45.dp)) {
                                Icon(
                                    painter = painterResource(R.drawable.volume_up),
                                    contentDescription = "Volume Up", 
                                    modifier = Modifier.fillMaxSize(),
                                    tint = Color.Black
                                )
                            }
                            Text(text = "$serverVolume", modifier = Modifier.width(20.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End, color = Color.Black)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (showSettings) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    IconButton(onClick = {
                                        isRepeatEnabled = !isRepeatEnabled
                                        bluetoothClient.sendMessage("SET_REPEAT:${if (isRepeatEnabled) "ON" else "OFF"}")
                                    }) {
                                        Icon(
                                            painter = painterResource(R.drawable.repeat),
                                            contentDescription = "Repeat",
                                            tint = if (isRepeatEnabled) Color.Black else Color.Gray,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    IconButton(onClick = {
                                        isShuffleEnabled = !isShuffleEnabled
                                        bluetoothClient.sendMessage("SET_SHUFFLE:${if (isShuffleEnabled) "ON" else "OFF"}")
                                    }) {
                                        Icon(
                                            painter = painterResource(R.drawable.shuffle),
                                            contentDescription = "Shuffle",
                                            tint = if (isShuffleEnabled) Color.Black else Color.Gray,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            } else {
                                // 設定非表示時は空白を埋めるためのダミー
                                Spacer(modifier = Modifier.width(24.dp))
                            }
                            TextButton(onClick = {
                                bluetoothClient.sendDisconnect()
                            }, colors = ButtonDefaults.textButtonColors(contentColor = Color.Black)) { Text("切断") }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (!isConnected) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        OutlinedButton(
                            onClick = { onDisconnect() },
                            border = BorderStroke(1.dp, Color.Black),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color.Black)
                        ) {
                            Text("← 戻る")
                        }
                    }

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(deviceList) { device ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        connectingDevice = device
                                        connectedDeviceName = device.name ?: "不明"
                                        Toast.makeText(context, "${device.name ?: "デバイス"} へ接続中...", Toast.LENGTH_SHORT).show()
                                        
                                        val intent = Intent(context, MusicService::class.java).apply {
                                            action = MusicService.ACTION_UPDATE_STATE
                                            putExtra("IS_PLAYING", false)
                                            putExtra("TITLE", "接続中...")
                                            putExtra("DEVICE", device.name ?: "不明")
                                        }
                                        ContextCompat.startForegroundService(context, intent)

                                        scope.launch(Dispatchers.IO) {
                                            bluetoothClient.connect(device)
                                        }
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = device.name ?: "不明", modifier = Modifier.weight(1f))
                                if (connectingDevice == device) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.Black)
                                }
                            }
                        }
                    }
                } else if (selectedTab == 0) {
                    // 再生メディアタブ
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("接続デバイス: $connectedDeviceName", color = Color.Black)
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        key(currentSongUri, artUpdateCounter) {
                            val currentSong = musicItems.find { it.uri.toString() == currentSongUri }
                            val artFile = currentSong?.let { File(cacheFolder, "${it.albumId}.webp") }
                            Image(
                                painter = rememberAsyncImagePainter(
                                    model = if (artFile?.exists() == true) artFile else R.drawable.no_image,
                                    error = painterResource(R.drawable.no_image),
                                    placeholder = painterResource(R.drawable.no_image)
                                ),
                                contentDescription = "Album Art",
                                modifier = Modifier.size(250.dp).padding(16.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        currentSongTitle?.let { title ->
                            Text("再生中: $title", style = MaterialTheme.typography.titleMedium, color = Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                IconButton(onClick = { skipPrevious() }) {
                                    Icon(
                                        painter = painterResource(R.drawable.skip_left), 
                                        contentDescription = "Skip Previous", 
                                        tint = Color.Black,
                                        modifier = Modifier.size(48.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(24.dp))
                                // 再生・一時停止ボタン
                                OutlinedButton(
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
                                    },
                                    modifier = Modifier.width(90.dp).height(56.dp),
                                    shape = RoundedCornerShape(percent = 50),
                                    border = BorderStroke(1.dp, Color.Black),
                                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
                                    contentPadding = PaddingValues(0.dp)
                                ) { 
                                    Icon(
                                        painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                                        contentDescription = "Play/Pause",
                                        tint = Color.Black,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(24.dp))
                                IconButton(onClick = { skipNext() }) {
                                    Icon(
                                        painter = painterResource(R.drawable.skip_right), 
                                        contentDescription = "Skip Next", 
                                        tint = Color.Black,
                                        modifier = Modifier.size(48.dp)
                                    )
                                }
                            }
                        }

                        if (duration > 0) {
                            val sliderValue = currentPosition.toFloat() / duration.toFloat()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
                            ) {
                                Text(text = formatTime(currentPosition), modifier = Modifier.width(45.dp), fontSize = 12.sp, color = Color.Black)
                                Slider(
                                    value = sliderValue,
                                    onValueChange = { currentPosition = (it * duration).toInt() },
                                    onValueChangeFinished = { bluetoothClient.sendSeek(currentPosition) },
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color.Black, 
                                        activeTrackColor = Color.Black,
                                        inactiveTrackColor = Color.LightGray
                                    ),
                                    thumb = { Surface(modifier = Modifier.size(12.dp), shape = CircleShape, color = Color.Black) {} },
                                    track = { SliderDefaults.Track(it, modifier = Modifier.height(2.dp), colors = SliderDefaults.colors(activeTrackColor = Color.Black, inactiveTrackColor = Color.LightGray)) }
                                )
                                Text(text = formatTime(duration), modifier = Modifier.width(45.dp), fontSize = 12.sp, color = Color.Black)
                            }
                        }
                        
                        // サーバー音量操作
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                        ) {
                            IconButton(onClick = { 
                                if (serverVolume > 0) {
                                    serverVolume--
                                    bluetoothClient.sendVolumeSet(serverVolume)
                                }
                            }, modifier = Modifier.size(45.dp)) {
                                Icon(
                                    painter = painterResource(R.drawable.volume_down),
                                    contentDescription = "Volume Down", 
                                    modifier = Modifier.fillMaxSize(),
                                    tint = Color.Black
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Slider(
                                value = serverVolume.toFloat(),
                                onValueChange = { 
                                    serverVolume = it.toInt()
                                    bluetoothClient.sendVolumeSet(serverVolume)
                                },
                                valueRange = 0f..serverMaxVolume.toFloat(),
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.Black, 
                                    activeTrackColor = Color.Black,
                                    inactiveTrackColor = Color.LightGray
                                ),
                                thumb = { Surface(modifier = Modifier.size(12.dp), shape = CircleShape, color = Color.Black) {} },
                                track = { SliderDefaults.Track(it, modifier = Modifier.height(2.dp), colors = SliderDefaults.colors(activeTrackColor = Color.Black, inactiveTrackColor = Color.LightGray)) }
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            IconButton(onClick = { 
                                if (serverVolume < serverMaxVolume) {
                                    serverVolume++
                                    bluetoothClient.sendVolumeSet(serverVolume)
                                }
                            }, modifier = Modifier.size(45.dp)) {
                                Icon(
                                    painter = painterResource(R.drawable.volume_up),
                                    contentDescription = "Volume Up", 
                                    modifier = Modifier.fillMaxSize(),
                                    tint = Color.Black
                                )
                            }
                            Text(text = "$serverVolume", modifier = Modifier.width(20.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End, color = Color.Black)
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(onClick = {
                                    isRepeatEnabled = !isRepeatEnabled
                                    bluetoothClient.sendMessage("SET_REPEAT:${if (isRepeatEnabled) "ON" else "OFF"}")
                                }) {
                                    Icon(
                                        painter = painterResource(R.drawable.repeat),
                                        contentDescription = "Repeat",
                                        tint = if (isRepeatEnabled) Color.Black else Color.Gray,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                IconButton(onClick = {
                                    isShuffleEnabled = !isShuffleEnabled
                                    bluetoothClient.sendMessage("SET_SHUFFLE:${if (isShuffleEnabled) "ON" else "OFF"}")
                                }) {
                                    Icon(
                                        painter = painterResource(R.drawable.shuffle),
                                        contentDescription = "Shuffle",
                                        tint = if (isShuffleEnabled) Color.Black else Color.Gray,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = { 
                                    bluetoothClient.sendDisconnect()
                                },
                                border = BorderStroke(1.dp, Color.Black),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color.Black)
                            ) { Text("切断") }
                        }
                    }
                } else if (selectedTab == 1) {
                    // 曲リストタブ
                    Column(modifier = Modifier.fillMaxSize()) {
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
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                painter = painterResource(if (isExpanded) R.drawable.folder_open else R.drawable.folder),
                                                contentDescription = null,
                                                modifier = Modifier.size(24.dp),
                                                tint = Color.Black
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(text = folder, color = Color.Black)
                                        }
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
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        if (isCurrent) {
                                                            Icon(
                                                                painter = painterResource(R.drawable.play),
                                                                contentDescription = null,
                                                                modifier = Modifier.size(16.dp),
                                                                tint = Color.Black
                                                            )
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                        }
                                                        Text(text = song.title, color = if (isCurrent) Color.Black else Color.DarkGray)
                                                    }
                                                    Text(
                                                        text = "[${song.storage}] ${song.path}",
                                                        fontSize = 12.sp,
                                                        color = Color.Gray.copy(alpha = 0.7f),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                key(song.albumId, artUpdateCounter) {
                                                    val artFile = File(cacheFolder, "${song.albumId}.webp")
                                                    Image(
                                                        painter = rememberAsyncImagePainter(
                                                            model = if (artFile.exists()) artFile else R.drawable.no_image,
                                                            error = painterResource(R.drawable.no_image),
                                                            placeholder = painterResource(R.drawable.no_image)
                                                        ),
                                                        contentDescription = "Song Album Art",
                                                        modifier = Modifier.size(48.dp).border(0.5.dp, Color.LightGray),
                                                        contentScale = ContentScale.Crop
                                                    )
                                                }
                                            }
                                        }
                                        HorizontalDivider(thickness = 2.dp, color = Color.Gray)
                                    }
                                }
                            }
                        }
                        playbackControlBox(false)
                    }
                } else {
                    // 再生ステータスタブ
                    Column(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(text = "サーバー側の音声出力先:", style = MaterialTheme.typography.titleMedium, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = serverOutputDevice, style = MaterialTheme.typography.headlineMedium, color = Color.Black, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            
                            Spacer(modifier = Modifier.height(32.dp))
                            
                            Text(text = "コーデック:", style = MaterialTheme.typography.titleMedium, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = serverAudioCodec, style = MaterialTheme.typography.headlineMedium, color = Color.Black, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            
                            Spacer(modifier = Modifier.height(32.dp))
                            
                            Text(text = "サンプリングレート/ビット深度:", style = MaterialTheme.typography.titleMedium, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = serverAudioFormat, style = MaterialTheme.typography.headlineMedium, color = Color.Black, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            
                            Spacer(modifier = Modifier.height(48.dp))
                        }
                        playbackControlBox(false)
                    }
                }
            }
        }
    }

    // ---------------- Server / Local ----------------
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ServerScreen(
        isBluetoothEnabled: Boolean, 
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
        val musicList = remember { getMusicListStatic(context) }
        var selectedTab by remember { mutableStateOf(0) }
        val scope = rememberCoroutineScope()
        var isRepeatEnabled by remember { mutableStateOf(false) }
        var isShuffleEnabled by remember { mutableStateOf(false) }

        // 再生ステータス用
        var outputDevice by remember { mutableStateOf("取得中...") }
        var audioFormat by remember { mutableStateOf("未取得") }
        var audioCodec by remember { mutableStateOf("取得中...") }

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
            if (isShuffleEnabled) {
                val currentSong = musicList.find { it.uri.toString() == currentSongUri }
                if (currentSong != null) {
                    val folderSongs = musicList.filter { it.folder == currentSong.folder }
                    if (folderSongs.isNotEmpty()) {
                        playSong(folderSongs.random().uri.toString())
                        return
                    }
                }
            }
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

        LaunchedEffect(isRepeatEnabled, isShuffleEnabled, musicList, currentSongUri) {
            MusicService.onTrackEnded = {
                if (isRepeatEnabled) {
                    currentSongUri?.let { scope.launch { playSong(it) } }
                } else {
                    scope.launch { skipNext() }
                }
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                server?.stopServer()
                MusicService.onTrackEnded = null
            }
        }

        LaunchedEffect(Unit) {
            if (isBluetoothEnabled && server != null) {
                val intent = Intent(context, MusicService::class.java).apply {
                    action = MusicService.ACTION_UPDATE_STATE
                    putExtra("IS_PLAYING", false)
                    putExtra("TITLE", "接続待機中")
                    putExtra("DEVICE", "Bluetooth")
                }
                ContextCompat.startForegroundService(context, intent)

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
                server.onNext = { scope.launch { skipNext() } }
                server.onPrevious = { scope.launch { skipPrevious() } }
                server.onAutoSkipSync = { enabled ->
                    isRepeatEnabled = !enabled 
                }
                server.onDisconnected = {
                    CoroutineScope(Dispatchers.Main).launch {
                        isConnected = false
                        val stopIntent = Intent(context, MusicService::class.java)
                        context.stopService(stopIntent)
                        onCancel()
                        Toast.makeText(context, "接続が切断されました", Toast.LENGTH_SHORT).show()
                    }
                }
                
                server.onReceiveMessage = { message ->
                    if (message.startsWith("SET_REPEAT:")) {
                        isRepeatEnabled = message.removePrefix("SET_REPEAT:") == "ON"
                    }
                    if (message.startsWith("SET_SHUFFLE:")) {
                        isShuffleEnabled = message.removePrefix("SET_SHUFFLE:") == "ON"
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
                    audioFormat = service.getCurrentAudioFormat()
                    audioCodec = service.getCurrentAudioCodec()
                    outputDevice = service.getCurrentOutputDevice()
                }
                delay(500)
            }
        }

        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(painterResource(R.drawable.play), "再生メディア", tint = if(selectedTab==0) Color.Black else Color.Gray, modifier = Modifier.size(35.dp)) },
                        label = { Text("再生メディア", color = if(selectedTab==0) Color.Black else Color.Gray) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Color.LightGray)
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(painterResource(R.drawable.lists), "曲リスト", tint = if(selectedTab==1) Color.Black else Color.Gray, modifier = Modifier.size(35.dp)) },
                        label = { Text("曲リスト", color = if(selectedTab==1) Color.Black else Color.Gray) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Color.LightGray)
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(painterResource(android.R.drawable.ic_menu_info_details), "再生ステータス", tint = if(selectedTab==2) Color.Black else Color.Gray, modifier = Modifier.size(35.dp)) },
                        label = { Text("再生ステータス", color = if(selectedTab==2) Color.Black else Color.Gray) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Color.LightGray)
                    )
                }
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (selectedTab) {
                    0 -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (!isBluetoothEnabled && onConnectBluetooth != null) {
                                OutlinedButton(
                                    onClick = onConnectBluetooth,
                                    modifier = Modifier.padding(bottom = 16.dp),
                                    border = BorderStroke(1.dp, Color.Black),
                                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color.Black)
                                ) {
                                    Text("デバイスと通信する")
                                }
                            } else if (isBluetoothEnabled && !isConnected && onStopCommunication != null) {
                                OutlinedButton(
                                    onClick = {
                                        server?.stopServer()
                                        onStopCommunication()
                                    },
                                    modifier = Modifier.padding(bottom = 16.dp),
                                    border = BorderStroke(1.dp, Color.Black),
                                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color.Black)
                                ) {
                                    Text("通信停止")
                                }
                            }

                            Text(if (isBluetoothEnabled) (if (isConnected) "接続相手: $clientName" else "接続待機中...") else "ローカル再生モード", color = Color.Black)
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
                                Text("再生中: $it", style = MaterialTheme.typography.titleMedium, color = Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                
                                Row(
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 8.dp)
                                ) {
                                    IconButton(onClick = { skipPrevious() }) {
                                        Icon(
                                            painter = painterResource(R.drawable.skip_left), 
                                            contentDescription = "Skip Previous", 
                                            tint = Color.Black,
                                            modifier = Modifier.size(48.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(24.dp))
                                    // 再生・一時停止ボタン
                                    OutlinedButton(
                                        onClick = {
                                            if (isPlaying) {
                                                if (isBluetoothEnabled) {
                                                    server?.pauseMusic()
                                                } else {
                                                    MusicService.instance?.pauseLocal()
                                                }
                                                isPlaying = false
                                            } else {
                                                if (isBluetoothEnabled) {
                                                    server?.resumeMusic()
                                                } else {
                                                    MusicService.instance?.resumeLocal()
                                                }
                                                isPlaying = true
                                            }
                                        },
                                        modifier = Modifier.width(90.dp).height(56.dp),
                                        shape = RoundedCornerShape(percent = 50),
                                        border = BorderStroke(1.dp, Color.Black),
                                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
                                        contentPadding = PaddingValues(0.dp)
                                    ) { 
                                        Icon(
                                            painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                                            contentDescription = "Play/Pause",
                                            tint = Color.Black,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(24.dp))
                                    IconButton(onClick = { skipNext() }) {
                                        Icon(
                                            painter = painterResource(R.drawable.skip_right), 
                                            contentDescription = "Skip Next", 
                                            tint = Color.Black,
                                            modifier = Modifier.size(48.dp)
                                        )
                                    }
                                }
                            }
                            
                            if (duration > 0) {
                                val sliderValue = currentPosition.toFloat() / duration.toFloat()
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
                                ) {
                                    Text(text = formatTime(currentPosition), modifier = Modifier.width(45.dp), fontSize = 12.sp, color = Color.Black)
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
                                        modifier = Modifier.weight(1f),
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color.Black, 
                                            activeTrackColor = Color.Black,
                                            inactiveTrackColor = Color.LightGray
                                        ),
                                        thumb = {
                                            Surface(
                                                modifier = Modifier.size(12.dp),
                                                shape = CircleShape,
                                                color = Color.Black
                                            ) {}
                                        },
                                        track = { sliderState ->
                                            SliderDefaults.Track(
                                                sliderState = sliderState,
                                                modifier = Modifier.height(2.dp),
                                                colors = SliderDefaults.colors(
                                                    activeTrackColor = Color.Black,
                                                    inactiveTrackColor = Color.LightGray
                                                )
                                            )
                                        }
                                    )
                                    Text(text = formatTime(duration), modifier = Modifier.width(45.dp), fontSize = 12.sp, color = Color.Black)
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    IconButton(onClick = {
                                        isRepeatEnabled = !isRepeatEnabled
                                        server?.sendMessage("SET_REPEAT:${if (isRepeatEnabled) "ON" else "OFF"}")
                                    }) {
                                        Icon(
                                            painter = painterResource(R.drawable.repeat),
                                            contentDescription = "Repeat",
                                            tint = if (isRepeatEnabled) Color.Black else Color.Gray,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    IconButton(onClick = {
                                        isShuffleEnabled = !isShuffleEnabled
                                        server?.sendMessage("SET_SHUFFLE:${if (isShuffleEnabled) "ON" else "OFF"}")
                                    }) {
                                        Icon(
                                            painter = painterResource(R.drawable.shuffle),
                                            contentDescription = "Shuffle",
                                            tint = if (isShuffleEnabled) Color.Black else Color.Gray,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                OutlinedButton(
                                    onClick = { 
                                        server?.stopServer()
                                        val stopIntent = Intent(context, MusicService::class.java)
                                        context.stopService(stopIntent)
                                        onCancel() 
                                    },
                                    border = BorderStroke(1.dp, Color.Black),
                                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color.Black)
                                ) { Text(if (isBluetoothEnabled) "切断" else "終了") }
                            }
                        }
                    }
                    1 -> {
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
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                painter = painterResource(if (isExpanded) R.drawable.folder_open else R.drawable.folder),
                                                contentDescription = null,
                                                modifier = Modifier.size(24.dp),
                                                tint = Color.Black
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(text = folder, color = Color.Black)
                                        }
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
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        if (isCurrent) {
                                                            Icon(
                                                                painter = painterResource(R.drawable.play),
                                                                contentDescription = null,
                                                                modifier = Modifier.size(16.dp),
                                                                tint = Color.Black
                                                            )
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                        }
                                                        Text(
                                                            text = song.title,
                                                            color = if (isCurrent) Color.Black else Color.DarkGray
                                                        )
                                                    }
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
                    2 -> {
                        // 再生ステータスタブ (サーバー/ローカル側)
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(text = "音声出力先:", style = MaterialTheme.typography.titleMedium, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = outputDevice, style = MaterialTheme.typography.headlineMedium, color = Color.Black, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            
                            Spacer(modifier = Modifier.height(32.dp))
                            
                            Text(text = "コーデック:", style = MaterialTheme.typography.titleMedium, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = audioCodec, style = MaterialTheme.typography.headlineMedium, color = Color.Black, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            
                            Spacer(modifier = Modifier.height(32.dp))
                            
                            Text(text = "サンプリングレート/ビット深度:", style = MaterialTheme.typography.titleMedium, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = audioFormat, style = MaterialTheme.typography.headlineMedium, color = Color.Black, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            
                            Spacer(modifier = Modifier.height(48.dp))
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