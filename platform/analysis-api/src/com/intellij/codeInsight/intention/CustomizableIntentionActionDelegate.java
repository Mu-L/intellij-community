// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.intention;

import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/**
 * Intention action delegate that proxies CustomizableIntentionAction interface
 * to underlying action, if it inherits from CustomizableIntentionAction
 */
public interface CustomizableIntentionActionDelegate extends IntentionActionDelegate, CustomizableIntentionAction {
  @Override
  default boolean isShowSubmenu() {
    IntentionAction action = IntentionActionDelegate.unwrap(getDelegate());
    if (action instanceof CustomizableIntentionAction customizable) {
      return customizable.isShowSubmenu();
    }
    return CustomizableIntentionAction.super.isShowSubmenu();
  }

  @Override
  default boolean isSelectable() {
    IntentionAction action = IntentionActionDelegate.unwrap(getDelegate());
    if (action instanceof CustomizableIntentionAction customizable) {
      return customizable.isSelectable();
    }
    return CustomizableIntentionAction.super.isSelectable();
  }

  @Override
  default boolean isShowIcon() {
    IntentionAction action = IntentionActionDelegate.unwrap(getDelegate());
    if (action instanceof CustomizableIntentionAction customizable) {
      return customizable.isShowIcon();
    }
    return CustomizableIntentionAction.super.isShowIcon();
  }

  @Override
  default String getTooltipText() {
    IntentionAction action = IntentionActionDelegate.unwrap(getDelegate());
    if (action instanceof CustomizableIntentionAction customizable) {
      return customizable.getTooltipText();
    }
    return CustomizableIntentionAction.super.getTooltipText();
  }

  @Override
  default @Unmodifiable @NotNull List<RangeToHighlight> getRangesToHighlight(@NotNull Editor editor, @NotNull PsiFile file) {
    IntentionAction action = IntentionActionDelegate.unwrap(getDelegate());
    if (action instanceof CustomizableIntentionAction customizable) {
      return customizable.getRangesToHighlight(editor, file);
    }
    return CustomizableIntentionAction.super.getRangesToHighlight(editor, file);
  }
}
