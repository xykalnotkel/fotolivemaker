#version 100
// Penghalus + penajam video.
//
// Dua tahap nyata, bukan filter warna kosmetik:
//   1. Bilateral 3x3  -> meredam bintik/noise TAPI tepi tetap tajam,
//                        karena piksel yang warnanya jauh beda diberi
//                        bobot kecil.
//   2. Unsharp mask   -> mengembalikan ketajaman yang hilang akibat
//                        penghalusan, sehingga hasilnya terlihat lebih
//                        bersih tanpa jadi buram.
//
// Catatan jujur: ini TIDAK menambah detail yang tidak ada di video asli.
// Yang dilakukan adalah membersihkan noise dan mempertegas tepi.

precision mediump float;

uniform sampler2D uTexSampler;
uniform float uTexelW;      // 1.0 / lebar
uniform float uTexelH;      // 1.0 / tinggi
uniform float uDenoise;     // 0.0 - 1.0
uniform float uSharpen;     // 0.0 - 1.0

varying vec2 vTexSamplingCoord;

void main() {
  vec3 center = texture2D(uTexSampler, vTexSamplingCoord).rgb;

  vec3 sum = vec3(0.0);
  float wsum = 0.0;

  // sigma untuk beda warna: makin kecil, makin menjaga tepi
  float sigmaC = mix(0.35, 0.10, uDenoise);

  for (int y = -1; y <= 1; y++) {
    for (int x = -1; x <= 1; x++) {
      vec2 off = vec2(float(x) * uTexelW, float(y) * uTexelH);
      vec3 s = texture2D(uTexSampler, vTexSamplingCoord + off).rgb;

      // bobot jarak spasial (gaussian 3x3 sederhana)
      float ws = (x == 0 && y == 0) ? 4.0
               : ((x == 0 || y == 0) ? 2.0 : 1.0);

      // bobot kemiripan warna -> inilah yang menjaga tepi
      float d = distance(s, center);
      float wc = exp(-(d * d) / (2.0 * sigmaC * sigmaC));

      float w = ws * wc;
      sum += s * w;
      wsum += w;
    }
  }

  vec3 smoothed = sum / max(wsum, 0.0001);
  vec3 base = mix(center, smoothed, uDenoise);

  // Unsharp mask: base + (base - blur) * jumlah
  vec3 blur = vec3(0.0);
  blur += texture2D(uTexSampler, vTexSamplingCoord + vec2(-uTexelW, -uTexelH)).rgb;
  blur += texture2D(uTexSampler, vTexSamplingCoord + vec2( 0.0,    -uTexelH)).rgb * 2.0;
  blur += texture2D(uTexSampler, vTexSamplingCoord + vec2( uTexelW, -uTexelH)).rgb;
  blur += texture2D(uTexSampler, vTexSamplingCoord + vec2(-uTexelW,  0.0)).rgb * 2.0;
  blur += texture2D(uTexSampler, vTexSamplingCoord).rgb * 4.0;
  blur += texture2D(uTexSampler, vTexSamplingCoord + vec2( uTexelW,  0.0)).rgb * 2.0;
  blur += texture2D(uTexSampler, vTexSamplingCoord + vec2(-uTexelW,  uTexelH)).rgb;
  blur += texture2D(uTexSampler, vTexSamplingCoord + vec2( 0.0,     uTexelH)).rgb * 2.0;
  blur += texture2D(uTexSampler, vTexSamplingCoord + vec2( uTexelW,  uTexelH)).rgb;
  blur /= 16.0;

  vec3 sharp = base + (base - blur) * (uSharpen * 1.6);

  gl_FragColor = vec4(clamp(sharp, 0.0, 1.0), 1.0);
}
