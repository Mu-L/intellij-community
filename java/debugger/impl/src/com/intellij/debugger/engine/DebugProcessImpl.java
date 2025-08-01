// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.debugger.engine;

import com.intellij.Patches;
import com.intellij.debugger.*;
import com.intellij.debugger.actions.DebuggerAction;
import com.intellij.debugger.engine.evaluation.*;
import com.intellij.debugger.engine.evaluation.expression.RetryEvaluationException;
import com.intellij.debugger.engine.events.DebuggerCommandImpl;
import com.intellij.debugger.engine.events.DebuggerContextCommandImpl;
import com.intellij.debugger.engine.events.SuspendContextCommandImpl;
import com.intellij.debugger.engine.jdi.ThreadReferenceProxy;
import com.intellij.debugger.engine.requests.MethodReturnValueWatcher;
import com.intellij.debugger.engine.requests.RequestManagerImpl;
import com.intellij.debugger.engine.requests.StepRequestor;
import com.intellij.debugger.impl.*;
import com.intellij.debugger.impl.attach.PidRemoteConnection;
import com.intellij.debugger.jdi.EmptyConnectorArgument;
import com.intellij.debugger.jdi.StackFrameProxyImpl;
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl;
import com.intellij.debugger.jdi.VirtualMachineProxyImpl;
import com.intellij.debugger.settings.DebuggerSettings;
import com.intellij.debugger.settings.NodeRendererSettings;
import com.intellij.debugger.statistics.DebuggerStatistics;
import com.intellij.debugger.statistics.Engine;
import com.intellij.debugger.statistics.StatisticsStorage;
import com.intellij.debugger.ui.breakpoints.*;
import com.intellij.debugger.ui.tree.render.*;
import com.intellij.execution.CantRunException;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.configurations.RemoteConnection;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.idea.ActionsBundle;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationNamesInfo;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Attachment;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.JavaSdkType;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootEvent;
import com.intellij.openapi.roots.ModuleRootListener;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.BalloonBuilder;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.psi.CommonClassNames;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.awt.AnchoredPoint;
import com.intellij.ui.classFilter.ClassFilter;
import com.intellij.ui.classFilter.DebuggerClassFilterProvider;
import com.intellij.util.Alarm;
import com.intellij.util.EventDispatcher;
import com.intellij.util.ObjectUtils;
import com.intellij.util.concurrency.EdtScheduler;
import com.intellij.util.concurrency.Semaphore;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.DisposableWrapperList;
import com.intellij.util.lang.JavaVersion;
import com.intellij.util.ui.UIUtil;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerBundle;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.impl.XDebugSessionImpl;
import com.intellij.xdebugger.impl.XDebuggerManagerImpl;
import com.intellij.xdebugger.impl.actions.XDebuggerActions;
import com.intellij.xdebugger.impl.frame.XFramesView;
import com.intellij.xdebugger.impl.ui.DebuggerUIUtil;
import com.intellij.xdebugger.impl.ui.XDebugSessionTab;
import com.jetbrains.jdi.*;
import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.LocatableEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.Job;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.*;

import javax.swing.*;
import javax.swing.plaf.basic.BasicArrowButton;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.intellij.debugger.engine.MethodInvokeUtilsKt.tryInvokeWithHelper;
import static com.intellij.debugger.impl.DebuggerUtilsImpl.forEachSafe;

public abstract class DebugProcessImpl extends UserDataHolderBase implements DebugProcess {
  private static final Logger LOG = Logger.getInstance(DebugProcessImpl.class);

  private final Project project;
  private final RequestManagerImpl requestManager;

  private final Deque<VirtualMachineData> myStashedVirtualMachines = new LinkedList<>();

  private volatile VirtualMachineProxyImpl myVirtualMachineProxy = null;
  protected final CheckedDisposable disposable = Disposer.newCheckedDisposable();
  protected final DisposableWrapperList<DebugProcessListener> myDebugProcessListeners = new DisposableWrapperList<>(); // propagate exceptions from listeners
  protected final EventDispatcher<EvaluationListener> myEvaluationDispatcher = EventDispatcher.create(EvaluationListener.class);

  private final List<ProcessListener> myProcessListeners = ContainerUtil.createLockFreeCopyOnWriteList();
  private final StringBuilder myTextBeforeStart = new StringBuilder();

  protected enum State {INITIAL, ATTACHED, DETACHING, DETACHED}

  protected final AtomicReference<State> myState = new AtomicReference<>(State.INITIAL);

  private volatile ExecutionResult myExecutionResult;
  private volatile RemoteConnection myConnection;
  private JavaDebugProcess myXDebugProcess;

  private volatile Map<String, Connector.Argument> myArguments;

  private final List<NodeRenderer> myRenderers = new ArrayList<>();

  // we use null key here
  private final Map<Type, Object> myNodeRenderersMap = Collections.synchronizedMap(new HashMap<>());

  private final SuspendManagerImpl mySuspendManager = new SuspendManagerImpl(this);
  protected CompoundPositionManager myPositionManager = CompoundPositionManager.EMPTY;
  private volatile @NotNull DebuggerManagerThreadImpl myDebuggerManagerThread;

  private final Semaphore myWaitFor = new Semaphore();
  private final AtomicBoolean myIsFailed = new AtomicBoolean(false);
  private final AtomicBoolean myIsStopped = new AtomicBoolean(false);
  protected volatile DebuggerSession mySession;
  protected @Nullable MethodReturnValueWatcher myReturnValueWatcher;
  private final CoroutineScope myCoroutineScope;
  private final ShowStatusManager myShowStatusManager;

  final ThreadBlockedMonitor myThreadBlockedMonitor = new ThreadBlockedMonitor(this, disposable);

  final SteppingProgressTracker mySteppingProgressTracker = new SteppingProgressTracker(this);

  // These 2 fields are needs to switching from found suspend-thread context to user-friendly suspend-all context.
  // The main related logic is in [SuspendOtherThreadsRequestor].
  volatile ParametersForSuspendAllReplacing myParametersForSuspendAllReplacing = null;
  volatile boolean myPreparingToSuspendAll = false;

  List<Runnable> mySuspendAllListeners = new ArrayList<>();

  private Job otherThreadsJob;
  private int myOtherThreadsReachBreakpointNumber = 0;
  private final AtomicInteger myMethodInvocations = new AtomicInteger();

  protected DebugProcessImpl(Project project) {
    this.project = project;
    CoroutineScope projectScope = ((XDebuggerManagerImpl)XDebuggerManager.getInstance(project)).getCoroutineScope();
    myCoroutineScope = com.intellij.platform.util.coroutines.CoroutineScopeKt
      .childScope(projectScope, "DebugProcessImpl", EmptyCoroutineContext.INSTANCE, true);
    myDebuggerManagerThread = createManagerThread();
    myShowStatusManager = new ShowStatusManager(project, myCoroutineScope);
    requestManager = new RequestManagerImpl(this);
    NodeRendererSettings.getInstance().addListener(this::reloadRenderers, disposable);
    NodeRenderer.EP_NAME.addChangeListener(this::reloadRenderers, disposable);
    CompoundRendererProvider.EP_NAME.addChangeListener(this::reloadRenderers, disposable);
    addDebugProcessListener(new DebugProcessListener() {
      @Override
      public void processAttached(@NotNull DebugProcess process) {
        reloadRenderers();
      }
    }, disposable);
    addDebugProcessListener(new DebugProcessListener() {
      @Override
      public void paused(@NotNull SuspendContext suspendContext) {
        boolean isSuspendAll = suspendContext.getSuspendPolicy() == EventRequest.SUSPEND_ALL;
        if (isSuspendAll && DebuggerUtils.isNewThreadSuspendStateTracking()) {
          resumeThreadsUnderEvaluationAndExplicitlyResumedAfterPause((SuspendContextImpl)suspendContext);
        }

        myThreadBlockedMonitor.stopWatching(!isSuspendAll ? suspendContext.getThread() : null);

        DebuggerDiagnosticsUtil.checkThreadsConsistency(DebugProcessImpl.this, false);
      }

      @Override
      public void processDetached(@NotNull DebugProcess process, boolean closedByUser) {
        DebuggerStatistics.logProcessStatistics(process);
      }
    });
    mySteppingProgressTracker.installListeners();
  }

  private DebuggerManagerThreadImpl createManagerThread() {
    CoroutineScope projectScope = ((XDebuggerManagerImpl)XDebuggerManager.getInstance(project)).getCoroutineScope();
    return new DebuggerManagerThreadImpl(disposable, projectScope, this);
  }

  private void reloadRenderers() {
    getManagerThread().schedule(new DebuggerCommandImpl(PrioritizedTask.Priority.HIGH) {
      @Override
      protected void action() {
        myNodeRenderersMap.clear();
        myRenderers.clear();
        try {
          myRenderers.addAll(NodeRendererSettings.getInstance().getAllRenderers(project));
        }
        finally {
          DebuggerInvocationUtil.invokeLaterAnyModality(project, () -> {
            final DebuggerSession session = mySession;
            if (session != null && session.isAttached()) {
              DebuggerAction.refreshViews(mySession.getXDebugSession());
            }
          });
        }
      }
    });
  }

  public @Nullable Pair<Method, Value> getLastExecutedMethod() {
    final MethodReturnValueWatcher watcher = myReturnValueWatcher;
    if (watcher == null) {
      return null;
    }
    final Method method = watcher.getLastExecutedMethod();
    if (method == null) {
      return null;
    }
    return Pair.create(method, watcher.getLastMethodReturnValue());
  }

  public void setWatchMethodReturnValuesEnabled(boolean enabled) {
    if (myReturnValueWatcher != null) {
      myReturnValueWatcher.setEnabled(enabled);
    }
  }

  public boolean canGetMethodReturnValue() {
    return myReturnValueWatcher != null;
  }

  public @NotNull CompletableFuture<List<NodeRenderer>> getApplicableRenderers(Type type) {
    return DebuggerUtilsImpl.getApplicableRenderers(myRenderers, type);
  }

  public @NotNull CompletableFuture<NodeRenderer> getAutoRendererAsync(@Nullable Type type) {
    DebuggerManagerThreadImpl.assertIsManagerThread();
    // in case evaluation is not possible, force default renderer
    if (!isEvaluationPossible()) {
      return CompletableFuture.completedFuture(getDefaultRenderer(type));
    }

    try {
      Object renderer = myNodeRenderersMap.get(type);
      if (renderer instanceof NodeRenderer) {
        return CompletableFuture.completedFuture((NodeRenderer)renderer);
      }
      else if (renderer instanceof CompletableFuture) {
        //noinspection unchecked
        CompletableFuture<NodeRenderer> future = (CompletableFuture<NodeRenderer>)renderer;
        LOG.assertTrue(!future.isDone(), "Completed future for " + type);
        return future;
      }
      List<NodeRenderer> enabledRenderers = ContainerUtil.filter(myRenderers, NodeRenderer::isEnabled);
      CompletableFuture<NodeRenderer> res = DebuggerUtilsImpl.getFirstApplicableRenderer(enabledRenderers, type)
        .handle((r, throwable) -> {
          // sometimes we may be not in DebuggerManagerThread here, see EA-433577 for more details
          //DebuggerManagerThreadImpl.assertIsManagerThread();
          if (r == null || throwable != null) {
            r = getDefaultRenderer(type); // do not cache the fallback renderer
            myNodeRenderersMap.remove(type);
          }
          else {
            // TODO: may add a new (possibly incorrect) value after the cleanup in reloadRenderers
            myNodeRenderersMap.put(type, r);
          }
          return r;
        });
      if (!res.isDone()) {
        myNodeRenderersMap.putIfAbsent(type, res);
      }
      return res;
    }
    catch (ClassNotPreparedException e) {
      LOG.info(e);
      // use default, but do not cache
      return CompletableFuture.completedFuture(getDefaultRenderer(type));
    }
  }

  public static @NotNull NodeRenderer getDefaultRenderer(Value value) {
    return getDefaultRenderer(value != null ? value.type() : null);
  }

  public static @NotNull NodeRenderer getDefaultRenderer(Type type) {
    final NodeRendererSettings settings = NodeRendererSettings.getInstance();

    final PrimitiveRenderer primitiveRenderer = settings.getPrimitiveRenderer();
    if (primitiveRenderer.isApplicable(type)) {
      return primitiveRenderer;
    }

    final ArrayRenderer arrayRenderer = settings.getArrayRenderer();
    if (arrayRenderer.isApplicable(type)) {
      return arrayRenderer;
    }

    final ClassRenderer classRenderer = settings.getClassRenderer();
    LOG.assertTrue(classRenderer.isApplicable(type), type.name());
    return classRenderer;
  }

  private static final int ourTraceMask;

  static {
    String traceStr = System.getProperty("idea.debugger.trace");
    int mask = 0;
    if (!StringUtil.isEmpty(traceStr)) {
      StringTokenizer tokenizer = new StringTokenizer(traceStr);
      while (tokenizer.hasMoreTokens()) {
        String token = tokenizer.nextToken();
        if ("SENDS".compareToIgnoreCase(token) == 0) {
          mask |= VirtualMachine.TRACE_SENDS;
        }
        else if ("RAW_SENDS".compareToIgnoreCase(token) == 0) {
          mask |= 0x01000000;
        }
        else if ("RECEIVES".compareToIgnoreCase(token) == 0) {
          mask |= VirtualMachine.TRACE_RECEIVES;
        }
        else if ("RAW_RECEIVES".compareToIgnoreCase(token) == 0) {
          mask |= 0x02000000;
        }
        else if ("EVENTS".compareToIgnoreCase(token) == 0) {
          mask |= VirtualMachine.TRACE_EVENTS;
        }
        else if ("REFTYPES".compareToIgnoreCase(token) == 0) {
          mask |= VirtualMachine.TRACE_REFTYPES;
        }
        else if ("OBJREFS".compareToIgnoreCase(token) == 0) {
          mask |= VirtualMachine.TRACE_OBJREFS;
        }
        else if ("ALL".compareToIgnoreCase(token) == 0) {
          mask |= VirtualMachine.TRACE_ALL;
        }
      }
    }
    ourTraceMask = mask;
  }

  protected int getTraceMask() {
    int mask = ourTraceMask;
    DebugEnvironment environment = mySession.getDebugEnvironment();
    if (environment instanceof DefaultDebugEnvironment) {
      mask |= ((DefaultDebugEnvironment)environment).getTraceMode();
    }
    return mask;
  }

  protected @NotNull VirtualMachineProxyImpl commitVM(VirtualMachine vm) {
    if (!isInInitialState()) {
      logError("State is invalid " + myState.get());
    }
    DebuggerManagerThreadImpl.assertIsManagerThread();
    myPositionManager = new CompoundPositionManager(new PositionManagerImpl(this));
    project.getMessageBus().connect(disposable).subscribe(ModuleRootListener.TOPIC, new ModuleRootListener() {
      @Override
      public void rootsChanged(final @NotNull ModuleRootEvent event) {
        DumbService.getInstance(project).runWhenSmart(
          () -> getManagerThread().schedule(PrioritizedTask.Priority.HIGH, () -> {
            myPositionManager.clearCache();
            DebuggerSession session = mySession;
            if (session != null && session.isAttached()) {
              DebuggerUIUtil.invokeLater(() -> session.refresh(true));
            }
          }));
      }
    });
    LOG.debug("*******************VM attached******************");

    int mask = getTraceMask();
    if (ApplicationManager.getApplication().isUnitTestMode() && vm instanceof VirtualMachineImpl extendedVM) {
      extendedVM.disableSoftReferences();
      if (mask == VirtualMachine.TRACE_NONE && Registry.is("debugger.log.jdi.in.unit.tests")) {
        mask = VirtualMachine.TRACE_ALL;
        extendedVM.setDebugTraceConsumer(
          strings -> LOG.debug(strings.stream().map(s -> "[JDI: " + s + "]").collect(Collectors.joining("\n"))));
      }
    }
    vm.setDebugTraceMode(mask);

    checkVirtualMachineVersion(vm);
    VirtualMachineProxyImpl proxy = new VirtualMachineProxyImpl(this, vm);
    myVirtualMachineProxy = proxy;
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      Alarm alarm = new Alarm(disposable);
      alarm.addRequest(() -> myDebuggerManagerThread.schedule(PrioritizedTask.Priority.HIGH, () -> {
        logError("Long debugger test execution");
      }), 3*60*1000);
    }
    return proxy;
  }

  private void stopConnecting() {
    Map<String, Connector.Argument> arguments = myArguments;
    try {
      if (arguments == null) {
        return;
      }
      if (myConnection.isServerMode()) {
        Connector connector = getConnector();
        if (connector instanceof ListeningConnector) {
          ((ListeningConnector)connector).stopListening(arguments);
        }
      }
    }
    catch (IOException | IllegalConnectorArgumentsException | IllegalArgumentException e) {
      LOG.debug(e);
    }
    catch (ExecutionException e) {
      logError("Evaluation exception", e);
    }
  }

  @Override
  public void printToConsole(final String text) {
    synchronized (myProcessListeners) {
      if (myExecutionResult == null) {
        myTextBeforeStart.append(text);
      }
      else {
        printToConsoleImpl(text);
      }
    }
  }

  private void printToConsoleImpl(String text) {
    myExecutionResult.getProcessHandler().notifyTextAvailable(text, ProcessOutputTypes.SYSTEM);
  }

  @Override
  public ProcessHandler getProcessHandler() {
    return myExecutionResult != null ? myExecutionResult.getProcessHandler() : null;
  }

  /**
   * @param size the step size. One of {@link StepRequest#STEP_LINE} or {@link StepRequest#STEP_MIN}
   * @param hint may be null
   */
  protected void doStep(@NotNull SuspendContextImpl suspendContext, final ThreadReferenceProxyImpl stepThread, int size, int depth,
                        RequestHint hint, Object commandToken) {
    if (stepThread == null) {
      return;
    }
    try {
      final ThreadReference stepThreadReference = stepThread.getThreadReference();
      if (LOG.isDebugEnabled()) {
        LOG.debug("DO_STEP: creating step request for " + stepThreadReference);
      }
      deleteStepRequests(suspendContext.getVirtualMachineProxy().eventRequestManager(), stepThreadReference);
      EventRequestManager requestManager = suspendContext.getVirtualMachineProxy().eventRequestManager();
      StepRequest stepRequest = requestManager.createStepRequest(stepThreadReference, size, depth);
      String policyFromRequestors = suspendContext.getSuspendPolicyFromRequestors();
      StepRequestor stepRequestor = new StepRequestor(policyFromRequestors);
      getRequestsManager().registerRequestInternal(stepRequestor, stepRequest);
      if (!(hint != null && hint.isIgnoreFilters()) && !isPositionFiltered(getLocation(stepThread, suspendContext))) {
        getActiveFilters().forEach(f -> stepRequest.addClassExclusionFilter(f.getPattern()));
      }

      // suspend policy to match the suspend policy of the context:
      // if all threads were suspended, then during stepping all the threads must be suspended
      // if only event thread was suspended, then only this particular thread must be suspended during stepping
      int policy = (suspendContext.getSuspendPolicy() == EventRequest.SUSPEND_EVENT_THREAD && !DebuggerSettings.SUSPEND_ALL.equals(policyFromRequestors))
                   ? EventRequest.SUSPEND_EVENT_THREAD
                   : EventRequest.SUSPEND_ALL;
      stepRequest.setSuspendPolicy(policy);

      stepRequest.addCountFilter(1);

      if (hint != null) {
        stepRequest.putProperty("hint", hint);
      }
      if (commandToken != null) {
        stepRequest.putProperty("commandToken", commandToken);
      }
      DebuggerUtilsAsync.setEnabled(stepRequest, true).whenComplete((__, e) -> {
        if (DebuggerUtilsAsync.unwrap(e) instanceof IllegalThreadStateException) {
          DebuggerUtilsAsync.deleteEventRequest(requestManager, stepRequest);
        }
      });
    }
    catch (ObjectCollectedException ignored) {

    }
  }

  public static boolean isPositionFiltered(@Nullable Location location) {
    ReferenceType referenceType = location != null ? location.declaringType() : null;
    if (referenceType == null) {
      return false;
    }
    return isClassFiltered(referenceType.name());
  }

  public static boolean isClassFiltered(@Nullable String name) {
    if (name == null) {
      return false;
    }
    return DebuggerUtilsEx.isFiltered(name, getActiveFilters());
  }

  private static @NotNull Stream<ClassFilter> getActiveFilters() {
    DebuggerSettings settings = DebuggerSettings.getInstance();
    StreamEx<ClassFilter> stream = StreamEx.of(DebuggerClassFilterProvider.EP_NAME.getExtensionList())
      .flatCollection(DebuggerClassFilterProvider::getFilters);
    if (settings.TRACING_FILTERS_ENABLED) {
      stream = stream.prepend(settings.getSteppingFilters());
    }
    return stream.filter(ClassFilter::isEnabled);
  }

  public static boolean shouldHideStackFramesUsingSteppingFilters() {
    return DebuggerSettings.getInstance().HIDE_STACK_FRAMES_USING_STEPPING_FILTER;
  }

  void deleteStepRequests(@NotNull EventRequestManager requestManager, @Nullable ThreadReference stepThread) {
    for (StepRequest request : new ArrayList<>(requestManager.stepRequests())) { // need a copy here to avoid CME
      if (stepThread == null || stepThread.equals(request.thread())) {
        try {
          DebuggerUtilsAsync.deleteEventRequest(requestManager, request);
        }
        catch (ObjectCollectedException ignored) {
        }
        catch (VMDisconnectedException e) {
          throw e;
        }
        catch (Exception e) {
          logError("Exception", e); // report all for now
        }
      }
    }
  }

  static int getFrameCount(@Nullable ThreadReferenceProxyImpl thread, @NotNull SuspendContextImpl suspendContext) {
    if (thread != null) {
      if (thread.equals(suspendContext.getEventThread())) {
        return suspendContext.getCachedThreadFrameCount();
      }
      else {
        try {
          return thread.frameCount();
        }
        catch (EvaluateException ignored) {
        }
      }
    }
    return 0;
  }

  static @Nullable Location getLocation(@Nullable ThreadReferenceProxyImpl thread, @NotNull SuspendContextImpl suspendContext) {
    if (thread != null) {
      if (thread.equals(suspendContext.getThread())) {
        return suspendContext.getLocation();
      }
      else {
        try {
          if (thread.frameCount() > 0) {
            StackFrameProxyImpl stackFrame = thread.frame(0);
            if (stackFrame != null) {
              return stackFrame.location();
            }
          }
        }
        catch (EvaluateException ignored) {
        }
      }
    }
    return null;
  }

  private VirtualMachine createVirtualMachineInt() throws ExecutionException {
    try {
      if (myArguments != null) {
        throw new IOException(JavaDebuggerBundle.message("error.debugger.already.listening"));
      }

      final String port = myConnection.getDebuggerAddress();

      Connector connector = getConnector();
      myArguments = connector.defaultArguments();
      if (myConnection instanceof PidRemoteConnection && !((PidRemoteConnection)myConnection).isFixedAddress()) {
        String pid = ((PidRemoteConnection)myConnection).getPid();
        if (StringUtil.isEmpty(pid)) {
          throw new CantRunException(JavaDebuggerBundle.message("error.no.pid"));
        }
        setConnectorArgument("pid", pid);
      }
      else if (myConnection.isServerMode()) {
        if (port == null) {
          throw new CantRunException(JavaDebuggerBundle.message("error.no.debug.listen.port"));
        }
        String debuggerHostName = myConnection.getDebuggerHostName();
        if (debuggerHostName != null) {
          setConnectorArgument("localAddress", debuggerHostName);
        }
      }
      else { // is client mode, should attach to already running process
        if (myConnection.isUseSockets()) {
          String debuggerHostName = myConnection.getDebuggerHostName();
          if (debuggerHostName != null) {
            setConnectorArgument("hostname", debuggerHostName);
          }
          if (port == null) {
            throw new CantRunException(JavaDebuggerBundle.message("error.no.debug.attach.port"));
          }
          setConnectorArgument("port", port);
        }
        else {
          if (port == null) {
            throw new CantRunException(JavaDebuggerBundle.message("error.no.shmem.address"));
          }
          setConnectorArgument("name", port);
        }
        setConnectorArgument("timeout", "0"); // wait forever
      }
      return connector instanceof AttachingConnector
             ? attachConnector((AttachingConnector)connector)
             : connectorListen(port, (ListeningConnector)connector);
    }
    catch (IOException e) {
      throw new ExecutionException(processIOException(e, JavaDebuggerBundle.getAddressDisplayName(myConnection)), e);
    }
    catch (IllegalConnectorArgumentsException e) {
      throw new ExecutionException(processError(e), e);
    }
    finally {
      myArguments = null;
    }
  }

  private void setConnectorArgument(String name, String value) {
    Connector.Argument argument = myArguments.get(name);
    if (argument != null) {
      argument.setValue(value);
    }
  }

  private VirtualMachine connectorListen(String address, ListeningConnector connector)
    throws CantRunException, IOException, IllegalConnectorArgumentsException {
    if (address == null) {
      throw new CantRunException(JavaDebuggerBundle.message("error.no.debug.listen.port"));
    }
    // zero port number means the caller leaves to debugger to decide at which port to listen
    final Connector.Argument portArg = myConnection.isUseSockets() ? myArguments.get("port") : myArguments.get("name");
    if (portArg != null) {
      portArg.setValue(address);

      // to allow connector to listen on several auto generated addresses
      if (address.isEmpty() || address.equals("0")) {
        EmptyConnectorArgument uniqueArg = new EmptyConnectorArgument("argForUniqueness");
        myArguments.put(uniqueArg.name(), uniqueArg);
      }
    }
    setConnectorArgument("timeout", "0"); // wait forever
    try {
      String listeningAddress = connector.startListening(myArguments);
      String port = StringUtil.substringAfterLast(listeningAddress, ":");
      if (port != null) {
        listeningAddress = port;
      }
      myConnection.setDebuggerAddress(listeningAddress);
      myConnection.setApplicationAddress(listeningAddress);

      forEachSafe(myDebugProcessListeners, it -> it.connectorIsReady());

      return connector.accept(myArguments);
    }
    catch (IllegalArgumentException e) {
      throw new CantRunException(e.getLocalizedMessage());
    }
    finally {
      if (myArguments != null) {
        try {
          connector.stopListening(myArguments);
        }
        catch (IllegalArgumentException | IllegalConnectorArgumentsException ignored) {
          // ignored
        }
      }
    }
  }

  private VirtualMachine attachConnector(AttachingConnector connector)
    throws IOException, IllegalConnectorArgumentsException, CantRunException {
    forEachSafe(myDebugProcessListeners, it -> it.connectorIsReady());
    try {
      return connector.attach(myArguments);
    }
    catch (IllegalArgumentException e) {
      throw new CantRunException(e.getLocalizedMessage());
    }
  }

  public void showStatusText(final @Nls String text) {
    LOG.debug("Show status text: " + text);
    myShowStatusManager.showStatus(text);
  }

  private Connector getConnector() throws ExecutionException {
    if (myConnection instanceof PidRemoteConnection && !((PidRemoteConnection)myConnection).isFixedAddress()) {
      return ((PidRemoteConnection)myConnection).getConnector(this);
    }
    return findConnector(myConnection.isUseSockets(), myConnection.isServerMode());
  }

  public static @NotNull Connector findConnector(boolean useSockets, boolean listen) throws ExecutionException {
    String connectorName = (Registry.is("debugger.jb.jdi") ? "com.jetbrains.jdi." : "com.sun.jdi.") +
                           (useSockets ? "Socket" : "SharedMemory") +
                           (listen ? "Listen" : "Attach");
    return findConnector(connectorName);
  }

  public static @NotNull Connector findConnector(String connectorName) throws ExecutionException {
    VirtualMachineManager virtualMachineManager;
    if (connectorName.startsWith("com.jetbrains")) {
      virtualMachineManager = VirtualMachineManagerImpl.virtualMachineManager();
    }
    else {
      try {
        virtualMachineManager = Bootstrap.virtualMachineManager();
      }
      catch (Error e) {
        throw new ExecutionException(JavaDebuggerBundle.message("debugger.jdi.bootstrap.error",
                                                                e.getClass().getName() + " : " + e.getLocalizedMessage()));
      }
    }
    return StreamEx.of(virtualMachineManager.allConnectors())
      .findFirst(c -> connectorName.equals(c.name()))
      .orElseThrow(() -> new CantRunException(JavaDebuggerBundle.message("error.debug.connector.not.found", connectorName)));
  }

  protected void checkVirtualMachineVersion(VirtualMachine vm) {
    final String versionString = vm.version();
    if ("1.4.0".equals(versionString)) {
      DebuggerInvocationUtil.invokeLaterAnyModality(project, () -> Messages.showMessageDialog(
        project,
        JavaDebuggerBundle.message("warning.jdk140.unstable"), JavaDebuggerBundle.message("title.jdk140.unstable"), Messages.getWarningIcon()
      ));
    }
    if (getSession().getAlternativeJre() == null) {
      Sdk runjre = getSession().getRunJre();
      JavaVersion version = JavaVersion.tryParse(versionString);
      if (version != null && (runjre == null || runjre.getSdkType() instanceof JavaSdkType) && !versionMatch(runjre, version)) {
        Arrays.stream(ProjectJdkTable.getInstance().getAllJdks())
          .sorted(Comparator.comparing(sdk -> !(sdk.getSdkType() instanceof JavaSdk))) // prefer regular jdks
          .filter(sdk -> versionMatch(sdk, version))
          .findFirst().ifPresent(sdk -> {
            LOG.info(
              JavaDebuggerBundle.message("message.remote.jre.version.mismatch",
                                         versionString,
                                         runjre != null ? runjre.getVersionString() : "unknown",
                                         sdk.getName())
            );
            getSession().setAlternativeJre(sdk);
          });
      }
    }
  }

  private static boolean versionMatch(@Nullable Sdk sdk, @NotNull JavaVersion version) {
    if (sdk != null && sdk.getSdkType() instanceof JavaSdkType) {
      JavaVersion v = JavaVersion.tryParse(sdk.getVersionString());
      if (v != null) {
        return version.feature == v.feature && version.minor == v.minor && version.update == v.update;
      }
    }
    return false;
  }

  /*Event dispatching*/
  public void addEvaluationListener(EvaluationListener evaluationListener) {
    myEvaluationDispatcher.addListener(evaluationListener);
  }

  public void removeEvaluationListener(EvaluationListener evaluationListener) {
    myEvaluationDispatcher.removeListener(evaluationListener);
  }


  @Override
  public void addDebugProcessListener(DebugProcessListener listener, Disposable parentDisposable) {
    myDebugProcessListeners.add(listener, parentDisposable);
  }

  @Override
  public void addDebugProcessListener(DebugProcessListener listener) {
    if (disposable.isDisposed()) return;
    myDebugProcessListeners.add(listener, disposable);
  }

  @Override
  public void removeDebugProcessListener(DebugProcessListener listener) {
    myDebugProcessListeners.remove(listener);
  }

  public void addProcessListener(ProcessListener processListener) {
    synchronized (myProcessListeners) {
      if (getProcessHandler() != null) {
        getProcessHandler().addProcessListener(processListener);
      }
      else {
        myProcessListeners.add(processListener);
      }
    }
  }

  public void removeProcessListener(ProcessListener processListener) {
    synchronized (myProcessListeners) {
      if (getProcessHandler() != null) {
        getProcessHandler().removeProcessListener(processListener);
      }
      else {
        myProcessListeners.remove(processListener);
      }
    }
  }

  /* getters */
  public RemoteConnection getConnection() {
    return myConnection;
  }

  @Override
  public ExecutionResult getExecutionResult() {
    return myExecutionResult;
  }

  @Override
  public @NotNull Project getProject() {
    return project;
  }

  public boolean canRedefineClasses() {
    final VirtualMachineProxyImpl vm = myVirtualMachineProxy;
    return vm != null && vm.canRedefineClasses();
  }

  public boolean isInInitialState() {
    return myState.get() == State.INITIAL;
  }

  @Override
  public boolean isAttached() {
    return myState.get() == State.ATTACHED;
  }

  @Override
  public boolean isDetached() {
    return myState.get() == State.DETACHED;
  }

  @Override
  public boolean isDetaching() {
    return myState.get() == State.DETACHING;
  }

  @Override
  public RequestManagerImpl getRequestsManager() {
    return requestManager;
  }

  /**
   * Get the current VM proxy connected to the process.
   * The VM can change due to a single debug process can be connected to several VMs (see {@link DebugProcessImpl#reattach(DebugEnvironment, boolean, Runnable)})
   * Prefer {@link SuspendContextImpl#getVirtualMachineProxy()} when possible.
   */
  @Override
  @ApiStatus.Obsolete
  public @NotNull VirtualMachineProxyImpl getVirtualMachineProxy() {
    DebuggerManagerThreadImpl.assertIsManagerThread();
    final VirtualMachineProxyImpl vm = myVirtualMachineProxy;
    if (vm == null) {
      if (isInInitialState()) {
        throw new IllegalStateException("Virtual machine is not initialized yet");
      }
      else {
        throw new VMDisconnectedException();
      }
    }
    return vm;
  }

  @Override
  public void appendPositionManager(final PositionManager positionManager) {
    DebuggerManagerThreadImpl.assertIsManagerThread();
    myPositionManager.appendPositionManager(positionManager);
  }

  private final List<SteppingBreakpoint> mySteppingBreakpoints = new ArrayList<>();

  public void setSteppingBreakpoint(@NotNull SteppingBreakpoint breakpoint) {
    mySteppingBreakpoints.add(breakpoint);
  }

  public void cancelSteppingBreakpoints() {
    DebuggerManagerThreadImpl.assertIsManagerThread();
    boolean isRestoreBreakpoints = false;
    for (SteppingBreakpoint runToCursorBreakpoint : mySteppingBreakpoints) {
      getRequestsManager().deleteRequest(runToCursorBreakpoint);
      isRestoreBreakpoints |= runToCursorBreakpoint.isRestoreBreakpoints();
    }
    if (isRestoreBreakpoints) {
      DebuggerManagerEx.getInstanceEx(getProject()).getBreakpointManager().enableBreakpoints(this);
    }
    mySteppingBreakpoints.clear();
  }

  public static void prepareAndSetSteppingBreakpoint(SuspendContextImpl context,
                                                     @NotNull SteppingBreakpoint breakpoint,
                                                     RequestHint hint,
                                                     boolean resetThreadFilter) {
    prepareAndSetSteppingBreakpoint(context, breakpoint, hint, resetThreadFilter, -1);
  }

  public static void prepareAndSetSteppingBreakpoint(SuspendContextImpl context,
                                                     @NotNull SteppingBreakpoint breakpoint,
                                                     RequestHint hint,
                                                     boolean resetThreadFilter,
                                                     int suspendPolicy) {
    DebugProcessImpl debugProcess = context.getDebugProcess();
    if (resetThreadFilter) {
      BreakpointManager breakpointManager = DebuggerManagerEx.getInstanceEx(debugProcess.getProject()).getBreakpointManager();
      breakpointManager.removeThreadFilter(debugProcess); // clear the filter on resume
    }
    breakpoint.setSuspendPolicy(
      suspendPolicy == EventRequest.SUSPEND_EVENT_THREAD ? DebuggerSettings.SUSPEND_THREAD : DebuggerSettings.SUSPEND_ALL);
    breakpoint.createRequest(debugProcess);
    breakpoint.setRequestHint(hint);
    debugProcess.setSteppingBreakpoint(breakpoint);
  }

  public void resetIgnoreSteppingFilters(@Nullable Location location, @Nullable RequestHint hint) {
    if (hint != null && hint.isResetIgnoreFilters() && location != null && !isPositionFiltered(location)) {
      getSession().resetIgnoreStepFiltersFlag();
    }
  }

  private void detachProcess(boolean closedByUser, boolean keepManagerThread, Consumer<@Nullable VirtualMachineData> callback) {
    DebuggerManagerThreadImpl.assertIsManagerThread();

    if (myState.compareAndSet(State.INITIAL, State.DETACHING) || myState.compareAndSet(State.ATTACHED, State.DETACHING)) {
      try {
        if (!keepManagerThread) {
          myDebuggerManagerThread.close();
        }
      }
      finally {
        if (!(myConnection instanceof RemoteConnectionStub)) {
          VirtualMachineData vmData = new VirtualMachineData(myVirtualMachineProxy, myConnection, myDebuggerManagerThread);
          myVirtualMachineProxy = null;
          myPositionManager = CompoundPositionManager.EMPTY;
          myReturnValueWatcher = null;
          myNodeRenderersMap.clear();
          myRenderers.clear();
          myState.compareAndSet(State.DETACHING, State.DETACHED);
          try {
            forEachSafe(myDebugProcessListeners, it -> it.processDetached(this, closedByUser));
          }
          finally {
            callback.accept(vmData);
          }
        }
      }
    }
  }

  protected void closeCurrentProcess(boolean closedByUser) {
    detachProcess(closedByUser, false, vmData -> {
      //if (DebuggerSettings.getInstance().UNMUTE_ON_STOP) {
      //  XDebugSession session = mySession.getXDebugSession();
      //  if (session != null) {
      //    session.setBreakpointMuted(false);
      //  }
      //}
      if (vmData != null && vmData.vm != null) {
        try {
          vmData.vm.dispose(); // to be on the safe side ensure that VM mirror, if present, is disposed and invalidated
        }
        catch (Throwable ignored) {
        }
      }

      var attachedNewThread = unstashAndReattach();
      if (!attachedNewThread) {
        onRootProcessClosed();
      }
    });
  }

  private void onRootProcessClosed() {
    myDebuggerManagerThread.cancelScope();
    myWaitFor.up();
  }

  @Contract(pure = true)
  private static String formatMessage(String message) {
    final int lineLength = 90;
    StringBuilder buf = new StringBuilder(message.length());
    int index = 0;
    while (index < message.length()) {
      buf.append(message, index, Math.min(index + lineLength, message.length())).append('\n');
      index += lineLength;
    }
    return buf.toString();
  }

  public static @NlsContexts.DialogMessage String processError(Exception e) {
    String message;

    if (e instanceof VMStartException e1) {
      message = e1.getLocalizedMessage();
    }
    else if (e instanceof IllegalConnectorArgumentsException e1) {
      final List<String> invalidArgumentNames = e1.argumentNames();
      message = formatMessage(
        JavaDebuggerBundle.message("error.invalid.argument", invalidArgumentNames.size()) + ": " + e1.getLocalizedMessage()) + invalidArgumentNames;
      LOG.debug(e1);
    }
    else if (e instanceof CantRunException) {
      message = e.getLocalizedMessage();
    }
    else if (e instanceof VMDisconnectedException) {
      message = JavaDebuggerBundle.message("error.vm.disconnected");
    }
    else if (e instanceof IOException) {
      message = processIOException((IOException)e, null);
    }
    else if (e instanceof ExecutionException) {
      message = e.getLocalizedMessage();
    }
    else {
      message = JavaDebuggerBundle.message("error.exception.while.connecting", e.getClass().getName(), e.getLocalizedMessage());
      LOG.debug(e);
    }
    return message;
  }

  public static @NotNull @NlsContexts.DialogMessage String processIOException(@NotNull IOException e, @Nullable String address) {
    if (e instanceof UnknownHostException) {
      if (address != null) {
        return JavaDebuggerBundle.message("error.unknown.host.with.address", address) + ":\n" + e.getLocalizedMessage();
      }
      return JavaDebuggerBundle.message("error.unknown.host") + ":\n" + e.getLocalizedMessage();
    }

    // Failed SA attach
    Throwable cause = e.getCause();
    if (cause instanceof InvocationTargetException) {
      if (cause.getCause() != null) {
        return cause.getCause().getLocalizedMessage();
      }
    }

    @Nls StringBuilder buf = new StringBuilder();
    if (address != null) {
      buf.append(JavaDebuggerBundle.message("error.cannot.open.debugger.port"));
      buf.append(" (").append(address).append("): ");
    }
    buf.append(e.getClass().getName()).append(" ");
    if (!StringUtil.isEmpty(e.getLocalizedMessage())) {
      buf.append('"').append(e.getLocalizedMessage()).append('"');
    }
    if (cause != null && !StringUtil.isEmpty(cause.getLocalizedMessage())) {
      buf.append(" (").append(cause.getLocalizedMessage()).append(')');
    }
    LOG.debug(e);
    return buf.toString();
  }

  public void dispose() {
    CoroutineScopeKt.cancel(myCoroutineScope, null);
    LOG.debug("Debug has been finished");
    Disposer.dispose(disposable);
    requestManager.setThreadFilter(null);
  }

  @ApiStatus.Obsolete
  @Override
  public final @NotNull DebuggerManagerThreadImpl getManagerThread() {
    return myDebuggerManagerThread;
  }

  public boolean isCurrentVirtualMachine(@NotNull VirtualMachineProxyImpl vmProxy) {
    VirtualMachine vm = ObjectUtils.doIfNotNull(myVirtualMachineProxy, it -> it.getVirtualMachine());
    return vmProxy.getVirtualMachine() == vm;
  }

  private static int getInvokePolicy(SuspendContext suspendContext) {
    if (suspendContext.getSuspendPolicy() == EventRequest.SUSPEND_EVENT_THREAD ||
        isResumeOnlyCurrentThread() ||
        ThreadBlockedMonitor.getSingleThreadedEvaluationThreshold() > 0) {
      return ObjectReference.INVOKE_SINGLE_THREADED;
    }
    return 0;
  }

  @Override
  public void waitFor() {
    LOG.assertTrue(!DebuggerManagerThreadImpl.isManagerThread());
    myWaitFor.waitFor();
  }

  @Override
  public void waitFor(long timeout) {
    LOG.assertTrue(!DebuggerManagerThreadImpl.isManagerThread());
    myWaitFor.waitFor(timeout);
  }


  private void resumeThreadsUnderEvaluationAndExplicitlyResumedAfterPause(@NotNull SuspendContextImpl suspendAllContext) {
    for (SuspendContextImpl suspendContext : mySuspendManager.getEventContexts()) {
      EvaluationContextImpl evaluationContext = suspendContext.getEvaluationContext();
      if (evaluationContext != null) {
        ThreadReferenceProxyImpl threadForEvaluation = evaluationContext.getThreadForEvaluation();
        if (threadForEvaluation == null) {
          logError("Thread for evaluation in evaluating " + suspendContext + " is null");
          continue;
        }
        if (threadForEvaluation == suspendAllContext.getEventThread()) {
          logError("Paused suspend-all context " + suspendAllContext + " for evaluating context " + suspendContext);
          continue;
        }
        if (suspendAllContext.suspends(threadForEvaluation)) {
          mySuspendManager.resumeThread(suspendAllContext, threadForEvaluation);
        }
      }
    }
    List<ThreadReferenceProxyImpl> threads = new ArrayList<>(mySuspendManager.myExplicitlyResumedThreads);
    for (ThreadReferenceProxyImpl thread : threads) {
      if (thread == suspendAllContext.getEventThread()) {
        // It seems, it is stopped on the breakpoint on this explicitly resumed thread
        mySuspendManager.myExplicitlyResumedThreads.remove(thread);
        continue;
      }
      if (!suspendAllContext.suspends(thread)) { // the previous loop can theoretically resume it already
        mySuspendManager.resumeThread(suspendAllContext, thread);
      }
    }
  }

  private abstract class InvokeCommand<E extends Value> {
    private final Method myMethod;
    private final List<Value> myArgs;
    protected final @NotNull EvaluationContextImpl myEvaluationContext;

    protected InvokeCommand(@NotNull Method method, @NotNull List<? extends Value> args, @NotNull EvaluationContextImpl evaluationContext) {
      myMethod = method;
      myArgs = new ArrayList<>(args);
      myEvaluationContext = evaluationContext;
    }

    @Override
    public String toString() {
      return "INVOKE: " + super.toString();
    }

    protected abstract E invokeMethod(ThreadReference thread, int invokePolicy, Method method, List<? extends Value> args)
      throws InvocationException, ClassNotLoadedException, IncompatibleThreadStateException, InvalidTypeException;


    E start(boolean internalEvaluate) throws EvaluateException {
      ReferenceType lastLoadedClass = null;
      ThreadReferenceProxyImpl lastUsedThread = null;
      while (true) {
        try {
          ThreadReferenceProxyImpl invokeThread = (ThreadReferenceProxyImpl)getEvaluationThread(myEvaluationContext);
          if (invokeThread == lastUsedThread) {
            throw new IllegalStateException("Endless loop while trying to identify thread to successful evaluation: " + invokeThread);
          }
          lastUsedThread = invokeThread;
          return startInternal(internalEvaluate, invokeThread);
        }
        catch (RetryEvaluationException e) {
          LOG.warn(e);
        }
        catch (ClassNotLoadedException e) {
          ReferenceType loadedClass = null;
          try {
            if (myEvaluationContext.isAutoLoadClasses()) {
              loadedClass = loadClass(myEvaluationContext, e, myEvaluationContext.getClassLoader());
            }
          }
          catch (Exception ignored) {
            loadedClass = null;
          }
          if (loadedClass == null) {
            throw EvaluateExceptionUtil.createEvaluateException(e);
          }
          else if (loadedClass.equals(lastLoadedClass)) { // check for endless loading in the incorrect class loader, see IDEA-335672
            throw EvaluateExceptionUtil.createEvaluateException(
              "Loading class " + e.className() + " in the wrong classloader " + myEvaluationContext.getClassLoader());
          }
          lastLoadedClass = loadedClass;
          lastUsedThread = null;
        }
      }
    }

    E startInternal(boolean internalEvaluate, @NotNull ThreadReferenceProxyImpl invokeThread)
      throws EvaluateException, ClassNotLoadedException {
      DebuggerManagerThreadImpl.assertIsManagerThread();
      SuspendContextImpl suspendContext = myEvaluationContext.getSuspendContext();
      if (!suspendContext.myInProgress) {
        logError("You can invoke methods only inside commands invoked for SuspendContext. " +
                 "myEvaluationContext = " + myEvaluationContext + ", invokeThread = " + invokeThread);
      }

      if (suspendContext.getSuspendPolicy() == EventRequest.SUSPEND_EVENT_THREAD && suspendContext.getEventThread() != invokeThread) {
        logError("Event thread context is used to evaluate on another thread, context = " + suspendContext + ", invokeThread = " + invokeThread);
      }

      if (invokeThread.isEvaluating()) {
        throw EvaluateExceptionUtil.NESTED_EVALUATION_ERROR;
      }

      if (!suspendContext.suspends(invokeThread)) {
        throw EvaluateExceptionUtil.THREAD_WAS_RESUMED;
      }

      Set<SuspendContextImpl> suspendingContexts = SuspendManagerUtil.getSuspendingContexts(getSuspendManager(), invokeThread);

      myEvaluationDispatcher.getMulticaster().evaluationStarted(suspendContext);
      beforeMethodInvocation(suspendContext, myMethod, internalEvaluate);

      Object resumeData = null;
      try {
        for (SuspendContextImpl suspendingContext : suspendingContexts) {
          if (suspendingContext == suspendContext) {
            continue;
          }
          if (suspendingContext.getSuspendPolicy() == EventRequest.SUSPEND_EVENT_THREAD) {
            // Event thread contexts may come from breakpoint, class prepare and so on.
            // But it may happen before (and already processed) or during evaluation (and will be processed).
            // It should not be ok to have several standing event thread contexts for the same thread.
            logError("Evaluating on " + suspendContext + ", but found existed event thread context " + suspendingContext);
          }
          if (LOG.isDebugEnabled()) {
            LOG.debug("Resuming " + invokeThread + " that is paused by " + suspendingContext);
          }
          getSuspendManager().resumeThread(suspendingContext, invokeThread);
        }

        if (!DebuggerUtils.isNewThreadSuspendStateTracking()) {
          resumeData = SuspendManagerUtil.prepareForResume(suspendContext);
        }
        myEvaluationContext.setThreadForEvaluation(invokeThread);

        invokeThread.getVirtualMachineProxy().clearCaches();

        return invokeMethodAndFork(suspendContext, invokeThread);
      }
      catch (IncompatibleThreadStateException e) {
        if (invokeThread == suspendContext.getEventThread()) {
          throw EvaluateExceptionUtil.createEvaluateException(e);
        }
        suspendContext.myNotExecutableThreads.add(invokeThread);
        String m = "Evaluation failed on non-primary thread '%s'. Will be a retry on primary suspended thread '%s'"
          .formatted(invokeThread, suspendContext.getEventThread());
        throw new RetryEvaluationException(m, e);
      }
      catch (InvocationException | InternalException | UnsupportedOperationException | ObjectCollectedException |
             InvalidTypeException e) {
        throw EvaluateExceptionUtil.createEvaluateException(e);
      }
      finally {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Evaluation finished in " + suspendContext);
        }
        myEvaluationContext.setThreadForEvaluation(null);
        if (DebuggerUtils.isNewThreadSuspendStateTracking() && !mySuspendManager.myExplicitlyResumedThreads.contains(invokeThread)) {
          for (SuspendContextImpl anotherContext : mySuspendManager.getEventContexts()) {
            if (anotherContext != suspendContext && !anotherContext.suspends(invokeThread)) {
              boolean shouldSuspendThread = false;
              if (anotherContext.getSuspendPolicy() == EventRequest.SUSPEND_EVENT_THREAD && anotherContext.getEventThread() == invokeThread) {
                // Event thread contexts may come from breakpoint, class prepare and so on.
                // But it may happen during evaluation and should be already processed.
                logError("Another event thread context after evaluation on " + invokeThread + " (" + suspendContext + ") : " + anotherContext);
                shouldSuspendThread = true;
              }
              if (anotherContext.getSuspendPolicy() == EventRequest.SUSPEND_ALL) {
                if (anotherContext.myResumedThreads == null || !anotherContext.myResumedThreads.contains(invokeThread)) {
                  logError("Suspend all context claims not suspending " + invokeThread + " but its resumed threads have no it: " + anotherContext.myResumedThreads);
                }
                shouldSuspendThread = true;
              }
              if (shouldSuspendThread) {
                mySuspendManager.suspendThread(anotherContext, invokeThread);
              }
            }
          }
        }
        else {
          if (resumeData != null) {
            SuspendManagerUtil.restoreAfterResume(suspendContext, resumeData);
          }
          for (SuspendContextImpl suspendingContext : mySuspendManager.getEventContexts()) {
            if (suspendingContexts.contains(suspendingContext) &&
                suspendingContext != suspendContext &&
                !suspendingContext.isEvaluating() &&
                !suspendingContext.suspends(invokeThread)) {
              mySuspendManager.suspendThread(suspendingContext, invokeThread);
            }
          }
        }

        LOG.debug("getVirtualMachine().clearCaches()");
        invokeThread.getVirtualMachineProxy().clearCaches();
        afterMethodInvocation(suspendContext, internalEvaluate);

        myEvaluationDispatcher.getMulticaster().evaluationFinished(suspendContext);
      }
    }

    private E invokeMethodAndFork(SuspendContextImpl context, ThreadReferenceProxyImpl thread) throws InvocationException,
                                                                                                      ClassNotLoadedException,
                                                                                                      IncompatibleThreadStateException,
                                                                                                      InvalidTypeException {
      Ref<Exception> exception = Ref.create();
      Ref<E> result = Ref.create();
      Ref<ThreadBlockedMonitor.InvocationWatcher> invocationWatcherRef = Ref.create();
      if (LOG.isDebugEnabled()) {
        LOG.debug("Invoke in " + context);
        assertThreadSuspended(thread, context);
      }
      ThreadReference threadReference = thread.getThreadReference(); // init threadReference in the current debugger manager thread request
      context.getManagerThread().startLongProcessAndFork(() -> {
        try {
          try {
            if (myMethod.isVarArgs()) {
              // See IDEA-63581
              // if vararg parameter array is of interface type and Object[] is expected, JDI wrap it into another array,
              // in this case we have to unroll the array manually and pass its elements to the method instead of array object
              int lastIndex = myMethod.argumentTypeNames().size() - 1;
              if (lastIndex >= 0 && myArgs.size() > lastIndex) { // at least one varargs param
                Object firstVararg = myArgs.get(lastIndex);
                if (myArgs.size() == lastIndex + 1) { // only one vararg param
                  if (firstVararg instanceof ArrayReference arrayRef) {
                    if (((ArrayType)arrayRef.referenceType()).componentType() instanceof InterfaceType) {
                      List<String> argTypes = myMethod.argumentTypeNames();
                      if (argTypes.size() > lastIndex && argTypes.get(lastIndex).startsWith(CommonClassNames.JAVA_LANG_OBJECT)) {
                        // unwrap array of interfaces for vararg param
                        myArgs.remove(lastIndex);
                        myArgs.addAll(arrayRef.getValues());
                      }
                    }
                  }
                }
              }
            }

            if (!Patches.IBM_JDK_DISABLE_COLLECTION_BUG) {
              // ensure args are not collected
              StreamEx.of(myArgs).select(ObjectReference.class).forEach(DebuggerUtilsEx::disableCollection);
            }

            if (Patches.JDK_BUG_ID_21275177 && (ourTraceMask & VirtualMachine.TRACE_SENDS) != 0) {
              //noinspection ResultOfMethodCallIgnored
              StreamEx.of(myArgs).nonNull().forEach(Object::toString);
            }

            // workaround for jdi hang in trace mode, see IDEA-183387
            if (Patches.JDK_BUG_WITH_TRACE_SEND && (getTraceMask() & VirtualMachine.TRACE_SENDS) != 0) {
              StreamEx.of(myArgs).findAny(ThreadReference.class::isInstance).ifPresent(t -> {
                //noinspection UseOfSystemOutOrSystemErr
                System.err.println("[JDI: workaround for invocation of " + myMethod + "]");
                myMethod.virtualMachine().setDebugTraceMode(getTraceMask() & ~VirtualMachine.TRACE_SENDS);
              });
            }

            int invokePolicy = getInvokePolicy(context);
            invocationWatcherRef.set(myThreadBlockedMonitor.startInvokeWatching(invokePolicy, thread, context));
            myMethodInvocations.incrementAndGet();
            result.set(invokeMethod(threadReference, invokePolicy, myMethod, myArgs));
          }
          finally {
            if (Patches.JDK_BUG_WITH_TRACE_SEND && (getTraceMask() & VirtualMachine.TRACE_SENDS) != 0) {
              myMethod.virtualMachine().setDebugTraceMode(getTraceMask());
            }
            //  assertThreadSuspended(thread, context);
            if (!Patches.IBM_JDK_DISABLE_COLLECTION_BUG) {
              // ensure args are not collected
              StreamEx.of(myArgs).select(ObjectReference.class).forEach(DebuggerUtilsEx::enableCollection);
            }
          }
        }
        catch (Exception e) {
          exception.set(e);
        }
      });

      try {
        Exception ex = exception.get();
        if (ex != null) {
          if (ex instanceof InvocationException) {
            throw (InvocationException)ex;
          }
          else if (ex instanceof ClassNotLoadedException) {
            throw (ClassNotLoadedException)ex;
          }
          else if (ex instanceof IncompatibleThreadStateException) {
            throw (IncompatibleThreadStateException)ex;
          }
          else if (ex instanceof InvalidTypeException) {
            throw (InvalidTypeException)ex;
          }
          else if (ex instanceof RuntimeException) {
            throw (RuntimeException)ex;
          }
          else {
            logError("Unexpected exception", new Throwable(ex));
          }
        }

        return result.get();
      } finally {
        ThreadBlockedMonitor.InvocationWatcher invocationWatcher = invocationWatcherRef.get();
        if (invocationWatcher != null) {
          invocationWatcher.invocationFinished();
        }
      }
    }

    private static void assertThreadSuspended(final ThreadReferenceProxyImpl thread, final SuspendContextImpl context) {
      LOG.assertTrue(context.isEvaluating());
      try {
        final boolean isSuspended = thread.isSuspended();
        LOG.assertTrue(isSuspended, thread);
      }
      catch (ObjectCollectedException ignored) {
      }
    }
  }

  @Override
  public Value invokeMethod(@NotNull EvaluationContext evaluationContext,
                            @NotNull ObjectReference objRef,
                            @NotNull Method method,
                            @NotNull List<? extends Value> args) throws EvaluateException {
    return invokeInstanceMethod(evaluationContext, objRef, method, args, 0);
  }

  @Override
  public Value invokeInstanceMethod(@NotNull EvaluationContext evaluationContext,
                                    final @NotNull ObjectReference objRef,
                                    @NotNull Method method,
                                    @NotNull List<? extends Value> args,
                                    final int invocationOptions) throws EvaluateException {
    return invokeInstanceMethod(evaluationContext, objRef, method, args, invocationOptions, false);
  }

  public Value invokeInstanceMethod(@NotNull EvaluationContext evaluationContext,
                                    final @NotNull ObjectReference objRef,
                                    @NotNull Method method,
                                    @NotNull List<? extends Value> args,
                                    final int invocationOptions,
                                    boolean internalEvaluate) throws EvaluateException {
    InvocationResult result =
      tryInvokeWithHelper(method.declaringType(), objRef, method, args, (EvaluationContextImpl)evaluationContext, invocationOptions, internalEvaluate);
    if (result.isSuccess()) {
      return result.getValue();
    }
    return new InvokeCommand<>(method, args, (EvaluationContextImpl)evaluationContext) {
      @Override
      protected Value invokeMethod(ThreadReference thread, int invokePolicy, Method method, List<? extends Value> args)
        throws InvocationException, ClassNotLoadedException, IncompatibleThreadStateException, InvalidTypeException {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Invoking " + objRef.type().name() + "." + method.name());
        }
        //noinspection SSBasedInspection
        return objRef.invokeMethod(thread, method, args, invokePolicy | invocationOptions);
      }
    }.start(internalEvaluate);
  }

  static @NotNull ThreadReferenceProxy getEvaluationThread(final EvaluationContext evaluationContext) throws EvaluateException {
    ThreadReferenceProxy fromStackFrame =
      ObjectUtils.doIfNotNull(evaluationContext.getFrameProxy(), stackFrameProxy -> stackFrameProxy.threadProxy());
    SuspendContextImpl suspendContext = (SuspendContextImpl)evaluationContext.getSuspendContext();
    ThreadReferenceProxy evaluationThread = fromStackFrame != null ? fromStackFrame : suspendContext.getThread();
    if (suspendContext.myNotExecutableThreads.contains(evaluationThread)) {
      evaluationThread = suspendContext.getEventThread();
    }
    if (evaluationThread == null) {
      throw EvaluateExceptionUtil.NULL_STACK_FRAME;
    }
    return evaluationThread;
  }

  @Override
  public Value invokeMethod(EvaluationContext evaluationContext,
                            ClassType classType,
                            Method method,
                            List<? extends Value> args) throws EvaluateException {
    return invokeMethod(evaluationContext, classType, method, args, 0, false);
  }

  public Value invokeMethod(@NotNull EvaluationContext evaluationContext,
                            final @NotNull ClassType classType,
                            @NotNull Method method,
                            @NotNull List<? extends Value> args,
                            boolean internalEvaluate) throws EvaluateException {
    return invokeMethod(evaluationContext, classType, method, args, 0, internalEvaluate);
  }

  public Value invokeMethod(@NotNull EvaluationContext evaluationContext,
                            final @NotNull ClassType classType,
                            @NotNull Method method,
                            @NotNull List<? extends Value> args,
                            int extraInvocationOptions,
                            boolean internalEvaluate) throws EvaluateException {
    InvocationResult result =
      tryInvokeWithHelper(classType, null, method, args, (EvaluationContextImpl)evaluationContext, extraInvocationOptions, internalEvaluate);
    if (result.isSuccess()) {
      return result.getValue();
    }
    return new InvokeCommand<>(method, args, (EvaluationContextImpl)evaluationContext) {
      @Override
      protected Value invokeMethod(ThreadReference thread, int invokePolicy, Method method, List<? extends Value> args)
        throws InvocationException, ClassNotLoadedException, IncompatibleThreadStateException, InvalidTypeException {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Invoking " + classType.name() + "." + method.name());
        }
        //noinspection SSBasedInspection
        return classType.invokeMethod(thread, method, args, invokePolicy | extraInvocationOptions);
      }
    }.start(internalEvaluate);
  }

  public Value invokeMethod(EvaluationContext evaluationContext,
                            InterfaceType interfaceType,
                            Method method,
                            List<? extends Value> args) throws EvaluateException {
    InvocationResult result =
      tryInvokeWithHelper(interfaceType, null, method, args, (EvaluationContextImpl)evaluationContext, 0, false);
    if (result.isSuccess()) {
      return result.getValue();
    }
    return new InvokeCommand<>(method, args, (EvaluationContextImpl)evaluationContext) {
      @Override
      protected Value invokeMethod(ThreadReference thread, int invokePolicy, Method method, List<? extends Value> args)
        throws InvocationException, ClassNotLoadedException, IncompatibleThreadStateException, InvalidTypeException {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Invoking " + interfaceType.name() + "." + method.name());
        }

        try {
          //noinspection SSBasedInspection
          return interfaceType.invokeMethod(thread, method, args, invokePolicy);
        }
        catch (LinkageError e) {
          throw new IllegalStateException("Interface method invocation is not supported in JVM " +
                                          SystemInfo.JAVA_VERSION +
                                          ". Use JVM 1.8.0_45 or higher to run " +
                                          ApplicationNamesInfo.getInstance().getFullProductName());
        }
      }
    }.start(false);
  }


  @Override
  public ArrayReference newInstance(final ArrayType arrayType,
                                    final int dimension)
    throws EvaluateException {
    try {
      return arrayType.newInstance(dimension);
    }
    catch (Exception e) {
      throw EvaluateExceptionUtil.createEvaluateException(e);
    }
  }

  @Override
  public ObjectReference newInstance(final @NotNull EvaluationContext evaluationContext,
                                     final @NotNull ClassType classType,
                                     @NotNull Method method,
                                     @NotNull List<? extends Value> args) throws EvaluateException {
    return newInstance(evaluationContext, classType, method, args, 0, false);
  }

  public ObjectReference newInstance(final @NotNull EvaluationContext evaluationContext,
                                     final @NotNull ClassType classType,
                                     @NotNull Method method,
                                     @NotNull List<? extends Value> args,
                                     final int invocationOptions,
                                     boolean internalEvaluate) throws EvaluateException {
    InvocationResult result =
      tryInvokeWithHelper(classType, null, method, args, (EvaluationContextImpl)evaluationContext, invocationOptions, internalEvaluate);
    if (result.isSuccess()) {
      return (ObjectReference)result.getValue();
    }
    InvokeCommand<ObjectReference> invokeCommand = new InvokeCommand<>(method, args, (EvaluationContextImpl)evaluationContext) {
      @Override
      protected ObjectReference invokeMethod(ThreadReference thread, int invokePolicy, Method method, List<? extends Value> args)
        throws InvocationException, ClassNotLoadedException, IncompatibleThreadStateException, InvalidTypeException {
        if (LOG.isDebugEnabled()) {
          LOG.debug("New instance " + classType.name() + "." + method.name());
        }
        //noinspection SSBasedInspection
        return classType.newInstance(thread, method, args, invokePolicy | invocationOptions);
      }
    };
    return invokeCommand.start(internalEvaluate);
  }

  public void clearCashes(@NotNull SuspendContextImpl context) {
    if (!isAttached()) return;
    switch (context.getSuspendPolicy()) {
      case EventRequest.SUSPEND_ALL, EventRequest.SUSPEND_EVENT_THREAD -> context.getVirtualMachineProxy().clearCaches();
    }
  }

  protected void beforeSuspend(SuspendContextImpl suspendContext) {
    clearCashes(suspendContext);
  }

  private void beforeMethodInvocation(SuspendContextImpl suspendContext, Method method, boolean internalEvaluate) {
    if (LOG.isDebugEnabled()) {
      LOG.debug(
        "before invocation in  thread " + suspendContext.getThread().name() + " method " + (method == null ? "null" : method.name()));
    }

    if (!internalEvaluate) {
      if (method != null) {
        showStatusText(JavaDebuggerBundle.message("progress.evaluating", DebuggerUtilsEx.methodName(method)));
      }
      else {
        showStatusText(JavaDebuggerBundle.message("title.evaluating"));
      }
    }
  }

  private void afterMethodInvocation(SuspendContextImpl suspendContext, boolean internalEvaluate) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("after invocation in  thread " + suspendContext.getThread().name());
    }
    if (!internalEvaluate) {
      showStatusText("");
    }
  }

  @Override
  public ReferenceType findClass(@Nullable EvaluationContext evaluationContext, String className,
                                 ClassLoaderReference classLoader) throws EvaluateException {
    try {
      DebuggerManagerThreadImpl.assertIsManagerThread();
      SuspendContext suspendContext = evaluationContext != null ? evaluationContext.getSuspendContext() : null;
      ReferenceType result = findLoadedClass(suspendContext, className, classLoader);
      if (result == null && evaluationContext != null) {
        EvaluationContextImpl evalContext = (EvaluationContextImpl)evaluationContext;
        if (evalContext.isAutoLoadClasses()) {
          return loadClass(evalContext, className, classLoader);
        }
      }
      return result;
    }
    catch (InvocationException | InvalidTypeException | IncompatibleThreadStateException | ClassNotLoadedException e) {
      throw EvaluateExceptionUtil.createEvaluateException(e);
    }
  }

  public @Nullable ReferenceType findLoadedClass(@Nullable SuspendContext suspendContext,
                                                 String className,
                                                 ClassLoaderReference classLoader) {
    List<ReferenceType> types = findLoadedClasses(suspendContext, className);
    // first try to quickly find the equal classloader only
    ReferenceType result = ContainerUtil.find(types, refType -> Objects.equals(classLoader, refType.classLoader()));
    // now do the full visibility check
    if (result == null && classLoader != null) {
      result = ContainerUtil.find(types, refType -> isVisibleFromClassLoader(classLoader, refType));
    }
    return result;
  }

  public @NotNull List<ReferenceType> findLoadedClasses(@Nullable SuspendContext suspendContext, String className) {
    return ContainerUtil.filter(getCurrentVm(suspendContext).classesByName(className), ReferenceType::isPrepared);
  }

  private VirtualMachineProxyImpl getCurrentVm(@Nullable SuspendContext suspendContext) {
    return suspendContext != null ? ((SuspendContextImpl)suspendContext).getVirtualMachineProxy() : getVirtualMachineProxy();
  }

  private static boolean isVisibleFromClassLoader(@NotNull ClassLoaderReference fromLoader, final ReferenceType refType) {
    // IMPORTANT! Even if the refType is already loaded by some parent or bootstrap loader, it may not be visible from the given loader.
    // For example because there were no accesses yet from this loader to this class. So the loader is not in the list of "initialing" loaders
    // for this refType and the refType is not visible to the loader.
    // Attempt to evaluate method with this refType will yield ClassNotLoadedException.
    // The only way to say for sure whether the class is _visible_ to the given loader, is to use the following API call
    if (fromLoader instanceof ClassLoaderReferenceImpl) {
      return ((ClassLoaderReferenceImpl)fromLoader).isVisible(refType);
    }
    return fromLoader.visibleClasses().contains(refType);
  }

  private static String reformatArrayName(String className) {
    if (className.indexOf('[') == -1) return className;

    int dims = 0;
    while (className.endsWith("[]")) {
      className = className.substring(0, className.length() - 2);
      dims++;
    }

    StringBuilder buffer = new StringBuilder();
    StringUtil.repeatSymbol(buffer, '[', dims);
    String primitiveSignature = JVMNameUtil.getPrimitiveSignature(className);
    if (primitiveSignature != null) {
      buffer.append(primitiveSignature);
    }
    else {
      buffer.append('L');
      buffer.append(className);
      buffer.append(';');
    }
    return buffer.toString();
  }

  public ReferenceType loadClass(EvaluationContextImpl evaluationContext,
                                 ClassNotLoadedException exception,
                                 ClassLoaderReference classLoader)
    throws ClassNotLoadedException, EvaluateException, IncompatibleThreadStateException, InvocationException, InvalidTypeException {
    if (exception instanceof ExactClassNotLoadedException ex) {
      classLoader = ex.getClassLoader();
    }
    return loadClass(evaluationContext, exception.className(), classLoader);
  }

  public ReferenceType loadClass(@NotNull EvaluationContextImpl evaluationContext, String qName, ClassLoaderReference classLoader)
    throws InvocationException, ClassNotLoadedException, IncompatibleThreadStateException, InvalidTypeException, EvaluateException {

    DebuggerManagerThreadImpl.assertIsManagerThread();
    qName = reformatArrayName(qName);
    VirtualMachineProxyImpl virtualMachine = getCurrentVm(evaluationContext.getSuspendContext());
    ClassType classClassType = (ClassType)ContainerUtil.getFirstItem(virtualMachine.classesByName(CommonClassNames.JAVA_LANG_CLASS));
    if (classClassType == null) {
      logError("Unable to find loaded class " + CommonClassNames.JAVA_LANG_CLASS);
      return null;
    }
    final Method forNameMethod;
    List<Value> args = new ArrayList<>(); // do not use unmodifiable lists because the list is modified by JPDA
    args.add(DebuggerUtilsEx.mirrorOfString(qName, evaluationContext));
    if (classLoader != null) {
      forNameMethod = DebuggerUtils.findMethod(classClassType, "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
      args.add(virtualMachine.mirrorOf(true));
      args.add(classLoader);
    }
    else {
      forNameMethod = DebuggerUtils.findMethod(classClassType, "forName", "(Ljava/lang/String;)Ljava/lang/Class;");
    }
    if (forNameMethod == null) {
      logError("Unable to find forName method in " + classClassType);
      return null;
    }
    Value classReference = invokeMethod(evaluationContext, classClassType, forNameMethod, args, MethodImpl.SKIP_ASSIGNABLE_CHECK, true);
    if (classReference instanceof ClassObjectReference) {
      ReferenceType refType = ((ClassObjectReference)classReference).reflectedType();
      if (classLoader instanceof ClassLoaderReferenceImpl) {
        ((ClassLoaderReferenceImpl)classLoader).addVisible(refType);
      }
      return refType;
    }
    return null;
  }

  public void logThreads() {
    if (LOG.isDebugEnabled()) {
      try {
        Collection<ThreadReferenceProxyImpl> allThreads = getVirtualMachineProxy().allThreads();
        for (ThreadReferenceProxyImpl threadReferenceProxy : allThreads) {
          LOG.debug("Thread name=" + threadReferenceProxy.name() + " suspendCount()=" + threadReferenceProxy.getSuspendCount());
        }
      }
      catch (Exception e) {
        LOG.debug(e);
      }
    }
  }

  public void onHotSwapFinished() {
    getPositionManager().clearCache();
    StackCapturingLineBreakpoint.clearCaches(this);
  }

  public @NotNull SuspendManager getSuspendManager() {
    return mySuspendManager;
  }

  @Override
  public @NotNull CompoundPositionManager getPositionManager() {
    return myPositionManager;
  }
  //ManagerCommands

  @Override
  public void stop(boolean forceTerminate) {
    myIsStopped.set(true);
    stopConnecting(); // does this first place in case debugger manager hanged accepting debugger connection (forever)
    getManagerThread().terminateAndInvoke(createStopCommand(forceTerminate), ApplicationManager.getApplication().isUnitTestMode() ? 0 : DebuggerManagerThreadImpl.COMMAND_TIMEOUT);
  }

  public @NotNull StopCommand createStopCommand(boolean forceTerminate) {
    return new StopCommand(forceTerminate);
  }

  protected class StopCommand extends DebuggerCommandImpl {
    private final boolean myIsTerminateTargetVM;

    public StopCommand(boolean isTerminateTargetVM) {
      myIsTerminateTargetVM = isTerminateTargetVM;
    }

    @Override
    public Priority getPriority() {
      return Priority.HIGH;
    }

    @Override
    protected void action() {
      if (isAttached()) {
        VirtualMachineProxyImpl virtualMachineProxy = getVirtualMachineProxy();

        if (!virtualMachineProxy.canBeModified()) {
          closeCurrentProcess(false);
          return;
        }

        if (myIsTerminateTargetVM) {
          virtualMachineProxy.exit(-1);
        }
        else {
          // some VMs (like IBM VM 1.4.2 bundled with WebSphere) does not resume threads on dispose() like it should
          try {
            virtualMachineProxy.addedSuspendAllContext();
            virtualMachineProxy.resume();
          }
          finally {
            virtualMachineProxy.dispose();
          }
        }
      }
      else {
        try {
          stopConnecting();
        }
        finally {
          closeCurrentProcess(true);
        }
      }
    }
  }

  public class StepOutCommand extends StepCommand {
    private final int myStepSize;

    public StepOutCommand(SuspendContextImpl suspendContext, int stepSize) {
      this(suspendContext, stepSize, null);
    }

    public StepOutCommand(SuspendContextImpl suspendContext, int stepSize, @Nullable MethodFilter filter) {
      super(suspendContext, filter);
      myStepSize = stepSize;
    }

    @Override
    public void contextAction(@NotNull SuspendContextImpl suspendContext) {
      showStatusText(JavaDebuggerBundle.message("status.step.out"));
      final ThreadReferenceProxyImpl thread = getContextThread();
      RequestHint hint = getHint(suspendContext, thread, null);
      applyThreadFilter(getThreadFilterFromContext(suspendContext));
      startWatchingMethodReturn(thread);
      step(suspendContext, thread, hint, createCommandToken());
      super.contextAction(suspendContext);
    }

    @Override
    public @NotNull RequestHint getHint(SuspendContextImpl suspendContext, ThreadReferenceProxyImpl thread, @Nullable RequestHint parentHint) {
      RequestHint hint = new RequestHint(thread, suspendContext, StepRequest.STEP_LINE, StepRequest.STEP_OUT, null, parentHint);
      hint.setIgnoreFilters(mySession.shouldIgnoreSteppingFilters());
      return hint;
    }

    @Override
    public void step(SuspendContextImpl suspendContext, ThreadReferenceProxyImpl stepThread, RequestHint hint, Object commandToken) {
      super.step(suspendContext, stepThread, hint, commandToken);
      doStep(suspendContext, stepThread, myStepSize, StepRequest.STEP_OUT, hint, commandToken);
    }

    @Override
    protected @NotNull SteppingAction getSteppingAction() {
      return SteppingAction.STEP_OUT;
    }
  }

  public class StepIntoCommand extends StepCommand {
    private final boolean myForcedIgnoreFilters;
    private final @Nullable StepIntoBreakpoint myBreakpoint;
    private final int myStepSize;

    public StepIntoCommand(SuspendContextImpl suspendContext, boolean ignoreFilters, final @Nullable MethodFilter methodFilter,
                           int stepSize) {
      super(suspendContext, methodFilter);
      myForcedIgnoreFilters = ignoreFilters || methodFilter != null;
      myBreakpoint = methodFilter instanceof BreakpointStepMethodFilter ?
        DebuggerManagerEx.getInstanceEx(project).getBreakpointManager().addStepIntoBreakpoint(((BreakpointStepMethodFilter)methodFilter)) :
        null;
      myStepSize = stepSize;
    }

    @Override
    public void contextAction(@NotNull SuspendContextImpl suspendContext) {
      showStatusText(JavaDebuggerBundle.message("status.step.into"));
      final ThreadReferenceProxyImpl stepThread = getContextThread();
      final RequestHint hint = getHint(suspendContext, stepThread, null);
      if (myForcedIgnoreFilters) {
        mySession.setIgnoreStepFiltersFlag(getFrameCount(stepThread, suspendContext));
      }
      hint.setIgnoreFilters(myForcedIgnoreFilters || mySession.shouldIgnoreSteppingFilters());
      applyThreadFilter(getThreadFilterFromContext(suspendContext));
      if (myBreakpoint != null) {
        prepareAndSetSteppingBreakpoint(suspendContext, myBreakpoint, hint, false);
      }
      step(suspendContext, stepThread, hint, createCommandToken());
      super.contextAction(suspendContext);
    }

    @Override
    public @NotNull RequestHint getHint(SuspendContextImpl suspendContext, ThreadReferenceProxyImpl stepThread, @Nullable RequestHint parentHint) {
      final RequestHint hint = new RequestHint(stepThread, suspendContext, StepRequest.STEP_LINE, StepRequest.STEP_INTO, myMethodFilter, parentHint);
      hint.setResetIgnoreFilters(myMethodFilter != null && !mySession.shouldIgnoreSteppingFilters());
      return hint;
    }

    @Override
    public void step(SuspendContextImpl suspendContext, ThreadReferenceProxyImpl stepThread, RequestHint hint, Object commandToken) {
      super.step(suspendContext, stepThread, hint, createCommandToken());
      doStep(suspendContext, stepThread, myStepSize, StepRequest.STEP_INTO, hint, commandToken);
    }

    @Override
    protected @NotNull SteppingAction getSteppingAction() {
      return SteppingAction.STEP_INTO;
    }
  }

  public class StepOverCommand extends StepCommand {
    private final boolean myIsIgnoreBreakpoints;
    private final int myStepSize;

    public StepOverCommand(SuspendContextImpl suspendContext,
                           boolean ignoreBreakpoints,
                           @Nullable MethodFilter methodFilter,
                           int stepSize) {
      super(suspendContext, methodFilter);
      myIsIgnoreBreakpoints = ignoreBreakpoints;
      myStepSize = stepSize;
    }

    public StepOverCommand(SuspendContextImpl suspendContext, boolean ignoreBreakpoints, int stepSize) {
      this(suspendContext, ignoreBreakpoints, null, stepSize);
    }

    protected int getStepDepth() {
      return StepRequest.STEP_OVER;
    }

    protected @NotNull @Nls String getStatusText() {
      return JavaDebuggerBundle.message("status.step.over");
    }

    @Override
    public @Nullable RequestHint getHint(SuspendContextImpl suspendContext, ThreadReferenceProxyImpl stepThread, @Nullable RequestHint parentHint) {
      // need this hint while stepping over for JSR45 support:
      // several lines of generated java code may correspond to a single line in the source file,
      // from which the java code was generated
      RequestHint hint = new RequestHint(stepThread, suspendContext, StepRequest.STEP_LINE, StepRequest.STEP_OVER, myMethodFilter, parentHint);
      hint.setRestoreBreakpoints(myIsIgnoreBreakpoints);
      hint.setIgnoreFilters(myIsIgnoreBreakpoints || mySession.shouldIgnoreSteppingFilters());
      return hint;
    }

    @Override
    public void contextAction(@NotNull SuspendContextImpl suspendContext) {
      showStatusText(getStatusText());

      prepareSteppingRequestsAndHints(suspendContext);

      if (myIsIgnoreBreakpoints) {
        DebuggerManagerEx.getInstanceEx(project).getBreakpointManager().disableBreakpoints(DebugProcessImpl.this);
      }
      super.contextAction(suspendContext);
    }

    public final void prepareSteppingRequestsAndHints(@NotNull SuspendContextImpl suspendContext) {
      ThreadReferenceProxyImpl stepThread = getContextThread();

      RequestHint hint = getHint(suspendContext, stepThread, null);

      applyThreadFilter(getThreadFilterFromContext(suspendContext));

      startWatchingMethodReturn(stepThread);

      step(suspendContext, stepThread, hint, createCommandToken());
    }

    @Override
    public void step(SuspendContextImpl suspendContext, ThreadReferenceProxyImpl stepThread, RequestHint hint, Object commandToken) {
      super.step(suspendContext, stepThread, hint, commandToken);
      doStep(suspendContext, stepThread, myStepSize, getStepDepth(), hint, commandToken);
    }

    @Override
    protected @NotNull SteppingAction getSteppingAction() {
      return SteppingAction.STEP_OVER;
    }
  }

  public class RunToCursorCommand extends StepCommand {
    private final RunToCursorBreakpoint myRunToCursorBreakpoint;
    private final boolean myIgnoreBreakpoints;

    public RunToCursorCommand(SuspendContextImpl suspendContext, @NotNull XSourcePosition position, final boolean ignoreBreakpoints) {
      super(suspendContext, null);
      myIgnoreBreakpoints = ignoreBreakpoints;
      myRunToCursorBreakpoint = DebuggerManagerEx.getInstanceEx(project).getBreakpointManager()
        .addRunToCursorBreakpoint(position, ignoreBreakpoints);
    }

    protected boolean shouldExecuteRegardlessOfRequestWarnings() { return false; }

    @Override
    protected void resumeAction() {
      project.getMessageBus().syncPublisher(DebuggerActionListener.TOPIC).onRunToCursor(getSuspendContext());
      super.resumeAction();
    }

    @Override
    public void contextAction(@NotNull SuspendContextImpl context) {
      showStatusText(JavaDebuggerBundle.message("status.run.to.cursor"));
      cancelSteppingBreakpoints();
      if (myRunToCursorBreakpoint == null) {
        return;
      }
      if (myIgnoreBreakpoints) {
        DebuggerManagerEx.getInstanceEx(project).getBreakpointManager().disableBreakpoints(DebugProcessImpl.this);
      }
      beforeSteppingAction(context);
      LightOrRealThreadInfo threadFilterFromContext = getThreadFilterFromContext(context);
      applyThreadFilter(threadFilterFromContext);
      int breakpointSuspendPolicy = context.getSuspendPolicy();
      // In the case of the isAlwaysSuspendThreadBeforeSwitch mode, the switch will be performed for all breakpoints by engine
      if (!DebuggerUtils.isAlwaysSuspendThreadBeforeSwitch()) {
        if (threadFilterFromContext != null && threadFilterFromContext.getRealThread() == null) {
          breakpointSuspendPolicy = EventRequest.SUSPEND_EVENT_THREAD;
        }
      }
      prepareAndSetSteppingBreakpoint(context, myRunToCursorBreakpoint, null, false, breakpointSuspendPolicy);
      final DebugProcessImpl debugProcess = context.getDebugProcess();

      if (shouldExecuteRegardlessOfRequestWarnings() || debugProcess.getRequestsManager().getWarning(myRunToCursorBreakpoint) == null) {
        super.contextAction(context);
      }
      else {
        DebuggerInvocationUtil.invokeLaterAnyModality(project, () -> {
          Messages.showErrorDialog(
            JavaDebuggerBundle.message("error.running.to.cursor.no.executable.code",
                                       myRunToCursorBreakpoint.getFileName(),
                                       myRunToCursorBreakpoint.getLineIndex() + 1),
            UIUtil.removeMnemonic(ActionsBundle.actionText(XDebuggerActions.RUN_TO_CURSOR)));
          DebuggerSession session = debugProcess.getSession();
          session.getContextManager().setState(DebuggerContextUtil.createDebuggerContext(session, context),
                                               DebuggerSession.State.PAUSED, DebuggerSession.Event.CONTEXT, null);
        });
      }
    }

    @Override
    protected @NotNull SteppingAction getSteppingAction() {
      return SteppingAction.RUN_TO_CURSOR;
    }
  }

  public abstract class StepCommand extends ResumeCommand {
    protected final @Nullable MethodFilter myMethodFilter;

    StepCommand(SuspendContextImpl suspendContext, @Nullable MethodFilter filter) {
      super(suspendContext);
      myMethodFilter = filter;
    }

    @Override
    protected void resumeAction() {
      SuspendContextImpl context = getSuspendContext();
      if (context != null &&
          (context.getSuspendPolicy() == EventRequest.SUSPEND_EVENT_THREAD || isResumeOnlyCurrentThread())) {
        myThreadBlockedMonitor.startWatching(myContextThread);
      }

      if (context != null
          && isResumeOnlyCurrentThread()
          && context.getSuspendPolicy() == EventRequest.SUSPEND_ALL
          && myContextThread != null) {
        getSuspendManager().resumeThread(context, myContextThread);
      }
      else {
        super.resumeAction();
      }
    }

    public void step(SuspendContextImpl suspendContext, ThreadReferenceProxyImpl stepThread, RequestHint hint, Object commandToken) {
      beforeSteppingAction(suspendContext);
    }

    protected void beforeSteppingAction(SuspendContextImpl context) {
      if (context != null) {
        DebuggerUtilsImpl.forEachSafe(SteppingListener.getExtensions(),
                                      listener -> listener.beforeSteppingStarted(context, getSteppingAction()));
      }
    }

    protected @NotNull Engine getEngine() {
      return Engine.JAVA;
    }

    protected abstract @NotNull SteppingAction getSteppingAction();

    public final @NotNull Object createCommandToken() {
      return StatisticsStorage.createSteppingToken(getSteppingAction(), getEngine());
    }

    public @Nullable RequestHint getHint(SuspendContextImpl suspendContext, ThreadReferenceProxyImpl stepThread, @Nullable RequestHint parentHint) {
      return null;
    }
  }

  public abstract class ResumeCommand extends SuspendContextCommandImpl {
    protected final @Nullable ThreadReferenceProxyImpl myContextThread;

    public ResumeCommand(SuspendContextImpl suspendContext) {
      super(suspendContext);
      final ThreadReferenceProxyImpl thread;
      if (suspendContext != null) {
        JavaExecutionStack activeExecutionStack = suspendContext.getActiveExecutionStack();
        if (activeExecutionStack != null) {
          thread = activeExecutionStack.getThreadProxy();
        }
        else {
          thread = suspendContext.getThread();
        }
      }
      else {
        thread = null;
      }
      myContextThread = thread != null ? thread : getDebuggerContext().getThreadProxy();
    }

    @Override
    public Priority getPriority() {
      return Priority.HIGH;
    }

    @Override
    public void contextAction(@NotNull SuspendContextImpl suspendContext) {
      showStatusText(JavaDebuggerBundle.message("status.process.resumed"));
      if (!(this instanceof StepCommand)) {
        DebuggerUtilsImpl.forEachSafe(SteppingListener.getExtensions(), listener -> listener.beforeResume(suspendContext));
      }
      resumeAction();

      forEachSafe(myDebugProcessListeners, it -> it.resumed(suspendContext));
    }

    protected void resumeAction() {
      LOG.debug("Resuming for command " + this);
      getSuspendManager().resume(getSuspendContext());
    }

    public @Nullable ThreadReferenceProxyImpl getContextThread() {
      return myContextThread;
    }

    public @Nullable LightOrRealThreadInfo getThreadFilterFromContext(@NotNull SuspendContextImpl suspendContext) {
      return myContextThread != null ? new RealThreadInfo(myContextThread.getThreadReference()) : null;
    }

    protected void applyThreadFilter(@Nullable LightOrRealThreadInfo threadInfo) {
      boolean isLightThread = threadInfo != null && threadInfo.getRealThread() == null;
      if (isLightThread || getSuspendContext().getSuspendPolicy() == EventRequest.SUSPEND_ALL) {
        // there could be explicit resume as a result of call to voteSuspend()
        // e.g. when breakpoint was considered invalid, in that case the filter will be applied _after_
        // resuming and all breakpoints in other threads will be ignored.
        // As resume() implicitly cleares the filter, the filter must be always applied _before_ any resume() action happens
        final BreakpointManager breakpointManager = DebuggerManagerEx.getInstanceEx(getProject()).getBreakpointManager();
        breakpointManager.applyThreadFilter(DebugProcessImpl.this, threadInfo);
      }
    }
  }

  private class PauseCommand extends DebuggerCommandImpl {
    private final @Nullable ThreadReferenceProxyImpl myPredefinedThread;

    PauseCommand(@Nullable ThreadReferenceProxyImpl thread) {
      myPredefinedThread = thread;
    }

    private boolean isDebuggerAgentAvailable() {
      return !getVirtualMachineProxy().classesByName("com.intellij.rt.debugger.agent.DebuggerAgent").isEmpty();
    }

    @Override
    public void action() {
      if (!isAttached() || getVirtualMachineProxy().isPausePressed()) {
        return;
      }
      logThreads();
      if (Registry.is("debugger.evaluate.on.pause")) {
        tryPauseWithEvaluatableContext();
      } else {
        fallbackPauseWithNonEvaluatableContext();
        DebuggerStatistics.logEvaluatablePauseDisabled(project);
      }
    }

    private void tryPauseWithEvaluatableContext() {
      DebugProcessImpl process = getVirtualMachineProxy().getDebugProcess();
      stopOnAnyMethodEntryAndGetSuspendContext(process);
    }

    private void stopOnAnyMethodEntryAndGetSuspendContext(DebugProcessImpl process) {
      var evaluatableContextObtained = new DebuggerCompletableFuture<Void>();
      var evaluatableContextFuture = new DebuggerCompletableFuture<SuspendContextImpl>();
      var requestor = new FilteredRequestorImpl(process.project) {
        @Override
        public boolean shouldIgnoreThreadFiltering() {
          // Such low-level requests are not supposed to be filtered out by thread filter (which is set during stepping, for example)
          return true;
        }

        @Override
        public boolean processLocatableEvent(@NotNull SuspendContextCommandImpl action, LocatableEvent event) {
          process.getRequestsManager().deleteRequest(this);
          var evaluatableContext = action.getSuspendContext();
          // evaluatableContextObtained future is used to timeout on getting the suspendContext,
          // evaluatableContextFuture actually stores the suspendContext.
          // We complete 2 futures for the case when the timeout expires and the request is deleted,
          // but the request was hit and processed concurrently with the timeout, thus we can still get the saved suspendContext.
          evaluatableContextFuture.complete(evaluatableContext);
          evaluatableContextObtained.complete(null);
          return true;
        }

        @Override
        public String getSuspendPolicy() {
          return DebuggerSettings.SUSPEND_ALL;
        }
      };
      var request = process.getRequestsManager().createMethodEntryRequest(requestor);
      request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
      request.setEnabled(true);

      long timeout = Registry.intValue("debugger.evaluate.on.pause.timeout.ms", 500);
      evaluatableContextObtained
        .orTimeout(timeout, TimeUnit.MILLISECONDS)
        .whenComplete((evaluatableContext, error) -> {
          process.getManagerThread().schedule(new DebuggerCommandImpl() {
            @Override
            protected void action() {
              if (error != null) {
                process.getRequestsManager().deleteRequest(requestor);
                // Check if the request was processed concurrently (before it was removed on a timeout) and saved the suspendContext.
                if (evaluatableContextFuture.isDone()) {
                  var evaluatableContext = evaluatableContextFuture.get();
                  assert evaluatableContext != null;
                  setSuspendContextAndCheckConsistency(evaluatableContext);
                  DebuggerStatistics.logEvaluatablePauseSuccess(project, isDebuggerAgentAvailable());
                } else {
                  fallbackPauseWithNonEvaluatableContext();
                  DebuggerStatistics.logEvaluatablePauseFailure(project, isDebuggerAgentAvailable());
                }
              } else {
                var evaluatableContext = evaluatableContextFuture.get();
                assert evaluatableContext != null;
                setSuspendContextAndCheckConsistency(evaluatableContext);
                DebuggerStatistics.logEvaluatablePauseSuccess(project, isDebuggerAgentAvailable());
              }
            }
          });
        });
    }

    private void setSuspendContextAndCheckConsistency(@NotNull SuspendContextImpl suspendContext) {
      logThreads();
      if (myPredefinedThread != null && !myPredefinedThread.isCollected()) {
        suspendContext.setThread(myPredefinedThread.getThreadReference());
      }

      myDebuggerManagerThread.schedule(new SuspendContextCommandImpl(suspendContext) {
        @Override
        public void contextAction(@NotNull SuspendContextImpl suspendContext) {
          // New events should not come after global pause
          DebuggerDiagnosticsUtil.checkThreadsConsistency(DebugProcessImpl.this, true);
        }
        @Override
        public Priority getPriority() {
          return Priority.LOWEST;
        }
      });
    }

    private void fallbackPauseWithNonEvaluatableContext() {
      getVirtualMachineProxy().suspend();
      SuspendContextImpl suspendContext = mySuspendManager.pushSuspendContext(EventRequest.SUSPEND_ALL, 0);
      setSuspendContextAndCheckConsistency(suspendContext);
      forEachSafe(myDebugProcessListeners, it -> it.paused(suspendContext));
      XDebuggerManagerImpl.getNotificationGroup()
        .createNotification(JavaDebuggerBundle.message("evaluation.warning.cannot.evaluate.on.pause"), MessageType.WARNING)
        .notify(getProject());
    }
  }

  private class ResumeThreadCommand extends SuspendContextCommandImpl {
    private final ThreadReferenceProxyImpl myThread;

    ResumeThreadCommand(SuspendContextImpl suspendContext, @NotNull ThreadReferenceProxyImpl thread) {
      super(suspendContext);
      myThread = thread;
    }

    @Override
    public void contextAction(@NotNull SuspendContextImpl context) {
      // handle unfreeze through the regular context resume
      //noinspection PointlessBooleanExpression
      if (false && getSuspendManager().isFrozen(myThread)) {
        getSuspendManager().unfreezeThread(myThread);
        return;
      }

      final Set<SuspendContextImpl> suspendingContexts = SuspendManagerUtil.getSuspendingContexts(mySuspendManager, myThread);
      for (SuspendContextImpl suspendContext : suspendingContexts) {
        if (suspendContext.getSuspendPolicy() == EventRequest.SUSPEND_EVENT_THREAD && suspendContext.getEventThread() == myThread) {
          getSession().getXDebugSession().sessionResumed();
          getCommandManagerThread().invokeNow(createResumeCommand(suspendContext));
        }
        else {
          DebuggerManagerEx.getInstanceEx(project).getBreakpointManager().removeThreadFilter(context.getDebugProcess());
          mySuspendManager.resumeThread(suspendContext, myThread);
          mySuspendManager.myExplicitlyResumedThreads.add(myThread);
        }
      }
    }
  }

  private class FreezeThreadCommand extends DebuggerCommandImpl {
    private final ThreadReferenceProxyImpl myThread;

    FreezeThreadCommand(ThreadReferenceProxyImpl thread) {
      myThread = thread;
    }

    @Override
    protected void action() {
      Set<SuspendContextImpl> suspendingContexts = SuspendManagerUtil.getSuspendingContexts(mySuspendManager, myThread);
      if (!suspendingContexts.isEmpty()) {
        logError("Trying to freeze already suspended thread " + myThread + " : " + suspendingContexts);
      }

      if (myThread.isEvaluating()) {
        throw new IllegalStateException("Trying to freeze evaluating thread " + myThread);
      }

      List<SuspendContextImpl> pausedSuspendAllContexts =
        ContainerUtil.filter(mySuspendManager.getPausedContexts(), c -> c.getSuspendPolicy() == EventRequest.SUSPEND_ALL);

      if (!pausedSuspendAllContexts.isEmpty()) {
        if (pausedSuspendAllContexts.size() > 1) {
          logError("A lot of paused suspend-all contexts: " + pausedSuspendAllContexts);
        }
        for (SuspendContextImpl suspendAllContext : pausedSuspendAllContexts) {
          if (!suspendAllContext.suspends(myThread)) {
            mySuspendManager.suspendThread(suspendAllContext, myThread);
          }
        }
        mySuspendManager.myExplicitlyResumedThreads.remove(myThread);
        SuspendManagerUtil.switchToThreadInSuspendAllContext(pausedSuspendAllContexts.get(0), myThread);
        return;
      }

      if (!mySuspendManager.isFrozen(myThread)) {
        mySuspendManager.freezeThread(myThread);
        SuspendContextImpl suspendContext = mySuspendManager.pushSuspendContext(EventRequest.SUSPEND_EVENT_THREAD, 0);
        suspendContext.setThread(myThread.getThreadReference());
        forEachSafe(myDebugProcessListeners, it -> it.paused(suspendContext));
      }
    }
  }

  private class PopFrameCommand extends DebuggerContextCommandImpl {
    private final StackFrameProxyImpl myStackFrame;

    PopFrameCommand(DebuggerContextImpl context, StackFrameProxyImpl frameProxy) {
      super(context, frameProxy.threadProxy());
      myStackFrame = frameProxy;
    }

    @Override
    public void threadAction(@NotNull SuspendContextImpl suspendContext) {
      final ThreadReferenceProxyImpl thread = myStackFrame.threadProxy();
      try {
        if (!getSuspendManager().isSuspended(thread)) {
          notifyCancelled();
          return;
        }
      }
      catch (ObjectCollectedException ignored) {
        notifyCancelled();
        return;
      }

      if (!suspendContext.suspends(thread)) {
        suspendContext.postponeCommand(this);
        return;
      }

      if (myStackFrame.isBottom()) {
        DebuggerInvocationUtil.invokeLaterAnyModality(project, () -> Messages.showMessageDialog(project, JavaDebuggerBundle
          .message("error.pop.bottom.stackframe"), XDebuggerBundle.message("xdebugger.reset.frame.title"), Messages.getErrorIcon()));
        return;
      }

      try {
        thread.popFrames(myStackFrame);
        getSuspendManager().popFrame(suspendContext);
      }
      catch (NativeMethodException e) {
        showError(JavaDebuggerBundle.message("error.native.method.exception"));
        LOG.info(e);
      }
      catch (EvaluateException e) {
        showError(JavaDebuggerBundle.message("error.pop.stackframe", e.getLocalizedMessage()));
        LOG.info(e);
      }
    }

    private void showError(@NlsContexts.DialogMessage String message) {
      DebuggerInvocationUtil.invokeLaterAnyModality(project, () ->
        Messages.showMessageDialog(project, message,
                                   XDebuggerBundle.message("xdebugger.reset.frame.title"), Messages.getErrorIcon()));
    }
  }

  @Override
  public @NotNull GlobalSearchScope getSearchScope() {
    LOG.assertTrue(mySession != null, "Accessing debug session before its initialization");
    return mySession.getSearchScope();
  }

  public void reattach(final DebugEnvironment environment) {
    reattach(environment, false, () -> {});
  }

  public void reattach(final DebugEnvironment environment, boolean keepCurrentVM, Runnable vmReadyCallback) {
    reattach(environment, () -> {
      if (keepCurrentVM) {
        detachProcess(false, true, vmData -> {
          myStashedVirtualMachines.addFirst(vmData);
          myDebuggerManagerThread = createManagerThread();
        });
      }
      else {
        closeCurrentProcess(false);
      }
    }, vmReadyCallback);
  }

  private boolean unstashAndReattach() {
    VirtualMachineData vmData = myStashedVirtualMachines.pollFirst();
    if (vmData != null && vmData.vm != null) {
      myDebuggerManagerThread = vmData.debuggerManagerThread;
      reattach(vmData.connection, () -> {}, () -> {
        afterProcessStarted(() -> vmData.debuggerManagerThread.schedule(new DebuggerCommandImpl() {
          @Override
          protected void action() {
            try {
              commitVM(vmData.vm.getVirtualMachine());
            }
            catch (VMDisconnectedException e) {
              fail();
            }
          }
        }));
      });
      return true;
    }
    return false;
  }

  private void reattach(DebugEnvironment environment, Runnable detachVm, Runnable vmReadyCallback) {
    reattach(environment.getRemoteConnection(), detachVm, () -> {
      createVirtualMachine(environment);
      vmReadyCallback.run();
    });
  }

  private void reattach(RemoteConnection connection, Runnable detachVm, Runnable attachVm) {
    if (!myIsStopped.get()) {
      getManagerThread().schedule(new DebuggerCommandImpl() {
        @Override
        protected void action() {
          detachVm.run();
          getCommandManagerThread().processRemaining();
          doReattach();
        }

        @Override
        protected void commandCancelled() {
          doReattach(); // if the original process is already finished
        }

        private void doReattach() {
          DebuggerInvocationUtil.invokeLaterAnyModality(project, () -> {
            ((XDebugSessionImpl)getXdebugProcess().getSession()).reset();
            myState.set(State.INITIAL);
            myConnection = connection;
            getManagerThread().restartIfNeeded();
            attachVm.run();
          });
        }
      });
    }
  }

  public @Nullable ExecutionResult attachVirtualMachine(@NotNull DebugEnvironment environment,
                                                        @NotNull DebuggerSession session) throws ExecutionException {
    mySession = session;
    myWaitFor.down();

    LOG.assertTrue(isInInitialState());

    myConnection = environment.getRemoteConnection();

    // in client mode start target process before the debugger to reduce polling
    if (!(myConnection instanceof RemoteConnectionStub) &&
        !(myConnection instanceof DelayedRemoteConnection) &&
        myConnection.isServerMode()) {
      createVirtualMachine(environment);
      if (myIsFailed.get()) {
        myExecutionResult = null;
        return null;
      }
    }

    ExecutionResult executionResult;
    try {
      synchronized (myProcessListeners) {
        executionResult = environment.createExecutionResult();
        myExecutionResult = executionResult;
        if (executionResult == null) {
          fail();
          return null;
        }
        for (ProcessListener processListener : myProcessListeners) {
          executionResult.getProcessHandler().addProcessListener(processListener);
        }
        myProcessListeners.clear();
        if (!myTextBeforeStart.isEmpty()) {
          printToConsoleImpl(myTextBeforeStart.toString());
          myTextBeforeStart.setLength(0);
        }
      }
    }
    catch (ExecutionException e) {
      fail();
      throw e;
    }

    if (myConnection instanceof DelayedRemoteConnection) {
      ((DelayedRemoteConnection)myConnection).setAttachRunnable(() -> createVirtualMachine(environment));
    }
    else if (!(myConnection instanceof RemoteConnectionStub) && !myConnection.isServerMode()) {
      createVirtualMachine(environment);
      if (myIsFailed.get()) {
        myExecutionResult = null;
        return null;
      }
    }

    return executionResult;
  }

  private void fail() {
    // need this in order to prevent calling stop() twice
    if (myIsFailed.compareAndSet(false, true)) {
      stop(false);
    }
  }

  private void createVirtualMachine(final DebugEnvironment environment) {
    final String sessionName = environment.getSessionName();
    final long pollTimeout = environment.getPollTimeout();
    final Semaphore semaphore = new Semaphore();
    semaphore.down();

    final AtomicBoolean connectorIsReady = new AtomicBoolean(false);
    addDebugProcessListener(new DebugProcessListener() {
      @Override
      public void connectorIsReady() {
        connectorIsReady.set(true);
        semaphore.up();
        removeDebugProcessListener(this);
      }
    }, disposable);

    getManagerThread().schedule(new DebuggerCommandImpl(PrioritizedTask.Priority.HIGH) {
      @Override
      protected void action() {
        VirtualMachine vm = null;

        try {
          final long time = System.currentTimeMillis();
          do {
            try {
              vm = createVirtualMachineInt();
              break;
            }
            catch (final ExecutionException e) {
              if (pollTimeout > 0 && !myConnection.isServerMode() && e.getCause() instanceof IOException) {
                synchronized (this) {
                  try {
                    wait(500);
                  }
                  catch (InterruptedException ignored) {
                    break;
                  }
                }
              }
              else {
                ProcessHandler processHandler = getProcessHandler();
                boolean terminated =
                  processHandler != null && (processHandler.isProcessTerminating() || processHandler.isProcessTerminated());

                try {
                  forEachSafe(myDebugProcessListeners, it -> it.attachException(null, e, myConnection));
                }
                catch (Exception ex) {
                  LOG.debug(ex);
                }
                fail();
                DebuggerInvocationUtil.invokeLaterAnyModality(() -> {
                  // propagate exception only in case we succeeded to obtain execution result,
                  // otherwise if the error is induced by the fact that there is nothing to debug, and there is no need to show
                  // this problem to the user
                  if (((myExecutionResult != null && !terminated) || !connectorIsReady.get()) &&
                      !ApplicationManager.getApplication().isHeadlessEnvironment()) {
                    ExecutionUtil.handleExecutionError(project, ToolWindowId.DEBUG, sessionName, e);
                  }
                });
                break;
              }
            }
          }
          while (System.currentTimeMillis() - time < pollTimeout);
        }
        finally {
          semaphore.up();
        }

        if (vm != null) {
          final VirtualMachine vm1 = vm;
          afterProcessStarted(() -> getCommandManagerThread().schedule(new DebuggerCommandImpl(PrioritizedTask.Priority.HIGH) {
            @Override
            protected void action() {
              try {
                commitVM(vm1);
              }
              catch (VMDisconnectedException e) {
                fail();
              }
            }
          }));
        }
        else {
          fail();
        }
      }

      @Override
      protected void commandCancelled() {
        try {
          super.commandCancelled();
        }
        finally {
          semaphore.up();
        }
      }
    });

    semaphore.waitFor();
  }

  private void afterProcessStarted(final Runnable run) {
    class MyProcessAdapter implements ProcessListener {
      private boolean alreadyRun = false;

      public synchronized void run() {
        if (!alreadyRun) {
          alreadyRun = true;
          run.run();
        }
        removeProcessListener(this);
      }

      @Override
      public void startNotified(@NotNull ProcessEvent event) {
        run();
      }
    }
    MyProcessAdapter processListener = new MyProcessAdapter();
    addProcessListener(processListener);
    if (myExecutionResult != null) {
      if (myExecutionResult.getProcessHandler().isStartNotified()) {
        processListener.run();
      }
    }
  }

  public @NotNull DebuggerCommandImpl createPauseCommand(@Nullable ThreadReferenceProxyImpl threadProxy) {
    return new PauseCommand(threadProxy);
  }

  public @NotNull ResumeCommand createResumeCommand(SuspendContextImpl suspendContext) {
    return createResumeCommand(suspendContext, PrioritizedTask.Priority.HIGH);
  }

  public @NotNull ResumeCommand createResumeCommand(SuspendContextImpl suspendContext, final PrioritizedTask.Priority priority) {
    final BreakpointManager breakpointManager = DebuggerManagerEx.getInstanceEx(getProject()).getBreakpointManager();
    return new ResumeCommand(suspendContext) {
      @Override
      public void contextAction(@NotNull SuspendContextImpl suspendContext) {
        breakpointManager.removeThreadFilter(DebugProcessImpl.this); // clear the filter on resume
        if (myReturnValueWatcher != null) {
          myReturnValueWatcher.clear();
        }
        super.contextAction(suspendContext);
      }

      @Override
      public Priority getPriority() {
        return priority;
      }
    };
  }

  public @NotNull ResumeCommand createStepOverCommand(SuspendContextImpl suspendContext, boolean ignoreBreakpoints) {
    return createStepOverCommand(suspendContext, ignoreBreakpoints, StepRequest.STEP_LINE);
  }

  public @NotNull ResumeCommand createStepOverCommand(SuspendContextImpl suspendContext, boolean ignoreBreakpoints, int stepSize) {
    return createStepOverCommand(suspendContext, ignoreBreakpoints, null, StepRequest.STEP_LINE);
  }

  public @NotNull ResumeCommand createStepOverCommand(SuspendContextImpl suspendContext,
                                                      boolean ignoreBreakpoints,
                                                      @Nullable MethodFilter methodFilter,
                                                      int stepSize) {
    return new StepOverCommand(suspendContext, ignoreBreakpoints, methodFilter, stepSize);
  }

  public @NotNull ResumeCommand createStepOutCommand(SuspendContextImpl suspendContext) {
    return createStepOutCommand(suspendContext, StepRequest.STEP_LINE);
  }

  public @NotNull ResumeCommand createStepOutCommand(SuspendContextImpl suspendContext, int stepSize) {
    return new StepOutCommand(suspendContext, stepSize);
  }

  public @NotNull ResumeCommand createStepIntoCommand(SuspendContextImpl suspendContext, boolean ignoreFilters, final MethodFilter smartStepFilter) {
    return createStepIntoCommand(suspendContext, ignoreFilters, smartStepFilter, StepRequest.STEP_LINE);
  }

  public @NotNull ResumeCommand createStepIntoCommand(SuspendContextImpl suspendContext, boolean ignoreFilters, final MethodFilter smartStepFilter,
                                                      int stepSize) {
    return new StepIntoCommand(suspendContext, ignoreFilters, smartStepFilter, stepSize);
  }

  public @NotNull ResumeCommand createRunToCursorCommand(SuspendContextImpl suspendContext,
                                                         @NotNull XSourcePosition position,
                                                         boolean ignoreBreakpoints)
    throws EvaluateException {
    RunToCursorCommand runToCursorCommand = new RunToCursorCommand(suspendContext, position, ignoreBreakpoints);
    checkRunToCursorIsOk(position, runToCursorCommand);
    return runToCursorCommand;
  }

  private void checkRunToCursorIsOk(@NotNull XSourcePosition position, RunToCursorCommand runToCursorCommand) throws EvaluateException {
    if (runToCursorCommand.myRunToCursorBreakpoint == null) {
      PsiFile psiFile = PsiManager.getInstance(project).findFile(position.getFile());
      throw new EvaluateException(
        JavaDebuggerBundle.message("error.running.to.cursor.no.executable.code", psiFile != null ? psiFile.getName() : "<No File>",
                                   position.getLine()), null);
    }
  }

  public @NotNull DebuggerCommandImpl createFreezeThreadCommand(ThreadReferenceProxyImpl thread) {
    return new FreezeThreadCommand(thread);
  }

  public @NotNull SuspendContextCommandImpl createResumeThreadCommand(SuspendContextImpl suspendContext, @NotNull ThreadReferenceProxyImpl thread) {
    return new ResumeThreadCommand(suspendContext, thread);
  }

  public @NotNull SuspendContextCommandImpl createPopFrameCommand(DebuggerContextImpl context, StackFrameProxyImpl stackFrame) {
    return new PopFrameCommand(context, stackFrame);
  }

  //public void setBreakpointsMuted(final boolean muted) {
  //  XDebugSession session = mySession.getXDebugSession();
  //  if (isAttached()) {
  //    getManagerThread().schedule(new DebuggerCommandImpl() {
  //      @Override
  //      protected void action() {
  //        // set the flag before enabling/disabling cause it affects if breakpoints will create requests
  //        if (myBreakpointsMuted.getAndSet(muted) != muted) {
  //          final BreakpointManager breakpointManager = DebuggerManagerEx.getInstanceEx(myProject).getBreakpointManager();
  //          if (muted) {
  //            breakpointManager.disableBreakpoints(DebugProcessImpl.this);
  //          }
  //          else {
  //            breakpointManager.enableBreakpoints(DebugProcessImpl.this);
  //          }
  //        }
  //      }
  //    });
  //  }
  //  else {
  //    session.setBreakpointMuted(muted);
  //  }
  //}

  public @NotNull DebuggerContextImpl getDebuggerContext() {
    return mySession.getContextManager().getContext();
  }

  public void setXDebugProcess(JavaDebugProcess XDebugProcess) {
    myXDebugProcess = XDebugProcess;
  }

  public @Nullable JavaDebugProcess getXdebugProcess() {
    return myXDebugProcess;
  }

  public boolean areBreakpointsMuted() {
    XDebugSession session = mySession.getXDebugSession();
    return session != null && session.areBreakpointsMuted();
  }

  public DebuggerSession getSession() {
    return mySession;
  }

  static boolean isResumeOnlyCurrentThread() {
    return DebuggerSettings.getInstance().RESUME_ONLY_CURRENT_THREAD;
  }

  public boolean isEvaluationPossible() {
    return getSuspendManager().getPausedContext() != null
           || DebuggerImplicitEvaluationContextUtil.getImplicitEvaluationThread(this) != null;
  }

  public static boolean isInSuspendCommand(SuspendContextImpl suspendContext) {
    DebuggerManagerThreadImpl.assertIsManagerThread();
    DebuggerCommandImpl command = DebuggerManagerThreadImpl.getCurrentCommand();
    return command instanceof SuspendContextCommandImpl suspendContextCommand
           && suspendContextCommand.getSuspendContext() == suspendContext;
  }

  public boolean isEvaluationPossible(SuspendContextImpl suspendContext) {
    return mySuspendManager.hasPausedContext(suspendContext);
  }

  public void startWatchingMethodReturn(ThreadReferenceProxyImpl thread) {
    if (myReturnValueWatcher != null) {
      myReturnValueWatcher.enable(thread.getThreadReference());
    }
  }

  void stopWatchingMethodReturn() {
    if (myReturnValueWatcher != null) {
      myReturnValueWatcher.disable();
    }
  }

  protected void notifyStoppedOtherThreads() {
    myOtherThreadsReachBreakpointNumber++;
    if (otherThreadsJob != null) {
      otherThreadsJob.cancel(null);
    }
    otherThreadsJob = EdtScheduler.getInstance().schedule(300, ModalityState.defaultModalityState(), () -> {
      showNotification(myOtherThreadsReachBreakpointNumber);
      myOtherThreadsReachBreakpointNumber = 0;
    });
  }

  private void showNotification(int number) {
    if (disposable.isDisposed()) {
      return;
    }
    String content = JavaDebuggerBundle.message("message.other.threads.reached.breakpoints", number);
    MessageType messageType = MessageType.INFO;

    if (mySession.getXDebugSession() instanceof XDebugSessionImpl session) {
      XDebugSessionTab tab = session.getSessionTab();
      if (tab != null) {
        XFramesView view = tab.getFramesView();
        if (view != null) {
          ComboBox<XExecutionStack> comboBox = view.getThreadComboBox();
          BasicArrowButton arrowButton = UIUtil.findComponentOfType(comboBox, BasicArrowButton.class);

          JComponent target = arrowButton != null ? arrowButton : comboBox;

          BalloonBuilder balloonBuilder = JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(content, messageType, null)
            .setHideOnClickOutside(true)
            .setDisposable(disposable)
            .setHideOnFrameResize(false);
          Balloon balloon = balloonBuilder.createBalloon();
          balloon.show(new AnchoredPoint(AnchoredPoint.Anchor.TOP, target), Balloon.Position.above);
          return;
        }
      }
    }

    // Fallback to the whole toolwindow notification
    XDebuggerManagerImpl.getNotificationGroup()
      .createNotification(content, messageType)
      .notify(getProject());
  }

  String getStateForDiagnostics() {
    String safeInfo = "myState = " + myState + "\n" +
                      "myStashedVirtualMachines = " + myStashedVirtualMachines + "\n" +
                      "myExecutionResult = " + myExecutionResult + "\n";
    if (DebuggerDiagnosticsUtil.needAnonymizedReports()) {
      return safeInfo;
    }
    return "myProject = " + project + "\n" +
           safeInfo +
           "myConnection = " + myConnection + "\n" +
           "myArguments = " + myArguments + "\n";
  }

  private record VirtualMachineData(VirtualMachineProxyImpl vm, RemoteConnection connection,
                                    DebuggerManagerThreadImpl debuggerManagerThread) {
  }

  public void logError(@NotNull String message, @NotNull Attachment attachment) {
    LOG.error(message, DebuggerDiagnosticsUtil.getAttachments(this, attachment));
  }

  public void logError(@NotNull String message) {
    LOG.error(message, DebuggerDiagnosticsUtil.getAttachments(this));
  }

  public void logError(@NotNull String message, @NotNull Throwable e) {
    LOG.error(message, e, DebuggerDiagnosticsUtil.getAttachments(this));
  }

  @ApiStatus.Internal
  @TestOnly
  public int getMethodInvocationsCount() {
    return myMethodInvocations.get();
  }
}
