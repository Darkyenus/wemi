package com.darkyen.wemi.intellij

import com.intellij.openapi.fileTypes.FileTypeConsumer
import com.intellij.openapi.fileTypes.FileTypeFactory
import org.jetbrains.kotlin.idea.KotlinFileType

/**
 * Register .wemi files as kotlin files
 */
class WemiFileTypeFactory : FileTypeFactory() {

    override fun createFileTypes(consumer: FileTypeConsumer) {
        consumer.consume(KotlinFileType.INSTANCE, WemiBuildFileExtensions.joinToString(FileTypeConsumer.EXTENSION_DELIMITER))
    }

}