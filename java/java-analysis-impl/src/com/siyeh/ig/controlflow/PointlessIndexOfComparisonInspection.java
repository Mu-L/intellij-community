// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.siyeh.ig.controlflow;

import com.intellij.psi.*;
import com.intellij.psi.tree.IElementType;
import com.siyeh.HardcodedMethodConstants;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.psiutils.ComparisonUtils;
import com.siyeh.ig.psiutils.ExpressionUtils;
import org.jetbrains.annotations.NotNull;

public final class PointlessIndexOfComparisonInspection extends BaseInspection {

  @Override
  protected @NotNull String buildErrorString(Object... infos) {
    final PsiBinaryExpression expression = (PsiBinaryExpression)infos[0];
    final PsiExpression lhs = expression.getLOperand();
    final PsiJavaToken sign = expression.getOperationSign();
    final boolean value;
    if (lhs instanceof PsiMethodCallExpression) {
      value = createContainsExpressionValue(sign, false);
    }
    else {
      value = createContainsExpressionValue(sign, true);
    }
    if (value) {
      return InspectionGadgetsBundle.message(
        "pointless.indexof.comparison.always.true.problem.descriptor");
    }
    else {
      return InspectionGadgetsBundle.message(
        "pointless.indexof.comparison.always.false.problem.descriptor");
    }
  }

  static boolean createContainsExpressionValue(
    @NotNull PsiJavaToken sign, boolean flipped) {
    final IElementType tokenType = sign.getTokenType();
    if (tokenType.equals(JavaTokenType.EQEQ)) {
      return false;
    }
    if (tokenType.equals(JavaTokenType.NE)) {
      return true;
    }
    if (flipped) {
      if (tokenType.equals(JavaTokenType.GT) ||
          tokenType.equals(JavaTokenType.GE)) {
        return false;
      }
    }
    else {
      if (tokenType.equals(JavaTokenType.LT) ||
          tokenType.equals(JavaTokenType.LE)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new PointlessIndexOfComparisonVisitor();
  }

  private static class PointlessIndexOfComparisonVisitor
    extends BaseInspectionVisitor {

    @Override
    public void visitBinaryExpression(
      @NotNull PsiBinaryExpression expression) {
      super.visitBinaryExpression(expression);
      final PsiExpression rhs = expression.getROperand();
      if (rhs == null) {
        return;
      }
      if (!ComparisonUtils.isComparison(expression)) {
        return;
      }
      final PsiExpression lhs = expression.getLOperand();
      if (lhs instanceof PsiMethodCallExpression) {
        final PsiJavaToken sign = expression.getOperationSign();
        if (isPointLess(lhs, sign, rhs, false)) {
          registerError(expression, expression);
        }
      }
      else if (rhs instanceof PsiMethodCallExpression) {
        final PsiJavaToken sign = expression.getOperationSign();
        if (isPointLess(rhs, sign, lhs, true)) {
          registerError(expression, expression);
        }
      }
    }

    private static boolean isPointLess(PsiExpression lhs, PsiJavaToken sign,
                                       PsiExpression rhs, boolean flipped) {
      final PsiMethodCallExpression callExpression =
        (PsiMethodCallExpression)lhs;
      if (!isIndexOfCall(callExpression)) {
        return false;
      }
      final Object object =
        ExpressionUtils.computeConstantExpression(rhs);
      if (!(object instanceof Integer integer)) {
        return false;
      }
      final int constant = integer.intValue();
      final IElementType tokenType = sign.getTokenType();
      if (tokenType == null) {
        return false;
      }
      if (flipped) {
        if (constant < 0 && (tokenType.equals(JavaTokenType.GT) ||
                             tokenType.equals(JavaTokenType.LE))) {
          return true;
        }
        else if (constant < -1 &&
                 (tokenType.equals(JavaTokenType.GE) ||
                  tokenType.equals(JavaTokenType.LT) ||
                  tokenType.equals(JavaTokenType.NE) ||
                  tokenType.equals(JavaTokenType.EQEQ))) {
          return true;
        }
      }
      else {
        if (constant < 0 && (tokenType.equals(JavaTokenType.LT) ||
                             tokenType.equals(JavaTokenType.GE))) {
          return true;
        }
        else if (constant < -1 &&
                 (tokenType.equals(JavaTokenType.LE) ||
                  tokenType.equals(JavaTokenType.GT) ||
                  tokenType.equals(JavaTokenType.NE) ||
                  tokenType.equals(JavaTokenType.EQEQ))) {
          return true;
        }
      }
      return false;
    }

    private static boolean isIndexOfCall(
      @NotNull PsiMethodCallExpression expression) {
      final PsiReferenceExpression methodExpression =
        expression.getMethodExpression();
      final String methodName = methodExpression.getReferenceName();
      return HardcodedMethodConstants.INDEX_OF.equals(methodName);
    }
  }
}