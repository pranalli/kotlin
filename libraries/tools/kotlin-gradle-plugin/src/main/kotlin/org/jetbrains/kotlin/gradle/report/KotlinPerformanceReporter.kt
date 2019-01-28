/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.report

import org.gradle.BuildAdapter
import org.gradle.BuildListener
import org.gradle.BuildResult
import org.gradle.api.Task
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.tasks.TaskState
import org.jetbrains.kotlin.gradle.plugin.internal.state.TaskExecutionResults
import java.io.File
import java.lang.StringBuilder
import java.util.concurrent.ConcurrentHashMap

internal class KotlinPerformanceReporter(private val perfReportFile: File) : BuildAdapter(), TaskExecutionListener {
    init {
        val dir = perfReportFile.parentFile
        check(dir.isDirectory) { "$dir does not exist or is a file" }
        check(!perfReportFile.isFile) { "Performance report log file $perfReportFile exists already" }
    }

    private var totalTimeNs = 0L
    private val taskStartNs = ConcurrentHashMap<Task, Long>()
    private val sb = StringBuilder()

    override fun beforeExecute(task: Task) {
        if (task.javaClass.name.startsWith("org.jetbrains.kotlin")) {
            taskStartNs[task] = System.nanoTime()

            sb.appendln("Task '$task' started")
        }
    }

    override fun afterExecute(task: Task, state: TaskState) {
        val startNs = taskStartNs[task] ?: return

        val endNs = System.nanoTime()
        val timeNs = endNs - startNs
        totalTimeNs += timeNs

        val executionResult = TaskExecutionResults[task.path]
        if (executionResult != null) {
            sb.appendln("Execution strategy: ${executionResult.executionStrategy}")

            executionResult.icLog?.let { icLog ->
                sb.appendln("===== BEGIN Incremental compilation log for $task =====")
                sb.appendln(icLog)
                sb.appendln("===== END Incremental compilation log for $task =====")
            }
        }

        val skipMessage = state.skipMessage
        if (skipMessage != null) {
            sb.appendln("Task '$task' was skipped: $skipMessage")
        }

        sb.appendln("Task '$task' finished in ${formatTime(totalTimeNs)}")
    }

    private fun formatTime(ns: Long): String {
        val seconds = ns.toDouble() / 1_000_000_000
        return String.format("%.2f s", seconds)
    }

    override fun buildFinished(result: BuildResult) {
        val logger = result.gradle?.rootProject?.logger
        try {
            perfReportFile.writeText(sb.toString())
            logger?.lifecycle("Kotlin performance report is written to $perfReportFile")
        } catch (e: Throwable) {
            logger?.error("Could not write Kotlin performance report to $perfReportFile", e)
        }
    }
}

