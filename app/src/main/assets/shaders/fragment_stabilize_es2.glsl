// XySpace License v1.0 — Personal use only
// Copyright 2026 XySpace — Haekal Saputra

#version 100
// Koreksi guncangan: rotasi kecil + geser X/Y + zoom penutup tepi.

precision mediump float;

uniform sampler2D uTexSampler;
uniform float uZoom;
uniform float uOffsetX;
uniform float uOffsetY;
uniform float uRot;         // radian, kecil (mis. -0.05..0.05)

varying vec2 vTexSamplingCoord;

void main() {
  vec2 c = vTexSamplingCoord - vec2(0.5);
  float s = sin(uRot);
  float co = cos(uRot);
  c = vec2(co * c.x - s * c.y, s * c.x + co * c.y);
  c /= max(uZoom, 1.0);
  c += vec2(0.5);
  c += vec2(uOffsetX, uOffsetY);
  c = clamp(c, vec2(0.0), vec2(1.0));
  gl_FragColor = texture2D(uTexSampler, c);
}
