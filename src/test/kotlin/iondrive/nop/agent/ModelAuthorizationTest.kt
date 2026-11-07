package iondrive.nop.agent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ModelAuthorizationTest {
    private val selected = ConversationModel("model-orchid", "high")
    private val other = ConversationModel("model-fern", "medium")

    @Test
    fun `vendor observations cannot replace an explicit launch choice`() {
        val authorization = ModelAuthorization(selected)
        assertFalse(authorization.observe(other, 20_000, 0))
        assertEquals(selected, authorization.choice)
        assertEquals(other, authorization.change?.observed)
        authorization.restored(selected)
        assertTrue(authorization.change!!.restored)
        assertFalse(authorization.observe(selected, 21_000, 0))
        assertNotNull(authorization.change, "the correction remains visible until dismissed")
    }

    @Test
    fun `a physical model selection authorizes its result but not an old or future selection`() {
        val authorization = ModelAuthorization(selected)
        assertTrue(authorization.observe(other, 20_000, 19_900))
        assertEquals(other, authorization.choice)
        assertNull(authorization.change)
        assertFalse(authorization.observe(selected, 20_100, 19_900), "one physical selection cannot authorize a second model")
        assertEquals(other, authorization.choice)
        assertFalse(authorization.observe(selected, 30_000, 19_900))
        assertEquals(other, authorization.choice)
        assertFalse(authorization.observe(selected, 30_000, 30_100))
        assertEquals(other, authorization.choice)
    }

    @Test
    fun `the CLI default and its effort are learned without overriding a named model`() {
        val authorization = ModelAuthorization(null)
        assertTrue(authorization.observe(selected, 20_000, 0))
        assertEquals(selected, authorization.choice)
        val named = ModelAuthorization(selected.copy(reasoning = null))
        assertFalse(named.observe(other, 20_000, 0))
        assertTrue(named.observe(selected, 21_000, 0))
        assertEquals(selected, named.choice)
    }

    @Test
    fun `an unexpected effort change is corrected just like a model change`() {
        val authorization = ModelAuthorization(selected)
        val lower = selected.copy(reasoning = "medium")
        authorization.observe(lower, 20_000, 0)
        assertEquals(lower, authorization.change?.observed)
        assertEquals(selected, authorization.choice)
    }
}
