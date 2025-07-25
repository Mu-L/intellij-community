// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("ReplaceJavaStaticMethodWithKotlinAnalog")

package org.jetbrains.intellij.build.kotlin

import com.intellij.util.io.Decompressor
import io.opentelemetry.api.trace.Span
import org.jetbrains.intellij.build.BuildOptions
import org.jetbrains.intellij.build.ProductProperties
import org.jetbrains.intellij.build.createBuildTasks
import org.jetbrains.intellij.build.impl.BuildContextImpl
import org.jetbrains.intellij.build.impl.BuildUtils.checkedReplace
import org.jetbrains.intellij.build.impl.PluginLayout
import org.jetbrains.intellij.build.impl.PluginVersionEvaluator
import org.jetbrains.intellij.build.impl.PluginVersionEvaluatorResult
import org.jetbrains.intellij.build.impl.consumeDataByPrefix
import org.jetbrains.jps.model.library.JpsOrderRootType
import java.nio.file.Path

abstract class KotlinPluginBuilder(val kind : KotlinPluginKind = System.getProperty("kotlin.plugin.kind")?.let(KotlinPluginKind::valueOf) ?: KotlinPluginKind.IJ) {
  enum class KotlinPluginKind { IJ, AS, MI, Fleet }

  companion object {
    /**
     * Module which contains META-INF/plugin.xml
     */
    const val MAIN_KOTLIN_PLUGIN_MODULE: String = "kotlin.plugin"
    const val MAIN_FRONTEND_MODULE_NAME: String = "kotlin.frontend.split"

    val MODULES: List<String> = java.util.List.of(
      "kotlin.plugin.common",
      "kotlin.plugin.k1",
      "kotlin.plugin.k2",
      "kotlin.base.util",
      "kotlin.base.indices",
      "kotlin.base.compiler-configuration",
      "kotlin.base.plugin",
      "kotlin.base.psi",
      "kotlin.base.kdoc",
      "kotlin.base.platforms",
      "kotlin.base.facet",
      "kotlin.base.project-structure",
      "kotlin.base.external-build-system",
      "kotlin.base.scripting.k1",
      "kotlin.base.scripting.k2",
      "kotlin.base.scripting.shared",
      "kotlin.base.analysis-api-platform",
      "kotlin.base.analysis",
      "kotlin.base.code-insight",
      "kotlin.base.jps",
      "kotlin.base.analysis-api.utils",
      "kotlin.base.compiler-configuration-ui",
      "kotlin.base.obsolete-compat",
      "kotlin.base.statistics",
      "kotlin.base.fe10.plugin",
      "kotlin.base.fe10.analysis",
      "kotlin.base.fe10.analysis-api-platform",
      "kotlin.base.fe10.kdoc",
      "kotlin.base.fe10.code-insight",
      "kotlin.base.fe10.obsolete-compat",
      "kotlin.base.fe10.project-structure",
      "kotlin.core",
      "kotlin.ide",
      "kotlin.idea",
      "kotlin.fir.frontend-independent",
      "kotlin.jvm.shared",
      "kotlin.jvm.k2",
      "kotlin.jvm.k1",
      "kotlin.compiler-reference-index",
      "kotlin.compiler-plugins.parcelize.common",
      "kotlin.compiler-plugins.parcelize.k1",
      "kotlin.compiler-plugins.parcelize.k2",
      "kotlin.compiler-plugins.parcelize.gradle",
      "kotlin.compiler-plugins.allopen.common.k1",
      "kotlin.compiler-plugins.allopen.gradle",
      "kotlin.compiler-plugins.allopen.maven",
      "kotlin.compiler-plugins.compiler-plugin-support.common",
      "kotlin.compiler-plugins.compiler-plugin-support.gradle",
      "kotlin.compiler-plugins.compiler-plugin-support.maven",
      "kotlin.compiler-plugins.kapt",
      "kotlin.compiler-plugins.kotlinx-serialization.common",
      "kotlin.compiler-plugins.kotlinx-serialization.gradle",
      "kotlin.compiler-plugins.kotlinx-serialization.k2",
      "kotlin.compiler-plugins.kotlinx-serialization.maven",
      "kotlin.compiler-plugins.noarg.common",
      "kotlin.compiler-plugins.noarg.gradle",
      "kotlin.compiler-plugins.noarg.maven",
      "kotlin.compiler-plugins.sam-with-receiver.common",
      "kotlin.compiler-plugins.sam-with-receiver.gradle",
      "kotlin.compiler-plugins.sam-with-receiver.maven",
      "kotlin.compiler-plugins.assignment.common.k1",
      "kotlin.compiler-plugins.assignment.common.k2",
      "kotlin.compiler-plugins.assignment.gradle",
      "kotlin.compiler-plugins.assignment.maven",
      "kotlin.compiler-plugins.lombok.gradle",
      "kotlin.compiler-plugins.lombok.maven",
      "kotlin.compiler-plugins.scripting",
      "kotlin.compiler-plugins.android-extensions-stubs",
      "kotlin.completion.api",
      "kotlin.completion.impl.shared",
      "kotlin.completion.impl.k1",
      "kotlin.completion.impl.k2",
      "kotlin.maven",
      "kotlin.gradle.gradle-tooling",
      "kotlin.gradle.gradle",
      "kotlin.gradle.code-insight-common",
      "kotlin.gradle.gradle-java",
      "kotlin.gradle.gradle-java.k1",
      "kotlin.gradle.gradle-java.k2",
      "kotlin.gradle.scripting.k1",
      "kotlin.gradle.scripting.k2",
      "kotlin.gradle.scripting.shared",
      "kotlin.gradle.code-insight-groovy",
      "kotlin.gradle.code-insight-toml",
      "kotlin.native",
      "kotlin.grazie",
      "kotlin.run-configurations.jvm",
      "kotlin.run-configurations.junit",
      "kotlin.run-configurations.junit-fe10",
      "kotlin.run-configurations.testng",
      "kotlin.formatter",
      "kotlin.repl",
      "kotlin.git",
      "kotlin.base.injection",
      "kotlin.injection.k1",
      "kotlin.injection.k2",
      "kotlin.scripting",
      "kotlin.coverage",
      "kotlin.ml-completion",
      "kotlin.copyright",
      "kotlin.spellchecker",
      "kotlin.jvm-decompiler",
      "kotlin.j2k.shared",
      "kotlin.j2k.k1.new.post-processing",
      "kotlin.j2k.k1.old",
      "kotlin.j2k.k1.old.post-processing",
      "kotlin.j2k.k1.new",
      "kotlin.j2k.k2",
      "kotlin.onboarding",
      "kotlin.onboarding.gradle",
      "kotlin.plugin-updater",
      "kotlin.preferences",
      "kotlin.project-configuration",
      "kotlin.project-wizard.cli",
      "kotlin.project-wizard.core",
      "kotlin.project-wizard.idea",
      "kotlin.project-wizard.idea.k1",
      "kotlin.project-wizard.maven",
      "kotlin.project-wizard.gradle",
      "kotlin.project-wizard.compose",
      "kotlin.jvm-debugger.base.util",
      "kotlin.jvm-debugger.core",
      "kotlin.jvm-debugger.core-fe10",
      "kotlin.jvm-debugger.evaluation",
      "kotlin.jvm-debugger.evaluation.k1",
      "kotlin.jvm-debugger.evaluation.k2",
      "kotlin.jvm-debugger.coroutines",
      "kotlin.jvm-debugger.sequence.k1",
      "kotlin.jvm-debugger.eval4j",
      "kotlin.uast.uast-kotlin-base",
      "kotlin.uast.uast-kotlin",
      "kotlin.uast.uast-kotlin-idea-base",
      "kotlin.uast.uast-kotlin-idea",
      "kotlin.i18n",
      "kotlin.migration",
      "kotlin.inspections",
      "kotlin.inspections-fe10",
      "kotlin.features-trainer",
      "kotlin.base.fir.analysis-api-platform",
      "kotlin.base.fir.code-insight",
      "kotlin.base.fir.project-structure",
      "kotlin.base.fir.scripting",
      "kotlin.code-insight.api",
      "kotlin.code-insight.utils",
      "kotlin.code-insight.intentions.shared",
      "kotlin.code-insight.inspections.shared",
      "kotlin.code-insight.impl-base",
      "kotlin.code-insight.descriptions",
      "kotlin.code-insight.fixes.k2",
      "kotlin.code-insight.intentions.k1",
      "kotlin.code-insight.intentions.k2",
      "kotlin.code-insight.inspections.k1",
      "kotlin.code-insight.inspections.k2",
      "kotlin.code-insight.k1",
      "kotlin.code-insight.k2",
      "kotlin.code-insight.override-implement.shared",
      "kotlin.code-insight.override-implement.k1",
      "kotlin.code-insight.override-implement.k2",
      "kotlin.code-insight.live-templates.shared",
      "kotlin.code-insight.live-templates.k1",
      "kotlin.code-insight.live-templates.k2",
      "kotlin.code-insight.postfix-templates.k1",
      "kotlin.code-insight.postfix-templates.k2",
      "kotlin.code-insight.structural-search.k1",
      "kotlin.code-insight.structural-search.k2",
      "kotlin.code-insight.line-markers.shared",
      "kotlin.code-insight.line-markers.k2",
      "kotlin.fir",
      "kotlin.searching.k2",
      "kotlin.searching.base",
      "kotlin.highlighting.shared",
      "kotlin.highlighting.k1",
      "kotlin.highlighting.k2",
      "kotlin.uast.uast-kotlin.k2",
      "kotlin.uast.uast-kotlin-idea.k2",
      "kotlin.navigation",
      "kotlin.refactorings.common",
      "kotlin.refactorings.k2",
      "kotlin.refactorings.move.k2",
      "kotlin.refactorings.rename.k2",
      "kotlin.performanceExtendedPlugin",
      "kotlin.bundled-compiler-plugins-support",
      "kotlin.jsr223",
      "kotlin.k2.internal",
      "kotlin.base.serialization",
    )

    private val KOTLIN_SCRIPTING_LIBRARIES = java.util.List.of(
      "kotlinc.kotlin-script-runtime",
      "kotlinc.kotlin-scripting-jvm"
    )

    private val MODULES_SHARED_WITH_CLIENT = java.util.List.of(
      "kotlin.base.resources",
      "kotlin.base.code-insight.minimal",
      "kotlin.highlighting.minimal",
      "kotlin.formatter.minimal"
    )

    private val LIBRARIES = java.util.List.of(
      "kotlinc.analysis-api-platform-interface",
      "kotlinc.analysis-api",
      "kotlinc.analysis-api-fe10",
      "kotlinc.analysis-api-impl-base",
      "kotlinc.kotlin-scripting-compiler-impl",
      "kotlinc.kotlin-scripting-common",
      "kotlinc.kotlin-scripting-dependencies",
      "kotlinc.kotlin-gradle-statistics",
      "kotlinc.analysis-api-k2",
      "kotlinc.kotlin-compiler-fir",
      "kotlinc.low-level-api-fir",
      "kotlinc.symbol-light-classes",
      "kotlin-metadata",
    ) + KOTLIN_SCRIPTING_LIBRARIES

    private val GRADLE_TOOLING_MODULES = java.util.List.of(
      "kotlin.base.project-model",
      "kotlin.gradle.gradle-tooling.impl",
    )

    private val GRADLE_TOOLING_LIBRARIES = java.util.List.of(
      "kotlin-gradle-plugin-idea",
      "kotlin-gradle-plugin-idea-proto",
      "kotlin-tooling-core",
    )

    private val COMPILER_PLUGINS = java.util.List.of(
      "kotlinc.allopen-compiler-plugin",
      "kotlinc.noarg-compiler-plugin",
      "kotlinc.sam-with-receiver-compiler-plugin",
      "kotlinc.assignment-compiler-plugin",
      "kotlinc.scripting-compiler-plugin",
      "kotlinc.kotlinx-serialization-compiler-plugin",
      "kotlinc.parcelize-compiler-plugin",
      "kotlinc.lombok-compiler-plugin",
      "kotlinc.compose-compiler-plugin",
      "kotlinc.js-plain-objects-compiler-plugin",
      "kotlinc.kotlin-dataframe-compiler-plugin",
    )
  }

  open fun kotlinPlugin(addition: ((PluginLayout.PluginLayoutSpec) -> Unit)? = null): PluginLayout {
    return PluginLayout.plugin(MAIN_KOTLIN_PLUGIN_MODULE) { spec ->
      spec.directoryName = "Kotlin"
      spec.mainJarName = "kotlin-plugin.jar"

      for (moduleName in MODULES_SHARED_WITH_CLIENT) {
        spec.withModule(moduleName, "kotlin-plugin-shared.jar")
      }

      for (moduleName in MODULES) {
        spec.withModule(moduleName)
      }
      for (libraryName in LIBRARIES) {
        spec.withProjectLibraryUnpackedIntoJar(libraryName, spec.mainJarName)
      }

      val toolingJarName = "kotlin-gradle-tooling.jar"
      for (moduleName in GRADLE_TOOLING_MODULES) {
        spec.withModule(moduleName, toolingJarName)
      }
      for (library in GRADLE_TOOLING_LIBRARIES) {
        spec.withProjectLibraryUnpackedIntoJar(library, toolingJarName)
      }

      for (library in COMPILER_PLUGINS) {
        spec.withProjectLibrary(library)
      }

      withKotlincKotlinCompilerCommonLibrary(spec, MAIN_KOTLIN_PLUGIN_MODULE)

      spec.withProjectLibrary("kotlinc.kotlin-compiler-fe10")
      spec.withProjectLibrary("kotlinc.kotlin-compiler-ir")

      spec.withProjectLibrary("kotlinc.kotlin-jps-plugin-classpath", "jps/kotlin-jps-plugin.jar")
      spec.withProjectLibrary("kotlinc.kotlin-jps-common")
      //noinspection SpellCheckingInspection
      spec.withProjectLibrary("vavr")
      spec.withProjectLibrary("javax-inject")
      spec.withProjectLibrary("jackson-dataformat-toml")

      withKotlincInPluginDirectory(spec = spec)
      // TODO: KTIJ-32993
      withKotlincInPluginDirectory(libName = "kotlin-ide-dist", target = "kotlinc.ide", spec = spec)

      spec.withCustomVersion(PluginVersionEvaluator { _, ideBuildVersion, _ ->
        // in kt-branches we have own since and until versions
        val sinceBuild = System.getProperty("kotlin.plugin.since")
        val untilBuild = System.getProperty("kotlin.plugin.until")
        val sinceUntil = if (sinceBuild != null && untilBuild != null) sinceBuild to untilBuild else null
        if (ideBuildVersion.contains("IJ")) {
          // TC configurations that are inherited from AbstractKotlinIdeArtifact.
          // In this environment, ideBuildVersion equals to build number.
          // The ideBuildVersion looks like XXX.YYYY.ZZ-IJ
          val version = ideBuildVersion.replace("IJ", kind.toString())
          Span.current().addEvent("Kotlin plugin IJ version: $version")
          PluginVersionEvaluatorResult(pluginVersion = version, sinceUntil = sinceUntil)
        }
        else {
          // IJ installer configurations.
          PluginVersionEvaluatorResult(pluginVersion = "$ideBuildVersion-$kind", sinceUntil = sinceUntil)
        }
      })

      if (kind == KotlinPluginKind.AS) {
        spec.withRawPluginXmlPatcher { text, _ ->
          checkedReplace(
            oldText = text,
            regex = "<!-- IJ/AS-DEPENDENCY-PLACEHOLDER -->",
            newText = """<plugin id="com.intellij.modules.androidstudio"/>""",
          )
        }
      }

      addition?.invoke(spec)
    }
  }

  suspend fun build(home: Path, properties: ProductProperties) {
    val context = BuildContextImpl.createContext(
      setupTracer = true,
      projectHome = home,
      productProperties = properties,
      options = BuildOptions(enableEmbeddedFrontend = false)
    )
    createBuildTasks(context).buildNonBundledPlugins(listOf(MAIN_KOTLIN_PLUGIN_MODULE))
  }

  /**
   * A special plugin for JetBrains Client
   */
  fun kotlinFrontendPlugin(): PluginLayout {
    return PluginLayout.plugin(MAIN_FRONTEND_MODULE_NAME) { spec ->
      spec.withModules(MODULES_SHARED_WITH_CLIENT)
      spec.withProjectLibrary("kotlinc.kotlin-compiler-common")
    }
  }

  fun kotlinScriptingPlugin(addition: ((PluginLayout.PluginLayoutSpec) -> Unit)? = null): PluginLayout {
    val mainModuleName = "kotlin.scripting-plugin"
    return PluginLayout.pluginAutoWithCustomDirName(mainModuleName) { spec ->
      spec.directoryName = "KotlinScripting"
      spec.mainJarName = "kotlin-scripting-plugin.jar"

      spec.withModule("kotlin.jsr223")

      withKotlincKotlinCompilerCommonLibrary(spec, mainModuleName)
      spec.withProjectLibrary("kotlinc.kotlin-compiler-fe10")
      withKotlincInPluginDirectory(spec = spec)
      // TODO: KTIJ-32993
      withKotlincInPluginDirectory(libName = "kotlin-ide-dist", target = "kotlinc.ide", spec = spec)

      addition?.invoke(spec)
    }
  }
}

private fun withKotlincKotlinCompilerCommonLibrary(spec: PluginLayout.PluginLayoutSpec, mainPluginModule: String) {
  val kotlincKotlinCompilerCommon = "kotlinc.kotlin-compiler-common"
  spec.withProjectLibrary(kotlincKotlinCompilerCommon)

  spec.withPatch { patcher, context ->
    val library = context.project.libraryCollection.findLibrary(kotlincKotlinCompilerCommon)!!
    val jars = library.getPaths(JpsOrderRootType.COMPILED)
    if (jars.size != 1) {
      throw IllegalStateException("$kotlincKotlinCompilerCommon is expected to have only one jar")
    }

    consumeDataByPrefix(jars[0], "META-INF/extensions/") { name, data ->
      patcher.patchModuleOutput(moduleName = mainPluginModule, path = name, content = data)
    }
  }
}

private fun withKotlincInPluginDirectory(libName: String = "kotlin-dist", target: String = "kotlinc", spec: PluginLayout.PluginLayoutSpec) {
  spec.withGeneratedResources { targetDir, context ->
    val distLibName = "kotlinc.$libName"
    val library = context.project.libraryCollection.findLibrary(distLibName)!!
    val jars = library.getPaths(JpsOrderRootType.COMPILED)
    if (jars.size != 1) {
      throw IllegalStateException("$distLibName is expected to have only one jar")
    }
    Decompressor.Zip(jars[0]).extract(targetDir.resolve(target))
  }
}

object CommunityKotlinPluginBuilder : KotlinPluginBuilder()
