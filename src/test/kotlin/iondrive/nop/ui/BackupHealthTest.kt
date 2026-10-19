package iondrive.nop.ui

import iondrive.nop.agent.Backup
import iondrive.nop.agent.NetworkShare
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** When the backup mark beside the gear warns, and what it says. */
class BackupHealthTest {

    private val dir = "/media/backup/agents"
    private val now = 1_800_000_000_000L
    private fun minutesAgo(minutes: Long) = now - minutes * 60_000

    @Test
    fun `a recent backup is fine`() {
        val health = backupHealth(dir, Backup.Status(lastSuccessAt = minutesAgo(4)), now)
        assertFalse(health.failing)
        assertTrue(health.line.contains("Backed up to $dir 4 minutes ago"), health.line)
    }

    @Test
    fun `no run yet is fine until the first few have had their chance`() {
        assertFalse(backupHealth(dir, Backup.Status(since = minutesAgo(1)), now).failing)
        val late = backupHealth(dir, Backup.Status(since = minutesAgo(45)), now)
        assertTrue(late.failing)
        assertTrue(late.line.contains("Nothing has been backed up yet"), late.line)
    }

    @Test
    fun `a failed run warns and says when the last good one was`() {
        val health = backupHealth(dir, Backup.Status(lastSuccessAt = minutesAgo(12), lastError = "No space left on device"), now)
        assertTrue(health.failing)
        assertTrue(health.line.contains("No space left on device"), health.line)
        assertTrue(health.line.contains("12 minutes ago"), health.line)
    }

    @Test
    fun `a share waiting on a password warns`() {
        val request = NetworkShare.PasswordRequest("smb://server/share", true, "", false, "", refused = false)
        val health = backupHealth("smb://server/share/agents", Backup.Status(lastSuccessAt = minutesAgo(2), password = request), now)
        assertTrue(health.failing)
        assertTrue(health.line.contains("waiting for the password to smb://server/share"), health.line)
    }

    @Test
    fun `a run that started again is not a failure until it is late`() {
        val retrying = Backup.Status(running = true, lastSuccessAt = minutesAgo(12), lastError = "Connection reset")
        assertFalse(backupHealth(dir, retrying, now).failing)
    }

    @Test
    fun `a backup that stopped running warns even with no error`() {
        val stopped = backupHealth(dir, Backup.Status(lastSuccessAt = minutesAgo(50)), now)
        assertTrue(stopped.failing)
        assertTrue(stopped.line.contains("50 minutes ago"), stopped.line)

        val hung = backupHealth(dir, Backup.Status(running = true, lastSuccessAt = minutesAgo(50)), now)
        assertTrue(hung.failing)
        assertTrue(hung.line.contains("still running"), hung.line)
    }
}
