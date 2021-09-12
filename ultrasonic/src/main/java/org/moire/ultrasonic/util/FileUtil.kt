/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */
package org.moire.ultrasonic.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.text.TextUtils
import org.koin.java.KoinJavaComponent.inject
import org.moire.ultrasonic.app.UApp.Companion.applicationContext
import org.moire.ultrasonic.domain.MusicDirectory
import org.moire.ultrasonic.util.Util.close
import org.moire.ultrasonic.util.Util.getPreferences
import org.moire.ultrasonic.util.Util.md5Hex
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.Arrays
import java.util.Locale
import java.util.SortedSet
import java.util.TreeSet
import java.util.regex.Pattern

/**
 * @author Sindre Mehus
 */
object FileUtil {
    private val FILE_SYSTEM_UNSAFE = arrayOf("/", "\\", "..", ":", "\"", "?", "*", "<", ">", "|")
    private val FILE_SYSTEM_UNSAFE_DIR = arrayOf("\\", "..", ":", "\"", "?", "*", "<", ">", "|")
    private val MUSIC_FILE_EXTENSIONS =
        Arrays.asList("mp3", "ogg", "aac", "flac", "m4a", "wav", "wma", "opus")
    private val VIDEO_FILE_EXTENSIONS =
        Arrays.asList("flv", "mp4", "m4v", "wmv", "avi", "mov", "mpg", "mkv")
    private val PLAYLIST_FILE_EXTENSIONS = listOf("m3u")
    private val TITLE_WITH_TRACK = Pattern.compile("^\\d\\d-.*")
    const val SUFFIX_LARGE = ".jpeg"
    const val SUFFIX_SMALL = ".jpeg-small"
    private val permissionUtil = inject<PermissionUtil>(
        PermissionUtil::class.java
    )

    fun getSongFile(song: MusicDirectory.Entry): File {
        val dir = getAlbumDirectory(song)

        // Do not generate new name for offline files. Offline files will have their Path as their Id.
        if (!TextUtils.isEmpty(song.id)) {
            if (song.id.startsWith(dir!!.absolutePath)) return File(song.id)
        }

        // Generate a file name for the song
        val fileName = StringBuilder(256)
        val track = song.track

        //check if filename already had track number
        if (!TITLE_WITH_TRACK.matcher(song.title).matches()) {
            if (track != null) {
                if (track < 10) {
                    fileName.append('0')
                }
                fileName.append(track).append('-')
            }
        }
        fileName.append(fileSystemSafe(song.title!!)).append('.')
        if (!TextUtils.isEmpty(song.transcodedSuffix)) {
            fileName.append(song.transcodedSuffix)
        } else {
            fileName.append(song.suffix)
        }
        return File(dir, fileName.toString())
    }

    @JvmStatic
	fun getPlaylistFile(server: String?, name: String): File {
        val playlistDir = getPlaylistDirectory(server)
        return File(playlistDir, String.format("%s.m3u", fileSystemSafe(name)))
    }

    @JvmStatic
	val playlistDirectory: File
        get() {
            val playlistDir = File(ultrasonicDirectory, "playlists")
            ensureDirectoryExistsAndIsReadWritable(playlistDir)
            return playlistDir
        }

    fun getPlaylistDirectory(server: String?): File {
        val playlistDir = File(playlistDirectory, server)
        ensureDirectoryExistsAndIsReadWritable(playlistDir)
        return playlistDir
    }

    /**
     * Get the album art file for a given album entry
     * @param entry The album entry
     * @return File object. Not guaranteed that it exists
     */
    fun getAlbumArtFile(entry: MusicDirectory.Entry?): File? {
        val albumDir = getAlbumDirectory(entry)
        return getAlbumArtFile(albumDir)
    }

    /**
     * Get the cache key for a given album entry
     * @param entry The album entry
     * @param large Whether to get the key for the large or the default image
     * @return String The hash key
     */
    fun getAlbumArtKey(entry: MusicDirectory.Entry?, large: Boolean): String? {
        val albumDir = getAlbumDirectory(entry)
        return getAlbumArtKey(albumDir, large)
    }

    /**
     * Get the cache key for a given album entry
     * @param albumDir The album directory
     * @param large Whether to get the key for the large or the default image
     * @return String The hash key
     */
    fun getAlbumArtKey(albumDir: File?, large: Boolean): String? {
        if (albumDir == null) {
            return null
        }
        val suffix = if (large) SUFFIX_LARGE else SUFFIX_SMALL
        return String.format(Locale.ROOT, "%s%s", md5Hex(albumDir.path), suffix)
    }

    fun getAvatarFile(username: String?): File? {
        val albumArtDir = albumArtDirectory
        if (albumArtDir == null || username == null) {
            return null
        }
        val md5Hex = md5Hex(username)
        return File(albumArtDir, String.format("%s%s", md5Hex, SUFFIX_LARGE))
    }

    /**
     * Get the album art file for a given album directory
     * @param albumDir The album directory
     * @return File object. Not guaranteed that it exists
     */
    fun getAlbumArtFile(albumDir: File?): File? {
        val albumArtDir = albumArtDirectory
        val key = getAlbumArtKey(albumDir, true)
        return if (key == null || albumArtDir == null) {
            null
        } else File(albumArtDir, key)
    }

    /**
     * Get the album art file for a given cache key
     * @param cacheKey The key (== the filename)
     * @return File object. Not guaranteed that it exists
     */
    fun getAlbumArtFile(cacheKey: String?): File? {
        val albumArtDir = albumArtDirectory
        return if (albumArtDir == null || cacheKey == null) {
            null
        } else File(albumArtDir, cacheKey)
    }

    val albumArtDirectory: File
        get() {
            val albumArtDir = File(ultrasonicDirectory, "artwork")
            ensureDirectoryExistsAndIsReadWritable(albumArtDir)
            ensureDirectoryExistsAndIsReadWritable(File(albumArtDir, ".nomedia"))
            return albumArtDir
        }

    fun getAlbumDirectory(entry: MusicDirectory.Entry?): File? {
        if (entry == null) {
            return null
        }
        val dir: File
        if (!TextUtils.isEmpty(entry.path)) {
            val f = File(fileSystemSafeDir(entry.path))
            dir = File(
                String.format(
                    "%s/%s",
                    musicDirectory.path,
                    if (entry.isDirectory) f.path else f.parent
                )
            )
        } else {
            val artist = fileSystemSafe(entry.artist!!)
            var album = fileSystemSafe(entry.album!!)
            if ("unnamed" == album) {
                album = fileSystemSafe(entry.title!!)
            }
            dir = File(String.format("%s/%s/%s", musicDirectory.path, artist, album))
        }
        return dir
    }

    fun createDirectoryForParent(file: File) {
        val dir = file.parentFile
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                Timber.e("Failed to create directory %s", dir)
            }
        }
    }

    private fun getOrCreateDirectory(name: String): File {
        val dir = File(ultrasonicDirectory, name)
        if (!dir.exists() && !dir.mkdirs()) {
            Timber.e("Failed to create %s", name)
        }
        return dir
    }

    // After Android M, the location of the files must be queried differently. GetExternalFilesDir will always return a directory which Ultrasonic can access without any extra privileges.
	@JvmStatic
	val ultrasonicDirectory: File?
        get() = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) File(
            Environment.getExternalStorageDirectory(),
            "Android/data/org.moire.ultrasonic"
        ) else applicationContext().getExternalFilesDir(null)

    // After Android M, the location of the files must be queried differently. GetExternalFilesDir will always return a directory which Ultrasonic can access without any extra privileges.
	@JvmStatic
	val defaultMusicDirectory: File
        get() = getOrCreateDirectory("music")
    @JvmStatic
	val musicDirectory: File
        get() {
            val defaultMusicDirectory = defaultMusicDirectory
            val path = getPreferences().getString(
                Constants.PREFERENCES_KEY_CACHE_LOCATION,
                defaultMusicDirectory.path
            )
            val dir = File(path)
            val hasAccess = ensureDirectoryExistsAndIsReadWritable(dir)
            if (!hasAccess) permissionUtil.value.handlePermissionFailed(null)
            return if (hasAccess) dir else defaultMusicDirectory
        }

    @JvmStatic
	fun ensureDirectoryExistsAndIsReadWritable(dir: File?): Boolean {
        if (dir == null) {
            return false
        }
        if (dir.exists()) {
            if (!dir.isDirectory) {
                Timber.w("%s exists but is not a directory.", dir)
                return false
            }
        } else {
            if (dir.mkdirs()) {
                Timber.i("Created directory %s", dir)
            } else {
                Timber.w("Failed to create directory %s", dir)
                return false
            }
        }
        if (!dir.canRead()) {
            Timber.w("No read permission for directory %s", dir)
            return false
        }
        if (!dir.canWrite()) {
            Timber.w("No write permission for directory %s", dir)
            return false
        }
        return true
    }

    /**
     * Makes a given filename safe by replacing special characters like slashes ("/" and "\")
     * with dashes ("-").
     *
     * @param filename The filename in question.
     * @return The filename with special characters replaced by hyphens.
     */
    private fun fileSystemSafe(filename: String): String {
        var filename = filename
        if (filename == null || filename.trim { it <= ' ' }.isEmpty()) {
            return "unnamed"
        }
        for (s in FILE_SYSTEM_UNSAFE) {
            filename = filename.replace(s, "-")
        }
        return filename
    }

    /**
     * Makes a given filename safe by replacing special characters like colons (":")
     * with dashes ("-").
     *
     * @param path The path of the directory in question.
     * @return The the directory name with special characters replaced by hyphens.
     */
    private fun fileSystemSafeDir(path: String?): String? {
        var path = path
        if (path == null || path.trim { it <= ' ' }.isEmpty()) {
            return ""
        }
        for (s in FILE_SYSTEM_UNSAFE_DIR) {
            path = path!!.replace(s, "-")
        }
        return path
    }

    /**
     * Similar to [File.listFiles], but returns a sorted set.
     * Never returns `null`, instead a warning is logged, and an empty set is returned.
     */
	@JvmStatic
	fun listFiles(dir: File): SortedSet<File> {
        val files = dir.listFiles()
        if (files == null) {
            Timber.w("Failed to list children for %s", dir.path)
            return TreeSet()
        }
        return TreeSet(Arrays.asList(*files))
    }

    fun listMediaFiles(dir: File): SortedSet<File> {
        val files = listFiles(dir)
        val iterator = files.iterator()
        while (iterator.hasNext()) {
            val file = iterator.next()
            if (!file.isDirectory && !isMediaFile(file)) {
                iterator.remove()
            }
        }
        return files
    }

    private fun isMediaFile(file: File): Boolean {
        val extension = getExtension(file.name)
        return MUSIC_FILE_EXTENSIONS.contains(extension) || VIDEO_FILE_EXTENSIONS.contains(extension)
    }

    fun isPlaylistFile(file: File): Boolean {
        val extension = getExtension(file.name)
        return PLAYLIST_FILE_EXTENSIONS.contains(extension)
    }

    /**
     * Returns the extension (the substring after the last dot) of the given file. The dot
     * is not included in the returned extension.
     *
     * @param name The filename in question.
     * @return The extension, or an empty string if no extension is found.
     */
    fun getExtension(name: String): String {
        val index = name.lastIndexOf('.')
        return if (index == -1) "" else name.substring(index + 1).toLowerCase()
    }

    /**
     * Returns the base name (the substring before the last dot) of the given file. The dot
     * is not included in the returned basename.
     *
     * @param name The filename in question.
     * @return The base name, or an empty string if no basename is found.
     */
    fun getBaseName(name: String): String {
        val index = name.lastIndexOf('.')
        return if (index == -1) name else name.substring(0, index)
    }

    /**
     * Returns the file name of a .partial file of the given file.
     *
     * @param name The filename in question.
     * @return The .partial file name
     */
    fun getPartialFile(name: String): String {
        return String.format("%s.partial.%s", getBaseName(name), getExtension(name))
    }

    /**
     * Returns the file name of a .complete file of the given file.
     *
     * @param name The filename in question.
     * @return The .complete file name
     */
    fun getCompleteFile(name: String): String {
        return String.format("%s.complete.%s", getBaseName(name), getExtension(name))
    }

    @JvmStatic
	fun <T : Serializable?> serialize(context: Context, obj: T, fileName: String?): Boolean {
        val file = File(context.cacheDir, fileName)
        var out: ObjectOutputStream? = null
        return try {
            out = ObjectOutputStream(FileOutputStream(file))
            out.writeObject(obj)
            Timber.i("Serialized object to %s", file)
            true
        } catch (x: Throwable) {
            Timber.w("Failed to serialize object to %s", file)
            false
        } finally {
            close(out)
        }
    }

    @JvmStatic
	fun <T : Serializable?> deserialize(context: Context, fileName: String?): T? {
        val file = File(context.cacheDir, fileName)
        if (!file.exists() || !file.isFile) {
            return null
        }
        var `in`: ObjectInputStream? = null
        return try {
            `in` = ObjectInputStream(FileInputStream(file))
            val `object` = `in`.readObject()
            val result = `object` as T
            Timber.i("Deserialized object from %s", file)
            result
        } catch (x: Throwable) {
            Timber.w(x, "Failed to deserialize object from %s", file)
            null
        } finally {
            close(`in`)
        }
    }
}