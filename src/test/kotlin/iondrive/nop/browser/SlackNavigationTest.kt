package iondrive.nop.browser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SlackNavigationTest {
    @Test
    fun `layout scripts only run on the Slack web app`() {
        assertTrue(SlackNavigation.isAppUrl("https://app.slack.com/client/TDEMO"))
        assertTrue(SlackNavigation.isAppUrl("https://app.slack.com/workspace-signin"))
        assertFalse(SlackNavigation.isAppUrl("https://accounts.example.org/oauth"))
        assertFalse(SlackNavigation.isAppUrl("https://app.slack.com.example.org/client/TDEMO"))
        assertFalse(SlackNavigation.isAppUrl("https://app.slack.com@example.org/"))
        assertFalse(SlackNavigation.isAppUrl("http://app.slack.com/client/TDEMO"))
        assertFalse(SlackNavigation.isAppUrl("invalid url"))
    }

    @Test
    fun `outbound links leave the client but Slack and authentication stay embedded`() {
        val client = "https://app.slack.com/client/TDEMO/CGENERAL"
        assertTrue(SlackNavigation.externalLink(client, "https://example.org/notes"))
        assertFalse(SlackNavigation.externalLink(client, "https://demo.slack.com/archives/CGENERAL"))
        assertFalse(SlackNavigation.externalLink(client, "https://slack.com/signin"))
        assertFalse(SlackNavigation.externalLink("https://slack.com/signin", "https://accounts.example.org/oauth"))
        assertFalse(SlackNavigation.externalLink(client, "file:///etc/passwd"))
        assertFalse(SlackNavigation.externalLink(client, "javascript:alert(1)"))
        assertTrue(SlackNavigation.externalLink(client, "https://slack.com.example.org/"))
    }

    @Test
    fun `desktop links return to the web workspace and channel`() {
        assertEquals("https://app.slack.com/client/TDEMO/CGENERAL",
            SlackNavigation.webDeepLink("slack://channel?team=TDEMO&id=CGENERAL"))
        assertEquals("https://app.slack.com/client/TDEMO", SlackNavigation.webDeepLink("slack://open?team=TDEMO"))
        assertEquals("https://app.slack.com/client/TDEMO", SlackNavigation.webDeepLink("slack://team?team=TDEMO"))
        assertNull(SlackNavigation.webDeepLink("https://example.org/?team=TDEMO"))
        assertNull(SlackNavigation.webDeepLink("slack://open?team=../other"))
        assertNull(SlackNavigation.webDeepLink("slack://open?team=TDEMO%2Fother"))
        assertNull(SlackNavigation.webDeepLink("slack://open"))
    }

    @Test
    fun `2FA desktop handoffs return to the web workspace without forwarding the credential`() {
        val destination = "https://app.slack.com/client/TWORKSHOP"
        assertEquals(destination, SlackNavigation.webDeepLink("slack://TWORKSHOP/magic-login/invented-one-time-code?id=example"))
        // Chromium treats the workspace as a URL host and normalizes its case.
        assertEquals(destination, SlackNavigation.webDeepLink("slack://tworkshop/magic-login/invented-one-time-code"))
        assertEquals(destination, SlackNavigation.webDeepLink("SLACK://TWORKSHOP/magic-login/another-invented-code?token=sample"))
        assertEquals(destination, SlackNavigation.webDeepLink("slack://TWORKSHOP/"))
        assertEquals(destination, SlackNavigation.webDeepLink("slack://TWORKSHOP/open"))
        assertNull(SlackNavigation.webDeepLink("slack://TWORKSHOP/magic-login/"))
        assertNull(SlackNavigation.webDeepLink("slack://TWORKSHOP/not-a-supported-route/secret"))
    }

    @Test
    fun `desktop handoffs cannot choose a web host or include URL credentials`() {
        assertNull(SlackNavigation.webDeepLink("slack://TWORKSHOP.example.org/magic-login/invented-code"))
        assertNull(SlackNavigation.webDeepLink("slack://TWORKSHOP@example.org/magic-login/invented-code"))
        assertNull(SlackNavigation.webDeepLink("slack://person@TWORKSHOP/magic-login/invented-code"))
        assertNull(SlackNavigation.webDeepLink("slack://TWORKSHOP:1234/magic-login/invented-code"))
        assertNull(SlackNavigation.webDeepLink("slack://open?team=TWORKSHOP%2Fother"))
        assertNull(SlackNavigation.webDeepLink("slack://open?team=" + "T".repeat(60)))
    }

    @Test
    fun `only valid web addresses can navigate or open externally`() {
        assertTrue(SlackNavigation.isWebUrl("https://app.slack.com/client/TDEMO"))
        assertTrue(SlackNavigation.isWebUrl("http://localhost:8080/"))
        assertFalse(SlackNavigation.isWebUrl("https://"))
        assertFalse(SlackNavigation.isWebUrl("https://user:password@example.org"))
        assertFalse(SlackNavigation.isWebUrl("not a url"))
        assertFalse(SlackNavigation.isWebUrl("data:text/html,hello"))
    }
}
