package com.darkyen.wemi.intellij.file;

import com.intellij.openapi.fileTypes.ExactFileNameMatcher;
import com.intellij.openapi.fileTypes.FileTypeConsumer;
import com.intellij.openapi.fileTypes.FileTypeFactory;
import org.jetbrains.annotations.NotNull;

import static com.darkyen.wemi.intellij.WemiKt.WemiLauncherFileName;

/**
 * Registers File type associations.
 */
// TODO(jp): Migrate after targeting >= 2019.2 (http://www.jetbrains.org/intellij/sdk/docs/reference_guide/custom_language_support/registering_file_type.html)
public class WemiFileTypeFactory extends FileTypeFactory {
	@Override
	public void createFileTypes(@NotNull FileTypeConsumer consumer) {
		consumer.consume(WemiLauncherFileType.INSTANCE, new ExactFileNameMatcher(WemiLauncherFileName, false));
	}
}
