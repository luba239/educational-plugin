package com.jetbrains.edu.learning.stepic;

import com.intellij.ide.SaveAndSyncHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.DefaultLogger;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLoadingPanel;
import com.intellij.util.messages.MessageBusConnection;
import com.jetbrains.edu.learning.StudyTaskManager;
import com.jetbrains.edu.learning.StudyUtils;
import com.jetbrains.edu.learning.core.EduUtils;
import com.jetbrains.edu.learning.courseFormat.Course;
import com.jetbrains.edu.learning.courseFormat.Lesson;
import com.jetbrains.edu.learning.courseFormat.StudyStatus;
import com.jetbrains.edu.learning.courseFormat.TaskFile;
import com.jetbrains.edu.learning.courseFormat.tasks.Task;
import com.jetbrains.edu.learning.courseFormat.tasks.TaskWithSubtasks;
import com.jetbrains.edu.learning.editor.StudyEditor;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static com.jetbrains.edu.learning.stepic.EduStepicConnector.getLastSubmission;
import static com.jetbrains.edu.learning.stepic.EduStepicConnector.removeAllTags;

public class StudyCourseSynchronizer {
  private static final Logger LOG = DefaultLogger.getInstance(StudyCourseSynchronizer.class);
  private static TaskFile mySelectedTaskFile;
  private static HashMap<Task, Future> myFutures;
  private final MessageBusConnection myBusConnection;
  private Project myProject;

  public StudyCourseSynchronizer(@NotNull final Project project) {
    myProject = project;
    myBusConnection = ApplicationManager.getApplication().getMessageBus().connect();
  }

  public void init() {
    StudyEditor selectedStudyEditor = StudyUtils.getSelectedStudyEditor(myProject);
    if (selectedStudyEditor != null) {
      mySelectedTaskFile = selectedStudyEditor.getTaskFile();
    }
    addFileOpenListener();
  }

  public void updateUnderProgress() {
    ProgressManager.getInstance().run(new com.intellij.openapi.progress.Task.Backgroundable(myProject, "Getting Tasks to Update") {
      @Override
      public void run(@NotNull ProgressIndicator progressIndicator) {
        Course course = StudyTaskManager.getInstance(myProject).getCourse();
        if (course != null) {
          Map<Task, StudyStatus> tasksToUpdate = StudyUtils.execCancelable(() -> tasksToUpdate(course));
          update(tasksToUpdate, progressIndicator);
        }
      }
    });
  }

  private void update(Map<Task, StudyStatus> tasksToUpdate, ProgressIndicator progressIndicator) {
    myFutures = new HashMap<>();

    if (tasksToUpdate == null) {
      LOG.warn("Can't get a list of tasks to update");
      return;
    }
    CountDownLatch countDownLatch = new CountDownLatch(tasksToUpdate.size());
    for (Map.Entry<Task, StudyStatus> taskStudyStatusEntry : tasksToUpdate.entrySet()) {
      Task task = taskStudyStatusEntry.getKey();
      StudyStatus status = taskStudyStatusEntry.getValue();
      task.setStatus(status);
      Future<?> future = ApplicationManager.getApplication().executeOnPooledThread(() -> {
        if (!progressIndicator.isCanceled()) {
          boolean isSolved = task.getStatus() == StudyStatus.Solved;
          updateTaskSolution(myProject, task, isSolved);
        }
        countDownLatch.countDown();
      });
      myFutures.put(task, future);
    }

    Task selectedTask = mySelectedTaskFile.getTask();
    if (tasksToUpdate.containsKey(selectedTask)) {
      StudyEditor selectedStudyEditor = StudyUtils.getSelectedStudyEditor(myProject);
      assert selectedStudyEditor != null;
      ApplicationManager.getApplication().invokeLater(() -> {
        showLoadingPanel(selectedStudyEditor);
        waitUntilTaskUpdatesAndEnableEditor(myFutures.get(selectedTask));
      });
    }

    try {
      countDownLatch.await();
      ApplicationManager.getApplication().invokeLater(() -> ApplicationManager.getApplication().runWriteAction(EduUtils::synchronize));
      myBusConnection.disconnect();
    }
    catch (InterruptedException e) {
      LOG.warn(e.getCause());
    }
  }

  private static Map<Task, StudyStatus> tasksToUpdate(@NotNull Course course) {
    Map<Task, StudyStatus> tasksToUpdate = new HashMap<>();
    if (!EduStepicConnector.ping()) {
      return tasksToUpdate;
    }
    for (Lesson lesson : course.getLessons()) {
      List<Task> tasks = lesson.getTaskList();
      String[] ids = tasks.stream().map(task -> String.valueOf(task.getStepId())).toArray(String[]::new);
      String[] progresses = Arrays.stream(ids).map(id -> ("77-" + id)).toArray(String[]::new);
      Boolean[] taskStatuses = EduStepicConnector.taskStatuses(progresses);
      if (taskStatuses == null) return tasksToUpdate;
      for (int i = 0; i < tasks.size(); i++) {
        Boolean isSolved = taskStatuses[i];
        Task task = tasks.get(i);
        if (isSolved != null && isToUpdate(isSolved, task)) {
          tasksToUpdate.put(tasks.get(i), isSolved ? StudyStatus.Solved : StudyStatus.Failed);
        }
      }
    }
    return tasksToUpdate;
  }

  private void addFileOpenListener() {
    myBusConnection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
      @Override
      public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        StudyEditor studyEditor = StudyUtils.getSelectedStudyEditor(myProject);
        TaskFile taskFile = StudyUtils.getTaskFile(myProject, file);
        if (studyEditor != null && taskFile != null) {
          mySelectedTaskFile = taskFile;
          Task task = taskFile.getTask();
          if (myFutures != null && myFutures.containsKey(task)) {
            showLoadingPanel(studyEditor);
            Future future = myFutures.get(task);
            if (!future.isDone() || !future.isCancelled()) {
              waitUntilTaskUpdatesAndEnableEditor(future);
            }
          }
        }
      }
    });
  }

  private void showLoadingPanel(StudyEditor studyEditor) {
    JBLoadingPanel component = studyEditor.getComponent();
    component.setLoadingText("Loading solution");
    component.startLoading();
  }

  private void waitUntilTaskUpdatesAndEnableEditor(Future future) {
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        future.get();
        ApplicationManager.getApplication().invokeLater(() -> {
          StudyEditor selectedEditor = StudyUtils.getSelectedStudyEditor(myProject);
          if (selectedEditor != null && mySelectedTaskFile.equals(selectedEditor.getTaskFile())) {
            JBLoadingPanel component = selectedEditor.getComponent();
            component.stopLoading();
          }
        });

      }
      catch (InterruptedException | ExecutionException e) {
        LOG.warn(e.getCause());
      }
    });
  }

  private static boolean isToUpdate(@NotNull Boolean isSolved, @NotNull Task task) {
    if (isSolved && task.getStatus() != StudyStatus.Solved) {
      return true;
    } else if (!isSolved) {
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

  private static void updateTaskSolution(@NotNull Project project, Task task, boolean isSolved) {
    if (task instanceof TaskWithSubtasks || !EduStepicConnector.ping()) {
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
      updateSolutionTexts(project, task);
    }
    catch (IOException e) {
      LOG.warn(e.getMessage());
    }
  }

  private static void updateSolutionTexts(@NotNull Project project, Task task) {
    Course course = StudyTaskManager.getInstance(project).getCourse();
    assert course != null;

    VirtualFile taskDir = task.getTaskDir(project);
    if (taskDir == null) {
      LOG.warn("Unable to find task directory");
      return;
    }
    ApplicationManager.getApplication().invokeLater(() -> ApplicationManager.getApplication().runWriteAction(() -> {
      for (TaskFile taskFile : task.getTaskFiles().values()) {
        VirtualFile vFile = taskDir.findChild(taskFile.name);
        if (vFile != null) {
          try {
            taskFile.setTrackChanges(false);
            VfsUtil.saveText(vFile, taskFile.text);
            SaveAndSyncHandler.getInstance().refreshOpenFiles();
            taskFile.setTrackChanges(true);
          }
          catch (IOException e) {
            LOG.warn(e.getMessage());
          }
        }
      }
    }));
  }
}
