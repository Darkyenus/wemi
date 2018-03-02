package wemi.util

import org.jline.utils.OSUtils
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions
import java.net.URL
import java.nio.file.Paths

/**
 *
 */
class FilesTests {

    private fun check(fromURL:String, toPath:String?) {
        check(URL(fromURL), toPath)
    }

    private fun check(fromURL:URL, toPath:String?) {
        assertEquals(toPath, fromURL.toPath()?.toString())
    }

    @Test
    fun invalidPaths() {
        check("http:/woo", null)
        check("jar:jar:file:/Directory/wemi!/wemi/util/Magic.jar!/HeyHo.class", null)
    }

    @Test
    fun unixPaths() {
        Assumptions.assumeTrue(!OSUtils.IS_WINDOWS)

        check("jar:file:/Directory/wemi!/wemi/util/MagicKt.class", "/Directory/wemi")
        check("file://localhost/Directory/thing.pom", "/Directory/thing.pom")
        check("file:/Directory/thing.pom", "/Directory/thing.pom")
        check("file://123.123.123.123/Directory/thing.pom", null)
    }

    @Test
    fun windowsPaths() {
        Assumptions.assumeTrue(OSUtils.IS_WINDOWS)

        //check("jar:file:/Directory/wemi!/wemi/util/MagicKt.class", "/Directory/wemi")
        //check("file://localhost/Directory/thing.pom", "/Directory/thing.pom")
        //check("file:/Directory/thing.pom", "/Directory/thing.pom")
        //check("file://123.123.123.123/Directory/thing.pom", null)
    }

    @Test
    fun localPaths() {
        val file = Paths.get("wemi-test-file").toAbsolutePath()
        val url = file.toUri().toURL()
        check(url, file.toString())

        // Check various variations that we want to ignore
        run {
            val messyUrl = URL(url.protocol, url.host, url.port, url.file)
            check(messyUrl, file.toString())
        }

        run {
            val messyUrl = URL(url.protocol, "localhost", url.port, url.file)
            check(messyUrl, file.toString())
        }

        run {
            val messyUrl = URL(url.protocol, "127.0.0.1", url.port, url.file)
            check(messyUrl, file.toString())
        }

        run {
            val messyUrl = URL(url.protocol, url.host, 1234, url.file)
            check(messyUrl, file.toString())
        }
    }

}