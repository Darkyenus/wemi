package wemiplugin.intellij.utils

import Files
import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.FileNotFoundException
import java.io.IOException
import java.io.Reader
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

private val LOG = LoggerFactory.getLogger("Xml")

/**
 * Parse XML from given [reader] into a [Document].
 * Does not preserve ignorable whitespace, does not validate.
 */
@Throws(Exception::class)
fun parseXml(reader: Reader):Document {
	val factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance()
	val builder: DocumentBuilder = factory.newDocumentBuilder()
	val document = builder.parse(InputSource(reader))
	// We have to strip whitespace to get nice output without double formatting.
	// We have to do it manually because XML is a clowntown and factory.isIgnoringElementContentWhitespace
	// apparently requires us to supply schema and enable validation, which is absolutely nuts.
	document.stripWhitespace()
	return document
}

private fun Node.stripWhitespace() {
	val childNodes = childNodes
	var i = childNodes.length - 1
	while (i >= 0) {
		val node = childNodes.item(i--)
		val nodeType = node.nodeType
		if (nodeType == Node.TEXT_NODE) {
			val nodeValue = node.nodeValue
			if (nodeValue.isNullOrBlank()) {
				// Keep blank lines, because they help readability
				if (nodeValue.count { it == '\n' } > 1) {
					node.nodeValue = "\n"
				} else {
					removeChild(node)
				}
			}
		} else if (nodeType == Node.ELEMENT_NODE) {
			node.stripWhitespace()
		}
	}
}

fun parseXml(path: Path):Document? {
	val reader = try {
		Files.newBufferedReader(path, Charsets.UTF_8)
	} catch (e: FileNotFoundException) {
		return null
	} catch (e: NoSuchFileException) {
		return null
	} catch (e: IOException) {
		LOG.warn("Failed to read {}", path, e)
		return null
	}

	return try {
		reader.use {
			parseXml(it)
		}
	} catch (e: Exception) {
		LOG.warn("Failed to read and parse {}", path, e)
		return null
	}
}

fun saveXml(path:Path, document:Document):Boolean {
	try {
		Files.createDirectories(path.parent)
		Files.newBufferedWriter(path, Charsets.UTF_8).use { out ->
			val xmlOutput = StreamResult(out)
			val transformerFactory: TransformerFactory = TransformerFactory.newInstance()
			transformerFactory.setAttribute("indent-number", 2)
			val transformer = transformerFactory.newTransformer()
			transformer.setOutputProperty(OutputKeys.INDENT, "yes")
			transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
			transformer.transform(DOMSource(document), xmlOutput)
		}
	} catch (e:Exception) {
		LOG.error("Failed to write XML to {}", path, e)
		LOG.debug("Written XML was: {}", document)
		return false
	}
	return true
}

inline fun Element.forEachElement(named:String, action:(Element)->Unit) {
	val childNodes = childNodes
	for (i in 0 until childNodes.length) {
		val item = childNodes.item(i)
		if (item.nodeType != Node.ELEMENT_NODE || item !is Element) {
			continue
		}
		if (!item.tagName.equals(named, ignoreCase = true)) {
			continue
		}
		action(item)
	}
}

fun Element.namedElements(named:String):Iterable<Element> {
	val nodeList = getElementsByTagName(named) ?: return emptyList()
	return object : Iterable<Element> {
		override fun iterator(): Iterator<Element> {
			return object : Iterator<Element> {
				var position = 0
				val length = nodeList.length

				override fun hasNext(): Boolean = position < length

				override fun next(): Element {
					return nodeList.item(position++) as Element
				}
			}
		}
	}
}

fun Element.getFirstElement(named:String): Element? {
	val childNodes = childNodes
	for (i in 0 until childNodes.length) {
		val item = childNodes.item(i)
		if (item.nodeType != Node.ELEMENT_NODE || item !is Element) {
			continue
		}
		if (!item.tagName.equals(named, ignoreCase = true)) {
			continue
		}
		return item
	}
	return null
}


/**
 * Definition of a XML element content or attribute patch.
 *
 * @param element path to the element to patch. Implicitly prefixed with the root element name.
 * @param attribute name of the attribute to patch or null if this should patch the text content of the element
 * @param content new content to put into the specified place
 * @param mode the patching strategy, see [PatchMode]
 */
class Patch(vararg val element:String, val attribute:String? = null, val content:String, val mode:PatchMode = PatchMode.SET) {
	override fun toString(): String {
		val sb = StringBuilder()
		sb.append("idea-plugin")
		for (s in element) {
			sb.append('/').append(sb)
		}
		if (attribute != null) {
			sb.append(" \"").append(attribute).append('"')
		}
		sb.append(when (mode) {
			PatchMode.SET -> "=?>"
			PatchMode.SET_OR_REPLACE -> "=>"
			PatchMode.ADD -> "+=>"
		})
		sb.append(content)
		return sb.toString()
	}
}

enum class PatchMode {
	/** Set the value, unless the existing value is not blank */
	SET,
	/** Set the value, replacing any existing value */
	SET_OR_REPLACE,
	/** Add the value as a new element or attribute.
	 * If all but the last element names exist, the existing parent element will be used.
	 * If named element already exists in the parent, new one will be created right after the last one.
	 *
	 * Note that in XML, multiple identically named attributes are not allowed, so using this mode on an attribute
	 * will instead create a new *Element* and add the attribute on that. */
	ADD
}

fun Document.patchInPlace(rootElementName:String, patches: List<Patch>) {
	// Compute which patches overwrite earlier patches and kick them out
	val effectivePatches = ArrayList<Patch>()
	for (patch in patches) {
		if (patch.mode != PatchMode.ADD) {
			effectivePatches.removeIf { it.element.contentEquals(patch.element) && it.attribute == patch.attribute }
		}
		effectivePatches.add(patch)
	}

	// Do the patching
	val root = documentElement
	if (root == null || !root.tagName.equals(rootElementName, ignoreCase = true)) {
		LOG.warn("Can't patch - root <{}> not found", rootElementName)
		return
	}

	for (patch in effectivePatches) {
		var element: Element = root
		for ((eIndex, elementName) in patch.element.withIndex()) {
			if (patch.mode == PatchMode.ADD && eIndex == patch.element.lastIndex) {
				// We always create new here
				val nextSibling = element.getFirstElement(elementName)?.nextSibling
				val newElement = createElement(elementName)!!
				if (nextSibling == null) {
					element.appendChild(newElement)
				} else {
					element.insertBefore(newElement, nextSibling)
				}
				element = newElement
			} else {
				var nextElement = element.getFirstElement(elementName)
				if (nextElement == null) {
					nextElement = createElement(elementName)!!
					element.appendChild(nextElement)
				}
				element = nextElement
			}
		}

		if (patch.mode == PatchMode.SET) {
			val existingContent = if (patch.attribute != null) element.getAttribute(patch.attribute) else element.textContent
			if (!existingContent.isNullOrBlank()) {
				// Skip this one
				continue
			}
		}

		if (patch.attribute != null) {
			element.setAttribute(patch.attribute, patch.content)
		} else {
			element.textContent = patch.content
		}
	}
}