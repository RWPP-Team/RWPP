/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.api.graphics

import java.io.BufferedReader
import java.io.InputStreamReader

class ShaderProgram(val programID: Int, val fragID: Int?, val vertexID: Int?) {
    private val locationCache: MutableMap<String, Int> = mutableMapOf()

    fun bind() {
        GL20.glUseProgram(programID)
    }

    fun getUniformLocation(name: String): Int {
        if (locationCache.containsKey(name)) {
            return locationCache[name]!!
        }
        val location = GL20.glGetUniformLocation(programID, name)
        if (location == -1) {
            return location
        }
        locationCache[name] = location
        return location
    }

    fun setUniform(name: String, vararg values: Float) {
        val loc = getUniformLocation(name)
        if (loc == -1) return

        when (values.size) {
            1 -> GL20.glUniform1f(loc, values[0])
            2 -> GL20.glUniform2f(loc, values[0], values[1])
            3 -> GL20.glUniform3f(loc, values[0], values[1], values[2])
            4 -> GL20.glUniform4f(loc, values[0], values[1], values[2], values[3])
            else -> throw IllegalArgumentException("Unsupported uniform float count: " + values.size)
        }
    }

    fun setUniformColor(name: String, red: Float, green: Float, blue: Float, alpha: Float, includeAlpha: Boolean) {
        if (includeAlpha) {
            setUniform(name, red, green, blue, alpha)
        } else {
            setUniform(name, red, green, blue)
        }
    }

    fun setUniformInt(name: String, value: Int) {
        val loc = getUniformLocation(name)
        if (loc != -1) {
            GL20.glUniform1i(loc, value)
        }
    }

    fun setUniform1f(name: String, value: Float) {
        val loc = GL20.glGetUniformLocation(programID, name)
        if (loc != -1) {
            GL20.glUniform1f(loc, value)
        }
    }

    fun cleanup() {
        unbind()
        if (vertexID != null) {
            GL20.glDetachShader(programID, vertexID)
            GL20.glDeleteShader(vertexID)
        }

        if (fragID != null) {
            GL20.glDetachShader(programID, fragID)
            GL20.glDeleteShader(fragID)
        }

        GL20.glDeleteProgram(programID)
    }

    fun unbind() {
        ShaderProgram.unbind()
    }

    companion object {
        fun unbind() {
            GL20.glUseProgram(0)
        }

        fun loadShaderFromPath(
            fragPath: String?,
            vertexPath: String? = null
        ): ShaderProgram {
            return loadShaderFragFromString(
                fragPath?.let { readFromResource(it) },
                vertexPath?.let { readFromResource(it) }
            )
        }

        fun loadShaderFragFromString(
            fragSource: String?,
            vertexSource: String? = null
        ): ShaderProgram {
            val programId = GL20.glCreateProgram()

            var fragID: Int? = null
            if (fragSource != null) {
                fragID = GL20.glCreateShader(GLConstants.GL_FRAGMENT_SHADER)
                GL20.glShaderSource(fragID, fragSource)
                GL20.glCompileShader(fragID)

                val status = GL20.glGetShaderi(fragID, GLConstants.GL_COMPILE_STATUS)
                if (status == GLConstants.GL_FALSE) {
                    val log = GL20.glGetShaderInfoLog(fragID)
                    throw RuntimeException("Shader Compile Error (embed source):\n$log")
                }
            }

            var vertexID: Int? = null
            if (vertexSource != null) {
                vertexID = GL20.glCreateShader(GLConstants.GL_VERTEX_SHADER)
                GL20.glShaderSource(vertexID, vertexSource)
                GL20.glCompileShader(vertexID)

                val status = GL20.glGetShaderi(vertexID, GLConstants.GL_COMPILE_STATUS)
                if (status == GLConstants.GL_FALSE) {
                    val log = GL20.glGetShaderInfoLog(vertexID)
                    throw RuntimeException("Shader Compile Error (embed source):\n$log")
                }
            }

            if (vertexID != null) GL20.glAttachShader(programId, vertexID)
            if (fragID != null) GL20.glAttachShader(programId, fragID)
            GL20.glLinkProgram(programId)
            GL20.glValidateProgram(programId)
            return ShaderProgram(programId, fragID, vertexID)
        }

        private fun readFromResource(path: String): String {
            val shaderSource = StringBuilder()
            BufferedReader(
                InputStreamReader(
                    requireNotNull(
                        (Thread.currentThread().contextClassLoader ?: ShaderProgram::class.java.classLoader)
                            ?.getResourceAsStream(path)
                    ) { "Shader resource not found: $path" }
                )
            ).use { reader ->
                var line: String?
                while ((reader.readLine().also { line = it }) != null) {
                    shaderSource.append(line).append("\n")
                }
            }
            return shaderSource.toString()
        }
    }
}
