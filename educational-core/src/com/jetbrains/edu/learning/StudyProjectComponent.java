package com.jetbrains.edu.learning;

import com.intellij.ide.ui.UISettings;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.AnActionListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.colors.EditorColorsListener;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.impl.EditorImpl;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.keymap.Keymap;
import com.intellij.openapi.keymap.ex.KeymapManagerEx;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileEvent;
import com.intellij.openapi.vfs.VirtualFileListener;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.panels.NonOpaquePanel;
import com.intellij.util.containers.hash.HashMap;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.jetbrains.edu.learning.actions.StudyActionWithShortcut;
import com.jetbrains.edu.learning.actions.StudyNextWindowAction;
import com.jetbrains.edu.learning.actions.StudyPrevWindowAction;
import com.jetbrains.edu.learning.actions.StudySyncCourseAction;
import com.jetbrains.edu.learning.core.EduNames;
import com.jetbrains.edu.learning.core.EduUtils;
import com.jetbrains.edu.learning.courseFormat.*;
import com.jetbrains.edu.learning.courseFormat.tasks.Task;
import com.jetbrains.edu.learning.editor.StudyEditor;
import com.jetbrains.edu.learning.editor.StudyEditorFactoryListener;
import com.jetbrains.edu.learning.statistics.EduUsagesCollector;
import com.jetbrains.edu.learning.stepic.EduStepicConnector;
import com.jetbrains.edu.learning.stepic.StepicWrappers;
import com.jetbrains.edu.learning.ui.StudyStepicUserWidget;
import com.jetbrains.edu.learning.ui.StudyToolWindow;
import com.jetbrains.edu.learning.ui.StudyToolWindowFactory;
import javafx.application.Platform;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.jetbrains.edu.learning.StudyUtils.execCancelable;
import static com.jetbrains.edu.learning.StudyUtils.navigateToStep;
import static com.jetbrains.edu.learning.stepic.EduStepicNames.STEP_ID;


public class StudyProjectComponent implements ProjectComponent {
  private static final Logger LOG = Logger.getInstance(StudyProjectComponent.class.getName());
  private final Project project;
  private FileCreatedByUserListener myListener;
  private final Map<Keymap, List<Pair<String, String>>> myDeletedShortcuts = new HashMap<>();
  private MessageBusConnection myBusConnection;
  private static final Key<Boolean> IS_TO_UPDATE = Key.create("IS_TO_UPDATE");

  private StudyProjectComponent(@NotNull final Project project) {
    this.project = project;
  }

  @Override
  public void projectOpened() {
    // Check if user has javafx lib in his JDK. Now bundled JDK doesn't have this lib inside.
    if (StudyUtils.hasJavaFx()) {
      Platform.setImplicitExit(false);
    }

    MessageBusConnection connect = ApplicationManager.getApplication().getMessageBus().connect();
    connect.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, taskUpdatingListener());

    StartupManager.getInstance(project).runWhenProjectIsInitialized(
      () -> {
        Course course = StudyTaskManager.getInstance(project).getCourse();
        if (course == null) {
          LOG.warn("Opened project is with null course");
          return;
        }

        if (!course.isAdaptive() && !course.isUpToDate()) {
          updateAvailable(course);
        }

        ApplicationManager.getApplication().invokeLater(() -> ProgressManager.getInstance().run(new com.intellij.openapi.progress.Task.Backgroundable(project, "Loading Solutions") {
          @Override
          public void run(@NotNull ProgressIndicator indicator) {
            assert myProject != null;
            ArrayList<Task> tasksToUpdate = getSolvedTasksAndUpdateStatus(course, StudyUtils.getSelectedTaskFile(myProject), project);

            for (Task task : tasksToUpdate) {
              boolean isSolved = task.getStatus() == StudyStatus.Solved;
              StudySyncCourseAction.updateTaskSolution(myProject, task, isSolved);
            }

            connect.disconnect();
            if (!tasksToUpdate.isEmpty()) {
              TaskFile selectedTaskFile = StudyUtils.getSelectedTaskFile(myProject);
              ApplicationManager.getApplication().invokeLater(() -> {
                VirtualFileManager.getInstance().refreshWithoutFileWatcher(false);
                StudySyncCourseAction.openTask(myProject, selectedTaskFile);
              });
            }
          }
        }));

        StudyUtils.registerStudyToolWindow(course, project);
        addStepicWidget();
        selectStep(course);

        ApplicationManager.getApplication().invokeLater(() -> ApplicationManager.getApplication().runWriteAction(() -> {
            UISettings instance = UISettings.getInstance();
            instance.setHideToolStripes(false);
            instance.fireUISettingsChanged();
            registerShortcuts();
            EduUsagesCollector.projectTypeOpened(course.isAdaptive() ? EduNames.ADAPTIVE : EduNames.STUDY);
          }));
      }
    );

    myBusConnection = ApplicationManager.getApplication().getMessageBus().connect();
    myBusConnection.subscribe(EditorColorsManager.TOPIC, new EditorColorsListener() {
      @Override
      public void globalSchemeChange(EditorColorsScheme scheme) {
        final StudyToolWindow toolWindow = StudyUtils.getStudyToolWindow(project);
        if (toolWindow != null) {
          toolWindow.updateFonts(project);
        }
      }
    });
  }

  @NotNull
  private FileEditorManagerListener taskUpdatingListener() {
    return new FileEditorManagerListener() {
      @Override
      public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        StudyEditor studyEditor = StudyUtils.getSelectedStudyEditor(project);
        TaskFile taskFile = StudyUtils.getTaskFile(project, file);
        if (studyEditor != null && taskFile != null) {
          Task task = taskFile.getTask();
          VirtualFile taskDir = task.getTaskDir(project);
          if (taskDir != null) {
            Boolean toUpdate = taskDir.getUserData(IS_TO_UPDATE);
            if (toUpdate != null && toUpdate) {
              disableEditor(StudyProjectComponent.this.project);
            }
          }
        }
      }
    };
  }

  private static ArrayList<Task> getSolvedTasksAndUpdateStatus(@NotNull Course course, @Nullable TaskFile selectedTaskFile, @NotNull Project project) {
    Task selectedTask = null;
    if (selectedTaskFile != null) {
      selectedTask = selectedTaskFile.getTask();
    }
    ArrayList<Task> taskToUpdate = new ArrayList<>();
    for (Lesson lesson : course.getLessons()) {
      List<Task> tasks = lesson.getTaskList();
      int[] ids = tasks.stream().mapToInt(Task::getStepId).toArray();
      List<StepicWrappers.StepSource> steps = EduStepicConnector.getSteps(ids);
      if (steps != null) {
        String[] progresses = steps.stream().map(step -> step.progress).toArray(String[]::new);
        Boolean[] solved = EduStepicConnector.isTasksSolved(progresses);
        if (solved == null) return taskToUpdate;
        for (int i = 0; i < tasks.size(); i++) {
          Boolean isSolved = solved[i];
          Task task = tasks.get(i);
          if (isSolved != null) {
            if (isToUpdate(isSolved, task)) {
              task.setStatus(isSolved ? StudyStatus.Solved : StudyStatus.Failed);
              if (task.equals(selectedTask)) {
                disableEditor(project);
              }
              VirtualFile taskDir = task.getTaskDir(project);
              if (taskDir != null) {
                taskDir.putUserData(IS_TO_UPDATE, true);
              }
              taskToUpdate.add(tasks.get(i));
            }
          }
        }
      }
    }
    return taskToUpdate;
  }

  private static boolean isToUpdate(@NotNull Boolean isSolved, @NotNull Task task) {
    if (isSolved && task.getStatus() != StudyStatus.Solved) {
      return true;
    }
    else if (!isSolved) {
      try {
        List<StepicWrappers.SolutionFile> solutionFiles = EduStepicConnector.getLastSubmission(String.valueOf(task.getStepId()));
        if (!solutionFiles.isEmpty()) {
          return true;
        }
      }
      catch (IOException e) {
        LOG.warn(e.getMessage());
      }
    }

    return false;
  }

  private static void disableEditor(Project project) {
    StudyEditor studyEditor = StudyUtils.getSelectedStudyEditor(project);

    if (studyEditor != null) {
      Editor editor = studyEditor.getEditor();
      editor.setHeaderComponent(createNotificationPanel());
      ((EditorImpl)editor).setViewer(true);
      ((EditorImpl)editor).setCaretEnabled(false);
      ((EditorImpl)editor).setShowPlaceholderWhenFocused(false);
    }
  }

  @NotNull
  private static JPanel createNotificationPanel() {
    JPanel panel = new NonOpaquePanel(new BorderLayout());
    JBLabel solution = new JBLabel("Loading solution");
    panel.setBackground(UIUtil.getToolTipBackground());
    panel.add(solution, BorderLayout.CENTER);
    panel.setBorder(JBUI.Borders.empty(10, 10));
    return panel;
  }

  private void addStepicWidget() {
    StudyStepicUserWidget widget = StudyUtils.getStepicWidget();
    StatusBar statusBar = WindowManager.getInstance().getStatusBar(project);
    if (widget != null) {
      statusBar.removeWidget(StudyStepicUserWidget.ID);
    }
    statusBar.addWidget(new StudyStepicUserWidget(project), "before Position");
  }

  private void selectStep(@NotNull Course course) {
    int stepId = PropertiesComponent.getInstance().getInt(STEP_ID, 0);
    if (stepId != 0) {
      navigateToStep(project, course, stepId);
    }
  }

  private void updateAvailable(Course course) {
    final Notification notification =
      new Notification("Update.course", "Course Updates", "Course is ready to <a href=\"update\">update</a>", NotificationType.INFORMATION,
                       new NotificationListener() {
                         @Override
                         public void hyperlinkUpdate(@NotNull Notification notification, @NotNull HyperlinkEvent event) {
                           FileEditorManagerEx.getInstanceEx(project).closeAllFiles();

                           ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
                             ProgressManager.getInstance().getProgressIndicator().setIndeterminate(true);
                             return execCancelable(() -> {
                               EduStepicConnector.updateCourse(StudyProjectComponent.this.project);
                               return true;
                             });
                           }, "Updating Course", true, project);
                           EduUtils.synchronize();
                           course.setUpdated();
                         }
                       });
    notification.notify(project);
  }

  private void registerShortcuts() {
    StudyToolWindow window = StudyUtils.getStudyToolWindow(project);
    if (window != null) {
      List<AnAction> actionsOnToolbar = window.getActions(true);
      for (AnAction action : actionsOnToolbar) {
        if (action instanceof StudyActionWithShortcut) {
          String id = ((StudyActionWithShortcut)action).getActionId();
          String[] shortcuts = ((StudyActionWithShortcut)action).getShortcuts();
          if (shortcuts != null) {
            addShortcut(id, shortcuts);
          }
        }
      }
      addShortcut(StudyNextWindowAction.ACTION_ID, new String[]{StudyNextWindowAction.SHORTCUT, StudyNextWindowAction.SHORTCUT2});
      addShortcut(StudyPrevWindowAction.ACTION_ID, new String[]{StudyPrevWindowAction.SHORTCUT});
    }
  }

  private void addShortcut(@NotNull final String actionIdString, @NotNull final String[] shortcuts) {
    KeymapManagerEx keymapManager = KeymapManagerEx.getInstanceEx();
    for (Keymap keymap : keymapManager.getAllKeymaps()) {
      List<Pair<String, String>> pairs = myDeletedShortcuts.get(keymap);
      if (pairs == null) {
        pairs = new ArrayList<>();
        myDeletedShortcuts.put(keymap, pairs);
      }
      for (String shortcutString : shortcuts) {
        Shortcut studyActionShortcut = new KeyboardShortcut(KeyStroke.getKeyStroke(shortcutString), null);
        String[] actionsIds = keymap.getActionIds(studyActionShortcut);
        for (String actionId : actionsIds) {
          pairs.add(Pair.create(actionId, shortcutString));
          keymap.removeShortcut(actionId, studyActionShortcut);
        }
        keymap.addShortcut(actionIdString, studyActionShortcut);
      }
    }
  }

  @Override
  public void projectClosed() {
    final Course course = StudyTaskManager.getInstance(project).getCourse();
    if (course != null) {
      final ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(StudyToolWindowFactory.STUDY_TOOL_WINDOW);
      if (toolWindow != null) {
        toolWindow.getContentManager().removeAllContents(false);
      }
      KeymapManagerEx keymapManager = KeymapManagerEx.getInstanceEx();
      for (Keymap keymap : keymapManager.getAllKeymaps()) {
        List<Pair<String, String>> pairs = myDeletedShortcuts.get(keymap);
        if (pairs != null && !pairs.isEmpty()) {
          for (Pair<String, String> actionShortcut : pairs) {
            keymap.addShortcut(actionShortcut.first, new KeyboardShortcut(KeyStroke.getKeyStroke(actionShortcut.second), null));
          }
        }
      }
    }
    myListener = null;
  }

  @Override
  public void initComponent() {
    EditorFactory.getInstance().addEditorFactoryListener(new StudyEditorFactoryListener(), project);
    ActionManager.getInstance().addAnActionListener(new AnActionListener() {
      @Override
      public void beforeActionPerformed(AnAction action, DataContext dataContext, AnActionEvent event) {
        AnAction[] newGroupActions = ((ActionGroup)ActionManager.getInstance().getAction("NewGroup")).getChildren(null);
        for (AnAction newAction : newGroupActions) {
          if (newAction == action) {
            myListener = new FileCreatedByUserListener();
            VirtualFileManager.getInstance().addVirtualFileListener(myListener);
            break;
          }
        }
      }

      @Override
      public void afterActionPerformed(AnAction action, DataContext dataContext, AnActionEvent event) {
        AnAction[] newGroupActions = ((ActionGroup)ActionManager.getInstance().getAction("NewGroup")).getChildren(null);
        for (AnAction newAction : newGroupActions) {
          if (newAction == action) {
            VirtualFileManager.getInstance().removeVirtualFileListener(myListener);
          }
        }
      }

      @Override
      public void beforeEditorTyping(char c, DataContext dataContext) {

      }
    });
  }

  @Override
  public void disposeComponent() {
    if (myBusConnection != null) {
      myBusConnection.disconnect();
    }
  }

  @NotNull
  @Override
  public String getComponentName() {
    return "StudyTaskManager";
  }

  public static StudyProjectComponent getInstance(@NotNull final Project project) {
    final Module module = ModuleManager.getInstance(project).getModules()[0];
    return module.getComponent(StudyProjectComponent.class);
  }

  private class FileCreatedByUserListener implements VirtualFileListener {
    @Override
    public void fileCreated(@NotNull VirtualFileEvent event) {
      if (project.isDisposed()) return;
      final VirtualFile createdFile = event.getFile();
      final VirtualFile taskDir = StudyUtils.getTaskDir(createdFile);
      final Course course = StudyTaskManager.getInstance(project).getCourse();
      if (course == null || !EduNames.STUDY.equals(course.getCourseMode())) {
        return;
      }
      if (taskDir != null && taskDir.getName().contains(EduNames.TASK)) {
        int taskIndex = EduUtils.getIndex(taskDir.getName(), EduNames.TASK);
        final VirtualFile lessonDir = taskDir.getParent();
        if (lessonDir != null && lessonDir.getName().contains(EduNames.LESSON)) {
          int lessonIndex = EduUtils.getIndex(lessonDir.getName(), EduNames.LESSON);
          List<Lesson> lessons = course.getLessons();
          if (StudyUtils.indexIsValid(lessonIndex, lessons)) {
            final Lesson lesson = lessons.get(lessonIndex);
            final List<Task> tasks = lesson.getTaskList();
            if (StudyUtils.indexIsValid(taskIndex, tasks)) {
              final Task task = tasks.get(taskIndex);
              final TaskFile taskFile = new TaskFile();
              taskFile.initTaskFile(task, false);
              taskFile.setUserCreated(true);
              final String name = FileUtil.getRelativePath(taskDir.getPath(), createdFile.getPath(), '/');
              taskFile.name = name;
              //TODO: put to other steps as well
              task.getTaskFiles().put(name, taskFile);
            }
          }
        }
      }
    }
  }
}
