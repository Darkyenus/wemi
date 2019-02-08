package wemi.util

import com.esotericsoftware.jsonbeans.*
import java.io.File
import java.io.Reader
import java.io.Writer
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.reflect.KClass

/**
 * Takes care of serializing values of some concrete type to and from Json.
 *
 * Should not write or read nulls, those are handled elsewhere.
 * Must be thread safe.
 *
 * Should be implemented by private inner class referenced by [Json] annotation on the serialized type.
 */
interface JsonSerializer<T> {

    /**
     * Must produce arbitrary closed Json value in the receiver writer.
     */
    fun JsonWriter.write(value: T)

    /**
     * Must consume given JsonValue and produce the value it represents.
     */
    fun read(value:JsonValue):T
}

/** Convenience shortcut for calling [JsonSerializer.write]. */
fun <T> JsonSerializer<T>.writeTo(writer:JsonWriter, item:T) {
    writer.apply {
        write(item)
    }
}

/**
 * Lightweight version of [JsonSerializer] for classes that can be only serialized, not deserialized.
 * (Unless [JsonReadable] is also implemented.)
 *
 * Should be implemented by the serialized classes themselves.
 * Written by [JsonWriter.writeValue].
 */
interface JsonWritable {

    /**
     * Must produce arbitrary Json value in the receiver writer.
     */
    fun JsonWriter.write()
}

/**
 * Complement of [JsonWritable] for mutable classes, that are stored immutably,
 * so their deserialization happens in-place.
 *
 * Should be implemented by the serialized classes themselves.
 * Read by [fieldTo] and [readJsonTo].
 */
interface JsonReadable {

    /**
     * Must consume given JsonValue and set itself to the value it represents.
     */
    fun read(value:JsonValue)
}

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class Json(val serializer: KClass<out JsonSerializer<*>>)

/**
 * All registered serializers.
 */
private val SERIALIZERS = HashMap<Class<*>, JsonSerializer<*>>().apply {
    put(File::class.java, object : JsonSerializer<File> {
        override fun JsonWriter.write(value: File) {
            writeValue(value.absolutePath, String::class.java)
        }

        override fun read(value: JsonValue): File {
            return File(value.to(String::class.java))
        }
    })

    put(Path::class.java, object : JsonSerializer<Path> {
        override fun JsonWriter.write(value: Path) {
            writeValue(value.absolutePath, String::class.java)
        }

        override fun read(value: JsonValue): Path {
            return Paths.get(value.to(String::class.java))
        }
    })

    put(URL::class.java, object : JsonSerializer<URL> {
        override fun JsonWriter.write(value: URL) {
            writeValue(value.toExternalForm(), String::class.java)
        }

        override fun read(value: JsonValue): URL {
            return URL(value.to(String::class.java))
        }
    })
}

/**
 * Obtain serializer for given [type].
 * Interfaces and superclasses are also searched, it is assumed that they can handle any subclass.
 * @throws JsonException when type has no serializer
 */
private fun <T> serializerFor(type:Class<T>):JsonSerializer<T>? {
    var searchedType:Class<*> = type
    while (true) {
        val base = getOrCreateSerializerFor(searchedType)
        if (base != null) {
            @Suppress("UNCHECKED_CAST")
            return base as JsonSerializer<T>
        }

        for (i in searchedType.interfaces) {
            val int = serializerFor(i) // Interfaces may extend interfaces...
            @Suppress("UNCHECKED_CAST")
            if (int != null) {
                return int as JsonSerializer<T>
            }
        }

        searchedType = searchedType.superclass ?: return null
        if (searchedType == Object::class.java) {
            return null
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> getOrCreateSerializerFor(type:Class<T>):JsonSerializer<T>? {
    var serializer = SERIALIZERS[type]
    if (serializer == null) {
        val jsonAnnotation = type.getAnnotation(Json::class.java) ?: return null

        try {
            serializer = jsonAnnotation.serializer.java.newInstance()
        } catch (e:Exception) {
            throw JsonException("Can't create serializer for $type", e)
        }
        SERIALIZERS[type] = serializer
    }
    return serializer as JsonSerializer<T>
}

/**
 * @see to
 */
inline fun <reified T> fromJson(json: String):T {
    return JsonReader().parse(json).to(T::class.java)
}

/**
 * @see to
 */
inline fun <reified T> Reader.readJson():T {
    return JsonReader().parse(this).to(T::class.java)
}

fun <T : JsonReadable> Reader.readJsonTo(targetValue:T) {
    targetValue.read(JsonReader().parse(this))
}

/** Convenience method, same as `get(name).to(T::class.java)`. */
inline fun <reified T> JsonValue?.field(name:String):T {
    return this?.get(name).to(T::class.java)
}

/** Same as the other [field], but if the field does't exist, returns [default]. */
inline fun <reified T> JsonValue?.field(name:String, default:T):T {
    val value = this?.get(name) ?: return default
    return value.to(T::class.java)
}

fun <T : JsonReadable> JsonValue?.fieldTo(name:String, targetValue:T) {
    val jsonValue = this?.get(name) ?: return
    targetValue.read(jsonValue)
}

inline fun <C : MutableCollection<E>, reified E> JsonValue?.fieldToCollection(name:String, collection:C):C {
    return this?.get(name).toCollection(E::class.java, collection)
}

inline fun <M : MutableMap<K, V>, reified K, reified V> JsonValue?.fieldToMap(name:String, map:M):M {
    this?.get(name).toMap(K::class.java, V::class.java, map)
    return map
}

/**
 * Converts given [JsonValue] to object of given [type].
 *
 * Not suited for [Collection]s or [Map]s, see [toCollection].
 */
@Suppress("UNCHECKED_CAST")
fun <T> JsonValue?.to(type:Class<T>):T {
    if (this == null || isNull) {
        // Assuming T is nullable
        return null as T
    }

    when (type) {
        // Primitives
        java.lang.Boolean::class.java, java.lang.Boolean.TYPE ->
            return this.asBoolean() as T
        java.lang.Byte::class.java, java.lang.Byte.TYPE ->
            return this.asByte() as T
        java.lang.Short::class.java, java.lang.Short.TYPE ->
            return this.asShort() as T
        java.lang.Character::class.java, java.lang.Character.TYPE ->
            return this.asChar() as T
        java.lang.Integer::class.java, java.lang.Integer.TYPE ->
            return this.asInt() as T
        java.lang.Long::class.java, java.lang.Long.TYPE ->
            return this.asLong() as T
        java.lang.Float::class.java, java.lang.Float.TYPE ->
            return this.asFloat() as T
        java.lang.Double::class.java, java.lang.Double.TYPE ->
            return this.asDouble() as T
        // Primitive arrays
        BooleanArray::class.java ->
            return this.asBooleanArray() as T
        ByteArray::class.java ->
            return this.asByteArray() as T
        ShortArray::class.java ->
            return this.asShortArray() as T
        CharArray::class.java ->
            return this.asCharArray() as T
        IntArray::class.java ->
            return this.asIntArray() as T
        LongArray::class.java ->
            return this.asLongArray() as T
        FloatArray::class.java ->
            return this.asFloatArray() as T
        DoubleArray::class.java ->
            return this.asDoubleArray() as T
        // String
        String::class.java ->
            return this.asString() as T
    }

    // Object Arrays
    if (type.isArray) {
        if (!this.isArray) {
            throw JsonException("Can't read '$this' as $type")
        }
        val componentType = type.componentType
        val result = java.lang.reflect.Array.newInstance(componentType, this.size)
        var i = 0
        var element = this.child
        while (element != null) {
            java.lang.reflect.Array.set(result, i++, element.to(componentType))
            element = element.next
        }

        return result as T
    }

    // Enums
    if (type.isEnum) {
        if (this.isString) {
            val name = this.asString()
            // Case must be exact, searching with ignored case might match multiple constants
            for (constant in (type as Class<out Enum<*>>).enumConstants) {
                if (name == constant.name) {
                    return constant as T
                }
            }
            throw JsonException("Failed to find enum constant named $name for enum $type")
        } else if (this.isLong) {
            val index = this.asInt()
            val constants = type.enumConstants
            if (index < 0 || index >= constants.size) {
                throw JsonException("Enum constant index $index out of range for enum $type")
            }
            return constants[index] as T
        } else {
            throw JsonException("Can't read enum $type from $this")
        }
    }

    // Registered objects
    return (serializerFor(type) ?: throw JsonException("Type $type has no serializer")).read(this)
}

/**
 * [to] version for collections.
 */
fun <C : MutableCollection<E>, E> JsonValue?.toCollection(elementType:Class<E>, collection:C):C {
    if (this == null || isNull) {
        return collection
    }
    if (!isArray) {
        throw JsonException("Can't read collection of $elementType from $this")
    }

    if (collection is ArrayList<*>) {
        collection.ensureCapacity(size)
    }
    var element = child
    while (element != null) {
        @Suppress("UNCHECKED_CAST")
        collection.add(element.to(elementType))
        element = element.next
    }
    return collection
}

/**
 * [to] version for maps.
 */
fun <M : MutableMap<K, V>, K, V> JsonValue?.toMap(keyType:Class<K>, valueType:Class<V>, map:M) {
    if (this == null|| isNull) {
        return
    }

    val shortForm:Boolean = if (isArray) {
        false
    } else if (keyType == String::class.java && isObject) {
        true
    } else {
        throw JsonException("Can't read map of $keyType -> $valueType from $this")
    }

    var element = child
    @Suppress("UNCHECKED_CAST")
    while (element != null) {
        val key:K
        val value:V
        if (shortForm) {
            key = element.name as K
            value = element.to(valueType)
        } else {
            key = element.get("key").to(keyType)
            value = element.get("value").to(valueType)
        }

        map[key] = value
        element = element.next
    }
}

fun <T>Writer.writeJson(value:T, type:Class<T>?) {
    val jsonWriter = JsonWriter(this)
    jsonWriter.setOutputType(OutputType.json)
    jsonWriter.setQuoteLongValues(false)
    jsonWriter.writeValue(value, type)
}

inline fun JsonWriter.writeObject(objectWriter:JsonWriter.()->Unit) {
    `object`()
    objectWriter()
    pop()
}

inline fun JsonWriter.writeArray(objectWriter:JsonWriter.()->Unit) {
    array()
    objectWriter()
    pop()
}

/**
 * Convenience method for `name(name).write(value, T::class.java)`.
 */
inline fun <reified T> JsonWriter.field(name:String, value:T) {
    name(name).writeValue(value, T::class.java)
}

/**
 * Convenience method for `name(name).writeCollection(E::class.java, collection)`.
 */
inline fun <C : Collection<E>, reified E> JsonWriter.fieldCollection(name:String, collection:C) {
    name(name).writeCollection(E::class.java, collection)
}

inline fun <M : Map<K, V>, reified K, reified V> JsonWriter.fieldMap(name:String, map:M) {
    name(name).writeMap(K::class.java, V::class.java, map)
}

/**
 * Write non-collection-or-map type [value] of [type] to the writer.
 */
fun <T> JsonWriter.writeValue(value:T, type:Class<T>?) {
    if (value == null) {
        value(null)
        return
    }

    @Suppress("UNCHECKED_CAST")
    val valueType:Class<T> = type ?: (value as Any).javaClass as Class<T>

    when (valueType) {
        // Primitives
        java.lang.Boolean::class.java, java.lang.Boolean.TYPE,
        java.lang.Byte::class.java, java.lang.Byte.TYPE,
        java.lang.Short::class.java, java.lang.Short.TYPE,
        java.lang.Character::class.java, java.lang.Character.TYPE,
        java.lang.Integer::class.java, java.lang.Integer.TYPE,
        java.lang.Long::class.java, java.lang.Long.TYPE,
        java.lang.Float::class.java, java.lang.Float.TYPE,
        java.lang.Double::class.java, java.lang.Double.TYPE,
        // String
        String::class.java -> {
            value(value)
            return
        }
    }

    // Object & Primitive Arrays
    if (valueType.isArray) {
        writeArray {
            val componentType = valueType.componentType
            val length = java.lang.reflect.Array.getLength(value)
            for (i in 0 until length) {
                val element = java.lang.reflect.Array.get(value, i)
                @Suppress("UNCHECKED_CAST")
                writeValue(element, componentType as Class<Any>)
            }
        }
        return
    }

    // Enums
    if (valueType.isEnum) {
        this.value((value as Enum<*>).name)
        return
    }

    // JsonWritable
    if (value is JsonWritable) {
        (value as JsonWritable).apply {
            write()
        }
        return
    }

    // Registered objects
    serializerFor(valueType)?.apply {
        write(value)
        return
    }

    // Collections & Maps can be written, if type is not explicit
    if (type == null && value is Collection<*>) {
        writeCollection(null, value)
        return
    }
    if (type == null && value is Map<*, *>) {
        @Suppress("UNCHECKED_CAST")
        writeMap(null, null, value as Map<Any?, Any?>)
        return
    }

    throw JsonException("Type $valueType has no serializer")
}

/**
 * [writeValue] version for collections.
 */
fun <C : Collection<E>, E> JsonWriter.writeCollection(elementType:Class<E>?, collection:C?) {
    if (collection == null) {
        value(null)
        return
    }

    writeArray {
        for (element in collection) {
            writeValue(element, elementType)
        }
    }
}

/**
 * [writeValue] version for maps with arbitrary keys.
 * String keys are handled as a special case.
 */
fun <M : Map<K, V>, K, V> JsonWriter.writeMap(keyType:Class<K>?, valueType:Class<V>?, map:M?) {
    if (map == null) {
        value(null)
        return
    }

    if (keyType == String::class.java) {
        // Short form
        writeObject {
            for ((key, value) in map) {
                name(key as String).writeValue(value, valueType)
            }
        }
    } else {
        writeArray {
            for ((key, value) in map) {
                writeObject {
                    name("key").writeValue(key, keyType)
                    name("value").writeValue(value, valueType)
                }
            }
        }
    }
}