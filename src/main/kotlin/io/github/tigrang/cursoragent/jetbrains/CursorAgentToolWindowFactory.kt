package io.github.tigrang.cursoragent.jetbrains

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

class CursorAgentToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CursorAgentPanel(project, toolWindow)
        Disposer.register(toolWindow.disposable, panel)
    }
}
