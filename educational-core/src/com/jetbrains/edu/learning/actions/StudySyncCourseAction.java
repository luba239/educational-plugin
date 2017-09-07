package com.jetbrains.edu.learning.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.jetbrains.edu.learning.StudyTaskManager;
import com.jetbrains.edu.learning.StudyUtils;
import com.jetbrains.edu.learning.courseFormat.*;
import com.jetbrains.edu.learning.courseFormat.tasks.Task;
import com.jetbrains.edu.learning.courseFormat.tasks.TaskWithSubtasks;
import com.jetbrains.edu.learning.navigation.StudyNavigator;
import com.jetbrains.edu.learning.stepic.EduAdaptiveStepicConnector;
import com.jetbrains.edu.learning.stepic.EduStepicConnector;
import com.jetbrains.edu.learning.stepic.StepicWrappers;
import com.jetbrains.edu.learning.ui.StudyStepicUserWidget;
import icons.EducationalCoreIcons;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;

import static com.jetbrains.edu.learning.stepic.EduStepicConnector.getLastSubmission;
import static com.jetbrains.edu.learning.stepic.EduStepicConnector.removeAllTags;

public class StudySyncCourseAction extends DumbAwareAction {
  private static final Logger LOG = Logger.getInstance(StudySyncCourseAction.class);

  public StudySyncCourseAction() {
    super("Synchronize Course", "Synchronize Course", EducationalCoreIcons.StepikRefresh);
  }

  @Override
  public void actionPerformed(AnActionEvent e) {
    if (doUpdate(e.getProject())) {
      StudyStepicUserWidget widget = StudyUtils.getStepicWidget();
      if (widget != null) {
        widget.update();
      }
    }
  }

  public static boolean doUpdate(Project project) {
    assert project != null;

    Course course = StudyTaskManager.getInstance(project).getCourse();
    assert course != null;
    return ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
      ProgressManager.getInstance().getProgressIndicator().setIndeterminate(true);
      StudyUtils.execCancelable(() ->{
        if (course.isAdaptive()) {
          return updateAdaptiveCourse(project, course);
        }
        else {
          return updateCourse(project, course);
        }
      });
    }, "Synchronizing Course", true, project);
  }

  private static boolean updateCourse(@NotNull Project project, @NotNull Course course) {
    TaskFile selectedTaskFile = StudyUtils.getSelectedTaskFile(project);

    for (Lesson lesson : course.getLessons()) {
      List<Task> tasks = lesson.getTaskList();
      int[] ids = tasks.stream().mapToInt(Task::getStepId).toArray();
      List<StepicWrappers.StepSource> steps = EduStepicConnector.getSteps(ids);
      if (steps != null) {
        String[] progesses = steps.stream().map(step -> step.progress).toArray(String[]::new);
        Boolean[] solved = EduStepicConnector.isTasksSolved(progesses);
        if (solved == null) return false;
        for (int i = 0; i < tasks.size(); i++) {
          Boolean isSolved = solved[i];
          Task task = tasks.get(i);
          if (isSolved == null) continue;
          updateTaskSolution(project, task, isSolved);
        }
      }
    }

    ApplicationManager.getApplication().invokeLater(() -> {
      VirtualFileManager.getInstance().refreshWithoutFileWatcher(false);
      openTask(project, course, selectedTaskFile);
    });

    return true;
  }

  public static void openTask(@NotNull Project project, @NotNull Course course, TaskFile selectedTaskFile) {
    if (selectedTaskFile != null) {
      Lesson selectedLesson = selectedTaskFile.getTask().getLesson();
      int index = selectedTaskFile.getTask().getIndex();
      Task task = selectedLesson.getTaskList().get(index - 1);
      StudyNavigator.navigateToTask(project, task);
    }
    else {
      StudyUtils.openFirstTask(course, project);
    }
  }

  public static void updateTaskSolution(@NotNull Project project, Task task, boolean isSolved) {
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
          EduStepicConnector.setPlaceholdersFromTags(taskFile, file);
          taskFile.text = removeAllTags(file.text);
        }
      }
      EduAdaptiveStepicConnector.replaceCurrentTask(project, task, task.getLesson(), task.getIndex());
    }
    catch (IOException e) {
      LOG.warn(e.getMessage());
    }
  }

  private static boolean updateAdaptiveCourse(@NotNull Project project, @NotNull Course course) {
    Lesson adaptiveLesson = course.getLessons().get(0);
    assert adaptiveLesson != null;

    int taskNumber = adaptiveLesson.getTaskList().size();
    Task lastRecommendationInCourse = adaptiveLesson.getTaskList().get(taskNumber - 1);
    Task lastRecommendationOnStepik = EduAdaptiveStepicConnector.getNextRecommendation(project, (RemoteCourse)course);

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
  }

  @Override
  public void update(AnActionEvent e) {
    Presentation presentation = e.getPresentation();

    Project project = e.getProject();
    if (project == null) {
      presentation.setEnabledAndVisible(false);
      return;
    }

    Course course = StudyTaskManager.getInstance(project).getCourse();
    if (course == null) {
      presentation.setEnabledAndVisible(false);
      return;
    }

    presentation.setEnabledAndVisible(true);
  }
}
