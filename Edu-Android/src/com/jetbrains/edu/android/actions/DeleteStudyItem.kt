package com.jetbrains.edu.android.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.edu.coursecreator.CCUtils
import com.jetbrains.edu.learning.courseFormat.StudyItem

abstract class DeleteStudyItem(text: String) : DumbAwareAction(text) {
  override fun actionPerformed(e: AnActionEvent?) {
    val dataContext = e?.dataContext!!
    val project = e.project!!
    val virtualFile = CommonDataKeys.VIRTUAL_FILE.getData(dataContext)!!
    ApplicationManager.getApplication().runWriteAction({
      CommandProcessor.getInstance().executeCommand(project, {virtualFile.delete(DeleteStudyItem::class.java)}, "", Object())
    })
  }

  override fun update(e: AnActionEvent?) {
    e?:return
    val presentation = e.presentation
    presentation.isEnabledAndVisible = false
    val dataContext = e.dataContext
    val project = e.project?:return
    if (CCUtils.isCourseCreator(project).not()) {
      return
    }
    val virtualFile = CommonDataKeys.VIRTUAL_FILE.getData(dataContext)?:return
    presentation.isEnabledAndVisible = getStudyItem(project, virtualFile) != null
  }

  abstract fun getStudyItem(project: Project, file: VirtualFile): StudyItem?
}