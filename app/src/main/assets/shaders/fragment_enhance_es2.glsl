// XySpace License v1.0 — Personal use only
// Copyright 2026 XySpace — Haekal Saputra

#version 100
// Filter Bersih: Redam noise bilateral 5x5 + Unsharp Mask dengan Coring Threshold
// Tepi tetap tajam sempurna tanpa memunculkan bintik noise pasir.
// Spatial weights disamakan dengan implementasi Kotlin/C++ biar konsisten.

precision mediump float;

uniform sampler2D uTexSampler;
uniform float uTexelW;
uniform float uTexelH;
uniform float uDenoise;
uniform float uSharpen;

varying vec2 vTexSamplingCoord;

void main() {
  vec3 center = texture2D(uTexSampler, vTexSamplingCoord).rgb;
  vec3 sum = vec3(0.0);
  float wsum = 0.0;
  // Sigma tetap (fixed 26 dalam byte space -> 0.102 normalized)
  // agar konsisten dengan CPU, tidak terbalik kayak sebelumnya (higher denoise = less smooth)
  float sigmaC = 0.102;

  for (int y = -2; y <= 2; y++) {
    for (int x = -2; x <= 2; x++) {
      vec2 off = vec2(float(x) * uTexelW, float(y) * uTexelH);
      vec3 s = texture2D(uTexSampler, vTexSamplingCoord + off).rgb;
      // Spatial weights: samain sama Kotlin/C++
      // 0->4.0, 1->2.5, 2->1.8, else->1.0
      float ws = 1.0;
      int dist2 = x*x + y*y;
      if (dist2 == 0) ws = 4.0;
      else if (dist2 == 1) ws = 2.5;
      else if (dist2 == 2) ws = 1.8;
      // else ws = 1.0 (default)
      float d = distance(s, center);
      float wc = exp(-(d * d) / (2.0 * sigmaC * sigmaC));
      float w = ws * wc;
      sum += s * w;
      wsum += w;
    }
  }

  vec3 smoothed = sum / max(wsum, 0.0001);
  vec3 base = mix(center, smoothed, 0.65);

  if (uSharpen > 0.01) {
    // 4-neighbour blur, samain sama CPU (bukan 5-tap weighted)
    vec3 blur = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, -uTexelH)).rgb;
    blur += texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0,  uTexelH)).rgb;
    blur += texture2D(uTexSampler, vTexSamplingCoord + vec2(-uTexelW, 0.0)).rgb;
    blur += texture2D(uTexSampler, vTexSamplingCoord + vec2( uTexelW, 0.0)).rgb;
    blur /= 4.0;

    vec3 diff = base - blur;
    // Coring gate: pake luma biar konsisten sama CPU, bukan length(RGB)
    float lumaDiff = abs(0.299*diff.r + 0.587*diff.g + 0.114*diff.b);
    float coringGate = smoothstep(0.0235, 0.0706, lumaDiff);
    base += diff * (uSharpen * 0.65 * coringGate);
  }

  gl_FragColor = vec4(clamp(base, 0.0, 1.0), 1.0);
}