package wemi.assembly

import java.io.File
import java.util.zip.ZipEntry

/** Used by [wemi.Keys.assemblyMergeStrategy] and [wemi.Keys.assemblyRenameFunction] as a representation of data source.
 * Most fields are internally cached. */
abstract class AssemblySource(
        /** Root of this source, usually a jar file or directory with resources or .class files. */
        val root:File,
        /** File from which the data is loaded, if any. ([data] may not directly correspond to the content of the file!) */
        val file:File?,
        /** ZipEntry from which the data is loaded, if any. */
        val zipEntry: ZipEntry?,
        /** Is this from the [wemi.Keys.internalClasspath]? */
        val own:Boolean) {

    val data:ByteArray by lazy(LazyThreadSafetyMode.NONE) { load() }

    /** Load the actual data */
    protected abstract fun load(): ByteArray
    /** Debug name of the source */
    abstract val name: String
}