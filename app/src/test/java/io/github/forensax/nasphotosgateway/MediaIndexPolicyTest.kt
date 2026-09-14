package io.github.forensax.nasphotosgateway

import org.junit.Assert.*
import org.junit.Test

class MediaIndexPolicyTest {
    private val root = "/storage/emulated/0/DCIM/NAS_test"
    private fun snapshot(vararg paths: String) = RemoteSnapshot("10:11:0:29:101", paths.map(MediaIndexPolicy::key).toSet())
    private fun listing(json: String, end: String = "10:11:0:29:101") =
        "GATEWAY_SNAPSHOT 10:11:0:29:101\n$json\nGATEWAY_SNAPSHOT_END $end"
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Unsafe inventory was accepted") } catch (_: IllegalArgumentException) { }
        catch (_: org.json.JSONException) { }
    }

    @Test fun emptyHealthyNasCanCleanDeletedRows() {
        val empty = RemoteSnapshot.parse(listing("[]"))
        val row = MediaIndexEntry(44, "$root/deleted.png")
        assertEquals(listOf(row), MediaIndexPolicy.confirmedMissing(root, listOf(row), empty, empty))
    }

    @Test fun existingFilesHiddenEntriesAndUnicodeVariantsAreRetained() {
        val existing = snapshot("子目录/照片.png", "CAFÉ.JPG", ".hidden.jpg", ".nomedia")
        val rows = listOf("子目录/照片.png", "cafe\u0301.jpg", ".hidden.jpg").mapIndexed { index, path ->
            MediaIndexEntry(index.toLong(), "$root/$path")
        }
        assertTrue(MediaIndexPolicy.confirmedMissing(root, rows, existing, existing).isEmpty())
    }

    @Test fun cleanupNeverCrossesMountBoundaryOrFollowsTraversal() {
        val rows = listOf("${root}2/file.jpg", "$root/../Camera/photo.jpg", "$root/sub/../../photo.jpg",
            "$root//photo.jpg", "$root/sub\\photo.jpg", "$root/photo\n.jpg", "$root", "/DCIM/NAS_test/a.jpg")
            .mapIndexed { index, path -> MediaIndexEntry(index.toLong(), path) }
        assertTrue(MediaIndexPolicy.missing(root, rows, snapshot()).isEmpty())
        assertNull(MediaIndexPolicy.relativePath("/storage/emulated/0", "/storage/emulated/0/photo.jpg"))
    }

    @Test fun changedMountOrChangingNasBlocksCleanup() {
        val rows = listOf(MediaIndexEntry(44, "$root/deleted.png"))
        rejects { MediaIndexPolicy.confirmedMissing(root, rows, snapshot(), snapshot().copy(mountIdentity = "12:13:0:30:102")) }
        rejects { MediaIndexPolicy.confirmedMissing(root, rows, snapshot("deleted.png"), snapshot()) }
        rejects { MediaIndexPolicy.confirmedMissing(root, rows, snapshot(), snapshot("new.png")) }
    }

    @Test fun restoredFileBetweenSnapshotsIsProtected() {
        val rows = listOf(MediaIndexEntry(44, "$root/photo.jpg"))
        rejects { MediaIndexPolicy.confirmedMissing(root, rows, snapshot(), snapshot("photo.jpg")) }
    }

    @Test fun incompleteOrFailedListingsAreRejected() {
        listOf("[]", "GATEWAY_SNAPSHOT 10:11:0:29:101\n[]", listing("["), listing("[]", "12:13:0:30:102"),
            listing("[]\nconnection failed"), listing("[]\n[]"), listing("{\"error\":\"offline\"}"))
            .forEach { output -> rejects { RemoteSnapshot.parse(output) } }
    }

    @Test fun malformedAndDeepRemotePathsBlockCleanup() {
        listOf("../escape.jpg", "/absolute.jpg", "sub//a.jpg", (1..33).joinToString("/") { "sub" }).forEach { path ->
            rejects { RemoteSnapshot.parse(listing("[{\"Path\":\"$path\",\"IsDir\":false}]")) }
        }
        rejects { RemoteSnapshot.parse(listing("[{\"Path\":\"photo.jpg\"}]")) }
    }

    @Test fun completeListingAcceptsSpacesChineseAndDirectories() {
        val parsed = RemoteSnapshot.parse(listing("[{\"Path\":\"旅行\",\"IsDir\":true},{\"Path\":\"旅行/照片 1.JPG\",\"IsDir\":false}]"))
        assertEquals(setOf("旅行", "旅行/照片 1.jpg"), parsed.paths)
    }

    @Test fun oversizedListingAndMassCleanupAreRejected() {
        val json = (0..RemoteSnapshot.MAX_ENTRIES).joinToString(",", "[", "]") { "{\"Path\":\"$it.jpg\",\"IsDir\":false}" }
        rejects { RemoteSnapshot.parse(listing(json)) }
        rejects { RemoteSnapshot.parse("x".repeat(RemoteSnapshot.MAX_OUTPUT + 1)) }
        val rows = (0..MediaIndexPolicy.MAX_CLEANUP).map { MediaIndexEntry(it.toLong(), "$root/$it.jpg") }
        rejects { MediaIndexPolicy.confirmedMissing(root, rows, snapshot(), snapshot()) }
    }

    @Test fun missingFileScanCannotRemoveSamePrefixMediaOrNonMediaRows() {
        val deleted = MediaIndexEntry(44, "$root/photo.jpg")
        val backup = MediaIndexEntry(45, "$root/photo.jpg.backup.png")
        val audio = MediaIndexEntry(46, "$root/photo.jpg.audio.mp3", 2)
        val document = MediaIndexEntry(47, "$root/photo.jpg.notes.txt", 0)
        assertTrue(MediaIndexPolicy.hasScanPrefixCollision(root, deleted, listOf(deleted, backup), snapshot()))
        assertTrue(MediaIndexPolicy.hasScanPrefixCollision(root, deleted, listOf(deleted, audio, document), snapshot()))
        assertTrue(MediaIndexPolicy.hasScanPrefixCollision(root, deleted, listOf(deleted), snapshot("PHOTO.JPG.backup.png")))
        assertFalse(MediaIndexPolicy.hasScanPrefixCollision(root, deleted, listOf(deleted), snapshot("photo2.jpg")))
        assertTrue(MediaIndexPolicy.missing(root, listOf(audio, document), snapshot()).isEmpty())
    }
}
