package wemi.assembly

import java.nio.file.Path

/** Used by [wemi.Keys.assemblyMergeStrategy] and [wemi.Keys.assemblyRenameFunction] as a representation of data source.
 * Most fields are internally cached. */
class AssemblySource private constructor(
        /** Debug name of the source */
        private val name:String,
        /** File from which the data somehow originated, if any.
         * This is just a hint, for example for [wemi.Keys.assemblyRenameFunction].
         * File may even be a directory, archive, etc.*/
        val sourceFile: Path?,
        /** Time of last modification. -1 if unknown */
        val lastModifiedMs: Long,
        /** Is this from the [wemi.Keys.internalClasspath] or aggregate? */
        val own: Boolean) {

    constructor(name:String, sourceFile:Path?, lastModifiedMs:Long, own:Boolean, data:ByteArray)
            : this (name, sourceFile, lastModifiedMs, own) {
        this._data = data
    }

    constructor(name:String, sourceFile:Path?, lastModifiedMs:Long, own:Boolean, loadData:() -> ByteArray)
            : this (name, sourceFile, lastModifiedMs, own) {
        this.dataRetriever = loadData
    }

    private var _data:ByteArray? = null
    private var dataRetriever:(() -> ByteArray)? = null

    /**
     * Lazily loaded data of the source that should be included in the assembled archive
     */
    val data: ByteArray
        get() {
            var result = _data
            if (result == null) {
                result = dataRetriever!!()
                _data = result
                dataRetriever = null
            }
            return result
        }

    override fun toString(): String = name
}