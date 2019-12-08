package com.darkyen.wemi.intellij.util

import com.intellij.openapi.components.PersistentStateComponent
import org.jdom.Element
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1

/**
 *
 */
abstract class XmlSerializable : PersistentStateComponent<Element> {

	private val registeredProperties = ArrayList<SerializableProperty<XmlSerializable, *>>()

	@Suppress("UNCHECKED_CAST")
	protected fun <Self : XmlSerializable, T, Serializer : XmlValueSerializer<T>>register(property: KMutableProperty1<Self, T>, serializer:KClass<Serializer>) {
		registeredProperties.add(SerializableProperty(property, getSerializerFor(serializer)) as SerializableProperty<XmlSerializable, *>)
	}

	@Suppress("UNCHECKED_CAST")
	protected fun <Self : XmlSerializable, T, Serializer : XmlValueSerializer<T>>register(property: KMutableProperty1<Self, T>, serializer:Serializer) {
		registeredProperties.add(SerializableProperty(property, serializer) as SerializableProperty<XmlSerializable, *>)
	}

	@Suppress("UNCHECKED_CAST")
	fun writeExternal(element: Element) {
		for (property in registeredProperties) {
			property.writeExternal(this, element)
		}
	}

	fun readExternal(element:Element) {
		for (property in registeredProperties) {
			property.readExternal(this, element)
		}
	}

	override fun getState(): Element? {
		val element = Element(this.javaClass.name.split('.').lastOrNull() ?: "XmlSerializable")
		writeExternal(element)
		return element
	}

	override fun loadState(state: Element) {
		readExternal(state)
	}

	private class SerializableProperty<Self, T>(
			private val property: KMutableProperty1<Self, T>,
			private val serializer:XmlValueSerializer<T>) {

		fun writeExternal(self:Self, element:Element) {
			val propertyElement = Element(property.name)
			serializer.writeExternal(property.get(self), propertyElement)
			element.addContent(propertyElement)
		}

		fun readExternal(self:Self, element:Element) {
			val child = element.getChild(property.name) ?: return
			property.set(self, serializer.readExternal(child, property.get(self)))
		}
	}

	private companion object {
		private val serializerCache = HashMap<KClass<XmlValueSerializer<*>>, XmlValueSerializer<*>>()

		@Suppress("UNCHECKED_CAST")
		private fun <T, S : XmlValueSerializer<T>> getSerializerFor(c:KClass<S>):XmlValueSerializer<T> {
			return serializerCache.getOrPut(c as KClass<XmlValueSerializer<*>>) { c.java.newInstance() as XmlValueSerializer<Any> } as XmlValueSerializer<T>
		}
	}
}

interface XmlValueSerializer<T> {
	fun writeExternal(value:T, element:Element)

	fun readExternal(element:Element, default:T):T
}

class ListOfStringArraysXmlSerializer : XmlValueSerializer<List<Array<String>>> {
	override fun writeExternal(value: List<Array<String>>, element: Element) {
		for (stringArray in value) {
			element.addContent(Element("array").also { arrayElement ->
				for (s in stringArray) {
					arrayElement.addContent(Element("item").apply {
						setAttribute("value", s)
					})
				}
			})
		}
	}

	override fun readExternal(element: Element, default:List<Array<String>>): List<Array<String>> {
		return element.getChildren("array").map { arrayElement ->
			arrayElement.getChildren("item").mapNotNull { it.getAttributeValue("value") }.toTypedArray()
		}
	}
}

class MapStringStringXmlSerializer : XmlValueSerializer<Map<String, String>> {
	override fun writeExternal(value: Map<String, String>, element: Element) {
		for ((k, v) in value) {
			element.addContent(Element("pair").apply {
				setAttribute("key", k)
				setAttribute("value", v)
			})
		}
	}

	override fun readExternal(element: Element, default:Map<String, String>): Map<String, String> {
		val result = HashMap<String, String>()
		for (pairElement in element.getChildren("pair")) {
			val key = pairElement.getAttributeValue("key") ?: continue
			val value = pairElement.getAttributeValue("value") ?: continue
			result[key] = value
		}
		return result
	}
}

class BooleanXmlSerializer : XmlValueSerializer<Boolean> {
	override fun writeExternal(value: Boolean, element: Element) {
		element.setAttribute("bool", if (value) "true" else "false")
	}

	override fun readExternal(element: Element, default:Boolean): Boolean {
		return (element.getAttributeValue("bool") ?: return default).equals("true", ignoreCase = true)
	}
}

class StringXmlSerializer : XmlValueSerializer<String> {
	override fun writeExternal(value: String, element: Element) {
		element.setAttribute("str", value)
	}

	override fun readExternal(element: Element, default:String): String {
		return element.getAttributeValue("str") ?: default
	}
}

class ListOfStringXmlSerializer : XmlValueSerializer<List<String>> {
	override fun writeExternal(value: List<String>, element: Element) {
		for (s in value) {
			element.addContent(Element("str").also { strElement ->
				strElement.setAttribute("value", s)
			})
		}
	}

	override fun readExternal(element: Element, default:List<String>): List<String> {
		return element.getChildren("str").mapNotNull { it.getAttributeValue("value") }
	}
}

@Suppress("UNCHECKED_CAST")
fun <T : XmlSerializable> xmlSerializableSerializer():KClass<XmlValueSerializer<T>> {
	return XmlSerializableSerializer::class as KClass<XmlValueSerializer<T>>
}

private class XmlSerializableSerializer : XmlValueSerializer<XmlSerializable> {

	override fun writeExternal(value: XmlSerializable, element: Element) {
		value.writeExternal(element)
	}

	override fun readExternal(element: Element, default: XmlSerializable): XmlSerializable {
		default.readExternal(element)
		return default
	}
}

inline fun <reified T:Enum<T>> enumXmlSerializer():XmlValueSerializer<T> {
	val enumJavaClass = T::class.java
	return object : XmlValueSerializer<T> {
		override fun writeExternal(value: T, element: Element) {
			element.setAttribute("enum", value.name)
		}

		override fun readExternal(element: Element, default: T): T {
			val enumName = element.getAttributeValue("enum") ?: return default
			try {
				return java.lang.Enum.valueOf(enumJavaClass, enumName.toUpperCase())
			} catch (e : IllegalArgumentException) {
				return default
			}
		}
	}
}

