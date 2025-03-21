// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.refactoring.move.ui

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.NlsContexts
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.util.parentOfType
import com.intellij.psi.util.parentOfTypes
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.move.MoveCallback
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.ui.dsl.builder.*
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.jetbrains.annotations.Nls
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.core.getFqNameWithImplicitPrefixOrRoot
import org.jetbrains.kotlin.idea.core.getPackage
import org.jetbrains.kotlin.idea.core.util.toPsiDirectory
import org.jetbrains.kotlin.idea.k2.refactoring.move.descriptor.K2MoveDescriptor
import org.jetbrains.kotlin.idea.k2.refactoring.move.descriptor.K2MoveOperationDescriptor
import org.jetbrains.kotlin.idea.k2.refactoring.move.descriptor.K2MoveOperationDescriptor.Declarations
import org.jetbrains.kotlin.idea.k2.refactoring.move.descriptor.K2MoveSourceDescriptor
import org.jetbrains.kotlin.idea.k2.refactoring.move.descriptor.K2MoveTargetDescriptor
import org.jetbrains.kotlin.idea.k2.refactoring.move.ui.K2MoveTargetModel.Declarations.MoveTargetType
import org.jetbrains.kotlin.idea.refactoring.KotlinCommonRefactoringSettings
import org.jetbrains.kotlin.idea.search.ExpectActualUtils
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.hasActualModifier
import org.jetbrains.kotlin.psi.psiUtil.isExpectDeclaration
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull

/**
 * @see K2MoveDescriptor
 */
sealed class K2MoveModel {
    abstract val project: Project

    abstract val source: K2MoveSourceModel<*>

    abstract val target: K2MoveTargetModel

    val searchForText: Setting = Setting.SEARCH_FOR_TEXT

    val searchInComments: Setting = Setting.SEARCH_IN_COMMENTS

    abstract val inSourceRoot: Boolean

    val searchReferences: Setting = Setting.SEARCH_REFERENCES

    val mppDeclarations: Setting = Setting.MPP_DECLARATIONS

    abstract val moveCallBack: MoveCallback?

    @RequiresReadLock
    abstract fun toDescriptor(): K2MoveOperationDescriptor<*>

    /**
     * Returns whether running the refactoring is meaningful.
     * For example, moving a file to the package and directory it's already in is not meaningful.
     */
    open fun isValidRefactoring(): Boolean {
        return source.elements.isNotEmpty()
    }

    open fun buildPanel(panel: Panel): Unit = with(panel) {
        row {
            panel {
                searchForText.createComboBox(this)
                searchReferences.createComboBox(this, inSourceRoot)
            }.align(AlignY.TOP + AlignX.LEFT)
            panel {
                searchInComments.createComboBox(this)
                mppDeclarations.createComboBox(this, inSourceRoot && this@K2MoveModel is Declarations)
            }.align(AlignY.TOP + AlignX.RIGHT)
        }
    }

    enum class Setting(private val text: @NlsContexts.Checkbox String) {
        SEARCH_FOR_TEXT(KotlinBundle.message("search.for.text.occurrences")) {
            override var state: Boolean
                get() {
                    return KotlinCommonRefactoringSettings.getInstance().MOVE_SEARCH_FOR_TEXT
                }
                set(value) {
                    KotlinCommonRefactoringSettings.getInstance().MOVE_SEARCH_FOR_TEXT = value
                }
        },

        SEARCH_IN_COMMENTS(KotlinBundle.message("search.in.comments.and.strings")) {
            override var state: Boolean
                get() {
                    return KotlinCommonRefactoringSettings.getInstance().MOVE_SEARCH_IN_COMMENTS
                }
                set(value) {
                    KotlinCommonRefactoringSettings.getInstance().MOVE_SEARCH_IN_COMMENTS = value
                }
        },


        SEARCH_REFERENCES(KotlinBundle.message("checkbox.text.search.references")) {
            override var state: Boolean
                get() {
                    return KotlinCommonRefactoringSettings.getInstance().MOVE_SEARCH_REFERENCES
                }
                set(value) {
                    KotlinCommonRefactoringSettings.getInstance().MOVE_SEARCH_REFERENCES = value
                }
        },

        MPP_DECLARATIONS(KotlinBundle.message("label.text.move.expect.actual.counterparts")) {
            override var state: Boolean
                get() {
                    return KotlinCommonRefactoringSettings.getInstance().MOVE_MPP_DECLARATIONS
                }
                set(value) {
                    KotlinCommonRefactoringSettings.getInstance().MOVE_MPP_DECLARATIONS = value
                }
        };

        abstract var state: Boolean

        fun createComboBox(panel: Panel, enabled: Boolean = true) {
            panel.row {
                val checkBox = checkBox(text).enabled(enabled)
                if (enabled) {
                    checkBox.bindSelected(::state)
                } else {
                    checkBox.selected(false)
                }
            }.layout(RowLayout.PARENT_GRID)
        }
    }

    /**
     * @see K2MoveDescriptor.Files
     */
    class Files(
        override val project: Project,
        override val source: K2MoveSourceModel.FileSource,
        override val target: K2MoveTargetModel.SourceDirectory,
        override val inSourceRoot: Boolean,
        override val moveCallBack: MoveCallback? = null
    ) : K2MoveModel() {
        private fun PsiFile.isAlreadyInTarget(): Boolean {
            return parent == target.directory && when (this) {
                is PsiJavaFile -> packageName == target.pkgName.asString()
                is KtFile -> packageFqName == target.pkgName
                else -> true
            }
        }

        override fun isValidRefactoring(): Boolean {
            if (!super.isValidRefactoring()) return false
            if (source.elements.all { it is PsiFile && it.isAlreadyInTarget() }) return false
            return true
        }

        override fun toDescriptor(): K2MoveOperationDescriptor.Files {
            val srcDescr = source.toDescriptor()
            val targetDescr = target.toDescriptor()
            val searchReferences = if (inSourceRoot) searchReferences.state else false
            val moveDescriptor = K2MoveDescriptor.Files(
                project,
                srcDescr,
                targetDescr
            )
            val operationDescriptor = K2MoveOperationDescriptor.Files(
                project,
                listOf(moveDescriptor),
                searchForText.state,
                searchReferences,
                searchInComments.state,
                dirStructureMatchesPkg = true,
                moveCallBack
            )
            return operationDescriptor
        }
    }

    /**
     * @see K2MoveDescriptor.Declarations
     */
    open class Declarations(
        override val project: Project,
        override val source: K2MoveSourceModel.ElementSource,
        override val target: K2MoveTargetModel,
        override val inSourceRoot: Boolean,
        override val moveCallBack: MoveCallback? = null
    ) : K2MoveModel() {
        private fun isValidFileRefactoring(fileName: String): Boolean {
            fun KtFile.isTargetFile(): Boolean {
                return containingDirectory == target.directory
                        && packageFqName == target.pkgName
                        && name == fileName
            }
            if (!fileName.isValidKotlinFile()) return false
            val files = source.elements.map { it.containingFile }
            return files.size != 1 || !(files.single() as KtFile).isTargetFile()
        }

        private fun K2MoveTargetModel.Declarations.isValidRefactoring(): Boolean {
            return if (destinationTargetType == MoveTargetType.FILE) {
                isValidFileRefactoring(fileName)
            } else {
                val destinationClass = destinationClass ?: return false
                // Cannot allow moving the class into itself
                destinationClass.parentsWithSelf.none { it in source.elements }
            }
        }

        internal fun isValidDeclarationsRefactoring(): Boolean {
            val target = target
            return when (target) {
                is K2MoveTargetModel.Declarations -> target.isValidRefactoring()
                is K2MoveTargetModel.File -> isValidFileRefactoring(target.fileName)
                else -> false
            }
        }

        override fun isValidRefactoring(): Boolean {
            return super.isValidRefactoring() && isValidDeclarationsRefactoring()
        }

        override fun toDescriptor(): K2MoveOperationDescriptor.Declarations {
            val declarations = source.elements
            val searchForReferences = if (inSourceRoot) searchReferences.state else false
            val searchForText = searchForText.state
            val searchInComments = searchInComments.state
            if (mppDeclarations.state && declarations.any { it.isExpectDeclaration() || it.hasActualModifier() }) {
                val descriptors = declarations.flatMap { elem ->
                    ExpectActualUtils.withExpectedActuals(elem).filterIsInstance<KtNamedDeclaration>()
                }.groupBy { elem ->
                    ProjectFileIndex.getInstance(project).getSourceRootForFile(elem.containingFile.virtualFile)?.toPsiDirectory(project)
                }.mapNotNull { (baseDir, elements) ->
                    if (baseDir == null) return@mapNotNull null
                    val srcDescriptor = K2MoveSourceDescriptor.ElementSource(elements)
                    val targetDescriptor = target.toDescriptor() as K2MoveTargetDescriptor.Declaration<*>
                    K2MoveDescriptor.Declarations(project, srcDescriptor, targetDescriptor)
                }
                return Declarations(
                    project = project,
                    moveDescriptors = descriptors,
                    searchForText = searchForText,
                    searchInComments = searchForReferences,
                    searchReferences = searchInComments,
                    dirStructureMatchesPkg = true,
                    moveCallBack = moveCallBack
                )
            } else {
                val srcDescr = K2MoveSourceDescriptor.ElementSource(declarations)
                val targetDescr = target.toDescriptor() as K2MoveTargetDescriptor.Declaration<*>
                val moveDescriptor = K2MoveDescriptor.Declarations(project, srcDescr, targetDescr)
                return Declarations(
                    project = project,
                    moveDescriptors = listOf(moveDescriptor),
                    searchForText = searchForText,
                    searchInComments = searchInComments,
                    searchReferences = searchForReferences,
                    dirStructureMatchesPkg = true,
                    moveCallBack = moveCallBack
                )
            }
        }
    }

    companion object {
        private val MOVE_DECLARATIONS: String
            @Nls
            get() = KotlinBundle.message("text.move.declarations")

        private fun List<PsiElement>.fileElements() = map { if (it is PsiDirectory) it else it.containingFile }

        fun create(
            elements: Array<out PsiElement>,
            targetContainer: PsiElement?,
            editor: Editor? = null,
            moveCallBack: MoveCallback? = null
        ): K2MoveModel? {
            val project = elements.firstOrNull()?.project ?: error("Elements not part of project")

            val elementsToMove = elements.filter { elem ->
                when (elem) {
                    is PsiDirectory, is PsiFile -> true
                    else -> {
                        val container = elem.parentOfTypes(PsiFile::class, KtNamedDeclaration::class) ?: error("Element not in Kotlin file")
                        container !in elements
                    }
                }
            }

            fun inSourceRoot(declarations: List<PsiElement>): Boolean {
                val fileIndex = ProjectFileIndex.getInstance(project)
                if (declarations.fileElements().toSet().any { !fileIndex.isInSourceContent(it.virtualFile) }) return false
                if (targetContainer == null || targetContainer is PsiDirectory) return true
                val targetFile = targetContainer.containingFile?.virtualFile ?: return false
                return fileIndex.isInSourceContent(targetFile)
            }

            fun isSingleFileMove(movedElements: List<PsiElement>) = movedElements.all { it is KtNamedDeclaration }
                    || movedElements.singleOrNull() is KtFile

            fun isMultiFileMove(movedElements: List<PsiElement>) = movedElements.fileElements().toSet().size > 1

            fun PsiElement?.isSingleClassContainer(): Boolean {
                if (this !is KtClassOrObject) return false
                val file = parent as? KtFile ?: return false
                return this == file.declarations.singleOrNull()
            }

            if (!CommonRefactoringUtil.checkReadOnlyStatusRecursively(project, elements.toList(), true)) return null

            if (elementsToMove.any { it.parentOfType<KtNamedDeclaration>(withSelf = false) != null }) {
                val singleElementToMove = elementsToMove.singleOrNull()
                if (singleElementToMove == null) {
                    val message = RefactoringBundle.getCannotRefactorMessage(
                        KotlinBundle.message("text.move.declaration.only.support.for.single.elements")
                    )
                    CommonRefactoringUtil.showErrorHint(project, editor, message, MOVE_DECLARATIONS, null)
                    return null
                } else if (singleElementToMove !is KtClassOrObject && singleElementToMove !is KtNamedFunction && singleElementToMove !is KtProperty) {
                    val message = RefactoringBundle.getCannotRefactorMessage(
                        KotlinBundle.message("text.move.declaration.only.support.for.some.nested.declarations")
                    )
                    CommonRefactoringUtil.showErrorHint(project, editor, message, MOVE_DECLARATIONS, null)
                    return null
                }
            }

            if (elementsToMove.any { it is KtEnumEntry }) {
                val message = RefactoringBundle.getCannotRefactorMessage(KotlinBundle.message("text.move.declaration.no.support.for.enums"))
                CommonRefactoringUtil.showErrorHint(project, editor, message, MOVE_DECLARATIONS, null)
                return null
            }

            if (isMultiFileMove(elementsToMove) && targetContainer != null && targetContainer !is PsiDirectory) {
                val message = RefactoringBundle.getCannotRefactorMessage(KotlinBundle.message("text.move.file.no.support.for.file.target"))
                CommonRefactoringUtil.showErrorHint(project, editor, message, MOVE_DECLARATIONS, null)
                return null
            }

            if (isMultiFileMove(elementsToMove) && elementsToMove.any { it is KtNamedDeclaration && !it.isSingleClassContainer() }) {
                val message = RefactoringBundle.getCannotRefactorMessage(
                    KotlinBundle.message("text.move.declaration.no.support.for.multi.file")
                )
                CommonRefactoringUtil.showErrorHint(project, editor, message, MOVE_DECLARATIONS, null)
                return null
            }

            val declarationsFromFiles = elementsToMove.flatMap { elem ->
                when (elem) {
                    is KtFile -> elem.declarations.filterIsInstance<KtNamedDeclaration>()
                    is KtNamedDeclaration -> listOf(elem)
                    else -> listOf()
                }
            }

            fun sourceFileName(): String {
                val firstElem = elementsToMove.firstOrNull() as KtElement
                return when (firstElem) {
                    is KtFile -> firstElem.name
                    is KtNamedDeclaration -> "${firstElem.name}.${KotlinLanguage.INSTANCE.associatedFileType?.defaultExtension}"
                    else -> error("Element to move should be a file or declaration")
                }
            }

            val inSourceRoot = inSourceRoot(elementsToMove)
            return when {
                (elementsToMove.all { it is KtFile } && targetContainer is PsiDirectory)
                        || isMultiFileMove(elementsToMove)
                        || declarationsFromFiles.isEmpty()
                        || (targetContainer is PsiDirectory && targetContainer.getPackage() == null) -> {
                    // this move can contain foreign language files
                    val source = K2MoveSourceModel.FileSource(elementsToMove.fileElements().toSet())
                    val target = if (targetContainer is PsiDirectory) {
                        val pkg = targetContainer.getFqNameWithImplicitPrefixOrRoot()
                        K2MoveTargetModel.SourceDirectory(pkg, targetContainer)
                    } else { // no default target is provided, happens when invoking refactoring via keyboard instead of drag-and-drop
                        val file = elementsToMove.firstOrNull { it.containingFile != null }?.containingFile
                            ?: error("No default target found")
                        val directory = file.containingDirectory ?: error("No default target found")
                        val pkgName = elementsToMove.firstIsInstanceOrNull<KtElement>()?.containingKtFile?.packageFqName ?: FqName.ROOT
                        K2MoveTargetModel.SourceDirectory(pkgName, directory)
                    }
                    Files(project, source, target, inSourceRoot, moveCallBack)
                }

                targetContainer is KtFile || targetContainer.isSingleClassContainer() || isSingleFileMove(elementsToMove) -> {
                    val source = K2MoveSourceModel.ElementSource(declarationsFromFiles.toSet())
                    val targetFile = targetContainer?.containingFile
                    val target = if (targetFile is KtFile) {
                        K2MoveTargetModel.File(targetFile)
                    } else if (targetContainer is PsiDirectory) {
                        val pkg = targetContainer.getFqNameWithImplicitPrefixOrRoot()
                        K2MoveTargetModel.File(sourceFileName(), pkg, targetContainer)
                    } else { // no default target is provided, happens when invoking refactoring via keyboard instead of drag-and-drop
                        val firstElem = elementsToMove.firstOrNull() as KtElement
                        val containingFile = firstElem.containingKtFile
                        val psiDirectory = containingFile.containingDirectory ?: error("No directory found")
                        K2MoveTargetModel.Declarations(
                            defaultDirectory = psiDirectory,
                            defaultPkgName = containingFile.packageFqName,
                            defaultFileName = sourceFileName()
                        )
                    }

                    Declarations(
                        project = project,
                        source = source,
                        target = target,
                        inSourceRoot = inSourceRoot,
                        moveCallBack = moveCallBack
                    )
                }

                else -> error("Unsupported move operation")
            }
        }
    }
}