# HypnoHeart · 催眠爱心

A ridiculously tiny Android app that renders the classic **"hypnosis heart"** visual — concentric pink/white heart stripes that keep expanding outward and fill the whole screen. Purely decorative: no sound, no permissions, no network, no effect whatsoever on the viewer.

一个「催眠爱心」视觉小玩具：粉白相间的爱心条纹从中心不断向外扩张、铺满整个屏幕，锁竖屏、不旋转。
纯视觉装饰，不发声、不联网、无任何权限，**对观看者没有任何实际作用**。

![preview](docs/preview.gif)

<p align="center">
  <img src="docs/screenshot-portrait.png" width="320" alt="portrait screenshot">
</p>

## 特点 / Highlights

- **12 KB APK** — single Java file, no resources, no third-party dependencies, no Gradle.
- **Portrait-locked, full-screen, keep-screen-on** — walks whatever screen it lands on.
- **GPU-generated per pixel** — the whole pattern is a fragment shader; the CPU idles at a few percent.
- **Sharp at any resolution** — anti-aliased analytically, no bitmaps, no texture upscaling.
- **Seamless loop** — the color period is exactly 2 stripes, so the outward flow never jumps.

## 效果参数 / Parameters

All in `HypnoRenderer` (`src/com/hypno/heart/MainActivity.java`):

| Constant | Value | Meaning |
|---|---|---|
| `BANDS` | `15` | stripes per unit radius |
| `FLOW` | `1.6f` | outward speed in stripes/second (one visual cycle = 1.25 s) |
| `R_RATIO` | `0.97f` | unit radius ÷ screen short side (>1 → the heart is bigger than the screen, stripes reach the edges) |
| colors | `#FFFFFF` / `#FF5FA2` | white / pink |

## 怎么实现的 / How it works

Every pixel is converted into a "heart-space" radius `d = |p| / ρ(θ)`, then colored by `parity(floor(d·N − phase))`:

- `ρ(θ)` — the heart outline comes from the classic parametric curve
  `x = 16sin³t`, `y = 13cos t − 5cos2t − 2cos3t − cos4t`,
  re-centred on its area centroid, normalized to the unit circle, and baked into a **512×1 lookup table**
  (`GL_LUMINANCE_ALPHA`, two channels = 16-bit precision, linearly filtered — 8-bit alone wobbles the stripe edges by ~5 px).
- Anti-aliasing — the shader derives the phase change per pixel from `uN/(uR·ρ)` and smoothsteps a ~1 px transition,
  so no `fwidth` / `GL_OES_standard_derivatives` is required (GLES 2.0 only).
- `RENDERMODE_CONTINUOUSLY` on a `GLSurfaceView`; the LUT is rebuilt whenever the GL context is recreated.

### 为什么不用 Canvas 画路径 / Why not just draw Paths

Drawing the pattern with `Canvas.drawPath` needs the heart scaled 4× beyond the screen, and a single path's
device-space bounds reach ~5600 px. **Skia falls back to CPU rasterization for path masks above the atlas size limit
(~2048 px)**, which pins a full core (measured 94–109% CPU at 60 fps). Tiling / even-odd / non-zero winding / stroking
all fail the same way, because the cost follows the clipped mask area. Moving to a fragment shader dropped it to **~10%**
(and most of that is the emulator's own compositor).

## 构建 / Build

```powershell
pwsh -File .\build.ps1
```

The script resolves the Android SDK from `ANDROID_HOME` / `ANDROID_SDK_ROOT`, or falls back to a `D:\xq-sdk`-style
layout; it needs **build-tools 34.0.0** (or 35.x), **platform android-34**, and a **JDK 17** on `JAVA_HOME`.
It runs `javac → d8 → aapt2 link → aapt add → zipalign → apksigner` and signs with a local debug keystore it creates
on first run. No Gradle, no Android Studio, no network.

## 安装 / Install

```bash
adb install -r HypnoHeart.apk
adb shell am start -n com.hypno.heart/.MainActivity
```

Full screen, screen kept awake; press **Back** to exit. Rendering pauses automatically in the background.

*(A prebuilt APK is attached to the [Releases](../../releases) page — this repo intentionally does not commit build artifacts.)*

## 预览是怎么来的 / About the preview

`make_preview.py` is a NumPy re-implementation of the same shader math (including the same analytic AA), so the GIF above
is generated offline at 50 frames and doubles as a cross-check of the GLSL. Regenerate with:

```bash
python make_preview.py
```

## License

[MIT](LICENSE)
