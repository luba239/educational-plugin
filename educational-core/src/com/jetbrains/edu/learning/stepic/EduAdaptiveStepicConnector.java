package com.jetbrains.edu.learning.stepic;

import com.google.common.collect.ImmutableMap;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.internal.LinkedTreeMap;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.lang.Language;
import com.intellij.lang.LanguageCommenters;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.jetbrains.edu.learning.EduPluginConfigurator;
import com.jetbrains.edu.learning.EduSettings;
import com.jetbrains.edu.learning.StudyTaskManager;
import com.jetbrains.edu.learning.StudyUtils;
import com.jetbrains.edu.learning.actions.StudyCheckAction;
import com.jetbrains.edu.learning.checker.StudyCheckResult;
import com.jetbrains.edu.learning.core.EduNames;
import com.jetbrains.edu.learning.courseFormat.*;
import com.jetbrains.edu.learning.courseFormat.tasks.*;
import com.jetbrains.edu.learning.navigation.StudyNavigator;
import com.jetbrains.edu.learning.ui.StudyToolWindow;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.jetbrains.edu.learning.stepic.EduStepicConnector.getStep;

public class EduAdaptiveStepicConnector {
  public static final String PYCHARM_COMMENT = " Posted from PyCharm Edu\n";
  public static final int NEXT_RECOMMENDATION_REACTION = 2;
  public static final int TOO_HARD_RECOMMENDATION_REACTION = 0;
  public static final int TOO_BORING_RECOMMENDATION_REACTION = -1;
  public static final String LOADING_NEXT_RECOMMENDATION = "Loading Next Recommendation";
  private static final Logger LOG = Logger.getInstance(EduAdaptiveStepicConnector.class);
  private static final int CONNECTION_TIMEOUT = 60 * 1000;

  @Nullable
  public static Task getNextRecommendation(@NotNull Project project, @NotNull RemoteCourse course) {
    try {
      final CloseableHttpClient client = EduStepicAuthorizedClient.getHttpClient();
      if (client == null) {
        LOG.warn("Http client is null");
        return null;
      }

      StepicUser user = EduSettings.getInstance().getUser();
      if (user == null) {
        LOG.warn("User is null");
        return null;
      }

      final URI uri = new URIBuilder(EduStepicNames.STEPIC_API_URL + EduStepicNames.RECOMMENDATIONS_URL)
        .addParameter(EduNames.COURSE, String.valueOf(course.getId()))
        .build();
      final HttpGet request = new HttpGet(uri);
      setTimeout(request);

      final CloseableHttpResponse response = client.execute(request);
      final HttpEntity responseEntity = response.getEntity();
      final String responseString = responseEntity != null ? EntityUtils.toString(responseEntity) : "";

      final int statusCode = response.getStatusLine().getStatusCode();
      EntityUtils.consume(responseEntity);
      if (statusCode == HttpStatus.SC_OK) {
        final Gson gson = new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();
        final StepicWrappers.RecommendationWrapper recomWrapper = gson.fromJson(responseString, StepicWrappers.RecommendationWrapper.class);

        if (recomWrapper.recommendations.length != 0) {
          final StepicWrappers.Recommendation recommendation = recomWrapper.recommendations[0];
          final String lessonId = recommendation.lesson;
          final StepicWrappers.LessonContainer lessonContainer = EduStepicAuthorizedClient.getFromStepic(EduStepicNames.LESSONS + lessonId,
                                                                                                         StepicWrappers.LessonContainer.class);
          if (lessonContainer != null && lessonContainer.lessons.size() == 1) {
            final Lesson realLesson = lessonContainer.lessons.get(0);
            course.getLessons().get(0).setId(Integer.parseInt(lessonId));

            for (int stepId : realLesson.steps) {
              StepicWrappers.StepSource step = getStep(stepId);
              String stepType = step.block.name;
              StepikTaskBuilder taskBuilder = new StepikTaskBuilder(course, realLesson.getName(), step, stepId, user.getId());
              if (taskBuilder.isSupported(stepType)) {
                final Task taskFromStep = taskBuilder.createTask(stepType);
                if (taskFromStep != null) return taskFromStep;
              }
              else {
                return skipRecommendation(project, course, user, lessonId);
              }
            }
          }
          else {
            LOG.warn("Got unexpected number of lessons: " + (lessonContainer == null ? null : lessonContainer.lessons.size()));
          }
        }
        else {
          LOG.warn("Got empty recommendation for the task: " + responseString);
        }
      }
      else {
        throw new IOException("Stepic returned non 200 status code: " + responseString);
      }
    }
    catch (IOException e) {
      LOG.warn(e.getMessage());
      ApplicationManager.getApplication()
        .invokeLater(() -> StudyUtils.showErrorPopupOnToolbar(project, "Connection problems, Please, try again"));
    }
    catch (URISyntaxException e) {
      LOG.warn(e.getMessage());
    }
    return null;
  }

  private static Task skipRecommendation(@NotNull Project project, @NotNull RemoteCourse course, StepicUser user, String lessonId) {
    postRecommendationReaction(lessonId, String.valueOf(user.getId()), TOO_HARD_RECOMMENDATION_REACTION);
    return getNextRecommendation(project, course);
  }

  @Nullable
  private static StepicWrappers.AdaptiveAttemptWrapper.Attempt getAttemptForStep(int stepId, int userId) {
    try {
      final List<StepicWrappers.AdaptiveAttemptWrapper.Attempt> attempts = getAttempts(stepId, userId);
      if (attempts != null && attempts.size() > 0) {
        final StepicWrappers.AdaptiveAttemptWrapper.Attempt attempt = attempts.get(0);
        return attempt.isActive() ? attempt : createNewAttempt(stepId);
      }
      else {
        return createNewAttempt(stepId);
      }
    }
    catch (URISyntaxException | IOException e) {
      LOG.warn(e.getMessage());
    }
    return null;
  }

  private static StepicWrappers.AdaptiveAttemptWrapper.Attempt createNewAttempt(int id) throws IOException {
    final String response = EduStepicConnector.postAttempt(id);
    final StepicWrappers.AdaptiveAttemptContainer attempt = new Gson().fromJson(response, StepicWrappers.AdaptiveAttemptContainer.class);
    return attempt.attempts.get(0);
  }

  @Nullable
  private static List<StepicWrappers.AdaptiveAttemptWrapper.Attempt> getAttempts(int stepId, int userId)
    throws URISyntaxException, IOException {
    final URI attemptUrl = new URIBuilder(EduStepicNames.ATTEMPTS)
      .addParameter("step", String.valueOf(stepId))
      .addParameter("user", String.valueOf(userId))
      .build();
    final StepicWrappers.AdaptiveAttemptContainer attempt =
      EduStepicAuthorizedClient.getFromStepic(attemptUrl.toString(), StepicWrappers.AdaptiveAttemptContainer.class);
    return attempt == null ? null : attempt.attempts;
  }

  private static void setTimeout(HttpGet request) {
    final RequestConfig requestConfig = RequestConfig.custom()
      .setConnectionRequestTimeout(CONNECTION_TIMEOUT)
      .setConnectTimeout(CONNECTION_TIMEOUT)
      .setSocketTimeout(CONNECTION_TIMEOUT)
      .build();
    request.setConfig(requestConfig);
  }

  private static void setTimeout(HttpPost request) {
    final RequestConfig requestConfig = RequestConfig.custom()
      .setConnectionRequestTimeout(CONNECTION_TIMEOUT)
      .setConnectTimeout(CONNECTION_TIMEOUT)
      .setSocketTimeout(CONNECTION_TIMEOUT)
      .build();
    request.setConfig(requestConfig);
  }

  public static boolean postRecommendationReaction(@NotNull String lessonId, @NotNull String user, int reaction) {
    final HttpPost post = new HttpPost(EduStepicNames.STEPIC_API_URL + EduStepicNames.RECOMMENDATION_REACTIONS_URL);
    final String json = new Gson()
      .toJson(new StepicWrappers.RecommendationReactionWrapper(new StepicWrappers.RecommendationReaction(reaction, user, lessonId)));
    post.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
    final CloseableHttpClient client = EduStepicAuthorizedClient.getHttpClient();
    if (client == null) return false;
    setTimeout(post);
    try {
      final CloseableHttpResponse execute = client.execute(post);
      final int statusCode = execute.getStatusLine().getStatusCode();
      final HttpEntity entity = execute.getEntity();
      final String entityString = EntityUtils.toString(entity);
      EntityUtils.consume(entity);
      if (statusCode == HttpStatus.SC_CREATED) {
        return true;
      }
      else {
        LOG.warn("Stepic returned non-201 status code: " + statusCode + " " + entityString);
        return false;
      }
    }
    catch (IOException e) {
      LOG.warn(e.getMessage());
      return false;
    }
  }

  public static void addNextRecommendedTask(@NotNull Project project,
                                            @NotNull Lesson lesson,
                                            @NotNull ProgressIndicator indicator,
                                            int reactionToPost) {
    final Course course = StudyTaskManager.getInstance(project).getCourse();
    if (!(course instanceof RemoteCourse)) {
      LOG.warn("Course is in incorrect state");
      ApplicationManager.getApplication().invokeLater(() -> StudyUtils.showErrorPopupOnToolbar(project,
                                                                                               "Can't get next recommendation: course is broken"));
      return;
    }

    indicator.checkCanceled();
    final StepicUser user = EduSettings.getInstance().getUser();
    if (user == null) {
      LOG.warn("Can't get next recommendation: user is null");
      ApplicationManager.getApplication().invokeLater(() -> StudyUtils.showErrorPopupOnToolbar(project,
                                                                                               "Can't get next recommendation: you're not logged in"));
      return;
    }

    final boolean reactionPosted = postRecommendationReaction(String.valueOf(lesson.getId()), String.valueOf(user.getId()), reactionToPost);
    if (!reactionPosted) {
      LOG.warn("Recommendation reaction wasn't posted");
      ApplicationManager.getApplication().invokeLater(() -> StudyUtils.showErrorPopupOnToolbar(project, "Couldn't post your reactionToPost"));
      return;
    }

    indicator.checkCanceled();
    final Task task = getNextRecommendation(project, (RemoteCourse)course);
    if (task == null) {
      ApplicationManager.getApplication().invokeLater(() -> StudyUtils.showErrorPopupOnToolbar(project,
                                                                                               "Couldn't load a new recommendation"));
      return;
    }

    task.initTask(lesson, false);
    boolean replaceCurrentTask = reactionToPost == TOO_HARD_RECOMMENDATION_REACTION || reactionToPost == TOO_BORING_RECOMMENDATION_REACTION;
    if (replaceCurrentTask) {
      replaceCurrentTask(project, task, lesson);
    }
    else {
      addAsNextTask(project, task, lesson);
    }

    ApplicationManager.getApplication().invokeLater(() -> {
      VirtualFileManager.getInstance().refreshWithoutFileWatcher(false);
      ProjectView.getInstance(project).refresh();
      StudyNavigator.navigateToTask(project, task);
    });
  }

  private static void addAsNextTask(@NotNull Project project, @NotNull Task task, @NotNull Lesson lesson) {
    Course course = StudyTaskManager.getInstance(project).getCourse();
    assert course != null;

    lesson.addTask(task);
    task.setIndex(lesson.getTaskList().size());
    lesson.initLesson(course, true);

    final String lessonName = EduNames.LESSON + lesson.getIndex();
    createFilesForNewTask(project, task, lessonName, course.getLanguageById());
  }

  private static void createFilesForNewTask(@NotNull Project project,
                                            @NotNull Task task,
                                            @NotNull String lessonName,
                                            @NotNull Language language) {
    final VirtualFile lessonDir = project.getBaseDir().findChild(lessonName);
    if (lessonDir == null) {
      return;
    }

    ApplicationManager.getApplication().invokeLater(() -> ApplicationManager.getApplication().runWriteAction(() -> {
      EduPluginConfigurator.INSTANCE.forLanguage(language).createTaskContent(project, task, lessonDir, task.getLesson().getCourse());
    }));
  }

  public static void replaceCurrentTask(@NotNull Project project, @NotNull Task task, @NotNull Lesson lesson) {
    Course course = StudyTaskManager.getInstance(project).getCourse();
    assert course != null;

    int taskIndex = lesson.getTaskList().size();

    task.setIndex(taskIndex);
    lesson.getTaskList().set(taskIndex - 1, task);

    final String lessonName = EduNames.LESSON + lesson.getIndex();
    updateProjectFiles(project, task, lessonName, course.getLanguageById());
    setToolWindowText(project, task);
  }

  private static void updateProjectFiles(@NotNull Project project, @NotNull Task task, @NotNull String lessonName, Language language) {
    final VirtualFile lessonDir = project.getBaseDir().findChild(lessonName);
    if (lessonDir == null) {
      return;
    }

    ApplicationManager.getApplication().invokeLater(() -> ApplicationManager.getApplication().runWriteAction(() -> {
      try {
        removeOldProjectFiles(lessonDir, task.getIndex());
        EduPluginConfigurator.INSTANCE.forLanguage(language).createTaskContent(project, task, lessonDir, task.getLesson().getCourse());
      }
      catch (IOException e) {
        LOG.warn(e.getMessage());
      }
    }));
  }

  private static void removeOldProjectFiles(@NotNull VirtualFile lessonDir, int taskIndex) throws IOException {
    final VirtualFile taskDir = lessonDir.findChild(EduNames.TASK + taskIndex);
    if (taskDir == null) {
      LOG.warn("Failed to update files for a new recommendation: task directory is null");
      return;
    }

    taskDir.delete(EduAdaptiveStepicConnector.class);
  }

  private static void setToolWindowText(@NotNull Project project, @NotNull Task task) {
    final StudyToolWindow window = StudyUtils.getStudyToolWindow(project);
    if (window != null) {
      window.setCurrentTask(project, task);
    }
  }

  private static String getCodeTemplateForTask(@NotNull Language language,
                                               @Nullable LinkedTreeMap codeTemplates) {
    if (codeTemplates != null) {
      final String languageString = EduPluginConfigurator.INSTANCE.forLanguage(language).getStepikDefaultLanguage();
      return (String)codeTemplates.get(languageString);
    }

    return null;
  }

  public static StudyCheckResult checkChoiceTask(@NotNull ChoiceTask task, @NotNull StepicUser user) {
    if (task.getSelectedVariants().isEmpty()) return new StudyCheckResult(StudyStatus.Failed, "No variants selected");
    final StepicWrappers.AdaptiveAttemptWrapper.Attempt attempt = getAttemptForStep(task.getStepId(), user.getId());

    if (attempt != null) {
      final int attemptId = attempt.id;

      final boolean isActiveAttempt = task.getSelectedVariants().stream()
        .allMatch(index -> attempt.dataset.options.get(index).equals(task.getChoiceVariants().get(index)));
      if (!isActiveAttempt) return new StudyCheckResult(StudyStatus.Failed, "Your solution is out of date. Please try again");
      final StepicWrappers.SubmissionToPostWrapper wrapper = new StepicWrappers.SubmissionToPostWrapper(String.valueOf(attemptId),
                                                                                                        createChoiceTaskAnswerArray(task));
      final StudyCheckResult result = doAdaptiveCheck(wrapper, attemptId, user.getId());
      if (result.getStatus() == StudyStatus.Failed) {
        try {
          createNewAttempt(task.getStepId());
          StepicWrappers.StepSource step = getStep(task.getStepId());
          StepikTaskBuilder taskBuilder = new StepikTaskBuilder((RemoteCourse)task.getLesson().getCourse(), task.getName(),
                                                                step, task.getStepId(), user.getId());
          final Task updatedTask = taskBuilder.createTask(step.block.name);
          if (updatedTask instanceof ChoiceTask) {
            final List<String> variants = ((ChoiceTask)updatedTask).getChoiceVariants();
            task.setChoiceVariants(variants);
            task.setSelectedVariants(new ArrayList<>());
          }
        }
        catch (IOException e) {
          LOG.warn(e.getMessage());
        }
      }
      return result;
    }

    return new StudyCheckResult(StudyStatus.Unchecked, StudyCheckAction.FAILED_CHECK_LAUNCH);
  }

  private static boolean[] createChoiceTaskAnswerArray(@NotNull ChoiceTask task) {
    final List<Integer> selectedVariants = task.getSelectedVariants();
    final boolean[] answer = new boolean[task.getChoiceVariants().size()];
    for (Integer index : selectedVariants) {
      answer[index] = true;
    }
    return answer;
  }

  public static StudyCheckResult checkCodeTask(@NotNull Project project, @NotNull Task task, @NotNull StepicUser user) {
    int attemptId = -1;
    try {
      attemptId = getAttemptId(task);
    }
    catch (IOException e) {
      LOG.warn(e.getMessage());
    }
    if (attemptId != -1) {
      Course course = task.getLesson().getCourse();
      Language courseLanguage = course.getLanguageById();
      final Editor editor = StudyUtils.getSelectedEditor(project);
      if (editor != null) {
        String commentPrefix = LanguageCommenters.INSTANCE.forLanguage(courseLanguage).getLineCommentPrefix();
        final String answer = commentPrefix + PYCHARM_COMMENT + editor.getDocument().getText();
        String defaultLanguage = EduPluginConfigurator.INSTANCE.forLanguage(courseLanguage).getStepikDefaultLanguage();
        final StepicWrappers.SubmissionToPostWrapper submissionToPost =
          new StepicWrappers.SubmissionToPostWrapper(String.valueOf(attemptId), defaultLanguage, answer);
        return doAdaptiveCheck(submissionToPost, attemptId, user.getId());
      }
    }
    else {
      LOG.warn("Got an incorrect attempt id: " + attemptId);
    }
    return new StudyCheckResult(StudyStatus.Unchecked, StudyCheckAction.FAILED_CHECK_LAUNCH);
  }

  private static StudyCheckResult doAdaptiveCheck(@NotNull StepicWrappers.SubmissionToPostWrapper submission,
                                                       int attemptId, int userId) {
    final CloseableHttpClient client = EduStepicAuthorizedClient.getHttpClient();
    if (client != null) {
      StepicWrappers.ResultSubmissionWrapper wrapper = postResultsForCheck(client, submission);
      if (wrapper != null) {
        wrapper = getCheckResults(client, wrapper, attemptId, userId);
        if (wrapper.submissions.length > 0) {
          final String status = wrapper.submissions[0].status;
          final String hint = wrapper.submissions[0].hint;
          final boolean isSolved = !status.equals("wrong");
          return new StudyCheckResult(isSolved ? StudyStatus.Solved : StudyStatus.Failed, hint.isEmpty() ? StringUtil.capitalize(status) + " solution" : hint);
        }
        else {
          LOG.warn("Got a submission wrapper with incorrect submissions number: " + wrapper.submissions.length);
        }
      }
      else {
        LOG.warn("Can't do adaptive check: " + "wrapper is null");
        return new StudyCheckResult(StudyStatus.Unchecked, "Can't get check results for Stepik");
      }
    }
    return new StudyCheckResult(StudyStatus.Unchecked, StudyCheckAction.FAILED_CHECK_LAUNCH);
  }

  @Nullable
  private static StepicWrappers.ResultSubmissionWrapper postResultsForCheck(@NotNull final CloseableHttpClient client,
                                                                            @NotNull StepicWrappers.SubmissionToPostWrapper submissionToPostWrapper) {
    final CloseableHttpResponse response;
    try {
      final HttpPost httpPost = new HttpPost(EduStepicNames.STEPIC_API_URL + EduStepicNames.SUBMISSIONS);
      setTimeout(httpPost);
      try {
        httpPost.setEntity(new StringEntity(new Gson().toJson(submissionToPostWrapper)));
      }
      catch (UnsupportedEncodingException e) {
        LOG.warn(e.getMessage());
      }
      response = client.execute(httpPost);
      final HttpEntity entity = response.getEntity();
      final String entityString = EntityUtils.toString(entity);
      EntityUtils.consume(entity);
      return new Gson().fromJson(entityString, StepicWrappers.ResultSubmissionWrapper.class);
    }
    catch (IOException e) {
      LOG.warn(e.getMessage());
    }
    return null;
  }

  @NotNull
  private static StepicWrappers.ResultSubmissionWrapper getCheckResults(@NotNull CloseableHttpClient client,
                                                                        @NotNull StepicWrappers.ResultSubmissionWrapper wrapper,
                                                                        int attemptId,
                                                                        int userId) {
    try {
      while (wrapper.submissions.length == 1 && wrapper.submissions[0].status.equals("evaluation")) {
        TimeUnit.MILLISECONDS.sleep(500);
        final URI submissionURI = new URIBuilder(EduStepicNames.STEPIC_API_URL + EduStepicNames.SUBMISSIONS)
          .addParameter("attempt", String.valueOf(attemptId))
          .addParameter("order", "desc")
          .addParameter("user", String.valueOf(userId))
          .build();
        final HttpGet httpGet = new HttpGet(submissionURI);
        setTimeout(httpGet);
        final CloseableHttpResponse httpResponse = client.execute(httpGet);
        final HttpEntity entity = httpResponse.getEntity();
        final String entityString = EntityUtils.toString(entity);
        EntityUtils.consume(entity);
        wrapper = new Gson().fromJson(entityString, StepicWrappers.ResultSubmissionWrapper.class);
      }
    }
    catch (InterruptedException | URISyntaxException | IOException e) {
      LOG.warn(e.getMessage());
    }
    return wrapper;
  }

  private static int getAttemptId(@NotNull Task task) throws IOException {
    final StepicWrappers.AdaptiveAttemptWrapper attemptWrapper = new StepicWrappers.AdaptiveAttemptWrapper(task.getStepId());

    final HttpPost post = new HttpPost(EduStepicNames.STEPIC_API_URL + EduStepicNames.ATTEMPTS);
    post.setEntity(new StringEntity(new Gson().toJson(attemptWrapper)));

    final CloseableHttpClient client = EduStepicAuthorizedClient.getHttpClient();
    if (client == null) return -1;
    setTimeout(post);
    final CloseableHttpResponse httpResponse = client.execute(post);
    final int statusCode = httpResponse.getStatusLine().getStatusCode();
    final HttpEntity entity = httpResponse.getEntity();
    final String entityString = EntityUtils.toString(entity);
    EntityUtils.consume(entity);
    if (statusCode == HttpStatus.SC_CREATED) {
      final StepicWrappers.AttemptContainer container =
        new Gson().fromJson(entityString, StepicWrappers.AttemptContainer.class);
      return (container.attempts != null && !container.attempts.isEmpty()) ? container.attempts.get(0).id : -1;
    }
    return -1;
  }

  private static void createTestFileFromSamples(@NotNull Task task,
                                                @NotNull List<List<String>> samples) {

    String testText = "from test_helper import check_samples\n\n" +
                      "if __name__ == '__main__':\n" +
                      "    check_samples(samples=" + new GsonBuilder().create().toJson(samples) + ")";
    task.addTestsTexts("tests.py", testText);
  }

  @NotNull
  public static List<Integer> getEnrolledCoursesIds(@NotNull StepicUser stepicUser) {
    try {
      final URI enrolledCoursesUri = new URIBuilder(EduStepicNames.COURSES).addParameter("enrolled", "true").build();
      final List<RemoteCourse> courses = EduStepicAuthorizedClient.getFromStepic(enrolledCoursesUri.toString(),
                                                                               StepicWrappers.CoursesContainer.class,
                                                                               stepicUser).courses;
      final ArrayList<Integer> ids = new ArrayList<>();
      for (RemoteCourse course : courses) {
        ids.add(course.getId());
      }
      return ids;
    }
    catch (IOException | URISyntaxException e) {
      LOG.warn(e.getMessage());
    }
    return Collections.emptyList();
  }

  public static String wrapAdaptiveCourseText(Task task, @NotNull String text) {
    String finalText = text;
    if (task instanceof TheoryTask) {
      finalText += "\n\n<b>Note</b>: This theory task aims to help you solve difficult tasks. " +
                   "Please, read it and press \"Check\" to go further.";
    }
    else if (!(task instanceof ChoiceTask)) {
      finalText += "\n\n<b>Note</b>: Use standard input to obtain input for the task.";
    }
    finalText += getFooterWithLink(task);

    return finalText;
  }

  @NotNull
  private static String getFooterWithLink(Task task) {
    return
      "<div class=\"footer\">" + "<a href=" + EduStepikUtils.getAdaptiveLink(task) + ">Open on Stepik</a>" + "</div>";
  }

  private static class StepikTaskBuilder {
    private static final String TASK_NAME = "task";
    private int myStepId;
    private int myUserId;
    private final String myName;
    private final Language myLanguage;
    private StepicWrappers.Step myStep;
    private final Map<String, Computable<Task>> taskTypes = ImmutableMap.of(
      "code", () -> codeTask(),
      "choice", () -> choiceTask(),
      "text", () -> theoryTask(),
      "task", () -> pycharmTask()
    );

    public StepikTaskBuilder(@NotNull RemoteCourse course,
                             @NotNull String name,
                             @NotNull StepicWrappers.StepSource step,
                             int stepId, int userId) {
      myName = name;
      myStep = step.block;
      myStepId = stepId;
      myUserId = userId;
      myLanguage = course.getLanguageById();
    }

    @Nullable
    public Task createTask(String type) {
      return taskTypes.get(type).compute();
    }

    public boolean isSupported(String type) {
      return taskTypes.containsKey(type);
    }

    @NotNull
    private CodeTask codeTask() {
      CodeTask task = new CodeTask(myName);
      task.setStepId(myStepId);

      task.setStatus(StudyStatus.Unchecked);
      final StringBuilder taskDescription = new StringBuilder(myStep.text);
      if (myStep.options.samples != null) {
        taskDescription.append("<br>");
        for (List<String> sample : myStep.options.samples) {
          if (sample.size() == 2) {
            taskDescription.append("<b>Sample Input:</b><br>");
            taskDescription.append(StringUtil.replace(sample.get(0), "\n", "<br>"));
            taskDescription.append("<br>");
            taskDescription.append("<b>Sample Output:</b><br>");
            taskDescription.append(StringUtil.replace(sample.get(1), "\n", "<br>"));
            taskDescription.append("<br><br>");
          }
        }
      }

      if (myStep.options.executionMemoryLimit != null && myStep.options.executionTimeLimit != null) {
        taskDescription.append("<br>").append("<b>Memory limit</b>: ").append(myStep.options.executionMemoryLimit).append(" Mb")
          .append("<br>")
          .append("<b>Time limit</b>: ").append(myStep.options.executionTimeLimit).append("s").append("<br><br>");
      }
      task.addTaskText(EduNames.TASK, taskDescription.toString());

      if (myStep.options.test != null) {
        for (StepicWrappers.FileWrapper wrapper : myStep.options.test) {
          task.addTestsTexts(wrapper.name, wrapper.text);
        }
      }
      else {
        if (myLanguage.isKindOf("Python") && myStep.options.samples != null) {
          createTestFileFromSamples(task, myStep.options.samples);
        }
      }

      task.taskFiles = new HashMap<>();
      if (myStep.options.files != null) {
        for (TaskFile taskFile : myStep.options.files) {
          task.taskFiles.put(taskFile.name, taskFile);
        }
      }
      else {
        final String templateForTask = getCodeTemplateForTask(myLanguage, myStep.options.codeTemplates);
        String commentPrefix = LanguageCommenters.INSTANCE.forLanguage(myLanguage).getLineCommentPrefix();
        String text = templateForTask == null ? (commentPrefix + " write your answer here \n") : templateForTask;
        String taskFileName = getTaskFileName(myLanguage);
        if (taskFileName != null) {
          createMockTaskFile(task, text, taskFileName);
        }
      }
      return task;
    }

    @NotNull
    private ChoiceTask choiceTask() {
      ChoiceTask task = new ChoiceTask(myName);
      task.setStepId(myStepId);
      task.addTaskText(EduNames.TASK, myStep.text);

      final StepicWrappers.AdaptiveAttemptWrapper.Attempt attempt = getAttemptForStep(myStepId, myUserId);
      if (attempt != null) {
        final StepicWrappers.AdaptiveAttemptWrapper.Dataset dataset = attempt.dataset;
        if (dataset != null) {
          task.setChoiceVariants(dataset.options);
          task.setMultipleChoice(dataset.is_multiple_choice);
        }
        else {
          LOG.warn("Dataset for step " + myStepId + " is null");
        }
      }
      String commentPrefix = LanguageCommenters.INSTANCE.forLanguage(myLanguage).getLineCommentPrefix();
      String taskFileName = getTaskFileName(myLanguage);
      if (taskFileName != null) {
        createMockTaskFile(task, commentPrefix + " you can experiment here, it won't be checked", taskFileName);
      }

      return task;
    }

    @NotNull
    private TheoryTask theoryTask() {
      TheoryTask task = new TheoryTask(myName);
      task.setStepId(myStepId);
      task.addTaskText(EduNames.TASK, myStep.text);
      String commentPrefix = LanguageCommenters.INSTANCE.forLanguage(myLanguage).getLineCommentPrefix();
      String taskFileName = getTaskFileName(myLanguage);

      if (taskFileName != null) {
        createMockTaskFile(task, commentPrefix + " this is a theory task. You can use this editor as a playground", taskFileName);
      }
      return task;
    }

    @Nullable
    private PyCharmTask pycharmTask() {
      try {
        return (PyCharmTask)EduStepicConnector.createTask(myStepId);
      }
      catch (IOException e) {
        LOG.warn(e.getMessage());
      }

      return null;
    }

    private static void createMockTaskFile(@NotNull Task task, @NotNull String editorText, @NotNull String taskFileName) {
      final TaskFile taskFile = new TaskFile();
      taskFile.text = editorText;
      taskFile.name = taskFileName;
      task.taskFiles.put(taskFile.name, taskFile);
    }

    @Nullable
    private static String getTaskFileName(@NotNull Language language) {
      // This is a hacky way to how we should name task file.
      // It's assumed that if test's name is capitalized we need to capitalize task file name too.
      String testFileName = EduPluginConfigurator.INSTANCE.forLanguage(language).getTestFileName();
      boolean capitalize = !testFileName.isEmpty() && Character.isUpperCase(testFileName.charAt(0));

      LanguageFileType type = language.getAssociatedFileType();
      if (type == null) {
        LOG.warn("Failed to create task file name: associated file type for " + language + " is null");
        return null;
      }

      return (capitalize ? StringUtil.capitalize(TASK_NAME) : TASK_NAME) + "." + type.getDefaultExtension();
    }
  }
}
