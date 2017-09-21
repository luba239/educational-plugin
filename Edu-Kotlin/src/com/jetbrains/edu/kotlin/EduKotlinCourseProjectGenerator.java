package com.jetbrains.edu.kotlin;

import com.intellij.facet.ui.ValidationResult;
import com.intellij.ide.util.newProjectWizard.AbstractProjectWizard;
import com.intellij.ide.util.newProjectWizard.StepSequence;
import com.intellij.ide.util.projectWizard.ProjectBuilder;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ui.configuration.actions.NewModuleAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.DirectoryProjectGenerator;
import com.jetbrains.edu.learning.StudyTaskManager;
import com.jetbrains.edu.learning.core.EduUtils;
import com.jetbrains.edu.learning.intellij.generation.EduGradleModuleGenerator;
import com.jetbrains.edu.learning.intellij.generation.EduIntellijCourseProjectGeneratorBase;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.idea.KotlinIcons;

import javax.swing.*;
import java.io.IOException;

class EduKotlinCourseProjectGenerator extends EduIntellijCourseProjectGeneratorBase {

  private static final Logger LOG = Logger.getInstance(EduKotlinCourseProjectGenerator.class);

  @NotNull
  @Override
  public DirectoryProjectGenerator getDirectoryProjectGenerator() {
    return new DirectoryProjectGenerator() {
      @Nls
      @NotNull
      @Override
      public String getName() {
        return "Kotlin Koans generator";
      }

      @Nullable
      @Override
      public Icon getLogo() {
        return KotlinIcons.SMALL_LOGO;
      }

      @Override
      public void generateProject(@NotNull Project project, @NotNull VirtualFile baseDir, @Nullable Object settings, @NotNull Module module) {
        if (EduUtils.isAndroidStudio()) {
          ApplicationManager.getApplication().runWriteAction(() -> {
            try {
              StudyTaskManager.getInstance(project).setCourse(myCourse);
              myCourse.initCourse(false);
              EduGradleModuleGenerator.createCourseContent(project, myCourse, baseDir.getPath());
            } catch (IOException e) {
              LOG.error("Failed to generate course", e);
            }
          });
          return;
        }
        new NewModuleAction().createModuleFromWizard(project, null, new AbstractProjectWizard("", project, baseDir.getPath()) {
          @Override
          public StepSequence getSequence() {
            return null;
          }

          @Override
          public ProjectBuilder getProjectBuilder() {
            return new EduKotlinKoansModuleBuilder(myCourse);
          }
        });
        setJdk(project);
        setCompilerOutput(project);

      }

      @NotNull
      @Override
      public ValidationResult validate(@NotNull String baseDirPath) {
        return ValidationResult.OK;
      }
    };

  }
}
