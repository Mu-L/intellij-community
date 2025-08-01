// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.core.formatter;

import com.intellij.configurationStore.Property;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.CustomCodeStyleSettings;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.idea.formatter.KotlinObsoleteStyleGuide;
import org.jetbrains.kotlin.idea.formatter.KotlinOfficialStyleGuide;
import org.jetbrains.kotlin.idea.util.ReflectionUtil;

public class KotlinCodeStyleSettings extends CustomCodeStyleSettings {
    @ReflectionUtil.SkipInEquals @Property(externalName = "packages_to_use_import_on_demand") public @NotNull KotlinPackageEntryTable PACKAGES_TO_USE_STAR_IMPORTS = new KotlinPackageEntryTable();

    @ReflectionUtil.SkipInEquals @Property(externalName = "imports_layout") public @NotNull KotlinPackageEntryTable PACKAGES_IMPORT_LAYOUT = new KotlinPackageEntryTable();

    public static final int DEFAULT_NAME_COUNT_TO_USE_STAR_IMPORT = 5;
    public static final int DEFAULT_NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS = 3;

    public boolean SPACE_AROUND_ELVIS = true;
    public boolean SPACE_AROUND_RANGE = false;
    public boolean SPACE_BEFORE_TYPE_COLON = false;
    public boolean SPACE_AFTER_TYPE_COLON = true;
    public boolean SPACE_BEFORE_EXTEND_COLON = true;
    public boolean SPACE_AFTER_EXTEND_COLON = true;
    public boolean INSERT_WHITESPACES_IN_SIMPLE_ONE_LINE_METHOD = true;
    public boolean ALIGN_IN_COLUMNS_CASE_BRANCH = false;
    public boolean LINE_BREAK_AFTER_MULTILINE_WHEN_ENTRY = true;
    public boolean INDENT_BEFORE_ARROW_ON_NEW_LINE = true;
    public boolean SPACE_AROUND_FUNCTION_TYPE_ARROW = true;
    public boolean SPACE_AROUND_WHEN_ARROW = true;
    public boolean SPACE_BEFORE_LAMBDA_ARROW = true;
    public boolean SPACE_BEFORE_WHEN_PARENTHESES = true;
    public boolean LBRACE_ON_NEXT_LINE = false;
    public int NAME_COUNT_TO_USE_STAR_IMPORT = ApplicationManager.getApplication().isUnitTestMode() ? Integer.MAX_VALUE : DEFAULT_NAME_COUNT_TO_USE_STAR_IMPORT;
    public int NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS = ApplicationManager.getApplication().isUnitTestMode() ? Integer.MAX_VALUE : DEFAULT_NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS;
    public boolean IMPORT_NESTED_CLASSES = false;
    public boolean CONTINUATION_INDENT_IN_PARAMETER_LISTS = true;
    public boolean CONTINUATION_INDENT_IN_ARGUMENT_LISTS = true;
    public boolean CONTINUATION_INDENT_FOR_EXPRESSION_BODIES = true;
    public boolean CONTINUATION_INDENT_FOR_CHAINED_CALLS = true;
    public boolean CONTINUATION_INDENT_IN_SUPERTYPE_LISTS = true;
    public boolean CONTINUATION_INDENT_IN_IF_CONDITIONS = true;
    public boolean CONTINUATION_INDENT_IN_ELVIS = true;
    public int BLANK_LINES_AROUND_BLOCK_WHEN_BRANCHES = 0;
    public int WRAP_EXPRESSION_BODY_FUNCTIONS = 0;
    public int WRAP_ELVIS_EXPRESSIONS = 1;
    @CommonCodeStyleSettings.WrapConstant
    public int PROPERTY_CONTEXT_PARAMETERS_WRAP = CommonCodeStyleSettings.WRAP_ALWAYS;
    @CommonCodeStyleSettings.WrapConstant
    public int FUNCTION_CONTEXT_PARAMETERS_WRAP = CommonCodeStyleSettings.WRAP_ALWAYS;
    public boolean IF_RPAREN_ON_NEW_LINE = false;
    public boolean ALLOW_TRAILING_COMMA = false;
    public boolean ALLOW_TRAILING_COMMA_ON_CALL_SITE = false;
    public boolean ALLOW_TRAILING_COMMA_TYPE_PARAMETER_LIST = true;
    public boolean ALLOW_TRAILING_COMMA_DESTRUCTURING_DECLARATION = true;
    public boolean ALLOW_TRAILING_COMMA_WHEN_ENTRY = true;
    public boolean ALLOW_TRAILING_COMMA_FUNCTION_LITERAL = true;
    public boolean ALLOW_TRAILING_COMMA_VALUE_PARAMETER_LIST = true;
    public boolean ALLOW_TRAILING_COMMA_CONTEXT_RECEIVER_LIST = true;
    public boolean ALLOW_TRAILING_COMMA_COLLECTION_LITERAL_EXPRESSION = false;
    public boolean ALLOW_TRAILING_COMMA_TYPE_ARGUMENT_LIST = false;
    public boolean ALLOW_TRAILING_COMMA_INDICES = false;
    public boolean ALLOW_TRAILING_COMMA_VALUE_ARGUMENT_LIST = false;
    public int BLANK_LINES_BEFORE_DECLARATION_WITH_COMMENT_OR_ANNOTATION_ON_SEPARATE_LINE = 1;

    @ReflectionUtil.SkipInEquals
    public String CODE_STYLE_DEFAULTS = null;

    private final boolean isTempForDeserialize;

    public KotlinCodeStyleSettings(CodeStyleSettings container) {
        this(container, false);
    }

    private KotlinCodeStyleSettings(@NotNull CodeStyleSettings container, boolean isTempForDeserialize) {
        super("JetCodeStyleSettings", container);

        this.isTempForDeserialize = isTempForDeserialize;

        // defaults in IDE but not in tests
        if (!ApplicationManager.getApplication().isUnitTestMode()) {
            PACKAGES_TO_USE_STAR_IMPORTS.addEntry(new KotlinPackageEntry("java.util", false));
            PACKAGES_TO_USE_STAR_IMPORTS.addEntry(new KotlinPackageEntry("kotlinx.android.synthetic", true));
            PACKAGES_TO_USE_STAR_IMPORTS.addEntry(new KotlinPackageEntry("io.ktor", true));
        }

        // Many of test data actually depend on this order of imports,
        // that is why we put it here even for test mode
        PACKAGES_IMPORT_LAYOUT.addEntry(KotlinPackageEntry.ALL_OTHER_IMPORTS_ENTRY);
        PACKAGES_IMPORT_LAYOUT.addEntry(new KotlinPackageEntry("java", true));
        PACKAGES_IMPORT_LAYOUT.addEntry(new KotlinPackageEntry("javax", true));
        PACKAGES_IMPORT_LAYOUT.addEntry(new KotlinPackageEntry("kotlin", true));
        PACKAGES_IMPORT_LAYOUT.addEntry(KotlinPackageEntry.ALL_OTHER_ALIAS_IMPORTS_ENTRY);

        if (!isTempForDeserialize) {
            // The new default for the code style is the Kotlin Official Style Guide
            applyKotlinCodeStyle(KotlinOfficialStyleGuide.CODE_STYLE_ID, this, false);
        }
    }

    @Override
    public Object clone() {
        KotlinCodeStyleSettings clone = (KotlinCodeStyleSettings) super.clone();

        clone.PACKAGES_TO_USE_STAR_IMPORTS = new KotlinPackageEntryTable();
        clone.PACKAGES_TO_USE_STAR_IMPORTS.copyFrom(this.PACKAGES_TO_USE_STAR_IMPORTS);

        clone.PACKAGES_IMPORT_LAYOUT = new KotlinPackageEntryTable();
        clone.PACKAGES_IMPORT_LAYOUT.copyFrom(this.PACKAGES_IMPORT_LAYOUT);

        return clone;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof KotlinCodeStyleSettings that)) return false;

        if (!Comparing.equal(PACKAGES_TO_USE_STAR_IMPORTS, that.PACKAGES_TO_USE_STAR_IMPORTS)) return false;
        if (!Comparing.equal(PACKAGES_IMPORT_LAYOUT, that.PACKAGES_IMPORT_LAYOUT)) return false;
        if (!ReflectionUtil.comparePublicNonFinalFieldsWithSkip(this, that)) return false;
        return true;
    }

    @Override
    public void writeExternal(Element parentElement, @NotNull CustomCodeStyleSettings parentSettings) throws WriteExternalException {
        if (CODE_STYLE_DEFAULTS != null) {
            KotlinCodeStyleSettings defaultKotlinCodeStyle = (KotlinCodeStyleSettings) parentSettings.clone();
            // Apply the chosen code style defaults to the value that is compared to when
            // writing the settings to disk to reduce the amount of fields to serialize.
            applyKotlinCodeStyle(CODE_STYLE_DEFAULTS, defaultKotlinCodeStyle, false);

            super.writeExternal(parentElement, defaultKotlinCodeStyle);
        } else {
            super.writeExternal(parentElement, parentSettings);
        }
    }

    @Override
    public void readExternal(Element parentElement) throws InvalidDataException {
        if (isTempForDeserialize) {
            super.readExternal(parentElement);
            return;
        }

        KotlinCodeStyleSettings tempSettings = readExternalToTemp(parentElement);
        String customDefaults = tempSettings.CODE_STYLE_DEFAULTS;

        applyKotlinCodeStyle(customDefaults, this, true);

        // Actual read
        super.readExternal(parentElement);
    }

    private static KotlinCodeStyleSettings readExternalToTemp(Element parentElement) {
        // Read to temp
        KotlinCodeStyleSettings tempSettings = new KotlinCodeStyleSettings(CodeStyleSettings.getDefaults(), true);
        tempSettings.readExternal(parentElement);

        return tempSettings;
    }

    private static void applyKotlinCodeStyle(
            @Nullable String codeStyleId,
            @NotNull KotlinCodeStyleSettings codeStyleSettings,
            Boolean modifyCodeStyle
    ) {
        if (codeStyleId != null) {
            switch (codeStyleId) {
                case KotlinOfficialStyleGuide.CODE_STYLE_ID: {
                    KotlinOfficialStyleGuide.applyToKotlinCustomSettings(codeStyleSettings, modifyCodeStyle);
                    break;
                }
                case KotlinObsoleteStyleGuide.CODE_STYLE_ID: {
                    KotlinObsoleteStyleGuide.applyToKotlinCustomSettings(codeStyleSettings, modifyCodeStyle);
                    break;
                }
            }
        }
    }
}
