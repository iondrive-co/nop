package iondrive.nop.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What a share's request for a password, and the mount it held up, come to. */
class GioMountTest {

    private val ask = GioMount.Ask(user = "pat", domain = "HOMENET", needsUser = true, needsDomain = true)

    @Test
    fun `reads which fields a request wants from its flags`() {
        // NEED_PASSWORD | NEED_USERNAME | NEED_DOMAIN | SAVING_SUPPORTED
        assertEquals(ask, GioMount.ask(0b1111, "pat", "HOMENET"))
        assertEquals(GioMount.Ask("", "", needsUser = false, needsDomain = false), GioMount.ask(0b0001, null, null))
    }

    @Test
    fun `a request nop had no answer for means the share needs a password`() {
        assertEquals(
            GioMount.Outcome.NeedsPassword(ask, refused = false),
            GioMount.outcome(mounted = false, message = "Password dialog cancelled", ask = ask, asked = 1, hadCredentials = false),
        )
    }

    @Test
    fun `a second request after answering the first means the answer was refused`() {
        assertEquals(
            GioMount.Outcome.NeedsPassword(ask, refused = true),
            GioMount.outcome(mounted = false, message = "Password dialog cancelled", ask = ask, asked = 2, hadCredentials = true),
        )
    }

    @Test
    fun `a failure after an answered request is the share's own`() {
        assertEquals(
            GioMount.Outcome.Failed("Host not found"),
            GioMount.outcome(mounted = false, message = "Host not found", ask = ask, asked = 1, hadCredentials = true),
        )
        assertEquals(
            GioMount.Outcome.Failed("the share gave no reason"),
            GioMount.outcome(mounted = false, message = " ", ask = null, asked = 0, hadCredentials = false),
        )
    }

    @Test
    fun `mounted is mounted, however it was asked`() {
        assertEquals(GioMount.Outcome.Mounted, GioMount.outcome(mounted = true, message = null, ask = ask, asked = 1, hadCredentials = true))
    }
}
