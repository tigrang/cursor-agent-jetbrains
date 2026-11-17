package io.github.tigrang.cursoragent.jetbrains

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

/**
 * Reads Cursor CLI's local session metadata. Session resumption is delegated to Cursor CLI.
 */
data class CursorSession(
    val id: String,
    val cwd: String,
    val title: String,
    val updatedAtMs: Long,
    val createdAtMs: Long,
) {
    val updatedAt: Instant get() = Instant.ofEpochMilli(updatedAtMs)
}

class CursorSessionStore(
    private val configRoot: Path = defaultConfigRoot(),
) {
    /** CLI session metadata is at chats/<workspace-hash>/<session-id>/meta.json. */
    fun list(): List<CursorSession> {
        val chats = configRoot.resolve("chats")
        val sessions = mutableListOf<CursorSession>()
        directories(chats).forEach { workspace ->
            directories(workspace).forEach { folder ->
                val meta = folder.resolve("meta.json")
                if (Files.isRegularFile(meta, NOFOLLOW_LINKS)) {
                    runCatching { parseSession(meta, folder) }.getOrNull()?.let(sessions::add)
                }
            }
        }
        return sessions.sortedByDescending { it.updatedAtMs }
    }

    private fun parseSession(meta: Path, folder: Path): CursorSession? {
        val metadata = Files.newInputStream(meta, NOFOLLOW_LINKS).use { it.readNBytes(MAX_METADATA_BYTES + 1) }
        if (metadata.size > MAX_METADATA_BYTES) return null
        val json = JsonParser.parseString(String(metadata, StandardCharsets.UTF_8)).asJsonObject
        if (json.optBoolean("isSubagent") == true || json.optBoolean("hasConversation") == false) return null
        val cwd = json.optString("cwd") ?: return null
        val sessionId = folder.fileName.toString()
        // Prevent malformed or tampered session metadata from creating shell commands.
        if (!SESSION_ID.matches(sessionId)) return null
        val title = json.optString("title")?.takeIf { it.isNotBlank() } ?: "New Agent"
        val created = json.optLong("createdAtMs") ?: 0L
        val updated = json.optLong("updatedAtMs") ?: created
        return CursorSession(sessionId, cwd, title, updated, created)
    }

    companion object {
        private const val MAX_METADATA_BYTES = 64 * 1024
        // Cursor CLI IDs are UUIDs today. Allow future URL-safe ids but no shell syntax.
        val SESSION_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")

        fun workspaceDirectory(path: String?): Path? {
            if (path.isNullOrBlank()) return null
            return runCatching {
                val directory = Paths.get(path)
                if (!directory.isAbsolute) return null
                directory.toRealPath().takeIf { Files.isDirectory(it) }
            }.getOrNull()
        }

        fun defaultConfigRoot(env: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home")): Path =
            env["CURSOR_CONFIG_DIR"]?.takeIf { it.isNotBlank() }?.let(Paths::get)
                ?: env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() }?.let { Paths.get(it, "cursor") }
                ?: Paths.get(home, ".cursor")

        private fun directories(root: Path): List<Path> = runCatching {
            if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return@runCatching emptyList()
            Files.newDirectoryStream(root).use { stream -> stream.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }.toList() }
        }.getOrDefault(emptyList())
    }
}

private fun JsonObject.optString(key: String): String? =
    get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }

private fun JsonObject.optLong(key: String): Long? =
    get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asLong }.getOrNull() }

private fun JsonObject.optBoolean(key: String): Boolean? =
    get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asBoolean }.getOrNull() }
