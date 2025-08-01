// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.refactoring.suggested

import com.intellij.refactoring.suggested.SignatureChangePresentationModel
import com.intellij.refactoring.suggested.SignatureChangePresentationModel.Effect
import com.intellij.refactoring.suggested.SignatureChangePresentationModel.TextFragment.Leaf
import com.intellij.refactoring.suggested.SignaturePresentationBuilder
import com.intellij.refactoring.suggested.SuggestedRefactoringSupport

internal class KotlinSignaturePresentationBuilder(
    signature: SuggestedRefactoringSupport.Signature,
    otherSignature: SuggestedRefactoringSupport.Signature,
    isOldSignature: Boolean
) : SignaturePresentationBuilder(signature, otherSignature, isOldSignature)
{
    override fun buildPresentation() {
        val declarationType = (signature.additionalData as KotlinSignatureAdditionalData).declarationType
        val parameterBuilder: (MutableList<SignatureChangePresentationModel.TextFragment>, SuggestedRefactoringSupport.Parameter, SuggestedRefactoringSupport.Parameter?) -> Unit =
            { fragments, parameter, correspondingParameter ->
                if (parameter.modifiers.isNotEmpty()) {
                    fragments += leaf(parameter.modifiers, correspondingParameter?.modifiers ?: parameter.modifiers)
                    fragments += Leaf(" ")
                }

                fragments += leaf(parameter.name, correspondingParameter?.name ?: parameter.name)

                fragments += Leaf(": ")

                fragments += leaf(parameter.type, correspondingParameter?.type ?: parameter.type)

                val defaultValue = parameter.defaultValue
                if (defaultValue != null) {
                    val defaultValueEffect = if (correspondingParameter != null)
                        effect(defaultValue, correspondingParameter.defaultValue)
                    else
                        Effect.None
                    fragments += Leaf(" = ", defaultValueEffect.takeIf { it != Effect.Modified } ?: Effect.None)
                    fragments += Leaf(defaultValue, defaultValueEffect)
                }
            }

        if (declarationType.isFunction) {
            val contextParameters = signature.parameters.filter { it.isContextParameter }
            if (contextParameters.isNotEmpty()) {
                buildParameterList(prefix = "context(", suffix = ")", parameters = contextParameters, parameterBuilder = parameterBuilder)
                fragments += Leaf("\n")
            }
        }

        val keyword = declarationType.prefixKeyword
        if (keyword != null) {
            fragments += Leaf("$keyword ")
        }

        signature.receiverType?.let {
            when (val effect = effect(it, otherSignature.receiverType)) {
                Effect.Modified -> {
                    fragments += Leaf(it, effect)
                    fragments += Leaf(".")
                }

                else -> {
                    fragments += Leaf("$it.", effect)
                }
            }
        }

        val name = if (declarationType == DeclarationType.SECONDARY_CONSTRUCTOR) "constructor" else signature.name
        fragments += Leaf(name, effect(signature.name, otherSignature.name))

        if (declarationType.isFunction) {
            buildParameterList(parameters = signature.parameters.filterNot { it.isContextParameter }, parameterBuilder = parameterBuilder)
        } else {
            require(signature.parameters.isEmpty())
        }

        signature.type?.let { type ->
            when (val effect = effect(type, otherSignature.type)) {
                Effect.Added, Effect.Removed, Effect.None -> {
                    fragments += Leaf(": ${signature.type}", effect)
                }

                Effect.Modified -> {
                    fragments += Leaf(": ")
                    fragments += Leaf(type, effect)
                }

                else -> {}
            }
        }
    }
}