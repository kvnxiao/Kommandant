/*
 * Copyright 2017 Ze Hao Xiao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.kvnxiao.kommandant

import com.github.kvnxiao.kommandant.command.CommandResult
import com.github.kvnxiao.kommandant.impl.CommandBank
import com.github.kvnxiao.kommandant.impl.CommandExecutor
import com.github.kvnxiao.kommandant.impl.CommandParser
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.async

/**
 * The default aggregation of a command registry, parser, and executor, which manages and executes commands.
 *
 * @property[cmdBank] The command registry, an implementation of [ICommandBank].
 * @property[cmdExecutor] The command executor, an implementation of [ICommandExecutor].
 * @property[cmdParser] The command parser, an impementation of [ICommandParser].
 * @constructor Default constructor uses default implementations of the registry, parser, and executor.
 */
open class KommandantAsync(cmdBank: ICommandBank = CommandBank(), cmdExecutor: ICommandExecutor = CommandExecutor(), cmdParser: ICommandParser = CommandParser()) : Kommandant(cmdBank, cmdExecutor, cmdParser) {

    /**
     * Processes a string input with any additional variables for command execution asynchronously.
     * This wraps the original process function with a coroutine which returns a deferred command result.
     *
     * @param[input] The string input to parse into valid context and command.
     * @param[opt] A nullable vararg of optional, extra input variables.
     * @return[Deferred] The deferred result after attempting execution of the command asynchronously.
     */
    open fun <T> processAsync(input: String, vararg opt: Any?): Deferred<CommandResult<T>> = async(CommonPool) { process<T>(input, *opt) }

}