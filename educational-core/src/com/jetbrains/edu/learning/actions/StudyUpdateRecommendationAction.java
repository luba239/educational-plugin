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
import icons.EducationalCoreIcons;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;

import static com.jetbrains.edu.learning.stepic.EduStepicConnector.getLastCorrectSubmissionFromStepik;
import static com.jetbrains.edu.learning.stepic.EduStepicConnector.removeAllTags;

public class StudyUpdateRecommendationAction extends DumbAwareAction {
  private static final Logger LOG = Logger.getInstance(StudyUpdateRecommendationAction.class);

  public StudyUpdateRecommendationAction() {
    super("Synchronize Course", "Synchronize Course", EducationalCoreIcons.StepikRefresh);
  }

  @Override
  public void actionPerformed(AnActionEvent e) {
    Project project = e.getProject();
    assert project != null;

    Course course = StudyTaskManager.getInstance(project).getCourse();
    assert course != null;
    ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
      ProgressManager.getInstance().getProgressIndicator().setIndeterminate(true);
      StudyUtils.execCancelable(() ->{
        if (course.isAdaptive()) {
          updateAdaptiveCourse(project, course);
        }
        else {
          updateCourse(project, course);
        }
        return null;
      });
    }, "Synchronizing Course", true, project);
  }

  private static void updateCourse(@NotNull Project project, @NotNull Course course) {
    TaskFile selectedTaskFile = StudyUtils.getSelectedTaskFile(project);

    for (Lesson lesson : course.getLessons()) {
      List<Task> tasks = lesson.getTaskList();
      int[] ids = tasks.stream().mapToInt(task -> task.getStepId()).toArray();
      List<StepicWrappers.StepSource> steps = EduStepicConnector.getSteps(ids);
      if (steps != null) {
        String[] progesses = steps.stream().map(step -> step.progress).toArray(String[]::new);
        Boolean[] solved = EduStepicConnector.isTasksSolved(progesses);
        if (solved == null) return;
        for (int i = 0; i < tasks.size(); i++) {
          Boolean isSolved = solved[i];
          Task task = tasks.get(i);
          if (isSolved == null || !isSolved) {
            task.setStatus(StudyStatus.Unchecked);
          }
          else {
            task.setStatus(StudyStatus.Solved);
            updateTaskFilesTexts(project, task);
          }
        }
      }
    }

    ApplicationManager.getApplication().invokeLater(() -> {
      VirtualFileManager.getInstance().refreshWithoutFileWatcher(false);
      openTask(project, course, selectedTaskFile);
    });
  }

  private static void openTask(@NotNull Project project, @NotNull Course course, TaskFile selectedTaskFile) {
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

  private static void updateTaskFilesTexts(@NotNull Project project, Task task) {
    if (task instanceof TaskWithSubtasks) {
      return;
    }

    try {
      List<StepicWrappers.SolutionFile> solutionFiles = getLastCorrectSubmissionFromStepik(String.valueOf(task.getStepId()));
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

  private static void updateAdaptiveCourse(@NotNull Project project, @NotNull Course course) {
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
    }
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
