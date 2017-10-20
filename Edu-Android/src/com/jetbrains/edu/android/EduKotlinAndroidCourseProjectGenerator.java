package com.jetbrains.edu.android;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.DumbModePermission;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.jetbrains.edu.learning.EduPluginConfigurator;
import com.jetbrains.edu.learning.StudyUtils;
import com.jetbrains.edu.learning.courseFormat.Course;
import com.jetbrains.edu.learning.intellij.generation.EduGradleModuleGenerator;
import com.jetbrains.edu.learning.intellij.generation.EduProjectGenerator;
import com.jetbrains.edu.learning.newproject.EduCourseProjectGenerator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

import static com.android.tools.idea.gradle.util.Projects.getBaseDirPath;

class EduKotlinAndroidCourseProjectGenerator implements EduCourseProjectGenerator<Object> {
  private static final Logger LOG = Logger.getInstance(EduKotlinAndroidCourseProjectGenerator.class);

  private final Course myCourse;

  public EduKotlinAndroidCourseProjectGenerator(Course course) {
    myCourse = course;
  }

  @NotNull
  @Override
  public Object getProjectSettings() {
    return new Object();
  }

  @Override
  public void generateProject(@NotNull Project project, @NotNull VirtualFile baseDir, @Nullable Object settings, @NotNull Module module) {
    EduProjectGenerator generator = new EduProjectGenerator();
    generator.setSelectedCourse(myCourse);
    generator.generateProject(project, project.getBaseDir());
    myCourse.setCourseType("Tutorial");

    EduPluginConfigurator.INSTANCE.forLanguage(myCourse.getLanguageById())
            .createCourseModuleContent(ModuleManager.getInstance(project).getModifiableModel(),
            project, myCourse, project.getBasePath());
    ApplicationManager.getApplication().invokeLater(() -> DumbService.allowStartingDumbModeInside(DumbModePermission.MAY_START_BACKGROUND,
            () -> ApplicationManager.getApplication().runWriteAction(() -> StudyUtils.registerStudyToolWindow(myCourse, project))));
  }

  @Override
  public void afterProjectGenerated(@NotNull Project project) {
    File projectPath = getBaseDirPath(project);
    EduGradleModuleGenerator.INSTANCE.createGradleWrapper(projectPath.getAbsolutePath());

    File gradlew = new File(projectPath, "gradlew");
    if (gradlew.exists() && !gradlew.canExecute()) {
      if (!gradlew.setExecutable(true)) {
        LOG.warn("Unable to make gradlew executable");
      }
    }
  }
}
