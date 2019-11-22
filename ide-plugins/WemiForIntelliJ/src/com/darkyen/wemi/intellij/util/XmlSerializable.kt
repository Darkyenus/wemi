package com.darkyen.wemi.intellij.util

import org.jdom.Element
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1

/**
 *
 */
abstract class XmlSerializable {

	private val registeredProperties = ArrayList<SerializableProperty<XmlSerializable, *>>()

	@Suppress("UNCHECKED_CAST")
	protected fun <Self : XmlSerializable, T, Serializer : XmlValueSerializer<T>>register(property: KMutableProperty1<Self, T>, serializer:KClass<Serializer>) {
		registeredProperties.add(SerializableProperty(property, getSerializerFor(serializer)) as SerializableProperty<XmlSerializable, *>)
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

