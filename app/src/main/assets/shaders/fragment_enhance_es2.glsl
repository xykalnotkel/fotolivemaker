#version 100
// Filter Bersih: Redam noise bilateral 5x5 + Unsharp Mask dengan Coring Threshold
// Tepi tetap tajam sempurna tanpa memunculkan bintik noise pasir.

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
  float sigmaC = mix(0.24, 0.08, uDenoise);

  for (int y = -2; y <= 2; y++) {
    for (int x = -2; x <= 2; x++) {
      vec2 off = vec2(float(x) * uTexelW, float(y) * uTexelH);
      vec3 s = texture2D(uTexSampler, vTexSamplingCoord + off).rgb;
      float ws = 1.0;
      if (x == 0 && y == 0) ws = 4.0;
      else if (x == 0 || y == 0) ws = 2.5;
      else ws = 1.4;
      float d = distance(s, center);
      float wc = exp(-(d * d) / (2.0 * sigmaC * sigmaC));
      float w = ws * wc;
      sum += s * w;
      wsum += w;
    }
  }

  vec3 smoothed = sum / max(wsum, 0.0001);
  vec3 base = mix(center, smoothed, uDenoise * 0.70);

  if (uSharpen > 0.01) {
    vec3 blur = texture2D(uTexSampler, vTexSamplingCoord).rgb * 4.0;
    blur += texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, -uTexelH)).rgb * 2.0;
    blur += texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0,  uTexelH)).rgb * 2.0;
    blur += texture2D(uTexSampler, vTexSamplingCoord + vec2(-uTexelW, 0.0)).rgb * 2.0;
    blur += texture2D(uTexSampler, vTexSamplingCoord + vec2( uTexelW, 0.0)).rgb * 2.0;
    blur /= 12.0;

    vec3 diff = base - blur;
    // Coring gate: abaikan perbedaan di bawah 0.02 agar tidak memunculkan bintik noise pasir
    float diffMag = length(diff);
    float coringGate = smoothstep(0.02, 0.06, diffMag);
    base += diff * (uSharpen * 0.65 * coringGate);
  }

  gl_FragColor = vec4(clamp(base, 0.0, 1.0), 1.0);
}
