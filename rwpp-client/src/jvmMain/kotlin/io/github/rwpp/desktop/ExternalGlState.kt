/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.desktop

import org.lwjgl.opengl.*

/** GL attribute stacks omit modern bindings and matrix contents shared by Slick and Skia. */
internal class ExternalGlState : AutoCloseable {
    private val framebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
    private val readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
    private val renderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING)
    private val program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
    private val vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)
    private val arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING)
    private val elementBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING)
    private val unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING)
    private val packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING)
    private val activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
    private val matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE)
    private val hasSamplers = GLContext.getCapabilities().OpenGL33
    private val samplers = IntArray(8)

    init {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS)
        GL11.glPushClientAttrib(-1)
        for (unit in samplers.indices) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit)
            if (hasSamplers) samplers[unit] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING)
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        for (mode in intArrayOf(GL11.GL_TEXTURE, GL11.GL_PROJECTION, GL11.GL_MODELVIEW)) {
            GL11.glMatrixMode(mode)
            GL11.glPushMatrix()
        }
        GL13.glActiveTexture(activeTexture)
        GL11.glMatrixMode(matrixMode)
    }

    override fun close() {
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        for (mode in intArrayOf(GL11.GL_MODELVIEW, GL11.GL_PROJECTION, GL11.GL_TEXTURE)) {
            GL11.glMatrixMode(mode)
            GL11.glPopMatrix()
        }
        GL30.glBindVertexArray(vertexArray)
        // Draw/read buffer selectors belong to their FBO. Pop attributes only after restoring
        // the owner, otherwise GL_BACK from the window would be applied to Skia's offscreen FBO.
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer)
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer)
        GL11.glPopClientAttrib()
        GL11.glPopAttrib()
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, renderbuffer)
        GL20.glUseProgram(program)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer)
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, elementBuffer)
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer)
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer)
        if (hasSamplers) for (unit in samplers.indices) GL33.glBindSampler(unit, samplers[unit])
        GL13.glActiveTexture(activeTexture)
        GL11.glMatrixMode(matrixMode)
    }
}
