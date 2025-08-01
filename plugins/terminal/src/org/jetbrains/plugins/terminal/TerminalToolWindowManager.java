// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.terminal;

import com.google.common.collect.Sets;
import com.intellij.frontend.FrontendApplicationInfo;
import com.intellij.frontend.FrontendType;
import com.intellij.ide.DataManager;
import com.intellij.ide.dnd.DnDDropHandler;
import com.intellij.ide.dnd.DnDEvent;
import com.intellij.ide.dnd.DnDSupport;
import com.intellij.ide.dnd.TransferableWrapper;
import com.intellij.idea.AppMode;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.NullableLazyValue;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.ToolWindowEx;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.terminal.JBTerminalWidget;
import com.intellij.terminal.JBTerminalWidgetListener;
import com.intellij.terminal.TerminalTitle;
import com.intellij.terminal.TerminalTitleListener;
import com.intellij.terminal.ui.TerminalWidget;
import com.intellij.terminal.ui.TerminalWidgetKt;
import com.intellij.toolWindow.InternalDecoratorImpl;
import com.intellij.ui.ExperimentalUI;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.awt.RelativeRectangle;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.docking.DockContainer;
import com.intellij.ui.docking.DockManager;
import com.intellij.ui.docking.DockableContent;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.PlatformUtils;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.text.UniqueNameGenerator;
import com.intellij.util.ui.UIUtil;
import kotlin.Unit;
import org.jetbrains.annotations.*;
import org.jetbrains.plugins.terminal.action.MoveTerminalToolWindowTabLeftAction;
import org.jetbrains.plugins.terminal.action.MoveTerminalToolWindowTabRightAction;
import org.jetbrains.plugins.terminal.action.RenameTerminalSessionAction;
import org.jetbrains.plugins.terminal.arrangement.TerminalArrangementManager;
import org.jetbrains.plugins.terminal.arrangement.TerminalArrangementState;
import org.jetbrains.plugins.terminal.arrangement.TerminalCommandHistoryManager;
import org.jetbrains.plugins.terminal.arrangement.TerminalWorkingDirectoryManager;
import org.jetbrains.plugins.terminal.block.reworked.FrontendTerminalTabsApi;
import org.jetbrains.plugins.terminal.block.reworked.session.TerminalSessionTab;
import org.jetbrains.plugins.terminal.fus.ReworkedTerminalUsageCollector;
import org.jetbrains.plugins.terminal.fus.TerminalFocusFusService;
import org.jetbrains.plugins.terminal.fus.TerminalOpeningWay;
import org.jetbrains.plugins.terminal.fus.TerminalStartupFusInfo;
import org.jetbrains.plugins.terminal.ui.TerminalContainer;
import org.jetbrains.plugins.terminal.vfs.TerminalSessionVirtualFileImpl;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Service(Service.Level.PROJECT)
public final class TerminalToolWindowManager implements Disposable {
  private static final Key<TerminalWidget> TERMINAL_WIDGET_KEY = new Key<>("TerminalWidget");
  private static final Logger LOG = Logger.getInstance(TerminalToolWindowManager.class);
  private static final Key<AbstractTerminalRunner<?>> RUNNER_KEY = Key.create("RUNNER_KEY");

  private ToolWindowEx myToolWindow;
  private final Project myProject;
  private final AbstractTerminalRunner<?> myTerminalRunner;
  private TerminalDockContainer myDockContainer;
  private final Map<TerminalWidget, TerminalContainer> myContainerByWidgetMap = new HashMap<>();
  /**
   * Stores IDs of the {@link TerminalSessionTab} that is stored on backend.
   * See {@link org.jetbrains.plugins.terminal.block.reworked.session.rpc.TerminalTabsManagerApi} for operations with tab ID.
   */
  private final Map<TerminalWidget, Integer> myTabIdByWidgetMap = new HashMap<>();

  private CompletableFuture<Void> myTabsRestoredFuture = CompletableFuture.completedFuture(null);

  public @NotNull AbstractTerminalRunner<?> getTerminalRunner() {
    return myTerminalRunner;
  }


  public ToolWindow getToolWindow() {
    return myToolWindow;
  }

  public TerminalToolWindowManager(@NotNull Project project) {
    myProject = project;
    myTerminalRunner = DefaultTerminalRunnerFactory.getInstance().create(project);
  }

  @Override
  public void dispose() {
  }

  /**
   * @deprecated use {@link #getTerminalWidgets()} instead
   */
  @ApiStatus.Internal
  @Deprecated
  public @Unmodifiable Set<JBTerminalWidget> getWidgets() {
    return ContainerUtil.map2SetNotNull(myContainerByWidgetMap.keySet(),
                                        widget -> JBTerminalWidget.asJediTermWidget(widget));
  }

  public @NotNull Set<TerminalWidget> getTerminalWidgets() {
    return Collections.unmodifiableSet(myContainerByWidgetMap.keySet());
  }

  private final List<Consumer<TerminalWidget>> myTerminalSetupHandlers = new CopyOnWriteArrayList<>();

  public void addNewTerminalSetupHandler(@NotNull Consumer<TerminalWidget> listener, @NotNull Disposable parentDisposable) {
    myTerminalSetupHandlers.add(listener);
    if (!Disposer.tryRegister(parentDisposable, () -> { myTerminalSetupHandlers.remove(listener); })) {
      myTerminalSetupHandlers.remove(listener);
    }
  }

  public static TerminalToolWindowManager getInstance(@NotNull Project project) {
    return project.getService(TerminalToolWindowManager.class);
  }

  void initToolWindow(@NotNull ToolWindowEx toolWindow) {
    if (myToolWindow != null) {
      LOG.error("Terminal tool window already initialized");
      return;
    }
    myToolWindow = toolWindow;

    toolWindow.setTabActions(ActionManager.getInstance().getAction("TerminalToolwindowActionGroup"));
    toolWindow.setTabDoubleClickActions(Collections.singletonList(new RenameTerminalSessionAction()));

    ToolWindowContentUi.setAllowTabsReordering(toolWindow, true);

    myProject.getMessageBus().connect(toolWindow.getDisposable())
      .subscribe(ToolWindowManagerListener.TOPIC, new ToolWindowManagerListener() {
        @Override
        public void toolWindowShown(@NotNull ToolWindow toolWindow) {
          var startupFusInfo = new TerminalStartupFusInfo(TerminalOpeningWay.OPEN_TOOLWINDOW);

          if (isTerminalToolWindow(toolWindow) && myToolWindow == toolWindow &&
              toolWindow.isVisible() && toolWindow.getContentManager().isEmpty()) {
            if (myTabsRestoredFuture.isDone()) {
              // Open a new session if all tabs were closed manually.
              createNewTab(TerminalOptionsProvider.getInstance().getTerminalEngine(), startupFusInfo, null, null);
            }
            else {
              // Wait for tabs restoration for some time and check if there are any tabs restored.
              Runnable createSessionIfNeeded = () -> {
                ApplicationManager.getApplication().invokeLater(() -> {
                  if (!myProject.isDisposed() && toolWindow.getContentManager().isEmpty()) {
                    createNewTab(TerminalOptionsProvider.getInstance().getTerminalEngine(), startupFusInfo, null, null);
                  }
                }, ModalityState.any());
              };

              myTabsRestoredFuture.thenRun(createSessionIfNeeded)
                .orTimeout(2, TimeUnit.SECONDS)
                .exceptionally((t) -> {
                  createSessionIfNeeded.run();
                  return null;
                });
            }
          }
        }
      });

    installDirectoryDnD(toolWindow);

    if (myDockContainer == null) {
      myDockContainer = new TerminalDockContainer();
      DockManager.getInstance(myProject).register(myDockContainer, toolWindow.getDisposable());
    }

    var focusService = TerminalFocusFusService.getInstance();
    if (focusService != null) { // the service only exists on the frontend
      focusService.ensureInitialized();
    }
  }

  /** Restores tabs for Classic Terminal and New Terminal Gen1. */
  void restoreTabsLocal(@Nullable TerminalArrangementState arrangementState) {
    ContentManager contentManager = myToolWindow.getContentManager();

    if (arrangementState != null) {
      for (TerminalTabState tabState : arrangementState.myTabStates) {
        TerminalEngine engine = TerminalOptionsProvider.getInstance().getTerminalEngine();
        createNewSession(null, myTerminalRunner, engine, tabState, null, null, false, true);
      }

      Content content = contentManager.getContent(arrangementState.mySelectedTabIndex);
      if (content != null) {
        contentManager.setSelectedContent(content);
      }
    }
  }

  /**
   * Requests tabs from the backend and reopens them asynchronously.
   * Should be used only with Reworked Terminal (Gen2).
   */
  void restoreTabsFromBackend() {
    myTabsRestoredFuture = new CompletableFuture<Void>()
      .orTimeout(5, TimeUnit.SECONDS)
      .exceptionally((t) -> {
        LOG.error("Failed to restore tabs from the backend in the given timeout", t);
        return null;
      });

    FrontendTerminalTabsApi.getInstance(myProject).getStoredTerminalTabs().thenAccept(tabs -> {
      ApplicationManager.getApplication().invokeLater(() -> {
        doRestoreTabsFromBackend(tabs);
        // Store tabs to the local state too. To not lose the stored tabs in case of disabling the Gen2 Terminal.
        TerminalArrangementManager.getInstance(myProject).setToolWindow(myToolWindow);
        myTabsRestoredFuture.complete(null);
      }, ModalityState.any());
    });
  }

  private void doRestoreTabsFromBackend(List<TerminalSessionTab> tabs) {
    for (TerminalSessionTab tab : tabs) {
      TerminalTabState tabState = new TerminalTabState();
      //noinspection HardCodedStringLiteral
      tabState.myTabName = tab.getName();
      tabState.myIsUserDefinedTabTitle = tab.isUserDefinedName();
      tabState.myShellCommand = tab.getShellCommand();
      tabState.myWorkingDirectory = tab.getWorkingDirectory();

      createNewSession(null, myTerminalRunner, TerminalEngine.REWORKED, tabState, tab, null, false, true);
    }

    ReworkedTerminalUsageCollector.logSessionRestored(myProject, tabs.size());

    ContentManager contentManager = myToolWindow.getContentManager();
    Content firstContent = contentManager.getContent(0);
    if (firstContent != null) {
      contentManager.setSelectedContent(firstContent);
    }
  }

  //------------ Classic Terminal tab creation API methods start ------------------------------------

  /** Creates the <b>Classic</b> terminal tab regardless of the {@link TerminalEngine} state in the {@link TerminalOptionsProvider} */
  public @NotNull TerminalWidget createNewSession() {
    return createNewSession(null, myTerminalRunner, TerminalEngine.CLASSIC, null, null, null, true, true);
  }

  /** Creates the <b>Classic</b> terminal tab regardless of the {@link TerminalEngine} state in the {@link TerminalOptionsProvider} */
  public void createNewSession(@NotNull AbstractTerminalRunner<?> terminalRunner) {
    createNewSession(null, terminalRunner, TerminalEngine.CLASSIC, null, null, null, true, true);
  }

  /** Creates the <b>Classic</b> terminal tab regardless of the {@link TerminalEngine} state in the {@link TerminalOptionsProvider} */
  public void createNewSession(@NotNull AbstractTerminalRunner<?> terminalRunner, @Nullable TerminalTabState tabState) {
    createNewSession(null, terminalRunner, TerminalEngine.CLASSIC, tabState, null, null, true, true);
  }

  @ApiStatus.Experimental
  public void createNewSession(@Nullable AbstractTerminalRunner<?> terminalRunner,
                               @Nullable TerminalTabState tabState,
                               @Nullable ContentManager contentManager) {
    var runner = terminalRunner != null ? terminalRunner : myTerminalRunner;
    createNewSession(contentManager, runner, TerminalEngine.CLASSIC, tabState, null, null, true, true);
  }

  /** Creates the <b>Classic</b> terminal tab regardless of the {@link TerminalEngine} state in the {@link TerminalOptionsProvider} */
  public @NotNull TerminalWidget createShellWidget(@Nullable String workingDirectory,
                                                   @Nullable @Nls String tabName,
                                                   boolean requestFocus,
                                                   boolean deferSessionStartUntilUiShown) {
    return createNewSession(workingDirectory, tabName, null, requestFocus, deferSessionStartUntilUiShown);
  }

  /** Creates the <b>Classic</b> terminal tab regardless of the {@link TerminalEngine} state in the {@link TerminalOptionsProvider} */
  public @NotNull Content newTab(@NotNull ToolWindow toolWindow, @Nullable TerminalWidget terminalWidget) {
    return createNewTab(null, terminalWidget, myTerminalRunner, TerminalEngine.CLASSIC, null, null, null, true, true);
  }

  /** Creates the <b>Classic</b> terminal tab regardless of the {@link TerminalEngine} state in the {@link TerminalOptionsProvider} */
  public void openTerminalIn(@Nullable VirtualFile fileToOpen) {
    TerminalTabState state = new TerminalTabState();
    if (fileToOpen != null) {
      state.myWorkingDirectory = fileToOpen.getPath();
    }
    createNewSession(null, myTerminalRunner, TerminalEngine.CLASSIC, state, null, null, true, true);
  }

  //------------ Classic Terminal tab creation API methods end --------------------------------------

  /** Creates the <b>Classic</b> terminal tab regardless of the {@link TerminalEngine} state in the {@link TerminalOptionsProvider} */
  @ApiStatus.Internal
  public @NotNull TerminalWidget createNewSession(@Nullable String workingDirectory,
                                                  @Nullable @Nls String tabName,
                                                  @Nullable List<String> shellCommand,
                                                  boolean requestFocus,
                                                  boolean deferSessionStartUntilUiShown) {
    TerminalTabState tabState = new TerminalTabState();
    tabState.myTabName = tabName;
    tabState.myWorkingDirectory = workingDirectory;
    tabState.myShellCommand = shellCommand;
    return createNewSession(null, myTerminalRunner, TerminalEngine.CLASSIC, tabState, null, null,
                            requestFocus, deferSessionStartUntilUiShown);
  }

  /**
   * Creates the new tab with the terminal implementation of the specified {@link TerminalEngine}.
   * Note, that for {@link TerminalEngine#NEW_TERMINAL} and {@link TerminalEngine#REWORKED} some additional checks may be performed.
   * For example, for New UI or RemDev.
   * If these checks are not satisfied, the effective engine will be {@link TerminalEngine#CLASSIC}.
   */
  @ApiStatus.Internal
  public @NotNull TerminalWidget createNewTab(@NotNull TerminalEngine preferredEngine,
                                              @Nullable TerminalStartupFusInfo startupFusInfo,
                                              @Nullable TerminalTabState tabState,
                                              @Nullable ContentManager contentManager) {
    return createNewSession(contentManager, myTerminalRunner, preferredEngine, tabState, null, startupFusInfo, true, true);
  }

  /**
   * @param contentManager pass child content manager of the Terminal tool window to open the tab in the specific split area.
   * If null is provided, the tab will be opened in the top-left splitter.
   * If there are no splits, the tab will be opened in the main tool window area.
   */
  private @NotNull TerminalWidget createNewSession(@Nullable ContentManager contentManager,
                                                   @NotNull AbstractTerminalRunner<?> terminalRunner,
                                                   @NotNull TerminalEngine preferredEngine,
                                                   @Nullable TerminalTabState tabState,
                                                   @Nullable TerminalSessionTab sessionTab,
                                                   @Nullable TerminalStartupFusInfo startupFusInfo,
                                                   boolean requestFocus,
                                                   boolean deferSessionStartUntilUiShown) {
    Content content = createNewTab(contentManager, null, terminalRunner, preferredEngine, tabState, sessionTab,
                                   startupFusInfo, requestFocus, deferSessionStartUntilUiShown);
    return Objects.requireNonNull(content.getUserData(TERMINAL_WIDGET_KEY));
  }

  private @NotNull ToolWindow getOrInitToolWindow() {
    ToolWindow toolWindow = myToolWindow;
    if (toolWindow == null) {
      toolWindow = ToolWindowManager.getInstance(myProject).getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID);
      Objects.requireNonNull(toolWindow).getContentManager(); // to call #initToolWindow
      LOG.assertTrue(toolWindow == myToolWindow);
    }
    return toolWindow;
  }

  private @NotNull Content createNewTab(@Nullable ContentManager contentManager,
                                        @Nullable TerminalWidget terminalWidget,
                                        @NotNull AbstractTerminalRunner<?> terminalRunner,
                                        @NotNull TerminalEngine preferredEngine,
                                        @Nullable TerminalTabState tabState,
                                        @Nullable TerminalSessionTab sessionTab,
                                        @Nullable TerminalStartupFusInfo startupFusInfo,
                                        boolean requestFocus,
                                        boolean deferSessionStartUntilUiShown) {
    ToolWindow toolWindow = getOrInitToolWindow();
    TerminalStartupMoment startupMoment = requestFocus && deferSessionStartUntilUiShown ? new TerminalStartupMoment() : null;
    Content content = createTerminalContent(terminalRunner, preferredEngine, terminalWidget, tabState,
                                            sessionTab, startupFusInfo, deferSessionStartUntilUiShown, startupMoment);

    ContentManager manager = contentManager != null ? contentManager : toolWindow.getContentManager();
    manager.addContent(content);
    Runnable selectRunnable = () -> {
      manager.setSelectedContent(content, requestFocus);
    };
    if (requestFocus && !toolWindow.isActive()) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Activating " + toolWindow.getId() + " tool window");
      }
      toolWindow.activate(selectRunnable, true, true);
    }
    else {
      selectRunnable.run();
    }

    int tabsCount = toolWindow.getContentManager().getContentsRecursively().size();
    ReworkedTerminalUsageCollector.logTabOpened(myProject, tabsCount);

    return content;
  }

  private static @Nls String generateUniqueName(@Nls String suggestedName, List<@Nls String> tabs) {
    final Set<String> names = Sets.newHashSet(tabs);

    return UniqueNameGenerator.generateUniqueName(suggestedName, "", "", " (", ")", o -> !names.contains(o));
  }

  /**
   * Creates the {@link Content} with the terminal implementation of the specified {@link TerminalEngine}.
   * Note that the created content is not added to the tool window's {@link ContentManager} yet.
   */
  @ApiStatus.Internal
  public @NotNull Content createTerminalContent(@NotNull AbstractTerminalRunner<?> terminalRunner,
                                                @NotNull TerminalEngine preferredEngine,
                                                @Nullable TerminalWidget terminalWidget,
                                                @Nullable TerminalTabState tabState,
                                                @Nullable TerminalSessionTab sessionTab,
                                                @Nullable TerminalStartupFusInfo startupFusInfo,
                                                boolean deferSessionStartUntilUiShown,
                                                @Nullable TerminalStartupMoment startupMoment) {
    ToolWindow toolWindow = getOrInitToolWindow();
    TerminalToolWindowPanel panel = new TerminalToolWindowPanel();

    Content content = ContentFactory.getInstance().createContent(panel, null, false);

    TerminalWidget widget = terminalWidget;
    if (widget == null) {
      String currentWorkingDir = terminalRunner.getCurrentWorkingDir(tabState);
      NullableLazyValue<Path> commandHistoryFileLazyValue = NullableLazyValue.atomicLazyNullable(() -> {
        return TerminalCommandHistoryManager.getInstance().getOrCreateCommandHistoryFile(
          tabState != null ? tabState.myCommandHistoryFileName : null,
          myProject
        );
      });
      ShellStartupOptions startupOptions = new ShellStartupOptions.Builder()
        .workingDirectory(currentWorkingDir)
        .shellCommand(tabState != null ? tabState.myShellCommand : null)
        .commandHistoryFileProvider(() -> commandHistoryFileLazyValue.getValue())
        .startupMoment(startupMoment)
        .build();
      widget = startShellTerminalWidget(content, terminalRunner, startupOptions, preferredEngine, sessionTab, startupFusInfo,
                                        deferSessionStartUntilUiShown, true, content);
      widget.getTerminalTitle().change(state -> {
        if (state.getDefaultTitle() == null) {
          state.setDefaultTitle(terminalRunner.getDefaultTabTitle());
        }
        return Unit.INSTANCE;
      });
      TerminalWorkingDirectoryManager.setInitialWorkingDirectory(content, currentWorkingDir);
    }
    else {
      TerminalWidgetKt.setNewParentDisposable(terminalWidget, content);
    }

    if (tabState != null && tabState.myTabName != null) {
      widget.getTerminalTitle().change(state -> {
        if (tabState.myIsUserDefinedTabTitle) {
          state.setUserDefinedTitle(tabState.myTabName);
        }
        else {
          state.setDefaultTitle(tabState.myTabName);
        }
        return null;
      });
    }
    updateTabTitle(widget, toolWindow, content);
    setupTerminalWidget(toolWindow, terminalRunner, widget, content);

    content.setCloseable(true);
    content.putUserData(TERMINAL_WIDGET_KEY, widget);
    content.putUserData(RUNNER_KEY, terminalRunner);

    TerminalContainer container = new TerminalContainer(myProject, content, widget, this);
    panel.setContent(container.getWrapperPanel());
    panel.addFocusListener(createFocusListener(toolWindow));

    TerminalWidget finalWidget = widget;
    myTerminalSetupHandlers.forEach(consumer -> consumer.accept(finalWidget));

    content.setPreferredFocusedComponent(() -> finalWidget.getPreferredFocusableComponent());
    //noinspection ResultOfObjectAllocationIgnored
    new TerminalTabCloseListener(content, myProject, this);

    return content;
  }

  private void setupTerminalWidget(@NotNull ToolWindow toolWindow,
                                   @NotNull AbstractTerminalRunner<?> runner,
                                   @NotNull TerminalWidget widget,
                                   @NotNull Content content) {
    MoveTerminalToolWindowTabLeftAction moveTabLeftAction = new MoveTerminalToolWindowTabLeftAction();
    MoveTerminalToolWindowTabRightAction moveTabRightAction = new MoveTerminalToolWindowTabRightAction();

    widget.getTerminalTitle().addTitleListener(new TerminalTitleListener() {
      @Override
      public void onTitleChanged(@NotNull TerminalTitle terminalTitle) {
        ApplicationManager.getApplication().invokeLater(() -> {
          updateTabTitle(widget, toolWindow, content);
        }, myProject.getDisposed());
      }
    }, content);
    JBTerminalWidget terminalWidget = JBTerminalWidget.asJediTermWidget(widget);
    if (terminalWidget == null) return;

    terminalWidget.setListener(new JBTerminalWidgetListener() {
      @Override
      public void onNewSession() {
        createNewTab(TerminalOptionsProvider.getInstance().getTerminalEngine(), null, null, content.getManager());
      }

      @Override
      public void onTerminalStarted() {
      }

      @Override
      public void onPreviousTabSelected() {
        var contentManager = content.getManager();
        if (contentManager != null && contentManager.getContentCount() > 1) {
          contentManager.selectPreviousContent();
        }
      }

      @Override
      public void onNextTabSelected() {
        var contentManager = content.getManager();
        if (contentManager != null && contentManager.getContentCount() > 1) {
          contentManager.selectNextContent();
        }
      }

      @Override
      public void onSessionClosed() {
        TerminalContainer container = getContainer(widget);
        if (container != null) {
          container.closeAndHide();
        }
      }

      @Override
      public void showTabs() {
        performAction("ShowContent");
      }

      @Override
      public void moveTabRight() {
        moveTabRightAction.move(content, myProject);
      }

      @Override
      public void moveTabLeft() {
        moveTabLeftAction.move(content, myProject);
      }

      @Override
      public boolean canMoveTabRight() {
        return moveTabRightAction.isAvailable(content);
      }

      @Override
      public boolean canMoveTabLeft() {
        return moveTabLeftAction.isAvailable(content);
      }

      @Override
      public boolean canSplit(boolean vertically) {
        var actionId = vertically ? "TW.SplitRight" : "TW.SplitDown";
        return isActionEnabled(actionId);
      }

      @Override
      public void split(boolean vertically) {
        var actionId = vertically ? "TW.SplitRight" : "TW.SplitDown";
        performAction(actionId);
      }

      @Override
      public boolean isGotoNextSplitTerminalAvailable() {
        return isActionEnabled("TW.MoveToNextSplitter");
      }

      @Override
      public void gotoNextSplitTerminal(boolean forward) {
        var actionId = forward ? "TW.MoveToNextSplitter" : "TW.MoveToPreviousSplitter";
        performAction(actionId);
      }

      private boolean isActionEnabled(@NotNull String actionId) {
        AnAction action = ActionManager.getInstance().getAction(actionId);
        if (action == null) return false;

        var event = createActionEvent(action);
        AnActionResult result = ActionUtil.updateAction(action, event);
        if (!result.isPerformed()) return false;

        return event.getPresentation().isEnabled();
      }

      private void performAction(@NotNull String actionId) {
        AnAction action = ActionManager.getInstance().getAction(actionId);
        if (action == null) return;

        var event = createActionEvent(action);
        ActionUtil.performAction(action, event);
      }

      private @NotNull AnActionEvent createActionEvent(@NotNull AnAction action) {
        var dataContext = DataManager.getInstance().getDataContext(widget.getComponent());
        return AnActionEvent.createEvent(action, dataContext, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null);
      }
    });
  }

  private void updateTabTitle(@NotNull TerminalWidget widget,
                              @NotNull ToolWindow toolWindow,
                              @NotNull Content content) {
    TerminalTitle title = widget.getTerminalTitle();
    String titleString = title.buildTitle();
    List<String> tabs = toolWindow.getContentManager().getContentsRecursively().stream()
      .filter(c -> c != content)
      .map(c -> c.getDisplayName()).toList();
    String generatedName = generateUniqueName(titleString, tabs);

    Integer tabId = getTabIdByWidget(widget);
    if (tabId != null) {
      boolean isDefinedByUser = Objects.equals(generatedName, title.getUserDefinedTitle());
      FrontendTerminalTabsApi.getInstance(myProject).renameTerminalTab(tabId, generatedName, isDefinedByUser);
    }

    content.setDisplayName(generatedName);
    title.change((state) -> {
      state.setDefaultTitle(generatedName);
      return Unit.INSTANCE;
    });
  }

  public void register(@NotNull TerminalContainer terminalContainer) {
    myContainerByWidgetMap.put(terminalContainer.getTerminalWidget(), terminalContainer);
  }

  public void unregister(@NotNull TerminalContainer terminalContainer) {
    myContainerByWidgetMap.remove(terminalContainer.getTerminalWidget());
    if (terminalContainer.getContent().getUserData(TERMINAL_WIDGET_KEY) == terminalContainer.getTerminalWidget()) {
      terminalContainer.getContent().putUserData(TERMINAL_WIDGET_KEY, findWidgetForContent(terminalContainer.getContent()));
    }
  }

  private @Nullable TerminalWidget findWidgetForContent(@NotNull Content content) {
    TerminalWidget any = null;
    for (Map.Entry<TerminalWidget, TerminalContainer> entry : myContainerByWidgetMap.entrySet()) {
      if (entry.getValue().getContent() == content) {
        TerminalWidget terminalWidget = entry.getKey();
        any = terminalWidget;
        if (terminalWidget.hasFocus()) {
          return terminalWidget;
        }
      }
    }
    return any;
  }

  public @Nullable TerminalContainer getContainer(@NotNull TerminalWidget terminalWidget) {
    return myContainerByWidgetMap.get(terminalWidget);
  }

  public void closeTab(@NotNull Content content) {
    var manager = content.getManager();
    if (manager != null) {
      manager.removeContent(content, true, true, true);
    }
  }

  private static @NotNull FocusListener createFocusListener(@NotNull ToolWindow toolWindow) {
    return new FocusListener() {
      @Override
      public void focusGained(FocusEvent e) {
        JComponent component = getComponentToFocus(toolWindow);
        if (component != null) {
          component.requestFocusInWindow();
        }
      }

      @Override
      public void focusLost(FocusEvent e) {
      }
    };
  }

  private static @Nullable JComponent getComponentToFocus(@NotNull ToolWindow toolWindow) {
    Content selectedContent = toolWindow.getContentManager().getSelectedContent();
    if (selectedContent != null) {
      return selectedContent.getPreferredFocusableComponent();
    }
    else {
      return toolWindow.getComponent();
    }
  }

  private @NotNull TerminalWidget startShellTerminalWidget(@NotNull Content content,
                                                           @NotNull AbstractTerminalRunner<?> terminalRunner,
                                                           @NotNull ShellStartupOptions startupOptions,
                                                           @NotNull TerminalEngine preferredEngine,
                                                           @Nullable TerminalSessionTab existingTab,
                                                           @Nullable TerminalStartupFusInfo startupFusInfo,
                                                           boolean deferSessionStartUntilUiShown,
                                                           boolean updateTabTitleOnBackend,
                                                           @NotNull Disposable parentDisposable) {
    TerminalWidget widget;

    FrontendType frontendType = FrontendApplicationInfo.INSTANCE.getFrontendType();
    boolean isAnyRemoteDev = frontendType instanceof FrontendType.Remote || PlatformUtils.isJetBrainsClient() || AppMode.isRemoteDevHost();
    boolean isCodeWithMe = frontendType instanceof FrontendType.Remote remote && remote.isGuest();

    TerminalWidgetProvider provider = TerminalWidgetProvider.getProvider();
    // Run Reworked Terminal (Gen2) only if the default terminal runner was specified.
    // Do not enable it in CodeWithMe since it is not adapted to this mode.
    if (preferredEngine == TerminalEngine.REWORKED &&
        ExperimentalUI.isNewUI() &&
        terminalRunner == myTerminalRunner &&
        provider != null &&
        !isCodeWithMe) {
      widget = startReworkedShellTerminalWidget(provider, content, startupOptions, existingTab, startupFusInfo,
                                                deferSessionStartUntilUiShown, updateTabTitleOnBackend, parentDisposable);
    }
    // Run New Terminal (Gen1) only if the default terminal runner was specified.
    // Do not enable it in remote dev since it is not adapted to this mode.
    else if (preferredEngine == TerminalEngine.NEW_TERMINAL &&
             ExperimentalUI.isNewUI() &&
             terminalRunner == myTerminalRunner &&
             !isAnyRemoteDev) {
      // Use the specific runner that will start the terminal with the corresponding shell integration.
      var runner = new LocalBlockTerminalRunner(myProject);
      widget = runner.startShellTerminalWidget(parentDisposable, startupOptions, deferSessionStartUntilUiShown);
    }
    // Otherwise start the Classic Terminal with the provided runner.
    else {
      widget = terminalRunner.startShellTerminalWidget(parentDisposable, startupOptions, deferSessionStartUntilUiShown);
    }

    return widget;
  }

  private @NotNull TerminalWidget startReworkedShellTerminalWidget(@NotNull TerminalWidgetProvider provider,
                                                                   @NotNull Content content,
                                                                   @NotNull ShellStartupOptions startupOptions,
                                                                   @Nullable TerminalSessionTab existingTab,
                                                                   @Nullable TerminalStartupFusInfo startupFusInfo,
                                                                   boolean deferSessionStartUntilUiShown,
                                                                   boolean updateTabTitleOnBackend,
                                                                   @NotNull Disposable parentDisposable) {
    TerminalWidget widget = provider.createTerminalWidget(myProject, startupFusInfo, parentDisposable);

    Disposer.register(widget, new Disposable() {
      @Override
      public void dispose() {
        // Backend terminal session tab lifecycle is not directly bound to the Tool Window tab lifecycle.
        // We need to close the backend tab when the tool window tab is closed explicitly.
        // And don't need it when a user is closing the project leaving the terminal tabs opened: to be able to reconnect back.
        // So we send close event only if the tab is closed explicitly: backend will close it on its termination.
        // It is not easy to determine whether it is explicit closing or not, so we use the heuristic.
        Integer sessionTabId = getTabIdByWidget(widget);
        boolean isProjectClosing = myToolWindow.getContentManager().isDisposed();
        if (sessionTabId != null && !isProjectClosing) {
          FrontendTerminalTabsApi.getInstance(myProject).closeTerminalTab(sessionTabId);
          bindTabIdToWidget(widget, null);
        }
      }
    });

    Consumer<TerminalSessionTab> bindTabIdAndStartSession = (TerminalSessionTab tab) -> {
      bindTabIdToWidget(widget, tab.getId());
      if (updateTabTitleOnBackend) {
        // Update the tab title on backend because all previous updates were ignored since we didn't have a tab ID.
        updateTabTitle(widget, myToolWindow, content);
      }
      FrontendTerminalTabsApi
        .getInstance(myProject)
        .startTerminalSessionForWidget(widget, startupOptions, tab, deferSessionStartUntilUiShown);
    };

    if (existingTab != null) {
      bindTabIdAndStartSession.accept(existingTab);
    }
    else {
      FrontendTerminalTabsApi.getInstance(myProject).createNewTerminalTab().thenAccept((tab) -> {
        ApplicationManager.getApplication().invokeLater(() -> {
          if (!myProject.isDisposed()) {
            bindTabIdAndStartSession.accept(tab);
          }
        }, ModalityState.any());
      });
    }

    return widget;
  }

  private @Nullable Integer getTabIdByWidget(@NotNull TerminalWidget widget) {
    return myTabIdByWidgetMap.get(widget);
  }

  private void bindTabIdToWidget(@NotNull TerminalWidget widget, @Nullable Integer tabId) {
    myTabIdByWidgetMap.put(widget, tabId);
  }

  public static @Nullable JBTerminalWidget getWidgetByContent(@NotNull Content content) {
    TerminalWidget data = content.getUserData(TERMINAL_WIDGET_KEY);
    return data != null ? JBTerminalWidget.asJediTermWidget(data) : null;
  }

  public static @Nullable TerminalWidget findWidgetByContent(@NotNull Content content) {
    return content.getUserData(TERMINAL_WIDGET_KEY);
  }

  public static @Nullable AbstractTerminalRunner<?> getRunnerByContent(@NotNull Content content) {
    return content.getUserData(RUNNER_KEY);
  }

  public void detachWidgetAndRemoveContent(@NotNull Content content) {
    ContentManager contentManager = content.getManager();
    if (contentManager == null) {
      throw new IllegalStateException("Content manager is null for " + content);
    }
    TerminalTabCloseListener.Companion.executeContentOperationSilently(content, () -> {
      contentManager.removeContent(content, true);
      return Unit.INSTANCE;
    });
    content.putUserData(TERMINAL_WIDGET_KEY, null);
  }

  public static boolean isInTerminalToolWindow(@NotNull JBTerminalWidget widget) {
    DataContext dataContext = DataManager.getInstance().getDataContext(widget.getTerminalPanel());
    ToolWindow toolWindow = dataContext.getData(PlatformDataKeys.TOOL_WINDOW);
    return isTerminalToolWindow(toolWindow);
  }

  public static boolean isTerminalToolWindow(@Nullable ToolWindow toolWindow) {
    return toolWindow != null && TerminalToolWindowFactory.TOOL_WINDOW_ID.equals(toolWindow.getId());
  }

  /**
   * Creates the new terminal tab on dropping the directory node inside the terminal tool window.
   * For example, from Project View.
   */
  private void installDirectoryDnD(@NotNull ToolWindow window) {
    var toolWindowEx = window instanceof ToolWindowEx ? (ToolWindowEx)window : null;
    if (toolWindowEx == null) return;

    DnDDropHandler handler = new DnDDropHandler() {
      @Override
      public void drop(DnDEvent event) {
        TransferableWrapper tw = ObjectUtils.tryCast(event.getAttachedObject(), TransferableWrapper.class);
        if (tw != null) {
          PsiDirectory dir = getDirectory(ArrayUtil.getFirstElement(tw.getPsiElements()));
          if (dir != null && tw.getPsiElements().length == 1) {
            // Find the right split to create the new tab in
            var nearestManager = findNearestContentManager(event);

            TerminalTabState state = new TerminalTabState();
            state.myWorkingDirectory = dir.getVirtualFile().getPath();
            createNewTab(TerminalOptionsProvider.getInstance().getTerminalEngine(), null, state, nearestManager);
          }
        }
      }
    };
    DnDSupport.createBuilder(toolWindowEx.getDecorator())
      .setDropHandler(handler)
      .setDisposableParent(this)
      .disableAsSource()
      .install();
  }

  private static @Nullable ContentManager findNearestContentManager(@NotNull DnDEvent event) {
    Component handlerComponent = event.getHandlerComponent();
    Point point = event.getPoint();
    if (handlerComponent == null || point == null) return null;

    Component deepestComponent = UIUtil.getDeepestComponentAt(handlerComponent, point.x, point.y);
    return findNearestContentManager(deepestComponent);
  }

  private static @Nullable ContentManager findNearestContentManager(@Nullable Component component) {
    if (component == null) return null;
    var dataContext = DataManager.getInstance().getDataContext(component);
    return dataContext.getData(PlatformDataKeys.TOOL_WINDOW_CONTENT_MANAGER);
  }

  private static @Nullable PsiDirectory getDirectory(@Nullable PsiElement item) {
    if (item instanceof PsiFile) {
      return ((PsiFile)item).getParent();
    }
    return ObjectUtils.tryCast(item, PsiDirectory.class);
  }

  /**
   * Terminal tab can be moved to the editor using {@link org.jetbrains.plugins.terminal.action.MoveTerminalSessionToEditorAction}.
   * This dock container is responsible for an ability to drop the editor tab with the terminal
   * back to the terminal tool window.
   */
  private final class TerminalDockContainer implements DockContainer {
    @Override
    public @NotNull RelativeRectangle getAcceptArea() {
      return new RelativeRectangle(myToolWindow.getDecorator());
    }

    @Override
    public @NotNull ContentResponse getContentResponse(@NotNull DockableContent content, RelativePoint point) {
      return isTerminalSessionContent(content) ? ContentResponse.ACCEPT_MOVE : ContentResponse.DENY;
    }

    @Override
    public @NotNull JComponent getContainerComponent() {
      return myToolWindow.getDecorator();
    }

    @Override
    public void add(@NotNull DockableContent content, RelativePoint dropTarget) {
      if (dropTarget == null) return;
      if (isTerminalSessionContent(content)) {
        // Find the right split to create the new tab in
        Component component = dropTarget.getOriginalComponent();
        Point point = dropTarget.getOriginalPoint();
        Component deepestComponent = UIUtil.getDeepestComponentAt(component, point.x, point.y);
        ContentManager nearestManager = findNearestContentManager(deepestComponent);

        TerminalSessionVirtualFileImpl terminalFile = (TerminalSessionVirtualFileImpl)content.getKey();
        var engine = TerminalEngine.CLASSIC; // Engine doesn't matter here because we will reuse the existing terminal widget.
        Content newContent = createNewTab(nearestManager, terminalFile.getTerminalWidget(), myTerminalRunner, engine,
                                          null, null, null, true, true);
        newContent.setDisplayName(terminalFile.getName());
      }
    }

    private static boolean isTerminalSessionContent(@NotNull DockableContent<?> content) {
      return content.getKey() instanceof TerminalSessionVirtualFileImpl;
    }

    @Override
    public boolean isEmpty() {
      return false;
    }

    @Override
    public boolean isDisposeWhenEmpty() {
      return false;
    }
  }

  /**
   * @deprecated use {@link #createShellWidget(String, String, boolean, boolean)} instead
   */
  @Deprecated(forRemoval = true)
  public @NotNull ShellTerminalWidget createLocalShellWidget(@Nullable String workingDirectory, @Nullable @Nls String tabName) {
    return ShellTerminalWidget.toShellJediTermWidgetOrThrow(createShellWidget(workingDirectory, tabName, true, true));
  }

  /**
   * @deprecated use {@link #createShellWidget(String, String, boolean, boolean)} instead
   */
  @Deprecated(forRemoval = true)
  public @NotNull ShellTerminalWidget createLocalShellWidget(@Nullable String workingDirectory,
                                                             @Nullable @Nls String tabName,
                                                             boolean requestFocus) {
    return ShellTerminalWidget.toShellJediTermWidgetOrThrow(createShellWidget(workingDirectory, tabName, requestFocus, true));
  }

  /**
   * @deprecated use {@link #createShellWidget(String, String, boolean, boolean)} instead
   */
  @Deprecated(forRemoval = true)
  public @NotNull ShellTerminalWidget createLocalShellWidget(@Nullable String workingDirectory,
                                                             @Nullable @Nls String tabName,
                                                             boolean requestFocus,
                                                             boolean deferSessionStartUntilUiShown) {
    return ShellTerminalWidget.toShellJediTermWidgetOrThrow(
      createShellWidget(workingDirectory, tabName, requestFocus, deferSessionStartUntilUiShown));
  }
}


final class TerminalToolWindowPanel extends SimpleToolWindowPanel {
  TerminalToolWindowPanel() {
    super(false, true);
  }

  @Override
  public void addNotify() {
    super.addNotify();
    InternalDecoratorImpl.componentWithEditorBackgroundAdded(this);
  }

  @Override
  public void removeNotify() {
    super.removeNotify();
    InternalDecoratorImpl.componentWithEditorBackgroundRemoved(this);
  }
}
