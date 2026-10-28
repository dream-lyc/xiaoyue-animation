package cn.childtv
import org.junit.Assert.*
import org.junit.Test
class AccountingTest {
    @Test fun `fractional playback survives ten short pauses`() {
        var seconds = 0L; var remainder = 0L
        repeat(10) { val plan = WatchAccounting.split(seconds, remainder, 2700, 150); seconds += plan.chargedSeconds; remainder = plan.remainderMillis }
        assertEquals(1, seconds); assertEquals(500, remainder)
    }
    @Test fun `crossing quota splits actual milliseconds at threshold`() {
        val plan = WatchAccounting.split(59, 500, 60, 5000)
        assertEquals(500, plan.normalMillis); assertEquals(4500, plan.graceMillis)
    }
    @Test fun `backup integrity rejects tampered payload`() {
        val payload = "{\"version\":1}"
        assertTrue(BackupIntegrity.valid(payload, BackupIntegrity.digest(payload)))
        assertFalse(BackupIntegrity.valid(payload + " ", BackupIntegrity.digest(payload)))
    }
}
