package imehc.recoow.core.backup

import android.database.sqlite.SQLiteDatabase
import android.system.Os
import android.system.OsConstants
import imehc.recoow.core.database.DatabaseRecovery
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

interface BackupPreferences {
    suspend fun capture(): String
    suspend fun restore(json: String)
}

/** Private, same-installation rollback bundles. These are not the iOS interchange format. */
class LocalRollbackFiles(
    private val dataDirectory: File,
    private val backupDirectory: File,
    private val preferences: BackupPreferences,
    private val installationId: String,
) : DatabaseRecovery {
    private val marker get() = File(backupDirectory, "pending-restore")

    override suspend fun beforeOpen(version: Int) {
        recover()
        val database = File(dataDirectory, "records.sqlite")
        if (!database.exists()) return
        val oldVersion = SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY,
            android.database.DatabaseErrorHandler { throw IllegalStateException("Damaged database retained for recovery") }).use { db ->
            db.rawQuery("PRAGMA integrity_check(1)", null).use {
                check(it.moveToFirst() && it.getString(0) == "ok") { "Damaged database retained for recovery" }
            }
            db.version
        }
        require(oldVersion <= version) { "Database downgrade is not supported" }
        if (oldVersion in 1 until version) begin("upgrade-$oldVersion-to-$version")
    }

    override suspend fun opened() = commit()
    override suspend fun failed() { recover() }

    override suspend fun begin(reason: String): File {
        check(!marker.exists()) { "Recovery must finish before another operation" }
        check(backupDirectory.isDirectory || backupDirectory.mkdirs())
        val name = "rollback-${UUID.randomUUID()}"
        val staging = File(backupDirectory, ".$name")
        val bundle = File(backupDirectory, name)
        check(staging.mkdir())
        try {
            for (suffix in listOf("", "-wal", "-journal")) {
                val source = File(dataDirectory, "records.sqlite$suffix")
                if (source.exists()) copyFile(source, File(staging, source.name))
            }
            copyTree(File(dataDirectory, "media"), File(staging, "media"))
            val entries = JSONArray()
            staging.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                entries.put(JSONObject().put("path", file.relativeTo(staging).invariantSeparatorsPath)
                    .put("size", file.length()).put("sha256", checksum(file)))
            }
            val manifest = JSONObject().put("format", 1).put("installation", installationId)
                .put("reason", reason).put("preferences", preferences.capture()).put("files", entries)
            durableWrite(File(staging, "manifest.json"), manifest.toString())
            syncDirectory(staging)
            check(staging.renameTo(bundle)) { "Cannot publish backup" }
            syncDirectory(backupDirectory)
            verify(bundle)
            // The marker is published last; an incomplete backup never authorizes a write.
            val temporaryMarker = File(backupDirectory, ".pending-restore")
            durableWrite(temporaryMarker, name)
            check(temporaryMarker.renameTo(marker))
            syncDirectory(backupDirectory)
            return bundle
        } finally {
            staging.deleteRecursively()
        }
    }

    override suspend fun commit() {
        if (marker.exists()) {
            check(marker.delete()) { "Cannot finalize guarded operation" }
            syncDirectory(backupDirectory)
        }
    }

    private suspend fun recover() {
        if (!marker.exists()) return
        val name = marker.readText()
        require(name.matches(Regex("rollback-[a-f0-9-]+"))) { "Invalid recovery marker" }
        val bundle = File(backupDirectory, name)
        val manifest = verify(bundle)
        check(dataDirectory.isDirectory || dataDirectory.mkdirs())
        // No database may be open here. The marker remains until every file and preference is restored.
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val target = File(dataDirectory, "records.sqlite$suffix")
            val source = File(bundle, target.name)
            if (source.exists()) {
                val temporary = File(dataDirectory, ".restore-${target.name}")
                copyFile(source, temporary)
                check(temporary.renameTo(target)) { "Cannot restore ${target.name}" }
            } else if (target.exists()) check(target.delete())
        }
        val stagingMedia = File(dataDirectory, ".restore-media")
        if (stagingMedia.exists()) check(stagingMedia.deleteRecursively())
        copyTree(File(bundle, "media"), stagingMedia)
        val media = File(dataDirectory, "media")
        if (media.exists()) check(media.deleteRecursively())
        check(stagingMedia.renameTo(media))
        syncDirectory(dataDirectory)
        preferences.restore(manifest.getString("preferences"))
        commit()
    }

    private fun verify(bundle: File): JSONObject {
        val manifest = JSONObject(File(bundle, "manifest.json").readText())
        require(manifest.getInt("format") == 1 && manifest.getString("installation") == installationId) {
            "Backup belongs to a different installation or format"
        }
        val entries = manifest.getJSONArray("files")
        val paths = mutableSetOf<String>()
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val path = entry.getString("path")
            require(path in listOf("records.sqlite", "records.sqlite-wal", "records.sqlite-journal") ||
                path.startsWith("media/") && path.split('/').none { it == ".." || it.isEmpty() })
            require(paths.add(path))
            val file = File(bundle, path)
            require(file.canonicalPath.startsWith(bundle.canonicalPath + File.separator) && !Files.isSymbolicLink(file.toPath()))
            require(file.isFile && file.length() == entry.getLong("size") && checksum(file) == entry.getString("sha256")) {
                "Backup checksum mismatch: $path"
            }
        }
        val actual = bundle.walkTopDown().filter { it.isFile && it != File(bundle, "manifest.json") }
            .map { it.relativeTo(bundle).invariantSeparatorsPath }.toSet()
        require(actual == paths) { "Unexpected backup files" }
        // Even a read-only Android SQLite open can create WAL/SHM files. Keep the bundle immutable.
        if (File(bundle, "records.sqlite").exists()) {
            val scratch = File(backupDirectory, ".verify-${UUID.randomUUID()}")
            check(scratch.mkdir())
            try {
                for (suffix in listOf("", "-wal", "-journal")) {
                    val source = File(bundle, "records.sqlite$suffix")
                    if (source.exists()) copyFile(source, File(scratch, source.name))
                }
                SQLiteDatabase.openDatabase(File(scratch, "records.sqlite").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("PRAGMA integrity_check(1)", null).use { cursor ->
                        check(cursor.moveToFirst() && cursor.getString(0) == "ok") { "Invalid backup database" }
                    }
                }
            } finally { scratch.deleteRecursively() }
        }
        return manifest
    }

    private fun copyTree(source: File, destination: File) {
        check(destination.isDirectory || destination.mkdirs())
        if (!source.exists()) return
        source.walkTopDown().forEach { item ->
            require(!Files.isSymbolicLink(item.toPath())) { "Symlinks are not supported in application media" }
            val output = File(destination, item.relativeTo(source).path)
            if (item.isDirectory) check(output.isDirectory || output.mkdirs()) else copyFile(item, output)
        }
        destination.walkBottomUp().filter { it.isDirectory }.forEach(::syncDirectory)
    }

    private fun copyFile(source: File, destination: File) {
        require(!Files.isSymbolicLink(source.toPath()))
        source.inputStream().use { input -> FileOutputStream(destination).use { output ->
            input.copyTo(output)
            output.fd.sync()
        } }
    }

    private fun checksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun durableWrite(file: File, value: String) {
        FileOutputStream(file).use { it.write(value.toByteArray(Charsets.UTF_8)); it.fd.sync() }
    }

    private fun syncDirectory(directory: File) {
        require(directory.isDirectory)
        val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
}
