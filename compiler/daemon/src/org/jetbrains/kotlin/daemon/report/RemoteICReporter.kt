/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

package org.jetbrains.kotlin.daemon.report

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.daemon.common.*
import org.jetbrains.kotlin.incremental.ICReporter
import java.io.File

internal class RemoteICReporter(
        private val servicesFacade: CompilerServicesFacadeBase,
        private val compilationResults: CompilationResults,
        compilationOptions: IncrementalCompilationOptions
) : ICReporter {

    private val rootDir = compilationOptions.modulesInfo.projectRoot
    private val shouldReportMessages = ReportCategory.IC_MESSAGE.code in compilationOptions.reportCategories
    private val isVerbose = compilationOptions.reportSeverity == ReportSeverity.DEBUG.code
    private val shouldReportCompileIteration = CompilationResultCategory.IC_COMPILE_ITERATION.code in compilationOptions.requestedCompilationResults
    private val shouldReportICLog = CompilationResultCategory.IC_LOG.code in compilationOptions.requestedCompilationResults
    private val icLogStringBuilder = StringBuilder()

    override fun report(message: () -> String) {
        val lazyMessage = lazy { message() }
        if (shouldReportMessages && isVerbose) {
            servicesFacade.report(ReportCategory.IC_MESSAGE, ReportSeverity.DEBUG, lazyMessage.value)
        }
        if (shouldReportICLog) {
            icLogStringBuilder.append(lazyMessage.value)
        }
    }

    override fun reportCompileIteration(sourceFiles: Collection<File>, exitCode: ExitCode) {
        if (shouldReportCompileIteration) {
            compilationResults.add(
                CompilationResultCategory.IC_COMPILE_ITERATION.code,
                CompileIterationResult(sourceFiles, exitCode.toString())
            )
        }
        if (shouldReportICLog) {
            icLogStringBuilder.appendln("compile iteration: ${sourceFiles.pathsAsStringRelativeTo(rootDir)}")
        }
    }

    fun flush() {
        compilationResults.add(
            CompilationResultCategory.IC_LOG.code,
            icLogStringBuilder.toString()
        )
    }

    private fun File.relativeOrCanonical(base: File): String =
        relativeToOrNull(base)?.path ?: canonicalPath

    private fun Iterable<File>.pathsAsStringRelativeTo(base: File): String =
        map { it.relativeOrCanonical(base) }.sorted().joinToString()
}

