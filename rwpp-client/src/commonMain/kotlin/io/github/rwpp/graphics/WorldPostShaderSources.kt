/*
 * Copyright 2023-2026 RWPP contributors
 * 此源代码的使用受 RWPP-Team Shield 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the RWPP-Team Shield license that can be found through the following link.
 * https://github.com/RWPP-Team/RWPP/blob/main/LICENSE.md
 */

package io.github.rwpp.graphics

internal object WorldPostShaderSources {
    internal val FRAGMENT_SHADER: String = """
        #version 130
        precision mediump float;
        varying vec4 v_color;
        varying vec2 v_texCoords;
        uniform sampler2D u_texture;
        uniform float u_sunStrength;
        uniform float u_materialPass;
        uniform float u_postSSR;
        uniform float u_postTraceSteps;
        uniform float u_surfaceHighlights;
        uniform float u_dynamicBloom;
        uniform float u_wetStrength;
        uniform sampler2D u_sceneMaterials;
        uniform sampler2D u_spriteCoverage;
        uniform float u_separateSpriteCoverage;
        uniform vec2 u_spriteUvScale;
        uniform vec2 u_materialUvScale;
        uniform vec2 u_worldPerUv;
        uniform vec2 u_sceneExtent;

        vec3 sampleWorld(vec2 uv) {
          vec2 halfTexel=0.5/vec2(textureSize(u_texture,0));
          vec2 extent=u_materialPass>0.5 ? u_sceneExtent : vec2(1.0);
          return texture2D(u_texture,clamp(uv,halfTexel,max(halfTexel,extent-halfTexel))).rgb;
        }

        float luminance(vec3 color) {
          return dot(color, vec3(0.2126, 0.7152, 0.0722));
        }

        vec3 materialAt(vec2 uv) {
          vec2 sceneUv=clamp(uv,vec2(0.0),u_sceneExtent);
          vec3 tags=texture2D(u_sceneMaterials,sceneUv*u_materialUvScale).rgb;
          if(u_separateSpriteCoverage>0.5) {
            float coverage=texture2D(u_spriteCoverage,sceneUv*u_spriteUvScale).a;
            tags=mix(vec3(tags.rg,0.0),vec3(0.0,0.5,1.0),coverage);
          }
          return tags;
        }
        vec3 sceneReflection(vec2 uv,vec2 direction,float water,out float confidence) {
          vec2 ray=length(direction)>0.035 ? normalize(direction) : normalize(vec2(-0.55,-0.55));
          vec3 color=vec3(0.0); float sum=0.0;
          for(int i=1;i<=8;i++) {
            float n=float(i);
            if(n>u_postTraceSteps) break;
            vec2 sampleUv=uv+ray*(3.5*n+0.65*n*n)/u_worldPerUv;
            if(sampleUv.x<0.0 || sampleUv.y<0.0 || sampleUv.x>u_sceneExtent.x || sampleUv.y>u_sceneExtent.y) break;
            vec3 target=materialAt(sampleUv);
            float hit=water>0.5 ? max(target.b,1.0-target.r) : max(target.b,target.r);
            float weight=smoothstep(0.2,0.8,hit)*(1.0-n/(u_postTraceSteps+1.0));
            if(weight>0.0) {
              color+=sampleWorld(sampleUv)*weight;
              sum+=weight;
            }
          }
          confidence=clamp(sum*0.9,0.0,1.0);
          return sum>0.0001 ? color/sum : vec3(0.0);
        }
        vec3 lightSurface(vec2 uv,vec3 center,vec4 neighborLuma,vec3 tag,float unit) {
          float water=clamp(tag.r,0.0,1.0)*(1.0-unit);
          float coast=(tag.g-0.5)*12.0;
          float wetGround=(1.0-smoothstep(0.0,3.4,max(-coast,0.0)))*(1.0-water)*(1.0-unit)*u_wetStrength;
          bool reflective=u_postSSR>0.5 && (water>0.1 || wetGround>0.02);
          if(u_surfaceHighlights<=0.5 && !reflective) return center;
          vec2 gradient=vec2(neighborLuma.w-neighborLuma.z,neighborLuma.x-neighborLuma.y);
          vec3 normal=normalize(vec3(gradient*1.25,1.0));
          vec3 result=center;
          if(u_surfaceHighlights>0.5) {
            vec3 halfSun=normalize(normalize(vec3(-0.55,-0.55,0.82))+vec3(0.0,0.0,1.0));
            float centerLuma=luminance(center);
            float brightSurface=smoothstep(0.06,0.55,centerLuma);
            float chroma=max(center.r,max(center.g,center.b))-min(center.r,min(center.g,center.b));
            float mineralSheen=(1.0-smoothstep(0.05,0.22,chroma))*smoothstep(0.24,0.72,centerLuma);
            float dryGroundGloss=mineralSheen*(1.0-water)*(1.0-unit)*0.10;
            float gloss=(wetGround*0.52+dryGroundGloss)*brightSurface;
            float spec=pow(max(dot(normal,halfSun),0.0),14.0);
            result+=vec3(0.94,0.92,0.84)*spec*gloss*0.42*u_sunStrength;
          }
          if(reflective) {
            float hit;
            vec3 reflection=sceneReflection(uv,reflect(vec3(0.0,0.0,-1.0),normal).xy,water,hit);
            float reflectivity=water*0.32+wetGround*0.26;
            result=mix(result,reflection,clamp(reflectivity*hit,0.0,0.42));
          }
          return result;
        }
        float bloomEnergy(vec2 uv) {
          float energy=max(luminance(sampleWorld(uv))-0.70,0.0);
          if(energy<=0.0) return 0.0;
          return energy*(1.0-materialAt(uv).b);
        }
        vec4 rwppLightWorld(vec2 sceneUv) {
          vec2 uv = clamp(sceneUv, vec2(0.0), vec2(1.0));
          vec2 texel = 1.0 / vec2(textureSize(u_texture, 0));
          vec3 center = sampleWorld(uv);
          vec3 sourceCenter = center;
          bool materialPass=u_materialPass>0.5;
          vec3 tag=materialPass ? materialAt(uv) : vec3(0.0);
          // Sprite art has no physical normals/materials. Preserve it instead of inferring
          // a glossy surface from team colours or terrain underneath an airborne unit.
          float spriteCoverage=clamp(tag.b,0.0,1.0);
          if(spriteCoverage>0.999) {
            return vec4(center,1.0);
          }
          vec3 north = sampleWorld(uv + vec2(0.0, -2.0 * texel.y));
          vec3 south = sampleWorld(uv + vec2(0.0,  2.0 * texel.y));
          vec3 east  = sampleWorld(uv + vec2( 2.0 * texel.x, 0.0));
          vec3 west  = sampleWorld(uv + vec2(-2.0 * texel.x, 0.0));

          vec4 neighborLuma=vec4(luminance(north),luminance(south),luminance(east),luminance(west));
          if(materialPass) center=lightSurface(uv,center,neighborLuma,tag,spriteCoverage);
          float centerLuma = luminance(center);
          float neighborhoodLuma = (neighborLuma.x+neighborLuma.y+neighborLuma.z+neighborLuma.w)*0.25;

          // A gentle S-curve strengthens midtones while keeping black and white anchored.
          float contrastLuma = centerLuma +
              0.40 * centerLuma * (1.0 - centerLuma) * (2.0 * centerLuma - 1.0);

          // Sharpen only local luminance edges; clamp the high-pass term to limit ringing.
          float edge = centerLuma - neighborhoodLuma;
          float edgeWeight = smoothstep(0.012, 0.10, abs(edge));
          float sharpenedLuma = contrastLuma + clamp(edge, -0.06, 0.06) * 0.14 * edgeWeight;

          float sunlight = clamp(u_sunStrength,0.0,3.0);
          float bloom=0.0;
          if(!materialPass) {
            // The fallback bloom limits highlights to the immediate neighborhood.
            vec4 bright=max(neighborLuma-0.78,vec4(0.0));
            float brightTaps=max(centerLuma-0.78,0.0)+bright.x+bright.y+bright.z+bright.w;
            float wideBright=max(luminance(sampleWorld(uv+vec2(6.0*texel.x,0.0)))-0.70,0.0)
                + max(luminance(sampleWorld(uv-vec2(6.0*texel.x,0.0)))-0.70,0.0)
                + max(luminance(sampleWorld(uv+vec2(0.0,6.0*texel.y)))-0.70,0.0)
                + max(luminance(sampleWorld(uv-vec2(0.0,6.0*texel.y)))-0.70,0.0);
            bloom=min((brightTaps*0.07+wideBright*0.035)*(0.6+0.4*sunlight),0.06);
          } else if(u_dynamicBloom>0.5) {
            float halo=max(luminance(sourceCenter)-0.70,0.0)*(1.0-tag.b)*0.08
                + (bloomEnergy(uv+vec2(4.0*texel.x,0.0))+bloomEnergy(uv-vec2(4.0*texel.x,0.0))
                  +bloomEnergy(uv+vec2(0.0,4.0*texel.y))+bloomEnergy(uv-vec2(0.0,4.0*texel.y)))*0.10
                + (bloomEnergy(uv+texel*9.0)+bloomEnergy(uv-texel*9.0))*0.06;
            bloom=min(halo*sunlight,0.11);
          }
          // Compress exposure near white instead of clipping snow/stone texture. Keep
          // shadow contrast from the terrain pass while still lighting unit sprites.
          float exposed = clamp(sharpenedLuma,0.0,1.0);
          float exposure = 1.0+(sunlight-0.45)*0.30;
          float baseLuma = exposed*exposure/(1.0+exposed*(exposure-1.0));
          float outputLuma = baseLuma+bloom*(1.0-baseLuma);

          // Scale RGB together to retain the source hue. Cap by channel headroom so highlights
          // never clip a single channel and white stays white.
          float sourceMax = max(center.r, max(center.g, center.b));
          float scale = outputLuma / max(centerLuma, 0.0001);
          scale = min(scale, 1.0 / max(sourceMax, 0.0001));
          vec3 detailed = centerLuma > 0.0001 ? center * scale : vec3(outputLuma);
          detailed=mix(detailed,sourceCenter,spriteCoverage);
          return vec4(clamp(detailed, vec3(0.0), vec3(1.0)), 1.0);
        }
        void main() {
          gl_FragColor = rwppLightWorld(v_texCoords) * v_color;
        }
    """.trimIndent()

    /** Native post_displacement coordinates and neutral value, followed by the same world lighting.
     * screenBase is the raw world FBO, not the already lit screen: apply lighting exactly once.
     * Material lookups use the displaced UV too, so water/sprite coverage follows the wave.
     */
    internal val DISPLACEMENT_FRAGMENT_SHADER: String = FRAGMENT_SHADER
        .substringBefore("void main() {")
        .replace(Regex("\\bu_texture\\b"), "screenBase") + """
        uniform sampler2D u_texture;
        uniform vec2 screenBaseSize;
        uniform vec2 u_resolution;
        uniform float u_offsetBy;
        uniform float u_uiScaling;
        void main() {
          vec2 usedScreenSize=u_resolution/screenBaseSize;
          vec2 screenUV=gl_FragCoord.xy/(u_resolution*u_uiScaling);
          screenUV.y=1.0-screenUV.y;
          screenUV*=usedScreenSize;
          vec4 spriteColor=texture2D(u_texture,v_texCoords);
          vec2 screenOffset=u_offsetBy*(spriteColor.xy-vec2(128.0/255.0))*spriteColor.a*v_color.a;
          vec2 offsetScreenUV=clamp(screenUV+screenOffset,vec2(0.0),usedScreenSize);
          gl_FragColor=rwppLightWorld(offsetScreenUV);
        }
    """.trimIndent()
}
