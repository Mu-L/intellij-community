// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.scripting.gradle.settings

import com.intellij.openapi.options.UnnamedConfigurable
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.idea.KotlinIdeaGradleBundle
import org.jetbrains.kotlin.idea.script.configuration.ScriptingSupportSpecificSettingsProvider

class GradleSettingsProvider(private val project: Project) : ScriptingSupportSpecificSettingsProvider() {
    override val title: String = KotlinIdeaGradleBundle.message("gradle.scripts.settings.title")

    override fun createConfigurable(): UnnamedConfigurable {
        return StandaloneScriptsUIComponent(project)
    }
}
