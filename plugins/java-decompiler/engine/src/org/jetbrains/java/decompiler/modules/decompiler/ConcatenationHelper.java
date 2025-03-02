// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.java.decompiler.modules.decompiler;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.java.decompiler.ClassNameConstants;
import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.modules.decompiler.exps.*;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;
import org.jetbrains.java.decompiler.struct.consts.PooledConstant;
import org.jetbrains.java.decompiler.struct.consts.PrimitiveConstant;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;
import org.jetbrains.java.decompiler.struct.gen.VarType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

public final class ConcatenationHelper {

  private static final String builderClass = "java/lang/StringBuilder";
  private static final String bufferClass = "java/lang/StringBuffer";

  private static final VarType builderType = new VarType(CodeConstants.TYPE_OBJECT, 0, "java/lang/StringBuilder");
  private static final VarType bufferType = new VarType(CodeConstants.TYPE_OBJECT, 0, "java/lang/StringBuffer");

  public static void simplifyStringConcat(Statement stat) {
    for (Statement s : stat.getStats()) {
      simplifyStringConcat(s);
    }

    if (stat.getExprents() != null) {
      for (int i = 0; i < stat.getExprents().size(); ++i) {
        Exprent ret = simplifyStringConcat(stat.getExprents().get(i));
        if (ret != null) {
          stat.getExprents().set(i, ret);
        }
      }
    }
  }

  private static Exprent simplifyStringConcat(Exprent exprent) {
    for (Exprent cexp : exprent.getAllExprents()) {
      Exprent ret = simplifyStringConcat(cexp);
      if (ret != null) {
        exprent.replaceExprent(cexp, ret);
      }
    }

    if (exprent.type == Exprent.EXPRENT_INVOCATION) {
      Exprent ret = ConcatenationHelper.contractStringConcat(exprent);
      if (!exprent.equals(ret)) {
        return ret;
      }
    }

    return null;
  }

  public static Exprent contractStringConcat(Exprent expr) {

    Exprent exprTmp = null;
    VarType cltype = null;

    // first quick test
    if (expr.type == Exprent.EXPRENT_INVOCATION) {
      InvocationExprent iex = (InvocationExprent)expr;
      if ("toString".equals(iex.getName())) {
        if (builderClass.equals(iex.getClassName())) {
          cltype = builderType;
        }
        else if (bufferClass.equals(iex.getClassName())) {
          cltype = bufferType;
        }
        if (cltype != null) {
          exprTmp = iex.getInstance();
        }
      }
      else if ("makeConcatWithConstants".equals(iex.getName())) { // java 9 style
        List<Exprent> parameters = extractParameters(iex.getBootstrapArguments(), iex);
        if (parameters.size() >= 2) {
          return createConcatExprent(parameters, expr.bytecode);
        }
      }
    }

    if (exprTmp == null) {
      return expr;
    }


    // iterate in depth, collecting possible operands
    List<Exprent> lstOperands = new ArrayList<>();

    while (true) {

      int found = 0;

      switch (exprTmp.type) {
        case Exprent.EXPRENT_INVOCATION -> {
          InvocationExprent iex = (InvocationExprent)exprTmp;
          if (isAppendConcat(iex, cltype)) {
            lstOperands.add(0, iex.getParameters().get(0));
            exprTmp = iex.getInstance();
            found = 1;
          }
        }
        case Exprent.EXPRENT_NEW -> {
          NewExprent nex = (NewExprent)exprTmp;
          if (isNewConcat(nex, cltype)) {
            VarType[] params = nex.getConstructor().getDescriptor().params;
            if (params.length == 1) {
              lstOperands.add(0, nex.getConstructor().getParameters().get(0));
            }
            found = 2;
          }
        }
      }

      if (found == 0) {
        return expr;
      }
      else if (found == 2) {
        break;
      }
    }

    int first2str = 0;
    int index = 0;
    while (index < lstOperands.size() && index < 2) {
      if (lstOperands.get(index).getExprType().equals(VarType.VARTYPE_STRING)) {
        first2str |= (index + 1);
      }
      index++;
    }

    if (first2str == 0) {
      lstOperands.add(0, new ConstExprent(VarType.VARTYPE_STRING, "", expr.bytecode));
    }

    // remove redundant String.valueOf
    for (int i = 0; i < lstOperands.size(); i++) {
      Exprent rep = removeStringValueOf(lstOperands.get(i));

      boolean ok = (i > 1);
      if (!ok) {
        boolean isstr = rep.getExprType().equals(VarType.VARTYPE_STRING);
        ok = isstr || first2str != i + 1;

        if (i == 0) {
          first2str &= 2;
        }
      }

      if (ok) {
        lstOperands.set(i, rep);
      }
    }
    return createConcatExprent(lstOperands, expr.bytecode);
  }

  private static Exprent createConcatExprent(List<Exprent> lstOperands, @Nullable BitSet bytecode) {
    // build exprent to return
    Exprent func = lstOperands.get(0);

    for (int i = 1; i < lstOperands.size(); i++) {
      func = new FunctionExprent(FunctionExprent.FUNCTION_STR_CONCAT, Arrays.asList(func, lstOperands.get(i)), bytecode);
    }

    return func;
  }

  // See StringConcatFactory in jdk sources
  private static final char TAG_ARG = '\u0001';
  private static final char TAG_CONST = '\u0002';

  private static List<Exprent> extractParameters(List<PooledConstant> bootstrapArguments, InvocationExprent expr) {
    List<Exprent> parameters = expr.getParameters();
    if (bootstrapArguments != null) {
      PooledConstant constant = bootstrapArguments.get(0);
      if (constant.type == CodeConstants.CONSTANT_String) {
        String recipe = ((PrimitiveConstant)constant).getString();

        List<Exprent> res = new ArrayList<>();
        StringBuilder acc = new StringBuilder();
        int parameterId = 0;
        int bootstrapArgumentId = 1;
        int nonString = 0;
        for (int i = 0; i < recipe.length(); i++) {
          char c = recipe.charAt(i);

          if (c == TAG_CONST || c == TAG_ARG) {
            // Detected a special tag, flush all accumulated characters
            // as a constant first:
            if (!acc.isEmpty()) {
              res.add(new ConstExprent(VarType.VARTYPE_STRING, acc.toString(), expr.bytecode));
              acc.setLength(0);
            }

            if (c == TAG_CONST) {
              PooledConstant pooledConstant = bootstrapArguments.get(bootstrapArgumentId++);

              if (pooledConstant.type == CodeConstants.CONSTANT_String) {
                res.add(new ConstExprent(VarType.VARTYPE_STRING, ((PrimitiveConstant) pooledConstant).getString(), expr.bytecode));
              }
            }

            if (c == TAG_ARG) {
              Exprent exprent = parameters.get(parameterId++);

              if (!VarType.VARTYPE_STRING.equals(exprent.getExprType())) {
                nonString++;
                if (nonString == 2 && i == 1) {
                  // First two items of concatenation are variables and their types are not String.
                  // Prepend it with empty string literal to force resulting expression type to be String.
                  res.add(0, new ConstExprent(VarType.VARTYPE_STRING, "", expr.bytecode));
                }
              }

              res.add(exprent);
            }
          }
          else {
            // Not a special characters, this is a constant embedded into
            // the recipe itself.
            acc.append(c);
          }
        }

        // Flush the remaining characters as constant:
        if (!acc.isEmpty()) {
          res.add(new ConstExprent(VarType.VARTYPE_STRING, acc.toString(), expr.bytecode));
        }

        if (res.size() == 1) {
          //
          // Only one element in result list.
          // The most probable cause is an empty prefix/suffix.
          // Something like this:
          //
          //   return "" + value;
          //
          // NOTE: Empty suffix is indistinguishable from empty prefix. We always assume latter.
          //
          res.add(0, new ConstExprent(VarType.VARTYPE_STRING, "", expr.bytecode));
        }

        return res;
      }
    }
    return new ArrayList<>(parameters);
  }

  private static boolean isAppendConcat(InvocationExprent expr, VarType cltype) {

    if ("append".equals(expr.getName())) {
      MethodDescriptor md = expr.getDescriptor();
      if (md.ret.equals(cltype) && md.params.length == 1) {
        VarType param = md.params[0];
        switch (param.getType()) {
          case CodeConstants.TYPE_OBJECT:
            if (!param.equals(VarType.VARTYPE_STRING) &&
                !param.equals(VarType.VARTYPE_OBJECT)) {
              break;
            }
          case CodeConstants.TYPE_BOOLEAN:
          case CodeConstants.TYPE_CHAR:
          case CodeConstants.TYPE_DOUBLE:
          case CodeConstants.TYPE_FLOAT:
          case CodeConstants.TYPE_INT:
          case CodeConstants.TYPE_LONG:
            return true;
          default:
        }
      }
    }

    return false;
  }

  private static boolean isNewConcat(NewExprent expr, VarType cltype) {
    if (expr.getNewType().equals(cltype)) {
      VarType[] params = expr.getConstructor().getDescriptor().params;
      return params.length == 0 || params.length == 1 && params[0].equals(VarType.VARTYPE_STRING);
    }

    return false;
  }

  private static Exprent removeStringValueOf(Exprent exprent) {

    if (exprent.type == Exprent.EXPRENT_INVOCATION) {
      InvocationExprent iex = (InvocationExprent)exprent;
      if ("valueOf".equals(iex.getName()) && ClassNameConstants.JAVA_LANG_STRING.equals(iex.getClassName())) {
        MethodDescriptor md = iex.getDescriptor();
        if (md.params.length == 1) {
          VarType param = md.params[0];
          switch (param.getType()) {
            case CodeConstants.TYPE_OBJECT:
              if (!param.equals(VarType.VARTYPE_OBJECT)) {
                break;
              }
            case CodeConstants.TYPE_BOOLEAN:
            case CodeConstants.TYPE_CHAR:
            case CodeConstants.TYPE_DOUBLE:
            case CodeConstants.TYPE_FLOAT:
            case CodeConstants.TYPE_INT:
            case CodeConstants.TYPE_LONG:
              return iex.getParameters().get(0);
          }
        }
      }
    }

    return exprent;
  }
}
