package io.github.tigrang.cursoragent.jetbrains

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField

/** Reuse the existing application-level preference so upgrades preserve the CLI path. */
internal object CursorAgentSettings {
    private const val CLI_PATH_KEY = "cursor.agent.terminal.cliPath"

    var cliPath: String
        get() = PropertiesComponent.getInstance().getValue(CLI_PATH_KEY, "")
        set(value) = PropertiesComponent.getInstance().setValue(CLI_PATH_KEY, value.trim())
}

/** Application-wide settings: Settings > Tools > Cursor Agent. */
class CursorAgentSettingsConfigurable : SearchableConfigurable {
    private var cliPathField: JTextField? = null

    override fun getId(): String = "io.github.tigrang.cursoragent.jetbrains.settings"

    override fun getDisplayName(): String = "Cursor Agent"

    override fun createComponent(): JComponent {
        val field = JTextField(40).apply {
            putClientProperty("JTextField.placeholderText", "Absolute path to the Cursor CLI")
        }
        cliPathField = field

        val inputRow = JPanel(BorderLayout(8, 0)).apply {
            add(JBLabel("CLI executable path:"), BorderLayout.WEST)
            add(field, BorderLayout.CENTER)
        }
        val help = JBLabel("Enter the full path to the Cursor CLI executable. This setting applies to all projects.")
        val top = JPanel(BorderLayout(0, 8)).apply {
            add(inputRow, BorderLayout.NORTH)
            add(help, BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(12)
            add(top, BorderLayout.NORTH)
        }
    }

    override fun isModified(): Boolean = cliPathField?.let {
        it.text.trim() != CursorAgentSettings.cliPath
    } ?: false

    override fun apply() {
        cliPathField?.let { CursorAgentSettings.cliPath = it.text }
    }

    override fun reset() {
        cliPathField?.text = CursorAgentSettings.cliPath
    }

    override fun disposeUIResources() {
        cliPathField = null
    }
}
