// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package com.intellij.codeInsight.lookup.impl;

import com.intellij.codeInsight.completion.CompletionProgressIndicator;
import com.intellij.codeInsight.completion.impl.CompletionServiceImpl;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public final class BackspaceHandler extends EditorActionHandler {
  @Nullable
  private final EditorActionHandler myOriginalHandler;

  public BackspaceHandler(@Nullable EditorActionHandler originalHandler) {
    myOriginalHandler = originalHandler;
  }

  @Override
  public void doExecute(final @NotNull Editor editor, Caret caret, final DataContext dataContext){
    LookupImpl lookup = (LookupImpl)LookupManager.getActiveLookup(editor);
    if (lookup == null) {
      if (myOriginalHandler != null) {
        myOriginalHandler.execute(editor, caret, dataContext);
      }
      return;
    }

    int hideOffset = lookup.getLookupStart();
    int originalStart = lookup.getLookupOriginalStart();
    if (originalStart >= 0 && originalStart <= hideOffset) {
      hideOffset = originalStart - 1;
    }

    truncatePrefix(dataContext, lookup, myOriginalHandler, hideOffset, caret);
  }

  static void truncatePrefix(final DataContext dataContext,
                             LookupImpl lookup,
                             final @Nullable EditorActionHandler handler,
                             final int hideOffset,
                             final Caret caret) {
    final Editor editor = lookup.getEditor();
    if (handler != null && !lookup.performGuardedChange(() -> handler.execute(editor, caret, dataContext))) {
      return;
    }

    final CompletionProgressIndicator process = CompletionServiceImpl.getCurrentCompletionProgressIndicator();
    lookup.truncatePrefix(process == null || !process.isAutopopupCompletion(), hideOffset);
  }
}
