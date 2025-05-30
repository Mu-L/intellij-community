// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.javaFX.sceneBuilder;

import com.intellij.jarRepository.JarRepositoryManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.ReadonlyStatusHandler;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SideBorder;
import com.intellij.ui.components.panels.NonOpaquePanel;
import com.intellij.util.CurrentJavaVersion;
import com.intellij.util.download.DownloadableFileService;
import com.intellij.util.download.FileDownloader;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.maven.utils.library.RepositoryLibraryProperties;
import org.jetbrains.plugins.javaFX.JavaFXBundle;
import org.jetbrains.plugins.javaFX.fxml.JavaFxCommonNames;

import javax.swing.*;
import java.awt.*;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author Alexander Lobas
 */
public final class SceneBuilderEditor extends UserDataHolderBase implements FileEditor, EditorCallback {
  private static final Logger LOG = Logger.getInstance(SceneBuilderEditor.class);

  private static final String SCENE_CARD = "scene_builder";
  private static final String ERROR_CARD = "error";

  private final Project myProject;
  private final VirtualFile myFile;

  private final CardLayout myLayout = new CardLayout();
  private final JPanel myPanel = new JPanel(myLayout);

  private final JPanel myErrorPanel = new JPanel(new BorderLayout());
  private final EditorNotificationPanel myErrorNotification = new EditorNotificationPanel(EditorNotificationPanel.Status.Error);
  private JTextArea myErrorStack;

  private final Document myDocument;
  private final ExternalChangeListener myChangeListener;

  private SceneBuilder mySceneBuilder;
  private NonOpaquePanel myWrapper;

  public SceneBuilderEditor(@NotNull Project project, @NotNull VirtualFile file) {
    myProject = project;
    myFile = file;

    myDocument = FileDocumentManager.getInstance().getDocument(file);
    myChangeListener = new ExternalChangeListener();

    createErrorPage();
  }

  private void createErrorPage() {
    myErrorStack = new JTextArea(1, 20);
    myErrorStack.setBorder(JBUI.Borders.empty(UIUtil.LARGE_VGAP, 20));
    myErrorStack.setEditable(false);

    myWrapper = new NonOpaquePanel(myErrorNotification);
    myWrapper.setBorder(new SideBorder(JBUI.CurrentTheme.Banner.ERROR_BORDER_COLOR, SideBorder.BOTTOM));
    myWrapper.setVisible(false);

    myErrorPanel.add(myWrapper, BorderLayout.NORTH);
    myErrorPanel.add(ScrollPaneFactory.createScrollPane(myErrorStack), BorderLayout.CENTER);
    myPanel.add(myErrorPanel);
  }

  @SuppressWarnings("DialogTitleCapitalization")
  private void showErrorPage(Throwable e) {
    if (e != null) {
      LOG.info(e);
    }

    removeSceneBuilder();

    myWrapper.setVisible(true);
    myErrorNotification.clear();
    myErrorStack.setText("");

    if (CurrentJavaVersion.currentJavaVersion().feature > 11 &&
        e instanceof NoClassDefFoundError &&
        !SceneBuilderUtil.getSceneBuilder11Path().toFile().isFile()) {
      myErrorNotification.setText(JavaFXBundle.message("javafx.scene.builder.editor.failed.to.open.file.error"));
      myErrorNotification.createActionLabel(
        JavaFXBundle.message("javafx.scene.builder.editor.download.scene.builder.kit"),
        () -> {
          DownloadableFileService service = DownloadableFileService.getInstance();
          var description = service.createFileDescription("https://cache-redirector.jetbrains.com/" +
                                                          "intellij-dependencies/org/jetbrains/intellij/deps/scenebuilderkit/" +
                                                          SceneBuilderUtil.SCENE_BUILDER_VERSION + "/" + SceneBuilderUtil.SCENE_BUILDER_KIT_FULL_NAME, SceneBuilderUtil.SCENE_BUILDER_KIT_FULL_NAME);
          FileDownloader downloader = service.createDownloader(Collections.singletonList(description), "Scene Builder Kit");
          try {
            Path tempDir = Files.createTempDirectory("");

            final var list = downloader.downloadWithProgress(tempDir.toString(), myProject, myErrorPanel);
            if (list == null || list.isEmpty()) {
              myErrorNotification.clear();
              myErrorNotification.setText(JavaFXBundle.message("javafx.scene.builder.editor.failed.to.download.kit.error"));
              return;
            }

            FileUtil.copy(VfsUtilCore.virtualToIoFile(list.get(0).first), SceneBuilderUtil.getSceneBuilder11Path().toFile());
            FileUtil.delete(tempDir.toFile());

            SceneBuilderUtil.updateLoader();
            updateState();
          }
          catch (IOException e2) {
            LOG.warn("Can't download SceneBuilderKit", e2);
          }
        }
      );
      myLayout.show(myPanel, ERROR_CARD);
      return;
    }
    if (CurrentJavaVersion.currentJavaVersion().feature > 11) {
      try {
        Class.forName(JavaFxCommonNames.JAVAFX_SCENE_NODE);
      }
      catch (ClassNotFoundException exception) {
        myErrorNotification.clear();
        myErrorNotification.setText(JavaFXBundle.message("javafx.scene.builder.editor.failed.to.open.file.error"));
        myErrorNotification.createActionLabel(
          JavaFXBundle.message("javafx.scene.builder.editor.download.javafx"),
          () -> downloadJavaFxDependencies()
        );
        myLayout.show(myPanel, ERROR_CARD);
        return;
      }
    }

    final String description;
    if (e != null) {
      final List<String> messages = new ArrayList<>();
      for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
        final String message = getErrorMessage(t);
        if (messages.isEmpty() || !messages.get(messages.size() - 1).contains(message)) {
          messages.add(message);
        }
        else {
          messages.set(messages.size() - 1, message);
        }
      }
      Collections.reverse(messages);
      description = "\n" + String.join("\n\n", messages);
    }
    else {
      description = "Unknown error occurred";
    }

    myErrorNotification.setText(JavaFXBundle.message("javafx.scene.builder.editor.failed.to.open.file.error"));
    myErrorStack.setText(description);
    myErrorStack.setRows(description.split("\n").length);
    myLayout.show(myPanel, ERROR_CARD);
  }

  private void downloadJavaFxDependencies() {
    for (String coordinate : SceneBuilderUtil.JAVAFX_ARTIFACTS) {
      RepositoryLibraryProperties libraryProperties =
        new RepositoryLibraryProperties("org.openjfx:" + coordinate + ":" + SceneBuilderUtil.JAVAFX_VERSION, true);
      JarRepositoryManager.loadDependenciesModal(myProject, libraryProperties, false, false, null, null);
    }
    SceneBuilderUtil.updateLoader();
    updateState();
  }

  private static String getErrorMessage(Throwable e) {
    final String message = e.getMessage();
    final String className = e.getClass().getName();
    if (StringUtil.isEmpty(message)) {
      if (e instanceof ClassNotFoundException) {
        return className + ": Unresolved import";
      }
      return className;
    }
    if (!message.contains(className)) {
      return className + ": " + message;
    }
    return message;
  }

  @Override
  public void saveChanges(final String content) {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (mySceneBuilder != null) {

        if (!myDocument.isWritable() && ReadonlyStatusHandler.getInstance(myProject).ensureFilesWritable(Collections.singletonList(myFile)).hasReadonlyFiles()) {
          return;
        }

        try {
          myChangeListener.setRunState(false);

          // XXX: strange behavior with undo/redo

          ApplicationManager.getApplication().runWriteAction(() -> CommandProcessor.getInstance()
            .executeCommand(myProject, () -> myDocument.setText(content), JavaFXBundle.message("javafx.scene.builder.editor.scene.builder.edit.operation"), null));
        }
        finally {
          myChangeListener.setRunState(true);
        }
      }
    });
  }

  @Override
  public void handleError(final Throwable e) {
    UIUtil.invokeLaterIfNeeded(() -> showErrorPage(e));
  }

  private void updateState() {
    addSceneBuilder();
  }

  private void addSceneBuilder() {
    ApplicationManager.getApplication().invokeLater(this::addSceneBuilderImpl, ModalityState.defaultModalityState());
  }

  private void addSceneBuilderImpl() {
    try {
      ApplicationManager.getApplication().runWriteAction(() -> FileDocumentManager.getInstance().saveDocument(myDocument));

      if (mySceneBuilder != null && mySceneBuilder.reload()) {
        return;
      }
      removeSceneBuilder();
      mySceneBuilder = SceneBuilderUtil.create(new File(myFile.getPath()).toURI().toURL(), myProject, this);

      myPanel.add(mySceneBuilder.getPanel(), SCENE_CARD);
      myLayout.show(myPanel, SCENE_CARD);

      myChangeListener.start();
    }
    catch (Throwable e) {
      showErrorPage(e);
    }
  }

  private void removeSceneBuilder() {
    myChangeListener.stop();

    if (mySceneBuilder != null) {
      myPanel.remove(mySceneBuilder.getPanel());
      mySceneBuilder.close();
      mySceneBuilder = null;
    }
  }

  @Override
  public @NotNull JComponent getComponent() {
    return myPanel;
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() {
    return mySceneBuilder == null ? myErrorPanel : mySceneBuilder.getPanel();
  }

  @Override
  public void dispose() {
    removeSceneBuilder();
    myChangeListener.dispose();
  }

  @Override
  public @NotNull String getName() {
    return JavaFXBundle.message("scene.builder.editor.tab.name");
  }

  @Override
  public void selectNotify() {
    updateState();
  }

  @Override
  public void deselectNotify() {
    myChangeListener.stop();
  }

  @Override
  public void setState(@NotNull FileEditorState state) {
  }

  @Override
  public boolean isValid() {
    return myFile.isValid();
  }

  @Override
  public boolean isModified() {
    return false;
  }

  @Override
  public @NotNull VirtualFile getFile() {
    return myFile;
  }

  @Override
  public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
  }

  @Override
  public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
  }

  private final class ExternalChangeListener implements DocumentListener {
    private volatile boolean myRunState;

    ExternalChangeListener() {
      myDocument.addDocumentListener(this);
    }

    public void start() {
      if (!myRunState) {
        myRunState = true;
      }
    }

    public void stop() {
      if (myRunState) {
        myRunState = false;
      }
    }

    public void setRunState(boolean state) {
      myRunState = state;
    }

    public void dispose() {
      myDocument.removeDocumentListener(this);
    }

    @Override
    public void documentChanged(@NotNull DocumentEvent e) {
      if (myRunState) {
        addSceneBuilder();
      }
    }
  }
}
