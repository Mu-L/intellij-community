// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.codeInsight.editorActions.moveUpDown;

import com.intellij.codeInsight.editorActions.moveUpDown.LineMover;
import com.intellij.codeInsight.editorActions.moveUpDown.LineRange;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.impl.source.PostprocessReformattingAspect;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.python.ast.*;
import com.jetbrains.python.ast.impl.PyPsiUtilsCore;
import com.jetbrains.python.ast.impl.PyUtilCore;
import com.jetbrains.python.psi.PyAstElementGenerator;
import com.jetbrains.python.psi.PyStringLiteralCoreUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * User : ktisha
 */
public class PyStatementMover extends LineMover {
  @Override
  public boolean checkAvailable(@NotNull Editor editor, @NotNull PsiFile file, @NotNull MoveInfo info, boolean down) {
    if (!(file instanceof PyAstFile)) return false;
    final int offset = editor.getCaretModel().getOffset();
    final SelectionModel selectionModel = editor.getSelectionModel();
    final Document document = editor.getDocument();
    final int lineNumber = document.getLineNumber(offset);
    int start = getLineStartSafeOffset(document, lineNumber);
    final int lineEndOffset = document.getLineEndOffset(lineNumber);
    int end = lineEndOffset == 0 ? 0 : lineEndOffset - 1;

    if (selectionModel.hasSelection()) {
      start = selectionModel.getSelectionStart();
      final int selectionEnd = selectionModel.getSelectionEnd();
      end = selectionEnd == 0 ? 0 : selectionEnd - 1;
    }
    PsiElement elementToMove1 = PyUtilCore.findNonWhitespaceAtOffset(file, start);
    PsiElement elementToMove2 = PyUtilCore.findNonWhitespaceAtOffset(file, end);
    if (elementToMove1 == null || elementToMove2 == null) return false;

    if (ifInsideString(document, lineNumber, elementToMove1, down)) return false;

    elementToMove1 = getCommentOrStatement(document, elementToMove1);
    elementToMove2 = getCommentOrStatement(document, elementToMove2);

    if (PsiTreeUtil.isAncestor(elementToMove1, elementToMove2, false)) {
      elementToMove2 = elementToMove1;
    }
    else if (PsiTreeUtil.isAncestor(elementToMove2, elementToMove1, false)) {
      elementToMove1 = elementToMove2;
    }
    else {
      PsiElement commonParent = PsiTreeUtil.findCommonParent(elementToMove1, elementToMove2);
      if (commonParent == null) return false;
      elementToMove1 = PyPsiUtilsCore.getParentRightBefore(elementToMove1, commonParent);
      elementToMove2 = PyPsiUtilsCore.getParentRightBefore(elementToMove2, commonParent);
      assert elementToMove1 != null && elementToMove2 != null;
    }

    info.toMove = new MyLineRange(elementToMove1, elementToMove2);
    info.toMove2 = getDestinationScope(file, editor, down ? elementToMove2 : elementToMove1, down);

    info.indentTarget = false;
    info.indentSource = false;

    return true;
  }

  private static boolean ifInsideString(final @NotNull Document document, int lineNumber, final @NotNull PsiElement elementToMove1, boolean down) {
    int start = document.getLineStartOffset(lineNumber);
    final int end = document.getLineEndOffset(lineNumber);
    int nearLine = down ? lineNumber + 1 : lineNumber - 1;
    if (nearLine >= document.getLineCount() || nearLine <= 0) return false;
    final PyAstStringLiteralExpression stringLiteralExpression = PsiTreeUtil.getParentOfType(elementToMove1, PyAstStringLiteralExpression.class);
    if (stringLiteralExpression != null) {
      final Pair<String,String> quotes = PyStringLiteralCoreUtil.getQuotes(stringLiteralExpression.getText());
      if (quotes != null && (quotes.first.equals("'''") || quotes.first.equals("\"\"\""))) {
        final String text1 = document.getText(TextRange.create(start, end)).trim();
        final String text2 = document.getText(TextRange.create(document.getLineStartOffset(nearLine), document.getLineEndOffset(nearLine))).trim();
        if (!text1.startsWith(quotes.first) && !text1.endsWith(quotes.second) && !text2.startsWith(quotes.first) && !text2.endsWith(quotes.second))
          return true;
      }
    }
    return false;
  }

  private static @Nullable LineRange getDestinationScope(final @NotNull PsiFile file, final @NotNull Editor editor,
                                                         final @NotNull PsiElement elementToMove, boolean down) {
    final Document document = file.getViewProvider().getDocument();
    if (document == null) return null;

    final int offset = down ? elementToMove.getTextRange().getEndOffset() : elementToMove.getTextRange().getStartOffset();
    int lineNumber = down ? document.getLineNumber(offset) + 1 : document.getLineNumber(offset) - 1;
    if (moveOutsideFile(document, lineNumber)) return null;
    int lineEndOffset = document.getLineEndOffset(lineNumber);
    final int startOffset = document.getLineStartOffset(lineNumber);

    final PyAstStatementList statementList = getStatementList(elementToMove);

    final PsiElement destination = getDestinationElement(elementToMove, document, lineEndOffset, down);

    final int start = destination != null ? destination.getTextRange().getStartOffset() : lineNumber;
    final int end = destination != null ? destination.getTextRange().getEndOffset() : lineNumber;
    final int startLine = document.getLineNumber(start);
    final int endLine = document.getLineNumber(end);

    if (elementToMove instanceof PyAstClass || elementToMove instanceof PyAstFunction) {
      PyAstElement scope = statementList == null ? (PyAstElement)elementToMove.getContainingFile() : statementList;
      if (destination != null)
        return new ScopeRange(scope, destination, !down, true);
    }
    final String lineText = document.getText(TextRange.create(startOffset, lineEndOffset));
    final boolean isEmptyLine = StringUtil.isEmptyOrSpaces(lineText);
    if (isEmptyLine && moveToEmptyLine(elementToMove, down)) return new LineRange(lineNumber, lineNumber + 1);

    LineRange scopeRange = moveOut(elementToMove, editor, down);
    if (scopeRange != null) return scopeRange;
    scopeRange = moveInto(elementToMove, file, editor, down, lineEndOffset);
    if (scopeRange != null) return scopeRange;

    if (elementToMove instanceof PsiComment && ( PsiTreeUtil.isAncestor(destination, elementToMove, true)) ||
        destination instanceof  PsiComment) {
      return new LineRange(lineNumber, lineNumber + 1);
    }

    final PyAstElement scope = statementList == null ? (PyAstElement)elementToMove.getContainingFile() : statementList;
    if ((elementToMove instanceof PyAstClass) || (elementToMove instanceof PyAstFunction))
      return new ScopeRange(scope, scope.getFirstChild(), !down, true);
    return new LineRange(startLine, endLine + 1);
  }

  private static boolean moveOutsideFile(final @NotNull Document document, int lineNumber) {
    return lineNumber < 0 || lineNumber >= document.getLineCount();
  }

  private static boolean moveToEmptyLine(final @NotNull PsiElement elementToMove, boolean down) {
    final PyAstStatementList statementList = getStatementList(elementToMove);
    if (statementList != null) {
      if (down) {
        final PsiElement child = statementList.getLastChild();
        if (elementToMove == child && PsiTreeUtil.getNextSiblingOfType(statementList.getParent(), PyAstStatementPart.class) != null
            || child != elementToMove) {
          return true;
        }
      }
      else {
        return true;
      }
    }
    return statementList == null;
  }

  private static PyAstStatementList getStatementList(final @NotNull PsiElement elementToMove) {
    return PsiTreeUtil.getParentOfType(elementToMove, PyAstStatementList.class, true,
                                                                PyAstStatementWithElse.class, PyAstLoopStatement.class,
                                                                PyAstFunction.class, PyAstClass.class);
  }

  private static @Nullable ScopeRange moveOut(final @NotNull PsiElement elementToMove, final @NotNull Editor editor, boolean down) {
    final PyAstStatementList statementList = getStatementList(elementToMove);
    if (statementList == null) return null;

    if ((!down || statementList.getLastChild() != elementToMove) && (down || statementList.getFirstChild() != elementToMove)) {
      return null;
    }
    boolean addBefore = !down;
    final PsiElement parent = statementList.getParent();
    final PyAstStatementPart sibling = down ? PsiTreeUtil.getNextSiblingOfType(parent, PyAstStatementPart.class)
                                            : PsiTreeUtil.getPrevSiblingOfType(parent, PyAstStatementPart.class);

    if (sibling != null) {
      final PyAstStatementList list = sibling.getStatementList();
      return new ScopeRange(list, down ? list.getFirstChild() : list.getLastChild(), !addBefore);
    }
    else {
      PsiElement scope = getScopeForComment(elementToMove, editor, parent, !down);
      PsiElement anchor = PsiTreeUtil.getParentOfType(statementList, PyAstStatement.class);
      return scope == null || anchor == null ? null : new ScopeRange(scope, anchor, addBefore);
    }
  }

  private static PsiElement getScopeForComment(final @NotNull PsiElement elementToMove, final @NotNull Editor editor,
                                               @Nullable PsiElement parent, boolean down) {
    PsiElement scope = PsiTreeUtil.getParentOfType(parent, PyAstStatementList.class, PyAstFile.class);
    final int offset = elementToMove.getTextOffset();
    PsiElement sibling = elementToMove;
    while (scope != null && elementToMove instanceof PsiComment) { // stupid workaround for PY-6408. Related to PSI structure
      final PsiElement prevSibling = down ? PsiTreeUtil.getNextSiblingOfType(sibling, PyAstStatement.class) :
                                            PsiTreeUtil.getPrevSiblingOfType(sibling, PyAstStatement.class);
      if (prevSibling == null) break;
      if (editor.offsetToLogicalPosition(prevSibling.getTextOffset()).column ==
          editor.offsetToLogicalPosition(offset).column) break;
      sibling = scope;
      scope = PsiTreeUtil.getParentOfType(scope, PyAstStatementList.class, PyAstFile.class);
    }
    return scope;
  }

  private static @Nullable LineRange moveInto(final @NotNull PsiElement elementToMove, final @NotNull PsiFile file,
                                              final @NotNull Editor editor, boolean down, int offset) {

    PsiElement rawElement = PyUtilCore.findNonWhitespaceAtOffset(file, offset);
    if (rawElement == null) return null;

    return down ? moveDownInto(editor.getDocument(), rawElement) : moveUpInto(elementToMove, editor, rawElement, false);
  }

  private static @Nullable LineRange moveUpInto(final @NotNull PsiElement elementToMove, final @NotNull Editor editor,
                                                final @NotNull PsiElement rawElement, boolean down) {
    final Document document = editor.getDocument();
    PsiElement element = getCommentOrStatement(document, rawElement);
    final PyAstStatementList statementList = getStatementList(elementToMove);
    final PsiElement scopeForComment = statementList == null ? null :
                                       getScopeForComment(elementToMove, editor, elementToMove, down);
    PyAstStatementList statementList2 = getStatementList(element);
    final int start1 = elementToMove.getTextOffset() - document.getLineStartOffset(document.getLineNumber(elementToMove.getTextOffset()));
    final int start2 = element.getTextOffset() - document.getLineStartOffset(document.getLineNumber(element.getTextOffset()));
    if (start1 != start2) {
      PyAstStatementList parent2 = PsiTreeUtil.getParentOfType(statementList2, PyAstStatementList.class);
      while (parent2 != scopeForComment && parent2 != null) {
        element = PsiTreeUtil.getParentOfType(statementList2, PyAstStatement.class);
        statementList2 = parent2;
        parent2 = PsiTreeUtil.getParentOfType(parent2, PyAstStatementList.class);
      }
    }

    if (statementList2 != null && scopeForComment != statementList2 &&
        (statementList2.getLastChild() == element || statementList2.getLastChild() == elementToMove) && element != null) {
      return new ScopeRange(statementList2, element, false);
    }
    return null;
  }

  private static @Nullable LineRange moveDownInto(final @NotNull Document document, final @NotNull PsiElement rawElement) {
    PsiElement element = getCommentOrStatement(document, rawElement);
    PyAstStatementList statementList2 = getStatementList(element);
    if (statementList2 != null) {                     // move to one-line conditional/loop statement
      final int number = document.getLineNumber(element.getTextOffset());
      final int number2 = document.getLineNumber(statementList2.getParent().getTextOffset());
      if (number == number2) {
        return new ScopeRange(statementList2, statementList2.getFirstChild(), true);
      }
    }
    final PyAstStatementPart statementPart = PsiTreeUtil.getParentOfType(rawElement, PyAstStatementPart.class, true, PyAstStatement.class,
                                                                         PyAstStatementList.class);
    final PyAstFunction functionDefinition = PsiTreeUtil.getParentOfType(rawElement, PyAstFunction.class, true, PyAstStatement.class,
                                                                         PyAstStatementList.class);
    final PyAstClass classDefinition = PsiTreeUtil.getParentOfType(rawElement, PyAstClass.class, true, PyAstStatement.class,
                                                                   PyAstStatementList.class);
    PyAstStatementList list = null;
    if (statementPart != null) list = statementPart.getStatementList();
    else if (functionDefinition != null) list = functionDefinition.getStatementList();
    else if (classDefinition != null) list = classDefinition.getStatementList();
    if (list != null) {
      return new ScopeRange(list, list.getFirstChild(), true);
    }
    return null;
  }

  private static PsiElement getDestinationElement(final @NotNull PsiElement elementToMove, final @NotNull Document document,
                                                  int lineEndOffset, boolean down) {
    PsiElement destination = PyUtilCore.findPrevAtOffset(elementToMove.getContainingFile(), lineEndOffset, PsiWhiteSpace.class);
    PsiElement sibling = down ? PsiTreeUtil.getNextSiblingOfType(elementToMove, PyAstStatement.class) :
                         PsiTreeUtil.getPrevSiblingOfType(elementToMove, PyAstStatement.class);
    if (destination == null) {
      if (elementToMove instanceof PyAstClass) {
        destination = sibling;
      }
      else if (elementToMove instanceof PyAstFunction) {
        if (!(sibling instanceof PyAstClass))
          destination = sibling;
        else destination = null;
      }
      else {
        return null;
      }
    }
    if (destination instanceof PsiComment) return destination;
    if (elementToMove instanceof PyAstClass) {
      destination = sibling;
    }
    else if (elementToMove instanceof PyAstFunction) {
      if (!(sibling instanceof PyAstClass))
        destination = sibling;
      else destination = null;
    }
    else {
      destination = getCommentOrStatement(document, sibling == null ? destination : sibling);
    }
    return destination;
  }

  private static @NotNull PsiElement getCommentOrStatement(final @NotNull Document document, @NotNull PsiElement destination) {
    final PsiElement statement = PsiTreeUtil.getParentOfType(destination, PyAstStatement.class, false);
    if (statement == null) return destination;
    if (destination instanceof PsiComment) {
      if (document.getLineNumber(destination.getTextOffset()) == document.getLineNumber(statement.getTextOffset()))
        destination = statement;
    }
    else
      destination = statement;
    return destination;
  }

  @Override
  public void beforeMove(final @NotNull Editor editor, final @NotNull MoveInfo info, final boolean down) {
    final LineRange toMove = info.toMove;
    final LineRange toMove2 = info.toMove2;

    if (toMove instanceof MyLineRange && toMove2 instanceof ScopeRange) {

      PostprocessReformattingAspect.getInstance(editor.getProject()).disablePostprocessFormattingInside(() -> {
        final PsiElement startToMove = ((MyLineRange)toMove).myStartElement;
        final PsiElement endToMove = ((MyLineRange)toMove).myEndElement;
        final PsiFile file = startToMove.getContainingFile();
        final SelectionModel selectionModel = editor.getSelectionModel();
        final CaretModel caretModel = editor.getCaretModel();

        final int selectionStart = selectionModel.getSelectionStart();
        final LogicalPosition selectionBeforeMoveStart = editor.offsetToLogicalPosition(selectionStart);
        final LogicalPosition selectionBeforeMoveEnd = editor.offsetToLogicalPosition(selectionModel.getSelectionEnd());
        boolean isSelectionStartAtCaret = caretModel.getOffset() == selectionStart;
        final SelectionContainer selectionLen = getSelectionLenContainer(editor, ((MyLineRange)toMove));

        int shift = getCaretShift(startToMove, endToMove, caretModel, isSelectionStartAtCaret);

        final boolean hasSelection = selectionModel.hasSelection();
        int offset;
        if (((ScopeRange)toMove2).isTheSameLevel()) {
          offset = moveTheSameLevel((ScopeRange)toMove2, (MyLineRange)toMove);
        }
        else {
          offset = moveInOut(((MyLineRange)toMove), editor, info);
        }
        final LogicalPosition positionOffsetAfterMove = editor.offsetToLogicalPosition(offset);
        restoreCaretAndSelection(file, editor, isSelectionStartAtCaret, hasSelection, selectionLen,
                                 shift, offset, (MyLineRange)toMove,
                                 selectionBeforeMoveStart,
                                 selectionBeforeMoveEnd,
                                 positionOffsetAfterMove);
        info.toMove2 = info.toMove;   //do not move further
      });
    }

  }

  private static SelectionContainer getSelectionLenContainer(final @NotNull Editor editor, final @NotNull MyLineRange toMove) {
    final SelectionModel selectionModel = editor.getSelectionModel();
    final PsiElement startToMove = toMove.myStartElement;
    final PsiElement endToMove = toMove.myEndElement;
    final int selectionStart = selectionModel.getSelectionStart();
    final int selectionEnd = selectionModel.getSelectionEnd();

    final TextRange range = startToMove.getTextRange();
    final int column = editor.offsetToLogicalPosition(selectionStart).column;
    final int additionalSelection = range.getStartOffset() > selectionStart ? range.getStartOffset() - selectionStart : 0;
    if (startToMove == endToMove) return new SelectionContainer(selectionEnd - range.getStartOffset(), additionalSelection, column == 0);
    int len = range.getStartOffset() <= selectionStart ? range.getEndOffset() - selectionStart : startToMove.getTextLength();

    PsiElement tmp = startToMove.getNextSibling();
    while (tmp != endToMove && tmp != null) {
      if (!(tmp instanceof PsiWhiteSpace))
        len += tmp.getTextLength();
      tmp = tmp.getNextSibling();
    }
    len = len + selectionEnd - endToMove.getTextOffset();

    return new SelectionContainer(len, additionalSelection, column == 0);
  }

  private static void restoreCaretAndSelection(final @NotNull PsiFile file, final @NotNull Editor editor, boolean selectionStartAtCaret,
                                               boolean hasSelection, final @NotNull SelectionContainer selectionContainer, int shift,
                                               int offset, final @NotNull MyLineRange toMove,
                                               LogicalPosition selectionBeforeMoveStart,
                                               LogicalPosition selectionBeforeMoveEnd,
                                               LogicalPosition positionOffsetAfterMove) {
    final Document document = editor.getDocument();
    final SelectionModel selectionModel = editor.getSelectionModel();
    final CaretModel caretModel = editor.getCaretModel();
    int selectionLen = selectionContainer.myLen;
    final PsiElement at = file.findElementAt(offset);
    if (at != null) {
      final PsiElement added = getCommentOrStatement(document, at);
      int size = toMove.size;
      if (size > 1) {
        PsiElement tmp = added.getNextSibling();
        while (size > 1 && tmp != null) {
          if (tmp instanceof PsiWhiteSpace) {
            if (!selectionStartAtCaret)
              shift += tmp.getTextLength();
            selectionLen += tmp.getTextLength();
          }
          tmp = tmp.getNextSibling();
          size -= 1;
        }
      }
      if (shift < 0) shift = 0;
      final int column = editor.offsetToLogicalPosition(added.getTextRange().getStartOffset()).column;
      if (selectionContainer.myAtTheBeginning || column < selectionContainer.myAdditional) {
        selectionLen += column;
      }
      else {
        selectionLen += selectionContainer.myAdditional;
      }
      if (selectionContainer.myAtTheBeginning && selectionStartAtCaret)
        shift = -column;
    }

    final int documentLength = document.getTextLength();
    int newCaretOffset = offset + shift;
    if (newCaretOffset >= documentLength) newCaretOffset = documentLength;
    caretModel.moveToOffset(newCaretOffset);

    if (hasSelection) {
      int selectionLinesDiff = selectionBeforeMoveEnd.line - selectionBeforeMoveStart.line;
      if (selectionLinesDiff > 1) {
        int endOffsetColumn = selectionBeforeMoveEnd.column;
        if (endOffsetColumn > 0) endOffsetColumn += positionOffsetAfterMove.column - selectionBeforeMoveStart.column;
        int startOffsetColumn = positionOffsetAfterMove.column;
        if (selectionBeforeMoveStart.column == 0) startOffsetColumn = 0;
        int startOffset = editor.logicalPositionToOffset(new LogicalPosition(positionOffsetAfterMove.line, startOffsetColumn));
        int endOffset = editor.logicalPositionToOffset(new LogicalPosition(positionOffsetAfterMove.line + selectionLinesDiff, endOffsetColumn));
        selectionModel.setSelection(startOffset, endOffset);
        if (selectionStartAtCaret) {
          caretModel.moveToOffset(startOffset);
        }
        else {
          caretModel.moveToOffset(endOffset);
        }
      }
      else {
        if (selectionStartAtCaret) {
          int newSelectionEnd = newCaretOffset + selectionLen;
          selectionModel.setSelection(newCaretOffset, newSelectionEnd);
        }
        else {
          int newSelectionStart = newCaretOffset - selectionLen;
          selectionModel.setSelection(newSelectionStart, newCaretOffset);
        }
      }
    }
  }

  private static int getCaretShift(PsiElement startToMove, PsiElement endToMove, CaretModel caretModel, boolean selectionStartAtCaret) {
    int shift;
    if (selectionStartAtCaret) {
      shift = caretModel.getOffset() - startToMove.getTextRange().getStartOffset();
    }
    else {
      shift = caretModel.getOffset();
      if (startToMove != endToMove) {
        shift += startToMove.getTextLength();

        PsiElement tmp = startToMove.getNextSibling();
        while (tmp != endToMove && tmp != null) {
          if (!(tmp instanceof PsiWhiteSpace))
            shift += tmp.getTextLength();
          tmp = tmp.getNextSibling();
        }
      }

      shift -= endToMove.getTextOffset();
    }
    return shift;
  }

  private static int moveTheSameLevel(final @NotNull ScopeRange toMove2, final @NotNull MyLineRange toMove) {
    final PsiElement anchor = toMove2.getAnchor();
    final PsiElement anchorCopy = anchor.copy();
    PsiElement startToMove = toMove.myStartElement;
    final PsiElement endToMove = toMove.myEndElement;

    final PsiElement parent = anchor.getParent();
    PsiElement tmp = startToMove.getNextSibling();

    if (startToMove != endToMove && tmp != null) {
      parent.addRangeAfter(tmp, endToMove, anchor);
    }

    PsiElement startCopy = startToMove.copy();
    startToMove.replace(anchorCopy);
    final PsiElement addedElement = anchor.replace(startCopy);

    if (startToMove != endToMove && tmp != null) {
      parent.deleteChildRange(tmp, endToMove);
    }

    return addedElement.getTextRange().getStartOffset();
  }

  private static int moveInOut(final @NotNull MyLineRange toMove, final @NotNull Editor editor, final @NotNull MoveInfo info) {
    boolean removePass = false;
    final ScopeRange toMove2 = (ScopeRange)info.toMove2;
    final PsiElement scope = toMove2.getScope();
    final PsiElement anchor = toMove2.getAnchor();
    final Project project = scope.getProject();

    final PsiElement startElement = toMove.myStartElement;
    final PsiElement endElement = toMove.myEndElement;
    PsiElement parent = startElement.getParent();

    if (scope instanceof PyAstStatementList && !(startElement == endElement && startElement instanceof PsiComment)) {
      final PyAstStatement[] statements = ((PyAstStatementList)scope).getStatements();
      if (statements.length == 1 && statements[0] == anchor && statements[0] instanceof PyAstPassStatement) {
        removePass = true;
      }
    }

    final PsiElement addedElement;
    PsiElement nextSibling = startElement.getNextSibling();
    if (toMove2.isAddBefore()) {
      PsiElement tmp = endElement.getPrevSibling();
      if (startElement != endElement && tmp != null) {
        addedElement = scope.addRangeBefore(startElement, tmp, anchor);
        scope.addBefore(endElement, anchor);
      }
      else {
        addedElement = scope.addBefore(endElement, anchor);
      }
    }
    else {
      if (startElement != endElement && nextSibling != null) {
        scope.addRangeAfter(nextSibling, endElement, anchor);
      }
      addedElement = scope.addAfter(startElement, anchor);
    }
    addPassStatement(toMove, project);

    if (startElement != endElement && nextSibling != null) {
      parent.deleteChildRange(nextSibling, endElement);
    }
    startElement.delete();

    final int addedElementLine = editor.getDocument().getLineNumber(addedElement.getTextOffset());
    final PsiFile file = scope.getContainingFile();

    adjustLineIndents(editor, scope, project, addedElement, toMove.size);

    if (removePass) {
      ApplicationManager.getApplication().runWriteAction(() -> {
        final Document document = editor.getDocument();
        final int lineNumber = document.getLineNumber(anchor.getTextOffset());
        final int endOffset = document.getLineCount() <= lineNumber + 1 ? document.getLineEndOffset(lineNumber)
                                                                        : document.getLineStartOffset(lineNumber + 1);
        document.deleteString(document.getLineStartOffset(lineNumber), endOffset);
        PsiDocumentManager.getInstance(startElement.getProject()).commitAllDocuments();
      });
    }

    int offset = addedElement.getTextRange().getStartOffset();
    int newLine = editor.getDocument().getLineNumber(offset);
    if (newLine != addedElementLine && !removePass) {  // PsiComment gets broken after adjust indent
      PsiElement psiElement = PyUtilCore.findNonWhitespaceAtOffset(file, editor.getDocument().getLineEndOffset(addedElementLine) - 1);
      if (psiElement != null) {
        psiElement = getCommentOrStatement(editor.getDocument(), psiElement);
        offset = psiElement.getTextRange().getStartOffset();
      }
    }
    return offset;
  }

  private static void adjustLineIndents(final @NotNull Editor editor, final @NotNull PsiElement scope, final @NotNull Project project,
                                        final @NotNull PsiElement addedElement, int size) {
    final CodeStyleManager codeStyleManager = CodeStyleManager.getInstance(project);
    final Document document = editor.getDocument();

    if (!(scope instanceof PsiFile)) {
      int line1 = editor.offsetToLogicalPosition(scope.getTextRange().getStartOffset()).line;
      int line2 = editor.offsetToLogicalPosition(scope.getTextRange().getEndOffset()).line;
      codeStyleManager.adjustLineIndent(scope.getContainingFile(),
                                        new TextRange(document.getLineStartOffset(line1), document.getLineEndOffset(line2)));
    }
    else {
      int line1 = editor.offsetToLogicalPosition(addedElement.getTextRange().getStartOffset()).line;
      PsiElement end = addedElement;
      while (size > 0) {
        PsiElement tmp = end.getNextSibling();
        if (tmp == null) break;
        size -= 1;
        end = tmp;
      }
      int endOffset = end.getTextRange().getEndOffset();
      int line2 = editor.offsetToLogicalPosition(endOffset).line;
      codeStyleManager.adjustLineIndent(scope.getContainingFile(),
                                        new TextRange(document.getLineStartOffset(line1), document.getLineEndOffset(line2)));
    }
  }

  private static void addPassStatement(final @NotNull MyLineRange toMove, final @NotNull Project project) {
    final PsiElement startElement = toMove.myStartElement;
    final PsiElement endElement = toMove.myEndElement;
    final PyAstStatementList initialScope = getStatementList(startElement);

    if (initialScope != null && !(startElement == endElement && startElement instanceof PsiComment)) {
      if (initialScope.getStatements().length == toMove.statementsSize) {
        final PyAstPassStatement passStatement = PyAstElementGenerator.getInstance(project).createPassStatement();
        initialScope.addAfter(passStatement, initialScope.getStatements()[initialScope.getStatements().length - 1]);
      }
    }
  }

  // use to keep elements
  public static class MyLineRange extends LineRange {
    private final PsiElement myStartElement;
    private final PsiElement myEndElement;
    int size = 0;
    int statementsSize = 0;

    MyLineRange(@NotNull PsiElement start, PsiElement end) {
      super(start, end);
      myStartElement = start;
      myEndElement = end;

      if (myStartElement == myEndElement) {
        size = 1;
        statementsSize = 1;
      }
      else {
        PsiElement counter = myStartElement;
        while (counter != myEndElement && counter != null) {
          size += 1;
          if (!(counter instanceof PsiWhiteSpace) && !(counter instanceof PsiComment)) {
            statementsSize += 1;
          }
          counter = counter.getNextSibling();
        }
        size += 1;
        if (!(counter instanceof PsiWhiteSpace) && !(counter instanceof PsiComment)) {
          statementsSize += 1;
        }
      }
    }

    public @NotNull PsiElement getStartElement() {
      return myStartElement;
    }

    public PsiElement getEndElement() {
      return myEndElement;
    }
  }

  static class SelectionContainer {
    private final int myLen;
    private final int myAdditional;
    private final boolean myAtTheBeginning;

    SelectionContainer(int len, int additional, boolean atTheBeginning) {
      myLen = len;
      myAdditional = additional;
      myAtTheBeginning = atTheBeginning;
    }
  }

  // Use when element scope changed
  public static class ScopeRange extends LineRange {
    private final PsiElement myScope;
    private final @NotNull PsiElement myAnchor;
    private final boolean addBefore;
    private boolean theSameLevel;

    ScopeRange(@NotNull PsiElement scope, @NotNull PsiElement anchor, boolean before) {
      super(scope);
      myScope = scope;
      myAnchor = anchor;
      addBefore = before;
    }

    ScopeRange(PyAstElement scope, @NotNull PsiElement anchor, boolean before, boolean b) {
      super(scope);
      myScope = scope;
      myAnchor = anchor;
      addBefore = before;
      theSameLevel = b;
    }

    public @NotNull PsiElement getAnchor() {
      return myAnchor;
    }

    public PsiElement getScope() {
      return myScope;
    }

    public boolean isAddBefore() {
      return addBefore;
    }

    public boolean isTheSameLevel() {
      return theSameLevel;
    }
  }
}
