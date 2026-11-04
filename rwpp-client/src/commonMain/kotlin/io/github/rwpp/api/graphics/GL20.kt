/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */
package io.github.rwpp.api.graphics

import java.nio.Buffer
import java.nio.IntBuffer

/**
 * Interface wrapping all the methods of OpenGL ES 2.0
 * @author mzechner
 */
@Suppress("unused")
expect object GL20 {
    fun glActiveTexture(texture: Int)
    fun glBindTexture(target: Int, texture: Int)
    fun glBlendFunc(sfactor: Int, dfactor: Int)
    fun glClear(mask: Int)
    fun glClearColor(red: Float, green: Float, blue: Float, alpha: Float)
    fun glClearDepthf(depth: Float)
    fun glClearStencil(s: Int)
    fun glColorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean)
    fun glCompressedTexImage2D(
        target: Int,
        level: Int,
        internalformat: Int,
        width: Int,
        height: Int,
        border: Int,
        imageSize: Int,
        data: Buffer?
    )

    fun glCompressedTexSubImage2D(
        target: Int,
        level: Int,
        xoffset: Int,
        yoffset: Int,
        width: Int,
        height: Int,
        format: Int,
        imageSize: Int,
        data: Buffer?
    )

    fun glCopyTexImage2D(
        target: Int,
        level: Int,
        internalformat: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        border: Int
    )

    fun glCopyTexSubImage2D(
        target: Int,
        level: Int,
        xoffset: Int,
        yoffset: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    )

    fun glCullFace(mode: Int)
    fun glDeleteTexture(texture: Int)
    fun glDepthFunc(func: Int)
    fun glDepthMask(flag: Boolean)
    fun glDepthRange(zNear: Double, zFar: Double)
    fun glDisable(cap: Int)
    fun glDrawArrays(mode: Int, first: Int, count: Int)
    fun glDrawElements(mode: Int, count: Int, type: Int, indices: Buffer?)
    fun glEnable(cap: Int)
    fun glFinish()
    fun glFlush()
    fun glFrontFace(mode: Int)
    fun glGenTexture(): Int
    fun glGetError(): Int
    fun glGetString(name: Int): String?
    fun glHint(target: Int, mode: Int)
    fun glLineWidth(width: Float)
    fun glPixelStorei(pname: Int, param: Int)
    fun glPolygonOffset(factor: Float, units: Float)
    fun glReadPixels(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        format: Int,
        type: Int,
        pixels: Buffer?
    )

    fun glScissor(x: Int, y: Int, width: Int, height: Int)
    fun glStencilFunc(func: Int, ref: Int, mask: Int)
    fun glStencilMask(mask: Int)
    fun glStencilOp(fail: Int, zfail: Int, zpass: Int)
    fun glTexImage2D(
        target: Int,
        level: Int,
        internalformat: Int,
        width: Int,
        height: Int,
        border: Int,
        format: Int,
        type: Int,
        pixels: Buffer?
    )

    fun glTexParameterf(target: Int, pname: Int, param: Float)
    fun glTexSubImage2D(
        target: Int,
        level: Int,
        xoffset: Int,
        yoffset: Int,
        width: Int,
        height: Int,
        format: Int,
        type: Int,
        pixels: Buffer?
    )

    fun glViewport(x: Int, y: Int, width: Int, height: Int)
    fun glAttachShader(program: Int, shader: Int)
    fun glBindAttribLocation(program: Int, index: Int, name: String?)
    fun glBindBuffer(target: Int, buffer: Int)
    fun glBindFramebuffer(target: Int, framebuffer: Int)
    fun glBindRenderbuffer(target: Int, renderbuffer: Int)
    fun glBlendColor(red: Float, green: Float, blue: Float, alpha: Float)
    fun glBlendEquation(mode: Int)
    fun glBlendEquationSeparate(modeRGB: Int, modeAlpha: Int)
    fun glBlendFuncSeparate(srcRGB: Int, dstRGB: Int, srcAlpha: Int, dstAlpha: Int)
    fun glBufferData(target: Int, size: Int, data: Buffer?, usage: Int)
    fun glBufferSubData(target: Int, offset: Int, size: Int, data: Buffer?)
    fun glCheckFramebufferStatus(target: Int): Int
    fun glCompileShader(shader: Int)
    fun glCreateProgram(): Int
    fun glCreateShader(type: Int): Int
    fun glDeleteBuffer(buffer: Int)
    fun glDeleteFramebuffer(framebuffer: Int)
    fun glDeleteProgram(program: Int)
    fun glDeleteRenderbuffer(renderbuffer: Int)
    fun glDeleteShader(shader: Int)
    fun glDetachShader(program: Int, shader: Int)
    fun glDisableVertexAttribArray(index: Int)
    fun glDrawElements(mode: Int, count: Int, type: Int, indices: Int)
    fun glEnableVertexAttribArray(index: Int)
    fun glFramebufferRenderbuffer(
        target: Int,
        attachment: Int,
        renderbuffertarget: Int,
        renderbuffer: Int
    )

    fun glFramebufferTexture2D(
        target: Int,
        attachment: Int,
        textarget: Int,
        texture: Int,
        level: Int
    )

    fun glGenBuffer(): Int
    fun glGenerateMipmap(target: Int)
    fun glGenFramebuffer(): Int
    fun glGenRenderbuffer(): Int
    fun glGetActiveAttrib(program: Int, index: Int, size: IntBuffer?, type: IntBuffer?): String?
    fun glGetActiveUniform(program: Int, index: Int, size: IntBuffer?, type: IntBuffer?): String?
    fun glGetAttribLocation(program: Int, name: String?): Int

    fun glGetProgramInfoLog(program: Int): String?
    fun glGetShaderInfoLog(shader: Int): String?
    fun glGetShaderPrecisionFormat(
        shadertype: Int,
        precisiontype: Int,
        range: IntBuffer?,
        precision: IntBuffer?
    )

    fun glGetUniformLocation(program: Int, name: String?): Int
    fun glIsBuffer(buffer: Int): Boolean
    fun glIsEnabled(cap: Int): Boolean
    fun glIsFramebuffer(framebuffer: Int): Boolean
    fun glIsProgram(program: Int): Boolean
    fun glIsRenderbuffer(renderbuffer: Int): Boolean
    fun glIsShader(shader: Int): Boolean
    fun glIsTexture(texture: Int): Boolean
    fun glLinkProgram(program: Int)
    fun glReleaseShaderCompiler()
    fun glRenderbufferStorage(target: Int, internalformat: Int, width: Int, height: Int)
    fun glSampleCoverage(value: Float, invert: Boolean)
    fun glShaderSource(shader: Int, string: String?)
    fun glStencilFuncSeparate(face: Int, func: Int, ref: Int, mask: Int)
    fun glStencilMaskSeparate(face: Int, mask: Int)
    fun glStencilOpSeparate(face: Int, fail: Int, zfail: Int, zpass: Int)
    fun glTexParameteri(target: Int, pname: Int, param: Int)
    fun glUniform1f(location: Int, x: Float)
    fun glUniform1i(location: Int, x: Int)
    fun glUniform2f(location: Int, x: Float, y: Float)
    fun glUniform2i(location: Int, x: Int, y: Int)
    fun glUniform3f(location: Int, x: Float, y: Float, z: Float)
    fun glUniform3i(location: Int, x: Int, y: Int, z: Int)
    fun glUniform4f(location: Int, x: Float, y: Float, z: Float, w: Float)
    fun glUniform4i(location: Int, x: Int, y: Int, z: Int, w: Int)

    fun glUseProgram(program: Int)
    fun glValidateProgram(program: Int)
    fun glVertexAttrib1f(indx: Int, x: Float)
    fun glVertexAttrib2f(indx: Int, x: Float, y: Float)
    fun glVertexAttrib3f(indx: Int, x: Float, y: Float, z: Float)
    fun glVertexAttrib4f(indx: Int, x: Float, y: Float, z: Float, w: Float)

    /**
     * In OpenGl core profiles (3.1+), passing a pointer to client memory is not valid.
     * In 3.0 and later, use the other version of this function instead, pass a zero-based
     * offset which references the buffer currently bound to GL_ARRAY_BUFFER.
     */
    fun glVertexAttribPointer(
        indx: Int,
        size: Int,
        type: Int,
        normalized: Boolean,
        stride: Int,
        ptr: Buffer?
    )

    fun glVertexAttribPointer(
        indx: Int,
        size: Int,
        type: Int,
        normalized: Boolean,
        stride: Int,
        ptr: Int
    )

    fun glGetShaderi(shader: Int, pname: Int): Int

}