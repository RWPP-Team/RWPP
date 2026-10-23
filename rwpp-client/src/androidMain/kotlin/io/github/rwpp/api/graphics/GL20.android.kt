/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.api.graphics

import android.opengl.GLES20
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.DoubleBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer


@Suppress("unused")
actual object GL20 {
    private val ints = intArrayOf(0)
    private val ints2: IntArray = intArrayOf(0)
    private val ints3: IntArray = intArrayOf(0)
    private val buffer = ByteArray(512)


    actual fun glActiveTexture(texture: Int) {
        GLES20.glActiveTexture(texture)
    }
    
    actual fun glAttachShader(program: Int, shader: Int) {
        GLES20.glAttachShader(program, shader)
    }

    actual fun glBindAttribLocation(program: Int, index: Int, name: String?) {
        GLES20.glBindAttribLocation(program, index, name)
    }

    actual fun glBindBuffer(target: Int, buffer: Int) {
        GLES20.glBindBuffer(target, buffer)
    }

    actual fun glBindFramebuffer(target: Int, framebuffer: Int) {
        GLES20.glBindFramebuffer(target, framebuffer)
    }

    actual fun glBindRenderbuffer(target: Int, renderbuffer: Int) {
        GLES20.glBindRenderbuffer(target, renderbuffer)
    }

    actual fun glBindTexture(target: Int, texture: Int) {
        GLES20.glBindTexture(target, texture)
    }

    actual fun glBlendColor(red: Float, green: Float, blue: Float, alpha: Float) {
        GLES20.glBlendColor(red, green, blue, alpha)
    }

    actual fun glBlendEquation(mode: Int) {
        GLES20.glBlendEquation(mode)
    }

    actual fun glBlendEquationSeparate(modeRGB: Int, modeAlpha: Int) {
        GLES20.glBlendEquationSeparate(modeRGB, modeAlpha)
    }


    actual fun glBufferData(target: Int, size: Int, data: Buffer?, usage: Int) {
        GLES20.glBufferData(target, size, data, usage)
    }

    actual fun glBufferSubData(target: Int, offset: Int, size: Int, data: Buffer?) {
        GLES20.glBufferSubData(target, offset, size, data)
    }

    actual fun glCheckFramebufferStatus(target: Int): Int {
        return GLES20.glCheckFramebufferStatus(target)
    }

    actual fun glClear(mask: Int) {
        GLES20.glClear(mask)
    }

    actual fun glClearColor(red: Float, green: Float, blue: Float, alpha: Float) {
        GLES20.glClearColor(red, green, blue, alpha)
    }

    actual fun glClearDepthf(depth: Float) {
        GLES20.glClearDepthf(depth)
    }

    actual fun glClearStencil(s: Int) {
        GLES20.glClearStencil(s)
    }

    actual fun glColorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) {
        GLES20.glColorMask(red, green, blue, alpha)
    }

    actual fun glCompileShader(shader: Int) {
        GLES20.glCompileShader(shader)
    }

    actual fun glCompressedTexImage2D(
        target: Int, level: Int, internalformat: Int, width: Int, height: Int, border: Int,
        imageSize: Int, data: Buffer?
    ) {
        GLES20.glCompressedTexImage2D(target, level, internalformat, width, height, border, imageSize, data)
    }

    actual fun glCompressedTexSubImage2D(
        target: Int, level: Int, xoffset: Int, yoffset: Int, width: Int, height: Int, format: Int,
        imageSize: Int, data: Buffer?
    ) {
        GLES20.glCompressedTexSubImage2D(target, level, xoffset, yoffset, width, height, format, imageSize, data)
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
        GLES20.glCopyTexImage2D(target, level, internalformat, x, y, width, height, border)
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
        GLES20.glCopyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height)
    }

    actual fun glCreateProgram(): Int {
        return GLES20.glCreateProgram()
    }

    actual fun glCreateShader(type: Int): Int {
        return GLES20.glCreateShader(type)
    }

    actual fun glCullFace(mode: Int) {
        GLES20.glCullFace(mode)
    }

    actual fun glDeleteBuffer(buffer: Int) {
        ints[0] = buffer
        GLES20.glDeleteBuffers(1, ints, 0)
    }

    actual fun glDeleteFramebuffer(framebuffer: Int) {
        ints[0] = framebuffer
        GLES20.glDeleteFramebuffers(1, ints, 0)
    }

    actual fun glDeleteProgram(program: Int) {
        GLES20.glDeleteProgram(program)
    }


    actual fun glDeleteRenderbuffer(renderbuffer: Int) {
        ints[0] = renderbuffer
        GLES20.glDeleteRenderbuffers(1, ints, 0)
    }

    actual fun glDeleteShader(shader: Int) {
        GLES20.glDeleteShader(shader)
    }


    actual fun glDeleteTexture(texture: Int) {
        ints[0] = texture
        GLES20.glDeleteTextures(1, ints, 0)
    }

    actual fun glDepthFunc(func: Int) {
        GLES20.glDepthFunc(func)
    }

    actual fun glDepthMask(flag: Boolean) {
        GLES20.glDepthMask(flag)
    }

    actual fun glDepthRange(zNear: Double, zFar: Double) {
        GLES20.glDepthRangef(zNear.toFloat(), zFar.toFloat())
    }

    actual fun glDetachShader(program: Int, shader: Int) {
        GLES20.glDetachShader(program, shader)
    }

    actual fun glDisable(cap: Int) {
        GLES20.glDisable(cap)
    }

    actual fun glDisableVertexAttribArray(index: Int) {
        GLES20.glDisableVertexAttribArray(index)
    }

    actual fun glDrawArrays(mode: Int, first: Int, count: Int) {
        GLES20.glDrawArrays(mode, first, count)
    }

    actual fun glDrawElements(mode: Int, count: Int, type: Int, indices: Buffer?) {
        GLES20.glDrawElements(mode, count, type, indices)
    }

    actual fun glEnable(cap: Int) {
        GLES20.glEnable(cap)
    }

    actual fun glEnableVertexAttribArray(index: Int) {
        GLES20.glEnableVertexAttribArray(index)
    }

    actual fun glFinish() {
        GLES20.glFinish()
    }

    actual fun glFlush() {
        GLES20.glFlush()
    }

    actual fun glFramebufferRenderbuffer(
        target: Int,
        attachment: Int,
        renderbuffertarget: Int,
        renderbuffer: Int
    ) {
        GLES20.glFramebufferRenderbuffer(
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
        GLES20.glFramebufferTexture2D(
            target,
            attachment,
            textarget,
            texture,
            level
        )
    }

    actual fun glFrontFace(mode: Int) {
        GLES20.glFrontFace(mode)
    }


    actual fun glGenBuffer(): Int {
        GLES20.glGenBuffers(1, ints, 0)
        return ints[0]
    }

    actual fun glGenFramebuffer(): Int {
        GLES20.glGenFramebuffers(1, ints, 0)
        return ints[0]
    }

    actual fun glGenRenderbuffer(): Int {
        GLES20.glGenRenderbuffers(1, ints, 0)
        return ints[0]
    }

    actual fun glGenTexture(): Int {
        GLES20.glGenTextures(1, ints, 0)
        return ints[0]
    }

    actual fun glGenerateMipmap(target: Int) {
        GLES20.glGenerateMipmap(target)
    }

    actual fun glGetActiveAttrib(program: Int, index: Int, size: IntBuffer?, type: IntBuffer?): String? {
        //length
        ints[0] = 0

        //size
        ints2[0] = size!!.get(0)

        //unitType
        ints3[0] = (type as IntBuffer).get(0)

        GLES20.glGetActiveAttrib(
            program,
            index,
            buffer.size,
            ints,
            0,
            ints2,
            0,
            ints3,
            0,
            buffer,
            0
        )
        return String(buffer, 0, ints[0])
    }
    actual fun glGetActiveUniform(program: Int, index: Int, size: IntBuffer?, type: IntBuffer?): String? {

        //length
        ints[0] = 0

        //size
        ints2[0] = size!!.get(0)

        //unitType
        ints3[0] = (type as IntBuffer).get(0)

        GLES20.glGetActiveUniform(
            program,
            index,
            buffer.size,
            ints,
            0,
            ints2,
            0,
            ints3,
            0,
            buffer,
            0
        )
        return String(buffer, 0, ints[0])
    }


    actual fun glGetAttribLocation(program: Int, name: String?): Int {
        return GLES20.glGetAttribLocation(program, name)
    }


    actual fun glGetError(): Int {
        return GLES20.glGetError()
    }


    actual fun glGetProgramInfoLog(program: Int): String? {
       return GLES20.glGetProgramInfoLog(program)
    }

    actual fun glGetShaderInfoLog(shader: Int): String? {
        return GLES20.glGetShaderInfoLog(shader)
    }

    actual fun glGetShaderPrecisionFormat(
        shadertype: Int,
        precisiontype: Int,
        range: IntBuffer?,
        precision: IntBuffer?
    ) {
        GLES20.glGetShaderPrecisionFormat(shadertype, precisiontype, range, precision)
    }


    actual fun glGetString(name: Int): String? {
        return GLES20.glGetString(name)
    }

    actual fun glGetUniformLocation(program: Int, name: String?): Int {
        return GLES20.glGetUniformLocation(program, name)
    }


    actual fun glHint(target: Int, mode: Int) {
        GLES20.glHint(target, mode)
    }

    actual fun glIsBuffer(buffer: Int): Boolean {
        return GLES20.glIsBuffer(buffer)
    }

    actual fun glIsEnabled(cap: Int): Boolean {
        return GLES20.glIsEnabled(cap)
    }

    actual fun glIsFramebuffer(framebuffer: Int): Boolean {
        return GLES20.glIsFramebuffer(framebuffer)
    }

    actual fun glIsProgram(program: Int): Boolean {
        return GLES20.glIsProgram(program)
    }

    actual fun glIsRenderbuffer(renderbuffer: Int): Boolean {
        return GLES20.glIsRenderbuffer(renderbuffer)
    }

    actual fun glIsShader(shader: Int): Boolean {
        return GLES20.glIsShader(shader)
    }

    actual fun glIsTexture(texture: Int): Boolean {
        return GLES20.glIsTexture(texture)
    }

    actual fun glLineWidth(width: Float) {
        GLES20.glLineWidth(width)
    }

    actual fun glLinkProgram(program: Int) {
        GLES20.glLinkProgram(program)
    }

    actual fun glPixelStorei(pname: Int, param: Int) {
        GLES20.glPixelStorei(pname, param)
    }

    actual fun glPolygonOffset(factor: Float, units: Float) {
        GLES20.glPolygonOffset(factor, units)
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
        if (pixels is ByteBuffer) GLES20.glReadPixels(x, y, width, height, format, type, pixels)
        else if (pixels is ShortBuffer) GLES20.glReadPixels(x, y, width, height, format, type, pixels)
        else if (pixels is IntBuffer) GLES20.glReadPixels(x, y, width, height, format, type, pixels)
        else if (pixels is FloatBuffer) GLES20.glReadPixels(x, y, width, height, format, type, pixels)
        else throw RuntimeException(
            ("Can't use " + pixels?.javaClass?.getName()
                    + " with this method. Use ByteBuffer, ShortBuffer, IntBuffer or FloatBuffer instead. Blame LWJGL")
        )
    }

    actual fun glReleaseShaderCompiler() {
        GLES20.glReleaseShaderCompiler()
    }

    actual fun glRenderbufferStorage(target: Int, internalformat: Int, width: Int, height: Int) {
        GLES20.glRenderbufferStorage(target, internalformat, width, height)
    }

    actual fun glSampleCoverage(value: Float, invert: Boolean) {
        GLES20.glSampleCoverage(value, invert)
    }

    actual fun glScissor(x: Int, y: Int, width: Int, height: Int) {
        GLES20.glScissor(x, y, width, height)
    }

    actual fun glShaderSource(shader: Int, string: String?) {
        GLES20.glShaderSource(shader, string)
    }

    actual fun glStencilFunc(func: Int, ref: Int, mask: Int) {
        GLES20.glStencilFunc(func, ref, mask)
    }

    actual fun glStencilFuncSeparate(face: Int, func: Int, ref: Int, mask: Int) {
        GLES20.glStencilFuncSeparate(face,func, ref, mask)
    }

    actual fun glStencilMask(mask: Int) {
        GLES20.glStencilMask(mask)
    }

    actual fun glStencilMaskSeparate(face: Int, mask: Int) {
        GLES20.glStencilMaskSeparate(face, mask)
    }

    actual fun glStencilOp(fail: Int, zfail: Int, zpass: Int) {
        GLES20.glStencilOp(fail, zfail, zpass)
    }

    actual fun glStencilOpSeparate(face: Int, fail: Int, zfail: Int, zpass: Int) {
        GLES20.glStencilOpSeparate(face, fail, zfail, zpass)
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
        if (pixels == null) GLES20.glTexImage2D(
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
        else if (pixels is ByteBuffer) GLES20.glTexImage2D(
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
        else if (pixels is ShortBuffer) GLES20.glTexImage2D(
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
        else if (pixels is IntBuffer) GLES20.glTexImage2D(
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
        else if (pixels is FloatBuffer) GLES20.glTexImage2D(
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
        else if (pixels is DoubleBuffer) GLES20.glTexImage2D(
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
        GLES20.glTexParameterf(target, pname, param)
    }

    actual fun glTexParameteri(target: Int, pname: Int, param: Int) {
        GLES20.glTexParameteri(target, pname, param)
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
        if (pixels is ByteBuffer) GLES20.glTexSubImage2D(
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
        else if (pixels is ShortBuffer) GLES20.glTexSubImage2D(
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
        else if (pixels is IntBuffer) GLES20.glTexSubImage2D(
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
        else if (pixels is FloatBuffer) GLES20.glTexSubImage2D(
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
        else if (pixels is DoubleBuffer) GLES20.glTexSubImage2D(
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
        GLES20.glUniform1f(location, x)
    }

    actual fun glUniform1i(location: Int, x: Int) {
        GLES20.glUniform1i(location, x)
    }

    actual fun glUniform2f(location: Int, x: Float, y: Float) {
        GLES20.glUniform2f(location, x, y)
    }

    actual fun glUniform2i(location: Int, x: Int, y: Int) {
        GLES20.glUniform2i(location, x, y)
    }

    actual fun glUniform3f(location: Int, x: Float, y: Float, z: Float) {
        GLES20.glUniform3f(location, x, y, z)
    }

    actual fun glUniform3i(location: Int, x: Int, y: Int, z: Int) {
        GLES20.glUniform3i(location, x, y, z)
    }

    actual fun glUniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) {
        GLES20.glUniform4f(location, x, y, z, w)
    }

    actual fun glUniform4i(location: Int, x: Int, y: Int, z: Int, w: Int) {
        GLES20.glUniform4i(location, x, y, z, w)
    }

    actual fun glUseProgram(program: Int) {
        GLES20.glUseProgram(program)
    }

    actual fun glValidateProgram(program: Int) {
        GLES20.glValidateProgram(program)
    }

    actual fun glVertexAttrib1f(indx: Int, x: Float) {
        GLES20.glVertexAttrib1f(indx, x)
    }


    actual fun glVertexAttrib2f(indx: Int, x: Float, y: Float) {
        GLES20.glVertexAttrib2f(indx, x, y)
    }

    actual fun glVertexAttrib3f(indx: Int, x: Float, y: Float, z: Float) {
        GLES20.glVertexAttrib3f(indx, x, y, z)
    }


    actual fun glVertexAttrib4f(indx: Int, x: Float, y: Float, z: Float, w: Float) {
        GLES20.glVertexAttrib4f(indx, x, y, z, w)
    }


    actual fun glVertexAttribPointer(
        indx: Int,
        size: Int,
        type: Int,
        normalized: Boolean,
        stride: Int,
        ptr: Buffer?
    ) {
        GLES20.glVertexAttribPointer(indx, size, type, normalized, stride, ptr)
    }

    actual fun glViewport(x: Int, y: Int, width: Int, height: Int) {
        GLES20.glViewport(x, y, width, height)
    }
    actual fun glDrawElements(mode: Int, count: Int, type: Int, indices: Int) {
        GLES20.glDrawElements(mode, count, type, indices)
    }
    actual fun glVertexAttribPointer(
        indx: Int,
        size: Int,
        type: Int,
        normalized: Boolean,
        stride: Int,
        ptr: Int
    ) {
        GLES20.glVertexAttribPointer(indx, size, type, normalized, stride, ptr)
    }

    actual fun glBlendFunc(sfactor: Int, dfactor: Int) {
        GLES20.glBlendFunc(sfactor, dfactor)
    }

    actual fun glBlendFuncSeparate(srcRGB: Int, dstRGB: Int, srcAlpha: Int, dstAlpha: Int) {
        GLES20.glBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha)
    }

    private val shaderBuffer = ByteBuffer.allocateDirect(4)
        .order(ByteOrder.nativeOrder())
        .asIntBuffer()

    actual fun glGetShaderi(shader: Int, pname: Int): Int {
        GLES20.glGetShaderiv(shader, pname, shaderBuffer)
        return shaderBuffer.get(0)
    }
}
