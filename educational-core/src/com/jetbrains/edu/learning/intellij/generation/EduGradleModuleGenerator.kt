package com.jetbrains.edu.learning.intellij.generation

import com.android.SdkConstants
import com.android.tools.idea.gradle.util.GradleUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.PathUtil
import com.intellij.util.ReflectionUtil
import com.jetbrains.edu.learning.core.EduNames
import com.jetbrains.edu.learning.core.EduUtils
import com.jetbrains.edu.learning.courseFormat.Course
import com.jetbrains.edu.learning.courseFormat.Lesson
import com.jetbrains.edu.learning.courseFormat.tasks.Task
import com.jetbrains.edu.learning.courseGeneration.StudyGenerator
import com.jetbrains.edu.learning.intellij.EduIntelliJNames
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException

object EduGradleModuleGenerator {
    private val LOG = Logger.getInstance(EduModuleBuilderUtils::class.java)
    private val FAILED_MESSAGE = "Failed to generate gradle wrapper"
    private val requestor = EduGradleModuleGenerator.javaClass

    @JvmStatic
    fun createModule(baseDir: VirtualFile, name: String): EduGradleModule {
        val moduleDir = baseDir.createChildDirectory(requestor, name)
        val srcDir = moduleDir.createChildDirectory(requestor, EduNames.SRC)
        val testDir = moduleDir.createChildDirectory(requestor, EduNames.TEST)
        return EduGradleModule(srcDir, testDir)
    }

    @Throws(IOException::class)
    private fun createTests(task: Task, testDir: VirtualFile) {
        for ((path, text) in getTestTexts(task)) {
            StudyGenerator.createChildFile(testDir, PathUtil.getFileName(path), text)
        }
    }


    private fun getTestTexts(task: Task): Map<String, String> {
        val additionalMaterials = task.lesson.course.additionalMaterialsTask
        if (task.testsText.isEmpty() && additionalMaterials != null) {
            val lessonDirName = EduNames.LESSON + task.lesson.index
            val taskDirName = EduNames.TASK + task.index
            return additionalMaterials.testsText.filterKeys { key -> key.contains("$lessonDirName/$taskDirName/") }
        }
        return task.testsText
    }


    @Throws(IOException::class)
    private fun createTaskModule(lessonDir: VirtualFile, task: Task) {
        val taskDirName = EduNames.TASK + task.index
        val (src, test) = EduGradleModuleGenerator.createModule(lessonDir, taskDirName)
        for (taskFile in task.getTaskFiles().values) {
            StudyGenerator.createTaskFile(src, taskFile)
        }
        createTests(task, test)
    }


    @Throws(IOException::class)
    private fun createLessonModule(moduleDir: VirtualFile, lesson: Lesson) {
        val lessonDir = moduleDir.createChildDirectory(requestor, EduNames.LESSON + lesson.index)
        val taskList = lesson.getTaskList()
        for ((i, task) in taskList.withIndex()) {
            task.index = i + 1
            createTaskModule(lessonDir, task)
        }
    }


    @Throws(IOException::class)
    private fun createUtilModule(course: Course, moduleDir: VirtualFile) {
        val additionalMaterials = course.additionalMaterialsTask ?: return
        val utilFiles = mutableMapOf<String, String>()
        additionalMaterials.getTaskFiles().mapValuesTo(utilFiles) { (_, v) -> v.text }
        additionalMaterials.testsText.filterTo(utilFiles) { (path, _) -> path.contains(EduIntelliJNames.UTIL) }
        if (utilFiles.isEmpty()) {
            return
        }
        val (src, _) = EduGradleModuleGenerator.createModule(moduleDir, EduIntelliJNames.UTIL)
        for ((key, value) in utilFiles) {
            StudyGenerator.createChildFile(src, PathUtil.getFileName(key), value)
        }
    }

    private fun createGradleWrapper(moduleDirPath: String) {
        try {
            val projectDirPath = File(FileUtil.toSystemDependentName(moduleDirPath))
            if (!EduUtils.isAndroidStudio()) {
                GradleUtil.createGradleWrapper(projectDirPath)
                return
            }
            // GradleWrapper#create(File)
            // android studio sources don't match idea android plugin sources
            // and we need this code to compile with IJ and work with AS, so we have to use reflection
            val aClass = Class.forName("com.android.tools.idea.gradle.util.GradleWrapper")
            val method = ReflectionUtil.getDeclaredMethod(aClass, "create", File::class.java)
            method?.invoke(null, projectDirPath)
        } catch (e: ClassNotFoundException) {
            LOG.error(FAILED_MESSAGE, e)
        } catch (e: IllegalAccessException) {
            LOG.error(FAILED_MESSAGE, e)
        } catch (e: InvocationTargetException) {
            LOG.error(FAILED_MESSAGE, e)
        } catch (e: IOException) {
            LOG.error(FAILED_MESSAGE, e)
        }

    }

    @JvmStatic
    @Throws(IOException::class)
    fun createCourseContent(project: Project, course: Course, moduleDirPath: String) {
        val moduleDir = VfsUtil.findFileByIoFile(File(FileUtil.toSystemDependentName(moduleDirPath)), true) ?: return
        val lessons = course.lessons
        for ((i, lesson) in lessons.withIndex()) {
            lesson.index = i + 1
            createLessonModule(moduleDir, lesson)
        }

        createGradleWrapper(moduleDirPath)
        File(FileUtil.toSystemDependentName(project.basePath!!), "gradlew").setExecutable(true)
        StudyGenerator.createFromInternalTemplate(project, moduleDir, SdkConstants.FN_BUILD_GRADLE)
        StudyGenerator.createFromInternalTemplate(project, moduleDir, SdkConstants.FN_SETTINGS_GRADLE)

        createUtilModule(course, moduleDir)
    }
}




