package io.github.tigrang.cursoragent.jetbrains

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

object CursorCli {
    fun executable(configured: String): Path? {
        val path = try {
            Path.of(configured)
        } catch (_: InvalidPathException) {
            return null
        }
        val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        return path.takeIf { it.isAbsolute && Files.isRegularFile(it) && (isWindows || Files.isExecutable(it)) }
    }

    fun command(executable: Path, sessionId: String? = null, windows: Boolean = false): String {
        require(sessionId == null || CursorSessionStore.SESSION_ID.matches(sessionId)) { "Invalid Cursor session ID" }
        val args = if (sessionId != null) {
            " --resume=${if (windows) quotePowerShell(sessionId) else quotePosix(sessionId)}"
        } else ""
        return if (windows) "& ${quotePowerShell(executable.toString())}$args" else "${quotePosix(executable.toString())}$args"
    }

    internal fun quotePosix(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    internal fun quotePowerShell(value: String): String = "'" + value.replace("'", "''") + "'"
}
