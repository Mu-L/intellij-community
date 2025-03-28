// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.compilerPlugin.parcelize.gradleJava

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.AbstractExternalEntityData
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.service.project.manage.AbstractProjectDataService
import com.intellij.serialization.PropertyMapping
import org.gradle.tooling.model.idea.IdeaModule
import org.jetbrains.kotlin.idea.gradleTooling.model.parcelize.ParcelizeGradleModel
import org.jetbrains.kotlin.idea.gradleTooling.model.parcelize.ParcelizeModelBuilderService
import org.jetbrains.plugins.gradle.service.project.AbstractProjectResolverExtension
import org.jetbrains.plugins.gradle.util.GradleConstants

internal class ParcelizeIdeModel @PropertyMapping("isEnabled") constructor(
    val isEnabled: Boolean
) : AbstractExternalEntityData(GradleConstants.SYSTEM_ID) {
    companion object {
        val KEY = Key.create(ParcelizeIdeModel::class.java, ProjectKeys.CONTENT_ROOT.processingWeight + 1)
    }
}

internal class ParcelizeIdeModelDataService : AbstractProjectDataService<ParcelizeIdeModel, Void>() {
    override fun getTargetDataKey() = ParcelizeIdeModel.KEY
}

internal class ParcelizeProjectResolverExtension : AbstractProjectResolverExtension() {
    override fun getExtraProjectModelClasses() = setOf(ParcelizeGradleModel::class.java)
    override fun getToolingExtensionsClasses() = setOf(ParcelizeModelBuilderService::class.java, Unit::class.java)

    override fun populateModuleExtraModels(gradleModule: IdeaModule, ideModule: DataNode<ModuleData>) {
        val parcelizeModel = resolverCtx.getExtraProject(gradleModule, ParcelizeGradleModel::class.java)

        if (parcelizeModel != null && parcelizeModel.isEnabled) {
            ideModule.createChild(ParcelizeIdeModel.KEY, ParcelizeIdeModel(isEnabled = parcelizeModel.isEnabled))
        }

        super.populateModuleExtraModels(gradleModule, ideModule)
    }
}

