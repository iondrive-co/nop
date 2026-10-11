package iondrive.nop.ui

import iondrive.nop.agent.Account
import iondrive.nop.agent.DEFAULT_CHOICE
import iondrive.nop.agent.Provider
import iondrive.nop.agent.UsageReading
import iondrive.nop.agent.UsageWindow
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HandoverPanelTest {

    private fun account(name: String, provider: Provider = Provider.Anthropic, model: String? = null) =
        Account(name, provider, "/tmp/$name", model = model)

    @Test
    fun `modelOptionsFor includes default and discovered models`() {
        val acct = account("claude-test")
        val options = modelOptionsFor(acct, listOf("claude-3-5-sonnet", "claude-3-opus"))

        assertEquals(
            listOf(DEFAULT_CHOICE, "claude-3-5-sonnet", "claude-3-opus"),
            options,
        )
    }

    @Test
    fun `modelOptionsFor includes account model even when not in discovered list`() {
        val acct = account("claude-test", model = "claude-custom-1")
        val options = modelOptionsFor(acct, listOf("claude-3-5-sonnet"))

        assertEquals(
            listOf(DEFAULT_CHOICE, "claude-custom-1", "claude-3-5-sonnet"),
            options,
        )
    }

    @Test
    fun `modelOptionsFor deduplicates account model if present in discovered list`() {
        val acct = account("claude-test", model = "claude-3-5-sonnet")
        val options = modelOptionsFor(acct, listOf("claude-3-5-sonnet", "claude-3-opus"))

        assertEquals(
            listOf(DEFAULT_CHOICE, "claude-3-5-sonnet", "claude-3-opus"),
            options,
        )
    }

    @Test
    fun `modelOptionsFor handles empty discovered models`() {
        val acct = account("agy-test", provider = Provider.Antigravity)
        val options = modelOptionsFor(acct, emptyList())

        assertEquals(
            listOf(DEFAULT_CHOICE),
            options,
        )
    }

    @Test
    fun `usageDescription formats percentage and reset ETA when available`() {
        val reading = UsageReading(
            session = UsageWindow(72.0, Instant.now().plus(Duration.ofMinutes(108))),
            weekly = null,
            asOf = Instant.now(),
        )
        val desc = usageDescription(reading)
        assertTrue(desc.startsWith("72% used · resets in "), "expected ETA in '$desc'")
    }

    @Test
    fun `usageDescription formats percentage when no ETA available`() {
        val reading = UsageReading(
            session = UsageWindow(35.0, null),
            weekly = null,
            asOf = Instant.now(),
        )
        assertEquals("35% used", usageDescription(reading))
    }

    @Test
    fun `usageDescription reports unavailable state`() {
        val reading = UsageReading.unavailable("rate-limited")
        assertEquals("rate-limited", usageDescription(reading))
    }

    @Test
    fun `usageDescription returns empty string for null reading`() {
        assertEquals("", usageDescription(null))
    }

    @Test
    fun `handoverAccountsFor includes current account alongside other providers`() {
        val current = account("claude-alpha")
        val other = account("codex", provider = Provider.OpenAI)
        val result = handoverAccountsFor(current, listOf(current, other))

        assertEquals(listOf("claude-alpha", "codex"), result.map { it.name })
    }

    @Test
    fun `handoverAccountsFor includes accounts of the same provider`() {
        val current = account("claude-alpha")
        val sibling = account("claude-bravo")
        val other = account("codex", provider = Provider.OpenAI)
        val result = handoverAccountsFor(current, listOf(current, sibling, other))

        assertEquals(listOf("claude-alpha", "claude-bravo", "codex"), result.map { it.name })
    }

    @Test
    fun `handoverAccountsFor preserves current account active model`() {
        val current = account("claude-alpha", model = "claude-haiku-4-5")
        val configured = account("claude-alpha", model = "claude-3-5-sonnet")
        val other = account("codex", provider = Provider.OpenAI)
        val result = handoverAccountsFor(current, listOf(configured, other))

        assertEquals("claude-haiku-4-5", result.first { it.name == "claude-alpha" }.model)
    }

    @Test
    fun `handoverAccountsFor includes current account when missing from accounts list`() {
        val current = account("claude-alpha")
        val other = account("codex", provider = Provider.OpenAI)
        val result = handoverAccountsFor(current, listOf(other))

        assertEquals(listOf("claude-alpha", "codex"), result.map { it.name })
    }
}
