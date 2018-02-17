package wemi.util

import java.io.IOException
import java.net.URL
import java.net.URLClassLoader
import java.util.*

/**
 * ClassLoader that first checks own URLs before asking parent.
 *
 * This creates an enclave of classes that exist only inside this class loader.
 *
 * Some (not yet loaded!) classes of the parent class loader will probably want to be loaded using this
 * class loader, to force any classes used by them to be loaded by this class loader.
 * Common prefixes of names of those classes should be passed in [entryPoints].
 *
 * This is useful for injecting dynamically loaded libraries which are compiled in as "provided".
 *
 * @param entryPoints patterns that either specifies a class and its inner classes (e.g. `java.lang.String`)
 *                  or pattern that specifies all classes in package (and its subpackages) (e.g. `java.lang.`)
 */
class EnclaveClassLoader(urls: Array<URL>,
                         private val myParent: ClassLoader,
                         private vararg val entryPoints:String) : URLClassLoader(urls, myParent) {

    private fun forceLoadClass(name: String): Class<*> {
        val resourceName = name.replace('.', '/') + ".class"
        try {
            getResourceAsStream(resourceName).use { stream ->
                var buffer = ByteArray(2048)
                var offset = 0
                while (true) {
                    val read = stream.read(buffer, offset, buffer.size - offset)
                    if (read <= 0) break
                    offset += read
                    if (offset == buffer.size) {
                        buffer = Arrays.copyOf(buffer, buffer.size * 2)
                    }
                }

                return defineClass(name, buffer, 0, offset)
            }
        } catch (ex: Exception) {
            throw ClassNotFoundException("Could not force-load class " + name, ex)
        }
    }

    @Throws(ClassNotFoundException::class)
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        /*
        Follows the default implementation closely, but tries to load the class in itself first
         */
        synchronized(getClassLoadingLock(name)) {
            val c = findLoadedClass(name)
                    ?: if (entryPoints.any { pattern ->
                                name == pattern
                                        || (name.startsWith(pattern) && (name[pattern.length] == '$' || pattern.last() == '.')) }) {
                        // This class is an entry point
                        forceLoadClass(name)
                    } else {
                        try {
                            findClass(name)
                        } catch (ex: ClassNotFoundException) {
                            myParent.loadClass(name)
                        }
                    }

            if (resolve) {
                resolveClass(c)
            }
            return c
        }
    }

    override fun getResource(name: String): URL? {
        /*
        Follows the default implementation closely, but tries to load the resource in itself first
         */
        return findResource(name) ?: myParent.getResource(name)
    }

    @Throws(IOException::class)
    override fun getResources(name: String): Enumeration<URL> {
        /*
        Follows the default implementation closely, but tries to load the resource in itself first
         */
        return CompoundEnumeration(arrayOf(
                findResources(name),
                myParent.getResources(name)))
    }

    /**
     * Copy of sun.misc.CompoundEnumeration
     */
    private class CompoundEnumeration<E> (private val enums: Array<Enumeration<E>>) : Enumeration<E> {
        private var index = 0

        private operator fun next(): Boolean {
            while (this.index < this.enums.size) {
                if (this.enums[this.index].hasMoreElements()) {
                    return true
                }

                ++this.index
            }

            return false
        }

        override fun hasMoreElements(): Boolean {
            return this.next()
        }

        override fun nextElement(): E {
            return if (!this.next()) {
                throw NoSuchElementException()
            } else {
                this.enums[this.index].nextElement()
            }
        }
    }
}
