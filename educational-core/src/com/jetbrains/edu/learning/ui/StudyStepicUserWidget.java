package com.jetbrains.edu.learning.ui;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.IconLikeCustomStatusBarWidget;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.ui.ClickListener;
import com.intellij.ui.HoverHyperlinkLabel;
import com.intellij.ui.HyperlinkAdapter;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.ui.JBUI;
import com.jetbrains.edu.learning.StudySettings;
import com.jetbrains.edu.learning.StudyTaskManager;
import com.jetbrains.edu.learning.StudyUtils;
import com.jetbrains.edu.learning.actions.StudyUpdateRecommendationAction;
import com.jetbrains.edu.learning.courseFormat.Course;
import com.jetbrains.edu.learning.stepic.EduStepicConnector;
import com.jetbrains.edu.learning.stepic.EduStepicNames;
import com.jetbrains.edu.learning.stepic.StepicUser;
import icons.EducationalCoreIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;

public class StudyStepicUserWidget implements IconLikeCustomStatusBarWidget {
  public static final String ID = "StepicUser";
  private JLabel myComponent;
  private Project myProject;
  private boolean myNewSolvedTasks;


  public StudyStepicUserWidget(Project project) {
    myProject = project;
    StepicUser user = StudySettings.getInstance().getUser();
    Course course = StudyTaskManager.getInstance(myProject).getCourse();
    assert course != null;
    myNewSolvedTasks = EduStepicConnector.hasNewSolvedTasks(course);
    Icon icon = getWidgetIcon(user, myNewSolvedTasks);
    myComponent = new JLabel(icon);

    new ClickListener() {
      @Override
      public boolean onClick(@NotNull MouseEvent e, int clickCount) {
        Point point = new Point(0, 0);
        StepicUserComponent component = new StepicUserComponent(StudySettings.getInstance().getUser(), myNewSolvedTasks);
        final Dimension dimension = component.getPreferredSize();
        point = new Point(point.x - dimension.width, point.y - dimension.height);
        component.showComponent(new RelativePoint(e.getComponent(), point));
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
    Course course = StudyTaskManager.getInstance(myProject).getCourse();
    assert course != null;
    myNewSolvedTasks = EduStepicConnector.hasNewSolvedTasks(course);
    Icon icon = getWidgetIcon(user, myNewSolvedTasks);
    myComponent.setIcon(icon);
  }

  private static Icon getWidgetIcon(StepicUser user, boolean newSolvedTasks) {
    Icon icon;
    if (user != null) {
      icon = newSolvedTasks ? EducationalCoreIcons.StepikRefresh : EducationalCoreIcons.Stepik;
    }
    else {
      icon = EducationalCoreIcons.StepikOff;
    }
    return icon;
  }

  @Override
  public JComponent getComponent() {
    return myComponent;
  }


  private class StepicUserComponent extends JPanel {
    private static final int myLeftMargin = 10;
    private static final int myTopMargin = 6;
    private static final int myActionLabelIndent = 260;
    private JBPopup myPopup;

    public StepicUserComponent(@Nullable StepicUser user, boolean newSolvedTasks) {
      super();
      BorderLayout layout = new BorderLayout();
      layout.setVgap(10);
      setLayout(layout);

      if (user == null) {
        JPanel statusPanel = createTextPanel("You're not logged in", null);
        JPanel actionPanel = createActionPanel("Log in to Stepik", createAuthorizeUserListener());
        add(constructPanel(statusPanel, actionPanel), BorderLayout.PAGE_START);
      }
      else {
        String firstName = user.getFirstName();
        String lastName = user.getLastName();
        String statusText;
        if (firstName == null || lastName == null || firstName.isEmpty() || lastName.isEmpty()) {
          statusText = "You're logged in";
        }
        else {
          statusText = "<html>You're logged in as <a href=\"\">" + firstName + " " + lastName + "</a></html>";
        }
        if (myNewSolvedTasks) {
          String loadSolutionText = "<html> <a href=\"\">Load</a> solutions from Stepik</html>";
          JPanel statusPanel = createTextPanel(statusText, createOpenProfileListener(user.getId()));
          JPanel loadSolutionsPanel = createTextPanel(loadSolutionText, createLoadSolutionsListener());
          JPanel actionPanel = createActionPanel("Log out", createLogoutListener());
          add(constructPanel(statusPanel, loadSolutionsPanel, actionPanel), BorderLayout.PAGE_START);
        }
        else {
          JPanel statusPanel = createTextPanel("You're not logged in", null);
          JPanel actionPanel = createActionPanel("Log in to Stepik", createAuthorizeUserListener());
          add(constructPanel(statusPanel, actionPanel), BorderLayout.PAGE_START);
        }
      }
    }

    @NotNull
    private HyperlinkAdapter createAuthorizeUserListener() {
      return new HyperlinkAdapter() {
        @Override
        protected void hyperlinkActivated(HyperlinkEvent e) {
          EduStepicConnector.doAuthorize(() -> StudyUtils.showOAuthDialog());
          myPopup.cancel();
        }
      };
    }

    @NotNull
    private HyperlinkAdapter createLogoutListener() {
      return new HyperlinkAdapter() {
        @Override
        protected void hyperlinkActivated(HyperlinkEvent e) {
          StudySettings.getInstance().setUser(null);
          myPopup.cancel();
        }
      };
    }

    private MouseAdapter createOpenProfileListener(int userId) {
      return new MouseAdapter() {
        @Override
        public void mouseClicked(MouseEvent e) {
          if (e.getClickCount() == 1) {
            BrowserUtil.browse(EduStepicNames.STEPIC_URL + EduStepicNames.USERS + userId);
            myPopup.cancel();
          }
        }
      };
    }

    private MouseAdapter createLoadSolutionsListener() {
      return new MouseAdapter() {
        @Override
        public void mouseClicked(MouseEvent e) {
          if (e.getClickCount() == 1) {
            if (StudyUpdateRecommendationAction.doUpdate(myProject)) {
              myNewSolvedTasks = false;
              myComponent.setIcon(getWidgetIcon(StudySettings.getInstance().getUser(), myNewSolvedTasks));
            }
            myPopup.cancel();
          }
        }
      };
    }

    private JPanel constructPanel(JPanel ... panels) {
      JPanel mainPanel = new JPanel();
      BoxLayout layout = new BoxLayout(mainPanel, BoxLayout.PAGE_AXIS);
      mainPanel.setLayout(layout);
      for (JPanel panel : panels) {
        mainPanel.add(panel);
        mainPanel.add(Box.createVerticalStrut(6));
      }

      return mainPanel;
    }

    @NotNull
    private JPanel createTextPanel(@NotNull String statusText, @Nullable MouseListener listener) {
      JPanel statusPanel = new JPanel(new BorderLayout());
      JLabel statusLabel = new JLabel(statusText);
      statusLabel.addMouseListener(listener);
      statusPanel.add(Box.createVerticalStrut(myTopMargin), BorderLayout.PAGE_START);
      statusPanel.add(Box.createHorizontalStrut(myLeftMargin), BorderLayout.WEST);
      statusPanel.add(statusLabel, BorderLayout.CENTER);
      return statusPanel;
    }

    @NotNull
    private JPanel createActionPanel(@NotNull String actionLabelText, @NotNull HyperlinkAdapter listener) {
      JPanel actionPanel = new JPanel(new BorderLayout());
      HoverHyperlinkLabel actionLabel = new HoverHyperlinkLabel(actionLabelText);
      actionLabel.addHyperlinkListener(listener);
      actionPanel.add(Box.createHorizontalStrut(myActionLabelIndent), BorderLayout.LINE_START);
      actionPanel.add(actionLabel, BorderLayout.CENTER);
      actionPanel.add(Box.createHorizontalStrut(myLeftMargin), BorderLayout.EAST);
      return actionPanel;
    }

    @Override
    public Dimension getPreferredSize() {
      final Dimension preferredSize = super.getPreferredSize();
      final int width = JBUI.scale(300);
      if (preferredSize.width < width){
        preferredSize.width = width;
      }
      return preferredSize;
    }

    public void showComponent(RelativePoint point) {
      myPopup = JBPopupFactory.getInstance().createComponentPopupBuilder(this, this)
        .setRequestFocus(true)
        .setCancelOnOtherWindowOpen(true)
        .setCancelOnClickOutside(true)
        .setShowBorder(true)
        .createPopup();

      Disposer.register(ApplicationManager.getApplication(), new Disposable() {
        @Override
        public void dispose() {
          Disposer.dispose(myPopup);
        }
      });

      myPopup.show(point);
    }
  }
}
