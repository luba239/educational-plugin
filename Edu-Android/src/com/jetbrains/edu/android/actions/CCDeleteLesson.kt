package com.jetbrains.edu.android.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.edu.learning.StudyTaskManager
import com.jetbrains.edu.learning.courseFormat.StudyItem


class CCDeleteLesson : DeleteStudyItem("Delete Lesson") {
  override fun getStudyItem(project: Project, file: VirtualFile): StudyItem? {
    val course = StudyTaskManager.getInstance(project).course?:return null
    return course.getLesson(file.name)
  }}