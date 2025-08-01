// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.gradle.toolingExtension.util;

import org.gradle.util.GradleVersion;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@ApiStatus.Internal
public final class GradleReflectionUtil {

  public static boolean hasMethod(@NotNull Object target, @NotNull String methodName, Class<?>... parameterTypes) {
    try {
      target.getClass().getMethod(methodName, parameterTypes);
      return true;
    }
    catch (NoSuchMethodException | SecurityException e) {
      return false;
    }
  }

  public static @NotNull Method getMethod(
    @NotNull Class<?> receiverParameterType,
    @NotNull String methodName,
    @NotNull Class<?> @NotNull ... parameterTypes
  ) {
    try {
      return receiverParameterType.getMethod(methodName, parameterTypes);
    }
    catch (NoSuchMethodException | SecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public static Object invokeMethod(
    @NotNull Method method,
    Object receiverArgument,
    Object... arguments
  ) {
    try {
      return method.invoke(receiverArgument, arguments);
    }
    catch (IllegalAccessException | InvocationTargetException e) {
      throw new IllegalStateException(e);
    }
  }

  public static <T> T getValue(
    @NotNull Object receiver,
    @NotNull String getterName,
    @NotNull Class<T> valueClass
  ) {
    Method method = getMethod(receiver.getClass(), getterName);
    Object value = invokeMethod(method, receiver);
    try {
      return valueClass.cast(value);
    }
    catch (ClassCastException e) {
      throw new IllegalStateException(e);
    }
  }

  public static <T> T getPrivateValue(
    @NotNull Object receiver,
    @NotNull String getterName,
    @NotNull Class<T> valueClass
  ) {
    Class<?> receiverClass = receiver.getClass();
    Method method;
    try {
      method = receiverClass.getDeclaredMethod(getterName);
    }
    catch (NoSuchMethodException e) {
      throw new IllegalStateException(String.format("The %s method are not found in %s", getterName, receiverClass.getCanonicalName()));
    }
    method.setAccessible(true);
    Object value = invokeMethod(method, receiver);
    try {
      return valueClass.cast(value);
    }
    catch (ClassCastException e) {
      throw new IllegalStateException(e);
    }
  }

  public static <T> void setValue(
    @NotNull Object receiver,
    @NotNull String setterName,
    @NotNull Class<T> valueClass,
    T value
  ) {
    Method method = getMethod(receiver.getClass(), setterName, valueClass);
    invokeMethod(method, receiver, value);
  }

  public static <T, V> T invokeMethod(
    @NotNull Object receiver,
    @NotNull String methodName,
    @NotNull V argument,
    @NotNull Class<V> argumentClass,
    @NotNull Class<T> resultClass
  ) {
    Method method = getMethod(receiver.getClass(), methodName, argumentClass);
    return resultClass.cast(invokeMethod(method, receiver, argument));
  }

  /**
   * @deprecated use {@link GradleReflectionUtil#getValue} instead.
   */
  @Deprecated
  @SuppressWarnings("DeprecatedIsStillUsed")
  public static <T> T reflectiveCall(@NotNull Object target, @NotNull String methodName, @NotNull Class<T> aClass) {
    try {
      Method method = target.getClass().getMethod(methodName);
      Object value = method.invoke(target);
      return aClass.cast(value);
    }
    catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static boolean isInstance(@NotNull Object object, String className) {
    Class<?> clazz = findClassForName(className);
    return clazz != null && clazz.isInstance(object);
  }

  public static @Nullable Class<?> findClassForName(@NotNull String className) {
    try {
      return Class.forName(className);
    }
    catch (ClassNotFoundException __) {
      return null;
    }
  }

  public static @Nullable Class<?> loadClassOrNull(@NotNull ClassLoader classLoader, @NotNull String className) {
    try {
      return classLoader.loadClass(className);
    }
    catch (ClassNotFoundException __) {
      return null;
    }
  }

  public static @NotNull Class<?> getGradleClass(@NotNull String requiredClassName) {
    Class<?> requiredClass = findClassForName(requiredClassName);
    if (requiredClass == null) {
      throw new IllegalStateException(
        String.format(
          "Class %s not found in the classpath of the Gradle daemon %s but it should be there!",
          requiredClass, GradleVersion.current().getBaseVersion()
        )
      );
    }
    return requiredClass;
  }
}
