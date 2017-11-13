package com.darkyen.wemi.intellij.file

import com.intellij.openapi.fileTypes.FileTypeConsumer
import com.intellij.openapi.fileTypes.FileTypeFactory

/**
 * Register .wemi files as kotlin files
 */
class WemiFileTypeFactory : FileTypeFactory() {

    override fun createFileTypes(consumer: FileTypeConsumer) {
        consumer.consume(WemiFileType)
    }

}