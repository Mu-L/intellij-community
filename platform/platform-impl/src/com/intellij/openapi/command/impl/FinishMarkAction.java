// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.command.impl;

import com.intellij.ide.IdeBundle;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.undo.BasicUndoableAction;
import com.intellij.openapi.command.undo.DocumentReference;
import com.intellij.openapi.command.undo.DocumentReferenceManager;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NlsContexts;
import org.jetbrains.annotations.Nullable;

public final class FinishMarkAction extends BasicUndoableAction {
  private final @Nullable StartMarkAction myStartAction;
  private boolean myGlobal = false;
  private @NlsContexts.Command String myCommandName;
  private final DocumentReference myReference;

  FinishMarkAction(DocumentReference reference, boolean isGlobal) {
    this(reference, null);
    setGlobal(isGlobal);
  }

  private FinishMarkAction(DocumentReference reference, @Nullable StartMarkAction action) {
    super(reference);
    myReference = reference;
    myStartAction = action;
  }

  @Override
  public void undo() {
  }

  @Override
  public void redo() {
  }

  @Override
  public boolean isGlobal() {
    return myGlobal;
  }

  public void setGlobal(boolean isGlobal) {
    if (myStartAction != null) {
      myStartAction.setGlobal(isGlobal);
    }
    myGlobal = isGlobal;
  }

  public void setCommandName(@NlsContexts.Command String commandName) {
    if (myStartAction != null) {
      myStartAction.setCommandName(commandName);
    }
    myCommandName = commandName;
  }

  public @NlsContexts.Command String getCommandName() {
    return myCommandName;
  }

  public DocumentReference getAffectedDocument() {
    return myReference;
  }

  public static void finish(final Project project, final Editor editor, final @Nullable StartMarkAction startAction) {
    if (startAction == null) return;
    CommandProcessor.getInstance().executeCommand(project, () -> {
      DocumentReference reference = DocumentReferenceManager.getInstance().create(editor.getDocument());
      UndoManager.getInstance(project).undoableActionPerformed(new FinishMarkAction(reference, startAction));
      StartMarkAction.markFinished(editor);
    }, IdeBundle.message("command.finish"), null);
  }
}
