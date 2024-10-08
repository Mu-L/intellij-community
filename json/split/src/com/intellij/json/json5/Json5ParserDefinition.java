// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.json.json5;

import com.intellij.json.JsonParserDefinition;
import com.intellij.json.psi.impl.JsonFileImpl;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IFileElementType;
import org.jetbrains.annotations.NotNull;

public class Json5ParserDefinition extends JsonParserDefinition {
  public static final IFileElementType FILE = new IFileElementType(Json5Language.INSTANCE);

  @Override
  public @NotNull Lexer createLexer(Project project) {
    return new Json5Lexer();
  }

  @Override
  public @NotNull PsiFile createFile(@NotNull FileViewProvider fileViewProvider) {
    return new JsonFileImpl(fileViewProvider, Json5Language.INSTANCE);
  }

  @Override
  public @NotNull IFileElementType getFileNodeType() {
    return FILE;
  }
}
