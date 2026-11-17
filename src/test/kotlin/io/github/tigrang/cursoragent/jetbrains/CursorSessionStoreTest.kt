package io.github.tigrang.cursoragent.jetbrains

import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class CursorSessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `finds sessions across buckets without reading databases`() {
        val config = tmp.newFolder("config").toPath()
        val session = config.resolve("chats/hash-1/abc-123")
        Files.createDirectories(session)
        Files.writeString(session.resolve("meta.json"), """{
            "schemaVersion":1,"createdAtMs":1000,"updatedAtMs":2000,
            "hasConversation":true,"cwd":"/tmp/work","title":"Implement widget"
        }""")
        val entries = CursorSessionStore(config).list()
        assertEquals(1, entries.size)
        assertEquals("abc-123", entries[0].id)
        assertEquals("Implement widget", entries[0].title)
        assertEquals(2000, entries[0].updatedAtMs)
    }

    @Test fun `filters subagents and empty conversations`() {
        val config = tmp.newFolder("config").toPath()
        for ((name, value) in listOf("a" to "\"isSubagent\":true", "b" to "\"hasConversation\":false")) {
            val folder = config.resolve("chats/hash-1/$name")
            Files.createDirectories(folder)
            Files.writeString(folder.resolve("meta.json"), """{"cwd":"/tmp/work",$value}""")
        }
        assertTrue(CursorSessionStore(config).list().isEmpty())
    }

    @Test fun `history skips linked metadata and directories`() {
        val config = tmp.newFolder("config").toPath()
        val realSession = Files.createDirectories(config.resolve("chats/workspace/real"))
        val metadata = Files.writeString(realSession.resolve("meta.json"), """{"cwd":"/tmp/work"}""")
        val linkedMetadataSession = Files.createDirectory(realSession.resolveSibling("linked-metadata"))
        assumeNoException(runCatching {
            Files.createSymbolicLink(linkedMetadataSession.resolve("meta.json"), metadata)
            Files.createSymbolicLink(realSession.resolveSibling("linked-session"), realSession)
            Files.createSymbolicLink(realSession.parent.resolveSibling("linked-workspace"), realSession.parent)
        }.exceptionOrNull())

        assertEquals(listOf("real"), CursorSessionStore(config).list().map { it.id })
    }

    @Test fun `workspace lookup resolves directory aliases and requires an absolute path`() {
        val project = tmp.newFolder("project").toPath()
        val alias = project.resolveSibling("alias")
        assumeNoException(runCatching { Files.createSymbolicLink(alias, project) }.exceptionOrNull())

        assertEquals(project.toRealPath(), CursorSessionStore.workspaceDirectory(alias.toString()))
        assertNull(CursorSessionStore.workspaceDirectory("project"))
        assertNull(CursorSessionStore.workspaceDirectory(project.resolve("missing").toString()))
    }
}
