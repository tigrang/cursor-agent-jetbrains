package io.github.tigrang.cursoragent.jetbrains

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Paths

class CursorCliTest {
    @Test fun `quotes shell path and resume id`() {
        val cmd = CursorCli.command(Paths.get("/Users/test/O'Brien/agent"), "abc-123")
        assertEquals("'/Users/test/O'\\''Brien/agent' --resume='abc-123'", cmd)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects injected resume id`() {
        CursorCli.command(Paths.get("/usr/local/bin/agent"), "id;rm -rf ~")
    }

    @Test fun `starts a fresh session without flags`() {
        assertEquals("'/usr/local/bin/agent'", CursorCli.command(Paths.get("/usr/local/bin/agent")))
    }
}
