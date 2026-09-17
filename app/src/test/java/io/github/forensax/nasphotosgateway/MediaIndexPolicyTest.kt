package io.github.forensax.nasphotosgateway

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MediaIndexPolicyTest {
    private val root = Paths.get("/storage/emulated/0/DCIM/NAS_test")
    private val identity = "10:11:0:29:101:ro"
    private fun store() = ScanIndex(File(RuntimeEnvironment.getApplication().cacheDir, "scan-tests"))
    private fun listing(entries: String, end: String = identity) = """{"identity":"$identity","entries":$entries,"end":"$end"}"""
    private fun snapshot(slot: Int = 0, complete: Boolean = true) = RemoteSnapshot(identity, slot, complete)
    private class FakeFiles(val root: Path) : ScanFiles() {
        val nodes = linkedMapOf(root to true)
        val badReads = mutableSetOf<Path>()
        val badDirectories = mutableSetOf<Path>()
        val badAttributes = mutableSetOf<Path>()
        val links = mutableSetOf<Path>()
        var genericMissing = false
        fun add(relative: String, dir: Boolean = false) { nodes[root.resolve(relative)] = dir }
        override fun attributes(path: Path): BasicFileAttributes {
            if (path in badAttributes) throw IOException("cannot stat path")
            val directory = nodes[path] ?: if (genericMissing) throw IOException("EIO") else throw NoSuchFileException(path.toString())
            return object : BasicFileAttributes {
                override fun isDirectory() = directory
                override fun isRegularFile() = !directory
                override fun isSymbolicLink() = path in links
                override fun isOther() = false
                override fun size() = 1L
                override fun fileKey(): Any = path
                override fun creationTime() = FileTime.fromMillis(0)
                override fun lastAccessTime() = creationTime()
                override fun lastModifiedTime() = creationTime()
            }
        }
        override fun children(path: Path, accept: (Path) -> Unit) {
            if (path in badDirectories) throw IOException("unreadable directory")
            nodes.keys.filter { it != root && it.parent == path }.forEach(accept)
        }
        override fun readable(path: Path): Boolean {
            if (path in badReads) throw IOException("bad file")
            return nodes.containsKey(path)
        }
    }
    private class FakeMedia(val fs: FakeFiles) : ScanMedia {
        val records = mutableListOf<MediaIndexEntry>()
        val scanned = mutableListOf<String>()
        var onScan: (String) -> Unit = {}
        override suspend fun scan(path: String): MediaScan {
            scanned += path
            onScan(path)
            val exists = fs.nodes.containsKey(Paths.get(path))
            if (!exists) records.removeAll { it.path == path }
            return MediaScan(true, exists, exists)
        }
        override fun entries(accept: (MediaIndexEntry) -> Unit) { records.toList().forEach(accept) }
        override fun indexExists(id: Long) = records.any { it.id == id }
    }
    @Test fun streamsOverTenThousandEntriesAndFourMiBWithDeepPaths() = store().use { index ->
        val longName = "x".repeat(420)
        val entries = (0..11000).joinToString(",", "[", "]") { """{"Path":"$longName/$it.jpg","IsDir":false}""" }
        assertTrue(entries.length > 4 * 1024 * 1024)
        val parsed = RemoteSnapshot.read(listing(entries).reader(), 0, index)
        assertTrue(parsed.complete)
        assertEquals(11001L, index.count(0))
        val deep = (1..40).joinToString("/") { "d" } + "/a.jpg"
        assertTrue(RemoteSnapshot.read(listing("""[{"Path":"$deep","IsDir":false}]""").reader(), 1, index).complete)
    }
    @Test fun malformedIncompleteAndChangedIdentityNeverComplete() = store().use { index ->
        for (text in listOf(listing("["), listing("[]", "12:13:0:30:102:ro"), listing("[]") + "garbage",
            listing("""[{"Path":"../bad","IsDir":false}]"""), listing("""[{"Path":"a.jpg"}]"""),
            listing("""[{"Path":"/absolute","IsDir":false}]"""))) {
            assertFalse(text, RemoteSnapshot.read(text.reader(), 0, index).complete)
        }
        assertThrows(IllegalArgumentException::class.java) { RemoteSnapshot.read("[]".reader(), 0, index) }
        Unit
    }
    @Test fun unicodeHiddenAndPrefixCollisionsRetainRecords() = store().use { index ->
        index.addPath(0, "CAFÉ.JPG", false)
        index.addPath(0, ".hidden.jpg", false)
        assertTrue(index.contains(0, "cafe\u0301.jpg"))
        val deleted = MediaIndexEntry(1, "$root/photo.jpg")
        index.addMedia(root.toString(), deleted)
        index.addMedia(root.toString(), MediaIndexEntry(2, "$root/photo.jpg.audio.mp3", 2))
        assertTrue(index.prefixCollision(root.toString(), deleted, 0))
        val other = MediaIndexEntry(3, "$root/other_.jpg")
        index.addMedia(root.toString(), other)
        index.addPath(0, "OTHER_.JPG.backup.png", false)
        assertTrue(index.prefixCollision(root.toString(), other, 0))
        assertFalse(MediaIndexPolicy.validRelativePath("../a"))
        assertNull(MediaIndexPolicy.relativePath(root.toString(), "${root}2/a.jpg"))
    }
    @Test fun scansMoreThanFiveHundredAndTenThousandBeyondFourMinutes() = runBlocking {
        store().use { index ->
            val fs = FakeFiles(root)
            index.batch { tick -> for (i in 0..10550) {
                val name = "$i." + if (i < 551) "jpg" else "txt"
                fs.add(name); index.addPath(0, name, false); tick()
            } }
            val media = FakeMedia(fs)
            media.onScan = { ShadowSystemClock.advanceBy(Duration.ofSeconds(1)) }
            val before = android.os.SystemClock.elapsedRealtime()
            val result = ScanEngine(index, media, fs).scan(root, snapshot(), { snapshot() }, {}, {})
            assertEquals(551, media.scanned.size)
            assertTrue(result, result.startsWith("完成"))
            assertTrue(result.contains("已遍历 10552"))
            assertTrue(android.os.SystemClock.elapsedRealtime() - before > 240000)
        }
    }
    @Test fun traversesDeepDirectoriesWithoutRecursionLimit() = runBlocking {
        store().use { index ->
            val fs = FakeFiles(root)
            var path = ""
            repeat(40) { path += if (path.isEmpty()) "d" else "/d"; fs.add(path, true); index.addPath(0, path, true) }
            fs.add("$path/a.jpg"); index.addPath(0, "$path/a.jpg", false)
            val media = FakeMedia(fs)
            ScanEngine(index, media, fs).scan(root, snapshot(), { snapshot() }, {}, {})
            assertEquals(listOf("$root/$path/a.jpg"), media.scanned)
        }
    }
    @Test fun healthyEmptyNasCleansMoreThanFiveHundredRows() = runBlocking {
        store().use { index ->
            val fs = FakeFiles(root); val media = FakeMedia(fs)
            for (i in 0..600) media.records += MediaIndexEntry(i.toLong(), "$root/$i.jpg")
            val result = ScanEngine(index, media, fs).scan(root, snapshot(), { snapshot(1) }, {}, {})
            assertEquals(601, media.scanned.size)
            assertTrue(media.records.isEmpty())
            assertTrue(result, result.contains("已清理 601"))
        }
    }
    @Test fun badFileAndDirectoryDoNotStopOtherFilesButBlockCleanup() = runBlocking {
        store().use { index ->
            val fs = FakeFiles(root)
            fs.add("bad.jpg"); fs.add("good.jpg"); fs.add("denied", true)
            fs.badReads.add(root.resolve("bad.jpg")); fs.badDirectories.add(root.resolve("denied"))
            for (name in listOf("bad.jpg", "good.jpg", "denied")) index.addPath(0, name, name == "denied")
            val media = FakeMedia(fs); media.records += MediaIndexEntry(1, "$root/deleted.jpg")
            val result = ScanEngine(index, media, fs).scan(root, snapshot(), { error("must not reconcile") }, {}, {})
            assertEquals(listOf("$root/good.jpg"), media.scanned)
            assertTrue(result.contains("部分完成")); assertTrue(result.contains("失败 2"))
            assertEquals(1, media.records.size)
        }
    }
    @Test fun incompleteInventoryStillScansReadableFilesWithoutCleanup() = runBlocking {
        store().use { index ->
            val fs = FakeFiles(root); fs.add("good.jpg")
            val media = FakeMedia(fs); media.records += MediaIndexEntry(1, "$root/missing.jpg")
            val result = ScanEngine(index, media, fs).scan(root, snapshot(complete = false), { error("must not reconcile") }, {}, {})
            assertEquals(listOf("$root/good.jpg"), media.scanned)
            assertTrue(result.startsWith("部分完成")); assertEquals(1, media.records.size)
        }
    }
    @Test fun changingInventoryProtectsDeletedRows() = runBlocking {
        store().use { index ->
            val fs = FakeFiles(root); val media = FakeMedia(fs)
            media.records += MediaIndexEntry(1, "$root/deleted.jpg")
            val result = ScanEngine(index, media, fs).scan(root, snapshot(), {
                index.addPath(1, "restored.jpg", false); snapshot(1)
            }, {}, {})
            assertTrue(result.startsWith("部分完成")); assertTrue(media.scanned.isEmpty())
        }
    }
    @Test fun cancellationStopsSubmissionAndSkipsCleanup() = runBlocking {
        store().use { index ->
            val fs = FakeFiles(root)
            for (i in 0..10) { fs.add("$i.jpg"); index.addPath(0, "$i.jpg", false) }
            val media = FakeMedia(fs)
            media.onScan = { throw CancellationException("cancel") }
            try { ScanEngine(index, media, fs).scan(root, snapshot(), { error("must not reconcile") }, {}, {}); fail() }
            catch (_: CancellationException) { }
            assertEquals(1, media.scanned.size)
        }
    }
    @Test fun mountChangeStopsBeforeCleanup() = runBlocking {
        store().use { index ->
            val fs = FakeFiles(root); val media = FakeMedia(fs)
            media.records += MediaIndexEntry(1, "$root/deleted.jpg")
            try { ScanEngine(index, media, fs).scan(root, snapshot(), { snapshot(1).copy(mountIdentity = "12:13:0:30:102:rw") }, {}, {}); fail() }
            catch (_: IllegalStateException) { }
            assertTrue(media.scanned.isEmpty())
        }
    }
    @Test fun temporaryDatabaseIsRemovedAndOrphansRecovered() {
        val directory = File(RuntimeEnvironment.getApplication().cacheDir, "scan-tests")
        directory.mkdirs(); File(directory, "scan-orphan.db").writeText("incomplete")
        ScanIndex(directory).use { assertFalse(File(directory, "scan-orphan.db").exists()) }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
    @Test fun symlinkNeverReachesMediaScanner() = runBlocking {
        store().use { index ->
            val fs = FakeFiles(root); fs.add("link.jpg"); fs.links.add(root.resolve("link.jpg"))
            index.addPath(0, "link.jpg", false)
            val media = FakeMedia(fs)
            try { ScanEngine(index, media, fs).scan(root, snapshot(), { error("must not reconcile") }, {}, {}); fail() }
            catch (_: IllegalStateException) { }
            assertTrue(media.scanned.isEmpty())
        }
    }
    @Test fun allCleanupPathsAreCheckedBeforeFirstDeletion() = runBlocking {
        store().use { index ->
            val fs = FakeFiles(root); fs.add("a.jpg"); fs.add("bad", true); fs.badAttributes.add(root.resolve("bad"))
            index.addPath(0, "a.jpg", false); index.addPath(0, "bad", true)
            val media = FakeMedia(fs)
            media.records += MediaIndexEntry(1, "$root/a.jpg")
            media.records += MediaIndexEntry(2, "$root/bad/z.jpg")
            val result = ScanEngine(index, media, fs).scan(root, snapshot(), { error("must not reconcile") }, {}, {})
            assertTrue(result.startsWith("部分完成")); assertTrue(media.scanned.isEmpty()); assertEquals(2, media.records.size)
        }
    }    @Test fun cleanupFailureStopsSubsequentSubmissions() = runBlocking {
        store().use { index ->
            val fs = FakeFiles(root); val media = FakeMedia(fs)
            media.records += MediaIndexEntry(1, "$root/a.jpg")
            media.records += MediaIndexEntry(2, "$root/b.jpg")
            media.onScan = { throw IOException("scan failed") }
            try { ScanEngine(index, media, fs).scan(root, snapshot(), { snapshot(1) }, {}, {}); fail() }
            catch (_: IOException) { }
            assertEquals(1, media.scanned.size); assertEquals(2, media.records.size)
        }
    }
    @Test fun missingPathsReportedAsEioRequireSuccessfulParentListing() = runBlocking {
        store().use { index ->
            val fs = FakeFiles(root); fs.genericMissing = true
            val media = FakeMedia(fs); media.records += MediaIndexEntry(1, "$root/deleted.jpg")
            val result = ScanEngine(index, media, fs).scan(root, snapshot(), { snapshot(1) }, {}, {})
            assertTrue(result, result.contains("已清理 1"))
        }
    }
    @Test fun noMediaPrunesEveryStagedChildEvenWhenMarkerIsLast() = runBlocking {
        store().use { index ->
            val fs = FakeFiles(root); fs.genericMissing = true
            repeat(300) { fs.add("$it.jpg"); index.addPath(0, "$it.jpg", false) }
            fs.add(".nomedia"); index.addPath(0, ".nomedia", false)
            val media = FakeMedia(fs)
            ScanEngine(index, media, fs).scan(root, snapshot(), { snapshot(1) }, {}, {})
            assertTrue(media.scanned.isEmpty())
        }
    }
}
