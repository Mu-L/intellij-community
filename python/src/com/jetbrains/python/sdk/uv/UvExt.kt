// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.sdk.uv

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.NlsSafe
import com.intellij.python.pyproject.PY_PROJECT_TOML
import com.intellij.util.PathUtil
import com.jetbrains.python.errorProcessing.PyResult
import com.jetbrains.python.icons.PythonIcons
import com.jetbrains.python.sdk.PythonSdkUtil
import com.jetbrains.python.sdk.createSdk
import com.jetbrains.python.sdk.getOrCreateAdditionalData
import com.jetbrains.python.sdk.uv.impl.createUvCli
import com.jetbrains.python.sdk.uv.impl.createUvLowLevel
import java.nio.file.Path
import javax.swing.Icon
import kotlin.io.path.exists
import kotlin.io.path.pathString


internal val Sdk.isUv: Boolean
  get() {
    if (!PythonSdkUtil.isPythonSdk(this)) {
      return false
    }
    return getOrCreateAdditionalData() is UvSdkAdditionalData
  }

internal val Sdk.uvUsePackageManagement: Boolean
  get() {
    if (!PythonSdkUtil.isPythonSdk(this)) {
      return false
    }

    val data = getOrCreateAdditionalData() as? UvSdkAdditionalData ?: return false
    return data.usePip
  }

internal fun suggestedSdkName(basePath: Path): @NlsSafe String {
  return "uv (${PathUtil.getFileName(basePath.pathString)})"
}

val UV_ICON: Icon = PythonIcons.UV


suspend fun setupNewUvSdkAndEnv(
  workingDir: Path,
  existingSdks: List<Sdk>,
  basePython: Path?,
): PyResult<Sdk> {
  val toml = workingDir.resolve(PY_PROJECT_TOML)
  val init = !toml.exists()

  val uv = createUvLowLevel(workingDir, createUvCli())
  val envExecutable = uv.initializeEnvironment(init, basePython)
    .getOr {
      return it
    }

  return setupExistingEnvAndSdk(envExecutable, workingDir, false, workingDir, existingSdks)
}

suspend fun setupExistingEnvAndSdk(
  envExecutable: Path,
  envWorkingDir: Path,
  usePip: Boolean,
  projectDir: Path,
  existingSdks: List<Sdk>,
): PyResult<Sdk> {
  val sdk = createSdk(
    envExecutable,
    existingSdks,
    projectDir.toString(),
    suggestedSdkName(envWorkingDir),
    UvSdkAdditionalData(envWorkingDir, usePip))

  return sdk
}