package io.github.forensax.nasphotosgateway

import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

/** Single-task metadata on disk. No media bytes are stored here. */
class ScanIndex(directory: File) : Closeable {
    private val file: File
    private val db: SQLiteDatabase
    init {
        check(directory.mkdirs() || directory.isDirectory) { "无法创建扫描临时目录" }
        // Repository serializes scans. Remove leftovers from dead tasks.
        directory.listFiles()?.filter { it.name.startsWith("scan-") && it.name.endsWith(".db") }
            ?.forEach { SQLiteDatabase.deleteDatabase(it) }
        file = File.createTempFile("scan-", ".db", directory)
        db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("PRAGMA temp_store=FILE")
        db.execSQL("CREATE TABLE listing(slot INTEGER, key TEXT, kind INTEGER, PRIMARY KEY(slot,key,kind))")
        db.execSQL("CREATE TABLE work(id INTEGER PRIMARY KEY, path TEXT NOT NULL, staged INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE media(id INTEGER PRIMARY KEY, path TEXT, key TEXT, type INTEGER, candidate INTEGER DEFAULT 0)")
        db.execSQL("CREATE INDEX media_key ON media(key)")
    }
    fun clearListing(slot: Int) { db.delete("listing", "slot=?", arrayOf(slot.toString())) }
    fun addPath(slot: Int, path: String, directory: Boolean) {
        require(MediaIndexPolicy.validRelativePath(path)) { "NAS 清单路径无效" }
        db.execSQL("INSERT OR IGNORE INTO listing VALUES(?,?,?)", arrayOf(slot, MediaIndexPolicy.key(path), if (directory) 1 else 0))
    }
    fun contains(slot: Int, path: String): Boolean = exists(
        "SELECT 1 FROM listing WHERE slot=? AND key=? LIMIT 1", slot.toString(), MediaIndexPolicy.key(path))
    fun count(slot: Int): Long = db.rawQuery("SELECT count(*) FROM listing WHERE slot=?", arrayOf(slot.toString())).use {
        it.moveToFirst(); it.getLong(0)
    }
    fun sameListings(a: Int, b: Int): Boolean = !exists(
        "SELECT key,kind FROM listing WHERE slot=? EXCEPT SELECT key,kind FROM listing WHERE slot=? LIMIT 1", a.toString(), b.toString()) && !exists(
        "SELECT key,kind FROM listing WHERE slot=? EXCEPT SELECT key,kind FROM listing WHERE slot=? LIMIT 1", b.toString(), a.toString())
    fun <T> batch(block: (tick: () -> Unit) -> T): T {
        var pending = 0
        db.beginTransaction()
        try {
            val result = block {
                if (++pending == 256) {
                    db.setTransactionSuccessful(); db.endTransaction(); db.beginTransaction(); pending = 0
                }
            }
            return result
        } finally {
            // Keep the successfully enqueued prefix even if a directory read
            // fails. Completeness is tracked independently by the scan engine.
            try { db.setTransactionSuccessful() } finally { db.endTransaction() }
        }
    }
    fun enqueue(path: String) { db.execSQL("INSERT INTO work(path) VALUES(?)", arrayOf(path)) }
    fun stage(path: String) { db.execSQL("INSERT INTO work(path,staged) VALUES(?,1)", arrayOf(path)) }
    fun finishDirectory(include: Boolean) {
        if (include) db.execSQL("UPDATE work SET staged=0 WHERE staged=1")
        else db.delete("work", "staged=1", null)
    }
    fun nextWork(): String? {
        val row = db.rawQuery("SELECT id,path FROM work WHERE staged=0 ORDER BY id LIMIT 1", null).use {
            if (it.moveToFirst()) it.getLong(0) to it.getString(1) else null
        } ?: return null
        db.delete("work", "id=?", arrayOf(row.first.toString()))
        return row.second
    }
    fun addMedia(root: String, entry: MediaIndexEntry) {
        val relative = MediaIndexPolicy.relativePath(root, entry.path) ?: return
        db.execSQL("INSERT OR REPLACE INTO media(id,path,key,type) VALUES(?,?,?,?)",
            arrayOf(entry.id, entry.path, MediaIndexPolicy.key(relative), entry.mediaType))
    }
    fun missing(slot: Int, after: Long = -1): List<MediaIndexEntry> = db.rawQuery(
        "SELECT id,path,type FROM media m WHERE id>? AND type IN (1,3) AND NOT EXISTS " +
            "(SELECT 1 FROM listing l WHERE l.slot=? AND l.key=m.key) ORDER BY id LIMIT 128",
        arrayOf(after.toString(), slot.toString())).use { cursor -> buildList {
            while (cursor.moveToNext()) add(MediaIndexEntry(cursor.getLong(0), cursor.getString(1), cursor.getInt(2)))
        } }
    fun prefixCollision(root: String, entry: MediaIndexEntry, slot: Int): Boolean {
        val key = MediaIndexPolicy.key(MediaIndexPolicy.relativePath(root, entry.path) ?: return true)
        // Indexed successor lookup avoids LIKE wildcard and full-memory scans.
        return firstKey("SELECT key FROM listing WHERE slot=? AND key>=? ORDER BY key LIMIT 1", slot.toString(), key)?.startsWith(key) == true ||
            firstKey("SELECT key FROM media WHERE key>=? AND id!=? ORDER BY key LIMIT 1", key, entry.id.toString())?.startsWith(key) == true
    }
    fun markCandidate(id: Long) { db.execSQL("UPDATE media SET candidate=1 WHERE id=?", arrayOf(id)) }
    fun candidates(after: Long = -1): List<MediaIndexEntry> = db.rawQuery(
        "SELECT id,path,type FROM media WHERE candidate=1 AND id>? ORDER BY id LIMIT 128", arrayOf(after.toString())).use { cursor -> buildList {
            while (cursor.moveToNext()) add(MediaIndexEntry(cursor.getLong(0), cursor.getString(1), cursor.getInt(2)))
        } }
    private fun exists(sql: String, vararg args: String): Boolean = db.rawQuery(sql, args).use { it.moveToFirst() }
    private fun firstKey(sql: String, vararg args: String): String? = db.rawQuery(sql, args).use { if (it.moveToFirst()) it.getString(0) else null }
    override fun close() { db.close(); SQLiteDatabase.deleteDatabase(file) }
}
