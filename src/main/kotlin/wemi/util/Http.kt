package wemi.util

import Files
import com.darkyen.dave.Request
import com.darkyen.dave.Response
import com.darkyen.dave.ResponseTranslator
import com.darkyen.dave.Webb
import com.darkyen.dave.WebbException
import com.esotericsoftware.jsonbeans.JsonReader
import com.esotericsoftware.jsonbeans.JsonValue
import org.slf4j.LoggerFactory
import wemi.ActivityListener
import wemi.boot.WemiUnicodeOutputSupported
import wemi.boot.WemiVersion
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private val LOG = LoggerFactory.getLogger("Http")

private fun createWebb(): Webb {
	return Webb(null).apply {
		// NOTE: When User-Agent is not set, it defaults to "Java/<version>" and some servers (Sonatype Nexus)
		// then return gutted version of some resources (at least maven-metadata.xml) for which the checksums don't match
		// This seems to be due to: https://issues.sonatype.org/browse/NEXUS-6171 (not a bug, but a feature!)
		setDefaultHeader("User-Agent", "Wemi/$WemiVersion")
		// Just for consistency
		setDefaultHeader("Accept", "*/*")
		setDefaultHeader("Accept-Language", "*")
		// Should be default, but just in case
		setFollowRedirects(true)
	}
}

private val WEBB = createWebb()

private val UNSAFE_WEBB = createWebb().apply {
	val sslContext = SSLContext.getInstance("TLS")
	sslContext.init(null, arrayOf(object : X509TrustManager {
		override fun checkClientTrusted(p0: Array<out X509Certificate>?, p1: String?) {}
		override fun checkServerTrusted(p0: Array<out X509Certificate>?, p1: String?) {}
		override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
	}), null)

	setSSLSocketFactory(sslContext.socketFactory)
	setHostnameVerifier { _, _ -> true }
}

fun httpGet(url: URL, ifModifiedSince:Long = -1, useUnsafeTransport:Boolean = false): Request {
	val webb = if (useUnsafeTransport) {
		LOG.warn("Forgoing all cryptography verifications on GET of {}", url)
		UNSAFE_WEBB
	} else WEBB

	val request = webb.get(url.toExternalForm())
	request.useCaches(false) // Do not use local caches, we do the caching ourselves
	request.retry(2, true)
	if (ifModifiedSince > 0) {
		request.ifModifiedSince(ifModifiedSince)
		request.header("Cache-Control", "no-transform")
	} else {
		request.header("Cache-Control", "no-transform, no-cache")
	}

	return request
}

fun <T> Request.execute(listener: ActivityListener?, responseTranslator:ResponseTranslator<T>):Response<T> {
	if (listener == null) {
		return execute(responseTranslator)
	} else {
		listener.beginActivity(uriToActivityName(uri))
		try {
			return execute(object : ResponseTranslator<T> {
				private val startNs = System.nanoTime()

				override fun decode(response: Response<*>, originalIn: InputStream): T {
					val totalLength = response.headers.entries.find { it.key.equals("Content-Length", ignoreCase = true) }?.value?.first()?.toLongOrNull() ?: 0L

					listener.activityDownloadProgress(0L, totalLength, System.nanoTime() - startNs)

					return responseTranslator.decode(response, object : GaugedInputStream(originalIn) {

						override var totalRead:Long = 0L
							set(value) {
								field = value
								listener.activityDownloadProgress(value, totalLength, System.nanoTime() - startNs)
							}
					})
				}

				override fun decodeEmptyBody(response: Response<*>): T {
					return responseTranslator.decodeEmptyBody(response)
				}
			})
		} finally {
			listener.endActivity()
		}
	}
}

private fun uriToActivityName(uri:String):String {
	val maxCharacters = 64
	if (uri.length < maxCharacters) {
		return uri
	}

	val protocolEnd = uri.indexOf("//")
	if (protocolEnd == -1 || (!uri.startsWith("https://", ignoreCase = true) && !uri.startsWith("http://"))) {
		return uri
	}

	// Shorten to domain/...file
	val domainStart = protocolEnd + 2
	var domainEnd = uri.indexOf('/', startIndex = domainStart)
	if (domainEnd == -1)
		domainEnd = uri.length

	val remainingCharacters = uri.length - domainEnd
	val availableCharacters = maxCharacters - (domainEnd - domainStart)

	if (remainingCharacters <= availableCharacters) {
		return uri.substring(domainStart)
	} else {
		val result = StringBuilder(70)
		result.append(uri, domainStart, domainEnd)
		result.append('/').append(if (WemiUnicodeOutputSupported) "[…]" else "[...]")
		val remaining = maxCharacters - result.length
		result.append(uri, uri.length - remaining, uri.length)
		return result.toString()
	}
}

/** Wrap [this] to call [action] on received response. */
fun <T> ResponseTranslator<T>.onResponse(action:(Response<*>) -> Unit):ResponseTranslator<T> {
	return object : ResponseTranslator<T> {
		override fun decode(response: Response<*>, `in`: InputStream): T {
			action(response)
			return this@onResponse.decode(response, `in`)
		}

		override fun decodeEmptyBody(response: Response<*>): T {
			action(response)
			return this@onResponse.decodeEmptyBody(response)
		}
	}
}

fun httpGetFile(url:URL, file: Path, listener: ActivityListener?, useUnsafeTransport:Boolean = false):Boolean {
	Files.createDirectories(file.parent)
	val fileLastModified = file.lastModifiedMillis()
	val downloadFile = file.resolveSibling(file.name+".downloading")
	val downloadFileSize = downloadFile.size
	val hasCache = fileLastModified > 0
	val hasRange = !hasCache && downloadFileSize > 0

	val response = try {
		val request = httpGet(url, fileLastModified, useUnsafeTransport)
		if (hasRange) {
			request.header("Range", "bytes=$downloadFileSize-")
		}

		request.execute(listener, object : ResponseTranslator<Boolean> {
			override fun decode(response: Response<*>, inp: InputStream): Boolean {
				if (hasCache && response.statusCode == 304) {
					LOG.debug("Not re-downloading {}, cache is still good", url)
					return true
				}
				if (hasRange && response.statusCode == 206) {
					Files.newOutputStream(downloadFile, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use {
						inp.copyTo(it)
					}
					Files.move(downloadFile, file, StandardCopyOption.REPLACE_EXISTING)
					return true
				}
				if (response.statusCode == 200) {
					Files.newOutputStream(downloadFile, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use {
						inp.copyTo(it)
					}
					Files.move(downloadFile, file, StandardCopyOption.REPLACE_EXISTING)
					return true
				}
				LOG.warn("Failed to fetch {} ({} - {})", url, response.statusCode, response.statusMessage)
				return false
			}

			override fun decodeEmptyBody(response: Response<*>): Boolean {
				return decode(response, ByteArrayInputStream(ByteArray(0)))
			}

		})
	} catch (e: WebbException) {
		LOG.warn("Failed to fetch {} ({})", url, if (hasCache) "using cached instead" else "cache is empty", e)
		return hasCache
	}

	return response.body == true
}

fun httpGetJson(url:URL, listener: ActivityListener?, useUnsafeTransport:Boolean = false):JsonValue? {
	try {
		val response = httpGet(url, useUnsafeTransport = useUnsafeTransport).execute(listener, ResponseTranslator.STRING_TRANSLATOR)
		if (response.isSuccess) {
			return JsonReader().parse(response.body)
		} else {
			LOG.warn("Failed to get json from {} - server responded with {}", url, response.statusMessage)
		}
	} catch (e:Exception) {
		LOG.warn("Failed to get json from {}", url, e)
	}
	return null
}