package com.jetbrains.edu.learning.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.impl.EditorImpl;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.JBUI;
import com.jetbrains.edu.learning.StudyTaskManager;
import com.jetbrains.edu.learning.StudyUtils;
import com.jetbrains.edu.learning.courseFormat.*;
import com.jetbrains.edu.learning.courseFormat.tasks.Task;
import com.jetbrains.edu.learning.courseFormat.tasks.TaskWithSubtasks;
import com.jetbrains.edu.learning.editor.StudyEditor;
import com.jetbrains.edu.learning.navigation.StudyNavigator;
import com.jetbrains.edu.learning.stepic.EduAdaptiveStepicConnector;
import com.jetbrains.edu.learning.stepic.EduStepicConnector;
import com.jetbrains.edu.learning.stepic.StepicWrappers;
import icons.EducationalCoreIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.jetbrains.edu.learning.stepic.EduStepicConnector.getLastSubmission;
import static com.jetbrains.edu.learning.stepic.EduStepicConnector.removeAllTags;

public class StudySyncCourseAction extends DumbAwareAction {
  private static final Logger LOG = Logger.getInstance(StudySyncCourseAction.class);
  private static final Key<Boolean> IS_TO_UPDATE = Key.create("IS_TO_UPDATE");

  public StudySyncCourseAction() {
    super("Synchronize Course", "Synchronize Course", EducationalCoreIcons.StepikRefresh);
  }

  @Override
  public void actionPerformed(AnActionEvent e) {
    Project project = e.getProject();
    if (project != null) {
      doUpdate(project);
    }
  }

  public static void doUpdate(@NotNull Project project) {
    Course course = StudyTaskManager.getInstance(project).getCourse();
    assert course != null;
    if (course.isAdaptive()) {
      updateAdaptiveCourse(project, course);
    }
    else {
      updateCourse(project, course);
    }
  }

  public static void updateCourse(@NotNull Project project, @NotNull Course course) {
    MessageBusConnection connection = ApplicationManager.getApplication().getMessageBus().connect();
    connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, taskUpdatingListener(project));
    ApplicationManager.getApplication().invokeLater(() -> ProgressManager.getInstance().run(new com.intellij.openapi.progress.Task.Backgroundable(project, "Loading Solutions") {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        TaskFile selectedTaskFile = StudyUtils.getSelectedTaskFile(project);
        ArrayList<Task> tasksToUpdate = getSolvedTasksAndUpdateStatus(course, selectedTaskFile, project);

        for (Task task : tasksToUpdate) {
          boolean isSolved = task.getStatus() == StudyStatus.Solved;
          updateTaskSolution(project, task, isSolved);
        }

        connection.disconnect();
        refreshFiles(tasksToUpdate, selectedTaskFile);
      }

      private void refreshFiles(ArrayList<Task> tasksToUpdate, TaskFile selectedTaskFile) {
        if (!tasksToUpdate.isEmpty()) {
          ApplicationManager.getApplication().invokeLater(() -> {
            VirtualFileManager.getInstance().refreshWithoutFileWatcher(false);
            StudySyncCourseAction.openTask(project, selectedTaskFile);
          });
        }
      }
    }));
  }

  @NotNull
  private static FileEditorManagerListener taskUpdatingListener(final Project project) {
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
              disableEditor(project);
            }
          }
        }
      }
    };
  }

  private static void disableEditor(Project project) {
    StudyEditor studyEditor = StudyUtils.getSelectedStudyEditor(project);

    if (studyEditor != null) {
      Editor editor = studyEditor.getEditor();
      editor.setHeaderComponent(createNotificationLabel());
      ((EditorImpl)editor).setViewer(true);
      ((EditorImpl)editor).setCaretEnabled(false);
    }
  }

  @NotNull
  private static JBLabel createNotificationLabel() {
    JBLabel solution = new JBLabel("Loading solution");
    solution.setBorder(JBUI.Borders.empty(10, 10));
    return solution;
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

  private static void openTask(@NotNull Project project, @Nullable TaskFile selectedTaskFile) {
    if (selectedTaskFile != null) {
      Lesson selectedLesson = selectedTaskFile.getTask().getLesson();
      int index = selectedTaskFile.getTask().getIndex();
      Task task = selectedLesson.getTaskList().get(index - 1);
      StudyNavigator.navigateToTask(project, task);
    }
  }

  private static void updateTaskSolution(@NotNull Project project, Task task, boolean isSolved) {
    if (task instanceof TaskWithSubtasks) {
      return;
    }

    try {
      List<StepicWrappers.SolutionFile> solutionFiles = getLastSubmission(String.valueOf(task.getStepId()));
      if (solutionFiles.isEmpty()) {
        task.setStatus(StudyStatus.Unchecked);
        return;
      }
      task.setStatus(isSolved ? StudyStatus.Solved : StudyStatus.Failed);
      for (StepicWrappers.SolutionFile file : solutionFiles) {
        TaskFile taskFile = task.getTaskFile(file.name);
        if (taskFile != null) {
          if (EduStepicConnector.setPlaceholdersFromTags(taskFile, file)) {
            taskFile.text = removeAllTags(file.text);
          }
        }
      }
      EduAdaptiveStepicConnector.replaceCurrentTask(project, task, task.getLesson(), task.getIndex());
    }
    catch (IOException e) {
      LOG.warn(e.getMessage());
    }
  }

  private static void updateAdaptiveCourse(@NotNull Project project, @NotNull Course course) {
    ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
      ProgressManager.getInstance().getProgressIndicator().setIndeterminate(true);
      StudyUtils.execCancelable(() -> {
        Lesson adaptiveLesson = course.getLessons().get(0);
        assert adaptiveLesson != null;

        int taskNumber = adaptiveLesson.getTaskList().size();
        Task lastRecommendationInCourse = adaptiveLesson.getTaskList().get(taskNumber - 1);
        Task lastRecommendationOnStepik = EduAdaptiveStepicConnector.getNextRecommendation(project, (RemoteCourse) course);

        if (lastRecommendationOnStepik != null && lastRecommendationOnStepik.getStepId() != lastRecommendationInCourse.getStepId()) {
          lastRecommendationOnStepik.initTask(adaptiveLesson, false);
          EduAdaptiveStepicConnector.replaceCurrentTask(project, lastRecommendationOnStepik, adaptiveLesson, adaptiveLesson.taskList.size());

          ApplicationManager.getApplication().invokeLater(() -> {
            VirtualFileManager.getInstance().refreshWithoutFileWatcher(false);
            StudyNavigator.navigateToTask(project, lastRecommendationOnStepik);
          });
          return true;
        }

        return false;
      });
    }, "Synchronizing Course", true, project);
  }

  public static boolean isVisible(@Nullable Project project) {
    if (project == null) {
      return false;
    }

    StudyTaskManager taskManager = StudyTaskManager.getInstance(project);
    Course course = taskManager.getCourse();
    if (course == null) {
      return false;
    }

    if (!taskManager.isMyLoadSolutions()) {
      return false;
    }

    return true;
  }

  @Override
  public void update(AnActionEvent e) {
    boolean visible = isVisible(e.getProject());
    Presentation presentation = e.getPresentation();
    presentation.setEnabledAndVisible(visible);
  }
}
