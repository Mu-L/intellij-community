// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.xdebugger.impl;

import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.filters.HyperlinkInfo;
import com.intellij.execution.filters.OpenFileHyperlinkInfo;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.*;
import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.platform.util.coroutines.CoroutineScopeKt;
import com.intellij.ui.AppUIUtil;
import com.intellij.util.EventDispatcher;
import com.intellij.util.SmartList;
import com.intellij.util.concurrency.annotations.RequiresReadLock;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.xdebugger.*;
import com.intellij.xdebugger.breakpoints.*;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XSuspendContext;
import com.intellij.xdebugger.frame.XValueMarkerProvider;
import com.intellij.xdebugger.impl.actions.XDebuggerActions;
import com.intellij.xdebugger.impl.breakpoints.*;
import com.intellij.xdebugger.impl.evaluate.ValueLookupManagerController;
import com.intellij.xdebugger.impl.frame.XDebugSessionProxy;
import com.intellij.xdebugger.impl.frame.XDebugSessionProxyKeeper;
import com.intellij.xdebugger.impl.frame.XValueMarkers;
import com.intellij.xdebugger.impl.inline.DebuggerInlayListener;
import com.intellij.xdebugger.impl.inline.InlineDebugRenderer;
import com.intellij.xdebugger.impl.mixedmode.XMixedModeCombinedDebugProcess;
import com.intellij.xdebugger.impl.rhizome.XDebugSessionEntity;
import com.intellij.xdebugger.impl.rpc.XDebugSessionPausedInfo;
import com.intellij.xdebugger.impl.rpc.XDebuggerSessionTabAbstractInfo;
import com.intellij.xdebugger.impl.rpc.XDebuggerSessionTabInfo;
import com.intellij.xdebugger.impl.rpc.XDebuggerSessionTabInfoNoInit;
import com.intellij.xdebugger.impl.settings.XDebuggerSettingManagerImpl;
import com.intellij.xdebugger.impl.ui.XDebugSessionData;
import com.intellij.xdebugger.impl.ui.XDebugSessionTab;
import com.intellij.xdebugger.impl.ui.XDebugSessionTabCustomizerKt;
import com.intellij.xdebugger.impl.util.BringDebuggeeInForegroundUtilsKt;
import com.intellij.xdebugger.stepping.XSmartStepIntoHandler;
import com.intellij.xdebugger.stepping.XSmartStepIntoVariant;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.Deferred;
import kotlinx.coroutines.flow.*;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static com.intellij.xdebugger.impl.CoroutineUtilsKt.createMutableStateFlow;
import static com.intellij.xdebugger.impl.CoroutineUtilsKt.createSessionSuspendedFlow;
import static com.intellij.xdebugger.impl.rhizome.XDebugSessionDbUtilsKt.storeXDebugSessionInDb;

@ApiStatus.Internal
public final class XDebugSessionImpl implements XDebugSession {
  private static final Logger LOG = Logger.getInstance(XDebugSessionImpl.class);
  private static final Logger PERFORMANCE_LOG = Logger.getInstance("#com.intellij.xdebugger.impl.XDebugSessionImpl.performance");

  // TODO[eldar] needed to workaround nullable myAlternativeSourceHandler.
  private static final StateFlow<Boolean> ALWAYS_FALSE_STATE = FlowKt.asStateFlow(StateFlowKt.MutableStateFlow(false));

  private XDebugProcess myDebugProcess;
  private final Map<XBreakpoint<?>, CustomizedBreakpointPresentation> myRegisteredBreakpoints = new HashMap<>();
  private final Set<XBreakpoint<?>> myInactiveSlaveBreakpoints = Collections.synchronizedSet(new HashSet<>());
  private boolean myBreakpointsDisabled;
  private final XDebuggerManagerImpl myDebuggerManager;
  private final XDebuggerExecutionPointManager myExecutionPointManager;
  private Disposable myBreakpointListenerDisposable;
  private final MutableStateFlow<XSuspendContext> mySuspendContext = createMutableStateFlow(null);
  private CoroutineScope mySuspendCoroutineScope;
  private XExecutionStack myCurrentExecutionStack;
  private @Nullable XAlternativeSourceHandler myAlternativeSourceHandler;
  private boolean myIsTopFrame;
  private volatile XStackFrame myTopStackFrame;
  private final MutableStateFlow<Boolean> myPaused = createMutableStateFlow(false);
  private XValueMarkers<?, ?> myValueMarkers;
  private final @Nls String mySessionName;
  private @Nullable XDebugSessionTab mySessionTab;
  private @Nullable RunContentDescriptor myRunContentDescriptor;
  private final @NotNull XDebugSessionData mySessionData;
  private final AtomicReference<Pair<XBreakpoint<?>, XSourcePosition>> myActiveNonLineBreakpoint = new AtomicReference<>();
  private final EventDispatcher<XDebugSessionListener> myDispatcher = EventDispatcher.create(XDebugSessionListener.class);
  private final Project myProject;
  private final CoroutineScope myCoroutineScope;
  private final @Nullable ExecutionEnvironment myEnvironment;
  private final MutableStateFlow<Boolean> myStopped = createMutableStateFlow(false);
  private final MutableStateFlow<Boolean> myPauseActionSupported = createMutableStateFlow(false);
  private final MutableStateFlow<Boolean> myReadOnly = createMutableStateFlow(false);
  private final boolean myShowToolWindowOnSuspendOnly;
  private final MutableStateFlow<@Nullable XDebuggerSessionTabAbstractInfo> myTabInitDataFlow = CoroutineUtilsKt.createMutableStateFlow(null);
  private final MutableStateFlow<@Nullable XDebugSessionPausedInfo> myPausedFlow = CoroutineUtilsKt.createMutableStateFlow(null);
  private final List<AnAction> myRestartActions = new SmartList<>();
  private final List<AnAction> myExtraStopActions = new SmartList<>();
  private final List<AnAction> myExtraActions = new SmartList<>();
  private ConsoleView myConsoleView;
  private final Icon myIcon;
  private final Deferred<@NotNull XDebugSessionEntity> myEntity;
  private final XDebugSessionCurrentStackFrameManager myCurrentStackFrameManager;
  private final StateFlow<Boolean> myIsSuspended;

  private volatile boolean breakpointsInitialized;
  private long myUserRequestStart;
  private String myUserRequestAction;

  public XDebugSessionImpl(@NotNull ExecutionEnvironment environment,
                           @NotNull XDebuggerManagerImpl debuggerManager) {
    this(environment, debuggerManager, environment.getRunProfile().getName(), environment.getRunProfile().getIcon(), false,
         null);
  }

  public XDebugSessionImpl(@Nullable ExecutionEnvironment environment,
                           @NotNull XDebuggerManagerImpl debuggerManager,
                           @NotNull @Nls String sessionName,
                           @Nullable Icon icon,
                           boolean showToolWindowOnSuspendOnly,
                           @Nullable RunContentDescriptor contentToReuse) {
    myCoroutineScope = CoroutineScopeKt.childScope(debuggerManager.getCoroutineScope(), "XDebugSession " + sessionName,
                                                   EmptyCoroutineContext.INSTANCE, true);
    myEnvironment = environment;
    mySessionName = sessionName;
    myDebuggerManager = debuggerManager;
    myShowToolWindowOnSuspendOnly = showToolWindowOnSuspendOnly;
    myProject = debuggerManager.getProject();
    myExecutionPointManager = debuggerManager.getExecutionPointManager();
    ValueLookupManagerController.getInstance(myProject).startListening();
    DebuggerInlayListener.getInstance(myProject).startListening();
    myIcon = icon;
    myEntity = storeXDebugSessionInDb(myCoroutineScope, this);
    myCurrentStackFrameManager = new XDebugSessionCurrentStackFrameManager(myCoroutineScope, myEntity);
    myIsSuspended = createSessionSuspendedFlow(myCoroutineScope, myPaused, mySuspendContext);

    XDebugSessionData oldSessionData = null;
    if (contentToReuse == null) {
      contentToReuse = environment != null ? environment.getContentToReuse() : null;
    }
    if (contentToReuse != null) {
      JComponent component = contentToReuse.getComponent();
      if (component != null) {
        oldSessionData = XDebugSessionData.DATA_KEY.getData(DataManager.getInstance().getDataContext(component));
      }
    }

    String currentConfigurationName = computeConfigurationName();
    if (oldSessionData == null || !oldSessionData.getConfigurationName().equals(currentConfigurationName)) {
      oldSessionData = new XDebugSessionData(myProject, currentConfigurationName);
    }
    mySessionData = oldSessionData;
  }

  @Override
  public @NotNull String getSessionName() {
    return mySessionName;
  }

  @ApiStatus.Internal
  public Flow<@Nullable XDebuggerSessionTabAbstractInfo> getTabInitDataFlow() {
    return myTabInitDataFlow;
  }

  @ApiStatus.Internal
  public Flow<@Nullable XDebugSessionPausedInfo> getPausedFlow() {
    return myPausedFlow;
  }

  @Override
  public @NotNull RunContentDescriptor getRunContentDescriptor() {
    RunContentDescriptor descriptor = myRunContentDescriptor;
    LOG.assertTrue(descriptor != null, "Run content descriptor is not initialized yet!");
    return descriptor;
  }

  @ApiStatus.Internal
  public @Nullable RunContentDescriptor getRunContentDescriptorIfInitialized() {
    return myRunContentDescriptor;
  }

  private boolean isTabInitialized() {
    return myTabInitDataFlow.getValue() != null && (XDebugSessionProxy.useFeProxy() || mySessionTab != null);
  }

  private void assertSessionTabInitialized() {
    if (myShowToolWindowOnSuspendOnly && !isTabInitialized()) {
      LOG.error("Debug tool window isn't shown yet because debug process isn't suspended");
    }
    else {
      LOG.assertTrue(isTabInitialized(), "Debug tool window not initialized yet!");
    }
  }

  @Override
  public void setPauseActionSupported(final boolean isSupported) {
    myPauseActionSupported.setValue(isSupported);
  }


  public StateFlow<Boolean> isReadOnlyState() {
    return myReadOnly;
  }

  public boolean isReadOnly() {
    return myReadOnly.getValue();
  }

  public void setReadOnly(boolean readOnly) {
    myReadOnly.setValue(readOnly);
  }

  public @NotNull List<AnAction> getRestartActions() {
    return myRestartActions;
  }

  public void addRestartActions(AnAction... restartActions) {
    if (restartActions != null) {
      Collections.addAll(myRestartActions, restartActions);
    }
  }

  public @NotNull List<AnAction> getExtraActions() {
    return myExtraActions;
  }

  public void addExtraActions(AnAction... extraActions) {
    if (extraActions != null) {
      Collections.addAll(myExtraActions, extraActions);
    }
  }

  public List<AnAction> getExtraStopActions() {
    return myExtraStopActions;
  }

  // used externally
  @SuppressWarnings("unused")
  public void addExtraStopActions(AnAction... extraStopActions) {
    if (extraStopActions != null) {
      Collections.addAll(myExtraStopActions, extraStopActions);
    }
  }

  @Override
  public void rebuildViews() {
    myDispatcher.getMulticaster().settingsChanged();
  }

  Deferred<@NotNull XDebugSessionEntity> getEntity() {
    return myEntity;
  }

  @Override
  public @Nullable RunProfile getRunProfile() {
    return myEnvironment != null ? myEnvironment.getRunProfile() : null;
  }

  public StateFlow<Boolean> isPauseActionSupportedState() {
    return myPauseActionSupported;
  }

  public boolean isPauseActionSupported() {
    return myPauseActionSupported.getValue();
  }

  @Override
  public @NotNull Project getProject() {
    return myDebuggerManager.getProject();
  }

  @Override
  public @NotNull XDebugProcess getDebugProcess() {
    return myDebugProcess;
  }

  @Override
  public boolean isSuspended() {
    return myIsSuspended.getValue();
  }

  @ApiStatus.Internal
  public StateFlow<Boolean> isSuspendedState() {
    return myIsSuspended;
  }

  @ApiStatus.Internal
  public StateFlow<Boolean> isPausedState() {
    return myPaused;
  }

  @Override
  public boolean isPaused() {
    return myPaused.getValue();
  }

  @Override
  public @Nullable XStackFrame getCurrentStackFrame() {
    return myCurrentStackFrameManager.getCurrentStackFrame();
  }

  public @Nullable XExecutionStack getCurrentExecutionStack() {
    return myCurrentExecutionStack;
  }

  @Override
  public @Nullable XSuspendContext getSuspendContext() {
    return mySuspendContext.getValue();
  }

  @ApiStatus.Internal
  public @Nullable CoroutineScope getSuspendCoroutineScope() {
    return mySuspendCoroutineScope;
  }

  @Override
  public @Nullable XSourcePosition getCurrentPosition() {
    return getFrameSourcePosition(getCurrentStackFrame());
  }

  @Override
  public @Nullable XSourcePosition getTopFramePosition() {
    return getFrameSourcePosition(myTopStackFrame);
  }

  public @Nullable XSourcePosition getFrameSourcePosition(@Nullable XStackFrame frame) {
    return getFrameSourcePosition(frame, getCurrentSourceKind());
  }

  public @Nullable XSourcePosition getFrameSourcePosition(@Nullable XStackFrame frame, @NotNull XSourceKind sourceKind) {
    if (frame == null) return null;
    return switch (sourceKind) {
      case MAIN -> frame.getSourcePosition();
      case ALTERNATIVE -> myAlternativeSourceHandler != null ? myAlternativeSourceHandler.getAlternativePosition(frame) : null;
    };
  }

  public @NotNull XSourceKind getCurrentSourceKind() {
    StateFlow<@NotNull Boolean> state = getAlternativeSourceKindState();
    return state.getValue() ? XSourceKind.ALTERNATIVE : XSourceKind.MAIN;
  }

  @NotNull StateFlow<@NotNull Boolean> getAlternativeSourceKindState() {
    return myAlternativeSourceHandler != null ? myAlternativeSourceHandler.getAlternativeSourceKindState() : ALWAYS_FALSE_STATE;
  }

  void init(@NotNull XDebugProcess process, @Nullable RunContentDescriptor contentToReuse) {
    LOG.assertTrue(myDebugProcess == null);
    myDebugProcess = process;
    myAlternativeSourceHandler = myDebugProcess.getAlternativeSourceHandler();
    myExecutionPointManager.setAlternativeSourceKindFlow(getAlternativeSourceKindState());

    if (myDebugProcess.checkCanInitBreakpoints()) {
      ReadAction.run(() -> initBreakpoints());
    }
    if (myDebugProcess instanceof XDebugProcessDebuggeeInForeground debuggeeInForeground &&
        debuggeeInForeground.isBringingToForegroundApplicable()) {
      BringDebuggeeInForegroundUtilsKt.start(debuggeeInForeground, this, 1000);
    }


    myDebugProcess.getProcessHandler().addProcessListener(new ProcessListener() {
      @Override
      public void processTerminated(final @NotNull ProcessEvent event) {
        stopImpl();
        myDebugProcess.getProcessHandler().removeProcessListener(this);
      }
    });
    //todo make 'createConsole()' method return ConsoleView
    myConsoleView = (ConsoleView)myDebugProcess.createConsole();
    if (!myShowToolWindowOnSuspendOnly) {
      initSessionTab(contentToReuse, false);
    }
  }

  public void reset() {
    breakpointsInitialized = false;
    removeBreakpointListeners();
    unsetPaused();
    clearPausedData();
    rebuildViews();
  }

  @RequiresReadLock
  @Override
  public void initBreakpoints() {
    LOG.assertTrue(!breakpointsInitialized);
    breakpointsInitialized = true;

    disableSlaveBreakpoints();
    processAllBreakpoints(true, false);

    if (myBreakpointListenerDisposable == null) {
      myBreakpointListenerDisposable = Disposer.newDisposable();
      Disposer.register(myProject, myBreakpointListenerDisposable);
      MessageBusConnection busConnection = myProject.getMessageBus().connect(myBreakpointListenerDisposable);
      busConnection.subscribe(XBreakpointListener.TOPIC, new MyBreakpointListener());
      busConnection.subscribe(XDependentBreakpointListener.TOPIC, new MyDependentBreakpointListener());
    }
  }

  @Override
  public ConsoleView getConsoleView() {
    return myConsoleView;
  }

  public @Nullable XDebugSessionTab getSessionTab() {
    if (XDebugSessionProxy.useFeProxy()) {
      LOG.error("Debug tab should not be used in split mode from XDebugSession");
    }
    return mySessionTab;
  }

  @Override
  public RunnerLayoutUi getUI() {
    assertSessionTabInitialized();
    XDebugSessionTab sessionTab = getSessionTab();
    assert sessionTab != null;
    return sessionTab.getUi();
  }

  @Override
  public boolean isMixedMode() {
    return myDebugProcess instanceof XMixedModeCombinedDebugProcess;
  }

  private void initSessionTab(@Nullable RunContentDescriptor contentToReuse, boolean shouldShowTab) {
    boolean forceNewDebuggerUi = XDebugSessionTabCustomizerKt.forceShowNewDebuggerUi(getDebugProcess());
    boolean withFramesCustomization = XDebugSessionTabCustomizerKt.allowFramesViewCustomization(getDebugProcess());

    if (XDebugSessionProxy.useFeProxy()) {
      XDebuggerSessionTabInfo tabInfo = new XDebuggerSessionTabInfo(myIcon, forceNewDebuggerUi, withFramesCustomization, shouldShowTab,
                                                                    contentToReuse, myEnvironment);
      if (myTabInitDataFlow.compareAndSet(null, tabInfo)) {
        myRunContentDescriptor = contentToReuse != null
                                 ? contentToReuse
                                 // This is a mock descriptor used in backend only
                                 : new RunContentDescriptor(myConsoleView, getDebugProcess().getProcessHandler(), new JLabel(),
                                                            getSessionName(), myIcon, null);
        myDebugProcess.sessionInitialized();
      }
    }
    else {
      if (myTabInitDataFlow.getValue() != null) return;
      XDebugSessionProxy proxy = XDebugSessionProxyKeeper.getInstance(myProject).getOrCreateProxy(this);
      XDebugSessionTab tab = XDebugSessionTab.create(proxy, myIcon, myEnvironment, contentToReuse,
                                                     forceNewDebuggerUi, withFramesCustomization);
      if (myTabInitDataFlow.compareAndSet(null, new XDebuggerSessionTabInfoNoInit(tab))) {
        tabInitialized(tab);
        myDebugProcess.sessionInitialized();
        if (shouldShowTab) {
          tab.showTab();
        }
      }
    }
  }

  public @NotNull XDebugSessionData getSessionData() {
    return mySessionData;
  }

  @ApiStatus.Internal
  public void tabInitialized(@NotNull XDebugSessionTab sessionTab) {
    mySessionTab = sessionTab;
    myRunContentDescriptor = sessionTab.getRunContentDescriptor();
  }

  private void disableSlaveBreakpoints() {
    Set<XBreakpoint<?>> slaveBreakpoints = myDebuggerManager.getBreakpointManager().getDependentBreakpointManager().getAllSlaveBreakpoints();
    if (slaveBreakpoints.isEmpty()) {
      return;
    }

    Set<XBreakpointType<?, ?>> breakpointTypes = new HashSet<>();
    for (XBreakpointHandler<?> handler : myDebugProcess.getBreakpointHandlers()) {
      breakpointTypes.add(getBreakpointTypeClass(handler));
    }
    for (XBreakpoint<?> slaveBreakpoint : slaveBreakpoints) {
      if (breakpointTypes.contains(slaveBreakpoint.getType())) {
        myInactiveSlaveBreakpoints.add(slaveBreakpoint);
      }
    }
  }

  public void showSessionTab() {
    XDebugSessionTab tab = getSessionTab();
    assert tab != null;
    tab.showTab();
  }

  public @Nullable XValueMarkers<?, ?> getValueMarkers() {
    if (myValueMarkers == null) {
      XValueMarkerProvider<?, ?> provider = myDebugProcess.createValueMarkerProvider();
      if (provider != null) {
        myValueMarkers = XValueMarkers.createValueMarkers(provider);
      }
    }
    return myValueMarkers;
  }

  @ApiStatus.Internal
  public CoroutineScope getCoroutineScope() {
    return myCoroutineScope;
  }

  @SuppressWarnings("unchecked") //need to compile under 1.8, please do not remove before checking
  private static XBreakpointType getBreakpointTypeClass(final XBreakpointHandler handler) {
    return XDebuggerUtil.getInstance().findBreakpointType(handler.getBreakpointTypeClass());
  }

  private <B extends XBreakpoint<?>> void processBreakpoints(final XBreakpointHandler<B> handler,
                                                             boolean register,
                                                             final boolean temporary) {
    Collection<? extends B> breakpoints = myDebuggerManager.getBreakpointManager().getBreakpoints(handler.getBreakpointTypeClass());
    for (B b : breakpoints) {
      handleBreakpoint(handler, b, register, temporary);
    }
  }

  private <B extends XBreakpoint<?>> void handleBreakpoint(final XBreakpointHandler<B> handler, final B b, final boolean register,
                                                           final boolean temporary) {
    if (register) {
      boolean active = ReadAction.compute(() -> isBreakpointActive(b));
      if (active) {
        synchronized (myRegisteredBreakpoints) {
          myRegisteredBreakpoints.put(b, new CustomizedBreakpointPresentation());
          if (b instanceof XLineBreakpoint) {
            updateBreakpointPresentation((XLineBreakpoint)b, b.getType().getPendingIcon(), null);
          }
        }
        handler.registerBreakpoint(b);
      }
    }
    else {
      boolean removed;
      synchronized (myRegisteredBreakpoints) {
        removed = myRegisteredBreakpoints.remove(b) != null;
      }
      if (removed) {
        handler.unregisterBreakpoint(b, temporary);
      }
    }
  }

  public @Nullable CustomizedBreakpointPresentation getBreakpointPresentation(@NotNull XBreakpoint<?> breakpoint) {
    synchronized (myRegisteredBreakpoints) {
      return myRegisteredBreakpoints.get(breakpoint);
    }
  }

  private void processAllHandlers(final XBreakpoint<?> breakpoint, final boolean register) {
    for (XBreakpointHandler<?> handler : myDebugProcess.getBreakpointHandlers()) {
      processBreakpoint(breakpoint, handler, register);
    }
  }

  private <B extends XBreakpoint<?>> void processBreakpoint(final XBreakpoint<?> breakpoint,
                                                            final XBreakpointHandler<B> handler,
                                                            boolean register) {
    XBreakpointType<?, ?> type = breakpoint.getType();
    if (handler.getBreakpointTypeClass().equals(type.getClass())) {
      //noinspection unchecked
      B b = (B)breakpoint;
      handleBreakpoint(handler, b, register, false);
    }
  }

  @RequiresReadLock
  public boolean isBreakpointActive(@NotNull XBreakpoint<?> b) {
    return !areBreakpointsMuted() && b.isEnabled() && !isInactiveSlaveBreakpoint(b) && !((XBreakpointBase<?, ?, ?>)b).isDisposed();
  }

  @Override
  public boolean areBreakpointsMuted() {
    return mySessionData.isBreakpointsMuted();
  }

  @Override
  public void addSessionListener(@NotNull XDebugSessionListener listener, @NotNull Disposable parentDisposable) {
    myDispatcher.addListener(listener, parentDisposable);
  }

  @Override
  public void addSessionListener(final @NotNull XDebugSessionListener listener) {
    myDispatcher.addListener(listener);
  }

  @Override
  public void removeSessionListener(final @NotNull XDebugSessionListener listener) {
    myDispatcher.removeListener(listener);
  }

  @RequiresReadLock
  @Override
  public void setBreakpointMuted(boolean muted) {
    if (areBreakpointsMuted() == muted) return;
    mySessionData.setBreakpointsMuted(muted);
    if (!myBreakpointsDisabled) {
      processAllBreakpoints(!muted, muted);
    }
    myDebuggerManager.getBreakpointManager().getLineBreakpointManager().queueAllBreakpointsUpdate();
    myDispatcher.getMulticaster().breakpointsMuted(muted);
  }

  @Override
  public void stepOver(final boolean ignoreBreakpoints) {
    rememberUserActionStart(XDebuggerActions.STEP_OVER);
    if (!myDebugProcess.checkCanPerformCommands()) return;

    if (ignoreBreakpoints) {
      setBreakpointsDisabledTemporarily(true);
    }
    myDebugProcess.startStepOver(doResume());
  }

  @Override
  public void stepInto() {
    rememberUserActionStart(XDebuggerActions.STEP_INTO);
    if (!myDebugProcess.checkCanPerformCommands()) return;

    myDebugProcess.startStepInto(doResume());
  }

  @Override
  public void stepOut() {
    rememberUserActionStart(XDebuggerActions.STEP_OUT);
    if (!myDebugProcess.checkCanPerformCommands()) return;

    myDebugProcess.startStepOut(doResume());
  }

  @Override
  public <V extends XSmartStepIntoVariant> void smartStepInto(XSmartStepIntoHandler<V> handler, V variant) {
    rememberUserActionStart(XDebuggerActions.SMART_STEP_INTO);
    if (!myDebugProcess.checkCanPerformCommands()) return;

    final XSuspendContext context = doResume();
    handler.startStepInto(variant, context);
  }

  @Override
  public void forceStepInto() {
    rememberUserActionStart(XDebuggerActions.FORCE_STEP_INTO);
    if (!myDebugProcess.checkCanPerformCommands()) return;

    myDebugProcess.startForceStepInto(doResume());
  }

  @Override
  public void runToPosition(final @NotNull XSourcePosition position, final boolean ignoreBreakpoints) {
    rememberUserActionStart(XDebuggerActions.RUN_TO_CURSOR);
    if (!myDebugProcess.checkCanPerformCommands()) return;

    if (ignoreBreakpoints) {
      setBreakpointsDisabledTemporarily(true);
    }
    myDebugProcess.runToPosition(position, doResume());
  }

  @Override
  public void pause() {
    rememberUserActionStart(XDebuggerActions.PAUSE);
    if (!myDebugProcess.checkCanPerformCommands()) return;

    myDebugProcess.startPausing();
  }

  @RequiresReadLock
  private void processAllBreakpoints(final boolean register, final boolean temporary) {
    for (XBreakpointHandler<?> handler : myDebugProcess.getBreakpointHandlers()) {
      processBreakpoints(handler, register, temporary);
    }
  }

  private void setBreakpointsDisabledTemporarily(boolean disabled) {
    ApplicationManager.getApplication().runReadAction(() -> {
      if (myBreakpointsDisabled == disabled) return;
      myBreakpointsDisabled = disabled;
      if (!areBreakpointsMuted()) {
        processAllBreakpoints(!disabled, disabled);
      }
    });
  }

  @Override
  public void resume() {
    if (!myDebugProcess.checkCanPerformCommands()) return;

    myDebugProcess.resume(doResume());
  }

  private @Nullable XSuspendContext doResume() {
    if (!StateFlowKt.getAndUpdate(myPaused, (it) -> false)) {
      return null;
    }

    myDispatcher.getMulticaster().beforeSessionResume();
    XSuspendContext context = mySuspendContext.getValue();
    clearPausedData();
    myDispatcher.getMulticaster().sessionResumed();
    return context;
  }

  private void clearPausedData() {
    mySuspendContext.setValue(null);
    if (mySuspendCoroutineScope != null) {
      kotlinx.coroutines.CoroutineScopeKt.cancel(mySuspendCoroutineScope, null);
    }
    mySuspendCoroutineScope = null;
    myCurrentExecutionStack = null;
    myCurrentStackFrameManager.setCurrentStackFrame(null);
    myTopStackFrame = null;
    clearActiveNonLineBreakpoint();
    updateExecutionPosition();
  }

  @Override
  public void updateExecutionPosition() {
    updateExecutionPosition(getCurrentSourceKind());
  }

  private void updateExecutionPosition(@NotNull XSourceKind navigationSourceKind) {
    // allowed only for the active session
    if (myDebuggerManager.getCurrentSession() == this) {
      boolean isTopFrame = isTopFrameSelected();
      XSourcePosition mainSourcePosition = getFrameSourcePosition(getCurrentStackFrame(), XSourceKind.MAIN);
      XSourcePosition alternativeSourcePosition = getFrameSourcePosition(getCurrentStackFrame(), XSourceKind.ALTERNATIVE);
      myExecutionPointManager.setExecutionPoint(mainSourcePosition, alternativeSourcePosition, isTopFrame, navigationSourceKind);
      updateExecutionPointGutterIconRenderer();
    }
  }

  public boolean isTopFrameSelected() {
    return myCurrentExecutionStack != null && myIsTopFrame;
  }


  @Override
  public void showExecutionPoint() {
    if (mySuspendContext.getValue() != null) {
      XExecutionStack executionStack = mySuspendContext.getValue().getActiveExecutionStack();
      if (executionStack != null) {
        XStackFrame topFrame = executionStack.getTopFrame();
        if (topFrame != null) {
          setCurrentStackFrame(executionStack, topFrame, true);
          myExecutionPointManager.showExecutionPosition();
        }
      }
    }
  }

  @Override
  public void setCurrentStackFrame(@NotNull XExecutionStack executionStack, @NotNull XStackFrame frame, boolean isTopFrame) {
    if (mySuspendContext.getValue() == null) return;

    boolean frameChanged = getCurrentStackFrame() != frame;
    myCurrentExecutionStack = executionStack;
    myCurrentStackFrameManager.setCurrentStackFrame(frame);
    myIsTopFrame = isTopFrame;

    if (frameChanged) {
      myDispatcher.getMulticaster().stackFrameChanged();
    }

    activateSession(frameChanged);
  }

  void activateSession(boolean forceUpdateExecutionPosition) {
    boolean sessionChanged = myDebuggerManager.setCurrentSession(this);
    if (sessionChanged || forceUpdateExecutionPosition) {
      updateExecutionPosition();
    }
    else {
      myExecutionPointManager.showExecutionPosition();
    }
  }

  public XBreakpoint<?> getActiveNonLineBreakpoint() {
    Pair<XBreakpoint<?>, XSourcePosition> pair = myActiveNonLineBreakpoint.get();
    if (pair != null) {
      XSourcePosition breakpointPosition = pair.getSecond();
      XSourcePosition position = getTopFramePosition();
      if (breakpointPosition == null ||
          (position != null &&
           !(breakpointPosition.getFile().equals(position.getFile()) && breakpointPosition.getLine() == position.getLine()))) {
        return pair.getFirst();
      }
    }
    return null;
  }

  public void checkActiveNonLineBreakpointOnRemoval(@NotNull XBreakpoint<?> removedBreakpoint) {
    Pair<XBreakpoint<?>, XSourcePosition> pair = myActiveNonLineBreakpoint.get();
    if (pair != null && pair.getFirst() == removedBreakpoint) {
      clearActiveNonLineBreakpoint();
      updateExecutionPointGutterIconRenderer();
    }
  }

  private void clearActiveNonLineBreakpoint() {
    myActiveNonLineBreakpoint.set(null);
  }

  void updateExecutionPointGutterIconRenderer() {
    if (myDebuggerManager.getCurrentSession() == this) {
      boolean isTopFrame = isTopFrameSelected();
      GutterIconRenderer renderer = getPositionIconRenderer(isTopFrame);
      myExecutionPointManager.setGutterIconRenderer(renderer);
    }
  }

  private @Nullable GutterIconRenderer getPositionIconRenderer(boolean isTopFrame) {
    if (!isTopFrame) {
      return null;
    }
    XBreakpoint<?> activeNonLineBreakpoint = getActiveNonLineBreakpoint();
    if (activeNonLineBreakpoint != null) {
      return ((XBreakpointBase<?, ?, ?>)activeNonLineBreakpoint).createGutterIconRenderer();
    }
    if (myCurrentExecutionStack != null) {
      return myCurrentExecutionStack.getExecutionLineIconRenderer();
    }
    return null;
  }

  @Override
  public void updateBreakpointPresentation(final @NotNull XLineBreakpoint<?> breakpoint,
                                           final @Nullable Icon icon,
                                           final @Nullable String errorMessage) {
    CustomizedBreakpointPresentation presentation;
    synchronized (myRegisteredBreakpoints) {
      presentation = myRegisteredBreakpoints.get(breakpoint);
      if (presentation == null ||
          (Comparing.equal(presentation.getIcon(), icon) && Comparing.strEqual(presentation.getErrorMessage(), errorMessage))) {
        return;
      }

      presentation.setErrorMessage(errorMessage);
      presentation.setIcon(icon);

      long timestamp = presentation.getTimestamp();
      if (timestamp != 0 && XDebuggerUtilImpl.getVerifiedIcon(breakpoint).equals(icon)) {
        long delay = System.currentTimeMillis() - timestamp;
        presentation.setTimestamp(0);
        BreakpointsUsageCollector.reportBreakpointVerified(breakpoint, delay);
      }
    }
    XBreakpointManagerImpl debuggerManager = myDebuggerManager.getBreakpointManager();
    debuggerManager.getLineBreakpointManager().queueBreakpointUpdate(breakpoint, () -> debuggerManager.fireBreakpointPresentationUpdated(breakpoint, this));
  }

  @Override
  public void setBreakpointVerified(@NotNull XLineBreakpoint<?> breakpoint) {
    updateBreakpointPresentation(breakpoint, XDebuggerUtilImpl.getVerifiedIcon(breakpoint), null);
  }

  @Override
  public void setBreakpointInvalid(@NotNull XLineBreakpoint<?> breakpoint, @Nullable String errorMessage) {
    updateBreakpointPresentation(breakpoint, AllIcons.Debugger.Db_invalid_breakpoint, errorMessage);
  }

  @Override
  public boolean breakpointReached(final @NotNull XBreakpoint<?> breakpoint, @Nullable String evaluatedLogExpression,
                                   @NotNull XSuspendContext suspendContext) {
    return breakpointReached(breakpoint, evaluatedLogExpression, suspendContext, true);
  }

  public void breakpointReachedNoProcessing(final @NotNull XBreakpoint<?> breakpoint, @NotNull XSuspendContext suspendContext) {
    breakpointReached(breakpoint, null, suspendContext, false);
  }

  private boolean breakpointReached(final @NotNull XBreakpoint<?> breakpoint, @Nullable String evaluatedLogExpression,
                                    @NotNull XSuspendContext suspendContext, boolean doProcessing) {
    if (doProcessing) {
      if (breakpoint.isLogMessage()) {
        XSourcePosition position = breakpoint.getSourcePosition();
        OpenFileHyperlinkInfo hyperlinkInfo =
          position != null ? new OpenFileHyperlinkInfo(myProject, position.getFile(), position.getLine()) : null;
        printMessage(XDebuggerBundle.message("xbreakpoint.reached.text") + " ", XBreakpointUtil.getShortText(breakpoint), hyperlinkInfo);
      }

      if (breakpoint.isLogStack()) {
        myDebugProcess.logStack(suspendContext, this);
      }

      if (evaluatedLogExpression != null) {
        printMessage(evaluatedLogExpression, null, null);
      }

      processDependencies(breakpoint);

      if (breakpoint.getSuspendPolicy() == SuspendPolicy.NONE) {
        return false;
      }
    }

    NotificationGroupManager.getInstance().getNotificationGroup("Breakpoint hit")
      .createNotification(XDebuggerBundle.message("xdebugger.breakpoint.reached"), MessageType.INFO)
      .notify(getProject());

    if (!(breakpoint instanceof XLineBreakpoint) || ((XLineBreakpoint)breakpoint).getType().canBeHitInOtherPlaces()) {
      // precompute source position for faster access later
      myActiveNonLineBreakpoint.set(Pair.create(breakpoint, breakpoint.getSourcePosition()));
    }
    else {
      myActiveNonLineBreakpoint.set(null);
    }

    // set this session active on breakpoint, update execution position will be called inside positionReached
    myDebuggerManager.setCurrentSession(this);

    positionReachedInternal(suspendContext, true);

    if (doProcessing && breakpoint instanceof XLineBreakpoint<?> && ((XLineBreakpoint<?>)breakpoint).isTemporary()) {
      handleTemporaryBreakpointHit(breakpoint);
    }
    return true;
  }

  private void handleTemporaryBreakpointHit(final XBreakpoint<?> breakpoint) {
    addSessionListener(new XDebugSessionListener() {
      private void removeBreakpoint() {
        XDebuggerUtil.getInstance().removeBreakpoint(myProject, breakpoint);
        removeSessionListener(this);
      }

      @Override
      public void sessionResumed() {
        removeBreakpoint();
      }

      @Override
      public void sessionStopped() {
        removeBreakpoint();
      }
    });
  }

  public void processDependencies(final XBreakpoint<?> breakpoint) {
    XDependentBreakpointManager dependentBreakpointManager = myDebuggerManager.getBreakpointManager().getDependentBreakpointManager();
    if (!dependentBreakpointManager.isMasterOrSlave(breakpoint)) return;

    List<XBreakpoint<?>> breakpoints = dependentBreakpointManager.getSlaveBreakpoints(breakpoint);
    breakpoints.forEach(myInactiveSlaveBreakpoints::remove);
    for (XBreakpoint<?> slaveBreakpoint : breakpoints) {
      processAllHandlers(slaveBreakpoint, true);
    }

    if (dependentBreakpointManager.getMasterBreakpoint(breakpoint) != null && !dependentBreakpointManager.isLeaveEnabled(breakpoint)) {
      boolean added = myInactiveSlaveBreakpoints.add(breakpoint);
      if (added) {
        processAllHandlers(breakpoint, false);
        myDebuggerManager.getBreakpointManager().getLineBreakpointManager().queueBreakpointUpdate(breakpoint);
      }
    }
  }

  private void printMessage(final String message, final String hyperLinkText, final @Nullable HyperlinkInfo info) {
    AppUIUtil.invokeOnEdt(() -> {
      myConsoleView.print(message, ConsoleViewContentType.SYSTEM_OUTPUT);
      if (info != null) {
        myConsoleView.printHyperlink(hyperLinkText, info);
      }
      else if (hyperLinkText != null) {
        myConsoleView.print(hyperLinkText, ConsoleViewContentType.SYSTEM_OUTPUT);
      }
      myConsoleView.print("\n", ConsoleViewContentType.SYSTEM_OUTPUT);
    });
  }

  public void unsetPaused() {
    myPaused.setValue(false);
  }

  private void positionReachedInternal(final @NotNull XSuspendContext suspendContext, boolean attract) {
    if (handlePositionReaching(suspendContext, attract)) {
      return;
    }

    setBreakpointsDisabledTemporarily(false);
    mySuspendContext.setValue(suspendContext);
    mySuspendCoroutineScope = XDebuggerSuspendScopeProvider.INSTANCE.provideSuspendScope(this);
    myCurrentExecutionStack = suspendContext.getActiveExecutionStack();
    XStackFrame newCurrentStackFrame = myCurrentExecutionStack != null ? myCurrentExecutionStack.getTopFrame() : null;
    myCurrentStackFrameManager.setCurrentStackFrame(newCurrentStackFrame);
    myIsTopFrame = true;
    myTopStackFrame = newCurrentStackFrame;
    XSourcePosition topFramePosition = getTopFramePosition();

    boolean isSteppingSuspendContext = suspendContext instanceof XSteppingSuspendContext;

    myPaused.setValue(!isSteppingSuspendContext);

    if (!isSteppingSuspendContext) {
      boolean isAlternative = myAlternativeSourceHandler != null &&
                              myAlternativeSourceHandler.isAlternativeSourceKindPreferred(suspendContext);
      updateExecutionPosition(isAlternative ? XSourceKind.ALTERNATIVE : XSourceKind.MAIN);
    }

    logPositionReached(topFramePosition);

    final boolean needsInitialization = myTabInitDataFlow.getValue() == null;
    if (needsInitialization || attract) {
      AppUIUtil.invokeLaterIfProjectAlive(myProject, () -> {
        if (needsInitialization) {
          initSessionTab(null, true);
        }

        boolean topFrameIsAbsent = topFramePosition == null;
        if (XDebugSessionProxy.useFeProxy()) {
          myPausedFlow.setValue(new XDebugSessionPausedInfo(attract, topFrameIsAbsent));
        }
        else {
          // We have to keep this code because Code with Me expects BE to work with tab similar to monolith
          assert mySessionTab != null;
          mySessionTab.onPause(attract, topFrameIsAbsent);
        }
      });
    }

    myDispatcher.getMulticaster().sessionPaused();
  }

  @Override
  public void positionReached(final @NotNull XSuspendContext suspendContext) {
    positionReached(suspendContext, false);
  }

  public void positionReached(@NotNull XSuspendContext suspendContext, boolean attract) {
    clearActiveNonLineBreakpoint();
    positionReachedInternal(suspendContext, attract);
  }

  @Override
  public void sessionResumed() {
    doResume();
  }

  @ApiStatus.Internal
  public StateFlow<Boolean> isStoppedState() {
    return myStopped;
  }

  @Override
  public boolean isStopped() {
    return myStopped.getValue();
  }

  private void stopImpl() {
    if (!myStopped.compareAndSet(false, true)) {
      return;
    }

    try {
      removeBreakpointListeners();
    }
    finally {
      myDebugProcess.stopAsync()
        .onSuccess(aVoid -> {
          processStopped();
        });
    }
  }

  private void processStopped() {
    if (!myProject.isDisposed()) {
      myProject.getMessageBus().syncPublisher(XDebuggerManager.TOPIC).processStopped(myDebugProcess);
    }

    if (!isTabInitialized() && myConsoleView != null) {
      AppUIUtil.invokeOnEdt(() -> Disposer.dispose(myConsoleView));
    }

    clearPausedData();

    if (myValueMarkers != null) {
      myValueMarkers.clear();
    }
    if (XDebuggerSettingManagerImpl.getInstanceImpl().getGeneralSettings().isUnmuteOnStop()) {
      mySessionData.setBreakpointsMuted(false);
    }
    myDebuggerManager.removeSession(this);
    myDispatcher.getMulticaster().sessionStopped();
    myDispatcher.getListeners().clear();

    myProject.putUserData(InlineDebugRenderer.LinePainter.CACHE, null);

    synchronized (myRegisteredBreakpoints) {
      myRegisteredBreakpoints.clear();
    }

    kotlinx.coroutines.CoroutineScopeKt.cancel(myCoroutineScope, null);
  }

  private void removeBreakpointListeners() {
    Disposable breakpointListenerDisposable = myBreakpointListenerDisposable;
    if (breakpointListenerDisposable != null) {
      myBreakpointListenerDisposable = null;
      Disposer.dispose(breakpointListenerDisposable);
    }
  }

  public boolean isInactiveSlaveBreakpoint(final XBreakpoint<?> breakpoint) {
    return myInactiveSlaveBreakpoints.contains(breakpoint);
  }

  @Override
  public void stop() {
    stop(myDebugProcess);
  }

  private void stop(XDebugProcess process) {
    ProcessHandler processHandler = process == null ? null : process.getProcessHandler();
    if (processHandler == null || processHandler.isProcessTerminated() || processHandler.isProcessTerminating()) return;

    if (processHandler.detachIsDefault()) {
      processHandler.detachProcess();
    }
    else {
      processHandler.destroyProcess();
    }
  }

  @Override
  public void reportError(final @NotNull String message) {
    reportMessage(message, MessageType.ERROR);
  }

  @Override
  public void reportMessage(final @NotNull String message, final @NotNull MessageType type) {
    reportMessage(message, type, null);
  }

  @Override
  public void reportMessage(final @NotNull String message, final @NotNull MessageType type, final @Nullable HyperlinkListener listener) {
    Notification notification = XDebuggerManagerImpl.getNotificationGroup().createNotification(message, type.toNotificationType());
    if (listener != null) {
      notification.setListener((__, event) -> {
        if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
          listener.hyperlinkUpdate(event);
        }
      });
    }
    notification.notify(myProject);
  }

  private final class MyBreakpointListener implements XBreakpointListener<XBreakpoint<?>> {
    @Override
    public void breakpointAdded(final @NotNull XBreakpoint<?> breakpoint) {
      if (processAdd(breakpoint)) {
        CustomizedBreakpointPresentation presentation = getBreakpointPresentation(breakpoint);
        if (presentation != null) {
          if (XDebuggerUtilImpl.getVerifiedIcon(breakpoint).equals(presentation.getIcon())) {
            BreakpointsUsageCollector.reportBreakpointVerified(breakpoint, 0);
          }
          else {
            presentation.setTimestamp(System.currentTimeMillis());
          }
        }
      }
    }

    @Override
    public void breakpointRemoved(final @NotNull XBreakpoint<?> breakpoint) {
      checkActiveNonLineBreakpointOnRemoval(breakpoint);
      processRemove(breakpoint);
    }

    void processRemove(final @NotNull XBreakpoint<?> breakpoint) {
      processAllHandlers(breakpoint, false);
    }

    boolean processAdd(final @NotNull XBreakpoint<?> breakpoint) {
      if (!myBreakpointsDisabled) {
        processAllHandlers(breakpoint, true);
        return true;
      }
      return false;
    }

    @Override
    public void breakpointChanged(final @NotNull XBreakpoint<?> breakpoint) {
      processRemove(breakpoint);
      processAdd(breakpoint);
    }
  }

  private class MyDependentBreakpointListener implements XDependentBreakpointListener {
    @Override
    public void dependencySet(final @NotNull XBreakpoint<?> slave, final @NotNull XBreakpoint<?> master) {
      boolean added = myInactiveSlaveBreakpoints.add(slave);
      if (added) {
        processAllHandlers(slave, false);
      }
    }

    @Override
    public void dependencyCleared(final XBreakpoint<?> breakpoint) {
      boolean removed = myInactiveSlaveBreakpoints.remove(breakpoint);
      if (removed) {
        processAllHandlers(breakpoint, true);
      }
    }
  }

  /**
   * Configuration name is either configuration type ID or session name.
   * <p/>
   * Configuration type ID is the preferred way to reuse watches within the same configuration type,
   * e.g., in different test run configurations.
   */
  private @NotNull String computeConfigurationName() {
    if (myEnvironment != null) {
      RunProfile profile = myEnvironment.getRunProfile();
      if (profile instanceof RunConfiguration) {
        return ((RunConfiguration)profile).getType().getId();
      }
    }
    return getSessionName();
  }

  public @Nullable ExecutionEnvironment getExecutionEnvironment() {
    return myEnvironment;
  }

  private void rememberUserActionStart(@NotNull String action) {
    myUserRequestStart = System.currentTimeMillis();
    myUserRequestAction = action;
  }

  private void logPositionReached(@Nullable XSourcePosition topFramePosition) {
    FileType fileType = topFramePosition != null ? topFramePosition.getFile().getFileType() : null;
    if (myUserRequestAction != null) {
      long durationMs = System.currentTimeMillis() - myUserRequestStart;
      if (PERFORMANCE_LOG.isDebugEnabled()) {
        PERFORMANCE_LOG.debug("Position reached in " + durationMs + "ms");
      }
      XDebuggerPerformanceCollector.logExecutionPointReached(myProject, fileType, myUserRequestAction, durationMs);
      myUserRequestAction = null;
    }
    else {
      XDebuggerPerformanceCollector.logBreakpointReached(myProject, fileType);
    }
  }

  private boolean handlePositionReaching(@NotNull XSuspendContext context, boolean attract) {
    return isMixedMode() && ((XMixedModeCombinedDebugProcess)myDebugProcess).handlePositionReached(context, attract);
  }
}
