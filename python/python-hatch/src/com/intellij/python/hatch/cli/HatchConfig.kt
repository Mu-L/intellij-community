// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.hatch.cli

import com.intellij.python.community.execService.ZeroCodeStdoutTransformer
import com.intellij.python.hatch.runtime.HatchRuntime
import com.jetbrains.python.errorProcessing.PyResult

/**
 * Manage environment dependencies
 */
class HatchConfig(runtime: HatchRuntime) : HatchCommand("config", runtime) {
  /**
   * Open the config location in your file manager
   */
  suspend fun explore(): PyResult<String> {
    return executeAndHandleErrors("explore", transformer = ZeroCodeStdoutTransformer)
  }

  /**
   * Show the location of the config file
   */
  suspend fun find(): PyResult<String> {
    return executeAndHandleErrors("find", transformer = ZeroCodeStdoutTransformer)
  }

  /**
   * Restore the config file to default settings
   */
  suspend fun restore(): PyResult<String> {
    return executeAndHandleErrors("restore", transformer = ZeroCodeStdoutTransformer)
  }

  /**
   * Assign values to config file entries
   */
  suspend fun set(key: String, value: String): PyResult<String> {
    return executeAndHandleErrors("set", key, value, transformer = ZeroCodeStdoutTransformer)
  }

  /**
   * Show the contents of the config file
   *
   * @param all Do not scrub secret fields
   */
  suspend fun show(all: Boolean? = null): PyResult<String> {
    val options = listOf(all to "--all").makeOptions()
    return executeAndHandleErrors("show", *options, transformer = ZeroCodeStdoutTransformer)
  }

  /**
   * Update the config file with any new fields
   */
  suspend fun update(): PyResult<String> {
    return executeAndHandleErrors("update", transformer = ZeroCodeStdoutTransformer)
  }
}