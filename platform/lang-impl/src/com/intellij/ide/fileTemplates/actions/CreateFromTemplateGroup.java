// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package com.intellij.ide.fileTemplates.actions;

import com.intellij.ide.IdeBundle;
import com.intellij.ide.IdeView;
import com.intellij.ide.actions.CreateFileFromTemplateAction;
import com.intellij.ide.actions.CreateFileFromTemplateDialog;
import com.intellij.ide.actions.EditFileTemplatesAction;
import com.intellij.ide.fileTemplates.CreateFromTemplateActionReplacer;
import com.intellij.ide.fileTemplates.FileTemplate;
import com.intellij.ide.fileTemplates.FileTemplateManager;
import com.intellij.ide.fileTemplates.FileTemplateUtil;
import com.intellij.ide.fileTemplates.impl.FileTemplateBase;
import com.intellij.ide.fileTemplates.ui.SelectTemplateDialog;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.fileTypes.StdFileTypes;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NlsActions;
import com.intellij.psi.PsiDirectory;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

@ApiStatus.Internal
public final class CreateFromTemplateGroup extends ActionGroup implements DumbAware {
  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @Override
  public AnAction @NotNull [] getChildren(@Nullable AnActionEvent e) {
    if (e == null) return EMPTY_ARRAY;

    Project project = e.getProject();
    if (project == null || project.isDisposed()) return EMPTY_ARRAY;

    FileTemplateManager manager = FileTemplateManager.getInstance(project);
    FileTemplate[] templates = manager.getAllTemplates();

    boolean showAll = templates.length <= FileTemplateManager.RECENT_TEMPLATES_SIZE;
    if (!showAll) {
      Collection<String> recentNames = manager.getRecentNames();
      templates = new FileTemplate[recentNames.size()];
      int i = 0;
      for (String name : recentNames) {
        templates[i] = manager.getTemplate(name);
        i++;
      }
    }

    Arrays.sort(templates, (template1, template2) -> {
      // java first
      if (template1.isTemplateOfType(StdFileTypes.JAVA) && !template2.isTemplateOfType(StdFileTypes.JAVA)) {
        return -1;
      }
      if (template2.isTemplateOfType(StdFileTypes.JAVA) && !template1.isTemplateOfType(StdFileTypes.JAVA)) {
        return 1;
      }

      // group by type
      int i = template1.getExtension().compareTo(template2.getExtension());
      if (i != 0) {
        return i;
      }

      // group by name if same type
      return template1.getName().compareTo(template2.getName());
    });
    List<AnAction> result = new ArrayList<>();

    for (FileTemplate template : templates) {
      if (!FileTemplateBase.isChild(template) && canCreateFromTemplate(e, template)) {
        AnAction action = replaceAction(template);
        if (action == null) {
          action = new CreateFromTemplateAction(template);
        }
        result.add(action);
      }
    }

    if (!result.isEmpty() || !showAll) {
      if (!showAll) {
        result.add(new CreateFromTemplatesAction(IdeBundle.message("action.from.file.template")));
      }

      result.add(Separator.getInstance());
      result.add(new EditFileTemplatesAction(IdeBundle.message("action.edit.file.templates")));
    }

    return result.toArray(EMPTY_ARRAY);
  }

  private static AnAction replaceAction(FileTemplate template) {
    for (CreateFromTemplateActionReplacer actionFactory : CreateFromTemplateActionReplacer.CREATE_FROM_TEMPLATE_REPLACER.getExtensionList()) {
      AnAction action = actionFactory.replaceCreateFromFileTemplateAction(template);
      if (action != null) {
        return action;
      }
    }
    return null;
  }

  static boolean canCreateFromTemplate(AnActionEvent e, @NotNull FileTemplate template) {
    if (e == null) return false;
    DataContext dataContext = e.getDataContext();
    IdeView view = LangDataKeys.IDE_VIEW.getData(dataContext);
    if (view == null) return false;

    PsiDirectory[] dirs = view.getDirectories();
    if (dirs.length == 0) return false;

    return FileTemplateUtil.canCreateFromTemplate(dirs, template);
  }

  private static final class CreateFromTemplatesAction extends CreateFromTemplateActionBase {

    CreateFromTemplatesAction(@NlsActions.ActionText String title) {
      super(title, null, null);
    }

    @Override
    protected AnAction getReplacedAction(FileTemplate template) {
      return replaceAction(template);
    }

    @Override
    protected FileTemplate getTemplate(Project project, PsiDirectory dir) {
      SelectTemplateDialog dialog = new SelectTemplateDialog(project, dir);
      dialog.show();
      return dialog.getSelectedTemplate();
    }
  }
}

final class CreateFromSimpleTemplateAction extends CreateFileFromTemplateAction implements DumbAware, CreateFromBundledTemplateAction {
  @NotNull private final FileTemplate myTemplate;

  CreateFromSimpleTemplateAction(@NotNull FileTemplate template) {
    super(template.getName(), null, FileTemplateUtil.getIcon(template));
    myTemplate = template;
  }

  @Override
  protected void buildDialog(@NotNull Project project,
                             @NotNull PsiDirectory directory,
                             CreateFileFromTemplateDialog.@NotNull Builder builder) {
    builder
      .setTitle(myTemplate.getName())
      .addKind(myTemplate.getName(),
               FileTemplateUtil.getIcon(myTemplate),
               myTemplate.getName());
  }

  @Override
  protected String getActionName(PsiDirectory directory, @NotNull String newName, String templateName) {
    return myTemplate.getName();
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    super.update(e);
    Presentation presentation = e.getPresentation();
    boolean isEnabled = CreateFromTemplateGroup.canCreateFromTemplate(e, myTemplate);
    presentation.setEnabledAndVisible(isEnabled);
  }

  @Override
  public @NotNull FileTemplate getTemplate() {
    return myTemplate;
  }
}