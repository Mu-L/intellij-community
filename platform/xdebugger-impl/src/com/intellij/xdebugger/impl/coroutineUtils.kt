// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.xdebugger.impl

import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.impl.frame.XDebugSessionProxy
import com.intellij.xdebugger.impl.rpc.XDebugSessionId
import fleet.kernel.withEntities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.jetbrains.annotations.ApiStatus

// Used only for Java code, since MutableStateFlow function cannot be called there.
internal fun <T> createMutableStateFlow(initialValue: T): MutableStateFlow<T> {
  return MutableStateFlow(initialValue)
}

@ApiStatus.Internal
suspend fun XDebugSessionImpl.id(): XDebugSessionId {
  val entity = entity.await()
  return withEntities(entity) {
    entity.sessionId
  }
}


// Used only for Java code, since combine cannot be called there
internal fun createSessionSuspendedFlow(
  cs: CoroutineScope,
  pausedFlow: StateFlow<Boolean>,
  suspendContextFlow: StateFlow<XSuspendContext?>,
): StateFlow<Boolean> {
  return combine(pausedFlow, suspendContextFlow) { paused, suspendContext ->
    paused && suspendContext != null
  }.stateIn(cs, SharingStarted.Eagerly, pausedFlow.value && suspendContextFlow.value != null)
}

internal fun addOnSessionSelectedListener(session: XDebugSessionProxy, action: () -> Unit) {
  val scope = session.coroutineScope
  val sessionId = scope.async {
    session.sessionId()
  }
  session.project.messageBus.connect(scope).subscribe(FrontendXDebuggerManagerListener.TOPIC, object : FrontendXDebuggerManagerListener {
    override fun activeSessionChanged(previousSessionId: XDebugSessionId?, currentSessionId: XDebugSessionId?) {
      scope.launch {
        if (currentSessionId == sessionId.await()) {
          action()
        }
      }
    }
  })
}
