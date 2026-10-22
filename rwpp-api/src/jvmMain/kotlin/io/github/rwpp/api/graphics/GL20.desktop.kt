/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.api.graphics

import io.github.rwpp.api.graphics.GLConstants
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.EXTFramebufferObject
import org.lwjgl.opengl.ARBES2Compatibility
import org.lwjgl.opengl.GLContext
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL15
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.DoubleBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer
import java.nio.charset.Charset
import org.lwjgl.opengl.GL20 as JL20

@Suppress("unused")
actual object GL20 {
    private var buffer: ByteBuffer? = null
    private var floatBuffer: FloatBuffer? = null
    private var intBuffer: IntBuffer? = null

    private fun ensureBufferCapacity(numBytes: Int) {
        if (buffer == null || buffer!!.capacity() < numBytes) {
            buffer = ByteBuffer.allocateDirect(numBytes)
            buffer!!.order(ByteOrder.nativeOrder())
            floatBuffer = buffer!!.asFloatBuffer()
            intBuffer = buffer!!.asIntBuffer()
        }
    }

    private fun toFloatBuffer(v: FloatArray?, offset: Int, count: Int): FloatBuffer {
        ensureBufferCapacity(count shl 2)
        floatBuffer!!.clear()
        floatBuffer!!.limit(count)
        floatBuffer!!.put(v, offset, count)
        floatBuffer!!.position(0)
        return floatBuffer!!
    }

    private fun toIntBuffer(v: IntArray?, offset: Int, count: Int): IntBuffer {
        ensureBufferCapacity(count shl 2)
        intBuffer!!.clear()
        intBuffer!!.limit(count)
        intBuffer!!.put(v, offset, count)
        intBuffer!!.position(0)
        return intBuffer!!
    }

    actual fun glActiveTexture(texture: Int) {
        GL13.glActiveTexture(texture)
    }

    actual fun glAttachShader(program: Int, shader: Int) {
        JL20.glAttachShader(program, shader)
    }

    actual fun glBindAttribLocation(program: Int, index: Int, name: String?) {
        JL20.glBindAttribLocation(program, index, name)
    }

    actual fun glBindBuffer(target: Int, buffer: Int) {
        GL15.glBindBuffer(target, buffer)
    }

    actual fun glBindFramebuffer(target: Int, framebuffer: Int) {
        EXTFramebufferObject.glBindFramebufferEXT(target, framebuffer)
    }

    actual fun glBindRenderbuffer(target: Int, renderbuffer: Int) {
        EXTFramebufferObject.glBindRenderbufferEXT(target, renderbuffer)
    }

    actual fun glBindTexture(target: Int, texture: Int) {
        GL11.glBindTexture(target, texture)
    }

    actual fun glBlendColor(red: Float, green: Float, blue: Float, alpha: Float) {
        GL14.glBlendColor(red, green, blue, alpha)
    }

    actual fun glBlendEquation(mode: Int) {
        GL14.glBlendEquation(mode)
    }

    actual fun glBlendEquationSeparate(modeRGB: Int, modeAlpha: Int) {
        JL20.glBlendEquationSeparate(modeRGB, modeAlpha)
    }


    actual fun glBufferData(target: Int, size: Int, data: Buffer?, usage: Int) {
        if (data == null) GL15.glBufferData(target, size.toLong(), usage)
        else if (data is ByteBuffer) GL15.glBufferData(target, data, usage)
        else if (data is IntBuffer) GL15.glBufferData(target, data, usage)
        else if (data is FloatBuffer) GL15.glBufferData(target, data, usage)
        else if (data is DoubleBuffer) GL15.glBufferData(target, data, usage)
        else if (data is ShortBuffer)  //
            GL15.glBufferData(target, data, usage)
    }

    actual fun glBufferSubData(target: Int, offset: Int, size: Int, data: Buffer?) {
        if (data == null) throw RuntimeException("Using null for the data not possible, blame LWJGL")
        else if (data is ByteBuffer) GL15.glBufferSubData(target, offset.toLong(), data)
        else if (data is IntBuffer) GL15.glBufferSubData(target, offset.toLong(), data)
        else if (data is FloatBuffer) GL15.glBufferSubData(target, offset.toLong(), data)
        else if (data is DoubleBuffer) GL15.glBufferSubData(target, offset.toLong(), data)
        else if (data is ShortBuffer)  //
            GL15.glBufferSubData(target, offset.toLong(), data)
    }

    actual fun glCheckFramebufferStatus(target: Int): Int {
        return EXTFramebufferObject.glCheckFramebufferStatusEXT(target)
    }

    actual fun glClear(mask: Int) {
        GL11.glClear(mask)
    }

    actual fun glClearColor(red: Float, green: Float, blue: Float, alpha: Float) {
        GL11.glClearColor(red, green, blue, alpha)
    }

    actual fun glClearDepthf(depth: Float) {
        GL11.glClearDepth(depth.toDouble())
    }

    actual fun glClearStencil(s: Int) {
        GL11.glClearStencil(s)
    }

    actual fun glColorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) {
        GL11.glColorMask(red, green, blue, alpha)
    }

    actual fun glCompileShader(shader: Int) {
        JL20.glCompileShader(shader)
    }

    actual fun glCompressedTexImage2D(
        target: Int, level: Int, internalformat: Int, width: Int, height: Int, border: Int,
        imageSize: Int, data: Buffer?
    ) {
        if (data is ByteBuffer) {
            GL13.glCompressedTexImage2D(target, level, internalformat, width, height, border, data)
        } else {
            throw RuntimeException("Can't use " + data?.javaClass?.getName() + " with this method. Use ByteBuffer instead.")
        }
    }

    actual fun glCompressedTexSubImage2D(
        target: Int, level: Int, xoffset: Int, yoffset: Int, width: Int, height: Int, format: Int,
        imageSize: Int, data: Buffer?
    ) {
        val bytes = requireNotNull(data as? ByteBuffer) { "Compressed texture data must be a ByteBuffer" }.duplicate()
        require(imageSize >= 0 && imageSize <= bytes.remaining())
        (bytes as Buffer).limit(bytes.position() + imageSize)
        GL13.glCompressedTexSubImage2D(target, level, xoffset, yoffset, width, height, format, bytes)
    }

    actual fun glCopyTexImage2D(
        target: Int,
        level: Int,
        internalformat: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        border: Int
    ) {
        GL11.glCopyTexImage2D(target, level, internalformat, x, y, width, height, border)
    }

    actual fun glCopyTexSubImage2D(
        target: Int,
        level: Int,
        xoffset: Int,
        yoffset: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ) {
        GL11.glCopyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height)
    }

    actual fun glCreateProgram(): Int {
        return JL20.glCreateProgram()
    }

    actual fun glCreateShader(type: Int): Int {
        return JL20.glCreateShader(type)
    }

    actual fun glCullFace(mode: Int) {
        GL11.glCullFace(mode)
    }

    actual fun glDeleteBuffer(buffer: Int) {
        GL15.glDeleteBuffers(buffer)
    }

    actual fun glDeleteFramebuffer(framebuffer: Int) {
        EXTFramebufferObject.glDeleteFramebuffersEXT(framebuffer)
    }

    actual fun glDeleteProgram(program: Int) {
        JL20.glDeleteProgram(program)
    }

    actual fun glDeleteRenderbuffer(renderbuffer: Int) {
        EXTFramebufferObject.glDeleteRenderbuffersEXT(renderbuffer)
    }

    actual fun glDeleteShader(shader: Int) {
        JL20.glDeleteShader(shader)
    }

    actual fun glDeleteTexture(texture: Int) {
        GL11.glDeleteTextures(texture)
    }

    actual fun glDepthFunc(func: Int) {
        GL11.glDepthFunc(func)
    }

    actual fun glDepthMask(flag: Boolean) {
        GL11.glDepthMask(flag)
    }

    actual fun glDepthRange(zNear: Double, zFar: Double) {
        GL11.glDepthRange(zNear, zFar)
    }

    actual fun glDetachShader(program: Int, shader: Int) {
        JL20.glDetachShader(program, shader)
    }

    actual fun glDisable(cap: Int) {
        GL11.glDisable(cap)
    }

    actual fun glDisableVertexAttribArray(index: Int) {
        JL20.glDisableVertexAttribArray(index)
    }

    actual fun glDrawArrays(mode: Int, first: Int, count: Int) {
        GL11.glDrawArrays(mode, first, count)
    }

    actual fun glDrawElements(mode: Int, count: Int, type: Int, indices: Buffer?) {
        if (indices is ShortBuffer && type == GLConstants.GL_UNSIGNED_SHORT) {
            val sb = indices
            val position = sb.position()
            val oldLimit = sb.limit()
            sb.limit(position + count)
            GL11.glDrawElements(mode, sb)
            sb.limit(oldLimit)
        } else if (indices is ByteBuffer && type == GLConstants.GL_UNSIGNED_SHORT) {
            val sb = indices.asShortBuffer()
            val position = sb.position()
            val oldLimit = sb.limit()
            sb.limit(position + count)
            GL11.glDrawElements(mode, sb)
            sb.limit(oldLimit)
        } else if (indices is ByteBuffer && type == GLConstants.GL_UNSIGNED_BYTE) {
            val bb = indices
            val position = bb.position()
            val oldLimit = bb.limit()
            bb.limit(position + count)
            GL11.glDrawElements(mode, bb)
            bb.limit(oldLimit)
        } else throw RuntimeException(
            ("Can't use " + indices?.javaClass?.getName()
                    + " with this method. Use ShortBuffer or ByteBuffer instead. Blame LWJGL")
        )
    }

    actual fun glEnable(cap: Int) {
        GL11.glEnable(cap)
    }

    actual fun glEnableVertexAttribArray(index: Int) {
        JL20.glEnableVertexAttribArray(index)
    }

    actual fun glFinish() {
        GL11.glFinish()
    }

    actual fun glFlush() {
        GL11.glFlush()
    }

    actual fun glFramebufferRenderbuffer(
        target: Int,
        attachment: Int,
        renderbuffertarget: Int,
        renderbuffer: Int
    ) {
        EXTFramebufferObject.glFramebufferRenderbufferEXT(
            target,
            attachment,
            renderbuffertarget,
            renderbuffer
        )
    }

    actual fun glFramebufferTexture2D(
        target: Int,
        attachment: Int,
        textarget: Int,
        texture: Int,
        level: Int
    ) {
        EXTFramebufferObject.glFramebufferTexture2DEXT(
            target,
            attachment,
            textarget,
            texture,
            level
        )
    }

    actual fun glFrontFace(mode: Int) {
        GL11.glFrontFace(mode)
    }


    actual fun glGenBuffer(): Int {
        return GL15.glGenBuffers()
    }


    actual fun glGenFramebuffer(): Int {
        return EXTFramebufferObject.glGenFramebuffersEXT()
    }

    actual fun glGenRenderbuffer(): Int {
        return EXTFramebufferObject.glGenRenderbuffersEXT()
    }

    actual fun glGenTexture(): Int {
        return GL11.glGenTextures()
    }

    actual fun glGenerateMipmap(target: Int) {
        EXTFramebufferObject.glGenerateMipmapEXT(target)
    }

    actual fun glGetActiveAttrib(program: Int, index: Int, size: IntBuffer?, type: IntBuffer?): String? {
        val maxNameLength = 256
        val nameBuffer = BufferUtils.createByteBuffer(maxNameLength)
        val lengthBuffer = BufferUtils.createIntBuffer(1)
        JL20.glGetActiveAttrib(program, index, lengthBuffer, size!!, type!!, nameBuffer)
        val length = lengthBuffer.get(0)
        val nameBytes = ByteArray(length)
        nameBuffer.get(nameBytes)
        return String(nameBytes, Charset.forName("UTF-8"))
    }

    actual fun glGetActiveUniform(program: Int, index: Int, size: IntBuffer?, type: IntBuffer?): String? {
        val maxNameLength = 256
        val nameBuffer = BufferUtils.createByteBuffer(maxNameLength)
        val lengthBuffer = BufferUtils.createIntBuffer(1)

        JL20.glGetActiveUniform(program, index, lengthBuffer, size!!, type!!, nameBuffer)

        val length = lengthBuffer.get(0)
        val nameBytes = ByteArray(length)
        nameBuffer.get(nameBytes)
        return String(nameBytes, Charset.forName("UTF-8"))
    }

    actual fun glGetAttribLocation(program: Int, name: String?): Int {
        return JL20.glGetAttribLocation(program, name)
    }


    actual fun glGetError(): Int {
        return GL11.glGetError()
    }


    actual fun glGetProgramInfoLog(program: Int): String? {
        val buffer = ByteBuffer.allocateDirect(1024 * 10)
        buffer.order(ByteOrder.nativeOrder())
        val tmp = ByteBuffer.allocateDirect(4)
        tmp.order(ByteOrder.nativeOrder())
        val intBuffer = tmp.asIntBuffer()

        JL20.glGetProgramInfoLog(program, intBuffer, buffer)
        val numBytes = intBuffer.get(0)
        val bytes = ByteArray(numBytes)
        buffer.get(bytes)
        return String(bytes)
    }

    actual fun glGetShaderInfoLog(shader: Int): String? {
        val buffer = ByteBuffer.allocateDirect(1024 * 10)
        buffer.order(ByteOrder.nativeOrder())
        val tmp = ByteBuffer.allocateDirect(4)
        tmp.order(ByteOrder.nativeOrder())
        val intBuffer = tmp.asIntBuffer()

        JL20.glGetShaderInfoLog(shader, intBuffer, buffer)
        val numBytes = intBuffer.get(0)
        val bytes = ByteArray(numBytes)
        buffer.get(bytes)
        return String(bytes)
    }

    actual fun glGetShaderPrecisionFormat(
        shadertype: Int,
        precisiontype: Int,
        range: IntBuffer?,
        precision: IntBuffer?
    ) {
        val capabilities = GLContext.getCapabilities()
        if (capabilities.OpenGL41 || capabilities.GL_ARB_ES2_compatibility) {
            ARBES2Compatibility.glGetShaderPrecisionFormat(shadertype, precisiontype, range, precision)
        } else {
            // Desktop GLSL uses IEEE 754 floats and 32-bit integers even before the ES query exists.
            val integer = precisiontype in 0x8DF3..0x8DF5
            val ranges = requireNotNull(range)
            val precisions = requireNotNull(precision)
            ranges.put(ranges.position(), if (integer) 31 else 127)
            ranges.put(ranges.position() + 1, if (integer) 30 else 127)
            precisions.put(precisions.position(), if (integer) 0 else 23)
        }
    }


    actual fun glGetString(name: Int): String? {
        return GL11.glGetString(name)
    }

    actual fun glGetUniformLocation(program: Int, name: String?): Int {
        return JL20.glGetUniformLocation(program, name)
    }


    actual fun glHint(target: Int, mode: Int) {
        GL11.glHint(target, mode)
    }

    actual fun glIsBuffer(buffer: Int): Boolean {
        return GL15.glIsBuffer(buffer)
    }

    actual fun glIsEnabled(cap: Int): Boolean {
        return GL11.glIsEnabled(cap)
    }

    actual fun glIsFramebuffer(framebuffer: Int): Boolean {
        return EXTFramebufferObject.glIsFramebufferEXT(framebuffer)
    }

    actual fun glIsProgram(program: Int): Boolean {
        return JL20.glIsProgram(program)
    }

    actual fun glIsRenderbuffer(renderbuffer: Int): Boolean {
        return EXTFramebufferObject.glIsRenderbufferEXT(renderbuffer)
    }

    actual fun glIsShader(shader: Int): Boolean {
        return JL20.glIsShader(shader)
    }

    actual fun glIsTexture(texture: Int): Boolean {
        return GL11.glIsTexture(texture)
    }

    actual fun glLineWidth(width: Float) {
        GL11.glLineWidth(width)
    }

    actual fun glLinkProgram(program: Int) {
        JL20.glLinkProgram(program)
    }

    actual fun glPixelStorei(pname: Int, param: Int) {
        GL11.glPixelStorei(pname, param)
    }

    actual fun glPolygonOffset(factor: Float, units: Float) {
        GL11.glPolygonOffset(factor, units)
    }

    actual fun glReadPixels(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        format: Int,
        type: Int,
        pixels: Buffer?
    ) {
        if (pixels is ByteBuffer) GL11.glReadPixels(x, y, width, height, format, type, pixels)
        else if (pixels is ShortBuffer) GL11.glReadPixels(x, y, width, height, format, type, pixels)
        else if (pixels is IntBuffer) GL11.glReadPixels(x, y, width, height, format, type, pixels)
        else if (pixels is FloatBuffer) GL11.glReadPixels(x, y, width, height, format, type, pixels)
        else throw RuntimeException(
            ("Can't use " + pixels?.javaClass?.getName()
                    + " with this method. Use ByteBuffer, ShortBuffer, IntBuffer or FloatBuffer instead. Blame LWJGL")
        )
    }

    actual fun glReleaseShaderCompiler() {
        val capabilities = GLContext.getCapabilities()
        if (capabilities.OpenGL41 || capabilities.GL_ARB_ES2_compatibility) {
            ARBES2Compatibility.glReleaseShaderCompiler()
        }
    }

    actual fun glRenderbufferStorage(target: Int, internalformat: Int, width: Int, height: Int) {
        EXTFramebufferObject.glRenderbufferStorageEXT(target, internalformat, width, height)
    }

    actual fun glSampleCoverage(value: Float, invert: Boolean) {
        GL13.glSampleCoverage(value, invert)
    }

    actual fun glScissor(x: Int, y: Int, width: Int, height: Int) {
        GL11.glScissor(x, y, width, height)
    }

    actual fun glShaderSource(shader: Int, string: String?) {
        JL20.glShaderSource(shader, string)
    }

    actual fun glStencilFunc(func: Int, ref: Int, mask: Int) {
        GL11.glStencilFunc(func, ref, mask)
    }

    actual fun glStencilFuncSeparate(face: Int, func: Int, ref: Int, mask: Int) {
        JL20.glStencilFuncSeparate(face,func, ref, mask)
    }

    actual fun glStencilMask(mask: Int) {
        GL11.glStencilMask(mask)
    }

    actual fun glStencilMaskSeparate(face: Int, mask: Int) {
        JL20.glStencilMaskSeparate(face, mask)
    }

    actual fun glStencilOp(fail: Int, zfail: Int, zpass: Int) {
        GL11.glStencilOp(fail, zfail, zpass)
    }

    actual fun glStencilOpSeparate(face: Int, fail: Int, zfail: Int, zpass: Int) {
        JL20.glStencilOpSeparate(face, fail, zfail, zpass)
    }

    actual fun glTexImage2D(
        target: Int,
        level: Int,
        internalformat: Int,
        width: Int,
        height: Int,
        border: Int,
        format: Int,
        type: Int,
        pixels: Buffer?
    ) {
        if (pixels == null) GL11.glTexImage2D(
            target,
            level,
            internalformat,
            width,
            height,
            border,
            format,
            type,
            null as ByteBuffer?
        )
        else if (pixels is ByteBuffer) GL11.glTexImage2D(
            target,
            level,
            internalformat,
            width,
            height,
            border,
            format,
            type,
            pixels
        )
        else if (pixels is ShortBuffer) GL11.glTexImage2D(
            target,
            level,
            internalformat,
            width,
            height,
            border,
            format,
            type,
            pixels
        )
        else if (pixels is IntBuffer) GL11.glTexImage2D(
            target,
            level,
            internalformat,
            width,
            height,
            border,
            format,
            type,
            pixels
        )
        else if (pixels is FloatBuffer) GL11.glTexImage2D(
            target,
            level,
            internalformat,
            width,
            height,
            border,
            format,
            type,
            pixels
        )
        else if (pixels is DoubleBuffer) GL11.glTexImage2D(
            target,
            level,
            internalformat,
            width,
            height,
            border,
            format,
            type,
            pixels
        )
        else throw RuntimeException(
            ("Can't use " + pixels.javaClass.getName()
                    + " with this method. Use ByteBuffer, ShortBuffer, IntBuffer, FloatBuffer or DoubleBuffer instead. Blame LWJGL")
        )
    }

    actual fun glTexParameterf(target: Int, pname: Int, param: Float) {
        GL11.glTexParameterf(target, pname, param)
    }

    actual fun glTexParameteri(target: Int, pname: Int, param: Int) {
        GL11.glTexParameteri(target, pname, param)
    }

    actual fun glTexSubImage2D(
        target: Int,
        level: Int,
        xoffset: Int,
        yoffset: Int,
        width: Int,
        height: Int,
        format: Int,
        type: Int,
        pixels: Buffer?
    ) {
        if (pixels is ByteBuffer) GL11.glTexSubImage2D(
            target,
            level,
            xoffset,
            yoffset,
            width,
            height,
            format,
            type,
            pixels
        )
        else if (pixels is ShortBuffer) GL11.glTexSubImage2D(
            target,
            level,
            xoffset,
            yoffset,
            width,
            height,
            format,
            type,
            pixels
        )
        else if (pixels is IntBuffer) GL11.glTexSubImage2D(
            target,
            level,
            xoffset,
            yoffset,
            width,
            height,
            format,
            type,
            pixels
        )
        else if (pixels is FloatBuffer) GL11.glTexSubImage2D(
            target,
            level,
            xoffset,
            yoffset,
            width,
            height,
            format,
            type,
            pixels
        )
        else if (pixels is DoubleBuffer) GL11.glTexSubImage2D(
            target,
            level,
            xoffset,
            yoffset,
            width,
            height,
            format,
            type,
            pixels
        )
        else throw RuntimeException(
            ("Can't use " + pixels?.javaClass?.getName()
                    + " with this method. Use ByteBuffer, ShortBuffer, IntBuffer, FloatBuffer or DoubleBuffer instead. Blame LWJGL")
        )
    }

    actual fun glUniform1f(location: Int, x: Float) {
        JL20.glUniform1f(location, x)
    }

    actual fun glUniform1i(location: Int, x: Int) {
        JL20.glUniform1i(location, x)
    }

    actual fun glUniform2f(location: Int, x: Float, y: Float) {
        JL20.glUniform2f(location, x, y)
    }

    actual fun glUniform2i(location: Int, x: Int, y: Int) {
        JL20.glUniform2i(location, x, y)
    }

    actual fun glUniform3f(location: Int, x: Float, y: Float, z: Float) {
        JL20.glUniform3f(location, x, y, z)
    }

    actual fun glUniform3i(location: Int, x: Int, y: Int, z: Int) {
        JL20.glUniform3i(location, x, y, z)
    }

    actual fun glUniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) {
        JL20.glUniform4f(location, x, y, z, w)
    }

    actual fun glUniform4i(location: Int, x: Int, y: Int, z: Int, w: Int) {
        JL20.glUniform4i(location, x, y, z, w)
    }

    actual fun glUseProgram(program: Int) {
        JL20.glUseProgram(program)
    }

    actual fun glValidateProgram(program: Int) {
        JL20.glValidateProgram(program)
    }

    actual fun glVertexAttrib1f(indx: Int, x: Float) {
        JL20.glVertexAttrib1f(indx, x)
    }


    actual fun glVertexAttrib2f(indx: Int, x: Float, y: Float) {
        JL20.glVertexAttrib2f(indx, x, y)
    }

    actual fun glVertexAttrib3f(indx: Int, x: Float, y: Float, z: Float) {
        JL20.glVertexAttrib3f(indx, x, y, z)
    }


    actual fun glVertexAttrib4f(indx: Int, x: Float, y: Float, z: Float, w: Float) {
        JL20.glVertexAttrib4f(indx, x, y, z, w)
    }


    actual fun glVertexAttribPointer(
        indx: Int,
        size: Int,
        type: Int,
        normalized: Boolean,
        stride: Int,
        ptr: Buffer?
    ) {
        if (ptr is ByteBuffer) {
            if (type == GLConstants.GL_BYTE) JL20.glVertexAttribPointer(
                indx,
                size,
                type,
                normalized,
                stride,
                ptr
            )
            else if (type == GLConstants.GL_UNSIGNED_BYTE) JL20.glVertexAttribPointer(
                indx,
                size,
                type,
                normalized,
                stride,
                ptr
            )
            else if (type == GLConstants.GL_SHORT) JL20.glVertexAttribPointer(
                indx,
                size,
                type,
                normalized,
                stride,
                ptr
            )
            else if (type == GLConstants.GL_UNSIGNED_SHORT) JL20.glVertexAttribPointer(
                indx,
                size,
                type,
                normalized,
                stride,
                ptr
            )
            else if (type == GLConstants.GL_FLOAT) JL20.glVertexAttribPointer(
                indx,
                size,
                type,
                normalized,
                stride,
                ptr
            )
            else throw RuntimeException(
                ("Can't use " + ptr.javaClass.getName() + " with type " + type
                        + " with this method. Use ByteBuffer and one of GL_BYTE, GL_UNSIGNED_BYTE, GL_SHORT, GL_UNSIGNED_SHORT or GL_FLOAT for type. Blame LWJGL")
            )
        } else if (ptr is FloatBuffer) {
            if (type == GLConstants.GL_FLOAT) JL20.glVertexAttribPointer(
                indx,
                size,
                type,
                normalized,
                stride,
                buffer
            )
            else throw RuntimeException(
                "Can't use " + buffer?.javaClass?.getName() + " with type " + type + " with this method."
            )
        } else throw RuntimeException(
            "Can't use " + buffer?.javaClass?.getName() + " with this method. Use ByteBuffer instead. Blame LWJGL"
        )
    }

    actual fun glViewport(x: Int, y: Int, width: Int, height: Int) {
        GL11.glViewport(x, y, width, height)
    }

    actual fun glDrawElements(mode: Int, count: Int, type: Int, indices: Int) {
        GL11.glDrawElements(mode, count, type, indices.toLong())
    }

    actual fun glVertexAttribPointer(
        indx: Int,
        size: Int,
        type: Int,
        normalized: Boolean,
        stride: Int,
        ptr: Int
    ) {
        JL20.glVertexAttribPointer(indx, size, type, normalized, stride, ptr.toLong())
    }

    actual fun glBlendFunc(sfactor: Int, dfactor: Int) {
        GL11.glBlendFunc(sfactor, dfactor)
    }

    actual fun glBlendFuncSeparate(srcRGB: Int, dstRGB: Int, srcAlpha: Int, dstAlpha: Int) {
        GL14.glBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha)
    }

    actual fun glGetShaderi(shader: Int, pname: Int): Int {
        return JL20.glGetShaderi(shader, pname)
    }
}
