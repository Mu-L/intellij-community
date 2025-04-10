// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.refactoring.move.moveInner;

import com.intellij.java.refactoring.JavaRefactoringBundle;
import com.intellij.lang.Language;
import com.intellij.lang.jvm.JvmLanguage;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiReference;
import com.intellij.psi.impl.source.jsp.jspJava.JspClass;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.refactoring.move.MoveCallback;
import com.intellij.refactoring.move.MoveHandlerDelegate;
import com.intellij.refactoring.move.moveClassesOrPackages.JavaMoveClassesOrPackagesHandler;
import com.intellij.refactoring.util.CommonRefactoringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class MoveInnerToUpperHandler extends MoveHandlerDelegate {
  @Override
  public boolean canMove(final PsiElement[] elements, final @Nullable PsiElement targetContainer, @Nullable PsiReference reference) {
    if (elements.length != 1) return false;
    PsiElement element = elements [0];
    return isNonStaticInnerClass(element);
  }

  private static boolean isNonStaticInnerClass(final PsiElement element) {
    return element instanceof PsiClass && element.getParent() instanceof PsiClass &&
           !((PsiClass) element).hasModifierProperty(PsiModifier.STATIC);
  }

  @Override
  public void doMove(final Project project, final PsiElement[] elements, final PsiElement targetContainer, final MoveCallback callback) {
    MoveInnerImpl.doMove(project, elements, callback, targetContainer);
  }

  @Override
  public boolean tryToMove(final PsiElement element, final Project project, final DataContext dataContext, final PsiReference reference,
                           final Editor editor) {
    if (isNonStaticInnerClass(element) && !JavaMoveClassesOrPackagesHandler.isReferenceInAnonymousClass(reference)) {
      PsiClass aClass = (PsiClass) element;
      final PsiClass containingClass = aClass.getContainingClass();
      if (containingClass instanceof JspClass) {
        CommonRefactoringUtil.showErrorHint(project, editor, JavaRefactoringBundle.message("move.nonstatic.class.from.jsp.not.supported"),
                                            RefactoringBundle.message("move.title"), null);
        return true;
      }
      MoveInnerImpl.doMove(project, new PsiElement[]{aClass}, null, LangDataKeys.TARGET_PSI_ELEMENT.getData(dataContext));
      return true;
    }
    return false;
  }

  @Override
  public @Nullable String getActionName(PsiElement @NotNull [] elements) {
    return JavaRefactoringBundle.message("move.inner.class.to.upper.level.action.name");
  }

  @Override
  public boolean supportsLanguage(@NotNull Language language) {
    return language instanceof JvmLanguage;
  }
}
