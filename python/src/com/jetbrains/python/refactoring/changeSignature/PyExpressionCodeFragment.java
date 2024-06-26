// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.refactoring.changeSignature;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiCodeFragment;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.SingleRootFileViewProvider;
import com.intellij.psi.impl.PsiManagerEx;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.LightVirtualFile;
import com.jetbrains.python.PythonFileType;
import com.jetbrains.python.psi.impl.PyFileImpl;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

/**
 * User : ktisha
 */

public class PyExpressionCodeFragment extends PyFileImpl implements PsiCodeFragment {

  private GlobalSearchScope myResolveScope;

  public PyExpressionCodeFragment(final @NotNull Project project,
                                  final @NonNls String name,
                                  final @NotNull CharSequence text) {
    super(PsiManagerEx.getInstanceEx(project).getFileManager().createFileViewProvider(
      new LightVirtualFile(name, PythonFileType.INSTANCE, text), true));
    ((SingleRootFileViewProvider)getViewProvider()).forceCachedPsi(this);
  }

  @Override
  public void forceResolveScope(GlobalSearchScope scope) {
    myResolveScope = scope;
  }

  @Override
  public GlobalSearchScope getForcedResolveScope() {
    return myResolveScope;
  }

  @Override
  public boolean isValid() {
    return true;
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
  }

  @Override
  public boolean isAcceptedFor(@NotNull Class visitorClass) {
    return false;
  }
}
