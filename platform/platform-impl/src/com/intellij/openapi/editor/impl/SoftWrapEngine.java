// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.LanguageLineWrapPositionStrategy;
import com.intellij.openapi.editor.LineWrapPositionStrategy;
import com.intellij.openapi.editor.SoftWrap;
import com.intellij.openapi.editor.ex.util.EditorUtil;
import com.intellij.openapi.editor.impl.softwrap.SoftWrapDrawingType;
import com.intellij.openapi.editor.impl.softwrap.SoftWrapImpl;
import com.intellij.openapi.editor.impl.softwrap.SoftWrapPainter;
import com.intellij.openapi.editor.impl.softwrap.SoftWrapsStorage;
import com.intellij.openapi.editor.impl.softwrap.mapping.CachingSoftWrapDataMapper;
import com.intellij.openapi.editor.impl.softwrap.mapping.IncrementalCacheUpdateEvent;
import com.intellij.openapi.editor.impl.view.CharacterGrid;
import com.intellij.openapi.editor.impl.view.EditorView;
import com.intellij.openapi.editor.impl.view.WrapElementMeasuringIterator;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.DocumentUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Class that calculates soft wrap positions for a given text fragment and available visible width.
 */
@ApiStatus.Internal
public final class SoftWrapEngine {
  private static final int BASIC_LOOK_BACK_LENGTH = 10;

  private final EditorImpl myEditor;
  private final Document myDocument;
  private final CharSequence myText;
  private final EditorView myView;
  private final SoftWrapsStorage myStorage;
  private final CachingSoftWrapDataMapper myDataMapper;
  private final int myVisibleWidth;
  private final int myMaxWidthAtWrap;
  private final int mySoftWrapWidth;
  private final IncrementalCacheUpdateEvent myEvent;
  private final int myRelativeIndent;

  private LineWrapPositionStrategy myLineWrapPositionStrategy;

  public SoftWrapEngine(@NotNull EditorImpl editor,
                        @NotNull SoftWrapPainter painter,
                        @NotNull SoftWrapsStorage storage,
                        @NotNull CachingSoftWrapDataMapper dataMapper,
                        @NotNull IncrementalCacheUpdateEvent event,
                        @Nullable LineWrapPositionStrategy lineWrapStrategy,
                        int visibleWidth,
                        int relativeIndent) {
    myEditor = editor;
    myDocument = editor.getDocument();
    myText = myDocument.getImmutableCharSequence();
    myView = editor.myView;
    myStorage = storage;
    myDataMapper = dataMapper;
    myVisibleWidth = visibleWidth;
    myMaxWidthAtWrap = visibleWidth - painter.getMinDrawingWidth(SoftWrapDrawingType.BEFORE_SOFT_WRAP_LINE_FEED);
    mySoftWrapWidth = painter.getMinDrawingWidth(SoftWrapDrawingType.AFTER_SOFT_WRAP);
    myEvent = event;
    myRelativeIndent = relativeIndent;
    myLineWrapPositionStrategy = lineWrapStrategy;
  }

  public void generate() {
    int startOffset = myEvent.getStartOffset();
    int minEndOffset = myEvent.getMandatoryEndOffset();
    int maxEndOffset = getEndOffsetUpperEstimate();
    var inlineInlays = myEditor.getInlayModel().getInlineElementsInRange(startOffset, maxEndOffset);
    var afterLineEndInlays = ContainerUtil.filter(
      myEditor.getInlayModel().getAfterLineEndElementsInRange(DocumentUtil.getLineStartOffset(startOffset, myDocument), maxEndOffset),
      inlay -> !inlay.getProperties().isSoftWrappingDisabled()
    );
    var grid = myEditor.getCharacterGrid();
    if (grid != null && inlineInlays.isEmpty() && afterLineEndInlays.isEmpty()) {
      generateGridSoftWraps(grid, startOffset, minEndOffset, maxEndOffset);
      return;
    }

    SoftWrap lastSoftWrap = null;
    boolean minWrapOffsetAtFolding = false;
    int minWrapOffset = -1;
    int maxWrapOffset = -1;
    float nonWhitespaceStartX = 0;
    int nonWhitespaceStartOffset = -1;

    float x;
    if (startOffset == 0) {
      x = myView.getPrefixTextWidthInPixels();
    }
    else {
      lastSoftWrap = myStorage.getSoftWrap(startOffset);
      x = lastSoftWrap == null ? 0 : lastSoftWrap.getIndentInPixels();
    }

    WrapElementMeasuringIterator it = new WrapElementMeasuringIterator(myView, startOffset, maxEndOffset, inlineInlays, afterLineEndInlays);
    while (!it.atEnd()) {
      if (it.isLineBreak()) {
        minWrapOffset = -1;
        maxWrapOffset = -1;
        x = 0;
        lastSoftWrap = null;
        nonWhitespaceStartOffset = -1;
        if (it.getElementEndOffset() > minEndOffset) {
          myEvent.setActualEndOffset(it.getElementEndOffset());
          return;
        }
      }
      else {
        if (myRelativeIndent >= 0 && nonWhitespaceStartOffset == -1 && !it.isWhitespace()) {
          nonWhitespaceStartX = x;
          nonWhitespaceStartOffset = it.getElementStartOffset();
        }
        x = it.getElementEndX(x);
        if (minWrapOffset < 0 || x <= myMaxWidthAtWrap && it.isFoldRegion()) {
          minWrapOffset = it.getElementEndOffset();
          minWrapOffsetAtFolding = it.isFoldRegion();
        }
        else {
          if (x > myMaxWidthAtWrap && maxWrapOffset < 0) {
            maxWrapOffset = it.getElementStartOffset();
            if (maxWrapOffset > minWrapOffset && it.isFoldRegion()) minWrapOffset = maxWrapOffset;
          }
          if (x > myVisibleWidth) {
            lastSoftWrap = createSoftWrap(lastSoftWrap, minWrapOffset, maxWrapOffset, minWrapOffsetAtFolding,
                                          nonWhitespaceStartOffset, nonWhitespaceStartX);
            int wrapOffset = lastSoftWrap.getStart();
            if (wrapOffset > minEndOffset && myDataMapper.matchesOldSoftWrap(lastSoftWrap, myEvent.getLengthDiff())) {
              myEvent.setActualEndOffset(wrapOffset);
              return;
            }
            minWrapOffset = -1;
            maxWrapOffset = -1;
            x = lastSoftWrap.getIndentInPixels();
            if (wrapOffset <= it.getElementStartOffset()) {
              it.retreat(wrapOffset);
              continue;
            }
          }
        }
      }
      it.advance();
    }
    myEvent.setActualEndOffset(maxEndOffset);
  }

  private SoftWrap createSoftWrap(SoftWrap lastSoftWrap,
                                  int minWrapOffset,
                                  int maxWrapOffset,
                                  boolean preferMinOffset,
                                  int nonWhitespaceStartOffset,
                                  float nonWhitespaceStartX) {
    int wrapOffset = minWrapOffset >= maxWrapOffset ? minWrapOffset : calcSoftWrapOffset(minWrapOffset, maxWrapOffset, preferMinOffset);
    int indentInColumns = 1;
    int indentInPixels = mySoftWrapWidth;
    if (myRelativeIndent >= 0) {
      if (lastSoftWrap == null) {
        if (nonWhitespaceStartOffset >= 0 && nonWhitespaceStartOffset < wrapOffset) {
          indentInColumns += myEditor.offsetToLogicalPosition(nonWhitespaceStartOffset).column;
          indentInPixels += nonWhitespaceStartX;
        }
        indentInColumns += myRelativeIndent;
        indentInPixels += myRelativeIndent * myView.getPlainSpaceWidth();
      }
      else {
        indentInColumns = lastSoftWrap.getIndentInColumns();
        indentInPixels = lastSoftWrap.getIndentInPixels();
      }
    }
    SoftWrapImpl result = new SoftWrapImpl(new TextChangeImpl("\n" + StringUtil.repeatSymbol(' ', indentInColumns - 1), wrapOffset),
                                           indentInColumns, indentInPixels);
    myStorage.storeOrReplace(result);
    return result;
  }

  private int calcSoftWrapOffset(int minOffset, int maxOffset, boolean preferMinOffset) {
    if (myLineWrapPositionStrategy == null) {
      myLineWrapPositionStrategy = LanguageLineWrapPositionStrategy.INSTANCE.forEditor(myEditor);
    }
    if (!myEditor.getState().getDisableDefaultSoftWrapsCalculation()) {
      int position = findWrapPosition(myText, maxOffset, minOffset, myLineWrapPositionStrategy);
      if (position != -1) return position;
    }

    int wrapOffset = myLineWrapPositionStrategy.calculateWrapPosition(myDocument, myEditor.getProject(), minOffset - 1, maxOffset + 1, maxOffset + 1,
                                                                      false, true);
    if (wrapOffset < 0) return preferMinOffset ? minOffset : maxOffset;
    if (wrapOffset < minOffset) return minOffset;
    if (wrapOffset > maxOffset) return maxOffset;
    if (DocumentUtil.isInsideSurrogatePair(myDocument, wrapOffset)) return wrapOffset - 1;
    return wrapOffset;
  }

  /**
   * Finds the most appropriate position for wrapping text within the specified range.
   * This method iterates backward from the maximum offset to the minimum offset
   * while checking each character's suitability for line wrapping based on the provided strategy.
   *
   * @return the offset at which the wrap can occur, or {@code -1} if no suitable wrap position is found
   */
  @ApiStatus.Internal
  public static int findWrapPosition(CharSequence text, int maxOffset, int minOffset, LineWrapPositionStrategy strategy) {
    if (strategy.canWrapLineAtOffset(text, maxOffset))  return maxOffset;
    for (int i = 0, offset = maxOffset; i < BASIC_LOOK_BACK_LENGTH && offset >= minOffset; i++) {
      int prevOffset = Character.offsetByCodePoints(text, offset, -1);
      if (strategy.canWrapLineAtOffset(text, prevOffset)) return offset;
      //noinspection AssignmentToForLoopParameter
      offset = prevOffset;
    }
    return -1;
  }

  private int getEndOffsetUpperEstimate() {
    int endOffsetUpperEstimate = EditorUtil.getNotFoldedLineEndOffset(myEditor, myEvent.getMandatoryEndOffset());
    int line = myDocument.getLineNumber(endOffsetUpperEstimate);
    if (line < myDocument.getLineCount() - 1) {
      endOffsetUpperEstimate = myDocument.getLineStartOffset(line + 1);
    }
    return endOffsetUpperEstimate;
  }

  private void generateGridSoftWraps(CharacterGrid grid, int startOffset, int minEndOffset, int maxEndOffset) {
    int widthInColumns = grid.getColumns();
    int endOffset = maxEndOffset;
    var wrappedSoFar = 0;
    for (var it = grid.iterator(startOffset, maxEndOffset); !it.isAtEnd(); it.advance()) {
      var cellStartOffset = it.getCellStartOffset();
      var cellEndOffset = it.getCellEndOffset();
      var cellStartColumn = it.getCellStartColumn() - wrappedSoFar;
      var cellEndColumn = it.getCellEndColumn() - wrappedSoFar;
      if (it.isLineBreak()) {
        wrappedSoFar = 0;
        if (cellEndOffset > minEndOffset) {
          endOffset = cellEndOffset;
          break;
        }
      }
      else if (cellEndColumn > widthInColumns) {
        wrappedSoFar += cellStartColumn;
        var softWrap = createGridSoftWrap(cellStartOffset);
        if (cellStartOffset > minEndOffset && myDataMapper.matchesOldSoftWrap(softWrap, myEvent.getLengthDiff())) {
          endOffset = cellStartOffset;
          break;
        }
      }
    }
    myEvent.setActualEndOffset(endOffset);
  }

  private @NotNull SoftWrapImpl createGridSoftWrap(int offset) {
    var result = new SoftWrapImpl(new TextChangeImpl("\n", offset), 1, mySoftWrapWidth);
    myStorage.storeOrReplace(result);
    return result;
  }
}
