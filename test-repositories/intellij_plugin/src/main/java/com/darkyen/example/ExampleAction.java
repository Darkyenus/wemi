package com.darkyen.example;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;
import javax.swing.JOptionPane;

public class ExampleAction extends AnAction {

	@Override
	public void update(@NotNull AnActionEvent e) {
		e.getPresentation().setEnabledAndVisible(true);
	}

	@Override
	public void actionPerformed(@NotNull AnActionEvent e) {
		JOptionPane.showMessageDialog(null, "It works!\n"+e.getProject().getService(com.darkyen.TimeTrackerService.class).toString(), "Alert", JOptionPane.PLAIN_MESSAGE);
	}
}