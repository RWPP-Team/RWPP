/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

internal object WorldUnitShaderSources {
    internal val VERTEX_SHADER = """
        #version 130
        varying vec4 v_color;
        varying vec2 v_texCoords;
        void main() {
          gl_Position=gl_ProjectionMatrix*gl_ModelViewMatrix*gl_Vertex;
          v_color=gl_Color;
          v_texCoords=vec2(gl_MultiTexCoord0);
        }
    """.trimIndent()
    internal val FRAGMENT_SHADER = """
        #version 130
        varying vec4 v_color;
        varying vec2 v_texCoords;
        uniform sampler2D u_texture;
        void main() {
          float a=texture2D(u_texture,v_texCoords).a*v_color.a;
          gl_FragColor=vec4(0.0,0.5,1.0,a);
        }
    """.trimIndent()
    internal val BACKGROUND_FRAGMENT_SHADER = """
        #version 130
        varying vec2 v_texCoords;
        uniform sampler2D u_texture;
        uniform vec2 u_worldOrigin;
        uniform vec2 u_viewWorldSize;
        uniform vec2 u_maskWorldScale;
        uniform vec2 u_maskExtent;
        uniform vec2 u_maskTexel;
        uniform vec2 u_maskWorldOrigin;
        void main() {
          vec2 world=u_worldOrigin+v_texCoords/u_maskExtent*u_viewWorldSize;
          vec2 uv=clamp((world-u_maskWorldOrigin)*u_maskWorldScale,u_maskTexel*0.5,u_maskExtent-u_maskTexel*0.5);
          vec2 surface=texture2D(u_texture,uv).rg;
          gl_FragColor=vec4(surface,0.0,1.0);
        }
    """.trimIndent()

}
