package com.jetbrains.edu.learning.editor;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.impl.text.PsiAwareTextEditorImpl;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ui.UIUtil;
import com.jetbrains.edu.learning.StudyUtils;
import com.jetbrains.edu.learning.actions.StudyRefreshTaskFileAction;
import com.jetbrains.edu.learning.core.EduDocumentListener;
import com.jetbrains.edu.learning.courseFormat.TaskFile;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * Implementation of StudyEditor which has panel with special buttons and task text
 * also @see {@link StudyFileEditorProvider}
 */
public class StudyEditor extends PsiAwareTextEditorImpl {
  private final TaskFile myTaskFile;
  private static final Map<Document, EduDocumentListener> myDocumentListeners = new HashMap<>();

  public StudyEditor(@NotNull final Project project, @NotNull final VirtualFile file) {
    super(project, file, TextEditorProvider.getInstance());
    myTaskFile = StudyUtils.getTaskFile(project, file);

    validateTaskFile();
  }

  public void validateTaskFile() {
    if (!StudyUtils.isTaskFileValid(myTaskFile)) {
      JLabel label = new JLabel(UIUtil.toHtml("Placeholders are broken. <a href=\"\">Reset task</a> to solve it again"));
      label.addMouseListener(new MouseAdapter() {
        @Override
        public void mouseClicked(MouseEvent e) {
          if (e.getClickCount() == 1) {
            StudyRefreshTaskFileAction.refresh(myProject);
          }
        }

        @Override
        public void mouseEntered(MouseEvent e) {
          label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        public void mouseExited(MouseEvent e) {
          label.setCursor(Cursor.getDefaultCursor());
        }
      });
      label.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));
      getEditor().setHeaderComponent(label);
    }
    else {
      getEditor().setHeaderComponent(null);
    }
  }

  public TaskFile getTaskFile() {
    return myTaskFile;
  }

  public static void addDocumentListener(@NotNull final Document document, @NotNull final EduDocumentListener listener) {
    document.addDocumentListener(listener);
    myDocumentListeners.put(document, listener);
  }

  public static void removeListener(Document document) {
    final EduDocumentListener listener = myDocumentListeners.get(document);
    if (listener != null) {
      document.removeDocumentListener(listener);
    }
    myDocumentListeners.remove(document);
  }
}
