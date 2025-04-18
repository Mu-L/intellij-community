// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.jvm.k1.scratch.compile

import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.rendering.DefaultErrorMessages
import org.jetbrains.kotlin.idea.base.psi.getLineNumber
import org.jetbrains.kotlin.idea.base.util.runReadActionInSmartMode
import org.jetbrains.kotlin.idea.caches.resolve.analyzeWithAllCompilerChecks
import org.jetbrains.kotlin.idea.jvm.shared.KotlinJvmBundle
import org.jetbrains.kotlin.idea.jvm.shared.scratch.*
import org.jetbrains.kotlin.idea.jvm.shared.scratch.output.ScratchOutput
import org.jetbrains.kotlin.idea.jvm.shared.scratch.output.ScratchOutputType
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.getElementTextWithContext
import org.jetbrains.kotlin.resolve.AnalyzingUtils

class KtCompilingExecutor(file: ScratchFile) : ScratchExecutor(file) {
    private var session: KtScratchExecutionSession? = null

    override fun execute() {
        handler.clear(file)
        handler.onStart(file)

        session = KtScratchExecutionSession(file, this)
        session?.execute {
            handler.onFinish(file)
            session = null
        }
    }

    override fun stop() {
        if (session == null) return

        try {
            session?.stop()
        } finally {
            handler.onFinish(file)
        }
    }

    fun checkForErrors(psiFile: KtFile, expressions: List<ScratchExpression>): Boolean =
        psiFile.project.runReadActionInSmartMode {
            try {
                AnalyzingUtils.checkForSyntacticErrors(psiFile)
            } catch (e: IllegalArgumentException) {
                errorOccurs(e.message ?: KotlinJvmBundle.message("couldn.t.compile.0", psiFile.name), isFatal = true)
                return@runReadActionInSmartMode false
            }

            val analysisResult = psiFile.analyzeWithAllCompilerChecks()

            if (analysisResult.isError()) {
                errorOccurs(analysisResult.error.message ?: KotlinJvmBundle.message("couldn.t.compile.0", psiFile.name), isFatal = true)
                return@runReadActionInSmartMode false
            }

            val bindingContext = analysisResult.bindingContext
            val diagnostics = bindingContext.diagnostics.filter { it.severity == Severity.ERROR }
            if (diagnostics.isNotEmpty()) {
                val scratchPsiFile = file.getPsiFile()
                diagnostics.forEach { diagnostic ->
                    val errorText = DefaultErrorMessages.render(diagnostic)
                    if (psiFile == scratchPsiFile) {
                        if (diagnostic.psiElement.containingFile == psiFile) {
                            val scratchExpression = expressions.findExpression(diagnostic.psiElement)
                            if (scratchExpression == null) {
                                LOG.error("Couldn't find expression to report error: ${diagnostic.psiElement.getElementTextWithContext()}")
                                handler.error(file, errorText)

                            } else {
                                handler.handle(file, scratchExpression, ScratchOutput(errorText, ScratchOutputType.ERROR))
                            }
                        } else {
                            handler.error(file, errorText)
                        }
                    } else {
                        handler.error(file, errorText)
                    }
                }
                handler.onFinish(file)
                return@runReadActionInSmartMode false
            }
            return@runReadActionInSmartMode true
        }

    fun parseOutput(processOutput: ProcessOutput, expressions: List<ScratchExpression>) {
        ProcessOutputParser(expressions).parse(processOutput)
    }

    private fun List<ScratchExpression>.findExpression(psiElement: PsiElement): ScratchExpression? {
        val elementLine = psiElement.getLineNumber()
        return runReadAction { firstOrNull { elementLine in it.lineStart..it.lineEnd } }
    }

    private fun List<ScratchExpression>.findExpression(lineStart: Int, lineEnd: Int): ScratchExpression? {
        return runReadAction { firstOrNull { it.lineStart == lineStart && it.lineEnd == lineEnd } }
    }

    private inner class ProcessOutputParser(private val expressions: List<ScratchExpression>) {
        fun parse(processOutput: ProcessOutput) {
            val out = processOutput.stdout
            val err = processOutput.stderr
            if (err.isNotBlank()) {
                handler.error(file, err)
            }
            if (out.isNotBlank()) {
                parseStdOut(out)
            }
        }

        private fun parseStdOut(out: String) {
            var results = arrayListOf<String>()
            var userOutput = arrayListOf<String>()
            for (line in out.split("\n")) {
                LOG.printDebugMessage("Compiling executor output: $line")

                if (isOutputEnd(line)) {
                    return
                }

                if (isGeneratedOutput(line)) {
                    val lineWoPrefix = line.removePrefix(KtScratchSourceFileProcessor.GENERATED_OUTPUT_PREFIX)
                    if (isResultEnd(lineWoPrefix)) {
                        val extractedLineInfo = extractLineInfoFrom(lineWoPrefix)
                            ?: return errorOccurs(
                                KotlinJvmBundle.message("couldn.t.extract.line.info.from.line.0", lineWoPrefix),
                                isFatal = true
                            )
                        val (startLine, endLine) = extractedLineInfo
                        val scratchExpression = expressions.findExpression(startLine, endLine)
                        if (scratchExpression == null) {
                            LOG.error(
                                "Couldn't find expression with start line = $startLine, end line = $endLine.\n" +
                                        expressions.joinToString("\n")
                            )
                        } else {
                            userOutput.forEach { output ->
                                handler.handle(file, scratchExpression, ScratchOutput(output, ScratchOutputType.OUTPUT))
                            }

                            results.forEach { result ->
                                handler.handle(file, scratchExpression, ScratchOutput(result, ScratchOutputType.RESULT))
                            }
                        }

                        results = arrayListOf()
                        userOutput = arrayListOf()
                    } else if (lineWoPrefix != Unit.toString()) {
                        results.add(lineWoPrefix)
                    }
                } else {
                    userOutput.add(line)
                }
            }
        }

        private fun isOutputEnd(line: String) = line.removeSuffix("\n") == KtScratchSourceFileProcessor.END_OUTPUT_MARKER
        private fun isResultEnd(line: String) = line.startsWith(KtScratchSourceFileProcessor.LINES_INFO_MARKER)
        private fun isGeneratedOutput(line: String) = line.startsWith(KtScratchSourceFileProcessor.GENERATED_OUTPUT_PREFIX)

        private fun extractLineInfoFrom(encoded: String): Pair<Int, Int>? {
            val lineInfo = encoded
                .removePrefix(KtScratchSourceFileProcessor.LINES_INFO_MARKER)
                .removeSuffix("\n")
                .split('|')
            if (lineInfo.size == 2) {
                try {
                    val (a, b) = lineInfo[0].toInt() to lineInfo[1].toInt()
                    if (a > -1 && b > -1) {
                        return a to b
                    }
                } catch (e: NumberFormatException) {
                }
            }
            return null
        }
    }
}