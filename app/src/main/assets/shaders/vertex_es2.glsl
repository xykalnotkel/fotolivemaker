// XySpace License v1.0 — Personal use only
// Copyright 2026 XySpace — Haekal Saputra

#version 100
// Vertex shader sederhana: gambar quad layar penuh.
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5,
                           aFramePosition.y * 0.5 + 0.5);
}
