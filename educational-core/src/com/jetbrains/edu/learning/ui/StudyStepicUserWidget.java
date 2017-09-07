package com.jetbrains.edu.learning.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.IconLikeCustomStatusBarWidget;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.ui.ClickListener;
import com.intellij.ui.awt.RelativePoint;
import com.jetbrains.edu.learning.StudySettings;
import com.jetbrains.edu.learning.StudyUtils;
import com.jetbrains.edu.learning.actions.StudySyncCourseAction;
import com.jetbrains.edu.learning.stepic.EduStepicConnector;
import com.jetbrains.edu.learning.stepic.StepicUser;
import icons.EducationalCoreIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.ArrayList;

public class StudyStepicUserWidget implements IconLikeCustomStatusBarWidget {
  public static final String ID = "StepicUser";
  private JLabel myComponent;

  public StudyStepicUserWidget(@NotNull Project project) {
    Icon icon = getWidgetIcon(StudySettings.getInstance().getUser());
    myComponent = new JLabel(icon);

    new ClickListener() {
      @Override
      public boolean onClick(@NotNull MouseEvent e, int clickCount) {
        StepicUser user = StudySettings.getInstance().getUser();
        ListPopup popup = createPopup(user, project);
        Dimension preferredSize = popup.getContent().getPreferredSize();
        Point point = new Point(0, 0);
        point = new Point(point.x - preferredSize.width, point.y - preferredSize.height);
        popup.show(new RelativePoint(myComponent, point));

        return true;
      }
    }.installOn(myComponent);
  }

  @NotNull
  @Override
  public String ID() {
    return ID;
  }

  @Nullable
  @Override
  public WidgetPresentation getPresentation(@NotNull PlatformType type) {
    return null;
  }

  @Override
  public void install(@NotNull StatusBar statusBar) {
  }

  @Override
  public void dispose() {
    Disposer.dispose(this);
  }

  public void update() {
    StepicUser user = StudySettings.getInstance().getUser();
    Icon icon = getWidgetIcon(user);
    myComponent.setIcon(icon);
  }

  private static Icon getWidgetIcon(StepicUser user) {
    Icon icon;
    if (user != null) {
      icon = EducationalCoreIcons.Stepik;
    }
    else {
      icon = EducationalCoreIcons.StepikOff;
    }
    return icon;
  }

  private ListPopup createPopup(@Nullable StepicUser user, @NotNull Project project) {
    String loginText = "Log in to Stepik";
    String logOutText = "Log out";
    String syncCourseStep = "Synchronize course";
    String userActionStep = user == null ? loginText : logOutText;

    ArrayList<String> steps = new ArrayList<>();
    if (user != null) {
      steps.add(syncCourseStep);
    }
    steps.add(userActionStep);

    BaseListPopupStep stepikStep = new BaseListPopupStep<String>(null, steps) {
      @Override
      public PopupStep onChosen(String selectedValue, boolean finalChoice) {
        return doFinalStep(() -> {
          if (syncCourseStep.equals(selectedValue)) {
            StudySyncCourseAction.doUpdate(project);
          } else {
            if (loginText.equals(selectedValue)) {
              EduStepicConnector.doAuthorize(StudyUtils::showOAuthDialog);
            } else if (logOutText.equals(selectedValue)) {
              StudySettings.getInstance().setUser(null);
            }
          }
        });
      }
    };
    return JBPopupFactory.getInstance().createListPopup(stepikStep);
  }

  @Override
  public JComponent getComponent() {
    return myComponent;
  }
}
