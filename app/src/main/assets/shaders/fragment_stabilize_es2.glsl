#version 100
// Koreksi guncangan per-frame.
//
// Tiap frame digeser berlawanan arah guncangannya sendiri, lalu di-zoom
// supaya tepi kosong akibat pergeseran tidak terlihat.
//
// Inilah yang membuat stabilisasi jadi nyata: sebelumnya hanya zoom
// seragam, sehingga guncangan tetap ada dan gambar cuma ter-crop.

precision mediump float;

uniform sampler2D uTexSampler;
uniform float uZoom;        // mis. 1.08
uniform float uOffsetX;     // pergeseran koreksi, satuan UV (-0.1..0.1)
uniform float uOffsetY;

varying vec2 vTexSamplingCoord;

void main() {
  // zoom terhadap titik tengah, lalu geser
  vec2 c = vTexSamplingCoord - vec2(0.5);
  c /= uZoom;
  c += vec2(0.5);
  c += vec2(uOffsetX, uOffsetY);

  // jaga supaya tidak mengambil di luar tekstur
  c = clamp(c, vec2(0.0), vec2(1.0));

  gl_FragColor = texture2D(uTexSampler, c);
}
