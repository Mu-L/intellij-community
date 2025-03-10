// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jps.incremental.artifacts.instructions;

import com.dynatrace.hash4j.hashing.HashSink;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.FileFilters;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.FileUtilRt;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.builders.BuildOutputConsumer;
import org.jetbrains.jps.builders.logging.ProjectBuilderLogger;
import org.jetbrains.jps.cmdline.ProjectDescriptor;
import org.jetbrains.jps.incremental.CompileContext;
import org.jetbrains.jps.incremental.ProjectBuildException;
import org.jetbrains.jps.incremental.artifacts.ArtifactBuildTarget;
import org.jetbrains.jps.incremental.artifacts.ArtifactOutputToSourceMapping;
import org.jetbrains.jps.incremental.artifacts.IncArtifactBuilder;
import org.jetbrains.jps.incremental.artifacts.impl.JpsArtifactPathUtil;
import org.jetbrains.jps.incremental.messages.BuildMessage;
import org.jetbrains.jps.incremental.messages.CompilerMessage;
import org.jetbrains.jps.incremental.relativizer.PathRelativizerService;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;

@ApiStatus.Internal
public final class FileBasedArtifactRootDescriptor extends ArtifactRootDescriptor {
  private static final Logger LOG = Logger.getInstance(FileBasedArtifactRootDescriptor.class);
  private final FileCopyingHandler myCopyingHandler;

  public FileBasedArtifactRootDescriptor(@NotNull File file,
                                         @NotNull SourceFileFilter filter,
                                         int index,
                                         ArtifactBuildTarget target,
                                         @NotNull DestinationInfo destinationInfo, FileCopyingHandler copyingHandler) {
    super(file, createCompositeFilter(filter, copyingHandler.createFileFilter()), index, target, destinationInfo);
    myCopyingHandler = copyingHandler;
  }

  private static SourceFileFilter createCompositeFilter(SourceFileFilter baseFilter, FileFilter filter) {
    return filter == FileFilters.EVERYTHING ? baseFilter : new CompositeSourceFileFilter(baseFilter, filter);
  }

  @Override
  protected String getFullPath() {
    return myRoot.getPath();
  }

  @Override
  public void writeConfiguration(@NotNull HashSink hash, PathRelativizerService relativizer) {
    super.writeConfiguration(hash, relativizer);
    myCopyingHandler.writeConfiguration(hash);
  }

  @Override
  public void copyFromRoot(String filePath,
                           int rootIndex, String outputPath,
                           CompileContext context, BuildOutputConsumer outputConsumer,
                           ArtifactOutputToSourceMapping outSrcMapping) throws IOException, ProjectBuildException {
    final File file = new File(filePath);
    if (!file.exists()) return;
    String targetPath;
    if (!FileUtil.filesEqual(file, getRootFile())) {
      Path rootFile = getFile();
      String relativePath = FileUtil.getRelativePath(FileUtilRt.toSystemIndependentName(rootFile.toString()), filePath, '/');
      if (relativePath == null || relativePath.startsWith("..")) {
        throw new ProjectBuildException(new AssertionError(filePath + " is not under " + rootFile));
      }
      targetPath = JpsArtifactPathUtil.appendToPath(outputPath, relativePath);
    }
    else {
      targetPath = outputPath;
    }

    final File targetFile = new File(targetPath);
    if (FileUtil.filesEqual(file, targetFile)) {
      //do not process file if should be copied to itself. Otherwise the file will be included to source-to-output mapping and will be deleted by rebuild
      return;
    }

    if (outSrcMapping.getState(targetPath) == null) {
      ProjectBuilderLogger logger = context.getLoggingManager().getProjectBuilderLogger();
      if (logger.isEnabled()) {
        logger.logCompiledFiles(Collections.singletonList(file), IncArtifactBuilder.BUILDER_ID, "Copying file:");
      }

      try {
        myCopyingHandler.copyFile(file, targetFile, context);
      }
      catch (IOException e) {
        context.processMessage(new CompilerMessage(IncArtifactBuilder.getBuilderName(), BuildMessage.Kind.ERROR, CompilerMessage.getTextFromThrowable(e)));
        return;
      }

      outputConsumer.registerOutputFile(targetFile, Collections.singletonList(filePath));
    }
    else if (LOG.isDebugEnabled()) {
      LOG.debug("Target path " + targetPath + " is already registered so " + filePath + " won't be copied");
    }
    outSrcMapping.appendData(targetPath, rootIndex, filePath);
  }

  private static final class CompositeSourceFileFilter extends SourceFileFilter {
    private final SourceFileFilter myBaseFilter;
    private final FileFilter myFilter;

    CompositeSourceFileFilter(SourceFileFilter baseFilter, FileFilter filter) {
      myBaseFilter = baseFilter;
      myFilter = filter;
    }

    @Override
    public boolean accept(@NotNull String fullFilePath) {
      return myFilter.accept(new File(fullFilePath)) && myBaseFilter.accept(fullFilePath);
    }

    @Override
    public boolean shouldBeCopied(@NotNull String fullFilePath, ProjectDescriptor projectDescriptor) throws IOException {
      return myBaseFilter.shouldBeCopied(fullFilePath, projectDescriptor);
    }
  }
}
