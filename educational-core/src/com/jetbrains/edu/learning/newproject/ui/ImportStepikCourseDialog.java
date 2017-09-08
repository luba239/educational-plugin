package com.jetbrains.edu.learning.newproject.ui;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.components.JBTextField;
import com.jetbrains.edu.learning.StudySettings;
import com.jetbrains.edu.learning.courseFormat.Course;
import com.jetbrains.edu.learning.stepic.EduStepicConnector;
import com.jetbrains.edu.learning.stepic.StepicUser;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;

public class ImportStepikCourseDialog extends JDialog {
  private static final Logger LOG = Logger.getInstance(ImportStepikCourseDialog.class);
  private Course myCourse;
  private JPanel contentPane;
  private JButton buttonOK;
  private JButton buttonCancel;
  private JBTextField myLinkTextField;

  public ImportStepikCourseDialog() {
    setContentPane(contentPane);
    setModal(true);
    getRootPane().setDefaultButton(buttonOK);
    setMinimumSize(new Dimension(300, 150));

    buttonOK.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        onOK();
      }
    });

    buttonCancel.addActionListener(new ActionListener() {
      public void actionPerformed(ActionEvent e) {onCancel();}
    });

    // call onCancel() when cross is clicked
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    addWindowListener(new WindowAdapter() {
      public void windowClosing(WindowEvent e) {
        onCancel();
      }
    });

    // call onCancel() on ESCAPE
    contentPane.registerKeyboardAction(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        onCancel();
      }
    }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
  }

  private void onOK() {
    try {
      StepicUser user = StudySettings.getInstance().getUser();
      assert user != null;
      myCourse = EduStepicConnector.getCourseByLink(user, myLinkTextField.getText());
    }
    catch (IOException e) {
      LOG.warn(e.getMessage());
    }
    finally {
      dispose();
    }
  }

  private void onCancel() {
    // add your code here if necessary
    dispose();
  }

  public Course getCourse() {
    return myCourse;
  }

  public static void main(String[] args) {
    ImportStepikCourseDialog dialog = new ImportStepikCourseDialog();
    dialog.pack();
    dialog.setVisible(true);
    System.exit(0);
  }
}
