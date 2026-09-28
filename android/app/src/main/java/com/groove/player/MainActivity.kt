package com.groove.player

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.audiofx.Equalizer
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import android.util.Base64
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private val Ink = Color(0xFF090D0B)
private val Panel = Color(0xFF111713)
private val Leaf = Color(0xFFB9F56B)
private val Muted = Color(0xFF8B978E)

data class Track(
    val fingerprint: String,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val folder: String,
    val uri: String,
    val duration: Long,
    val size: Long,
    val artwork: String = "",
    val addedAt: Long = System.currentTimeMillis()
) { val local: Boolean get() = uri.isNotBlank() }

class MainActivity : ComponentActivity() {
    private val tracks = mutableStateListOf<Track>()
    private val queue = mutableStateListOf<String>()
    private val playlists = mutableStateMapOf<String, List<String>>()
    private var currentFingerprint by mutableStateOf<String?>(null)
    private var playing by mutableStateOf(false)
    private var currentTab by mutableStateOf("Home")
    private var selectedPlaylist by mutableStateOf("Liked songs")
    private var query by mutableStateOf("")
    private var darkMode by mutableStateOf(true)
    private var endpoint by mutableStateOf("")
    private var token by mutableStateOf("")
    private var scanProgress by mutableStateOf("")
    private var selectedTrack by mutableStateOf<Track?>(null)
    private var showPair by mutableStateOf(false)
    private var showSleepTimer by mutableStateOf(false)
    private var showPlaybackParams by mutableStateOf(false)
    private var sleepMinutes by mutableStateOf("30")
    private var playbackSpeed by mutableFloatStateOf(1f)
    private var playbackPitch by mutableFloatStateOf(1f)
    private var stopAfterThisTrack by mutableStateOf(false)
    private var sleepTimerJob: Job? = null
    private var equalizer: Equalizer? = null
    private var showEqualizer by mutableStateOf(false)
    private var showMainMenu by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var editTitle by mutableStateOf("")
    private var editArtist by mutableStateOf("")
    private var showEdit by mutableStateOf(false)
    private var showDeleteConfirm by mutableStateOf(false)
    private var deleteTarget by mutableStateOf<Track?>(null)
    private var moveTarget by mutableStateOf<Track?>(null)
    private var showSearch by mutableStateOf(false)
    private var mediaPlayer: MediaPlayer? = null
    private var syncJob: Job? = null
    private var openFolderPicker: (() -> Unit)? = null
    private var openMusicPicker: (() -> Unit)? = null
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("groove", MODE_PRIVATE)
        endpoint = prefs.getString("endpoint", "") ?: ""
        token = prefs.getString("token", "") ?: ""
        darkMode = prefs.getBoolean("dark", true)
        playlists.putAll(mapOf("Liked songs" to emptyList(), "Late night drive" to emptyList(), "The good stuff" to emptyList()))
        restoreLibrary(prefs)
        setContent { GrooveUi() }
        if (endpoint.isNotBlank() && token.isNotBlank()) startSync()
    }

    private fun persistLibrary() {
        val list = JSONArray()
        tracks.forEach { t -> list.put(JSONObject().put("fp", t.fingerprint).put("title", t.title).put("artist", t.artist).put("album", t.album).put("genre", t.genre).put("folder", t.folder).put("uri", t.uri).put("duration", t.duration).put("size", t.size).put("artwork", t.artwork).put("added", t.addedAt)) }
        val q = JSONArray().also { a -> queue.forEach { a.put(it) } }
        val ps=JSONObject();playlists.forEach{(name,ids)->ps.put(name,JSONArray().also{a->ids.forEach{a.put(it)}})}
        getSharedPreferences("groove", MODE_PRIVATE).edit().putString("tracks", list.toString()).putString("queue", q.toString()).putString("playlists",ps.toString()).putBoolean("dark", darkMode).apply()
    }

    private fun restoreLibrary(prefs: android.content.SharedPreferences) {
        runCatching { JSONArray(prefs.getString("tracks", "[]")).let { a -> for (i in 0 until a.length()) { val o=a.getJSONObject(i); tracks.add(Track(o.getString("fp"),o.optString("title"),o.optString("artist"),o.optString("album"),o.optString("genre"),o.optString("folder"),o.optString("uri"),o.optLong("duration"),o.optLong("size"),o.optString("artwork"),o.optLong("added",System.currentTimeMillis()))) } } }
        runCatching { JSONArray(prefs.getString("queue", "[]")).let { a -> for (i in 0 until a.length()) queue.add(a.getString(i)) } }
        runCatching { JSONObject(prefs.getString("playlists", "{}")).let { o -> o.keys().forEach { name -> val arr=o.optJSONArray(name)?:JSONArray();playlists[name]=List(arr.length()){i->arr.getString(i)} } } }
    }

    private fun display(text: String) { message = text; Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }

    private fun pair(baseUrl: String, code: String) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val body = JSONObject().put("code",code).put("name",android.os.Build.MODEL).put("platform","android").toString().toRequestBody(jsonType)
                    val req = Request.Builder().url(baseUrl.trimEnd('/')+"/api/pair").post(body).build()
                    client.newCall(req).execute().use { r -> if (!r.isSuccessful) error(JSONObject(r.body?.string().orEmpty()).optString("error","Pairing failed")); JSONObject(r.body!!.string()) }
                }
            }
            result.onSuccess { obj ->
                endpoint=baseUrl.trimEnd('/'); token=obj.getString("token")
                getSharedPreferences("groove", MODE_PRIVATE).edit().putString("endpoint",endpoint).putString("token",token).apply()
                mergeState(obj.optJSONObject("state")); showPair=false; startSync(); postState(); display("This device is paired and syncing")
            }.onFailure { display(it.message ?: "Could not pair") }
        }
    }

    private fun api(path: String, method: String = "GET", data: JSONObject? = null): JSONObject? {
        val builder = Request.Builder().url(endpoint.trimEnd('/')+path).header("Authorization", "Bearer $token")
        if (method == "POST") builder.post((data ?: JSONObject()).toString().toRequestBody(jsonType)) else builder.get()
        return client.newCall(builder.build()).execute().use { response -> if (!response.isSuccessful) null else JSONObject(response.body?.string().orEmpty()) }
    }

    private fun syncPayload(): JSONObject {
        val songArray = JSONArray()
        tracks.forEach { t -> songArray.put(JSONObject().put("fingerprint",t.fingerprint).put("title",t.title).put("artist",t.artist).put("album",t.album).put("genre",t.genre).put("folder",t.folder).put("duration",t.duration/1000.0).put("size",t.size).put("addedAt",java.time.Instant.ofEpochMilli(t.addedAt).toString())) }
        val ps=JSONArray();playlists.forEach{(name,ids)->ps.put(JSONObject().put("id",name.lowercase().replace(" ","-")).put("name",name).put("trackIds",JSONArray().also{a->ids.forEach{a.put(it)}}))}
        return JSONObject().put("songs",songArray).put("playlists",ps).put("queue",JSONArray().also { a -> queue.forEach { a.put(it) } }).put("settings",JSONObject().put("theme",if(darkMode)"dark" else "light"))
    }

    private fun postState() { if (token.isBlank()) return; val payload=syncPayload();lifecycleScope.launch(Dispatchers.IO) { runCatching { api("/api/state","POST",payload) }.onFailure { } } }
    private fun startSync() {
        syncJob?.cancel()
        syncJob = lifecycleScope.launch {
            while (isActive && token.isNotBlank()) {
                val state = withContext(Dispatchers.IO) { runCatching { api("/api/state") }.getOrNull() }
                if (state != null) mergeState(state)
                delay(2500)
            }
        }
    }

    private fun mergeState(state: JSONObject?) {
        val incoming = state?.optJSONArray("songs") ?: return
        state.optJSONArray("deletedFingerprints")?.let { deleted -> for(i in 0 until deleted.length()) { val d=deleted.optJSONObject(i);val fp=d?.optString("fingerprint")?:deleted.optString(i);tracks.removeAll{it.fingerprint==fp};queue.removeAll{it==fp};playlists.replaceAll{_,ids->ids.filterNot{it==fp}};if(currentFingerprint==fp){runCatching{mediaPlayer?.stop()};mediaPlayer?.release();mediaPlayer=null;currentFingerprint=null;playing=false} } }
        for (i in 0 until incoming.length()) {
            val o = incoming.optJSONObject(i) ?: continue
            val fp = o.optString("fingerprint"); if (fp.isBlank()) continue
            val at = tracks.indexOfFirst { it.fingerprint == fp }
            val prior = if(at >= 0) tracks[at] else null
            val updated = Track(fp,o.optString("title",prior?.title.orEmpty()),o.optString("artist",prior?.artist.orEmpty()),o.optString("album",prior?.album.orEmpty()),o.optString("genre",prior?.genre.orEmpty()),o.optString("folder",prior?.folder ?: "Synced from web"),prior?.uri.orEmpty(),(o.optDouble("duration")*1000).toLong(),o.optLong("size",prior?.size ?: 0),o.optString("artwork").ifBlank{prior?.artwork.orEmpty()}.take(90_000),prior?.addedAt ?: System.currentTimeMillis())
            if(at >= 0) tracks[at] = updated else tracks.add(updated)
        }
        state.optJSONArray("queue")?.let { arr -> queue.clear(); for(i in 0 until arr.length()) queue.add(arr.getString(i)) }
        state.optJSONArray("playlists")?.let { arr -> for(i in 0 until arr.length()){val p=arr.optJSONObject(i)?:continue;val ids=p.optJSONArray("trackIds")?:JSONArray();playlists[p.optString("name","Playlist")]=List(ids.length()){n->ids.getString(n)}} }
        persistLibrary()
    }

    private fun filePickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="audio/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true) }
    private fun keepUriPermission(uri:Uri){runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}.onFailure{runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}}}

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun GrooveUi() {
        val bg = if(darkMode) Ink else Color(0xFFF3F6F1)
        val fg = if(darkMode) Color(0xFFEAF0EA) else Color(0xFF1B241C)
        val card = if(darkMode) Panel else Color.White
        val colors = if(darkMode) darkColorScheme(primary=Leaf,background=bg,surface=card,onBackground=fg,onSurface=fg) else lightColorScheme(primary=Color(0xFF416C28),background=bg,surface=card,onBackground=fg,onSurface=fg)
        val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if(uri!=null) { keepUriPermission(uri);val moving=moveTarget;moveTarget=null;if(moving!=null)moveToFolder(moving,uri) else scanFolder(uri) } }
        val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if(uris.isNotEmpty()) importUris(uris) }
        openFolderPicker = { folderPicker.launch(null) }
        openMusicPicker = { filePicker.launch(arrayOf("audio/*")) }
        MaterialTheme(colorScheme=colors) {
            Scaffold(containerColor=bg,topBar={
                CenterAlignedTopAppBar(
                    title={ Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)){ Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(Leaf),contentAlignment=Alignment.Center){ Text("♫",color=Ink,fontWeight=FontWeight.Black) }; Text("groove",fontWeight=FontWeight.ExtraBold) } },
                    actions={ IconButton(onClick={showSearch=!showSearch}){Icon(Icons.Default.Search,"Search")}; Box { IconButton(onClick={showMainMenu=true}){Icon(Icons.Default.MoreVert,"Menu")}; MainMenu(visible=showMainMenu,onDismiss={showMainMenu=false},onSelect={showMainMenu=false;menuAction(it);}) } }
                )
            },bottomBar={ Column {
                PlayerStrip(track=tracks.firstOrNull{it.fingerprint==currentFingerprint},playing=playing,onClick={currentTab="Now playing"},onToggle={togglePlayback()},onNext={nextTrack()})
                NavigationBar(containerColor=card){
                    val tabs=listOf("Home","Folders","Albums","Artists","Genres","Playlists","Queue")
                    tabs.forEach { tab -> NavigationBarItem(selected=currentTab==tab,onClick={currentTab=tab},icon={Icon(when(tab){"Home"->Icons.Default.Home;"Folders"->Icons.Default.Folder;"Albums"->Icons.Default.Album;"Queue"->Icons.Default.QueueMusic;else->Icons.Default.LibraryMusic},tab)},label={Text(tab,maxLines=1,style=MaterialTheme.typography.labelSmall)}) }
                }
            } }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if(showSearch) OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(horizontal=16.dp, vertical=6.dp),singleLine=true,placeholder={Text("Search songs, artists, albums")})
                    if(scanProgress.isNotBlank()) Text(scanProgress,Modifier.padding(horizontal=18.dp,vertical=6.dp),color=Muted,style=MaterialTheme.typography.labelSmall)
                    when(currentTab){
                        "Now playing" -> NowPlaying(tracks.firstOrNull{it.fingerprint==currentFingerprint},playing,{togglePlayback()},{previousTrack()},{nextTrack()})
                        "Queue" -> TrackList(queue.mapNotNull { id -> tracks.firstOrNull{it.fingerprint==id} },"Up next",::play)
                        "Folders" -> BrowseList(tracks.filter{it.folder.isNotBlank()}.distinctBy{it.folder}.map { it.copy(title=it.folder,artist="${tracks.count(t->t.folder==it.folder)} songs") },"Folders",::play,openFolderPicker?:{})
                        "Albums" -> BrowseList(tracks.distinctBy{it.album}.map{it.copy(title=it.album,artist=it.artist)},"Albums",::play)
                        "Artists" -> BrowseList(tracks.distinctBy{it.artist}.map{it.copy(title=it.artist,artist="${tracks.count(t->t.artist==it.artist)} tracks")},"Artists",::play)
                        "Genres" -> BrowseList(tracks.distinctBy{it.genre}.map{it.copy(title=it.genre,artist="${tracks.count(t->t.genre==it.genre)} tracks")},"Genres",::play)
                        "Playlists" -> PlaylistView(::play)
                        else -> LibraryView(tracks.filter{query.isBlank()||"${it.title} ${it.artist} ${it.album}".contains(query,true)},::play,::queueTrack,::openTrackMenu,openMusicPicker?:{},openFolderPicker?:{})
                    }
                    if(tracks.isEmpty() && scanProgress.isBlank()) Welcome(onPick={filePicker.launch(arrayOf("audio/*"))},onFolder={folderPicker.launch(null)},onPair={showPair=true})
                }
            }
        }
        if(showPair) PairDialog(onDismiss={showPair=false},onPair=::pair)
        if(showSleepTimer) SleepDialog(sleepMinutes,{sleepMinutes=it},{startSleepTimer()},{showSleepTimer=false})
        if(showPlaybackParams) PlaybackDialog(playbackSpeed,playbackPitch,{playbackSpeed=it;applyPlaybackParams()},{playbackPitch=it;applyPlaybackParams()},{showPlaybackParams=false})
        if(showEqualizer) EqualizerDialog(equalizer,{showEqualizer=false})
        if(showEdit) EditDialog(editTitle, {editTitle=it},editArtist,{editArtist=it}, { saveTags() },{showEdit=false})
        if(showDeleteConfirm) AlertDialog(onDismissRequest={showDeleteConfirm=false},title={Text("Delete this audio file permanently?")},text={Text("This removes the original file from device storage. This cannot be undone.")},confirmButton={TextButton(onClick={deleteAudioFile}){Text("Delete permanently",color=Color(0xFFFF7777))}},dismissButton={TextButton(onClick={showDeleteConfirm=false}){Text("Cancel")}})
        selectedTrack?.let { t -> TrackMenuDialog(t,onDismiss={selectedTrack=null},onAction={action -> selectedTrack=null; trackAction(action,t)}) }
    }

    private fun menuAction(item:String) { when(item){"Scan folders"->openFolderPicker?.invoke();"Sync devices"->showPair=true;"Settings"->{darkMode=!darkMode;getSharedPreferences("groove",MODE_PRIVATE).edit().putBoolean("dark",darkMode).apply();postState()};"Sleep timer"->showSleepTimer=true;"Equalizer"->showEqualizer=true;"Help"->display("Add a folder, pair devices, then tap a track to play.");"About Groove"->display("Groove · local music player");"Close Groove"->finish()} }

    private fun scanFolder(tree: Uri) {
        lifecycleScope.launch {
            scanProgress="Scanning selected folder…"
            val found = withContext(Dispatchers.IO) { scanTree(tree) }
            found.forEach { t -> val index=tracks.indexOfFirst{it.fingerprint==t.fingerprint}; if(index>=0) tracks[index]=t else tracks.add(t) }
            scanProgress="${found.size} audio files scanned · ${tracks.size} songs in your library"
            persistLibrary(); postState()
        }
    }

    private suspend fun scanTree(tree: Uri): List<Track> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(this@MainActivity,tree) ?: return@withContext emptyList()
        val output=mutableListOf<Track>(); val todo=ArrayDeque<DocumentFile>(); todo.add(root)
        while(todo.isNotEmpty()) { val dir=todo.removeFirst(); for(file in dir.listFiles()) { if(file.isDirectory) todo.add(file) else if(isAudio(file.name.orEmpty(),file.type)) { runCatching { output.add(readTrack(file.uri,file.name ?: "audio",file.length(),tree.toString())) } } } }
        output
    }

    private fun isAudio(name:String,mime:String?) = mime?.startsWith("audio/")==true || name.substringAfterLast('.',"").lowercase() in setOf("mp3","m4a","flac","ogg","wav","aac","opus","wma")
    private fun importUris(uris:List<Uri>) { lifecycleScope.launch { scanProgress="Adding ${uris.size} songs…";val items=withContext(Dispatchers.IO){uris.mapNotNull { uri -> runCatching { keepUriPermission(uri);readTrack(uri,displayName(uri),fileSize(uri),"Selected files") }.getOrNull() }};items.forEach{t->val i=tracks.indexOfFirst{it.fingerprint==t.fingerprint};if(i>=0)tracks[i]=t else tracks.add(t)};scanProgress="${items.size} songs added";persistLibrary();postState() } }
    private fun displayName(uri:Uri):String { var n=uri.lastPathSegment?.substringAfterLast('/')?:"Audio";contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())n=it.getString(0)};return n }
    private fun fileSize(uri:Uri):Long { var n=0L;contentResolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)?.use{if(it.moveToFirst())n=it.getLong(0)};return n }
    private fun readTrack(uri:Uri,name:String,size:Long,folder:String):Track {
        val fp=hashPartial(uri,size);val base=name.substringBeforeLast('.',name);val parts=base.split(" - ",limit=2);var artist=if(parts.size==2)parts[0] else "Unknown artist";var title=if(parts.size==2)parts[1] else base
        var duration=0L;var album="Local files";var genre="Unknown";var art=""
        runCatching { val retriever=MediaMetadataRetriever(); try {
            retriever.setDataSource(this@MainActivity,uri)
            duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0
            title=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf{it.isNotBlank()}?:title
            artist=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf{it.isNotBlank()}?:artist
            album=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf{it.isNotBlank()}?:album
            genre=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)?.takeIf{it.isNotBlank()}?:genre
            retriever.embeddedPicture?.takeIf{it.size<=64*1024}?.let { bytes ->
                val mime=when { bytes.size>3&&bytes[0]==0x89.toByte()&&bytes[1]==0x50.toByte()->"image/png";else->"image/jpeg" }
                art="data:$mime;base64,"+Base64.encodeToString(bytes,Base64.NO_WRAP)
            }
        } finally { retriever.release() } }
        return Track(fp,title,artist,album,genre,folder,uri.toString(),duration,size,art)
    }
    private fun hashPartial(uri:Uri,size:Long):String {
        val digest=MessageDigest.getInstance("SHA-256");contentResolver.openInputStream(uri).use { stream -> if(stream!=null){val first=stream.readUpTo(65536);digest.update(first);if(size>65536){var skip=(maxOf(65536L,size-65536L)-first.size).coerceAtLeast(0);while(skip>0){val n=stream.skip(skip);if(n<=0)break;skip-=n};val tail=stream.readUpTo((size-first.size).coerceAtMost(65536).toInt());digest.update(tail)} } };digest.update(size.toString().toByteArray(Charsets.UTF_8));return digest.digest().joinToString(""){"%02x".format(it)}
    }

    private fun moveToFolder(track:Track,tree:Uri){lifecycleScope.launch{scanProgress="Moving ${track.title}…";val result=withContext(Dispatchers.IO){runCatching{val src=Uri.parse(track.uri);val root=DocumentFile.fromTreeUri(this@MainActivity,tree)?:error("Folder access is unavailable");val name=displayName(src);val dest=root.createFile(contentResolver.getType(src)?:"audio/mpeg",name)?:error("Could not create the audio file in that folder");contentResolver.openInputStream(src).use{input->contentResolver.openOutputStream(dest.uri,"w").use{output->if(input==null||output==null)error("Could not open the selected files");input.copyTo(output)}};val removed=runCatching{DocumentFile.fromSingleUri(this@MainActivity,src)?.delete()==true}.getOrDefault(false);Pair(dest.uri,removed)}};result.onSuccess{(uri,removed)->val i=tracks.indexOfFirst{it.fingerprint==track.fingerprint};if(i>=0)tracks[i]=tracks[i].copy(uri=uri.toString(),folder=DocumentFile.fromTreeUri(this,tree)?.name?:"Music");persistLibrary();postState();scanProgress="";display(if(removed)"Song moved to the selected folder" else "Copied to folder; the original file could not be removed")}.onFailure{scanProgress="";display(it.message?:"Could not move this song")}}}
    private fun deleteAudioFile(){val t=deleteTarget;showDeleteConfirm=false;if(t==null||!t.local){display("This track has no local file to delete");return};lifecycleScope.launch{val removed=withContext(Dispatchers.IO){runCatching{DocumentFile.fromSingleUri(this@MainActivity,Uri.parse(t.uri))?.delete()==true}.getOrDefault(false)};if(removed){tracks.removeAll{it.fingerprint==t.fingerprint};queue.removeAll{it==t.fingerprint};playlists.replaceAll{_,ids->ids.filterNot{it==t.fingerprint}};if(currentFingerprint==t.fingerprint){mediaPlayer?.stop();mediaPlayer?.release();mediaPlayer=null;currentFingerprint=null;playing=false};persistLibrary();withContext(Dispatchers.IO){runCatching{api("/api/tracks/remove","POST",JSONObject().put("fingerprint",t.fingerprint))}};postState();display("Audio file deleted permanently")}else display("Android denied delete access to this file")}}

    private fun play(t:Track) { if(!t.local){display("Select the local audio folder to play this synced song");return};if(t.fingerprint !in queue)queue.add(t.fingerprint);runCatching{mediaPlayer?.release();equalizer?.release();currentFingerprint=t.fingerprint;mediaPlayer=MediaPlayer().apply{setDataSource(this@MainActivity,Uri.parse(t.uri));setOnCompletionListener{if(stopAfterThisTrack){stopAfterThisTrack=false;playing=false;currentFingerprint=null}else nextTrack()};setOnPreparedListener{it.start();applyPlaybackParams();playing=true};setOnErrorListener{_,_,_->playing=false;display("Could not play this file");true};prepareAsync()};equalizer=runCatching{Equalizer(0,mediaPlayer!!.audioSessionId).apply{enabled=true}}.getOrNull();applyPlaybackParams();playing=false}.onFailure{display("Could not play this file")};persistLibrary();postState() }
    private fun togglePlayback() { if(mediaPlayer==null){tracks.firstOrNull{it.local}?.let(::play);return};if(playing)mediaPlayer?.pause() else runCatching{mediaPlayer?.start()};playing=!playing }
    private fun nextTrack(){val current=queue.indexOf(currentFingerprint);val fp=if(current>=0&&queue.isNotEmpty())queue[(current+1)%queue.size] else tracks.firstOrNull{it.local&&it.fingerprint!=currentFingerprint}?.fingerprint;tracks.firstOrNull{it.fingerprint==fp}?.let(::play)}
    private fun previousTrack(){val i=queue.indexOf(currentFingerprint);val fp=if(i>0)queue[i-1] else tracks.firstOrNull{it.local}?.fingerprint;tracks.firstOrNull{it.fingerprint==fp}?.let(::play)}
    private fun queueTrack(t:Track){if(t.fingerprint !in queue)queue.add(t.fingerprint);persistLibrary();postState();display("Added to queue")}
    private fun openTrackMenu(t:Track){selectedTrack=t}
    private fun trackAction(action:String,t:Track){when(action){"Play"->play(t);"Song info"->display("${t.title} · ${t.artist} · ${t.album}");"Add to queue"->queueTrack(t);"Play after current"->{val i=queue.indexOf(currentFingerprint);queue.add((i+1).coerceAtLeast(0),t.fingerprint);persistLibrary();postState()};"Preview"->{play(t);lifecycleScope.launch{delay(30_000);if(currentFingerprint==t.fingerprint&&playing){mediaPlayer?.pause();playing=false}}};"Edit tags"->{editTitle=t.title;editArtist=t.artist;selectedTrack=t;showEdit=true};"Stop after this song"->{stopAfterThisTrack=true;display("Playback will stop after this song")};"Add to playlist"->{playlists[selectedPlaylist]=(playlists[selectedPlaylist].orEmpty()+t.fingerprint).distinct();persistLibrary();postState();display("Added to $selectedPlaylist")};"Remove from queue"->{queue.remove(t.fingerprint);persistLibrary();postState()};"Playback speed & pitch"->showPlaybackParams=true;"Share audio"->shareTrack(t);"Move to folder"->{if(!t.local)display("Choose this track's file on this device first")else{moveTarget=t;openFolderPicker?.invoke()}};"Delete permanently"->{if(!t.local)display("There is no local audio file to delete")else{deleteTarget=t;showDeleteConfirm=true}};else->display("${action} is not available for this device yet")}}
    private fun startSleepTimer(){val minutes=sleepMinutes.toIntOrNull()?.coerceIn(1,360)?:30;showSleepTimer=false;sleepTimerJob?.cancel();sleepTimerJob=lifecycleScope.launch{delay(minutes*60_000L);runCatching{mediaPlayer?.pause()};playing=false;display("Sleep timer paused playback")}}
    private fun applyPlaybackParams(){runCatching{mediaPlayer?.playbackParams=PlaybackParams().setSpeed(playbackSpeed).setPitch(playbackPitch)}}
    private fun shareTrack(t:Track){if(!t.local){display("This song is not stored on this device");return};runCatching{val intent=Intent(Intent.ACTION_SEND).apply{type=contentResolver.getType(Uri.parse(t.uri))?:"audio/*";putExtra(Intent.EXTRA_STREAM,Uri.parse(t.uri));addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)};startActivity(Intent.createChooser(intent,"Share ${t.title}"))}.onFailure{display("Could not share this file")}}
    private fun saveTags(){selectedTrack?.let{target->val i=tracks.indexOfFirst{it.fingerprint==target.fingerprint};if(i>=0)tracks[i]=tracks[i].copy(title=editTitle,artist=editArtist);persistLibrary();postState()};selectedTrack=null;showEdit=false}

    @Composable private fun Welcome(onPick:()->Unit,onFolder:()->Unit,onPair:()->Unit){Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.size(70.dp).clip(RoundedCornerShape(24.dp)).background(Leaf),contentAlignment=Alignment.Center){Text("♫",color=Ink,style=MaterialTheme.typography.displaySmall)};Spacer(Modifier.height(20.dp));Text("Your music, in rhythm.",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Spacer(Modifier.height(8.dp));Text("Play audio from your device. Your files stay yours.",color=Muted);Spacer(Modifier.height(22.dp));Button(onClick=onFolder){Icon(Icons.Default.Folder,null);Spacer(Modifier.width(8.dp));Text("Choose a music folder")};OutlinedButton(onClick=onPick){Text("Select songs")};TextButton(onClick=onPair){Text("Connect another device")}}}

    @Composable private fun LibraryView(list:List<Track>,onPlay:(Track)->Unit,onQueue:(Track)->Unit,onMore:(Track)->Unit,onPick:()->Unit,onFolder:()->Unit){Column(Modifier.fillMaxSize()){Text(if(currentTab=="Home")"Good evening." else "Your songs",Modifier.padding(start=20.dp,top=20.dp,bottom=10.dp),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Row(Modifier.padding(horizontal=16.dp,vertical=5.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick=onPick){Text("＋ Add music")};OutlinedButton(onClick=onFolder){Text("Choose folder")};TextButton(onClick={currentTab="Queue"}){Text("${tracks.size} tracks")}};if(list.isEmpty())Text("Choose a folder to add your music library.",Modifier.padding(20.dp),color=Muted);LazyColumn { itemsIndexed(list,key={_,t->t.fingerprint}){index,t->TrackRow(t,index,onClick={onPlay(t)},onQueue={onQueue(t)},onMore={onMore(t)})} }}}
    @Composable private fun TrackList(list:List<Track>,title:String,onPlay:(Track)->Unit){Column{Text(title,Modifier.padding(20.dp),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);LazyColumn{itemsIndexed(list,key={_,t->t.fingerprint}){i,t->TrackRow(t,i,{onPlay(t)},{queueTrack(t)},{openTrackMenu(t)})}}}}
    @Composable private fun BrowseList(list:List<Track>,title:String,onPlay:(Track)->Unit,onFolder:()->Unit={}){Column{Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);if(title=="Folders")TextButton(onClick=onFolder){Text("＋ Add folder")}};LazyColumn{itemsIndexed(list,key={_,t->t.fingerprint}){i,t->TrackRow(t,i,{onPlay(t)},{},{})}}}}
    @Composable private fun PlaylistView(onPlay:(Track)->Unit){val list=playlists[selectedPlaylist].orEmpty().mapNotNull{id->tracks.firstOrNull{it.fingerprint==id}};Column{Text("Playlists",Modifier.padding(start=20.dp,top=20.dp,bottom=10.dp),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(2.dp)){playlists.keys.toList().forEach{name->TextButton(onClick={selectedPlaylist=name}){Text(name,color=if(name==selectedPlaylist)Leaf else Muted,maxLines=1)}}};LazyColumn{itemsIndexed(list,key={_,t->t.fingerprint}){i,t->TrackRow(t,i,{onPlay(t)},{queueTrack(t)},{openTrackMenu(t)})}}}}
    @Composable private fun Cover(t:Track,modifier:Modifier=Modifier){val bitmap=remember(t.artwork){runCatching{decodeCover(t.artwork)?.asImageBitmap()}.getOrNull()};if(bitmap!=null)Image(bitmap,null,modifier,contentScale=androidx.compose.ui.layout.ContentScale.Crop) else Box(modifier.background(BrushColor),contentAlignment=Alignment.Center){Text("♫",color=Leaf)}}
    @Composable private fun NowPlaying(t:Track?,isPlaying:Boolean,onToggle:()->Unit,onPrevious:()->Unit,onNext:()->Unit){if(t==null){Text("Choose a song to play",Modifier.padding(30.dp),color=Muted);return};Column(Modifier.fillMaxSize().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Cover(t,Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp)));Spacer(Modifier.height(28.dp));Text(t.title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(t.artist,color=Muted);Spacer(Modifier.height(25.dp));Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(24.dp)){IconButton(onClick=onPrevious){Icon(Icons.Default.SkipPrevious,null)};FilledIconButton(onClick=onToggle,colors=IconButtonDefaults.filledIconButtonColors(containerColor=Leaf,contentColor=Ink)){Icon(if(isPlaying)Icons.Default.Pause else Icons.Default.PlayArrow,null)};IconButton(onClick=onNext){Icon(Icons.Default.SkipNext,null)}}}}
    @Composable private fun PlayerStrip(track:Track?,playing:Boolean,onClick:()->Unit,onToggle:()->Unit,onNext:()->Unit){if(track==null)return;Row(Modifier.fillMaxWidth().background(Panel).clickable(onClick=onClick).padding(horizontal=15.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){Cover(track,Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)));Column(Modifier.weight(1f).padding(start=11.dp)){Text(track.title,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(track.artist,color=Muted,style=MaterialTheme.typography.labelSmall)};IconButton(onClick=onToggle){Icon(if(playing)Icons.Default.Pause else Icons.Default.PlayArrow,null)};IconButton(onClick=onNext){Icon(Icons.Default.SkipNext,null)}}}
    @Composable private fun TrackRow(t:Track,index:Int,onClick:()->Unit,onQueue:()->Unit,onMore:()->Unit){Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(horizontal=15.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Cover(t,Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)));Column(Modifier.weight(1f).padding(start=11.dp,end=6.dp)){Text(t.title,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(t.artist+if(t.local)"" else " · synced",color=Muted,style=MaterialTheme.typography.labelSmall,maxLines=1)};IconButton(onClick=onMore){Icon(Icons.Default.MoreVert,"Song options")}}}
    @Composable private fun MainMenu(visible:Boolean,onDismiss:()->Unit,onSelect:(String)->Unit){DropdownMenu(visible,onDismiss){listOf("Equalizer","Sleep timer","Scan folders","Sync devices","Settings","Help","About Groove","Close Groove").forEach{DropdownMenuItem(text={Text(it)},onClick={onSelect(it)},leadingIcon={Icon(when(it){"Equalizer"->Icons.Default.GraphicEq;"Settings"->Icons.Default.Settings;"Scan folders"->Icons.Default.Folder;else->Icons.Default.Tune},null)})}}}
    @Composable private fun PairDialog(onDismiss:()->Unit,onPair:(String,String)->Unit){var url by remember{mutableStateOf("")};var code by remember{mutableStateOf("")};AlertDialog(onDismissRequest=onDismiss,title={Text("Connect Groove devices")},text={Column{Text("Enter the sync server URL and pairing code. Audio files stay on this device.",color=Muted);Spacer(Modifier.height(12.dp));OutlinedTextField(url,{url=it},label={Text("Server URL")},singleLine=true);OutlinedTextField(code,{code=it},label={Text("Pairing code")},singleLine=true)}},confirmButton={TextButton(onClick={if(url.isNotBlank()&&code.isNotBlank())onPair(url,code)}){Text("Pair device")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})}
    @Composable private fun SleepDialog(minutes:String,onMinutes:(String)->Unit,onStart:()->Unit,onDismiss:()->Unit){AlertDialog(onDismissRequest=onDismiss,title={Text("Sleep timer")},text={Column{Text("Pause playback after a set time.",color=Muted);OutlinedTextField(minutes,onMinutes,label={Text("Minutes")},singleLine=true)}},confirmButton={TextButton(onClick=onStart){Text("Start timer")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})}
    @Composable private fun PlaybackDialog(speed:Float,pitch:Float,onSpeed:(Float)->Unit,onPitch:(Float)->Unit,onDismiss:()->Unit){AlertDialog(onDismissRequest=onDismiss,title={Text("Playback speed & pitch")},text={Column{Text("Speed · ${(speed*100).toInt()}%",color=Muted);Slider(speed,onSpeed,valueRange=0.5f..2f);Text("Pitch · ${(pitch*100).toInt()}%",color=Muted);Slider(pitch,onPitch,valueRange=0.5f..2f)}},confirmButton={TextButton(onClick=onDismiss){Text("Done")}})}
    @Composable private fun EqualizerDialog(eq:Equalizer?,onDismiss:()->Unit){AlertDialog(onDismissRequest=onDismiss,title={Text("Equalizer")},text={if(eq==null)Text("Start playback to adjust the device equalizer.",color=Muted)else{val bands=eq.numberOfBands.toInt();val range=eq.bandLevelRange;LazyColumn(Modifier.heightIn(max=420.dp)){items(bands){i->var level by remember(eq,i){mutableFloatStateOf(runCatching{eq.getBandLevel(i.toShort()).toFloat()}.getOrDefault(0f))};val center=runCatching{eq.getCenterFreq(i.toShort())/1000}.getOrDefault(0);Text("${center} Hz · ${(level/100).toInt()} dB",color=Muted,style=MaterialTheme.typography.labelSmall);Slider(level,{level=it;runCatching{eq.setBandLevel(i.toShort(),it.toInt().toShort())}},valueRange=range[0].toFloat()..range[1].toFloat())}}}},confirmButton={TextButton(onClick=onDismiss){Text("Done")}})}
    @Composable private fun TrackMenuDialog(t:Track,onDismiss:()->Unit,onAction:(String)->Unit){val actions=listOf("Play","Song info","Remove from queue","Play after current","Add to queue","Add to playlist","Preview","Stop after this song","Edit tags","Move to folder","Audio cutter","Set as ringtone","Playback speed & pitch","Share audio","Delete permanently");AlertDialog(onDismissRequest=onDismiss,title={Text(t.title,maxLines=1,overflow=TextOverflow.Ellipsis)},text={LazyColumn(Modifier.heightIn(max=480.dp)){items(actions){action->Text(action,Modifier.fillMaxWidth().clickable{onAction(action)}.padding(vertical=11.dp),color=if(action=="Delete permanently")Color(0xFFFF8888) else MaterialTheme.colorScheme.onSurface)}}},confirmButton={TextButton(onClick=onDismiss){Text("Close")}})}
    @Composable private fun EditDialog(title:String,onTitle:(String)->Unit,artist:String,onArtist:(String)->Unit,onSave:()->Unit,onDismiss:()->Unit){AlertDialog(onDismissRequest=onDismiss,title={Text("Edit tags")},text={Column{OutlinedTextField(title,onTitle,label={Text("Title")});OutlinedTextField(artist,onArtist,label={Text("Artist")})}},confirmButton={TextButton(onClick=onSave){Text("Save")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})}

    override fun onDestroy(){super.onDestroy();syncJob?.cancel();sleepTimerJob?.cancel();equalizer?.release();mediaPlayer?.release();mediaPlayer=null}
}

private val BrushColor = Color(0xFF1C291D)

private fun decodeCover(dataUri:String):android.graphics.Bitmap? {
    val encoded=dataUri.substringAfter(',',"")
    if(encoded.isBlank()||encoded.length>90_000)return null
    val bytes=Base64.decode(encoded,Base64.DEFAULT)
    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
    var sample=1
    while(bounds.outWidth/sample>512||bounds.outHeight/sample>512)sample*=2
    return BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})
}

private fun InputStream.readUpTo(limit: Int): ByteArray {
    val out = ByteArray(limit)
    var count = 0
    while (count < limit) {
        val n = read(out, count, limit - count)
        if (n <= 0) break
        count += n
    }
    return if (count == limit) out else out.copyOf(count)
}
