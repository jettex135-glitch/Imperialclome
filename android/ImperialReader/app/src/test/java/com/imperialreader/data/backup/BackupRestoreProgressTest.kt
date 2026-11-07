package com.imperialreader.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupRestoreProgressTest {
    @Test
    fun reportsPercentageFromActualCompletedWork() {
        assertEquals(40, BackupRestoreProgress("Restoring entries", 2, 5).percentage)
        assertEquals(100, BackupRestoreProgress("Restoring entries", 8, 5).percentage)
    }

    @Test
    fun leavesUnknownOrEmptyWorkIndeterminate() {
        assertNull(BackupRestoreProgress("Reading backup").percentage)
        assertNull(BackupRestoreProgress("Preparing restoration", 0, 0).percentage)
    }
}
