// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInspection.dataFlow;

import com.intellij.codeInspection.dataFlow.jvm.SpecialField;
import com.intellij.codeInspection.util.InspectionMessage;
import com.intellij.java.analysis.JavaAnalysisBundle;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.PsiFieldImpl;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.ObjectUtils;
import com.siyeh.ig.psiutils.ClassUtils;
import com.siyeh.ig.psiutils.ExpressionUtils;
import com.siyeh.ig.psiutils.MethodCallUtils;
import one.util.streamex.IntStreamEx;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Represents method mutation signature
 */
public final class MutationSignature {
  private enum Kind {
    /**
     * Does not mutate anything, including private fields
     */
    TRANSPARENT,
    /**
     * Does not mutate publicly visible state, may mutate private fields (e.g., to cache something)
     */
    PURE,
    /**
     * Mutation signature is unknown: anything can be mutated
     */
    MUTATES_ANYTHING,
    /**
     * Any other specific case described by other fields (mutates either 'this', or parameters, or io) 
     */
    OTHER
  }
  
  public static final String ATTR_MUTATES = "mutates";
  static final MutationSignature UNKNOWN = new MutationSignature(Kind.MUTATES_ANYTHING, false, false, new boolean[0]);
  private static final MutationSignature PURE = new MutationSignature(Kind.PURE, false, false, new boolean[0]);
  private static final MutationSignature TRANSPARENT = new MutationSignature(Kind.TRANSPARENT, false, false, new boolean[0]);
  private static final MutationSignature MUTATES_THIS_ONLY = new MutationSignature(Kind.OTHER, true, false, new boolean[0]);
  private final @NotNull Kind myKind;
  private final boolean myThis;
  private final boolean myIo;
  private final boolean[] myParameters;

  private MutationSignature(@NotNull Kind kind, boolean mutatesThis, boolean io, boolean[] params) {
    if (kind == Kind.PURE || kind == Kind.TRANSPARENT || kind == Kind.MUTATES_ANYTHING) {
      if (mutatesThis || io || params.length != 0) {
        throw new IllegalArgumentException();
      }
    }
    myKind = kind;
    myThis = mutatesThis;
    myIo = io;
    myParameters = params;
  }

  /**
   * @return true if the instance method may mutate this object
   */
  public boolean mutatesThis() {
    return myThis;
  }

  /**
   * @return true if the method may perform input/output operations
   */
  public boolean performsIO() {
    return myIo;
  }

  /**
   * @param n argument number (zero-based)
   * @return true if the method may mutate given argument
   */
  public boolean mutatesArg(int n) {
    return n < myParameters.length && myParameters[n];
  }

  /**
   * @return true if the method is static or never mutates this object
   */
  public boolean preservesThis() {
    return this != UNKNOWN && !myThis;
  }

  /**
   * @param n argument number (zero-based)
   * @return true if the method never mutates given argument
   */
  public boolean preservesArg(int n) {
    return this != UNKNOWN && !mutatesArg(n);
  }

  /**
   * @return a signature that is equivalent to this signature but may also mutate this object
   */
  public MutationSignature alsoMutatesThis() {
    return this == UNKNOWN || myThis ? this :
           isPure() ? MUTATES_THIS_ONLY : new MutationSignature(Kind.OTHER, true, myIo, myParameters);
  }

  /**
   * @param n argument number (zero-based)
   * @return a signature that is equivalent to this signature but may also mutate n-th argument
   */
  public MutationSignature alsoMutatesArg(int n) {
    if (myParameters.length > n && myParameters[n]) return this;
    boolean[] params = Arrays.copyOf(myParameters, Math.max(n + 1, myParameters.length));
    params[n] = true;
    return new MutationSignature(Kind.OTHER, myThis, myIo, params);
  }

  /**
   * @return true if this signature represents a pure method
   */
  public boolean isPure() {
    return myKind == Kind.PURE || myKind == Kind.TRANSPARENT;
  }

  /**
   * @return true if this signature represents a pure method which doesn't mutate even the private state of the object
   */
  public boolean isTransparent() {
    return myKind == Kind.TRANSPARENT;
  }

  @Override
  public int hashCode() {
    int result = myKind.hashCode();
    result = 31 * result + Boolean.hashCode(myThis);
    result = 31 * result + Boolean.hashCode(myIo);
    result = 31 * result + Arrays.hashCode(myParameters);
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    return obj instanceof MutationSignature signature && signature.myKind == myKind &&
           signature.myThis == myThis && signature.myIo == myIo &&
           Arrays.equals(signature.myParameters, myParameters);
  }

  @Override
  public String toString() {
    return switch (myKind) {
      case TRANSPARENT -> "(transparent)";
      case PURE -> "(pure)";
      case MUTATES_ANYTHING -> "(unknown)";
      case OTHER -> IntStreamEx.range(myParameters.length).mapToEntry(idx -> "param" + (idx + 1), idx -> myParameters[idx])
        .prepend("this", myThis).prepend("io", myIo).filterValues(b -> b).keys().joining(",");
    };
  }

  /**
   * Returns a stream of expressions which are mutated by given signature assuming that supplied call
   * resolves to the method with this signature.
   *
   * @param call a call which resolves to the method with this mutation signature
   * @return a stream of expressions which are mutated by this call. If qualifier is omitted, but mutated,
   * a non-physical {@link PsiThisExpression} might be returned.
   */
  public Stream<PsiExpression> mutatedExpressions(PsiMethodCallExpression call) {
    PsiExpression[] args = call.getArgumentList().getExpressions();
    StreamEx<PsiExpression> elements =
      IntStreamEx.range(Math.min(myParameters.length, MethodCallUtils.isVarArgCall(call) ? args.length - 1 : args.length))
        .filter(idx -> myParameters[idx]).elements(args);
    if (myThis) {
      PsiExpression qualifier = ExpressionUtils.getEffectiveQualifier(call.getMethodExpression());
      if (qualifier != null) {
        return elements.prepend(qualifier);
      }
    }
    return elements;
  }

  /**
   * @return true if known to mutate any parameter or receiver, or performs an input/output; false if pure or not known
   */
  public boolean mutatesAnything() {
    if (myThis || myIo) return true;
    for (boolean parameter : myParameters) {
      if (parameter) return true;
    }
    return false;
  }

  /**
   * @param signature to parse
   * @return a parsed mutation signature
   * @throws IllegalArgumentException if signature is invalid
   */
  public static MutationSignature parse(@NotNull String signature) {
    if (signature.trim().isEmpty()) {
      return UNKNOWN;
    }
    boolean mutatesThis = false;
    boolean mutatesIO = false;
    boolean[] args = {};
    for (String part : signature.split(",")) {
      part = part.trim();
      if (part.equals("this")) {
        mutatesThis = true;
      }
      else if (part.equals("io")) {
        mutatesIO = true;
      }
      else if (part.equals("param")) {
        if (args.length == 0) {
          args = new boolean[] {true};
        } else {
          args[0] = true;
        }
      }
      else if (part.startsWith("param")) {
        int argNum = Integer.parseInt(part.substring("param".length()));
        if (argNum < 0 || argNum > 255) {
          throw new IllegalArgumentException(JavaAnalysisBundle.message("mutation.signature.problem.invalid.token", part));
        }
        if(args.length < argNum) {
          args = Arrays.copyOf(args, argNum);
        }
        args[argNum-1] = true;
      }
      else if (!part.isEmpty()) {
        throw new IllegalArgumentException(JavaAnalysisBundle.message("mutation.signature.problem.invalid.token", part));
      }
    }
    return new MutationSignature(Kind.OTHER, mutatesThis, mutatesIO, args);
  }

  /**
   * Checks the mutation signature
   *
   * @param signature signature to check
   * @param method    a method to apply the signature
   * @return error message or null if signature is valid
   */
  public static @Nullable @InspectionMessage String checkSignature(@NotNull String signature, @NotNull PsiMethod method) {
    try {
      MutationSignature ms = parse(signature);
      if (ms.myThis && method.hasModifierProperty(PsiModifier.STATIC)) {
        return JavaAnalysisBundle.message("mutation.signature.problem.static.method.cannot.mutate.this");
      }
      PsiParameter[] parameters = method.getParameterList().getParameters();
      if (ms.myParameters.length > parameters.length) {
        return JavaAnalysisBundle.message("mutation.signature.problem.reference.to.parameter.invalid", ms.myParameters.length);
      }
      for (int i = 0; i < ms.myParameters.length; i++) {
        if (ms.myParameters[i]) {
          PsiType type = parameters[i].getType();
          if (ClassUtils.isImmutable(type)) {
            return JavaAnalysisBundle.message("mutation.signature.problem.parameter.has.immutable.type", i + 1, type.getPresentableText());
          }
        }
      }
    }
    catch (IllegalArgumentException ex) {
      return ex.getMessage();
    }
    return null;
  }

  public static @NotNull MutationSignature fromMethod(@Nullable PsiMethod method) {
    if (method == null) return UNKNOWN;
    return JavaMethodContractUtil.getContractInfo(method).getMutationSignature();
  }

  public static @NotNull MutationSignature fromCall(@Nullable PsiCall call) {
    if (call == null) return UNKNOWN;
    PsiMethod method = call.resolveMethod();
    if (method != null) {
      if (SpecialField.findSpecialField(method) != null) {
        return PURE;
      }
      return fromMethod(method);
    }
    if (call instanceof PsiNewExpression newExpression) {
      if (newExpression.isArrayCreation()) return PURE;
      if (newExpression.getArgumentList() == null || !newExpression.getArgumentList().isEmpty()) return UNKNOWN;
      PsiJavaCodeReferenceElement classReference = newExpression.getClassOrAnonymousClassReference();
      if (classReference == null) return UNKNOWN;
      PsiClass clazz = ObjectUtils.tryCast(classReference.resolve(), PsiClass.class);
      if (clazz == null) return UNKNOWN;
      Set<PsiClass> visited = new HashSet<>();
      while (true) {
        for (PsiField field : clazz.getFields()) {
          if (!field.hasModifierProperty(PsiModifier.STATIC) && field.hasInitializer()) {
            PsiExpression initializer = PsiUtil.skipParenthesizedExprDown(PsiFieldImpl.getDetachedInitializer(field));
            // TODO: support less trivial initializers
            if (initializer instanceof PsiLiteralExpression) {
              continue;
            }
            return UNKNOWN;
          }
        }
        for (PsiClassInitializer initializer : clazz.getInitializers()) {
          if (!initializer.hasModifierProperty(PsiModifier.STATIC)) {
            return UNKNOWN;
          }
        }
        for (PsiMethod ctor : clazz.getConstructors()) {
          if(ctor.getParameterList().isEmpty()) {
            return fromMethod(ctor);
          }
        }
        clazz = clazz.getSuperClass();
        if (clazz == null || !visited.add(clazz)) return unknown();
      }
    }
    return UNKNOWN;
  }

  /**
   * @return a signature of the pure method, which doesn't mutate anything
   */
  public static @NotNull MutationSignature pure() {
    return PURE;
  }

  /**
   * @return a signature of the pure method, which doesn't mutate anything, including private fields
   */
  public static @NotNull MutationSignature transparent() {
    return TRANSPARENT;
  }

  /**
   * @return a signature of the unknown method, which may mutate anything
   */
  public static @NotNull MutationSignature unknown() {
    return UNKNOWN;
  }
}
