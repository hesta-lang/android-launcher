package com.geode.launcher

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.util.Locale

/** Local music fallback selected through Android's Storage Access Framework. */
object OfflineMusicPlayer {
    private const val TAG = "OfflineMusicPlayer"
    private const val PREFS = "music_integrations"
    private const val KEY_TREE_URI = "offline_music_tree_uri"
    private val audioExtensions = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "3gp")
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var currentIndex = -1
    @Volatile private var currentContext: Context? = null
    @Volatile private var files: List<Uri> = emptyList()
    @Volatile private var localMode = false

    fun selectedFolder(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null)?.let(Uri::parse)

    fun isLocalMode(): Boolean = localMode

    @Synchronized fun switchToSpotifyMode() {
        localMode = false
        runCatching { player?.pause() }
    }

    fun saveFolder(context: Context, uri: Uri) {
        localMode = true
        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TREE_URI, uri.toString()).apply()
        files = emptyList()
        currentIndex = -1
    }

    @Synchronized
    fun playOrResume(context: Context): Boolean {
        currentContext = context.applicationContext
        val existing = player
        if (existing != null) {
            if (!existing.isPlaying) runCatching { existing.start() }
            return true
        }
        val tracks = loadTracks(context)
        if (tracks.isEmpty()) {
            Log.w(TAG, "No supported audio files found in selected folder")
            return false
        }
        files = tracks
        currentIndex = 0
        return playAt(context.applicationContext, currentIndex)
    }

    @Synchronized fun pause(): Boolean {
        val active = player ?: return false
        return runCatching { if (active.isPlaying) active.pause() }.isSuccess
    }

    @Synchronized fun next(): Boolean {
        val context = currentContext ?: return false
        if (files.isEmpty()) files = loadTracks(context)
        if (files.isEmpty()) return false
        currentIndex = if (currentIndex + 1 >= files.size) 0 else currentIndex + 1
        return playAt(context, currentIndex)
    }

    @Synchronized fun previous(): Boolean {
        val context = currentContext ?: return false
        if (files.isEmpty()) files = loadTracks(context)
        if (files.isEmpty()) return false
        currentIndex = if (currentIndex <= 0) files.lastIndex else currentIndex - 1
        return playAt(context, currentIndex)
    }

    @Synchronized fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    fun isPlaying(): Boolean = runCatching { player?.isPlaying == true }.getOrDefault(false)

    private fun loadTracks(context: Context): List<Uri> {
        val tree = selectedFolder(context) ?: return emptyList()
        return runCatching {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
            val result = mutableListOf<Uri>()
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn).orEmpty()
                    val mime = cursor.getString(mimeColumn).orEmpty()
                    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (mime.startsWith("audio/") || ext in audioExtensions) {
                        result += DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idColumn))
                    }
                }
            }
            result.sortedBy { it.lastPathSegment.orEmpty().lowercase(Locale.ROOT) }
        }.onFailure { Log.e(TAG, "Unable to read selected music folder", it) }.getOrDefault(emptyList())
    }

    @Synchronized private fun playAt(context: Context, index: Int): Boolean {
        if (index !in files.indices) return false
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        return runCatching {
            val nextPlayer = MediaPlayer()
            nextPlayer.setDataSource(context, files[index])
            nextPlayer.setOnCompletionListener { next() }
            nextPlayer.setOnErrorListener { mp, what, extra ->
                Log.w(TAG, "Local MediaPlayer error what=$what extra=$extra")
                runCatching { mp.release() }
                if (player === mp) player = null
                true
            }
            nextPlayer.prepare()
            nextPlayer.start()
            player = nextPlayer
            Log.i(TAG, "Playing local audio " + files[index])
            true
        }.onFailure { Log.e(TAG, "Could not play local audio", it) }.getOrDefault(false)
    }
}
