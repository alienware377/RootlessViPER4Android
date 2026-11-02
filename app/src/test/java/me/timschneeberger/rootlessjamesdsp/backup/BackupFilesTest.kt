package me.timschneeberger.rootlessjamesdsp.backup

import me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which library files a backup takes and gives back. */
class BackupFilesTest {

    @Test
    fun `a head set with an upper case extension is backed up and restored`() {
        // The library lets it in, so the backup has to carry it.
        assertTrue(FileLibraryPreference.hasSofaExtension("HRIR_L2354.SOFA"))
        assertTrue(FileLibraryPreference.matchesType("Sofa", "HRIR_L2354.SOFA"))
        assertTrue(BackupManager.isKnownFile("Sofa/HRIR_L2354.SOFA"))
        assertTrue(BackupManager.isKnownFile("Sofa/kemar.sofa"))
    }

    @Test
    fun `other files are still left out`() {
        assertFalse(FileLibraryPreference.matchesType("Sofa", "notes.txt"))
        assertFalse(FileLibraryPreference.matchesType("Convolver", "head.sofa"))
        assertFalse(FileLibraryPreference.matchesType("Nope", "a.sofa"))
        assertFalse(BackupManager.isKnownFile("Sofa/notes.txt"))
    }
}
