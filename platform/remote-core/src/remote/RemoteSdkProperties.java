// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.remote;

import com.intellij.remote.ext.CredentialsManager;
import com.intellij.util.PathMappingSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface RemoteSdkProperties extends RemoteSdkPropertiesPaths {
  void setInterpreterPath(String interpreterPath);

  void setHelpersPath(String helpersPath);

  String getDefaultHelpersName();

  @NotNull PathMappingSettings getPathMappings();

  void setPathMappings(@Nullable PathMappingSettings pathMappings);

  boolean isHelpersVersionChecked();

  void setHelpersVersionChecked(boolean helpersVersionChecked);

  void setSdkId(String sdkId);

  String getSdkId();

  boolean isValid();

  void setValid(boolean valid);

  boolean isRunAsRootViaSudo();

  void setRunAsRootViaSudo(boolean runAsRootViaSudo);

  /**
   * Composes a descriptive string out of connection credentials and remote SDK properties.
   */
  static @NotNull String getFullInterpreterPath(@NotNull RemoteCredentials credentials, @NotNull RemoteSdkProperties properties) {
    var builder = new StringBuilder();
    if (properties.isRunAsRootViaSudo()) {
      builder.append("sudo+");
    }
    builder.append(RemoteCredentialsHolder.getCredentialsString(credentials));
    builder.append(properties.getInterpreterPath());
    return builder.toString();
  }

  /**
   * Extracts an interpreter path from the full path generated by {@link #getFullInterpreterPath(RemoteCredentials, RemoteSdkProperties)}.
   * Returns {@code fullPath} as a fallback.
   * <p/>
   * Assumes a host cannot contain a colon character.
   */
  static @NotNull String getInterpreterPathFromFullPath(@NotNull String fullPath) {
    if (fullPath.startsWith(RemoteCredentialsHolder.SSH_PREFIX)) {
      fullPath = fullPath.substring(RemoteCredentialsHolder.SSH_PREFIX.length());
      int index = fullPath.indexOf(":");
      if (index != -1) {
        fullPath = fullPath.substring(index + 1); // it is like 8080/home/user or 8080C:\Windows
        index = 0;
        while (index < fullPath.length() && Character.isDigit(fullPath.charAt(index))) {
          index++;
        }
        if (index < fullPath.length()) {
          return fullPath.substring(index);
        }
      }
    }

    return fullPath;
  }

  static boolean isRemoteSdk(@Nullable String path) {
    if (path != null) {
      for (var type : CredentialsManager.getInstance().getAllTypes()) {
        if (type.hasPrefix(path)) {
          return true;
        }
      }
    }
    return false;
  }
}
