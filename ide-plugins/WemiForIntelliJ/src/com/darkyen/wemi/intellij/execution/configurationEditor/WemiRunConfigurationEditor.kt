/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.darkyen.wemi.intellij.execution.configurationEditor

import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.externalSystem.util.ExternalSystemUiUtil
import com.intellij.openapi.externalSystem.util.PaintAwarePanel
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import java.awt.Dimension
import java.awt.GridBagLayout
import javax.swing.Box
import javax.swing.JComponent

/**
 * Mostly boilerplate for [WemiTaskSettingsControl]
 */
class WemiRunConfigurationEditor(project: Project) : SettingsEditor<ExternalSystemRunConfiguration>() {

    private val control: WemiTaskSettingsControl = WemiTaskSettingsControl(project)

    override fun resetEditorFrom(s: ExternalSystemRunConfiguration) {
        control.setOriginalSettings(s.settings)
        control.reset()
    }

    @Throws(ConfigurationException::class)
    override fun applyEditorTo(s: ExternalSystemRunConfiguration) {
        control.apply(s.settings)
    }

    override fun createEditor(): JComponent {
        val result = PaintAwarePanel(GridBagLayout())
        control.fillUi(result, 0)
        result.add(Box.Filler(Dimension(0, 0), Dimension(0, 200), Dimension(0, 0)),
                ExternalSystemUiUtil.getFillLineConstraints(0))
        ExternalSystemUiUtil.fillBottom(result)
        return result
    }

    override fun disposeEditor() {
        control.disposeUIResources()
    }
}
