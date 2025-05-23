// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.execution.application;

import com.intellij.execution.JavaExecutionUtil;
import com.intellij.execution.configuration.BrowseModuleValueActionListener;
import com.intellij.execution.ui.ClassBrowser;
import com.intellij.icons.AllIcons;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComponentWithBrowseButton;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.ExtendableEditorSupport;
import com.intellij.ui.components.fields.ExtendableTextComponent;
import com.intellij.util.concurrency.NonUrgentExecutor;
import com.intellij.util.containers.ConcurrentFactoryMap;
import com.intellij.util.indexing.DumbModeAccessType;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Map;

public final class ClassEditorField extends EditorTextField {

  private final Map<String, String> myJvmNames = ConcurrentFactoryMap.createMap(className -> getJvmName(className));

  public void setClassName(String className) {
    String qName = getQName(className);
    myJvmNames.put(qName, className);
    setText(qName);
  }

  public String getClassName() {
    String text = getText();
    return myJvmNames.get(text);
  }

  public boolean isReadyForApply() {
    return myJvmNames.containsKey(getText());
  }

  @Override
  public void documentChanged(@NotNull DocumentEvent event) {
    if (isReadyForApply()) {
      super.documentChanged(event);
    }
    else {
      String text = getText();
      ReadAction.nonBlocking(() -> myJvmNames.get(text))
        .expireWhen(() -> !isVisible())
        .finishOnUiThread(ModalityState.defaultModalityState(), s -> super.documentChanged(event))
        .submit(NonUrgentExecutor.getInstance());
    }
  }

  public static ClassEditorField createClassField(Project project,
                                                  Computable<? extends Module> moduleSelector,
                                                  JavaCodeFragment.VisibilityChecker visibilityChecker,
                                                  @Nullable BrowseModuleValueActionListener<?> classBrowser) {
    if (project.isDefault()) {
      return new ClassEditorField();
    }
    PsiElement defaultPackage = JavaPsiFacade.getInstance(project).findPackage("");
    JavaCodeFragmentFactory factory = JavaCodeFragmentFactory.getInstance(project);
    JavaCodeFragment fragment = factory.createReferenceCodeFragment("", defaultPackage, true, true);
    fragment.setVisibilityChecker(visibilityChecker);
    Document document = PsiDocumentManager.getInstance(project).getDocument(fragment);

    ClassEditorField field = new ClassEditorField(document, project, JavaFileType.INSTANCE);
    if (classBrowser != null) {
      classBrowser.setTextAccessor(field);
    }
    BrowseModuleValueActionListener<?> browser = classBrowser != null ? classBrowser :
                                            new ClassBrowser.AppClassBrowser<EditorTextField>(project, moduleSelector) {
      @Override
      public String getText() {
        return field.getText();
      }

      @Override
      public void actionPerformed(ActionEvent e) {
        String text = showDialog();
        if (text != null) {
          field.setText(text);
        }
      }
    };
    field.addSettingsProvider(editor -> {
      ExtendableTextComponent.Extension extension =
        ExtendableTextComponent.Extension.create(AllIcons.General.InlineVariables, AllIcons.General.InlineVariablesHover,
                                                 ComponentWithBrowseButton.getTooltip(), () -> browser.actionPerformed(null));
      ExtendableEditorSupport.setupExtension(editor, field.getBackground(), extension);
    });
    new DumbAwareAction() {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        browser.actionPerformed(null);
      }
    }.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)), field);

    return field;
  }

  private ClassEditorField(Document document, Project project, FileType fileType) {
    super(document, project, fileType);
  }

  private ClassEditorField() {
  }

  private static String getQName(@Nullable String className) {
    if (className == null) return "";
    return className.replace('$', '.');
  }

  private @Nullable String getJvmName(@Nullable String className) {
    if (className == null || className.isEmpty()) return null;

    return FileBasedIndex.getInstance().ignoreDumbMode(DumbModeAccessType.RELIABLE_DATA_ONLY, () -> {
      PsiClass aClass = JavaPsiFacade.getInstance(getProject()).findClass(className, GlobalSearchScope.allScope(getProject()));
      return aClass != null ? JavaExecutionUtil.getRuntimeQualifiedName(aClass) : className;
    });
  }
}
