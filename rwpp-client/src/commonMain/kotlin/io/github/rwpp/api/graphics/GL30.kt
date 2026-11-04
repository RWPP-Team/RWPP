/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */
package io.github.rwpp.api.graphics

import java.nio.Buffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

@Suppress("unused")
expect object GL30 {
    fun glReadBuffer(mode: Int)
    fun glDrawRangeElements(
        mode: Int,
        start: Int,
        end: Int,
        count: Int,
        type: Int,
        indices: Buffer?
    )

    fun glDrawRangeElements(mode: Int, start: Int, end: Int, count: Int, type: Int, offset: Int)
    fun glTexImage3D(
        target: Int,
        level: Int,
        internalformat: Int,
        width: Int,
        height: Int,
        depth: Int,
        border: Int,
        format: Int,
        type: Int,
        pixels: Buffer?
    )

    fun glTexImage3D(
        target: Int,
        level: Int,
        internalformat: Int,
        width: Int,
        height: Int,
        depth: Int,
        border: Int,
        format: Int,
        type: Int,
        offset: Int
    )

    fun glTexSubImage3D(
        target: Int,
        level: Int,
        xoffset: Int,
        yoffset: Int,
        zoffset: Int,
        width: Int,
        height: Int,
        depth: Int,
        format: Int,
        type: Int,
        pixels: Buffer?
    )

    fun glTexSubImage3D(
        target: Int,
        level: Int,
        xoffset: Int,
        yoffset: Int,
        zoffset: Int,
        width: Int,
        height: Int,
        depth: Int,
        format: Int,
        type: Int,
        offset: Int
    )

    fun glCopyTexSubImage3D(
        target: Int,
        level: Int,
        xoffset: Int,
        yoffset: Int,
        zoffset: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    )

    fun glGenQueries(n: Int, ids: IntBuffer?)
    fun glDeleteQueries(n: Int, ids: IntBuffer?)
    fun glIsQuery(id: Int): Boolean
    fun glBeginQuery(target: Int, id: Int)
    fun glEndQuery(target: Int)
    fun glGetQueryiv(target: Int, pname: Int, params: IntBuffer?)
    fun glGetQueryObjectuiv(id: Int, pname: Int, params: IntBuffer?)
    fun glUnmapBuffer(target: Int): Boolean
    fun glGetBufferPointerv(target: Int, pname: Int): Buffer?
    fun glDrawBuffers(n: Int, bufs: IntBuffer?)
    fun glUniformMatrix2x3fv(location: Int, count: Int, transpose: Boolean, value: FloatBuffer?)
    fun glUniformMatrix3x2fv(location: Int, count: Int, transpose: Boolean, value: FloatBuffer?)
    fun glUniformMatrix2x4fv(location: Int, count: Int, transpose: Boolean, value: FloatBuffer?)
    fun glUniformMatrix4x2fv(location: Int, count: Int, transpose: Boolean, value: FloatBuffer?)
    fun glUniformMatrix3x4fv(location: Int, count: Int, transpose: Boolean, value: FloatBuffer?)
    fun glUniformMatrix4x3fv(location: Int, count: Int, transpose: Boolean, value: FloatBuffer?)
    fun glBlitFramebuffer(
        srcX0: Int,
        srcY0: Int,
        srcX1: Int,
        srcY1: Int,
        dstX0: Int,
        dstY0: Int,
        dstX1: Int,
        dstY1: Int,
        mask: Int,
        filter: Int
    )

    fun glRenderbufferStorageMultisample(
        target: Int,
        samples: Int,
        internalformat: Int,
        width: Int,
        height: Int
    )

    fun glFramebufferTextureLayer(
        target: Int,
        attachment: Int,
        texture: Int,
        level: Int,
        layer: Int
    )

    fun glFlushMappedBufferRange(target: Int, offset: Int, length: Int)
    fun glBindVertexArray(array: Int)
    fun glDeleteVertexArrays(n: Int, arrays: IntBuffer?)
    fun glGenVertexArrays(n: Int, arrays: IntBuffer?)
    fun glIsVertexArray(array: Int): Boolean
    fun glBeginTransformFeedback(primitiveMode: Int)
    fun glEndTransformFeedback()
    fun glBindBufferRange(target: Int, index: Int, buffer: Int, offset: Int, size: Int)
    fun glBindBufferBase(target: Int, index: Int, buffer: Int)
    fun glTransformFeedbackVaryings(program: Int, varyings: Array<String?>?, bufferMode: Int)
    fun glVertexAttribIPointer(index: Int, size: Int, type: Int, stride: Int, offset: Int)
    fun glGetVertexAttribIiv(index: Int, pname: Int, params: IntBuffer?)
    fun glGetVertexAttribIuiv(index: Int, pname: Int, params: IntBuffer?)
    fun glVertexAttribI4i(index: Int, x: Int, y: Int, z: Int, w: Int)
    fun glVertexAttribI4ui(index: Int, x: Int, y: Int, z: Int, w: Int)
    fun glGetUniformuiv(program: Int, location: Int, params: IntBuffer?)
    fun glGetFragDataLocation(program: Int, name: String?): Int
    fun glUniform1uiv(location: Int, count: Int, value: IntBuffer?)
    fun glUniform3uiv(location: Int, count: Int, value: IntBuffer?)
    fun glUniform4uiv(location: Int, count: Int, value: IntBuffer?)
    fun glClearBufferiv(buffer: Int, drawbuffer: Int, value: IntBuffer?)
    fun glClearBufferuiv(buffer: Int, drawbuffer: Int, value: IntBuffer?)
    fun glClearBufferfv(buffer: Int, drawbuffer: Int, value: FloatBuffer?)
    fun glClearBufferfi(buffer: Int, drawbuffer: Int, depth: Float, stencil: Int)
    fun glGetStringi(name: Int, index: Int): String?
    fun glCopyBufferSubData(
        readTarget: Int,
        writeTarget: Int,
        readOffset: Int,
        writeOffset: Int,
        size: Int
    )

    fun glGetUniformIndices(program: Int, uniformNames: Array<String?>?, uniformIndices: IntBuffer?)
    fun glGetActiveUniformsiv(
        program: Int,
        uniformCount: Int,
        uniformIndices: IntBuffer?,
        pname: Int,
        params: IntBuffer?
    )

    fun glGetUniformBlockIndex(program: Int, uniformBlockName: String?): Int
    fun glGetActiveUniformBlockiv(
        program: Int,
        uniformBlockIndex: Int,
        pname: Int,
        params: IntBuffer?
    )

    fun glGetActiveUniformBlockName(
        program: Int,
        uniformBlockIndex: Int,
        length: Buffer?,
        uniformBlockName: Buffer?
    )

    fun glUniformBlockBinding(program: Int, uniformBlockIndex: Int, uniformBlockBinding: Int)
    fun glDrawArraysInstanced(mode: Int, first: Int, count: Int, instanceCount: Int)
    fun glDrawElementsInstanced(
        mode: Int,
        count: Int,
        type: Int,
        indicesOffset: Int,
        instanceCount: Int
    )

    fun glGetInteger64v(pname: Int, params: LongBuffer?)
    fun glGetBufferParameteri64v(target: Int, pname: Int, params: LongBuffer?)
    fun glGenSamplers(count: Int, samplers: IntBuffer?)
    fun glDeleteSamplers(count: Int, samplers: IntBuffer?)
    fun glIsSampler(sampler: Int): Boolean
    fun glBindSampler(unit: Int, sampler: Int)
    fun glSamplerParameteri(sampler: Int, pname: Int, param: Int)
    fun glSamplerParameteriv(sampler: Int, pname: Int, param: IntBuffer?)
    fun glSamplerParameterf(sampler: Int, pname: Int, param: Float)
    fun glSamplerParameterfv(sampler: Int, pname: Int, param: FloatBuffer?)
    fun glGetSamplerParameteriv(sampler: Int, pname: Int, params: IntBuffer?)
    fun glGetSamplerParameterfv(sampler: Int, pname: Int, params: FloatBuffer?)
    fun glVertexAttribDivisor(index: Int, divisor: Int)
    fun glBindTransformFeedback(target: Int, id: Int)
    fun glDeleteTransformFeedbacks(n: Int, ids: IntBuffer?)
    fun glGenTransformFeedbacks(n: Int, ids: IntBuffer?)
    fun glIsTransformFeedback(id: Int): Boolean
    fun glPauseTransformFeedback()
    fun glResumeTransformFeedback()
    fun glProgramParameteri(program: Int, pname: Int, value: Int)
    fun glInvalidateFramebuffer(target: Int, numAttachments: Int, attachments: IntBuffer?)
    fun glInvalidateSubFramebuffer(
        target: Int,
        numAttachments: Int,
        attachments: IntBuffer?,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    )
}