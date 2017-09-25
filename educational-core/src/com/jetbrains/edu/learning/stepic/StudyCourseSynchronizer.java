package com.jetbrains.edu.learning.stepic;

import com.intellij.ide.SaveAndSyncHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.DefaultLogger;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.impl.EditorImpl;
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
  private static final int MAX_REQUEST_PARAMS = 100; // restriction od Stepik API for multiple requests
  private static HashMap<Task, Future> myFutures;
  private final MessageBusConnection myBusConnection;
  private Project myProject;
  private Task mySelectedTask;

  public StudyCourseSynchronizer(@NotNull final Project project) {
    myProject = project;
    myBusConnection = ApplicationManager.getApplication().getMessageBus().connect();
  }

  public void init() {
    StudyEditor selectedStudyEditor = StudyUtils.getSelectedStudyEditor(myProject);
    if (selectedStudyEditor != null && selectedStudyEditor.getTaskFile() != null) {
      mySelectedTask = selectedStudyEditor.getTaskFile().getTask();
    }
    addFileOpenListener();
  }

  public void updateUnderProgress() {
    ProgressManager.getInstance().run(new com.intellij.openapi.progress.Task.Backgroundable(myProject, "Getting Tasks to Update") {
      @Override
      public void run(@NotNull ProgressIndicator progressIndicator) {
        Course course = StudyTaskManager.getInstance(myProject).getCourse();
        if (course != null) {
          update(progressIndicator, course);
        }
      }
    });
  }

  public void update(@NotNull ProgressIndicator progressIndicator, @NotNull Course course) {
    Map<Task, StudyStatus> tasksToUpdate = StudyUtils.execCancelable(() -> tasksToUpdate(course));
    updateTasks(tasksToUpdate, progressIndicator);
  }

  private void updateTasks(Map<Task, StudyStatus> tasksToUpdate, ProgressIndicator progressIndicator) {
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

    if (mySelectedTask != null && tasksToUpdate.containsKey(mySelectedTask)) {
      StudyEditor selectedStudyEditor = StudyUtils.getSelectedStudyEditor(myProject);
      assert selectedStudyEditor != null;
      ApplicationManager.getApplication().invokeLater(() -> {
        showLoadingPanel(selectedStudyEditor);
        waitUntilTaskUpdatesAndEnableEditor(myFutures.get(mySelectedTask));
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
    Task[] allTasks = course.getLessons().stream().flatMap(lesson -> lesson.getTaskList().stream()).toArray(Task[]::new);
    int length = allTasks.length;
    for (int i = 0; i < length; i += MAX_REQUEST_PARAMS) {
      List<Task> sublist = Arrays.asList(allTasks).subList(i, Math.min(i + MAX_REQUEST_PARAMS, length));
      String[] progresses = sublist.stream().map(task -> "77-" + String.valueOf(task.getStepId())).toArray(String[]::new);
      Boolean[] taskStatuses = EduStepicConnector.taskStatuses(progresses);
      if (taskStatuses == null) return tasksToUpdate;
      for (int j = 0; j < sublist.size(); j++) {
        Boolean isSolved = taskStatuses[j];
        Task task = allTasks[j];
        if (isSolved != null && isToUpdate(isSolved, task.getStatus(), task.getStepId())) {
          tasksToUpdate.put(allTasks[j], isSolved ? StudyStatus.Solved : StudyStatus.Failed);
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
          mySelectedTask = taskFile.getTask();
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
    ((EditorImpl)studyEditor.getEditor()).setViewer(true);
    component.setLoadingText("Loading solution");
    component.startLoading();
  }

  private void waitUntilTaskUpdatesAndEnableEditor(Future future) {
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        future.get();
        ApplicationManager.getApplication().invokeLater(() -> {
          StudyEditor selectedEditor = StudyUtils.getSelectedStudyEditor(myProject);
          if (selectedEditor != null && mySelectedTask.getTaskFiles().containsKey(selectedEditor.getTaskFile().name)) {
            JBLoadingPanel component = selectedEditor.getComponent();
            component.stopLoading();
            ((EditorImpl)selectedEditor.getEditor()).setViewer(false);
          }
        });
      }
      catch (InterruptedException | ExecutionException e) {
        LOG.warn(e.getCause());
      }
    });
  }

  private static boolean isToUpdate(@NotNull Boolean isSolved, @NotNull StudyStatus currentStatus, int stepId) {
    if (isSolved && currentStatus != StudyStatus.Solved) {
      return true;
    }
    else if (!isSolved) {
      try {
        List<StepicWrappers.SolutionFile> solutionFiles = EduStepicConnector.getLastSubmission(String.valueOf(stepId), isSolved);
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

  private static void updateTaskSolution(@NotNull Project project, @NotNull Task task, boolean isSolved) {
    if (task instanceof TaskWithSubtasks || !EduStepicConnector.ping()) {
      return;
    }

    try {
      if (!updateTask(task, isSolved)) return;
      updateSolutionTexts(project, task);
    }
    catch (IOException e) {
      LOG.warn(e.getMessage());
    }
  }

  private static boolean updateTask(Task task, boolean isSolved) throws IOException {
    List<StepicWrappers.SolutionFile> solutionFiles = getLastSubmission(String.valueOf(task.getStepId()), isSolved);
    if (solutionFiles.isEmpty()) {
      task.setStatus(StudyStatus.Unchecked);
      return false;
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
    return true;
  }

  private static void updateSolutionTexts(@NotNull Project project, @NotNull Task task) {
    VirtualFile taskDir = task.getTaskDir(project);
    if (taskDir == null) {
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
