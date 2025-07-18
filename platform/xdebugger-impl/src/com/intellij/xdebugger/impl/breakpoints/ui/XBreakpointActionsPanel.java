// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.xdebugger.impl.breakpoints.ui;

import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.xdebugger.XDebuggerBundle;
import com.intellij.xdebugger.XExpression;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import com.intellij.xdebugger.impl.XDebuggerUtilImpl;
import com.intellij.xdebugger.impl.breakpoints.XBreakpointProxy;
import com.intellij.xdebugger.impl.breakpoints.XLineBreakpointProxy;
import com.intellij.xdebugger.impl.ui.DebuggerUIUtil;
import com.intellij.xdebugger.impl.ui.XDebuggerExpressionComboBox;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

@ApiStatus.Internal
public class XBreakpointActionsPanel extends XBreakpointPropertiesSubPanel {
  public static final String LOG_EXPRESSION_HISTORY_ID = "breakpointLogExpression";

  private JLabel myLogMessageLabel;
  private JCheckBox myLogMessageCheckBox;
  private JCheckBox myLogExpressionCheckBox;
  private JPanel myLogExpressionCheckBoxPanel;
  private JPanel myLogExpressionPanel;
  private JPanel myContentPane;
  private JPanel myMainPanel;
  private JCheckBox myTemporaryCheckBox;
  private JPanel myExpressionPanel;
  private JPanel myLanguageChooserPanel;
  private JCheckBox myLogStack;
  private @Nullable XDebuggerExpressionComboBox myLogExpressionComboBox;

  private boolean myShowAllOptions;

  public void init(Project project,
                   @NotNull XBreakpointProxy breakpoint,
                   @Nullable XDebuggerEditorsProvider debuggerEditorsProvider,
                   boolean showAllOptions) {
    init(project, breakpoint);
    myShowAllOptions = showAllOptions;
    if (debuggerEditorsProvider != null) {
      ActionListener listener = new ActionListener() {
        @Override
        public void actionPerformed(final ActionEvent e) {
          onCheckboxChanged();
        }
      };
      myLogExpressionCheckBox = new JBCheckBox(XDebuggerBundle.message("xbreakpoints.log.expression.checkbox"));
      myLogExpressionComboBox = new XDebuggerExpressionComboBox(project, debuggerEditorsProvider, LOG_EXPRESSION_HISTORY_ID,
                                                                null, true, false);
      myLanguageChooserPanel.add(myLogExpressionComboBox.getLanguageChooser(), BorderLayout.CENTER);
      myLogExpressionPanel.setBorder(JBUI.Borders.emptyLeft(UIUtil.getCheckBoxTextHorizontalOffset(myLogExpressionCheckBox)));
      myLogExpressionPanel.add(myLogExpressionComboBox.getComponent(), BorderLayout.CENTER);
      myLogExpressionComboBox.setEnabled(false);
      myLogExpressionCheckBox.addActionListener(listener);
      DebuggerUIUtil.focusEditorOnCheck(myLogExpressionCheckBox, myLogExpressionComboBox.getEditorComponent());
    }
    else {
      myExpressionPanel.getParent().remove(myExpressionPanel);
    }
    boolean isLineBreakpoint = breakpoint instanceof XLineBreakpointProxy;
    myTemporaryCheckBox.setVisible(isLineBreakpoint);
    if (isLineBreakpoint) {
      myTemporaryCheckBox.addActionListener(e -> ((XLineBreakpointProxy)myBreakpoint).setTemporary(myTemporaryCheckBox.isSelected()));
    }
  }

  void setSourcePosition(XSourcePosition sourcePosition) {
    if (myLogExpressionComboBox != null) {
      myLogExpressionComboBox.setSourcePosition(sourcePosition);
    }
  }

  @Override
  public boolean lightVariant(boolean showAllOptions) {
    if (!showAllOptions && !myBreakpoint.isLogMessage() && !myBreakpoint.isLogStack() && myBreakpoint.getLogExpressionObjectInt() == null &&
        (!(myBreakpoint instanceof XLineBreakpointProxy) || !((XLineBreakpointProxy)myBreakpoint).isTemporary())) {
      myMainPanel.setVisible(false);
      return true;
    } else {
      myMainPanel.setBorder(null);
      return false;
    }
  }

  private void onCheckboxChanged() {
    if (myLogExpressionComboBox != null) {
      myLogExpressionComboBox.setEnabled(myLogExpressionCheckBox.isSelected());
    }
  }

  @Override
  void loadProperties() {
    myLogMessageCheckBox.setSelected(myBreakpoint.isLogMessage());
    myLogStack.setSelected(myBreakpoint.isLogStack());

    if (myBreakpoint instanceof XLineBreakpointProxy lineBreakpointProxy) {
      myTemporaryCheckBox.setSelected(lineBreakpointProxy.isTemporary());
    }

    if (myLogExpressionComboBox != null) {
      XExpression logExpression = myBreakpoint.getLogExpressionObjectInt();
      myLogExpressionComboBox.setExpression(logExpression);
      boolean hideCheckbox = !myShowAllOptions && logExpression == null;
      myLogExpressionCheckBox.setSelected(hideCheckbox || (myBreakpoint.isLogExpressionEnabled() && logExpression != null));
      myLogExpressionCheckBoxPanel.removeAll();
      if (hideCheckbox) {
        var label = new JLabel(XDebuggerBundle.message("xbreakpoints.log.expression.checkbox"));
        label.setBorder(JBUI.Borders.empty(0, 4, 4, 0));
        label.setLabelFor(myLogExpressionComboBox.getComboBox());
        myLogExpressionCheckBoxPanel.add(label);
        myLogExpressionPanel.setBorder(JBUI.Borders.emptyLeft(3));
        myLogMessageLabel.setBorder(JBUI.Borders.empty(0, 4, 4, 0)); // to unify with other labels
      } else {
        myLogExpressionCheckBoxPanel.add(myLogExpressionCheckBox);
        myLogExpressionPanel.setBorder(JBUI.Borders.emptyLeft(UIUtil.getCheckBoxTextHorizontalOffset(myLogExpressionCheckBox)));
      }
    }
    onCheckboxChanged();
  }

  @Override
  void saveProperties() {
    myBreakpoint.setLogMessage(myLogMessageCheckBox.isSelected());
    myBreakpoint.setLogStack(myLogStack.isSelected());

    if (myBreakpoint instanceof XLineBreakpointProxy lineBreakpointProxy) {
      lineBreakpointProxy.setTemporary(myTemporaryCheckBox.isSelected());
    }

    if (myLogExpressionComboBox != null) {
      XExpression expression = myLogExpressionComboBox.getExpression();
      XExpression logExpression = !XDebuggerUtilImpl.isEmptyExpression(expression) ? expression : null;
      myBreakpoint.setLogExpressionEnabled(logExpression == null || myLogExpressionCheckBox.isSelected());
      myBreakpoint.setLogExpressionObject(logExpression);
      myLogExpressionComboBox.saveTextInHistory();
    }
  }

  JComponent getDefaultFocusComponent() {
    if (myLogExpressionComboBox != null && myLogExpressionComboBox.getComboBox().isEnabled()) {
      return myLogExpressionComboBox.getEditorComponent();
    }
    return null;
  }

  public void dispose() {
  }

  public void hide() {
    myContentPane.setVisible(false);
  }
}
