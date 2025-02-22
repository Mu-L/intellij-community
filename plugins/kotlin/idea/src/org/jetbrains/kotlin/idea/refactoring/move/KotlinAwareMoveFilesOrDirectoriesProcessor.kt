// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.refactoring.move

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.move.MoveCallback
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewDescriptor
import com.intellij.util.containers.MultiMap
import org.jetbrains.kotlin.psi.KtFile

class KotlinAwareMoveFilesOrDirectoriesProcessor @JvmOverloads constructor(
    project: Project,
    private val elementsToMove: List<PsiElement>,
    private val targetDirectory: PsiDirectory,
    private val searchReferences: Boolean,
    searchInComments: Boolean,
    searchInNonJavaFiles: Boolean,
    moveCallback: MoveCallback?,
    prepareSuccessfulCallback: Runnable = EmptyRunnable.INSTANCE,
    private val throwOnConflicts: Boolean = false
) : MoveFilesOrDirectoriesProcessor(
    project,
    elementsToMove.toTypedArray(),
    targetDirectory,
    true,
    searchInComments,
    searchInNonJavaFiles,
    moveCallback,
    prepareSuccessfulCallback
) {
    override fun createUsageViewDescriptor(usages: Array<out UsageInfo>): UsageViewDescriptor {
        return MoveFilesWithDeclarationsViewDescriptor(elementsToMove.toTypedArray(), targetDirectory)
    }

    protected override fun findUsages(): Array<UsageInfo> {
        try {
            markScopeToMove(elementsToMove)
            return if (searchReferences) super.findUsages() else UsageInfo.EMPTY_ARRAY
        } finally {
            markScopeToMove(null)
        }
    }

    protected override fun preprocessUsages(refUsages: Ref<Array<UsageInfo>>): Boolean {
        val (conflicts, usages) = MoveConflictUsages.preprocess(refUsages)
        return showConflicts(conflicts, usages)
    }

    override fun showConflicts(conflicts: MultiMap<PsiElement, String>, usages: Array<out UsageInfo>?): Boolean {
        if (throwOnConflicts && !conflicts.isEmpty) throw MoveConflictsFoundException()
        return super.showConflicts(conflicts, usages)
    }

    private fun markPsiFiles(mark: PsiFile.() -> Unit) {
        fun PsiElement.doMark(mark: PsiFile.() -> Unit) {
            when (this) {
                is PsiFile -> mark()
                is PsiDirectory -> children.forEach { it.doMark(mark) }
            }
        }

        elementsToMove.forEach { it.doMark(mark) }
    }

    private fun markScopeToMove(allElementsToMove: List<PsiElement>?) {
        markPsiFiles { (this as? KtFile)?.allElementsToMove = allElementsToMove }
    }
}
