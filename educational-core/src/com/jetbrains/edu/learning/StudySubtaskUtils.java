package com.jetbrains.edu.learning;

import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.undo.DocumentReferenceManager;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.problems.WolfTheProblemSolver;
import com.intellij.ui.EditorNotifications;
import com.intellij.util.ArrayUtil;
import com.jetbrains.edu.coursecreator.CCUtils;
import com.jetbrains.edu.learning.checker.StudyCheckUtils;
import com.jetbrains.edu.learning.core.EduDocumentListener;
import com.jetbrains.edu.learning.core.EduNames;
import com.jetbrains.edu.learning.core.EduUtils;
import com.jetbrains.edu.learning.courseFormat.AnswerPlaceholder;
import com.jetbrains.edu.learning.courseFormat.AnswerPlaceholderSubtaskInfo;
import com.jetbrains.edu.learning.courseFormat.Course;
import com.jetbrains.edu.learning.courseFormat.TaskFile;
import com.jetbrains.edu.learning.courseFormat.tasks.Task;
import com.jetbrains.edu.learning.courseFormat.tasks.TaskWithSubtasks;
import com.jetbrains.edu.learning.navigation.StudyNavigator;
import com.jetbrains.edu.learning.ui.StudyToolWindow;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Map;

public class StudySubtaskUtils {
  private static final Logger LOG = Logger.getInstance(StudySubtaskUtils.class);

  private StudySubtaskUtils() {
  }

  public static void switchStep(@NotNull Project project, @NotNull TaskWithSubtasks task, int toSubtaskIndex) {
    switchStep(project, task, toSubtaskIndex, true);
  }

  /***
   * @param toSubtaskIndex from 0 to subtaskNum - 1
   */
  public static void switchStep(@NotNull Project project, @NotNull TaskWithSubtasks task, int toSubtaskIndex, boolean navigateToTask) {
    if (toSubtaskIndex == task.getActiveSubtaskIndex()) {
      return;
    }
    VirtualFile taskDir = task.getTaskDir(project);
    if (taskDir == null) {
      return;
    }
    int fromSubtaskIndex = task.getActiveSubtaskIndex();
    for (Map.Entry<String, TaskFile> entry : task.getTaskFiles().entrySet()) {
      String name = entry.getKey();
      VirtualFile virtualFile = taskDir.findFileByRelativePath(name);
      if (virtualFile == null) {
        continue;
      }
      TaskFile taskFile = entry.getValue();
      Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
      if (document == null) {
        continue;
      }
      EduDocumentListener listener = null;
      if (!FileEditorManager.getInstance(project).isFileOpen(virtualFile)) {
        listener = new EduDocumentListener(taskFile, true);
        document.addDocumentListener(listener);
      }
      updatePlaceholderTexts(document, taskFile, fromSubtaskIndex, toSubtaskIndex);
      if (listener != null) {
        document.removeDocumentListener(listener);
      }
      UndoManager.getInstance(project).nonundoableActionPerformed(DocumentReferenceManager.getInstance().create(document), false);
      EditorNotifications.getInstance(project).updateNotifications(virtualFile);
      if (StudyUtils.isStudentProject(project)) {
        WolfTheProblemSolver.getInstance(project).clearProblems(virtualFile);
        taskFile.setHighlightErrors(false);
      }
    }
    transformTestFile(project, toSubtaskIndex, taskDir, task);

    // We want to dump current tool window editor state to subtask
    // before we will switch subtask
    StudyUtils.saveToolWindowTextIfNeeded(project);

    task.setActiveSubtaskIndex(toSubtaskIndex);
    updateUI(project, task, !CCUtils.isCourseCreator(project) && navigateToTask);
    if (CCUtils.isCourseCreator(project)) {
      updateOpenedTestFiles(project, taskDir, fromSubtaskIndex, toSubtaskIndex);
    }
  }

  private static void updateOpenedTestFiles(@NotNull Project project,
                                            @NotNull VirtualFile taskDir,
                                            int fromTaskNumber,
                                            int toSubtaskNumber) {
    String fromSubtaskTestName = getTestFileName(project, fromTaskNumber);
    String toSubtaskTestName = getTestFileName(project, toSubtaskNumber);
    if (fromSubtaskTestName == null || toSubtaskTestName == null) {
      return;
    }
    VirtualFile fromTest = taskDir.findChild(fromSubtaskTestName);
    VirtualFile toTest = taskDir.findChild(toSubtaskTestName);
    if (fromTest == null || toTest == null) {
      return;
    }
    FileEditorManager editorManager = FileEditorManager.getInstance(project);
    if (editorManager.isFileOpen(fromTest)) {
      VirtualFile[] selectedFiles = editorManager.getSelectedFiles();
      boolean isSelected = ArrayUtil.contains(fromTest, selectedFiles);
      editorManager.closeFile(fromTest);
      editorManager.openFile(toTest, isSelected);
      if (!isSelected) {
        for (VirtualFile file : selectedFiles) {
          editorManager.openFile(file, true);
        }
      }
    }
  }

  private static void transformTestFile(@NotNull Project project,
                                        int toSubtaskIndex,
                                        VirtualFile taskDir,
                                        @NotNull TaskWithSubtasks task) {

    String subtaskTestFileName = getTestFileName(project, toSubtaskIndex);
    if (subtaskTestFileName == null) {
      return;
    }
    final Ref<VirtualFile> subtaskTestFile = new Ref<>();
    for (Map.Entry<String, String> entry : task.getTestsText().entrySet()) {
      String name = entry.getKey();
      if (name.contains(FileUtil.getNameWithoutExtension(subtaskTestFileName))) {
        String nameWithoutExtension = FileUtil.getNameWithoutExtension(name);
        String extension = FileUtil.getExtension(subtaskTestFileName);
        subtaskTestFile.set(taskDir.findFileByRelativePath(nameWithoutExtension + ".txt"));
        if (subtaskTestFile.get() != null) {
          ApplicationManager.getApplication().runWriteAction(() -> {
            try {
              subtaskTestFile.get().rename(project, FileUtil.getNameWithoutExtension(subtaskTestFileName) + "." + extension);
              if (toSubtaskIndex > 0) {
                int indexOfMarker = nameWithoutExtension.indexOf(EduNames.SUBTASK_MARKER);
                String prefix = nameWithoutExtension.substring(0, indexOfMarker + EduNames.SUBTASK_MARKER.length());
                String oldFileName = prefix + (toSubtaskIndex - 1) + "." + extension;
                VirtualFile oldFile = taskDir.findFileByRelativePath(oldFileName);
                if (oldFile != null) {
                  oldFile.rename(project, FileUtil.getNameWithoutExtension(oldFile.getName()) + ".txt");
                }
              }
            }
            catch (IOException e) {
              LOG.error(e);
            }
          });
        }
        return;
      }
    }
  }

  @Nullable
  private static String getTestFileName(@NotNull Project project, int subtaskIndex) {
    Course course = StudyTaskManager.getInstance(project).getCourse();
    if (course == null) {
      return null;
    }
    EduPluginConfigurator configurator = EduPluginConfiguratorManager.forLanguage(course.getLanguageById());
    if (configurator == null) {
      return null;
    }
    String defaultTestFileName = configurator.getTestFileName();
    String nameWithoutExtension = FileUtil.getNameWithoutExtension(defaultTestFileName);
    String extension = FileUtilRt.getExtension(defaultTestFileName);
    return nameWithoutExtension + EduNames.SUBTASK_MARKER + subtaskIndex + "." + extension;
  }

  public static void updateUI(@NotNull Project project, @NotNull Task task, boolean navigateToTask) {
    StudyCheckUtils.drawAllPlaceholders(project, task);
    ProjectView.getInstance(project).refresh();
    StudyToolWindow toolWindow = StudyUtils.getStudyToolWindow(project);
    if (toolWindow != null) {
      if (task.getTaskDescription() == null) {
        task.addTaskText(task.getTaskDescriptionName(), CCUtils.TASK_DESCRIPTION_TEXT);
      }
      toolWindow.setCurrentTask(project, task);
    }
    if (navigateToTask) {
      StudyNavigator.navigateToTask(project, task);
    }
  }

  private static void updatePlaceholderTexts(@NotNull Document document,
                                             @NotNull TaskFile taskFile,
                                             int fromSubtaskIndex,
                                             int toSubtaskIndex) {
    taskFile.setTrackLengths(false);
    for (AnswerPlaceholder placeholder : taskFile.getAnswerPlaceholders()) {
      placeholder.switchSubtask(document, fromSubtaskIndex, toSubtaskIndex);
    }
    taskFile.setTrackLengths(true);
  }

  public static void refreshPlaceholder(@NotNull Editor editor, @NotNull AnswerPlaceholder placeholder) {
    int prevSubtaskIndex = placeholder.getActiveSubtaskIndex() - 1;
    AnswerPlaceholderSubtaskInfo info = placeholder.getSubtaskInfos().get(prevSubtaskIndex);
    String replacementText = info != null ? info.getAnswer() : placeholder.getTaskText();
    EduUtils.replaceAnswerPlaceholder(editor.getDocument(), placeholder, placeholder.getRealLength(), replacementText);
  }
}