# Cursor Agent Terminal for JetBrains

Use the **Cursor CLI directly inside PhpStorm or IntelliJ IDEA** with a dedicated tool window, native IDE tabs, and a searchable history of local conversations.

> This project was generated entirely by AI.
>
> Not affiliated with or endorsed by Cursor or JetBrains.

## Features

- **Interactive Cursor Agent terminal** — runs the standard `agent` command in an embedded terminal, preserving its normal CLI experience, approval prompts, slash commands, and available skills and hooks.
- **Native JetBrains interface** — dedicated right-side **Cursor Agent** tool window, standard closeable terminal tabs, and a compact **New Agent** action.
- **Conversation history** — browse local Cursor CLI sessions for the current project and search by title or path.
- **Resume an earlier conversation** — select a session and resume it via Cursor's `agent --resume=<session-id>` command.
- **Configurable CLI executable** — choose the Cursor CLI path under **Settings / Preferences → Tools → Cursor Agent**.
- **Theme-aware UI** — uses native IntelliJ Platform components for light and dark themes.

This integration intentionally uses the **interactive Cursor CLI**, **not ACP**.

## Requirements

| Component | Requirement |
| --- | --- |
| IDE | PhpStorm or IntelliJ IDEA **2025.2+** (platform build 252+) |
| JetBrains terminal | Bundled **Terminal** plugin enabled |
| Cursor | Cursor CLI installed and authenticated |
| Build tools (developers only) | **JDK 21** and **Gradle 9.9.1** |
| Operating systems | Primarily macOS; Linux expected to work; Windows not yet verified |

Check your CLI installation:

```bash
agent --version
agent login
```

See the [official Cursor CLI installation instructions](https://cursor.com/docs/cli/installation).

## Download and install

### From GitHub Releases

Download the **plugin ZIP** from this repository's **Releases** page. You can also download the `cursor-agent-jetbrains-plugin` artifact from the corresponding **Actions → Build and release plugin** run and extract the plugin ZIP.

In **PhpStorm** or **IntelliJ IDEA**:

1. Open **Settings / Preferences → Plugins**.
2. Select the gear icon → **Install Plugin from Disk…**.
3. Select the **plugin distribution ZIP** (for example `cursor-agent-jetbrains-0.0.1.zip`). Do not select the source archive or the outer GitHub Actions artifact ZIP.
4. Restart the IDE if requested.
5. Open **View → Tool Windows → Cursor Agent**.
6. Use **New Agent** (`+`) to start a conversation, or click the **History** icon in the tool-window header to browse and resume sessions.

Before opening an agent tab, set the absolute path to the Cursor CLI under **Settings / Preferences → Tools → Cursor Agent**. This setting applies to all projects. Find the path in your normal terminal with `command -v agent`.

## Build from source

Install JDK 21 and Gradle 9.9.1, then run from the project root:

```bash
gradle clean test buildPlugin
```

On macOS with Homebrew, you can install Java with `brew install openjdk@21` and, if necessary, select it before building:

```bash
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

The **installable ZIP** will be in `build/distributions/`. To launch an IntelliJ IDEA sandbox with the plugin loaded, run:

```bash
gradle runIde
```

The repository currently does not include a Gradle Wrapper; GitHub CI provisions the pinned Gradle version automatically.

## Automated builds and releases

When you push a `v*` tag, GitHub Actions runs `clean test buildPlugin` and uploads the **plugin distribution ZIP** and `SHA256SUMS.txt` as a workflow artifact. It then publishes a **GitHub Release** with both files. The tag must match `version` in `build.gradle.kts`, with a `v` prefix. For version `0.0.1`:

```bash
git tag v0.0.1
git push origin v0.0.1
```

The publishing job runs only after a successful build, uses release-only write permissions, and does **not** upload to the JetBrains Marketplace. No marketplace credentials are required.

## Limitations

- **Local session history:** The history list reads Cursor CLI's local session metadata (`meta.json`). Use the Open chat icon to resume a conversation in the embedded terminal.
- **Not all chat history:** ACP and cloud-only conversations may not appear in the local list.
- **Terminal compatibility:** The embedded terminal currently uses a legacy JetBrains terminal API, which may need changes in newer IDE versions.
- **Windows:** CLI shell integration is not yet verified.

## References

- [JetBrains tool-window documentation](https://plugins.jetbrains.com/docs/intellij/tool-windows.html)
- [Cursor CLI documentation](https://cursor.com/docs/cli/overview)
