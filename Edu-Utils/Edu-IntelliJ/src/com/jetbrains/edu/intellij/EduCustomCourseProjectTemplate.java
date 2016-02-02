package com.jetbrains.edu.intellij;

import com.intellij.icons.AllIcons;
import com.intellij.ide.util.projectWizard.AbstractModuleBuilder;
import com.intellij.openapi.ui.ValidationInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

class EduCustomCourseProjectTemplate implements EduIntelliJProjectTemplate {
    @NotNull
    @Override
    public String getName() {
        return "Custom Course";
    }

    @Nullable
    @Override
    public String getDescription() {
        return "Custom Course Addition";
    }

    @Override
    public Icon getIcon() {
        return AllIcons.General.SecondaryGroup;
    }

    @NotNull
    @Override
    public AbstractModuleBuilder createModuleBuilder() {
        return new EduCustomCourseModuleBuilder();
    }

    @Nullable
    @Override
    public ValidationInfo validateSettings() {
        return null;
    }
}