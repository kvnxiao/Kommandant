/*
 *   Copyright (C) 2017-2018 Ze Hao Xiao
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.github.kvnxiao.kommandant.command.parser

import com.github.kvnxiao.kommandant.DefaultErrorHandler
import com.github.kvnxiao.kommandant.command.CommandDefaults
import com.github.kvnxiao.kommandant.command.CommandPackage
import com.github.kvnxiao.kommandant.command.CommandProperties
import com.github.kvnxiao.kommandant.command.Context
import com.github.kvnxiao.kommandant.command.ExecutableAction
import com.github.kvnxiao.kommandant.command.ExecutionErrorHandler
import com.github.kvnxiao.kommandant.command.annotations.Command
import com.github.kvnxiao.kommandant.command.annotations.ErrorHandler
import com.github.kvnxiao.kommandant.command.annotations.GroupId
import com.github.kvnxiao.kommandant.command.annotations.Info
import com.github.kvnxiao.kommandant.command.annotations.Prefix
import com.github.kvnxiao.kommandant.command.annotations.Settings
import com.github.kvnxiao.kommandant.utility.LINE_SEPARATOR
import mu.KotlinLogging
import java.beans.Introspector
import java.lang.reflect.Method
import java.util.ArrayDeque
import java.util.Queue

private val LOGGER = KotlinLogging.logger { }

/**
 * The default annotation parser implementation. Parses a class instance to create a list of commands from the class.
 *
 * The only required annotation to define a command is the [Command] annotation. The annotation parser will parse
 * [Command] annotations on methods with a two parameter signature, a [Context] and a nullable [Array] of objects,
 * to create the base command.
 *
 * Optionally, the annotation parser will also parse:
 * - [ErrorHandler] annotations on a getter method returning [ExecutionErrorHandler] for custom error
 * handlers.
 * - [GroupId] annotations for setting the group parent id of all commands declared in the class instance.
 * - [Prefix] annotations on a class level to globally set the prefix for all commands, and on a method level
 * to locally set the prefix for each individual command.
 * - [Info] annotations for setting the command metadata (i.e. description, usage).
 * - [Settings] annotations for enabling or disabling certain command settings.
 *
 * Optional annotations that are not declared in the class instance will result in the command defaulting to values
 * declared in [CommandDefaults].
 *
 * @see [AnnotationParser]
 */
open class AnnotationParserImpl : AnnotationParser {
    override fun parseAnnotations(instance: Any): List<CommandPackage<*>> {
        val clazz = instance::class.java

        // Preconditions check
        val methodSet = clazz.methods.filter { it.isAnnotationPresent(Command::class.java) }.toMutableSet()
        if (methodSet.isEmpty()) {
            LOGGER.debug { "Skipping class ${clazz.name} as there are no methods annotated with @${Command::class.java.simpleName}" }
            return emptyList()
        }
        require(methodSet.map { it.commandAnn().id }.toSet().size == methodSet.size, {
            "All methods annotated with @${Command::class.java.simpleName} must each have a unique 'id' value!"
        })
        require(methodSet.none {
            val commandAnn = it.commandAnn()
            commandAnn.parentId == commandAnn.id
        }, {
            "Methods annotated with @${Command::class.java.simpleName} cannot self reference with the same 'id' and 'parentId' values."
        })

        // Establish parent-child relationship of methods
        val idToMethodMap = mutableMapOf<String, Method>()
        val methodToIdMap = mutableMapOf<Method, String>()
        val rootMethodSet = methodSet.filter { it.commandAnn().parentId == CommandDefaults.PARENT_ID }
        methodSet.removeAll(rootMethodSet)
        val methodQueue: Queue<Method> = ArrayDeque()
        methodQueue.addAll(rootMethodSet)
        while (methodQueue.isNotEmpty()) {
            // Add method to map
            val method = methodQueue.poll()
            val annId = method.commandAnn().id
            if (!methodToIdMap.containsKey(method)) {
                idToMethodMap[annId] = method
                methodToIdMap[method] = annId
            }
            // Find children of current method
            val childrenSet = methodSet.filter { it.commandAnn().parentId == annId }
            if (childrenSet.isNotEmpty()) {
                childrenSet.forEach {
                    val childId = methodToIdMap[method] + "." + it.commandAnn().id
                    idToMethodMap[childId] = it
                    methodToIdMap[it] = childId
                }
                methodQueue.addAll(childrenSet)
                methodSet.removeAll(childrenSet)
            }
        }
        require(methodSet.isEmpty(), {
            "${methodSet.size} invalid 'parentId' values declared in the @${Command::class.java.simpleName} annotation for ${instance::class.java.name}:" +
                methodSet.joinToString(separator = LINE_SEPARATOR, prefix = LINE_SEPARATOR) {
                    "method ${it.name} -> @${Command::class.java.simpleName} annotation id=${it.commandAnn().id} references invalid parentId=${it.commandAnn().parentId}"
                }
        })

        val commandGroup: String = instance::class.java.getCommandGroup() ?: CommandDefaults.PARENT_ID
        val globalPrefix: String? = instance::class.java.getGlobalPrefix()

        return idToMethodMap.map { (id, method) ->
            this.createCommand(method, method.commandAnn(), commandGroup + id, globalPrefix, instance)
        }.toList()
    }

    /**
     * Creates a command from the given method, class instance, command annotation, and other provided parameters describing the command.
     */
    protected open fun createCommand(method: Method, commandAnn: Command, id: String, globalPrefix: String?, instance: Any): CommandPackage<*> {
        val properties = createProperties(method, id, globalPrefix, commandAnn)
        val executable = createExecutable(method, instance)
        val errorHandler = createErrorHandler(method, instance)
        return CommandPackage(executable, properties, errorHandler)
    }

    /**
     * Creates the command properties for the command, using the provided parameters describing the command.
     */
    protected open fun createProperties(method: Method, id: String, globalPrefix: String?, commandAnn: Command): CommandProperties {
        // Check if there is a local prefix -- Global prefix will always override local prefix -- Default to empty string ""
        val prefix = globalPrefix ?: method.getPrefix() ?: CommandDefaults.NO_PREFIX
        // Check if there is a @Info annotation
        val commandInfo = method.getCommandInfo()
        // Check if there is a @Settings annotation
        val commandSettings = method.getCommandSettings()

        return CommandProperties(
            id = id,
            prefix = prefix,
            aliases = commandAnn.aliases.toSet(),
            parentId = if (commandAnn.parentId == CommandDefaults.PARENT_ID) CommandDefaults.PARENT_ID else id.substring(0, id.length - commandAnn.id.length - 1),
            description = commandInfo?.description ?: CommandDefaults.NO_DESCRIPTION,
            usage = commandInfo?.usage ?: CommandDefaults.NO_USAGE,
            execWithSubCommands = commandSettings?.execWithSubCommands ?: CommandDefaults.EXEC_WITH_SUBCOMMANDS,
            isDisabled = commandSettings?.isDisabled ?: CommandDefaults.IS_DISABLED
        )
    }

    /**
     * Creates the command executable action for the provided method and class instance.
     */
    protected open fun createExecutable(method: Method, instance: Any): ExecutableAction<Any?> {
        return object : ExecutableAction<Any?> {
            override fun execute(context: Context, opt: Array<Any>?): Any? {
                return method.invoke(instance, context, opt)
            }
        }
    }

    /**
     * Creates the error handler for the provided command's class instance.
     */
    protected open fun createErrorHandler(method: Method, instance: Any, defaultHandler: () -> ExecutionErrorHandler = { DefaultErrorHandler() }): ExecutionErrorHandler {
        // Get error handler from class instance
        val clazz = instance::class.java
        val errorHandlerMethod = Introspector.getBeanInfo(clazz).propertyDescriptors.map { it.readMethod }.filter { it.isAnnotationPresent(ErrorHandler::class.java) }
        require(errorHandlerMethod.size <= 1, { "Cannot have more than one error handler declared!" })

        return if (errorHandlerMethod.isNotEmpty()) {
            errorHandlerMethod[0].invoke(instance) as ExecutionErrorHandler
        } else {
            defaultHandler()
        }
    }

    /**
     * Gets the [GroupId] annotation from the class instance.
     */
    protected fun Class<out Any>.getCommandGroup(): String? =
        if (this.isAnnotationPresent(GroupId::class.java)) this.getAnnotation(GroupId::class.java).groupName + "."
        else null

    /**
     * Gets the [Prefix] annotation from the class instance, for defining a global prefix.
     */
    protected fun Class<out Any>.getGlobalPrefix(): String? =
        if (this.isAnnotationPresent(Prefix::class.java)) this.getAnnotation(Prefix::class.java).prefix
        else null

    /**
     * Gets the [Prefix] annotation from a method declaration, for defining a local prefix.
     */
    protected fun Method.getPrefix(): String? =
        if (this.isAnnotationPresent(Prefix::class.java)) this.getAnnotation(Prefix::class.java).prefix
        else null

    /**
     * Gets the [Info] annotation from a method declaration.
     */
    protected fun Method.getCommandInfo(): Info? =
        if (this.isAnnotationPresent(Info::class.java)) this.getAnnotation(Info::class.java)
        else null

    /**
     * Gets the [Settings] annotation from a method declaration.
     */
    protected fun Method.getCommandSettings(): Settings? =
        if (this.isAnnotationPresent(Settings::class.java)) this.getAnnotation(Settings::class.java)
        else null

    /**
     * Gets the [Command] annotation from a method declaration.
     */
    protected fun Method.commandAnn(): Command = this.getAnnotation(Command::class.java)
}
