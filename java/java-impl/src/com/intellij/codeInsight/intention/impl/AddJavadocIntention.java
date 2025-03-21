// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.intention.impl;

import com.intellij.codeInsight.editorActions.FixDocCommentAction;
import com.intellij.codeInsight.intention.BaseElementAtCaretIntentionAction;
import com.intellij.codeInsight.intention.LowPriorityAction;
import com.intellij.ide.util.PackageUtil;
import com.intellij.java.JavaBundle;
import com.intellij.lang.java.JavaDocumentationProvider;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;

public class AddJavadocIntention extends BaseElementAtCaretIntentionAction implements LowPriorityAction, DumbAware {
  @Override
  public void invoke(@NotNull Project project, @NotNull Editor editor, @NotNull PsiElement element) {
    FixDocCommentAction.generateOrFixComment(element, project, editor);
  }

  @Override
  public boolean isAvailable(@NotNull Project project, @NotNull Editor editor, @NotNull PsiElement element) {
    if (element instanceof PsiIdentifier ||
        element instanceof PsiJavaCodeReferenceElement ||
        element instanceof PsiJavaModuleReferenceElement) {
      PsiElement targetElement = PsiTreeUtil.skipParentsOfType(element, PsiIdentifier.class, PsiJavaCodeReferenceElement.class, PsiJavaModuleReferenceElement.class);
      if (targetElement instanceof PsiVariable && PsiTreeUtil.isAncestor(((PsiVariable)targetElement).getInitializer(), element, false)) {
        return false;
      }
      if ( targetElement instanceof PsiClass aClass && PsiUtil.isLocalClass(aClass)) {
        return false;
      }
      if (targetElement instanceof PsiJavaDocumentedElement &&
          !(targetElement instanceof PsiTypeParameter) &&
          !(targetElement instanceof PsiAnonymousClass)) {
        return ((PsiJavaDocumentedElement)targetElement).getDocComment() == null;
      }

      if (targetElement instanceof PsiPackageStatement) {
        PsiFile file = targetElement.getContainingFile();
        return PackageUtil.isPackageInfoFile(file) && JavaDocumentationProvider.getPackageInfoComment(file) == null;
      }
      else if (PackageUtil.isPackageInfoFile(targetElement)) {
        return JavaDocumentationProvider.getPackageInfoComment(targetElement) == null;
      }
    }
    return false;
  }

  @Override
  public @NotNull String getFamilyName() {
    //noinspection DialogTitleCapitalization
    return JavaBundle.message("intention.family.add.javadoc");
  }

  @Override
  public @NotNull String getText() {
    //noinspection DialogTitleCapitalization
    return getFamilyName();
  }
}
