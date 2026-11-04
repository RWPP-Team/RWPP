/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

internal object WorldShadowShaderSources {
    internal val FRAGMENT_SHADER = """
        #version 130
        precision mediump float;
        varying vec4 v_color;
        varying vec2 v_texCoords;
        uniform sampler2D u_texture;
        uniform vec4 u_shadowUv;
        uniform vec2 u_shadowTexel;
        uniform float u_shadowSoft;
        float silhouette(vec2 p) {
          vec2 lo = u_shadowUv.xy + u_shadowTexel * 0.5;
          vec2 hi = max(lo, u_shadowUv.zw - u_shadowTexel * 0.5);
          if (p.x < lo.x || p.y < lo.y || p.x > hi.x || p.y > hi.y) return 0.0;
          return texture2D(u_texture, clamp(p, lo, hi)).a;
        }
        void main() {
          vec2 d = u_shadowTexel * 1.65 * (1.0 + u_shadowSoft * 0.85);
          float a = silhouette(v_texCoords) * 0.28;
          a += silhouette(v_texCoords + vec2(d.x, 0.0)) * 0.10;
          a += silhouette(v_texCoords - vec2(d.x, 0.0)) * 0.10;
          a += silhouette(v_texCoords + vec2(0.0, d.y)) * 0.10;
          a += silhouette(v_texCoords - vec2(0.0, d.y)) * 0.10;
          a += silhouette(v_texCoords + d) * 0.08;
          a += silhouette(v_texCoords - d) * 0.08;
          a += silhouette(v_texCoords + vec2(d.x,-d.y)) * 0.08;
          a += silhouette(v_texCoords + vec2(-d.x,d.y)) * 0.08;
          gl_FragColor = vec4(0.04, 0.055, 0.075, a * mix(0.78, 0.56, u_shadowSoft) * v_color.a);
        }
    """.trimIndent()
}