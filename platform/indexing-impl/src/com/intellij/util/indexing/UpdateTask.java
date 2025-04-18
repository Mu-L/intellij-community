// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util.indexing;

import com.intellij.concurrency.ConcurrentCollectionFactory;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.Semaphore;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collection;
import java.util.Set;

@ApiStatus.Internal
public abstract class UpdateTask<Type> {
  private final Semaphore myUpdateSemaphore = new Semaphore();
  private final Set<Type> myItemsBeingIndexed = ConcurrentCollectionFactory.createConcurrentSet();
  private static final boolean DEBUG = false;

  public final boolean processAll(Collection<? extends Type> itemsToProcess, Project project) {
    if (DEBUG) trace("enter processAll");
    try {
      boolean hasMoreToProcess;
      boolean allItemsProcessed = true;

      do {
        hasMoreToProcess = false;
        if (DEBUG) trace("observing " + itemsToProcess.size());
        // todo we can decrease itemsToProcess
        for (Type item : itemsToProcess) {
          myUpdateSemaphore.down();

          try {
            if (DEBUG) trace("about to process");
            boolean processed = process(item, project);
            if (DEBUG) trace(processed ? "processed " : "skipped");

            if (!processed) {
              hasMoreToProcess = true;
              allItemsProcessed = false;
            }
          }
          finally {
            myUpdateSemaphore.up();
          }
          ProgressManager.checkCanceled();
        }

        do {
          ProgressManager.checkCanceled();
        }
        while (!myUpdateSemaphore.waitFor(100));
        if (DEBUG) if (hasMoreToProcess) trace("reiterating");
      }
      while (hasMoreToProcess);

      return allItemsProcessed;
    } finally {
      if (DEBUG) trace("exits processAll");
    }
  }

  private boolean process(Type item, Project project) {
    if (myItemsBeingIndexed.add(item)) {
      try {
        doProcess(item, project);
        return true;
      } finally {
        myItemsBeingIndexed.remove(item);
      }
    }
    return false;
  }

  @ApiStatus.Internal
  protected abstract void doProcess(Type item, Project project);

  protected static void trace(String s) {
    System.out.println(Thread.currentThread() + " " + s);
  }
}