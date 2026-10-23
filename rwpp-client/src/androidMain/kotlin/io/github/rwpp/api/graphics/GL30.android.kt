/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE
 */
package io.github.rwpp.api.graphics

import android.opengl.GLES30
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

actual object GL30 {
    actual fun glReadBuffer(mode: Int) {
        GLES30.glReadBuffer(mode)
    }

    actual fun glDrawRangeElements(mode: Int, start: Int, end: Int, count: Int, type: Int, indices: Buffer?) {
        GLES30.glDrawRangeElements(mode, start, end, count, type, indices)
    }

    actual fun glDrawRangeElements(mode: Int, start: Int, end: Int, count: Int, type: Int, offset: Int) {
        GLES30.glDrawRangeElements(mode, start, end, count, type, offset)
    }

    actual fun glTexImage3D(target: Int, level: Int, internalformat: Int, width: Int, height: Int, depth: Int, border: Int, format: Int, type: Int, pixels: Buffer?) {
        GLES30.glTexImage3D(target, level, internalformat, width, height, depth, border, format, type, pixels)
    }

    actual fun glTexImage3D(target: Int, level: Int, internalformat: Int, width: Int, height: Int, depth: Int, border: Int, format: Int, type: Int, offset: Int) {
        GLES30.glTexImage3D(target, level, internalformat, width, height, depth, border, format, type, offset)
    }

    actual fun glTexSubImage3D(target: Int, level: Int, xoffset: Int, yoffset: Int, zoffset: Int, width: Int, height: Int, depth: Int, format: Int, type: Int, pixels: Buffer?) {
        GLES30.glTexSubImage3D(target, level, xoffset, yoffset, zoffset, width, height, depth, format, type, pixels)
    }

    actual fun glTexSubImage3D(target: Int, level: Int, xoffset: Int, yoffset: Int, zoffset: Int, width: Int, height: Int, depth: Int, format: Int, type: Int, offset: Int) {
        GLES30.glTexSubImage3D(target, level, xoffset, yoffset, zoffset, width, height, depth, format, type, offset)
    }

    actual fun glCopyTexSubImage3D(target: Int, level: Int, xoffset: Int, yoffset: Int, zoffset: Int, x: Int, y: Int, width: Int, height: Int) {
        GLES30.glCopyTexSubImage3D(target, level, xoffset, yoffset, zoffset, x, y, width, height)
    }

    actual fun glGenQueries(n: Int, ids: IntBuffer?) {
        GLES30.glGenQueries(n, ids)
    }

    actual fun glDeleteQueries(n: Int, ids: IntBuffer?) {
        GLES30.glDeleteQueries(n, ids)
    }

    actual fun glIsQuery(id: Int): Boolean {
        return GLES30.glIsQuery(id)
    }

    actual fun glBeginQuery(target: Int, id: Int) {
        GLES30.glBeginQuery(target, id)
    }

    actual fun glEndQuery(target: Int) {
        GLES30.glEndQuery(target)
    }

    actual fun glGetQueryiv(target: Int, pname: Int, params: IntBuffer?) {
        GLES30.glGetQueryiv(target, pname, params)
    }

    actual fun glGetQueryObjectuiv(id: Int, pname: Int, params: IntBuffer?) {
        GLES30.glGetQueryObjectuiv(id, pname, params)
    }

    actual fun glUnmapBuffer(target: Int): Boolean {
        return GLES30.glUnmapBuffer(target)
    }

    actual fun glGetBufferPointerv(target: Int, pname: Int): Buffer? {
        return GLES30.glGetBufferPointerv(target, pname)
    }

    actual fun glDrawBuffers(n: Int, bufs: IntBuffer?) {
        GLES30.glDrawBuffers(n, bufs)
    }

    actual fun glUniformMatrix2x3fv(location: Int, count: Int, transpose: Boolean, value: FloatBuffer?) {
        GLES30.glUniformMatrix2x3fv(location, count, transpose, value)
    }

    actual fun glUniformMatrix3x2fv(location: Int, count: Int, transpose: Boolean, value: FloatBuffer?) {
        GLES30.glUniformMatrix3x2fv(location, count, transpose, value)
    }

    actual fun glUniformMatrix2x4fv(location: Int, count: Int, transpose: Boolean, value: FloatBuffer?) {
        GLES30.glUniformMatrix2x4fv(location, count, transpose, value)
    }

    actual fun glUniformMatrix4x2fv(location: Int, count: Int, transpose: Boolean, value: FloatBuffer?) {
        GLES30.glUniformMatrix4x2fv(location, count, transpose, value)
    }

    actual fun glUniformMatrix3x4fv(location: Int, count: Int, transpose: Boolean, value: FloatBuffer?) {
        GLES30.glUniformMatrix3x4fv(location, count, transpose, value)
    }

    actual fun glUniformMatrix4x3fv(location: Int, count: Int, transpose: Boolean, value: FloatBuffer?) {
        GLES30.glUniformMatrix4x3fv(location, count, transpose, value)
    }

    actual fun glBlitFramebuffer(srcX0: Int, srcY0: Int, srcX1: Int, srcY1: Int, dstX0: Int, dstY0: Int, dstX1: Int, dstY1: Int, mask: Int, filter: Int) {
        GLES30.glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter)
    }

    actual fun glRenderbufferStorageMultisample(target: Int, samples: Int, internalformat: Int, width: Int, height: Int) {
        GLES30.glRenderbufferStorageMultisample(target, samples, internalformat, width, height)
    }

    actual fun glFramebufferTextureLayer(target: Int, attachment: Int, texture: Int, level: Int, layer: Int) {
        GLES30.glFramebufferTextureLayer(target, attachment, texture, level, layer)
    }

    actual fun glFlushMappedBufferRange(target: Int, offset: Int, length: Int) {
        GLES30.glFlushMappedBufferRange(target, offset, length)
    }

    actual fun glBindVertexArray(array: Int) {
        GLES30.glBindVertexArray(array)
    }

    actual fun glDeleteVertexArrays(n: Int, arrays: IntBuffer?) {
        GLES30.glDeleteVertexArrays(n, arrays)
    }

    actual fun glGenVertexArrays(n: Int, arrays: IntBuffer?) {
        GLES30.glGenVertexArrays(n, arrays)
    }

    actual fun glIsVertexArray(array: Int): Boolean {
        return GLES30.glIsVertexArray(array)
    }

    actual fun glBeginTransformFeedback(primitiveMode: Int) {
        GLES30.glBeginTransformFeedback(primitiveMode)
    }

    actual fun glEndTransformFeedback() {
        GLES30.glEndTransformFeedback()
    }

    actual fun glBindBufferRange(target: Int, index: Int, buffer: Int, offset: Int, size: Int) {
        GLES30.glBindBufferRange(target, index, buffer, offset, size)
    }

    actual fun glBindBufferBase(target: Int, index: Int, buffer: Int) {
        GLES30.glBindBufferBase(target, index, buffer)
    }

    actual fun glTransformFeedbackVaryings(program: Int, varyings: Array<String?>?, bufferMode: Int) {
        GLES30.glTransformFeedbackVaryings(program, varyings, bufferMode)
    }

    actual fun glVertexAttribIPointer(index: Int, size: Int, type: Int, stride: Int, offset: Int) {
        GLES30.glVertexAttribIPointer(index, size, type, stride, offset)
    }

    actual fun glGetVertexAttribIiv(index: Int, pname: Int, params: IntBuffer?) {
        GLES30.glGetVertexAttribIiv(index, pname, params)
    }

    actual fun glGetVertexAttribIuiv(index: Int, pname: Int, params: IntBuffer?) {
        GLES30.glGetVertexAttribIuiv(index, pname, params)
    }

    actual fun glVertexAttribI4i(index: Int, x: Int, y: Int, z: Int, w: Int) {
        GLES30.glVertexAttribI4i(index, x, y, z, w)
    }

    actual fun glVertexAttribI4ui(index: Int, x: Int, y: Int, z: Int, w: Int) {
        GLES30.glVertexAttribI4ui(index, x, y, z, w)
    }

    actual fun glGetUniformuiv(program: Int, location: Int, params: IntBuffer?) {
        GLES30.glGetUniformuiv(program, location, params)
    }

    actual fun glGetFragDataLocation(program: Int, name: String?): Int {
        return GLES30.glGetFragDataLocation(program, name)
    }

    actual fun glUniform1uiv(location: Int, count: Int, value: IntBuffer?) {
        GLES30.glUniform1uiv(location, count, value)
    }

    actual fun glUniform3uiv(location: Int, count: Int, value: IntBuffer?) {
        GLES30.glUniform3uiv(location, count, value)
    }

    actual fun glUniform4uiv(location: Int, count: Int, value: IntBuffer?) {
        GLES30.glUniform4uiv(location, count, value)
    }

    actual fun glClearBufferiv(buffer: Int, drawbuffer: Int, value: IntBuffer?) {
        GLES30.glClearBufferiv(buffer, drawbuffer, value)
    }

    actual fun glClearBufferuiv(buffer: Int, drawbuffer: Int, value: IntBuffer?) {
        GLES30.glClearBufferuiv(buffer, drawbuffer, value)
    }

    actual fun glClearBufferfv(buffer: Int, drawbuffer: Int, value: FloatBuffer?) {
        GLES30.glClearBufferfv(buffer, drawbuffer, value)
    }

    actual fun glClearBufferfi(buffer: Int, drawbuffer: Int, depth: Float, stencil: Int) {
        GLES30.glClearBufferfi(buffer, drawbuffer, depth, stencil)
    }

    actual fun glGetStringi(name: Int, index: Int): String? {
        return GLES30.glGetStringi(name, index)
    }

    actual fun glCopyBufferSubData(readTarget: Int, writeTarget: Int, readOffset: Int, writeOffset: Int, size: Int) {
        GLES30.glCopyBufferSubData(readTarget, writeTarget, readOffset, writeOffset, size)
    }

    actual fun glGetUniformIndices(program: Int, uniformNames: Array<String?>?, uniformIndices: IntBuffer?) {
        GLES30.glGetUniformIndices(program, uniformNames, uniformIndices)
    }

    actual fun glGetActiveUniformsiv(program: Int, uniformCount: Int, uniformIndices: IntBuffer?, pname: Int, params: IntBuffer?) {
        GLES30.glGetActiveUniformsiv(program, uniformCount, uniformIndices, pname, params)
    }

    actual fun glGetUniformBlockIndex(program: Int, uniformBlockName: String?): Int {
        return GLES30.glGetUniformBlockIndex(program, uniformBlockName)
    }

    actual fun glGetActiveUniformBlockiv(program: Int, uniformBlockIndex: Int, pname: Int, params: IntBuffer?) {
        GLES30.glGetActiveUniformBlockiv(program, uniformBlockIndex, pname, params)
    }

    actual fun glGetActiveUniformBlockName(program: Int, uniformBlockIndex: Int, length: Buffer?, uniformBlockName: Buffer?) {
        GLES30.glGetActiveUniformBlockName(program, uniformBlockIndex, length as IntBuffer?, uniformBlockName as ByteBuffer?)
    }

    actual fun glUniformBlockBinding(program: Int, uniformBlockIndex: Int, uniformBlockBinding: Int) {
        GLES30.glUniformBlockBinding(program, uniformBlockIndex, uniformBlockBinding)
    }

    actual fun glDrawArraysInstanced(mode: Int, first: Int, count: Int, instanceCount: Int) {
        GLES30.glDrawArraysInstanced(mode, first, count, instanceCount)
    }

    actual fun glDrawElementsInstanced(mode: Int, count: Int, type: Int, indicesOffset: Int, instanceCount: Int) {
        GLES30.glDrawElementsInstanced(mode, count, type, indicesOffset, instanceCount)
    }

    actual fun glGetInteger64v(pname: Int, params: LongBuffer?) {
        GLES30.glGetInteger64v(pname, params)
    }

    actual fun glGetBufferParameteri64v(target: Int, pname: Int, params: LongBuffer?) {
        GLES30.glGetBufferParameteri64v(target, pname, params)
    }

    actual fun glGenSamplers(count: Int, samplers: IntBuffer?) {
        GLES30.glGenSamplers(count, samplers)
    }

    actual fun glDeleteSamplers(count: Int, samplers: IntBuffer?) {
        GLES30.glDeleteSamplers(count, samplers)
    }

    actual fun glIsSampler(sampler: Int): Boolean {
        return GLES30.glIsSampler(sampler)
    }

    actual fun glBindSampler(unit: Int, sampler: Int) {
        GLES30.glBindSampler(unit, sampler)
    }

    actual fun glSamplerParameteri(sampler: Int, pname: Int, param: Int) {
        GLES30.glSamplerParameteri(sampler, pname, param)
    }

    actual fun glSamplerParameteriv(sampler: Int, pname: Int, param: IntBuffer?) {
        GLES30.glSamplerParameteriv(sampler, pname, param)
    }

    actual fun glSamplerParameterf(sampler: Int, pname: Int, param: Float) {
        GLES30.glSamplerParameterf(sampler, pname, param)
    }

    actual fun glSamplerParameterfv(sampler: Int, pname: Int, param: FloatBuffer?) {
        GLES30.glSamplerParameterfv(sampler, pname, param)
    }

    actual fun glGetSamplerParameteriv(sampler: Int, pname: Int, params: IntBuffer?) {
        GLES30.glGetSamplerParameteriv(sampler, pname, params)
    }

    actual fun glGetSamplerParameterfv(sampler: Int, pname: Int, params: FloatBuffer?) {
        GLES30.glGetSamplerParameterfv(sampler, pname, params)
    }

    actual fun glVertexAttribDivisor(index: Int, divisor: Int) {
        GLES30.glVertexAttribDivisor(index, divisor)
    }

    actual fun glBindTransformFeedback(target: Int, id: Int) {
        GLES30.glBindTransformFeedback(target, id)
    }

    actual fun glDeleteTransformFeedbacks(n: Int, ids: IntBuffer?) {
        GLES30.glDeleteTransformFeedbacks(n, ids)
    }

    actual fun glGenTransformFeedbacks(n: Int, ids: IntBuffer?) {
        GLES30.glGenTransformFeedbacks(n, ids)
    }

    actual fun glIsTransformFeedback(id: Int): Boolean {
        return GLES30.glIsTransformFeedback(id)
    }

    actual fun glPauseTransformFeedback() {
        GLES30.glPauseTransformFeedback()
    }

    actual fun glResumeTransformFeedback() {
        GLES30.glResumeTransformFeedback()
    }

    actual fun glProgramParameteri(program: Int, pname: Int, value: Int) {
        GLES30.glProgramParameteri(program, pname, value)
    }

    actual fun glInvalidateFramebuffer(target: Int, numAttachments: Int, attachments: IntBuffer?) {
        GLES30.glInvalidateFramebuffer(target, numAttachments, attachments)
    }

    actual fun glInvalidateSubFramebuffer(target: Int, numAttachments: Int, attachments: IntBuffer?, x: Int, y: Int, width: Int, height: Int) {
        GLES30.glInvalidateSubFramebuffer(target, numAttachments, attachments, x, y, width, height)
    }
}
