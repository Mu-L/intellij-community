// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.uast.kotlin.internal

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiModifierListOwner
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.idea.base.projectStructure.languageVersionSettings
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.caches.resolve.getResolutionFacade
import org.jetbrains.kotlin.idea.caches.resolve.safeAnalyzeNonSourceRootCode
import org.jetbrains.kotlin.idea.core.resolveCandidates
import org.jetbrains.kotlin.idea.util.actionUnderSafeAnalyzeBlock
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.util.getCall
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.uast.kotlin.KotlinUastResolveProviderService
import org.jetbrains.uast.kotlin.resolveToDeclarationImpl

private class IdeaKotlinUastResolveProviderService : KotlinUastResolveProviderService {
    @Deprecated(
        "Do not use the old frontend, retroactively named as FE1.0, since K2 with the new frontend is coming.\n" +
                "Please use analysis API: https://github.com/JetBrains/kotlin/blob/master/docs/analysis/analysis-api/analysis-api.md",
        replaceWith = ReplaceWith("analyze(element) { }", "org.jetbrains.kotlin.analysis.api.analyze")
    )
    override fun getBindingContext(element: KtElement) = element.analyze(BodyResolveMode.PARTIAL_WITH_CFA)

    @Deprecated(
        "Do not use the old frontend, retroactively named as FE1.0, since K2 with the new frontend is coming.\n" +
                "Please use analysis API: https://github.com/JetBrains/kotlin/blob/master/docs/analysis/analysis-api/analysis-api.md",
        replaceWith = ReplaceWith("analyze(element) { }", "org.jetbrains.kotlin.analysis.api.analyze")
    )
    override fun getBindingContextIfAny(element: KtElement): BindingContext? =
        element.actionUnderSafeAnalyzeBlock({ getBindingContext(element) }, { null })

    override fun isJvmOrCommonElement(psiElement: PsiElement): Boolean = psiElement.isJvmOrCommonElement

    override fun getLanguageVersionSettings(element: KtElement): LanguageVersionSettings = element.languageVersionSettings

    override fun getReferenceVariants(ktExpression: KtExpression, nameHint: String): Sequence<PsiElement> {
        val resolutionFacade = ktExpression.getResolutionFacade()
        val bindingContext = ktExpression.safeAnalyzeNonSourceRootCode(resolutionFacade)
        return sequence {
            // Use logic (shared with CLI) about handling compound assignments
            yieldAll(super.getReferenceVariants(ktExpression, nameHint))
            // Then, look for other candidates with a name hint
            val call = ktExpression.getCall(bindingContext) ?: return@sequence
            call.resolveCandidates(bindingContext, resolutionFacade)
                .forEach {resolvedCall ->
                    resolveToDeclarationImpl(ktExpression, resolvedCall.candidateDescriptor)?.let { yield(it) }
                }
        }
    }

    override fun convertToPsiAnnotation(ktElement: KtElement): PsiAnnotation? {
        return ktElement.actionUnderSafeAnalyzeBlock({ super.convertToPsiAnnotation(ktElement) }, { null })
    }

    override fun getPsiAnnotations(psiElement: PsiModifierListOwner): Array<PsiAnnotation> {
        return psiElement.actionUnderSafeAnalyzeBlock({ psiElement.annotations }, { emptyArray() })
    }
}
