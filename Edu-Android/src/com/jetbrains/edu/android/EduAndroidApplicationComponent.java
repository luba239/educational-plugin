package com.jetbrains.edu.android;

import com.intellij.lang.Language;
import com.intellij.openapi.components.ApplicationComponent;
import org.jetbrains.annotations.NotNull;

public class EduAndroidApplicationComponent implements ApplicationComponent {
  @Override
  public void initComponent() {
    new Language("edu-android") {
      @NotNull
      @Override
      public String getDisplayName() {
        return "Android";
      }
    };
  }

  @Override
  public void disposeComponent() {

  }

  @NotNull
  @Override
  public String getComponentName() {
    return "edu android";
  }
}
