// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.vcs.commit

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.VcsBundle.message
import com.intellij.openapi.vcs.VcsConfiguration
import com.intellij.openapi.vcs.ui.RefreshableOnComponent
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.layout.ComponentPredicate
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.UIUtil.removeMnemonic
import org.jetbrains.annotations.Nls
import java.awt.BorderLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.border.EmptyBorder

class CommitOptionsPanel(private val project: Project,
                         private val actionNameSupplier: () -> @Nls String,
                         private val nonFocusable: Boolean,
                         private val nonModalCommit: Boolean) : CommitOptionsUi {
  val component: JComponent
  private lateinit var placeholder: Placeholder

  var isEmpty: Boolean = true
    private set

  private var visibleVcses: Set<AbstractVcs>? = null
  private val visibleVcsListeners = mutableListOf<Runnable>()

  init {
    val panel = panel {
      row {
        placeholder = placeholder()
          .align(Align.FILL)
      }.resizableRow()
    }
    component = ScrollPaneFactory.createScrollPane(panel, true)
  }

  override fun setOptions(options: CommitOptions) {
    val actionName = removeMnemonic(actionNameSupplier())

    visibleVcsListeners.clear()
    isEmpty = options.isEmpty

    placeholder.component = panel {
      for ((vcs, option) in options.vcsOptions) {
        group(vcs.displayName) {
          appendOptionRow(option)
        }.visibleIf(VcsVisiblePredicate(vcs))
      }

      val beforeOptions = options.beforeOptions
      if (beforeOptions.isNotEmpty()) {
        group(commitChecksGroupTitle(actionName)) {
          if (nonModalCommit) {
            appendNonModalCommitSettingsRow()
            separator()
          }
          for (option in beforeOptions) {
            appendOptionRow(option)
          }
        }
      }

      val afterOptions = options.afterOptions
      if (afterOptions.isNotEmpty()) {
        group(message("border.standard.after.checkin.options.group", actionName)) {
          for (option in afterOptions) {
            appendOptionRow(option)
          }
        }
      }
      val extensionOptions = options.extensionOptions
      if (extensionOptions.isNotEmpty()) {
        for (option in extensionOptions) {
          appendOptionRow(option)
        }
      }
    }

    // Hack: do not iterate over checkboxes in CommitDialog.
    if (nonFocusable) {
      UIUtil.forEachComponentInHierarchy(component) {
        if (it is JCheckBox) it.isFocusable = false
      }
    }
  }

  private fun Panel.appendNonModalCommitSettingsRow() {
    val settings = VcsConfiguration.getInstance(project)
    row {
      checkBox(message("settings.commit.slow.checks"))
        .comment(message("settings.commit.slow.checks.description.short"))
        .selected(settings.NON_MODAL_COMMIT_POSTPONE_SLOW_CHECKS)
        .onChanged { setRunSlowCommitChecksAfterCommit(project, it.isSelected) }
    }
  }

  private fun Panel.appendOptionRow(option: RefreshableOnComponent) {
    val component = extractMeaningfulComponent(option.component)
    row {
      cell(component ?: option.component)
        .align(Align.FILL)
    }
  }

  private fun extractMeaningfulComponent(component: JComponent): JComponent? {
    if (component is DialogPanel) return null
    if (component is JPanel) {
      val border = component.border
      if (component.layout is BorderLayout &&
          component.components.size == 1 &&
          (border == null || border is EmptyBorder)) {
        return component.components[0] as? JComponent
      }
    }
    return null
  }

  override fun setVisible(vcses: Collection<AbstractVcs>?) {
    visibleVcses = vcses?.toSet()
    for (listener in visibleVcsListeners) {
      listener.run()
    }
  }

  private inner class VcsVisiblePredicate(val vcs: AbstractVcs) : ComponentPredicate() {
    override fun addListener(listener: (Boolean) -> Unit) {
      visibleVcsListeners.add(Runnable { listener(invoke()) })
    }

    override fun invoke(): Boolean {
      return visibleVcses?.contains(vcs) ?: true
    }
  }

  companion object {
    fun commitChecksGroupTitle(actionName: @Nls String): @Nls String = message("commit.checks.group", actionName)
  }
}
