package com.darkyen.wemi.intellij.execution

import com.darkyen.wemi.intellij.WemiProjectSystemId
import com.darkyen.wemi.intellij.execution.configurationEditor.WemiTaskSettingsControl
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemBeforeRunTask
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemBeforeRunTaskProvider
import com.intellij.openapi.externalSystem.util.ExternalSystemBundle
import com.intellij.openapi.externalSystem.util.PaintAwarePanel
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Key
import icons.WemiIcons
import java.awt.GridBagLayout
import javax.swing.JComponent

/**
 * Provides ability to run Wemi task before any run configuration.
 *
 * The task uses the same settings controller as the Wemi Run Configuration.
 */
class WemiBeforeRunTaskProvider(private val project: Project) : ExternalSystemBeforeRunTaskProvider(WemiProjectSystemId, project, ID) {

    /** Icon used in the "Before launch" window in "Run/Debug Configurations" */
    override fun getIcon() = WemiIcons.WEMI

    /**
     * NOTE: We allow to create BeforeRunTask even for Wemi configurations. This is intentional, user might want to
     * create a sequence of before run tasks.
     *
     * @param configuration for which the task is created
     */
    override fun createTask(configuration: RunConfiguration) = ExternalSystemBeforeRunTask(ID, WemiProjectSystemId)

    override fun configureTask(runConfiguration: RunConfiguration, task: ExternalSystemBeforeRunTask): Boolean {
        val dialog = WemiEditTaskDialog(project, task.taskExecutionSettings)
        return dialog.showAndGet()
    }

    companion object {
        val ID = Key.create<ExternalSystemBeforeRunTask>("Wemi.BeforeRunTask")
    }

    private class WemiEditTaskDialog(project: Project,
                                     private val myTaskExecutionSettings: ExternalSystemTaskExecutionSettings) : DialogWrapper(project, true) {
        private val myControl: WemiTaskSettingsControl
        private var contentPane: JComponent? = null

        init {

            title = ExternalSystemBundle.message("tasks.select.task.title", WemiProjectSystemId.readableName)
            myControl = WemiTaskSettingsControl(project)
            myControl.setOriginalSettings(myTaskExecutionSettings)
            isModal = true
            init()
        }

        override fun createCenterPanel(): JComponent? {
            if (contentPane == null) {
                contentPane = PaintAwarePanel(GridBagLayout())
                myControl.fillUi((contentPane as PaintAwarePanel?)!!, 0)
                myControl.reset()
            }
            return contentPane
        }

        override fun getPreferredFocusedComponent(): JComponent? {
            return null
        }

        override fun dispose() {
            super.dispose()
            myControl.disposeUIResources()
        }

        override fun doOKAction() {
            myControl.apply(myTaskExecutionSettings)
            super.doOKAction()
        }
    }
}