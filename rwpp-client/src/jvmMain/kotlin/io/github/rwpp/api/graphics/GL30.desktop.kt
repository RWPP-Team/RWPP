/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */
package io.github.rwpp.api.graphics

import org.lwjgl.opengl.*
import org.lwjgl.opengl.GL20
import java.nio.*
import org.lwjgl.opengl.GL30 as JGL30

actual object GL30 {
    actual fun glReadBuffer(mode: Int) {
        GL11.glReadBuffer(mode)
    }

    actual fun glDrawRangeElements(mode: Int, start: Int, end: Int, count: Int, type: Int, indices: Buffer?) {
        when (indices) {
            is ByteBuffer -> when (type) {
                GL11.GL_UNSIGNED_BYTE -> GL12.glDrawRangeElements(mode, start, end, indices.limited(count))
                GL11.GL_UNSIGNED_SHORT -> GL12.glDrawRangeElements(
                    mode,
                    start,
                    end,
                    indices.duplicate().order(indices.order()).asShortBuffer().limited(count)
                )

                GL11.GL_UNSIGNED_INT -> GL12.glDrawRangeElements(
                    mode,
                    start,
                    end,
                    indices.duplicate().order(indices.order()).asIntBuffer().limited(count)
                )

                else -> error("Unsupported element type: $type")
            }

            is ShortBuffer -> GL12.glDrawRangeElements(mode, start, end, indices.limited(count))
            is IntBuffer -> GL12.glDrawRangeElements(mode, start, end, indices.limited(count))
            null -> GL12.glDrawRangeElements(mode, start, end, count, type, 0L)
            else -> error("Indices must be a byte, short or int buffer")
        }
    }

    actual fun glDrawRangeElements(mode: Int, start: Int, end: Int, count: Int, type: Int, offset: Int) {
        GL12.glDrawRangeElements(mode, start, end, count, type, offset.toLong())
    }

    actual fun glTexImage3D(
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
    ) {
        when (pixels) {
            is ByteBuffer -> GL12.glTexImage3D(
                target,
                level,
                internalformat,
                width,
                height,
                depth,
                border,
                format,
                type,
                pixels
            )

            is ShortBuffer -> GL12.glTexImage3D(
                target,
                level,
                internalformat,
                width,
                height,
                depth,
                border,
                format,
                type,
                pixels
            )

            is IntBuffer -> GL12.glTexImage3D(
                target,
                level,
                internalformat,
                width,
                height,
                depth,
                border,
                format,
                type,
                pixels
            )

            is FloatBuffer -> GL12.glTexImage3D(
                target,
                level,
                internalformat,
                width,
                height,
                depth,
                border,
                format,
                type,
                pixels
            )

            is DoubleBuffer -> GL12.glTexImage3D(
                target,
                level,
                internalformat,
                width,
                height,
                depth,
                border,
                format,
                type,
                pixels
            )

            null -> GL12.glTexImage3D(
                target,
                level,
                internalformat,
                width,
                height,
                depth,
                border,
                format,
                type,
                null as ByteBuffer?
            )

            else -> error("Pixels must be a byte, short, int, float or double buffer")
        }
    }

    actual fun glTexImage3D(
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
    ) {
        GL12.glTexImage3D(target, level, internalformat, width, height, depth, border, format, type, offset.toLong())
    }

    actual fun glTexSubImage3D(
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
    ) {
        when (pixels) {
            is ByteBuffer -> GL12.glTexSubImage3D(
                target,
                level,
                xoffset,
                yoffset,
                zoffset,
                width,
                height,
                depth,
                format,
                type,
                pixels
            )

            is ShortBuffer -> GL12.glTexSubImage3D(
                target,
                level,
                xoffset,
                yoffset,
                zoffset,
                width,
                height,
                depth,
                format,
                type,
                pixels
            )

            is IntBuffer -> GL12.glTexSubImage3D(
                target,
                level,
                xoffset,
                yoffset,
                zoffset,
                width,
                height,
                depth,
                format,
                type,
                pixels
            )

            is FloatBuffer -> GL12.glTexSubImage3D(
                target,
                level,
                xoffset,
                yoffset,
                zoffset,
                width,
                height,
                depth,
                format,
                type,
                pixels
            )

            is DoubleBuffer -> GL12.glTexSubImage3D(
                target,
                level,
                xoffset,
                yoffset,
                zoffset,
                width,
                height,
                depth,
                format,
                type,
                pixels
            )

            null -> GL12.glTexSubImage3D(
                target,
                level,
                xoffset,
                yoffset,
                zoffset,
                width,
                height,
                depth,
                format,
                type,
                null as ByteBuffer?
            )

            else -> error("Pixels must be a byte, short, int, float or double buffer")
        }
    }

    actual fun glTexSubImage3D(
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
    ) {
        GL12.glTexSubImage3D(
            target,
            level,
            xoffset,
            yoffset,
            zoffset,
            width,
            height,
            depth,
            format,
            type,
            offset.toLong()
        )
    }

    actual fun glCopyTexSubImage3D(
        target: Int,
        level: Int,
        xoffset: Int,
        yoffset: Int,
        zoffset: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ) {
        GL12.glCopyTexSubImage3D(target, level, xoffset, yoffset, zoffset, x, y, width, height)
    }

    actual fun glGenQueries(n: Int, ids: IntBuffer?) {
        GL15.glGenQueries(ids.limited(n))
    }

    actual fun glDeleteQueries(n: Int, ids: IntBuffer?) {
        GL15.glDeleteQueries(ids.limited(n))
    }

    actual fun glIsQuery(id: Int): Boolean {
        return GL15.glIsQuery(id)
    }

    actual fun glBeginQuery(target: Int, id: Int) {
        GL15.glBeginQuery(target, id)
    }

    actual fun glEndQuery(target: Int) {
        GL15.glEndQuery(target)
    }

    actual fun glGetQueryiv(target: Int, pname: Int, params: IntBuffer?) {
        GL15.glGetQuery(target, pname, params)
    }

    actual fun glGetQueryObjectuiv(id: Int, pname: Int, params: IntBuffer?) {
        GL15.glGetQueryObjectu(id, pname, params)
    }

    actual fun glUnmapBuffer(target: Int): Boolean {
        return GL15.glUnmapBuffer(target)
    }

    actual fun glGetBufferPointerv(target: Int, pname: Int): Buffer? {
        return GL15.glGetBufferPointer(target, pname)
    }

    actual fun glDrawBuffers(n: Int, bufs: IntBuffer?) {
        GL20.glDrawBuffers(bufs.limited(n))
    }

    actual fun glUniformMatrix2x3fv(location: Int, count: Int, transpose: Boolean, value: FloatBuffer?) {
        GL21.glUniformMatrix2x3(location, transpose, value.limited(count * 6))
    }

    actual fun glUniformMatrix3x2fv(location: Int, count: Int, transpose: Boolean, value: FloatBuffer?) {
        GL21.glUniformMatrix3x2(location, transpose, value.limited(count * 6))
    }

    actual fun glUniformMatrix2x4fv(location: Int, count: Int, transpose: Boolean, value: FloatBuffer?) {
        GL21.glUniformMatrix2x4(location, transpose, value.limited(count * 8))
    }

    actual fun glUniformMatrix4x2fv(location: Int, count: Int, transpose: Boolean, value: FloatBuffer?) {
        GL21.glUniformMatrix4x2(location, transpose, value.limited(count * 8))
    }

    actual fun glUniformMatrix3x4fv(location: Int, count: Int, transpose: Boolean, value: FloatBuffer?) {
        GL21.glUniformMatrix3x4(location, transpose, value.limited(count * 12))
    }

    actual fun glUniformMatrix4x3fv(location: Int, count: Int, transpose: Boolean, value: FloatBuffer?) {
        GL21.glUniformMatrix4x3(location, transpose, value.limited(count * 12))
    }

    actual fun glBlitFramebuffer(
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
    ) {
        JGL30.glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter)
    }

    actual fun glRenderbufferStorageMultisample(
        target: Int,
        samples: Int,
        internalformat: Int,
        width: Int,
        height: Int
    ) {
        JGL30.glRenderbufferStorageMultisample(target, samples, internalformat, width, height)
    }

    actual fun glFramebufferTextureLayer(target: Int, attachment: Int, texture: Int, level: Int, layer: Int) {
        JGL30.glFramebufferTextureLayer(target, attachment, texture, level, layer)
    }

    actual fun glFlushMappedBufferRange(target: Int, offset: Int, length: Int) {
        JGL30.glFlushMappedBufferRange(target, offset.toLong(), length.toLong())
    }

    actual fun glBindVertexArray(array: Int) {
        JGL30.glBindVertexArray(array)
    }

    actual fun glDeleteVertexArrays(n: Int, arrays: IntBuffer?) {
        JGL30.glDeleteVertexArrays(arrays.limited(n))
    }

    actual fun glGenVertexArrays(n: Int, arrays: IntBuffer?) {
        JGL30.glGenVertexArrays(arrays.limited(n))
    }

    actual fun glIsVertexArray(array: Int): Boolean {
        return JGL30.glIsVertexArray(array)
    }

    actual fun glBeginTransformFeedback(primitiveMode: Int) {
        JGL30.glBeginTransformFeedback(primitiveMode)
    }

    actual fun glEndTransformFeedback() {
        JGL30.glEndTransformFeedback()
    }

    actual fun glBindBufferRange(target: Int, index: Int, buffer: Int, offset: Int, size: Int) {
        JGL30.glBindBufferRange(target, index, buffer, offset.toLong(), size.toLong())
    }

    actual fun glBindBufferBase(target: Int, index: Int, buffer: Int) {
        JGL30.glBindBufferBase(target, index, buffer)
    }

    actual fun glTransformFeedbackVaryings(program: Int, varyings: Array<String?>?, bufferMode: Int) {
        JGL30.glTransformFeedbackVaryings(
            program,
            requireNotNull(varyings).map { requireNotNull(it) }.toTypedArray(),
            bufferMode
        )
    }

    actual fun glVertexAttribIPointer(index: Int, size: Int, type: Int, stride: Int, offset: Int) {
        JGL30.glVertexAttribIPointer(index, size, type, stride, offset.toLong())
    }

    actual fun glGetVertexAttribIiv(index: Int, pname: Int, params: IntBuffer?) {
        JGL30.glGetVertexAttribI(index, pname, params)
    }

    actual fun glGetVertexAttribIuiv(index: Int, pname: Int, params: IntBuffer?) {
        JGL30.glGetVertexAttribIu(index, pname, params)
    }

    actual fun glVertexAttribI4i(index: Int, x: Int, y: Int, z: Int, w: Int) {
        JGL30.glVertexAttribI4i(index, x, y, z, w)
    }

    actual fun glVertexAttribI4ui(index: Int, x: Int, y: Int, z: Int, w: Int) {
        JGL30.glVertexAttribI4ui(index, x, y, z, w)
    }

    actual fun glGetUniformuiv(program: Int, location: Int, params: IntBuffer?) {
        JGL30.glGetUniformu(program, location, params)
    }

    actual fun glGetFragDataLocation(program: Int, name: String?): Int {
        return JGL30.glGetFragDataLocation(program, name)
    }

    actual fun glUniform1uiv(location: Int, count: Int, value: IntBuffer?) {
        JGL30.glUniform1u(location, value.limited(count * 1))
    }

    actual fun glUniform3uiv(location: Int, count: Int, value: IntBuffer?) {
        JGL30.glUniform3u(location, value.limited(count * 3))
    }

    actual fun glUniform4uiv(location: Int, count: Int, value: IntBuffer?) {
        JGL30.glUniform4u(location, value.limited(count * 4))
    }

    actual fun glClearBufferiv(buffer: Int, drawbuffer: Int, value: IntBuffer?) {
        JGL30.glClearBuffer(buffer, drawbuffer, value)
    }

    actual fun glClearBufferuiv(buffer: Int, drawbuffer: Int, value: IntBuffer?) {
        JGL30.glClearBufferu(buffer, drawbuffer, value)
    }

    actual fun glClearBufferfv(buffer: Int, drawbuffer: Int, value: FloatBuffer?) {
        JGL30.glClearBuffer(buffer, drawbuffer, value)
    }

    actual fun glClearBufferfi(buffer: Int, drawbuffer: Int, depth: Float, stencil: Int) {
        JGL30.glClearBufferfi(buffer, drawbuffer, depth, stencil)
    }

    actual fun glGetStringi(name: Int, index: Int): String? {
        return JGL30.glGetStringi(name, index)
    }

    actual fun glCopyBufferSubData(readTarget: Int, writeTarget: Int, readOffset: Int, writeOffset: Int, size: Int) {
        GL31.glCopyBufferSubData(readTarget, writeTarget, readOffset.toLong(), writeOffset.toLong(), size.toLong())
    }

    actual fun glGetUniformIndices(program: Int, uniformNames: Array<String?>?, uniformIndices: IntBuffer?) {
        GL31.glGetUniformIndices(
            program,
            requireNotNull(uniformNames).map { requireNotNull(it) }.toTypedArray(),
            uniformIndices
        )
    }

    actual fun glGetActiveUniformsiv(
        program: Int,
        uniformCount: Int,
        uniformIndices: IntBuffer?,
        pname: Int,
        params: IntBuffer?
    ) {
        GL31.glGetActiveUniforms(program, uniformIndices.limited(uniformCount), pname, params)
    }

    actual fun glGetUniformBlockIndex(program: Int, uniformBlockName: String?): Int {
        return GL31.glGetUniformBlockIndex(program, uniformBlockName)
    }

    actual fun glGetActiveUniformBlockiv(program: Int, uniformBlockIndex: Int, pname: Int, params: IntBuffer?) {
        GL31.glGetActiveUniformBlock(program, uniformBlockIndex, pname, params)
    }

    actual fun glGetActiveUniformBlockName(
        program: Int,
        uniformBlockIndex: Int,
        length: Buffer?,
        uniformBlockName: Buffer?
    ) {
        GL31.glGetActiveUniformBlockName(
            program,
            uniformBlockIndex,
            length as IntBuffer?,
            uniformBlockName as ByteBuffer?
        )
    }

    actual fun glUniformBlockBinding(program: Int, uniformBlockIndex: Int, uniformBlockBinding: Int) {
        GL31.glUniformBlockBinding(program, uniformBlockIndex, uniformBlockBinding)
    }

    actual fun glDrawArraysInstanced(mode: Int, first: Int, count: Int, instanceCount: Int) {
        GL31.glDrawArraysInstanced(mode, first, count, instanceCount)
    }

    actual fun glDrawElementsInstanced(mode: Int, count: Int, type: Int, indicesOffset: Int, instanceCount: Int) {
        GL31.glDrawElementsInstanced(mode, count, type, indicesOffset.toLong(), instanceCount)
    }

    actual fun glGetInteger64v(pname: Int, params: LongBuffer?) {
        GL32.glGetInteger64(pname, params)
    }

    actual fun glGetBufferParameteri64v(target: Int, pname: Int, params: LongBuffer?) {
        GL32.glGetBufferParameter(target, pname, params)
    }

    actual fun glGenSamplers(count: Int, samplers: IntBuffer?) {
        GL33.glGenSamplers(samplers.limited(count))
    }

    actual fun glDeleteSamplers(count: Int, samplers: IntBuffer?) {
        GL33.glDeleteSamplers(samplers.limited(count))
    }

    actual fun glIsSampler(sampler: Int): Boolean {
        return GL33.glIsSampler(sampler)
    }

    actual fun glBindSampler(unit: Int, sampler: Int) {
        GL33.glBindSampler(unit, sampler)
    }

    actual fun glSamplerParameteri(sampler: Int, pname: Int, param: Int) {
        GL33.glSamplerParameteri(sampler, pname, param)
    }

    actual fun glSamplerParameteriv(sampler: Int, pname: Int, param: IntBuffer?) {
        GL33.glSamplerParameter(sampler, pname, param)
    }

    actual fun glSamplerParameterf(sampler: Int, pname: Int, param: Float) {
        GL33.glSamplerParameterf(sampler, pname, param)
    }

    actual fun glSamplerParameterfv(sampler: Int, pname: Int, param: FloatBuffer?) {
        GL33.glSamplerParameter(sampler, pname, param)
    }

    actual fun glGetSamplerParameteriv(sampler: Int, pname: Int, params: IntBuffer?) {
        GL33.glGetSamplerParameter(sampler, pname, params)
    }

    actual fun glGetSamplerParameterfv(sampler: Int, pname: Int, params: FloatBuffer?) {
        GL33.glGetSamplerParameter(sampler, pname, params)
    }

    actual fun glVertexAttribDivisor(index: Int, divisor: Int) {
        GL33.glVertexAttribDivisor(index, divisor)
    }

    actual fun glBindTransformFeedback(target: Int, id: Int) {
        GL40.glBindTransformFeedback(target, id)
    }

    actual fun glDeleteTransformFeedbacks(n: Int, ids: IntBuffer?) {
        GL40.glDeleteTransformFeedbacks(ids.limited(n))
    }

    actual fun glGenTransformFeedbacks(n: Int, ids: IntBuffer?) {
        GL40.glGenTransformFeedbacks(ids.limited(n))
    }

    actual fun glIsTransformFeedback(id: Int): Boolean {
        return GL40.glIsTransformFeedback(id)
    }

    actual fun glPauseTransformFeedback() {
        GL40.glPauseTransformFeedback()
    }

    actual fun glResumeTransformFeedback() {
        GL40.glResumeTransformFeedback()
    }

    actual fun glProgramParameteri(program: Int, pname: Int, value: Int) {
        GL41.glProgramParameteri(program, pname, value)
    }

    actual fun glInvalidateFramebuffer(target: Int, numAttachments: Int, attachments: IntBuffer?) {
        GL43.glInvalidateFramebuffer(target, attachments.limited(numAttachments))
    }

    actual fun glInvalidateSubFramebuffer(
        target: Int,
        numAttachments: Int,
        attachments: IntBuffer?,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ) {
        GL43.glInvalidateSubFramebuffer(target, attachments.limited(numAttachments), x, y, width, height)
    }

    // LWJGL derives native element counts from the buffer's remaining range.
    // Use a duplicate to honour the API count without changing the caller's position or limit.
    private fun ByteBuffer?.limited(count: Int): ByteBuffer {
        val view = requireNotNull(this).duplicate()
        require(count >= 0 && count <= view.remaining()) { "Count exceeds the buffer's remaining elements" }
        (view as Buffer).limit(view.position() + count)
        return view
    }

    private fun ShortBuffer?.limited(count: Int): ShortBuffer {
        val view = requireNotNull(this).duplicate()
        require(count >= 0 && count <= view.remaining()) { "Count exceeds the buffer's remaining elements" }
        (view as Buffer).limit(view.position() + count)
        return view
    }

    private fun IntBuffer?.limited(count: Int): IntBuffer {
        val view = requireNotNull(this).duplicate()
        require(count >= 0 && count <= view.remaining()) { "Count exceeds the buffer's remaining elements" }
        (view as Buffer).limit(view.position() + count)
        return view
    }

    private fun FloatBuffer?.limited(count: Int): FloatBuffer {
        val view = requireNotNull(this).duplicate()
        require(count >= 0 && count <= view.remaining()) { "Count exceeds the buffer's remaining elements" }
        (view as Buffer).limit(view.position() + count)
        return view
    }

}
