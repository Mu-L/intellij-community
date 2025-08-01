// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.configuration;

import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.UnnamedConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.FixedSizeButton;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.ui.CollectionComboBoxModel;
import com.intellij.ui.ComboboxSpeedSearch;
import com.intellij.ui.components.DropDownLink;
import com.intellij.util.NullableConsumer;
import com.intellij.util.ui.JBUI;
import com.intellij.webcore.packaging.PackagesNotificationPanel;
import com.jetbrains.python.PyBundle;
import com.jetbrains.python.packaging.PyPackageManagers;
import com.jetbrains.python.packaging.PyPackagesNotificationPanel;
import com.jetbrains.python.packaging.ui.PyInstalledPackagesPanel;
import com.jetbrains.python.sdk.*;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.jetbrains.python.sdk.PySdkRenderingKt.groupModuleSdksByTypes;
import static com.jetbrains.python.sdk.PythonSdkUtil.isRemote;

public class PyActiveSdkConfigurable implements UnnamedConfigurable {

  private static final Logger LOG = Logger.getInstance(PyActiveSdkConfigurable.class);

  protected final @NotNull Project myProject;

  protected final @Nullable Module myModule;

  private final @NotNull PyConfigurableInterpreterList myInterpreterList;

  protected final @NotNull ProjectSdksModel myProjectSdksModel;

  private final @NotNull JPanel myMainPanel;

  private final @NotNull ComboBox<Object> mySdkCombo;

  private final @NotNull PyInstalledPackagesPanel myPackagesPanel;
  private final @Nullable PyPanelWithPromo myPanelWithPromo;

  private final @Nullable Disposable myDisposable;

  public PyActiveSdkConfigurable(@NotNull Project project) {
    this(project, null);
  }

  public PyActiveSdkConfigurable(@NotNull Module module) {
    this(module.getProject(), module);
  }

  private PyActiveSdkConfigurable(@NotNull Project project, @Nullable Module module) {
    myProject = project;
    myModule = module;

    mySdkCombo = buildSdkComboBox(this::onShowAllSelected, this::onSdkSelected);

    final PackagesNotificationPanel packagesNotificationPanel = new PyPackagesNotificationPanel();
    myPackagesPanel = new PyInstalledPackagesPanel(myProject, packagesNotificationPanel);
    myPackagesPanel.setShowGrid(false);
    boolean freeTier = PythonSdkUtil.isFreeTier();
    myPanelWithPromo = freeTier ? new PyPanelWithPromo(myPackagesPanel) : null;

    final PyCustomSdkUiProvider customUiProvider = PyCustomSdkUiProvider.getInstance();
    myDisposable = customUiProvider == null ? null : Disposer.newDisposable();
    final Pair<PyCustomSdkUiProvider, Disposable> customizer =
      customUiProvider == null ? null : new Pair<>(customUiProvider, myDisposable);

    final JButton additionalAction;
    additionalAction = new DropDownLink<>(PyBundle.message("active.sdk.dialog.link.add.interpreter.text"),
                                          link -> createAddInterpreterPopup(project, module, link, this::updateSdkListAndSelect));

    myMainPanel =
      buildPanel(project, mySdkCombo, additionalAction, freeTier ? myPanelWithPromo.getPanel() : myPackagesPanel, packagesNotificationPanel,
                 customizer);

    myInterpreterList = PyConfigurableInterpreterList.getInstance(myProject);
    myProjectSdksModel = myInterpreterList.getModel();
  }

  @ApiStatus.Internal
  public static @NotNull ListPopup createAddInterpreterPopup(@NotNull Project project,
                                                             @Nullable Module module,
                                                             @NotNull Component dataContextComponent,
                                                             @NotNull Consumer<Sdk> onSdkCreated) {
    DataContext dataContext = DataManager.getInstance().getDataContext(dataContextComponent);
    var moduleOrProject = (module != null) ? new ModuleOrProject.ModuleAndProject(module) : new ModuleOrProject.ProjectOnly(project);
    List<DialogAction> actions = AddInterpreterActions.collectAddInterpreterActions(moduleOrProject, onSdkCreated);
    return JBPopupFactory.getInstance().createActionGroupPopup(
      null,
      new DefaultActionGroup(actions),
      dataContext,
      JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
      false,
      null,
      -1,
      action -> false,
      null
    );
  }

  private static @NotNull ComboBox<Object> buildSdkComboBox(@NotNull Runnable onShowAllSelected,
                                                            @NotNull Runnable onSdkSelected) {
    final ComboBox<Object> result = new ComboBox<>() {
      @Override
      public void setSelectedItem(Object item) {
        if (getShowAll().equals(item)) {
          ApplicationManager.getApplication().invokeLater(onShowAllSelected);
        }
        else if (!PySdkListCellRenderer.SEPARATOR.equals(item)) {
          super.setSelectedItem(item);
        }
      }
    };

    result.addItemListener(
      e -> {
        if (e.getStateChange() == ItemEvent.SELECTED) onSdkSelected.run();
      }
    );

    ComboboxSpeedSearch.installOn(result);
    result.setPreferredSize(result.getPreferredSize()); // this line allows making `result` resizable
    return result;
  }

  private static @NotNull JButton buildDetailsButton(@NotNull ComboBox<?> sdkComboBox, @NotNull Consumer<JButton> onShowDetails) {
    final FixedSizeButton result = new FixedSizeButton(sdkComboBox.getPreferredSize().height);
    result.setIcon(AllIcons.General.GearPlain);
    result.addActionListener(e -> onShowDetails.accept(result));
    return result;
  }

  /**
   * @param additionalAction either the gear button for the old UI or the link "Add Interpreter" for the new UI
   */
  private static @NotNull JPanel buildPanel(@NotNull Project project,
                                            @NotNull ComboBox<?> sdkComboBox,
                                            @NotNull JComponent additionalAction,
                                            @NotNull JPanel promotionPanel,
                                            @NotNull PackagesNotificationPanel packagesNotificationPanel,
                                            @Nullable Pair<PyCustomSdkUiProvider, Disposable> customizer) {

    final JPanel result = new JPanel(new GridBagLayout());

    final GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = JBUI.insets(2);

    c.gridx = 0;
    c.gridy = 0;
    JLabel label = new JLabel(PyBundle.message("active.sdk.dialog.project.interpreter"));
    label.setLabelFor(sdkComboBox);
    result.add(label, c);

    c.gridx = 1;
    c.gridy = 0;
    c.weightx = 0.1;
    result.add(sdkComboBox, c);

    c.insets = JBUI.insets(2, 0, 2, 2);
    c.gridx = 2;
    c.gridy = 0;
    c.weightx = 0.0;
    result.add(additionalAction, c);

    if (customizer != null) {
      customizer.first.customizeActiveSdkPanel(project, sdkComboBox, result, c, customizer.second);
    }

    c.gridx = 0;
    c.gridy++;
    c.weighty = 1.;
    c.gridwidth = 3;
    c.gridheight = GridBagConstraints.RELATIVE;
    c.fill = GridBagConstraints.BOTH;
    result.add(promotionPanel, c);

    c.gridheight = GridBagConstraints.REMAINDER;
    c.gridx = 0;
    c.gridy++;
    c.gridwidth = 3;
    c.weighty = 0.;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.anchor = GridBagConstraints.SOUTH;

    result.add(packagesNotificationPanel.getComponent(), c);

    return result;
  }

  private void onShowAllSelected() {
    Sdk selectedSdk = PythonInterpreterConfigurable.openInDialog(myProject, myModule, getEditableSelectedSdk());
    onShowAllInterpretersDialogClosed(selectedSdk);
  }

  protected void onSdkSelected() {
    final Sdk sdk = getOriginalSelectedSdk();

    if (sdk != null) {
      // Non-null means we are in free tier mode, so must switch between packages and promo panel
      if (myPanelWithPromo != null) {
        boolean remote = isRemote(sdk);
        myPanelWithPromo.setPromoMode(remote);
        if (remote) {
          return;
        }
      }
    }

    refreshPackages(sdk);
  }

  protected void refreshPackages(@Nullable Sdk sdk) {
    final PyPackageManagers packageManagers = PyPackageManagers.getInstance();
    myPackagesPanel.updatePackages(sdk != null ? packageManagers.getManagementService(myProject, sdk) : null);
    myPackagesPanel.updateNotifications(sdk);
  }

  /**
   * @param selectedSdk the selected Python SDK before closing "Python Interpreters" dialog if the user clicked "OK" and {@code null} if the
   *                    user clicked "Cancel" button
   */
  private void onShowAllInterpretersDialogClosed(@Nullable Sdk selectedSdk) {
    if (selectedSdk != null) {
      updateSdkListAndSelect(selectedSdk);
    }
    else {
      // do not use `getOriginalSelectedSdk()` here since `model` won't find original sdk for selected item due to applying
      final Sdk currentSelectedSdk = getEditableSelectedSdk();

      if (currentSelectedSdk != null && myProjectSdksModel.findSdk(currentSelectedSdk.getName()) != null) {
        // nothing has been selected but previously selected sdk still exists, stay with it
        updateSdkListAndSelect(currentSelectedSdk);
      }
      else {
        // nothing has been selected but previously selected sdk removed, switch to `No interpreter`
        updateSdkListAndSelect(null);
      }
    }
  }

  @Override
  public JComponent createComponent() {
    return myMainPanel;
  }

  @Override
  public boolean isModified() {
    return !Comparing.equal(getSdk(), getOriginalSelectedSdk());
  }

  protected @Nullable Sdk getOriginalSelectedSdk() {
    final Sdk editableSdk = getEditableSelectedSdk();
    return editableSdk == null ? null : myProjectSdksModel.findSdk(editableSdk);
  }

  protected @Nullable Sdk getEditableSelectedSdk() {
    return (Sdk)mySdkCombo.getSelectedItem();
  }

  protected @Nullable Sdk getSdk() {
    Sdk sdk = null;
    if (myModule == null) {
      sdk = ProjectRootManager.getInstance(myProject).getProjectSdk();
    }
    else {
      sdk = ModuleRootManager.getInstance(myModule).getSdk();
    }

    if (sdk != null && PythonSdkUtil.isPythonSdk(sdk)) {
      return sdk;
    }

    return null;
  }

  @Override
  public void apply() {
    final Sdk selectedSdk = getOriginalSelectedSdk();
    if (selectedSdk != null) {
      ((PythonSdkType)selectedSdk.getSdkType()).setupSdkPaths(selectedSdk);
    }
    setSdk(selectedSdk);
  }

  protected void setSdk(@Nullable Sdk item) {
    // This function literally associates SDK with module and must be moved to the service
    final var currentSdk = getSdk();

    PyTransferredSdkRootsKt.removeTransferredRootsFromModulesWithInheritedSdk(myProject, currentSdk);
    PySdkExtKt.setPythonSdk(myProject, item);
    PyTransferredSdkRootsKt.transferRootsToModulesWithInheritedSdk(myProject, item);

    if (myModule != null) {
      PyTransferredSdkRootsKt.removeTransferredRoots(myModule, currentSdk);
      PySdkExtKt.setPythonSdk(myModule, item);
      PyTransferredSdkRootsKt.transferRoots(myModule, item);
    }
  }

  @Override
  public void reset() {
    Sdk sdk = getSdk();
    updateSdkListAndSelect(sdk);
  }

  protected @NotNull List<Sdk> getAvailableSdks() {
    return myInterpreterList.getAllPythonSdks(myProject, myModule, true);
  }

  private void updateSdkListAndSelect(@Nullable Sdk selectedSdk) {
    final List<Sdk> allPythonSdks = getAvailableSdks();

    final List<Object> items = new ArrayList<>();
    items.add(null);

    final Map<PyRenderedSdkType, List<Sdk>> moduleSdksByTypes =
      groupModuleSdksByTypes(allPythonSdks, myModule, sdk -> !PySdkExtKt.getSdkSeemsValid(sdk));

    final PyRenderedSdkType[] renderedSdkTypes = PyRenderedSdkType.values();
    for (int i = 0; i < renderedSdkTypes.length; i++) {
      final PyRenderedSdkType currentSdkType = renderedSdkTypes[i];

      if (moduleSdksByTypes.containsKey(currentSdkType)) {
        if (i != 0) items.add(PySdkListCellRenderer.SEPARATOR);
        items.addAll(moduleSdksByTypes.get(currentSdkType));
      }
    }

    items.add(PySdkListCellRenderer.SEPARATOR);
    items.add(getShowAll());

    mySdkCombo.setRenderer(new PySdkListCellRenderer());
    final Sdk selection = getEditableSdkUsingOriginal(selectedSdk);
    mySdkCombo.setModel(new CollectionComboBoxModel<>(items, selection));
    // The call of `setSelectedItem` is required to notify `PyPathMappingsUiProvider` about initial setting of `Sdk` via `setModel` above
    // Fragile as it is vulnerable to changes of `setSelectedItem` method in respect to processing `ActionEvent`
    mySdkCombo.setSelectedItem(selection);
    onSdkSelected();
  }

  protected @Nullable Sdk getEditableSdkUsingOriginal(@Nullable Sdk sdk) {
    return sdk == null ? null : myProjectSdksModel.findSdk(sdk.getName());
  }

  @Override
  public void disposeUIResources() {
    myInterpreterList.disposeModel();
    if (myDisposable != null) {
      Disposer.dispose(myDisposable);
    }
  }

  private class SdkAddedCallback implements NullableConsumer<Sdk> {
    @Override
    public void consume(Sdk sdk) {
      if (sdk != null && myProjectSdksModel.findSdk(sdk.getName()) == null) {
        myProjectSdksModel.addSdk(sdk);
        try {
          myProjectSdksModel.apply(null, true);
        }
        catch (ConfigurationException e) {
          LOG.error(e);
        }
        updateSdkListAndSelect(sdk);
      }
    }
  }

  private static String getShowAll() {
    return PyBundle.message("active.sdk.dialog.show.all.item");
  }
}
