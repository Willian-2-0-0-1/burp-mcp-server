package net.portswigger.mcp

import burp.BurpExtender
import burp.api.montoya.BurpExtension
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EntrypointTest {
    @Test
    fun `conventional Burp entrypoint implements Montoya extension`() {
        assertTrue(BurpExtension::class.java.isAssignableFrom(BurpExtender::class.java))
    }
}
