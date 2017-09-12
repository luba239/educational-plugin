package com.jetbrains.edu.learning.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.jetbrains.edu.learning.StudyTaskManager;
import com.jetbrains.edu.learning.core.EduNames;
import com.jetbrains.edu.learning.core.EduUtils;
import com.jetbrains.edu.learning.courseFormat.Course;
import com.jetbrains.edu.learning.courseFormat.Lesson;
import com.jetbrains.edu.learning.courseFormat.RemoteCourse;
import com.jetbrains.edu.learning.stepic.EduStepicConnector;

import java.io.IOException;
import java.util.ArrayList;

import static com.jetbrains.edu.learning.StudyUtils.execCancelable;

public class StudyResetCourseAction extends DumbAwareAction {
  private static final Logger LOG = Logger.getInstance(StudySyncCourseAction.class);

  public StudyResetCourseAction() {
    super("Reset Course");
  }

  @Override
  public void actionPerformed(AnActionEvent e) {
    Project project = e.getProject();
    assert project != null;

    StudyTaskManager studyTaskManager = StudyTaskManager.getInstance(project);
    Course course = studyTaskManager.getCourse();
    assert course != null;

    removeAllLessons(project, course);
    studyTaskManager.setMyLoadSolutions(false);

    ApplicationManager.getApplication().invokeLater(() -> {
      ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
        ProgressManager.getInstance().getProgressIndicator().setIndeterminate(true);
        return execCancelable(() -> {
          EduStepicConnector.updateCourse(project);
          return true;
        });
      }, "Resetting Course", true, project);
      EduUtils.synchronize();
    });
  }

  private static void removeAllLessons(Project project, Course course) {
    ApplicationManager.getApplication().runWriteAction(() -> {
      for (Lesson lesson : course.getLessons()) {
        final String lessonDirName = EduNames.LESSON + String.valueOf(lesson.getIndex());
        try {
          VirtualFile lessonFile = project.getBaseDir().findChild(lessonDirName);
          if (lessonFile != null) {
            lessonFile.delete(StudyResetCourseAction.class);
          }
        }
        catch (IOException e1) {
          LOG.warn(e1.getMessage());
        }
      }
    });
    course.setLessons(new ArrayList<>());
  }

  @Override
  public void update(AnActionEvent e) {
    Project project = e.getProject();
    if (project != null) {
      Course course = StudyTaskManager.getInstance(project).getCourse();
      if (course instanceof RemoteCourse) {
        e.getPresentation().setEnabledAndVisible(true);
        return;
      }
    }
    e.getPresentation().setEnabledAndVisible(false);
  }
}
