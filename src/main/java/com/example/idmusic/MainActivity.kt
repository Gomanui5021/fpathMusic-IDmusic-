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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.painter.Painter
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
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.compose.ui.text.style.TextAlign
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        fun getMusicListStatic(context: Context): List<MusicItem> {
            val list = mutableListOf<MusicItem>()
            val scannedPaths = mutableSetOf<String>()

            // 1. ストレージ内の音声ファイルを直接検索
            val audioExtensions = setOf("mp3", "wav", "flac", "m4a", "aac", "ogg", "opus", "wma")
            val localAudioFiles = mutableListOf<File>()

            fun searchAudioFiles(dir: File, depth: Int = 0) {
                if (depth > 6) return
                val files = dir.listFiles() ?: return
                for (file in files) {
                    if (file.isDirectory) {
                        if (!file.name.startsWith(".")) {
                            searchAudioFiles(file, depth + 1)
                        }
                    } else if (file.isFile) {
                        val ext = file.extension.lowercase(Locale.ROOT)
                        if (audioExtensions.contains(ext)) {
                            localAudioFiles.add(file)
                        }
                    }
                }
            }

            try {
                val externalStorage = Environment.getExternalStorageDirectory()
                if (externalStorage != null && externalStorage.exists()) {
                    searchAudioFiles(externalStorage)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error searching storage files: ${e.message}")
            }

            // 見つかったファイルの MediaScanner 登録要求を発行
            if (localAudioFiles.isNotEmpty()) {
                val pathsToScan = localAudioFiles.map { it.absolutePath }.toTypedArray()
                MediaScannerConnection.scanFile(context, pathsToScan, null, null)
            }

            // 2. MediaStore クエリ（条件緩和）
            val selection = "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR " +
                    "${MediaStore.Audio.Media.MIME_TYPE} LIKE 'audio/%' OR " +
                    "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.mp3' OR " +
                    "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.wav' OR " +
                    "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.flac' OR " +
                    "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.m4a' OR " +
                    "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.aac' OR " +
                    "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.ogg')"

            val finalSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "$selection AND (${MediaStore.Audio.Media.IS_PENDING} = 0)"
            } else {
                selection
            }

            val collections = listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)

            val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DATA
                )
            } else {
                arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.ALBUM_ID
                )
            }

            for (collection in collections) {
                try {
                    val cursor = context.contentResolver.query(collection, projection, finalSelection, null, null)
                    cursor?.use {
                        val idColumn = it.getColumnIndex(MediaStore.Audio.Media._ID)
                        val titleColumn = it.getColumnIndex(MediaStore.Audio.Media.TITLE)
                        val albumIdColumn = it.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                        val dataColumn = it.getColumnIndex(MediaStore.Audio.Media.DATA)
                        val displayNameColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME) else -1
                        val relativePathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH) else -1

                        while (it.moveToNext()) {
                            val id = if (idColumn >= 0) it.getLong(idColumn) else 0L
                            val rawTitle = if (titleColumn >= 0) it.getString(titleColumn) else null
                            val albumId = if (albumIdColumn >= 0) it.getLong(albumIdColumn) else 0L
                            val fullPath = if (dataColumn >= 0) it.getString(dataColumn) ?: "" else ""

                            if (fullPath.isNotEmpty()) {
                                scannedPaths.add(fullPath)
                            }

                            val fileName: String
                            val folderPath: String
                            val relativePathForGrouping: String

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && relativePathColumn >= 0) {
                                fileName = if (displayNameColumn >= 0) it.getString(displayNameColumn) ?: "" else fullPath.substringAfterLast("/")
                                folderPath = it.getString(relativePathColumn) ?: ""
                                relativePathForGrouping = folderPath
                            } else {
                                fileName = fullPath.substringAfterLast("/")
                                val dir = fullPath.substringBeforeLast("/", "")
                                folderPath = if (dir.isNotEmpty()) "$dir/" else ""
                                relativePathForGrouping = dir
                            }

                            val title = if (!rawTitle.isNullOrBlank()) rawTitle else fileName.substringBeforeLast(".")
                            val displayPath = "${folderPath}${fileName}/${title}"
                            val contentUri = ContentUris.withAppendedId(collection, id)
                            val folder = relativePathForGrouping.removeSuffix("/").substringAfterLast("/", "Unknown")
                            val storageType = if (collection.toString().contains("external_primary") || collection.toString().contains("external")) "内部" else "SD"

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
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error querying MediaStore", e)
                }
            }

            // 3. MediaStoreにまだ未登録のローカルファイルを直接フォールバック追加
            for (file in localAudioFiles) {
                if (!scannedPaths.contains(file.absolutePath)) {
                    val fileName = file.name
                    val folder = file.parentFile?.name ?: "Root"
                    val title = file.nameWithoutExtension
                    val contentUri = Uri.fromFile(file)
                    val displayPath = "${file.parentFile?.name ?: ""}/${fileName}/${title}"

                    list.add(MusicItem(
                        title = title,
                        uri = contentUri,
                        folder = folder.ifEmpty { "Root" },
                        path = displayPath,
                        storage = "内部",
                        albumId = -1L
                    ))
                }
            }

            return list.distinctBy { "${it.folder}/${it.path}" }
                .sortedWith(compareBy({ it.folder }, { it.title }))
                .sortedWith(compareBy({ it.folder }, { it.title }))
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
                    var role by rememberSaveable { mutableStateOf<String?>(null) }
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
        val isDark = isSystemInDarkTheme()
        
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

        @Suppress("DEPRECATION")
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Box(contentAlignment = Alignment.BottomEnd) {
                Image(
                    painter = painterResource(id = if (isDark) R.drawable.app_logo_mr else R.drawable.app_logo),
                    contentDescription = "App Logo",
                    modifier = Modifier
                        .size(500.dp)
                        .padding(bottom = 32.dp)
                )
                Text(
                    text = "Ver $versionName",
                    modifier = Modifier.padding(bottom = 48.dp, end = 32.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center
            ) {
                OutlinedButton(
                    onClick = { onSelect("CLIENT") },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("送信側")
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { onSelect("SERVER") },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("受信側")
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { onSelect("LOCAL") },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary)
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
        val isDark = isSystemInDarkTheme()
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

        val foldMode = getFoldUiMode()
        val isFold = foldMode != FoldUiMode.ONE_COLUMN

        Scaffold(
            bottomBar = {
                if (isConnected || foldMode != FoldUiMode.ONE_COLUMN) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        val selectedLabelColor = if (isDark) Color.White else Color.Black
                        val indicatorColor = if (isDark) MaterialTheme.colorScheme.primaryContainer else Color.LightGray
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = { Icon(painterResource(R.drawable.play), "再生メディア", tint = if(selectedTab==0) Color.Black else Color.Gray, modifier = Modifier.size(35.dp)) },
                            label = { Text("再生メディア", color = if(selectedTab==0) selectedLabelColor else Color.Gray) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = indicatorColor)
                        )
                        if (foldMode == FoldUiMode.ONE_COLUMN) {
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(painterResource(R.drawable.lists), "曲リスト", tint = if(selectedTab==1) Color.Black else Color.Gray, modifier = Modifier.size(35.dp)) },
                                label = { Text("曲リスト", color = if(selectedTab==1) selectedLabelColor else Color.Gray) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = indicatorColor)
                            )
                        }
                        NavigationBarItem(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            icon = { Icon(painterResource(android.R.drawable.ic_menu_info_details), "再生ステータス", tint = if(selectedTab==2) Color.Black else Color.Gray, modifier = Modifier.size(35.dp)) },
                            label = { Text("再生ステータス", color = if(selectedTab==2) selectedLabelColor else Color.Gray) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = indicatorColor)
                        )
                    }
                }
            }
        ) { padding ->
            val expandedState = remember { mutableStateMapOf<String, Boolean>() }
            
            val playerUiContent = @Composable {
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("接続デバイス: $connectedDeviceName", color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(12.dp))
                    
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
                            modifier = Modifier.size(if (isFold) 200.dp else 250.dp).padding(8.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    currentSongTitle?.let { title ->
                        Text("再生中: $title", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            IconButton(onClick = { skipPrevious() }) {
                                Icon(
                                    painter = painterResource(R.drawable.skip_left), 
                                    contentDescription = "Skip Previous", 
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(44.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(20.dp))
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
                                modifier = Modifier.width(84.dp).height(52.dp),
                                shape = RoundedCornerShape(percent = 50),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface),
                                contentPadding = PaddingValues(0.dp)
                            ) { 
                                Icon(
                                    painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                                    contentDescription = "Play/Pause",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(20.dp))
                            IconButton(onClick = { skipNext() }) {
                                Icon(
                                    painter = painterResource(R.drawable.skip_right), 
                                    contentDescription = "Skip Next", 
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(44.dp)
                                )
                            }
                        }
                    }

                    if (duration > 0) {
                        val sliderValue = currentPosition.toFloat() / duration.toFloat()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Text(text = formatTime(currentPosition), modifier = Modifier.width(45.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                            Slider(
                                value = sliderValue,
                                onValueChange = { currentPosition = (it * duration).toInt() },
                                onValueChangeFinished = { bluetoothClient.sendSeek(currentPosition) },
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary, 
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
                                )
                            )
                            Text(text = formatTime(duration), modifier = Modifier.width(45.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        IconButton(onClick = { 
                            if (serverVolume > 0) {
                                serverVolume--
                                bluetoothClient.sendVolumeSet(serverVolume)
                            }
                        }, modifier = Modifier.size(40.dp)) {
                            Icon(painter = painterResource(R.drawable.volume_down), contentDescription = "Volume Down", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        Slider(
                            value = serverVolume.toFloat(),
                            onValueChange = { 
                                serverVolume = it.toInt()
                                bluetoothClient.sendVolumeSet(serverVolume)
                            },
                            valueRange = 0f..serverMaxVolume.toFloat(),
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary, 
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
                            )
                        )
                        IconButton(onClick = { 
                            if (serverVolume < serverMaxVolume) {
                                serverVolume++
                                bluetoothClient.sendVolumeSet(serverVolume)
                            }
                        }, modifier = Modifier.size(40.dp)) {
                            Icon(painter = painterResource(R.drawable.volume_up), contentDescription = "Volume Up", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        Text(text = "$serverVolume", modifier = Modifier.width(20.dp), fontSize = 12.sp, textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurface)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row {
                            ToggleButtonWithSlash(
                                painter = painterResource(R.drawable.repeat),
                                checked = isRepeatEnabled,
                                onCheckedChange = {
                                    isRepeatEnabled = !isRepeatEnabled
                                    bluetoothClient.sendMessage("SET_REPEAT:${if (isRepeatEnabled) "ON" else "OFF"}")
                                },
                                contentDescription = "Repeat"
                            )
                            ToggleButtonWithSlash(
                                painter = painterResource(R.drawable.shuffle),
                                checked = isShuffleEnabled,
                                onCheckedChange = {
                                    isShuffleEnabled = !isShuffleEnabled
                                    bluetoothClient.sendMessage("SET_SHUFFLE:${if (isShuffleEnabled) "ON" else "OFF"}")
                                },
                                contentDescription = "Shuffle"
                            )
                        }
                        OutlinedButton(
                            onClick = { bluetoothClient.sendDisconnect() },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary)
                        ) { Text("切断") }
                    }
                }
            }

            val songListUiContent = @Composable {
                Column(modifier = Modifier.fillMaxSize()) {
                    val grouped = musicItems.groupBy { it.folder }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        grouped.forEach { (folder, songs) ->
                            val isExpanded = expandedState[folder] ?: false
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(width = 1.dp, MaterialTheme.colorScheme.outline)
                                        .height(52.dp)
                                        .clickable { expandedState[folder] = !isExpanded }
                                        .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            painter = painterResource(if (isExpanded) R.drawable.folder_open else R.drawable.folder),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(text = folder, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                            if (isExpanded) {
                                items(songs) { song ->
                                    val isCurrent = song.uri.toString() == currentSongUri
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
                                            .clickable { playSong(song) }
                                            .padding(10.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (isCurrent) {
                                                        Icon(
                                                            painter = painterResource(R.drawable.play),
                                                            contentDescription = null,
                                                            modifier = Modifier.size(16.dp),
                                                            tint = MaterialTheme.colorScheme.primary
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                    }
                                                    Text(text = song.title, color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                                }
                                                Text(
                                                    text = "[${song.storage}] ${song.path}",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
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
                                                    modifier = Modifier.size(40.dp).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                                                    contentScale = ContentScale.Crop
                                                )
                                            }
                                        }
                                    }
                                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    }
                }
            }

            val statusUiContent = @Composable {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    currentSongTitle?.let { title ->
                        Text("再生中: $title", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(modifier = Modifier.height(24.dp))
                    }

                    Text(text = "サーバー側の音声出力先:", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = serverOutputDevice, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    Text(text = "コーデック:", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = serverAudioCodec, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    Text(text = "サンプリングレート/ビット深度:", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = serverAudioFormat, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }

            Box(
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
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("← 戻る")
                        }
                    }

                    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 48.dp)) {
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
                                Text(text = device.name ?: "不明", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                                if (connectingDevice == device) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                } else {
                    when (foldMode) {
                        FoldUiMode.THREE_COLUMN -> {
                            Row(modifier = Modifier.fillMaxSize()) {
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    playerUiContent()
                                }
                                VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    songListUiContent()
                                }
                                VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    statusUiContent()
                                }
                            }
                        }
                        FoldUiMode.TWO_COLUMN -> {
                            Row(modifier = Modifier.fillMaxSize()) {
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    if (selectedTab == 2) {
                                        statusUiContent()
                                    } else {
                                        playerUiContent()
                                    }
                                }
                                VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    songListUiContent()
                                }
                            }
                        }
                        FoldUiMode.ONE_COLUMN -> {
                            when (selectedTab) {
                                0 -> playerUiContent()
                                1 -> songListUiContent()
                                2 -> statusUiContent()
                            }
                        }
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
        val isDark = isSystemInDarkTheme()
        val server = remember { if (isBluetoothEnabled) BluetoothServer(context) else null }

        var clientName by remember { mutableStateOf(if (isBluetoothEnabled) "未接続" else "ローカル") }
        var isConnected by remember { mutableStateOf(!isBluetoothEnabled) }
        var playingTitle by remember { mutableStateOf<String?>(null) }
        var currentSongUri by remember { mutableStateOf<String?>(null) }
        var isPlaying by remember { mutableStateOf(false) }
        var currentPosition by remember { mutableStateOf(0) }
        var duration by remember { mutableStateOf(0) }
        val musicList = remember { getMusicListStatic(context) }
        val displayMusicList = remember(isBluetoothEnabled, isConnected, musicList) {
            if (isBluetoothEnabled) {
                if (isConnected) musicList else emptyList()
            } else {
                musicList
            }
        }
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
            val found = displayMusicList.find { it.uri.toString() == uri }
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
                val currentSong = displayMusicList.find { it.uri.toString() == currentSongUri }
                if (currentSong != null) {
                    val folderSongs = displayMusicList.filter { it.folder == currentSong.folder }
                    if (folderSongs.isNotEmpty()) {
                        playSong(folderSongs.random().uri.toString())
                        return
                    }
                }
            }
            val currentIndex = displayMusicList.indexOfFirst { it.uri.toString() == currentSongUri }
            if (currentIndex != -1 && currentIndex < displayMusicList.size - 1) {
                val nextSong = displayMusicList[currentIndex + 1]
                playSong(nextSong.uri.toString())
            }
        }

        fun skipPrevious() {
            val currentIndex = displayMusicList.indexOfFirst { it.uri.toString() == currentSongUri }
            if (currentIndex > 0) {
                val prevSong = displayMusicList[currentIndex - 1]
                playSong(prevSong.uri.toString())
            }
        }

        LaunchedEffect(isRepeatEnabled, isShuffleEnabled, displayMusicList, currentSongUri) {
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

        val foldModeServer = getFoldUiMode()
        val isFoldServer = foldModeServer != FoldUiMode.ONE_COLUMN

        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    val selectedLabelColor = if (isDark) Color.White else Color.Black
                    val indicatorColor = if (isDark) MaterialTheme.colorScheme.primaryContainer else Color.LightGray
                    
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(painterResource(R.drawable.play), "再生メディア", tint = if(selectedTab==0) Color.Black else Color.Gray, modifier = Modifier.size(35.dp)) },
                        label = { Text("再生メディア", color = if(selectedTab==0) selectedLabelColor else Color.Gray) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = indicatorColor)
                    )
                    if (foldModeServer == FoldUiMode.ONE_COLUMN) {
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = { Icon(painterResource(R.drawable.lists), "曲リスト", tint = if(selectedTab==1) Color.Black else Color.Gray, modifier = Modifier.size(35.dp)) },
                            label = { Text("曲リスト", color = if(selectedTab==1) selectedLabelColor else Color.Gray) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = indicatorColor)
                        )
                    }
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(painterResource(android.R.drawable.ic_menu_info_details), "再生ステータス", tint = if(selectedTab==2) Color.Black else Color.Gray, modifier = Modifier.size(35.dp)) },
                        label = { Text("再生ステータス", color = if(selectedTab==2) selectedLabelColor else Color.Gray) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = indicatorColor)
                    )
                }
            }
        ) { padding ->
            val serverPlayerUiContent = @Composable {
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!isBluetoothEnabled && onConnectBluetooth != null) {
                        OutlinedButton(
                            onClick = onConnectBluetooth,
                            modifier = Modifier.padding(bottom = 16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary)
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
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("通信停止")
                        }
                    }

                    Text(if (isBluetoothEnabled) (if (isConnected) "接続相手: $clientName" else "接続待機中...") else "ローカル再生モード", color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(12.dp))
                    Image(
                        painter = rememberAsyncImagePainter(
                            model = albumArtUri ?: R.drawable.no_image,
                            error = painterResource(R.drawable.no_image),
                            fallback = painterResource(R.drawable.no_image)
                        ),
                        contentDescription = "Album Art",
                        modifier = Modifier.size(if (isFoldServer) 200.dp else 250.dp).padding(8.dp),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    playingTitle?.let {
                        Text("再生中: $it", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            IconButton(onClick = { skipPrevious() }) {
                                Icon(
                                    painter = painterResource(R.drawable.skip_left), 
                                    contentDescription = "Skip Previous", 
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(44.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(20.dp))
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
                                modifier = Modifier.width(84.dp).height(52.dp),
                                shape = RoundedCornerShape(percent = 50),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface),
                                contentPadding = PaddingValues(0.dp)
                            ) { 
                                Icon(
                                    painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                                    contentDescription = "Play/Pause",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(20.dp))
                            IconButton(onClick = { skipNext() }) {
                                Icon(
                                    painter = painterResource(R.drawable.skip_right), 
                                    contentDescription = "Skip Next", 
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(44.dp)
                                )
                            }
                        }
                    }
                    
                    if (duration > 0) {
                        val sliderValue = currentPosition.toFloat() / duration.toFloat()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Text(text = formatTime(currentPosition), modifier = Modifier.width(45.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
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
                                    thumbColor = MaterialTheme.colorScheme.primary, 
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
                                )
                            )
                            Text(text = formatTime(duration), modifier = Modifier.width(45.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row {
                            ToggleButtonWithSlash(
                                painter = painterResource(R.drawable.repeat),
                                checked = isRepeatEnabled,
                                onCheckedChange = {
                                    isRepeatEnabled = !isRepeatEnabled
                                    server?.sendMessage("SET_REPEAT:${if (isRepeatEnabled) "ON" else "OFF"}")
                                },
                                contentDescription = "Repeat"
                            )
                            ToggleButtonWithSlash(
                                painter = painterResource(R.drawable.shuffle),
                                checked = isShuffleEnabled,
                                onCheckedChange = {
                                    isShuffleEnabled = !isShuffleEnabled
                                    server?.sendMessage("SET_SHUFFLE:${if (isShuffleEnabled) "ON" else "OFF"}")
                                },
                                contentDescription = "Shuffle"
                            )
                        }
                        OutlinedButton(
                            onClick = { 
                                server?.stopServer()
                                val stopIntent = Intent(context, MusicService::class.java)
                                context.stopService(stopIntent)
                                onCancel() 
                            },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary)
                        ) { Text(if (isBluetoothEnabled) "切断" else "終了") }
                    }
                }
            }

            val serverSongListUiContent = @Composable {
                if (displayMusicList.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isBluetoothEnabled) "接続待機中...\n接続されると曲リストが表示されます" else "曲が見つかりません",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val grouped = displayMusicList.groupBy { it.folder }
                    val expandedState = remember { mutableStateMapOf<String, Boolean>() }

                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        grouped.forEach { (folder, songs) ->
                            val isExpanded = expandedState[folder] ?: false
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(width = 1.dp, MaterialTheme.colorScheme.outline)
                                        .height(52.dp)
                                        .clickable { expandedState[folder] = !isExpanded }
                                        .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            painter = painterResource(if (isExpanded) R.drawable.folder_open else R.drawable.folder),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(text = folder, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                            if (isExpanded) {
                                items(songs) { song ->
                                    val isCurrent = song.uri.toString() == currentSongUri
                                    
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
                                            .clickable { playSong(song.uri.toString()) }
                                            .padding(10.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (isCurrent) {
                                                        Icon(
                                                            painter = painterResource(R.drawable.play),
                                                            contentDescription = null,
                                                            modifier = Modifier.size(16.dp),
                                                            tint = MaterialTheme.colorScheme.primary
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                    }
                                                    Text(
                                                        text = song.title,
                                                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                                Text(
                                                    text = "[${song.storage}] ${song.path}",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
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
                                                modifier = Modifier.size(40.dp).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                    }
                                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    }
                }
            }

            val serverStatusUiContent = @Composable {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    playingTitle?.let { title ->
                        Text("再生中: $title", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(modifier = Modifier.height(24.dp))
                    }

                    Text(text = "音声出力先:", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = outputDevice, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    Text(text = "コーデック:", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = audioCodec, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    Text(text = "サンプリングレート/ビット深度:", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = audioFormat, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }

            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (foldModeServer) {
                    FoldUiMode.THREE_COLUMN -> {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                serverPlayerUiContent()
                            }
                            VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                serverSongListUiContent()
                            }
                            VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                serverStatusUiContent()
                            }
                        }
                    }
                    FoldUiMode.TWO_COLUMN -> {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                if (selectedTab == 2) {
                                    serverStatusUiContent()
                                } else {
                                    serverPlayerUiContent()
                                }
                            }
                            VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                serverSongListUiContent()
                            }
                        }
                    }
                    FoldUiMode.ONE_COLUMN -> {
                        when (selectedTab) {
                            0 -> serverPlayerUiContent()
                            1 -> serverSongListUiContent()
                            2 -> serverStatusUiContent()
                        }
                    }
                }
            }
        }
    }


    // ---------------- Music List ----------------
    fun getMusicList(context: Context): List<MusicItem> {
        return getMusicListStatic(context)
    }
}

enum class FoldUiMode {
    ONE_COLUMN,   // 1画面（通常スマホ状態）
    TWO_COLUMN,   // 2画面（折りたたみ展開状態: Google Pixel Fold, Mate XT 2画面など）
    THREE_COLUMN  // 3画面（完全展開状態: HUAWEI Mate XT 3画面など）
}

@Composable
fun getFoldUiMode(): FoldUiMode {
    val context = LocalContext.current
    val displayMetrics = context.resources.displayMetrics
    val widthPx = displayMetrics.widthPixels
    val heightPx = displayMetrics.heightPixels
    val shortSidePx = minOf(widthPx, heightPx)
    val longSidePx = maxOf(widthPx, heightPx)
    val densityDpi = displayMetrics.densityDpi

    if (densityDpi < 350) {
        return FoldUiMode.ONE_COLUMN
    }

    return when {
        // 3. 【3カラムUI（3画面完全展開状態）】: 長辺が3000px以上、かつ短辺が2200px以上
        longSidePx >= 3000 && shortSidePx >= 2200 -> FoldUiMode.THREE_COLUMN

        // 2. 【2カラムUI（2画面折りたたみ状態）】: 短辺が1800px以上、かつ長辺が3000px未満
        shortSidePx >= 1800 && longSidePx < 3000 -> FoldUiMode.TWO_COLUMN

        // 1. 【1カラムUI（通常スマホ状態）】: 短辺が1800px未満
        else -> FoldUiMode.ONE_COLUMN
    }
}

@Composable
fun isFoldUi(): Boolean {
    return getFoldUiMode() != FoldUiMode.ONE_COLUMN
}

@Composable
fun ToggleButtonWithSlash(
    painter: Painter,
    checked: Boolean,
    onCheckedChange: () -> Unit,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val tintColor = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val slashColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    
    IconButton(onClick = onCheckedChange, modifier = modifier) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painter,
                contentDescription = contentDescription,
                tint = tintColor,
                modifier = Modifier.size(24.dp)
            )
            if (!checked) {
                Canvas(modifier = Modifier.size(24.dp)) {
                    val strokeWidth = 2.dp.toPx()
                    drawLine(
                        color = slashColor,
                        start = androidx.compose.ui.geometry.Offset(x = size.width * 0.1f, y = size.height * 0.1f),
                        end = androidx.compose.ui.geometry.Offset(x = size.width * 0.9f, y = size.height * 0.9f),
                        strokeWidth = strokeWidth
                    )
                }
            }
        }
    }
}
