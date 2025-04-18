// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.refactoring.typeMigration.rules.guava;

import com.intellij.codeInspection.java18StreamApi.PseudoLambdaReplaceTemplate;
import com.intellij.codeInspection.java18StreamApi.StreamApiConstants;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.openapi.util.NotNullLazyValue;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.codeStyle.SuggestedNameInfo;
import com.intellij.psi.codeStyle.VariableKind;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiTypesUtil;
import com.intellij.refactoring.typeMigration.TypeConversionDescriptor;
import com.intellij.refactoring.typeMigration.TypeConversionDescriptorBase;
import com.intellij.refactoring.typeMigration.TypeEvaluator;
import com.intellij.refactoring.typeMigration.TypeMigrationLabeler;
import com.intellij.refactoring.typeMigration.rules.TypeConversionRule;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.Stack;
import com.siyeh.ig.controlflow.DoubleNegationInspection;
import com.siyeh.ig.psiutils.ParenthesesUtils;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * @author Dmitry Batkovich
 */
public final class GuavaFluentIterableConversionRule extends BaseGuavaTypeConversionRule {
  private static final Logger LOG = Logger.getInstance(GuavaFluentIterableConversionRule.class);
  private static final Map<@NonNls String, TypeConversionDescriptorFactory> DESCRIPTORS_MAP = Map.of(
    "skip", new TypeConversionDescriptorFactory("$q$.skip($p$)", "$q$.skip($p$)", false, true, true),
    "limit", new TypeConversionDescriptorFactory("$q$.limit($p$)", "$q$.limit($p$)", false, true, true),
    "first", new TypeConversionDescriptorFactory("$q$.first()", "$q$.findFirst()", false, true, false),
    "transform", new TypeConversionDescriptorFactory("$q$.transform($params$)", "$q$.map($params$)", true, true, true),

    "allMatch", new TypeConversionDescriptorFactory("$it$.allMatch($c$)", "$it$." + StreamApiConstants.ALL_MATCH + "($c$)", true),
    "anyMatch", new TypeConversionDescriptorFactory("$it$.anyMatch($c$)", "$it$." + StreamApiConstants.ANY_MATCH + "($c$)", true),
    "firstMatch", new TypeConversionDescriptorFactory("$it$.firstMatch($p$)", "$it$.filter($p$).findFirst()", true, true, false),
    "size", new TypeConversionDescriptorFactory("$it$.size()", "(int) $it$.count()", false)
  );

  public static final Set<@NonNls String> CHAIN_HEAD_METHODS = ContainerUtil.newHashSet("from", "of", "fromNullable");
  public static final @NonNls String FLUENT_ITERABLE = "com.google.common.collect.FluentIterable";
  public static final @NonNls String STREAM_COLLECT_TO_LIST = "$it$.collect(java.util.stream.Collectors.toList())";

  static class TypeConversionDescriptorFactory {
    private final String myStringToReplace;
    private final String myReplaceByString;
    private final boolean myWithLambdaParameter;
    private final boolean myChainedMethod;
    private final boolean myFluentIterableReturnType;

    TypeConversionDescriptorFactory(@NonNls String stringToReplace, @NonNls String replaceByString, boolean withLambdaParameter) {
      this(stringToReplace, replaceByString, withLambdaParameter, false, false);
    }

    TypeConversionDescriptorFactory(final @NonNls String stringToReplace,
                                    final @NonNls String replaceByString,
                                    boolean withLambdaParameter,
                                    boolean chainedMethod,
                                    boolean fluentIterableReturnType) {
      myStringToReplace = stringToReplace;
      myReplaceByString = replaceByString;
      myWithLambdaParameter = withLambdaParameter;
      myChainedMethod = chainedMethod;
      myFluentIterableReturnType = fluentIterableReturnType;
    }

    public TypeConversionDescriptor create(PsiExpression context) {
      GuavaTypeConversionDescriptor descriptor = new GuavaTypeConversionDescriptor(myStringToReplace, myReplaceByString, context);
      if (!myWithLambdaParameter) {
        descriptor = descriptor.setConvertParameterAsLambda(false);
      }
      return descriptor;
    }

    public boolean isChainedMethod() {
      return myChainedMethod;
    }

    public boolean isFluentIterableReturnType() {
      return myFluentIterableReturnType;
    }
  }

  @Override
  protected boolean isValidMethodQualifierToConvert(PsiClass aClass) {
    return super.isValidMethodQualifierToConvert(aClass) ||
           (aClass != null && GuavaOptionalConversionRule.GUAVA_OPTIONAL.equals(aClass.getQualifiedName()));
  }

  @Override
  protected @Nullable TypeConversionDescriptorBase findConversionForMethod(@NotNull PsiType from,
                                                                           @NotNull PsiType to,
                                                                           @NotNull PsiMethod method,
                                                                           @NotNull String methodName,
                                                                           PsiExpression context,
                                                                           TypeMigrationLabeler labeler) {
    if (context instanceof PsiMethodCallExpression) {
      return buildCompoundDescriptor((PsiMethodCallExpression)context, to, labeler);
    }

    return getOneMethodDescriptor(methodName, method, from, context);
  }

  private static @Nullable TypeConversionDescriptorBase getOneMethodDescriptor(@NotNull @NlsSafe String methodName,
                                                                               @NotNull PsiMethod method,
                                                                               @NotNull PsiType from,
                                                                               @Nullable PsiExpression context) {
    TypeConversionDescriptor descriptorBase = null;
    PsiType conversionType = null;
    boolean needSpecifyType = true;
    switch (methodName) {
      case "of" -> descriptorBase = new TypeConversionDescriptor("'_FluentIterable?.of($arr$)", "java.util.Arrays.stream($arr$)");
      case "from" -> descriptorBase = new TypeConversionDescriptor("'_FluentIterable?.from($it$)", null) {
        @Override
        public PsiExpression replace(PsiExpression expression, @NotNull TypeEvaluator evaluator) {
          final PsiMethodCallExpression methodCall = (PsiMethodCallExpression)expression;
          PsiExpression argument =
            PseudoLambdaReplaceTemplate.replaceTypeParameters(methodCall.getArgumentList().getExpressions()[0]);
          if (argument == null) {
            return expression;
          }
          boolean isCollection =
            InheritanceUtil.isInheritor(PsiTypesUtil.getPsiClass(argument.getType()), CommonClassNames.JAVA_UTIL_COLLECTION);
          setReplaceByString(isCollection ? "($it$).stream()" : "java.util.stream.StreamSupport.stream(($it$).spliterator(), false)");
          final PsiExpression replaced = super.replace(expression, evaluator);
          ParenthesesUtils.removeParentheses(replaced, false);
          return replaced;
        }
      };
      case "filter" -> descriptorBase = FluentIterableConversionUtil.getFilterDescriptor(method, context);
      case "isEmpty" -> {
        descriptorBase = new TypeConversionDescriptor("$q$.isEmpty()", null) {
          @Override
          public PsiExpression replace(PsiExpression expression, @NotNull TypeEvaluator evaluator) {
            final PsiElement parent = expression.getParent();
            boolean isDoubleNegation = false;
            if (parent instanceof PsiExpression && DoubleNegationInspection.isNegation((PsiExpression)parent)) {
              isDoubleNegation = true;
              expression = (PsiExpression)parent.replace(expression);
            }
            setReplaceByString((isDoubleNegation ? "" : "!") + "$q$.findAny().isPresent()");
            return super.replace(expression, evaluator);
          }
        };
        needSpecifyType = false;
      }
      case "transformAndConcat" -> descriptorBase = new FluentIterableConversionUtil.TransformAndConcatConversionRule(context);
      case "toArray" -> {
        descriptorBase = FluentIterableConversionUtil.getToArrayDescriptor(from, context);
        needSpecifyType = false;
      }
      case "copyInto" -> {
        descriptorBase = new FluentIterableConversionUtil.CopyIntoConversionDescriptor();
        needSpecifyType = false;
      }
      case "append" -> descriptorBase = createDescriptorForAppend(method, context);
      case "get" -> {
        descriptorBase = new TypeConversionDescriptor("$it$.get($p$)", null) {
          @Override
          public PsiExpression replace(PsiExpression expression, @NotNull TypeEvaluator evaluator) {
            PsiMethodCallExpression methodCall = (PsiMethodCallExpression)expression;
            final PsiExpression[] arguments = methodCall.getArgumentList().getExpressions();
            setReplaceByString("$it$.skip($p$).findFirst().get()");
            if (arguments.length == 1 && arguments[0] instanceof PsiLiteralExpression) {
              final Object value = ((PsiLiteralExpression)arguments[0]).getValue();
              if (value != null && value.equals(0)) {
                setReplaceByString("$it$.findFirst().get()");
              }
            }
            return super.replace(expression, evaluator);
          }
        };
        needSpecifyType = false;
      }
      case "contains" -> {
        descriptorBase = new TypeConversionDescriptor("$it$.contains($o$)", null) {
          @Override
          public PsiExpression replace(PsiExpression expression, @NotNull TypeEvaluator evaluator) {
            final PsiMethodCallExpression methodCallExpression = (PsiMethodCallExpression)expression;
            final PsiExpression qualifier = methodCallExpression.getMethodExpression().getQualifierExpression();
            LOG.assertTrue(qualifier != null);
            final PsiClassType qualifierType = (PsiClassType)qualifier.getType();
            LOG.assertTrue(qualifierType != null);
            final PsiType[] parameters = qualifierType.getParameters();

            final JavaCodeStyleManager codeStyleManager = JavaCodeStyleManager.getInstance(expression.getProject());
            final SuggestedNameInfo suggestedNameInfo = codeStyleManager
              .suggestVariableName(VariableKind.LOCAL_VARIABLE, null, null, parameters.length == 1 ? parameters[0] : null, false);
            final String suggestedName = codeStyleManager.suggestUniqueVariableName(suggestedNameInfo, expression, false).names[0];

            setReplaceByString("$it$.anyMatch(" + suggestedName + " -> java.util.Objects.equals(" + suggestedName + ", $o$))");

            return super.replace(expression, evaluator);
          }
        };
        needSpecifyType = false;
      }
      case "last" -> descriptorBase = new TypeConversionDescriptor("$it$.last()", null) {
        @Override
        public PsiExpression replace(PsiExpression expression, @NotNull TypeEvaluator evaluator) {
          final JavaCodeStyleManager codeStyleManager = JavaCodeStyleManager.getInstance(expression.getProject());
          String varA = suggestName("a", codeStyleManager, expression);
          String varB = suggestName("b", codeStyleManager, expression);
          setReplaceByString("$it$.reduce((" + varA + ", " + varB + ") -> " + varB + ")");
          return super.replace(expression, evaluator);
        }

        private static String suggestName(String baseName, JavaCodeStyleManager codeStyleManager, PsiElement place) {
          final SuggestedNameInfo suggestedNameInfo =
            codeStyleManager.suggestVariableName(VariableKind.LOCAL_VARIABLE, baseName, null, null, false);
          return codeStyleManager.suggestUniqueVariableName(suggestedNameInfo, place, false).names[0];
        }
      };
      default -> {
        final TypeConversionDescriptorFactory base = DESCRIPTORS_MAP.get(methodName);
        if (base != null) {
          final TypeConversionDescriptor descriptor = base.create(context);
          needSpecifyType = base.isChainedMethod();
          if (needSpecifyType && !base.isFluentIterableReturnType()) {
            conversionType = GuavaConversionUtil.addTypeParameters(GuavaOptionalConversionRule.JAVA_OPTIONAL, context.getType(), context);
          }
          descriptorBase = descriptor;
        }
      }
    }
    if (descriptorBase == null) {
      return FluentIterableConversionUtil.createToCollectionDescriptor(methodName, context);
    }
    if (needSpecifyType) {
      if (conversionType == null) {
        PsiMethodCallExpression methodCall =
          (PsiMethodCallExpression)(context instanceof PsiMethodCallExpression ? context : context.getParent());
        conversionType = GuavaConversionUtil.addTypeParameters(GuavaTypeConversionDescriptor.isIterable(methodCall)
                                                               ? CommonClassNames.JAVA_LANG_ITERABLE
                                                               : StreamApiConstants.JAVA_UTIL_STREAM_STREAM, context.getType(), context);
      }
      descriptorBase.withConversionType(conversionType);
    }
    return descriptorBase;
  }

  private static @Nullable TypeConversionDescriptor createDescriptorForAppend(PsiMethod method, PsiExpression context) {
    LOG.assertTrue("append".equals(method.getName()));
    final PsiParameterList list = method.getParameterList();
    if (list.getParametersCount() != 1) return null;
    final PsiType parameterType = list.getParameters()[0].getType();
    if (parameterType instanceof PsiEllipsisType) {
      return new TypeConversionDescriptor("$q$.append('_params*)",
                                          "java.util.stream.Stream.concat($q$, java.util.Arrays.asList($params$).stream())");
    }
    else if (parameterType instanceof PsiClassType) {
      final PsiClass psiClass = PsiTypesUtil.getPsiClass(parameterType);
      if (psiClass != null && CommonClassNames.JAVA_LANG_ITERABLE.equals(psiClass.getQualifiedName())) {
        PsiMethodCallExpression methodCall =
          (PsiMethodCallExpression)(context instanceof PsiMethodCallExpression ? context : context.getParent());
        final PsiExpression expression = methodCall.getArgumentList().getExpressions()[0];
        boolean isCollection =
          InheritanceUtil.isInheritor(PsiTypesUtil.getPsiClass(expression.getType()), CommonClassNames.JAVA_UTIL_COLLECTION);
        final String argTemplate = isCollection ? "$arg$.stream()" : "java.util.stream.StreamSupport.stream($arg$.spliterator(), false)";
        return new TypeConversionDescriptor("$q$.append($arg$)", "java.util.stream.Stream.concat($q$," + argTemplate + ")");
      }
    }
    return null;
  }

  public static @Nullable GuavaChainedConversionDescriptor buildCompoundDescriptor(PsiMethodCallExpression expression,
                                                                                   PsiType to,
                                                                                   TypeMigrationLabeler labeler) {
    List<TypeConversionDescriptorBase> methodDescriptors = new SmartList<>();

    NotNullLazyValue<TypeConversionRule> optionalDescriptor = NotNullLazyValue.createValue(() -> {
      for (TypeConversionRule rule : TypeConversionRule.EP_NAME.getExtensions()) {
        if (rule instanceof GuavaOptionalConversionRule) {
          return rule;
        }
      }
      throw new RuntimeException("GuavaOptionalConversionRule extension is not found");
    });

    PsiMethodCallExpression current = expression;
    while (true) {
      final PsiMethod method = current.resolveMethod();
      if (method == null) {
        break;
      }
      final String methodName = method.getName();
      final PsiClass containingClass = method.getContainingClass();
      if (containingClass == null) {
        break;
      }
      TypeConversionDescriptorBase descriptor = null;
      if (FLUENT_ITERABLE.equals(containingClass.getQualifiedName())) {
        descriptor = getOneMethodDescriptor(methodName, method, current.getType(), current);
        if (descriptor == null) {
          return null;
        }
      }
      else if (GuavaOptionalConversionRule.GUAVA_OPTIONAL.equals(containingClass.getQualifiedName())) {
        descriptor = optionalDescriptor.getValue().findConversion(null, null, method, current.getMethodExpression(), labeler);
        if (descriptor == null) {
          return null;
        }
      }
      if (descriptor == null) {
        addToMigrateChainQualifier(labeler, current);
        break;
      }
      methodDescriptors.add(descriptor);
      final PsiExpression qualifier = current.getMethodExpression().getQualifierExpression();
      if (qualifier instanceof PsiMethodCallExpression) {
        current = (PsiMethodCallExpression)qualifier;
      }
      else if (method.hasModifierProperty(PsiModifier.STATIC)) {
        if (!CHAIN_HEAD_METHODS.contains(methodName)) {
          return null;
        }
        final PsiClass aClass = method.getContainingClass();
        if (aClass == null || !(FLUENT_ITERABLE.equals(aClass.getQualifiedName()) ||
                                GuavaOptionalConversionRule.GUAVA_OPTIONAL.equals(aClass.getQualifiedName()))) {
          return null;
        }
        break;
      }
      else if (qualifier instanceof PsiReferenceExpression && ((PsiReferenceExpression)qualifier).resolve() instanceof PsiVariable) {
        addToMigrateChainQualifier(labeler, qualifier);
        break;
      }
      else {
        return null;
      }
    }

    return new GuavaChainedConversionDescriptor(methodDescriptors, to);
  }

  private static void addToMigrateChainQualifier(TypeMigrationLabeler labeler, PsiExpression qualifier) {
    final PsiClass qClass = PsiTypesUtil.getPsiClass(qualifier.getType());
    final boolean isFluentIterable;
    if (qClass != null && ((isFluentIterable = FLUENT_ITERABLE.equals(qClass.getQualifiedName())) ||
                           GuavaOptionalConversionRule.GUAVA_OPTIONAL.equals(qClass.getQualifiedName()))) {
      labeler.migrateExpressionType(qualifier,
                                    GuavaConversionUtil.addTypeParameters(isFluentIterable ? StreamApiConstants.JAVA_UTIL_STREAM_STREAM :
                                                                          GuavaOptionalConversionRule.JAVA_OPTIONAL, qualifier.getType(),
                                                                          qualifier),
                                    qualifier.getParent(),
                                    false,
                                    false);
    }
  }

  private static final class GuavaChainedConversionDescriptor extends TypeConversionDescriptorBase {
    private final List<TypeConversionDescriptorBase> myMethodDescriptors;
    private final PsiType myToType;

    private GuavaChainedConversionDescriptor(List<TypeConversionDescriptorBase> descriptors, PsiType to) {
      myMethodDescriptors = new ArrayList<>(descriptors);
      Collections.reverse(myMethodDescriptors);
      myToType = to;
    }

    @Override
    public PsiExpression replace(@NotNull PsiExpression expression, @NotNull TypeEvaluator evaluator) {
      Stack<PsiMethodCallExpression> methodChainStack = new Stack<>();
      PsiMethodCallExpression current = (PsiMethodCallExpression)expression;
      while (current != null) {
        methodChainStack.add(current);
        final PsiExpression qualifier = current.getMethodExpression().getQualifierExpression();
        current = qualifier instanceof PsiMethodCallExpression ? (PsiMethodCallExpression)qualifier : null;
      }

      if (methodChainStack.size() != myMethodDescriptors.size()) {
        return expression;
      }

      PsiExpression converted = null;

      for (TypeConversionDescriptorBase descriptor : myMethodDescriptors) {
        final PsiMethodCallExpression toConvert = methodChainStack.pop();
        converted = descriptor.replace(toConvert, evaluator);
      }

      return converted;
    }

    @Override
    public @Nullable PsiType conversionType() {
      return myToType;
    }
  }

  @Override
  public @NotNull String ruleFromClass() {
    return FLUENT_ITERABLE;
  }

  @Override
  public @NotNull String ruleToClass() {
    return StreamApiConstants.JAVA_UTIL_STREAM_STREAM;
  }

  @Override
  protected TypeConversionDescriptorBase getUnknownMethodConversion() {
    return null;
  }
}
