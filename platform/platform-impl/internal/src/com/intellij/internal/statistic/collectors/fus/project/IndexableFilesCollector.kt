// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.internal.statistic.collectors.fus.project

import com.intellij.internal.statistic.beans.MetricEvent
import com.intellij.internal.statistic.eventLog.EventLogGroup
import com.intellij.internal.statistic.eventLog.events.EventFields
import com.intellij.internal.statistic.service.fus.collectors.ProjectUsagesCollector
import com.intellij.internal.statistic.utils.StatisticsUtil.roundToPowerOfTwo
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFileFilter
import com.intellij.util.indexing.FileBasedIndex
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

private class IndexableFilesCollector : ProjectUsagesCollector() {
  private val GROUP = EventLogGroup("project.indexable.files", 4)
  private val ALL_INDEXABLE_FILES = GROUP.registerEvent("all.indexable.files", EventFields.Int("count"))
  private val ALL_NON_INDEXABLE_FILES = GROUP.registerEvent("all.non.indexable.files", EventFields.Int("count"))
  private val CONTENT_INDEXABLE_FILES = GROUP.registerEvent("content.indexable.files", EventFields.Int("count"))

  override fun getGroup(): EventLogGroup = GROUP

  override suspend fun collect(project: Project): Set<MetricEvent> {
    val context = coroutineContext

    var allIndexableFiles = 0
    var inContentIndexableFiles = 0

    val fileIndex = ProjectRootManager.getInstance(project).fileIndex

    val nonIndexableFiles = countNonIndexableFiles(project)

    FileBasedIndex.getInstance().iterateIndexableFiles(ContentIterator { fileOrDir ->
      if (!context.isActive) {
        return@ContentIterator false
      }

      runReadAction {
        if (fileOrDir.isValid && !fileOrDir.isDirectory && !fileIndex.isExcluded(fileOrDir)) {
          if (fileIndex.isInContent(fileOrDir)) {
            inContentIndexableFiles++
          }
          allIndexableFiles++
        }
      }
      true
    }, project, null)

    return hashSetOf(
      ALL_INDEXABLE_FILES.metric(roundToPowerOfTwo(allIndexableFiles)),
      ALL_NON_INDEXABLE_FILES.metric(roundToPowerOfTwo(nonIndexableFiles)),
      CONTENT_INDEXABLE_FILES.metric(roundToPowerOfTwo(inContentIndexableFiles))
    )
  }

  private fun countNonIndexableFiles(
    project: Project,
  ): Int {
    var allNonIndexableFiles = 0
    FileBasedIndex.getInstance().iterateNonIndexableFiles(project, VirtualFileFilter.ALL) {
      allNonIndexableFiles++
      true
    }
    return allNonIndexableFiles
  }
}
