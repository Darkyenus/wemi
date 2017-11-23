package wemi.boot

import com.esotericsoftware.jsonbeans.*
import org.slf4j.LoggerFactory
import wemi.AllConfigurations
import wemi.AllKeys
import wemi.AllProjects
import wemi.WemiException
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 *
 */

private val LOG = LoggerFactory.getLogger("MachineReadableOutput")

fun machineReadableEvaluateAndPrint(out: PrintStream, task:String) {
    when (task) {
        "#projects" -> {
            machineReadablePrint(out, AllProjects.values)
            return
        }
        "#configurations" -> {
            machineReadablePrint(out, AllConfigurations.values)
            return
        }
        "#keys" -> {
            machineReadablePrint(out, AllKeys.values)
            return
        }
        "#keysWithDescription" -> {
            machineReadablePrint(out, object : MachineWritable {
                override fun writeMachine(json: Json) {
                    json.writeArrayStart()
                    for (key in AllKeys.values) {
                        json.writeObjectStart()
                        json.writeValue("name", key.name, String::class.java)
                        json.writeValue("description", key.description, String::class.java)
                        json.writeObjectEnd()
                    }
                    json.writeArrayEnd()
                }
            })
            return
        }
        "#buildScripts" -> {
            machineReadablePrint(out, object : MachineWritable {
                override fun writeMachine(json: Json) {
                    json.writeArrayStart()
                    for ((buildFile, projects) in BuildScriptIntrospection.buildScriptProjects) {
                        json.writeObjectStart()

                        json.writeValue("buildFolder", buildFile.buildFolder)
                        json.writeValue("sources", buildFile.sources)
                        json.writeValue("scriptJar", buildFile.scriptJar)
                        json.writeValue("classpath", buildFile.classpath)
                        json.writeArrayStart("projects")
                        for (project in projects) {
                            json.writeValue(project.name)
                        }
                        json.writeArrayEnd()

                        json.writeObjectEnd()
                    }
                    json.writeArrayEnd()
                }
            })
            return
        }
        "#defaultProject" -> {
            machineReadablePrint(out, CLI.findDefaultProject(File("."))?.name)
            return
        }
    }

    try {
        val (data, status) = CLI.evaluateKey(task)
        when (status) {
            CLI.KeyEvaluationStatus.Success -> {
                machineReadablePrint(out, data)
            }
            CLI.KeyEvaluationStatus.NoProject -> {
                val projectString = data as String?
                if (projectString != null) {
                    LOG.error("Can't evaluate {} - no project named {} found", task, projectString)
                } else {
                    LOG.error("Can't evaluate {} - no project specified", task)
                }
                exitProcess(EXIT_CODE_MACHINE_OUTPUT_NO_PROJECT_ERROR)
            }
            CLI.KeyEvaluationStatus.NoConfiguration -> {
                val configString = data as String
                LOG.error("Can't evaluate {} - no configuration named {} found", task, configString)
                exitProcess(EXIT_CODE_MACHINE_OUTPUT_NO_CONFIGURATION_ERROR)
            }
            CLI.KeyEvaluationStatus.NoKey -> {
                val keyString = data as String
                LOG.error("Can't evaluate {} - no key named {} found", task, keyString)
                exitProcess(EXIT_CODE_MACHINE_OUTPUT_NO_KEY_ERROR)
            }
            CLI.KeyEvaluationStatus.NotAssigned -> {
                val error = data as WemiException.KeyNotAssignedException
                LOG.error("Can't evaluate {} - {}{} not set", task, error.scope.scopeToString(), error.key.name)
                exitProcess(EXIT_CODE_MACHINE_OUTPUT_KEY_NOT_SET_ERROR)
            }
            CLI.KeyEvaluationStatus.Exception -> {
                val we = data as WemiException

                val message = we.message
                if (we.showStacktrace || message == null || message.isBlank()) {
                    LOG.error("Can't evaluate {} - exception", task, we)
                } else {
                    LOG.error("Can't evaluate {} - {}", task, we.message)
                    LOG.debug("Can't evaluate {} - exception to previous message", task, we)
                }
                exitProcess(EXIT_CODE_MACHINE_OUTPUT_THROWN_EXCEPTION_ERROR)
            }
        }
    } catch (e: Throwable) {
        LOG.error("Can't evaluate {} - fatal exception", task, e)
        exitProcess(EXIT_CODE_MACHINE_OUTPUT_THROWN_EXCEPTION_ERROR)
    }
}

private val MACHINE_READABLE_JSON_WRITING = object: Json(){

    fun isSimpleType(type:Class<*>):Boolean {
        return type.isPrimitive || type == String::class.java || type == Int::class.java || type == Boolean::class.java
                || type == Float::class.java || type == Long::class.java || type == Double::class.java
                || type == Short::class.java || type == Byte::class.java || type == Char::class.java
    }

    override fun writeValue(value: Any?, knownType: Class<*>?, elementType: Class<*>?) {
        try {
            val writer = this.writer

            if (value == null) {
                writer.value(null)
            } else if (knownType != null && isSimpleType(knownType)) {
                writer.value(value)
            } else {
                val actualType: Class<*> = value.javaClass
                @Suppress("UNCHECKED_CAST")
                val serializer = getSerializer(actualType) as JsonSerializer<Any>?

                when {
                    serializer != null -> {
                        serializer.write(this, value, knownType)
                    }
                    value is MachineWritable -> {
                        value.writeMachine(this)
                    }
                    value is JsonSerializable -> {
                        this.writeObjectStart(actualType, knownType)
                        value.write(this)
                        this.writeObjectEnd()
                    }
                    isSimpleType(actualType) -> {
                        this.writeValue(value)
                    }
                    actualType.isArray -> {
                        val length = java.lang.reflect.Array.getLength(value)
                        this.writeArrayStart()

                        var i = 0
                        while (i < length) {
                            this.writeValue(java.lang.reflect.Array.get(value, i) as Any, elementType ?: actualType.componentType, null as Class<*>?)
                            i++
                        }

                        this.writeArrayEnd()
                    }
                    value is Map<*, *> -> {
                        this.writeArrayStart()
                        for ((k, v) in value) {
                            this.writeObjectStart()
                            this.writeValue("key", k)
                            this.writeValue("value", v)
                            this.writeObjectEnd()
                        }
                        this.writeArrayEnd()
                    }
                    value is Collection<*> -> {
                        this.writeArrayStart()
                        for (item in value) {
                            this.writeValue(item)
                        }
                        this.writeArrayEnd()
                    }
                    Enum::class.java.isAssignableFrom(actualType) -> {
                        //Does not respect enumNames!!!
                        writer.value((value as Enum<*>).name)
                    }
                    else -> {
                        this.writeObjectStart(actualType, knownType)
                        this.writeFields(value)
                        this.writeObjectEnd()
                    }
                }
            }
        } catch (e: IOException) {
            throw JsonException(e)
        }
    }
}.apply {
    this.setSerializer(File::class.java, object : JsonSerializer<File> {
        override fun write(json: Json, value: File?, type: Class<*>?) {
            json.writeValue(value?.absolutePath as Any?, String::class.java)
        }

        override fun read(json: Json, value: JsonValue, type: Class<*>?): File {
            throw NotImplementedError("Only writing is supported")
        }
    })

    this.setOutputType(com.esotericsoftware.jsonbeans.OutputType.json)
    this.setUsePrototypes(false)
    this.setEnumNames(true)
}

private fun machineReadablePrint(out: PrintStream, thing:Any?) {
    val writer = OutputStreamWriter(out)
    val json = MACHINE_READABLE_JSON_WRITING
    json.setWriter(writer)
    json.writeValue(thing, null, null)
    json.setWriter(null)
    writer.append(0.toChar())
    writer.flush()
}

interface MachineWritable {
    fun writeMachine(json: Json)
}
