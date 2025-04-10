// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package com.intellij.codeInsight.editorActions.enter;

import com.intellij.application.options.CodeStyle;
import com.intellij.codeInsight.editorActions.EnterHandler;
import com.intellij.codeInsight.editorActions.JavaLikeQuoteHandler;
import com.intellij.codeInsight.editorActions.QuoteHandler;
import com.intellij.codeInsight.editorActions.TypedHandler;
import com.intellij.lang.ASTNode;
import com.intellij.lang.Language;
import com.intellij.lexer.StringLiteralLexer;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.StringEscapesTokenTypes;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class EnterInStringLiteralHandler implements EnterHandlerDelegate {
  @Override
  public Result preprocessEnter(final @NotNull PsiFile file, final @NotNull Editor editor, @NotNull Ref<Integer> caretOffsetRef,
                                final @NotNull Ref<Integer> caretAdvanceRef, final @NotNull DataContext dataContext,
                                final EditorActionHandler originalHandler) {
    final Language language = EnterHandler.getLanguage(dataContext);
    if (language == null) return Result.Continue;

    int caretOffset = caretOffsetRef.get().intValue();
    final JavaLikeQuoteHandler quoteHandler = getJavaLikeQuoteHandler(editor, file);
    if (!isInStringLiteral(editor, quoteHandler, caretOffset)) {
      return Result.Continue;
    }

    PsiDocumentManager.getInstance(file.getProject()).commitDocument(editor.getDocument());
    PsiElement psiAtOffset = file.findElementAt(caretOffset);
    if (psiAtOffset != null && psiAtOffset.getTextOffset() < caretOffset) {
      Document document = editor.getDocument();
      if (quoteHandler.canBeConcatenated(psiAtOffset)) {
        return splitString(file, caretOffsetRef, caretAdvanceRef, psiAtOffset, document, caretOffset, quoteHandler);
      }
    }
    return Result.Continue;
  }

  protected Result splitString(@NotNull PsiFile file,
                             @NotNull Ref<Integer> caretOffsetRef,
                             @NotNull Ref<Integer> caretAdvanceRef,
                             @NotNull PsiElement psiAtOffset,
                             @NotNull Document document,
                             int caretOffset,
                             @NotNull JavaLikeQuoteHandler quoteHandler) {
    ASTNode token = psiAtOffset.getNode();
    CharSequence text = document.getText();

    TextRange range = token.getTextRange();
    final char literalStart = token.getText().charAt(0);
    final StringLiteralLexer lexer = new StringLiteralLexer(literalStart, token.getElementType());
    lexer.start(text, range.getStartOffset(), range.getEndOffset());
    caretOffset = skipStringLiteralEscapes(caretOffset, lexer);
    caretOffset = wrapWithParensIfNeeded(psiAtOffset, document, caretOffset, quoteHandler);
    final String insertedFragment = literalStart + " " + quoteHandler.getStringConcatenationOperatorRepresentation();
    document.insertString(caretOffset, insertedFragment + " " + literalStart);
    caretOffset += insertedFragment.length();
    int caretAdvance = 1;
    if (CodeStyle.getLanguageSettings(file).BINARY_OPERATION_SIGN_ON_NEXT_LINE) {
      caretOffset -= 1;
      caretAdvance = 3;
    }
    caretOffsetRef.set(caretOffset);
    caretAdvanceRef.set(caretAdvance);
    return Result.DefaultForceIndent;
  }

  protected static int wrapWithParensIfNeeded(@NotNull PsiElement psiAtOffset,
                               @NotNull Document document,
                               int caretOffset,
                               @NotNull JavaLikeQuoteHandler quoteHandler) {
    if (quoteHandler.needParenthesesAroundConcatenation(psiAtOffset)) {
      document.insertString(psiAtOffset.getTextRange().getEndOffset(), ")");
      document.insertString(psiAtOffset.getTextRange().getStartOffset(), "(");
      caretOffset++;
    }
    return caretOffset;
  }

  protected static int skipStringLiteralEscapes(int caretOffset, StringLiteralLexer lexer) {
    while (lexer.getTokenType() != null) {
      if (lexer.getTokenStart() < caretOffset && caretOffset < lexer.getTokenEnd()) {
        if (StringEscapesTokenTypes.STRING_LITERAL_ESCAPES.contains(lexer.getTokenType())) {
          caretOffset = lexer.getTokenEnd();
        }
        break;
      }
      lexer.advance();
    }
    return caretOffset;
  }

  protected @Nullable JavaLikeQuoteHandler getJavaLikeQuoteHandler(@NotNull Editor editor, @NotNull PsiElement psiAtOffset) {
    final QuoteHandler fileTypeQuoteHandler = TypedHandler.getQuoteHandler(psiAtOffset.getContainingFile(), editor);
    return fileTypeQuoteHandler instanceof JavaLikeQuoteHandler
           ? (JavaLikeQuoteHandler)fileTypeQuoteHandler
           : null;
  }

  @Contract("_,null,_->false")
  protected boolean isInStringLiteral(@NotNull Editor editor, @Nullable JavaLikeQuoteHandler quoteHandler, int offset) {
    if (offset > 0 && quoteHandler != null) {
      EditorHighlighter highlighter = editor.getHighlighter();
      HighlighterIterator iterator = highlighter.createIterator(offset - 1);
      final IElementType type = iterator.getTokenType();
      if (StringEscapesTokenTypes.STRING_LITERAL_ESCAPES.contains(type) || isInsideLiteral(quoteHandler, iterator)) {
        return true;
      }
    }
    return false;
  }

  protected boolean isInsideLiteral(@NotNull JavaLikeQuoteHandler quoteHandler, @NotNull HighlighterIterator iterator) {
    return quoteHandler.isInsideLiteral(iterator);
  }
}
