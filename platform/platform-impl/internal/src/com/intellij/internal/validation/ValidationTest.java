// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.internal.validation;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * @author Konstantin Bulenkov
 */
public final class ValidationTest extends DialogWrapper {
  private final ValidTest myPanel;
  final ValidationInfo[] ERRORS;

  ValidationTest(Project project) {
    super(project);
    myPanel = new ValidTest();
    ERRORS = new ValidationInfo[]{
      new ValidationInfo("Field1 should not be empty", myPanel.field1),
      new ValidationInfo("Field2 is zip. It should contain 5 digits", myPanel.field2).asWarning(),
      new ValidationInfo("Field3. Value is not chosen", myPanel.field3),
      new ValidationInfo("Field4: Select A or B", myPanel.p4),
      new ValidationInfo("Field5: You should accept license agreement<br/>text text text text text text text text text text text text<br/>text text text text text text text text text text text text"),
      new ValidationInfo("editableComboBox: should contain some value", myPanel.comboBox),
      new ValidationInfo("Spinner should contain values between 20 and 30", myPanel.spinner)
    };

    init();

    myPanel.field5.addActionListener(e -> myPanel.spinner.setEnabled(myPanel.field5.isSelected()));
    myPanel.spinner.setEnabled(myPanel.field5.isSelected());
  }


  @Override
  public JComponent getPreferredFocusedComponent() {
    return myPanel.field2;
  }

  @Override
  protected void doOKAction() {
    super.doOKAction();
    Messages.showInfoMessage("on OK", "Info");
  }

  @Override
  protected @NotNull List<ValidationInfo> doValidateAll() {
    List<ValidationInfo> result = new ArrayList<>();

    if (myPanel.field1.getText().isEmpty()) result.add(ERRORS[0]);
    if (!Pattern.compile("[0-9]{5}").matcher(myPanel.field2.getText()).matches()) result.add(ERRORS[1]);
    if (myPanel.field3.getSelectedItem() == null || "".equals(myPanel.field3.getSelectedItem())) result.add(ERRORS[2]);
    if (!myPanel.field4A.isSelected() && !myPanel.field4B.isSelected()) result.add(ERRORS[3]);
    if (!myPanel.field5.isSelected()) result.add(ERRORS[4]);
    if (myPanel.comboBox.getSelectedItem() == null || "".equals(myPanel.comboBox.getSelectedItem()) ||
        myPanel.comboBox.getEditor().getItem() == null) result.add(ERRORS[5]);

    Object value = myPanel.spinner.getValue();
    try {
      int iv = Integer.parseInt(String.valueOf(value));
      if (value == null || iv < 20 || iv > 30) result.add(ERRORS[6]);
    } catch (NumberFormatException nfe) {
      result.add(ERRORS[6]);
    }

    return result;
  }

  @Override
  protected JComponent createCenterPanel() {
    return myPanel.panel;
  }
}
