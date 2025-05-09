// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.psi.stubs;

import com.intellij.openapi.util.Key;
import com.intellij.util.ArrayUtil;
import com.intellij.util.containers.CollectionFactory;
import com.intellij.util.containers.HashingStrategy;
import org.jetbrains.annotations.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Dmitry Avdeev
 */
public class ObjectStubTree<T extends Stub> {
  private static final Key<ObjectStubTree<?>> STUB_TO_TREE_REFERENCE = Key.create("stub to tree reference");
  protected final ObjectStubBase<?> myRoot;
  private String myDebugInfo;
  private boolean myHasBackReference;
  private final List<T> myPlainList;

  public ObjectStubTree(@NotNull ObjectStubBase<?> root, boolean withBackReference) {
    myRoot = root;
    myPlainList = enumerateStubs(root);
    if (withBackReference) {
      // this will prevent soft references to a stub tree to be collected before all the stubs are collected
      myRoot.putUserData(STUB_TO_TREE_REFERENCE, this);
    }
  }

  public @NotNull Stub getRoot() {
    return myRoot;
  }

  public @Unmodifiable @NotNull List<T> getPlainList() {
    return myPlainList;
  }

  @NotNull
  @Unmodifiable
  List<T> getPlainListFromAllRoots() {
    return getPlainList();
  }

  @ApiStatus.Internal
  public @NotNull Map<StubIndexKey<?, ?>, Map<Object, int[]>> indexStubTree(
    @Nullable HashingStrategyProvider keyHashingStrategyProvider
  ) {
    StubIndexSink sink = new StubIndexSink(keyHashingStrategyProvider);
    List<T> plainList = getPlainListFromAllRoots();
    for (int i = 0, plainListSize = plainList.size(); i < plainListSize; i++) {
      Stub stub = plainList.get(i);
      sink.myStubIdx = i;
      StubSerializationUtil.getSerializer(stub).indexStub(stub, sink);
    }

    return sink.getResult();
  }

  protected @Unmodifiable @NotNull List<T> enumerateStubs(@NotNull Stub root) {
    List<T> result = new ArrayList<>();
    //noinspection rawtypes,unchecked
    enumerateStubsInto(root, (List)result);
    return result;
  }

  private static void enumerateStubsInto(@NotNull Stub root, @NotNull List<? super Stub> result) {
    ((ObjectStubBase<?>)root).id = result.size();
    result.add(root);
    List<? extends Stub> childrenStubs = root.getChildrenStubs();
    for (int i = 0; i < childrenStubs.size(); i++) {
      Stub child = childrenStubs.get(i);
      enumerateStubsInto(child, result);
    }
  }

  public void setDebugInfo(@NotNull @NonNls String info) {
    ObjectStubTree<?> ref = getStubTree(myRoot);
    if (ref != null) {
      assert ref == this;
    }
    myHasBackReference = ref != null;
    myDebugInfo = info;
  }

  public static @Nullable ObjectStubTree<?> getStubTree(@NotNull ObjectStubBase<?> root) {
    return root.getUserData(STUB_TO_TREE_REFERENCE);
  }

  public @NonNls String getDebugInfo() {
    return myHasBackReference ? myDebugInfo + "; with backReference" : myDebugInfo;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{myDebugInfo='" + getDebugInfo() + '\'' + ", myRoot=" + myRoot + '}' + hashCode();
  }

  private static final class StubIndexSink implements IndexSink {
    private final Map<StubIndexKey<?, ?>, Map<Object, int[]>> myResult = new HashMap<>();
    private final @Nullable HashingStrategyProvider myHashingStrategyFunction;
    private int myStubIdx;

    private StubIndexSink(@Nullable HashingStrategyProvider hashingStrategyFunction) {
      myHashingStrategyFunction = hashingStrategyFunction;
    }

    @Override
    public void occurrence(@NotNull StubIndexKey indexKey, @NotNull Object value) {
      Map<Object, int[]> map = myResult.get(indexKey);
      if (map == null) {
        map = myHashingStrategyFunction == null
              ? new HashMap<>()
              : CollectionFactory.createCustomHashingStrategyMap(myHashingStrategyFunction.getStrategy(indexKey));

        myResult.put(indexKey, map);
      }

      int[] list = map.get(value);
      if (list == null) {
        map.put(value, new int[] {myStubIdx});
      }
      else {
        int lastNonZero = ArrayUtil.lastIndexOfNot(list, 0);
        if (lastNonZero >= 0 && list[lastNonZero] == myStubIdx) {
          // the second and later occurrence calls for the same value are no op
          return;
        }
        int firstZero = lastNonZero + 1;

        if (firstZero == list.length) {
          list = ArrayUtil.realloc(list, Math.max(4, list.length << 1));
          map.put(value, list);
        }
        list[firstZero] = myStubIdx;
      }
    }

    public @NotNull Map<StubIndexKey<?, ?>, Map<Object, int[]>> getResult() {
      for (Map<Object, int[]> map : myResult.values()) {
        for (Map.Entry<Object, int[]> entry : map.entrySet()) {
          int[] ints = entry.getValue();
          if (ints.length == 1) {
            continue;
          }

          int firstZero = ArrayUtil.indexOf(ints, 0);
          if (firstZero != -1) {
            map.put(entry.getKey(), ArrayUtil.realloc(ints, firstZero));
          }
        }
      }
      return myResult;
    }
  }

  @ApiStatus.Internal
  @FunctionalInterface
  public interface HashingStrategyProvider {
    HashingStrategy<Object> getStrategy(StubIndexKey<?, ?> key);
  }
}
