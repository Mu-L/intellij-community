// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.base.compilerPreferences.facet

import com.intellij.facet.ui.*
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.ShowSettingsUtilImpl
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NlsSafe
import com.intellij.ui.HoverHyperlinkLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.ThreeStateCheckBox
import com.intellij.util.xmlb.annotations.Transient
import org.jetbrains.kotlin.cli.common.arguments.*
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.idea.base.compilerPreferences.KotlinBaseCompilerConfigurationUiBundle
import org.jetbrains.kotlin.idea.base.compilerPreferences.configuration.KotlinCompilerConfigurableTab
import org.jetbrains.kotlin.idea.base.util.onTextChange
import org.jetbrains.kotlin.idea.compiler.configuration.Kotlin2JsCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.compiler.configuration.Kotlin2JvmCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettings
import org.jetbrains.kotlin.idea.facet.KotlinFacetConfiguration
import org.jetbrains.kotlin.idea.facet.KotlinFacetModificationTracker
import org.jetbrains.kotlin.idea.facet.getExposedFacetFields
import org.jetbrains.kotlin.platform.*
import org.jetbrains.kotlin.platform.jvm.JdkPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.platform.jvm.isJvm
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.*
import javax.swing.border.EmptyBorder
import kotlin.reflect.full.findAnnotation

class KotlinFacetEditorGeneralTab(
    private val configuration: KotlinFacetConfiguration,
    private val editorContext: FacetEditorContext,
    private val validatorsManager: FacetValidatorsManager
) : FacetEditorTab() {

    //TODO(auskov): remove this hack as far as inconsistent equals in JdkPlatform is removed
    // what it actually does: if version of JVM target platform changed the TargetPlatform will
    // return true because equals in JvmPlatform is declared in the following way:
    // override fun equals(other: Any?): Boolean = other is JdkPlatform
    class TargetPlatformWrapper(val targetPlatform: TargetPlatform) {

        override fun equals(other: Any?): Boolean {
            if (other is TargetPlatformWrapper) {
                if (this.targetPlatform == other.targetPlatform) {
                    return if (this.targetPlatform.size == 1) {
                        this.targetPlatform.componentPlatforms.singleOrNull() === other.targetPlatform.componentPlatforms.singleOrNull()
                    } else {
                        true
                    }
                }
            }
            return false
        }
    }

    class EditorComponent(
        private val project: Project,
        private val configuration: KotlinFacetConfiguration?
    ) : JPanel(BorderLayout()), Disposable {
        private val isMultiEditor: Boolean
            get() = configuration == null

        private lateinit var editableCommonArguments: CommonCompilerArguments
        private lateinit var editableJvmArguments: K2JVMCompilerArguments
        private lateinit var editableJsArguments: K2JSCompilerArguments
        private lateinit var editableCompilerSettings: CompilerSettings

        lateinit var compilerConfigurable: KotlinCompilerConfigurableTab
            private set

        lateinit var useProjectSettingsCheckBox: ThreeStateCheckBox

        // UI components related to MPP target platforms
        lateinit var targetPlatformSelectSingleCombobox: ComboBox<TargetPlatformWrapper>
        lateinit var dependsOnLabel: JLabel
        lateinit var targetPlatformWrappers: List<TargetPlatformWrapper>
        lateinit var targetPlatformLabel: JLabel //JTextField?
        var targetPlatformsCurrentlySelected: TargetPlatform? = null
        private lateinit var projectSettingsLink: HoverHyperlinkLabel
        private lateinit var targetPlatformFacetLabel: JLabel
        private lateinit var useProjectSettingsWarningLabel: JLabel

        @Volatile
        private var isInTargetPlatformChangeAction = false

        private fun FormBuilder.addTargetPlatformComponents(): FormBuilder {
            targetPlatformFacetLabel = JLabel(KotlinBaseCompilerConfigurationUiBundle.message("facet.label.text.target.platform"))
            return if (configuration?.settings?.mppVersion?.isHmpp == true) {
                targetPlatformLabel.toolTipText =
                    KotlinBaseCompilerConfigurationUiBundle.message("facet.label.text.the.project.is.imported.from.external.build.system.and.could.not.be.edited")
                addLabeledComponent(
                    KotlinBaseCompilerConfigurationUiBundle.message("facet.label.text.selected.target.platforms"), targetPlatformLabel
                )
            } else {
                addLabeledComponent(
                    targetPlatformFacetLabel, targetPlatformSelectSingleCombobox
                )
            }
        }

        // Fixes sorting of JVM versions.
        // JVM 1.6, ... JVM 1.8 -> unchanged
        // JVM 9 -> JVM 1.9
        // JVM 11.. -> unchanged
        // As result JVM 1.8 < JVM 1.9 < JVM 11 in UI representation
        private fun unifyJvmVersion(version: String) = if (version.equals("JVM 9")) "JVM 1.9" else version

        // Returns maxRowsCount for combobox.
        // Try to show whole the list at one, but do not show more than 15 elements at once. 10 elements returned otherwise
        private fun targetPlatformsComboboxRowsCount(targetPlatforms: Int) =
            if (targetPlatforms <= 15) {
                targetPlatforms
            } else {
                10
            }

        fun initialize() {
            class CommonCompilerArgumentsHolder: CommonCompilerArguments() {
                override fun copyOf(): Freezable = copyCommonCompilerArguments(this, CommonCompilerArgumentsHolder())

                @get:Transient
                @field:kotlin.jvm.Transient
                override val configurator: CommonCompilerArgumentsConfigurator = CommonCompilerArgumentsConfigurator()
            }
            if (isMultiEditor) {
                editableCommonArguments = CommonCompilerArgumentsHolder()
                editableJvmArguments = K2JVMCompilerArguments()
                editableJsArguments = K2JSCompilerArguments()
                editableCompilerSettings = CompilerSettings()
            } else {
                editableCommonArguments = configuration!!.settings.compilerArguments!!
                editableJvmArguments = editableCommonArguments as? K2JVMCompilerArguments
                    ?: Kotlin2JvmCompilerArgumentsHolder.getInstance(project).settings.unfrozen()
                editableJsArguments = editableCommonArguments as? K2JSCompilerArguments
                    ?: Kotlin2JsCompilerArgumentsHolder.getInstance(project).settings.unfrozen()
                editableCompilerSettings = configuration.settings.compilerSettings!!
            }

            compilerConfigurable = KotlinCompilerConfigurableTab(
                project,
                editableCommonArguments,
                editableJsArguments,
                editableJvmArguments,
                editableCompilerSettings,
                null,
                false,
                isMultiEditor
            )

            useProjectSettingsCheckBox = ThreeStateCheckBox(KotlinBaseCompilerConfigurationUiBundle.message("facet.checkbox.text.use.project.settings")).apply {
                isThirdStateEnabled = isMultiEditor
            }
            useProjectSettingsWarningLabel = JLabel().apply {
                icon = AllIcons.General.WarningDialog
                text = KotlinBaseCompilerConfigurationUiBundle.message("facet.label.text.project.settings.that.are.used.for.this.facet")
                iconTextGap = 5
                isVisible = false
            }
            dependsOnLabel = JLabel()

            targetPlatformWrappers = CommonPlatforms.allDefaultTargetPlatforms.sortedBy { unifyJvmVersion(it.oldFashionedDescription) }
                .map { TargetPlatformWrapper(it) }
            targetPlatformLabel = JLabel() //JTextField()? targetPlatformLabel.isEditable = false
            targetPlatformSelectSingleCombobox =
                ComboBox(targetPlatformWrappers.toTypedArray()).apply {
                    setRenderer(object : DefaultListCellRenderer() {
                        override fun getListCellRendererComponent(
                            list: JList<*>?,
                            value: Any?,
                            index: Int,
                            isSelected: Boolean,
                            cellHasFocus: Boolean
                        ): Component {
                            return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                                val specificPlatform = (value as? TargetPlatformWrapper)?.targetPlatform?.componentPlatforms?.singleOrNull()
                                text = specificPlatform?.oldFashionedDescription ?: KotlinBaseCompilerConfigurationUiBundle.message("facet.text.multiplatform")

                                if (specificPlatform is JdkPlatform && specificPlatform.targetVersion == JvmTarget.JVM_1_6) {
                                    text += " " + KotlinBaseCompilerConfigurationUiBundle.message("deprecated.jvm.version")
                                }
                            }
                        }
                    })
                }
            targetPlatformSelectSingleCombobox.maximumRowCount =
                targetPlatformsComboboxRowsCount(targetPlatformWrappers.size)
            projectSettingsLink = HoverHyperlinkLabel(KotlinBaseCompilerConfigurationUiBundle.message("facet.link.text.edit.project.settings")).apply {
                addHyperlinkListener {
                    ShowSettingsUtilImpl.showSettingsDialog(project, compilerConfigurable.id, "")
                    if (useProjectSettingsCheckBox.isSelected) {
                        updateCompilerConfigurable()
                    }
                }
            }

            val contentPanel = FormBuilder
                .createFormBuilder()
                .addComponent(JPanel(BorderLayout()).apply {
                    add(useProjectSettingsCheckBox, BorderLayout.WEST)
                    add(projectSettingsLink, BorderLayout.EAST)
                })
                .addComponent(useProjectSettingsWarningLabel)
                .addTargetPlatformComponents()
                .addComponent(dependsOnLabel)
                .addComponent(compilerConfigurable.createComponent()!!.apply {
                    border = null
                })
                .panel
                .apply {
                    border = EmptyBorder(10, 10, 10, 10)
                }
            add(contentPanel, BorderLayout.NORTH)

            useProjectSettingsCheckBox.addActionListener {
                updateCompilerConfigurable()
            }
            targetPlatformSelectSingleCombobox.addActionListener {
                isInTargetPlatformChangeAction = true
                updateCompilerConfigurable()
                isInTargetPlatformChangeAction = false
            }
        }

        internal fun updateCompilerConfigurable() {
            val useProjectSettings = useProjectSettingsCheckBox.isSelected

            useProjectSettingsWarningLabel.isVisible = useProjectSettings
            compilerConfigurable.setTargetPlatform(getChosenPlatform()?.idePlatformKind)
            compilerConfigurable.setEnabled(!useProjectSettings)
            if (useProjectSettings) {
                compilerConfigurable.commonCompilerArguments = KotlinCommonCompilerArgumentsHolder.getInstance(project).settings.unfrozen()
                compilerConfigurable.k2jvmCompilerArguments = Kotlin2JvmCompilerArgumentsHolder.getInstance(project).settings.unfrozen()
                compilerConfigurable.k2jsCompilerArguments = Kotlin2JsCompilerArgumentsHolder.getInstance(project).settings.unfrozen()
                compilerConfigurable.compilerSettings = KotlinCompilerSettings.getInstance(project).settings.unfrozen()
            } else {
                compilerConfigurable.commonCompilerArguments = editableCommonArguments
                compilerConfigurable.k2jvmCompilerArguments = editableJvmArguments
                compilerConfigurable.k2jsCompilerArguments = editableJsArguments
                compilerConfigurable.compilerSettings = editableCompilerSettings
            }
            compilerConfigurable.reset()
            setupTargetPlatformCombobox(useProjectSettings)
        }

        private fun setupTargetPlatformCombobox(useProjectSettings: Boolean) {
            val target = if (useProjectSettings) {
                targetPlatformWrappers.toTypedArray()
                    .mapNotNull { it.targetPlatform.componentPlatforms.singleOrNull() }
                    .filter { it.platformName == "JVM" && it.targetPlatformVersion.description == compilerConfigurable.selectedJvmVersion }
            } else {
                configuration?.settings?.targetPlatform
            }

            if (useProjectSettings || (!isInTargetPlatformChangeAction && !target.isNullOrEmpty())) {
                targetPlatformSelectSingleCombobox.selectedItem = TargetPlatform(target!!.toSet()).let {
                    val index = targetPlatformWrappers.indexOf(TargetPlatformWrapper(it))
                    if (index >= 0) {
                        targetPlatformWrappers[index]
                    } else {
                        null
                    }
                }
            }

            targetPlatformSelectSingleCombobox.isEnabled = !useProjectSettings
            targetPlatformFacetLabel.isEnabled = !useProjectSettings
        }

        fun getChosenPlatform(): TargetPlatform? {
            return if (configuration?.settings?.mppVersion?.isHmpp == true) {
                targetPlatformsCurrentlySelected
            } else {
                targetPlatformSelectSingleCombobox.selectedItemTyped?.targetPlatform
            }
        }

        override fun dispose() {
            if (::compilerConfigurable.isInitialized) {
                compilerConfigurable.disposeUIResources()
            }
        }
    }

    inner class ArgumentConsistencyValidator : FacetEditorValidator() {
        override fun check(): ValidationResult {
            val platform = editor.getChosenPlatform() ?: return ValidationResult(
                KotlinBaseCompilerConfigurationUiBundle.message("facet.error.text.at.least.one.target.platform.should.be.selected")
            )
            val primaryArguments = platform.createArguments {
                editor.compilerConfigurable.applyTo(
                    this,
                    this as? K2JVMCompilerArguments ?: K2JVMCompilerArguments(),
                    this as? K2JSCompilerArguments ?: K2JSCompilerArguments(),
                    CompilerSettings()
                )
            }
            val argumentClass = primaryArguments.javaClass
            val additionalArguments = argumentClass.getDeclaredConstructor().newInstance().apply {
                parseCommandLineArguments(splitArgumentString(editor.compilerConfigurable.additionalArgsOptionsField.text), this)
                validateArguments(errors)?.let { message -> return ValidationResult(message) }
            }
            val emptyArguments = argumentClass.getDeclaredConstructor().newInstance()
            val fieldNamesToCheck = getExposedFacetFields(platform.idePlatformKind)

            val propertiesToCheck = collectProperties(argumentClass.kotlin, false).filter { it.name in fieldNamesToCheck }
            val overridingArguments = ArrayList<String>()
            val redundantArguments = ArrayList<String>()
            for (property in propertiesToCheck) {
                val additionalValue = property.get(additionalArguments)
                if (additionalValue != property.get(emptyArguments)) {
                    val argumentInfo = property.findAnnotation<Argument>() ?: continue
                    val addTo = if (additionalValue != property.get(primaryArguments)) overridingArguments else redundantArguments
                    addTo += "<strong>" + argumentInfo.value.first() + "</strong>"
                }
            }
            if (overridingArguments.isNotEmpty() || redundantArguments.isNotEmpty()) {
                @NlsSafe
                val message = buildString {
                    if (overridingArguments.isNotEmpty()) {
                        append(
                            KotlinBaseCompilerConfigurationUiBundle.message(
                                "facet.text.following.arguments.override.facet.settings",
                                overridingArguments.joinToString()
                            )
                        )
                    }
                    if (redundantArguments.isNotEmpty()) {
                        if (isNotEmpty()) {
                            append("<br/>")
                        }
                        append(
                            KotlinBaseCompilerConfigurationUiBundle.message(
                                "facet.text.following.arguments.are.redundant",
                                redundantArguments.joinToString()
                            )
                        )
                    }
                }
                return ValidationResult(message)
            }

            return ValidationResult.OK
        }
    }

    private var isInitialized = false
    val editor by lazy { EditorComponent(editorContext.project, configuration) }

    private var enableValidation = false

    private fun JTextField.validateOnChange() {
        onTextChange { doValidate() }
    }

    private fun AbstractButton.validateOnChange() {
        addChangeListener { doValidate() }
    }

    private fun JComboBox<*>.validateOnChange() {
        addActionListener { doValidate() }
    }

    private fun validateOnce(body: () -> Unit) {
        enableValidation = false
        body()
        enableValidation = true
        doValidate()
    }

    private fun doValidate() {
        if (enableValidation) {
            validatorsManager.validate()
        }
    }

    private fun initializeIfNeeded() {
        if (isInitialized) return

        editor.initialize()

        for (creator in KotlinFacetValidatorCreator.EP_NAME.extensions) {
            validatorsManager.registerValidator(creator.create(editor, validatorsManager, editorContext))
        }

        validatorsManager.registerValidator(ArgumentConsistencyValidator())

        with(editor.compilerConfigurable) {
            reportWarningsCheckBox.validateOnChange()
            additionalArgsOptionsField.textField.validateOnChange()
            generateSourceMapsCheckBox.validateOnChange()
            outputDirectory.textField.validateOnChange()
            copyRuntimeFilesCheckBox.validateOnChange()
            moduleKindComboBox.validateOnChange()
            languageVersionComboBox.addActionListener {
                doValidate()
            }
            apiVersionComboBox.validateOnChange()
        }
        editor.targetPlatformSelectSingleCombobox.validateOnChange()

        editor.updateCompilerConfigurable()
        isInitialized = true

        reset()
    }

    override fun isModified(): Boolean {
        if (!isInitialized) return false
        if (editor.useProjectSettingsCheckBox.isSelected != configuration.settings.useProjectSettings) return true
        val chosenPlatform = editor.getChosenPlatform()
        if (chosenPlatform != configuration.settings.targetPlatform) return true

        // work-around for hacked equals in JvmPlatform
        if (!configuration.settings.mppVersion.isHmpp) {
            if (!editor.useProjectSettingsCheckBox.isSelected
                && configuration.settings.targetPlatform?.let { TargetPlatformWrapper(it) } != editor.targetPlatformSelectSingleCombobox.selectedItemTyped) {
                return true
            }
        }
        val chosenSingle = chosenPlatform?.componentPlatforms?.singleOrNull()
        if (chosenSingle != null && chosenSingle == JvmPlatforms.defaultJvmPlatform) {
            if (chosenSingle !== configuration.settings.targetPlatform) {
                return true
            }
        }
        return !editor.useProjectSettingsCheckBox.isSelected && editor.compilerConfigurable.isModified
    }

    override fun reset() {
        if (!isInitialized) return
        validateOnce {
            editor.useProjectSettingsCheckBox.isSelected = configuration.settings.useProjectSettings
            editor.targetPlatformsCurrentlySelected = configuration.settings.targetPlatform
            editor.targetPlatformLabel.text =
                editor.targetPlatformsCurrentlySelected?.componentPlatforms?.map { it.oldFashionedDescription.trim() }?.joinToString(", ")
                    ?: "<none>"
            editor.dependsOnLabel.isVisible = configuration.settings.dependsOnModuleNames.isNotEmpty()
            editor.dependsOnLabel.text =
                KotlinBaseCompilerConfigurationUiBundle.message(
                    "facets.editor.general.tab.label.depends.on.0",
                    configuration.settings.dependsOnModuleNames.joinToString()
                )

            editor.targetPlatformSelectSingleCombobox.selectedItem = configuration.settings.targetPlatform?.let {
                val index = editor.targetPlatformWrappers.indexOf(TargetPlatformWrapper(it))
                if (index >= 0) {
                    editor.targetPlatformWrappers[index]
                } else {
                    null
                }
            }
            editor.compilerConfigurable.reset()
            editor.updateCompilerConfigurable()
        }
    }

    override fun apply() {
        validateOnce {
            editor.compilerConfigurable.apply()
            configuration.settings.compilerArguments = editor.compilerConfigurable.commonCompilerArguments
            with(configuration.settings) {
                useProjectSettings = editor.useProjectSettingsCheckBox.isSelected
                editor.getChosenPlatform()?.let {
                    if (it != targetPlatform ||
                        isModified // work-around due to hacked equals
                    ) {
                        val platformArguments = when {
                            it.isJvm() -> editor.compilerConfigurable.k2jvmCompilerArguments
                            it.isJs() -> editor.compilerConfigurable.k2jsCompilerArguments
                            else -> null
                        }
                        compilerArguments = it.createArguments {
                            if (platformArguments != null) {
                                mergeBeans(platformArguments, this)
                            }
                            copyInheritedFields(compilerArguments!!, this)
                        }
                    }
                }

                configuration.settings.targetPlatform = editor.getChosenPlatform()
                updateMergedArguments()

                // Force code analysis with modified settings
                runWriteAction { KotlinFacetModificationTracker.getInstance(editorContext.project).incModificationCount() }
            }
        }
    }

    override fun getDisplayName() = KotlinBaseCompilerConfigurationUiBundle.message("facet.name.general")

    override fun createComponent(): JComponent {
        initializeIfNeeded()
        return editor
    }

    override fun disposeUIResources() {
        if (isInitialized) {
            Disposer.dispose(editor)
        }
    }
}

@Suppress("UNCHECKED_CAST")
private val <T> ComboBox<T>.selectedItemTyped: T?
    get() = selectedItem as T?
