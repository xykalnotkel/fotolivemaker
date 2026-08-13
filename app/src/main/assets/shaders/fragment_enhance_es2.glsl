#version 100
// Bersih: redam noise 5x5, lalu unsharp RINGAN untuk mengembalikan
// tepi yang hilang karena zoom stabilizer / encode. Bukan HD engine.

precision mediump float;

uniform sampler2D uTexSampler;
uniform float uTexelW;
uniform float uTexelH;
uniform float uDenoise;
uniform float uSharpen;     // disimpan supaya shader lama tidak pecah; 0 = mati

varying vec2 vTexSamplingCoord;

void main() {
  vec3 center = texture2D(uTexSampler, vTexSamplingCoord).rgb;
  vec3 sum = vec3(0.0);
  float wsum = 0.0;
  float sigmaC = mix(0.28, 0.09, uDenoise);

  for (int y = -2; y <= 2; y++) {
    for (int x = -2; x <= 2; x++) {
      vec2 off = vec2(float(x) * uTexelW, float(y) * uTexelH);
      vec3 s = texture2D(uTexSampler, vTexSamplingCoord + off).rgb;
      float ws = 1.0;
      if (x == 0 && y == 0) ws = 5.0;
      else if (x == 0 || y == 0) ws = 3.0;
      else if (abs(float(x)) + abs(float(y)) == 2.0) ws = 2.0;
      float d = distance(s, center);
      float wc = exp(-(d * d) / (2.0 * sigmaC * sigmaC));
      float w = ws * wc;
      sum += s * w;
      wsum += w;
    }
  }

  vec3 smoothed = sum / max(wsum, 0.0001);
  vec3 base = mix(center, smoothed, uDenoise);

  if (uSharpen > 0.01) {
    vec3 blur = texture2D(uTexSampler, vTexSamplingCoord).rgb * 4.0;
    blur += texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, -uTexelH)).rgb * 2.0;
    blur += texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0,  uTexelH)).rgb * 2.0;
    blur += texture2D(uTexSampler, vTexSamplingCoord + vec2(-uTexelW, 0.0)).rgb * 2.0;
    blur += texture2D(uTexSampler, vTexSamplingCoord + vec2( uTexelW, 0.0)).rgb * 2.0;
    blur /= 12.0;
    base += (base - blur) * (uSharpen * 0.7);
  }

  gl_FragColor = vec4(clamp(base, 0.0, 1.0), 1.0);
}
