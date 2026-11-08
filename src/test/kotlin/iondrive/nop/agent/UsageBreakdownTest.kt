package iondrive.nop.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant

class UsageBreakdownTest {

    @Test
    fun `native Codex counts cache as part of input and keeps historical windows separate`(@TempDir dir: Path) {
        val account = Account("sample-codex", Provider.OpenAI, dir.toString())
        val now = Instant.parse("2033-04-18T12:00:00Z")
        val rollouts = Files.createDirectories(dir.resolve(".codex/sessions/2033/04/18"))
        val file = rollouts.resolve("rollout-example.jsonl")
        Files.writeString(file, listOf(
            """{"type":"session_meta","payload":{"id":"thread-larch","cwd":"/workspace/scanner"}}""",
            """{"type":"turn_context","payload":{"model":"example-model"}}""",
            """{"type":"event_msg","timestamp":"2033-04-18T11:00:00Z","payload":{"type":"token_count","info":{"last_token_usage":{"input_tokens":640,"cached_input_tokens":420,"output_tokens":35}}}}""",
            """{"type":"event_msg","timestamp":"2033-04-17T11:00:00Z","payload":{"type":"token_count","info":{"last_token_usage":{"input_tokens":280,"cached_input_tokens":180,"output_tokens":15}}}}""",
        ).joinToString("\n"))
        Files.setLastModifiedTime(file, FileTime.from(now))
        val result = UsageBreakdown.compute(listOf(account), emptyMap(), UsageScope.Session5h, now).getValue(account.name)
        assertEquals(675L, result.currentPeriod.totalTokens)
        assertEquals(295L, result.historicalPeriod.totalTokens)
        assertEquals(1, result.currentPeriod.sessions.single().steps)
    }

    @Test
    fun `nop logs count Codex cache once and Claude cache separately across a switch`(@TempDir dir: Path) {
        val now = Instant.parse("2033-04-18T12:00:00Z")
        val codex = Account("sample-codex", Provider.OpenAI, dir.resolve("cx").toString())
        val claude = Account("sample-claude", Provider.Anthropic, dir.resolve("cl").toString())
        val logs = Files.createDirectories(dir.resolve("logs"))
        Files.writeString(logs.resolve("example.jsonl"), listOf(
            """{"type":"run_started","account":"sample-codex","provider":"openai"}""",
            """{"type":"assistant_message","at":${now.toEpochMilli()},"model":"sample","usage":{"inputTokens":640,"outputTokens":35,"cacheReadTokens":420}}""",
            """{"type":"run_started","account":"sample-claude","provider":"anthropic"}""",
            """{"type":"assistant_message","at":${now.toEpochMilli()},"model":"sample","usage":{"inputTokens":220,"outputTokens":35,"cacheReadTokens":420,"cacheCreationTokens":10}}""",
        ).joinToString("\n"))
        Files.setLastModifiedTime(logs.resolve("example.jsonl"), FileTime.from(now))
        val results = UsageBreakdown.compute(listOf(codex, claude), emptyMap(), UsageScope.Session5h, now, nopSessionsDir = logs)
        assertEquals(675L, results.getValue(codex.name).totalTokens)
        assertEquals(685L, results.getValue(claude.name).totalTokens)
    }

    @Test
    fun testFormatModelName() {
        assertEquals("GPT-6 Astra", UsageBreakdown.formatModelName("gpt-6-astra"))
        assertEquals("GPT-6.1 Sol", UsageBreakdown.formatModelName("gpt-6.1-sol"))
        assertEquals("Claude Opus 5.5", UsageBreakdown.formatModelName("claude-opus-5-5"))
        assertEquals("Gemini 3.8 Flash", UsageBreakdown.formatModelName("gemini-3.8-flash"))
        assertEquals("custom-model", UsageBreakdown.formatModelName("custom-model"))
    }

    @Test
    fun testFormatTokens() {
        assertEquals("500", UsageBreakdown.formatTokens(500L))
        assertEquals("1.5K", UsageBreakdown.formatTokens(1500L))
        assertEquals("2.4M", UsageBreakdown.formatTokens(2_400_000L))
        assertEquals("3.1B", UsageBreakdown.formatTokens(3_100_000_000L))
    }

    @Test
    fun testWindowStartCalculation() {
        val now = Instant.parse("2026-10-05T00:00:00Z")
        val account = Account(
            name = "claude-work",
            provider = Provider.Anthropic,
            home = "/tmp/fake",
        )

        // Weekly window resetting on Friday morning (Oct 9, 2026) -> started Oct 2, 2026
        val weeklyResets = Instant.parse("2026-10-09T00:00:00Z")
        val reading = UsageReading(
            session = null,
            weekly = UsageWindow(percent = 45.0, resetsAt = weeklyResets, length = Duration.ofDays(7)),
            asOf = now,
        )

        val start = UsageBreakdown.windowStartFor(account, reading, UsageScope.Weekly7d, now)
        assertEquals(Instant.parse("2026-10-02T00:00:00Z"), start)

        // If reset has already passed (e.g. rolled over 1 hour ago)
        val passedResets = Instant.parse("2026-10-04T23:00:00Z")
        val rolledReading = UsageReading(
            session = null,
            weekly = UsageWindow(percent = 0.0, resetsAt = passedResets, length = Duration.ofDays(7)),
            asOf = now,
        )
        val rolledStart = UsageBreakdown.windowStartFor(account, rolledReading, UsageScope.Weekly7d, now)
        assertEquals(passedResets, rolledStart)

        // Without reset info -> defaults to now - duration
        val emptyReading = UsageReading(session = null, weekly = null, asOf = now)
        val fallbackStart = UsageBreakdown.windowStartFor(account, emptyReading, UsageScope.Weekly7d, now)
        assertEquals(now.minus(Duration.ofDays(7)), fallbackStart)
    }

    @Test
    fun testComputeEmptyAccount(@TempDir tempDir: Path) {
        val account = Account(
            name = "test-acct",
            provider = Provider.Anthropic,
            home = tempDir.toString(),
        )
        val result = UsageBreakdown.compute(
            accounts = listOf(account),
            readings = emptyMap(),
            scope = UsageScope.Session5h,
        )

        assertTrue(result.containsKey("test-acct"))
        val breakdown = result["test-acct"]!!
        assertEquals("test-acct", breakdown.account.name)
        assertEquals(0L, breakdown.totalTokens)
        assertEquals(0, breakdown.totalSteps)
        assertTrue(breakdown.currentPeriod.models.isEmpty())
        assertTrue(breakdown.currentPeriod.sessions.isEmpty())
        assertTrue(breakdown.historicalPeriod.models.isEmpty())
        assertTrue(breakdown.historicalPeriod.sessions.isEmpty())
    }
}
