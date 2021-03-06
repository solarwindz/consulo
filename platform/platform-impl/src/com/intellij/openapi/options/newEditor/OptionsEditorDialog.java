/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.options.newEditor;

import com.intellij.CommonBundle;
import com.intellij.ide.ui.search.SearchUtil;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.help.HelpManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.DumbModePermission;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import consulo.ui.WholeWestDialogWrapper;
import com.intellij.openapi.util.ActionCallback;
import com.intellij.openapi.util.Couple;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.border.CustomLineBorder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.components.BorderLayoutPanel;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import consulo.annotations.RequiredDispatchThread;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class OptionsEditorDialog extends WholeWestDialogWrapper implements DataProvider{

  private Project myProject;
  private Configurable[] myConfigurables;
  private Configurable myPreselected;
  private OptionsEditor myEditor;

  private ApplyAction myApplyAction;
  public static final String DIMENSION_KEY = "OptionsEditor";
  @NonNls static final String LAST_SELECTED_CONFIGURABLE = "options.lastSelected";

  /** This constructor should be eliminated after the new modality approach
   *  will have been checked. See a {@code Registry} key ide.mac.modalDialogsOnFullscreen
   *  @deprecated
   */
  public OptionsEditorDialog(Project project, Configurable[] configurables,
                             @Nullable Configurable preselectedConfigurable, boolean applicationModalIfPossible) {
    super(true, applicationModalIfPossible);
    init(project, configurables, preselectedConfigurable != null ? preselectedConfigurable : findLastSavedConfigurable(configurables, project));
  }

  /** This constructor should be eliminated after the new modality approach
   *  will have been checked. See a {@code Registry} key ide.mac.modalDialogsOnFullscreen
   *  @deprecated
   */
  public OptionsEditorDialog(Project project, Configurable[] configurables,
                             @NotNull String preselectedConfigurableDisplayName, boolean applicationModalIfPossible) {
    super(true, applicationModalIfPossible);
    init(project, configurables, getPreselectedByDisplayName(configurables, preselectedConfigurableDisplayName, project));
  }

  public OptionsEditorDialog(Project project, Configurable[] configurables, @Nullable Configurable preselectedConfigurable) {
    super(project, true);
    init(project, configurables, preselectedConfigurable != null ? preselectedConfigurable : findLastSavedConfigurable(configurables, project));
  }

  public OptionsEditorDialog(Project project, Configurable[] configurables, @NotNull String preselectedConfigurableDisplayName) {
    super(project, true);
    init(project, configurables, getPreselectedByDisplayName(configurables, preselectedConfigurableDisplayName, project));
  }

  private void init(final Project project, final Configurable[] configurables, @Nullable final Configurable preselected) {
    myProject = project;
    myConfigurables = configurables;
    myPreselected = preselected;

    setTitle(CommonBundle.settingsTitle());

    init();
  }

  @Nullable
  private static Configurable getPreselectedByDisplayName(final Configurable[] configurables, final String preselectedConfigurableDisplayName,
                                                   final Project project) {
    Configurable result = findPreselectedByDisplayName(preselectedConfigurableDisplayName, configurables);

    return result == null ? findLastSavedConfigurable(configurables, project) : result;
  }

  @Override
  public boolean isTypeAheadEnabled() {
    return true;
  }

  @NotNull
  @Override
  public String getSplitterKey() {
    return OptionsEditor.MAIN_SPLITTER_PROPORTION;
  }

  @Override
  public Dimension getDefaultSize() {
    return new Dimension(1028, 500);
  }

  @Nullable
  @Override
  protected Border createContentPaneBorder() {
    return JBUI.Borders.empty();
  }

  @Nullable
  @Override
  protected JComponent createSouthPanel() {
    JComponent southPanel = super.createSouthPanel();
    if(southPanel != null) {
      southPanel.setBorder(JBUI.Borders.empty(ourDefaultBorderInsets));
      BorderLayoutPanel borderLayoutPanel = JBUI.Panels.simplePanel(southPanel);
      borderLayoutPanel.setBorder(new CustomLineBorder(JBUI.scale(1), 0, 0, 0));
      return borderLayoutPanel;
    }
    return null;
  }

  @RequiredDispatchThread
  @NotNull
  @Override
  public Couple<JComponent> createSplitterComponents(JPanel rootPanel) {
    myEditor = new OptionsEditor(myProject, myConfigurables, myPreselected, rootPanel);
    myEditor.getContext().addColleague(new OptionsEditorColleague.Adapter() {
      @Override
      public ActionCallback onModifiedAdded(final Configurable configurable) {
        updateStatus();
        return new ActionCallback.Done();
      }

      @Override
      public ActionCallback onModifiedRemoved(final Configurable configurable) {
        updateStatus();
        return new ActionCallback.Done();
      }

      @Override
      public ActionCallback onErrorsChanged() {
        updateStatus();
        return new ActionCallback.Done();
      }
    });
    Disposer.register(myDisposable, myEditor);
    return Couple.<JComponent>of(myEditor.getLeftSide(), myEditor.getRightSide());
  }

  public boolean updateStatus() {
    myApplyAction.setEnabled(myEditor.canApply());

    final Map<Configurable,ConfigurationException> errors = myEditor.getContext().getErrors();
    if (errors.size() == 0) {
      setErrorText(null);
    } else {
      String text = "Changes were not applied because of an error";

      final String errorMessage = getErrorMessage(errors);
      if (errorMessage != null) {
        text += "<br>" + errorMessage;
      }

      setErrorText(text);
    }

    return errors.size() == 0;
  }

  @Nullable
  private static String getErrorMessage(final Map<Configurable, ConfigurationException> errors) {
    final Collection<ConfigurationException> values = errors.values();
    final ConfigurationException[] exceptions = values.toArray(new ConfigurationException[values.size()]);
    if (exceptions.length > 0) {
      return exceptions[0].getMessage();
    }
    return null;
  }

  @Override
  protected String getDimensionServiceKey() {
    return DIMENSION_KEY;
  }

  @Override
  public void show() {
    DumbService.allowStartingDumbModeInside(DumbModePermission.MAY_START_BACKGROUND, new Runnable() {
      @Override
      public void run() {
        OptionsEditorDialog.super.show();
      }
    });
  }

  @Override
  protected void doOKAction() {
    myEditor.flushModifications();

    if (myEditor.canApply()) {
      myEditor.apply();
      if (!updateStatus()) return;
    }

    saveCurrentConfigurable();

    ApplicationManager.getApplication().saveAll();

    super.doOKAction();
  }


  private void saveCurrentConfigurable() {
    final Configurable current = myEditor.getContext().getCurrentConfigurable();
    if (current == null) return;

    final PropertiesComponent props = PropertiesComponent.getInstance(myProject);

    if (current instanceof SearchableConfigurable) {
      props.setValue(LAST_SELECTED_CONFIGURABLE, ((SearchableConfigurable)current).getId());
    } else {
      props.setValue(LAST_SELECTED_CONFIGURABLE, current.getClass().getName());
    }
  }

  @Nullable
  private static Configurable findLastSavedConfigurable(Configurable[] configurables, final Project project) {
    final String id = PropertiesComponent.getInstance(project).getValue(LAST_SELECTED_CONFIGURABLE);
    if (id == null) return null;

    return findConfigurableInGroups(id, configurables);
  }

  @Nullable
  private static Configurable findConfigurableInGroups(String id, Configurable[] configurables) {
    // avoid unnecessary group expand: check top-level configurables in all groups before looking at children
    for (Configurable c : configurables) {
      if (c instanceof SearchableConfigurable && id.equals(((SearchableConfigurable)c).getId())) {
        return c;
      } else if (id.equals(c.getClass().getName())) {
        return c;
      }
    }

    for (Configurable c : configurables) {
      if (c instanceof Configurable.Composite) {
        Configurable result = findConfigurableInGroups(id, ((Configurable.Composite)c).getConfigurables());
        if (result != null) {
          return result;
        }
      }
    }
    return null;
  }

  @Nullable
  private static Configurable findPreselectedByDisplayName(final String preselectedConfigurableDisplayName, Configurable[] configurables) {
    final List<Configurable> all = SearchUtil.expand(configurables);
    for (Configurable each : all) {
      if (preselectedConfigurableDisplayName.equals(each.getDisplayName())) return each;
    }
    return null;
  }

  @Override
  public void doCancelAction(final AWTEvent source) {
    if (source instanceof KeyEvent || source instanceof ActionEvent) {
      if (myEditor.getContext().isHoldingFilter()) {
        myEditor.clearFilter();
        return;
      }
    }

    super.doCancelAction(source);
  }

  @Override
  public void doCancelAction() {
    saveCurrentConfigurable();
    super.doCancelAction();
  }

  @NotNull
  @Override
  protected Action[] createActions() {
    myApplyAction = new ApplyAction();
    return new Action[] {getOKAction(), getCancelAction(), myApplyAction, getHelpAction()};
  }

  @Override
  protected void doHelpAction() {
    final String topic = myEditor.getHelpTopic();
    if (topic != null) {
      HelpManager.getInstance().invokeHelp(topic);
    }
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return myEditor.getPreferredFocusedComponent();
  }

  @Override
  public Object getData(@NonNls String dataId) {
    if (OptionsEditor.KEY.is(dataId)) {
      return myEditor;
    }
    return null;
  }

  private class ApplyAction extends AbstractAction {
    public ApplyAction() {
      super(CommonBundle.getApplyButtonText());
      setEnabled(false);
    }

    @Override
    public void actionPerformed(final ActionEvent e) {
      myEditor.apply();
      myEditor.repaint();
    }
  }

}
