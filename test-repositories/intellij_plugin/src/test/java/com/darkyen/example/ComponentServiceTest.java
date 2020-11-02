package com.darkyen.example;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class ComponentServiceTest extends BasePlatformTestCase {

	public void testComponentService() {
		DemoComponent component = ApplicationManager.getApplication().getComponent(DemoComponent.class);
		assertInstanceOf(component.getService(), DemoService.class);
	}

}