# GPU enhancement in the Android overlay

On arm64 Android devices, open the Web player's existing picture enhancement menu. Select a mode, tier, and output size or use a saved custom chain. The same per-series settings and optional CRT and monochrome controls apply. Switching effects during playback keeps the video in the ExoPlayer overlay. Turning enhancement off removes the effect chain.

Web resolves the current preset to effect IDs and parameters. Android loads the corresponding bundled GPU shaders, creates the GLES passes, and reports activation after rendering a frame. A revision and the proxied video's ID associate each status update with its request. MediaCodec and FFmpeg software video use the same GPU model catalog. For SDR software decoding, Media3 owns the output surface while the menu switches chains, including the empty chain. HDR bypasses enhancement. Older clients without the bridge retain the existing browser playback policy.

The catalog is generated from the adjacent Web checkout's NijiLucid descriptor registry and 76 pinned GLSL sources. The build verifies the source hashes. Standalone Anime4K CNNx2S at the tested 2× sizes uses the specialized four-pass GLES implementation; other models and chains use the libplacebo OpenGL backend. Both release flavors include the shader notices and the source, license, build script and GLES patch for the linked libplacebo version.

The original Anime4K weights are from [bloc97/Anime4K](https://github.com/bloc97/Anime4K) (MIT, copyright 2019–2021 bloc97). Retain its [license](https://github.com/bloc97/Anime4K/blob/master/LICENSE) when distributing the generated shaders or weights.

The normal `./gradlew assembleRelease` build uses the GPU shaders in `app/src/main/assets` and the arm64 libraries in `app/src/main/jniLibs`. Both Libre and Proprietary use the Web menu preference. When the native library cannot load, the client uses its existing Web enhancement policy.

To update the bundled files, use the matching Jellyfin Web checkout with its Node dependencies installed, Python with NumPy, Meson, Ninja and Android NDK 29 on Linux. From the Android repository root:

```sh
overlay=tools/anime4k-gpu/overlay
web=../jellyfin-web
assets=app/src/main/assets
python3 -B "$overlay/gpu_shaders.py" "$web/src/lib/nijilucid/engines/anime4k/pipelines/upscale/CNNx2S/shaders" "$assets/anime4k"
node "$overlay/model-assets.cjs" "$web" "$assets"
bash "$overlay/build-renderer.sh" app/src/main/jniLibs build/overlay-placebo "$assets"
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded app/src/main/jniLibs/arm64-v8a/*.so
```

The optional debug fixture can be built with `./gradlew :app:assembleProprietaryDebug -Panime4kTrial`.

To exercise a particular model in the ADB-only fixture activity, pass `--es model` with its full catalog ID, for example `artcnn/Upscale/C4F16`. Omit the ID to use CNNx2S. The activity uses the normal VideoOverlayManager and a 1920×1080 viewport. For a direct baseline pass `--ez hybrid false`; this historical intent extra controls the fixture only.

```sh
ffmpeg -f lavfi -i 'testsrc2=size=960x540:rate=24' -f lavfi -i 'anullsrc=r=48000:cl=stereo' \
  -t 30 -c:v libx264 -pix_fmt yuv420p -colorspace bt709 -color_primaries bt709 -color_trc bt709 \
  -c:a aac -movflags +faststart /tmp/overlay-540p.mp4
pkg=org.jellyfin.mobile.custom.debug
fixture=files/00000000-0000-0000-0000-000000000001
adb -s "$SERIAL" shell "run-as $pkg mkdir -p $fixture"
adb -s "$SERIAL" shell -T "run-as $pkg sh -c 'cat > $fixture/anime4k-overlay-fixture.mp4'" < /tmp/overlay-540p.mp4
adb -s "$SERIAL" shell am start -S -n "$pkg/org.jellyfin.mobile.player.videoproxy.OverlayTrialActivity" --es model artcnn/Upscale/C4F16
```

On a Xiaomi 11 Ultra (Adreno 660), a 128×72 clip exercised all 76 catalog models individually and the CRT, monochrome and resize effects through first rendered output. This is a compatibility check. In a separate short 540p playback check, ArtCNN C4F16 processed about 3 fps; Anime4K DoG, ACNet F8B4, ARNet F8B8, CuNNy DS/2x12, CRT and monochrome processed about 24 fps. During regular playback of a 1080p High 10 video, selecting CuNNy, switching to ARNet and disabling enhancement through the Web menu all kept the same proxied video playing.

## GPU CNNx2S measurements

These measurements of the specialized CNNx2S GLES implementation predate the full model-menu integration.

Xiaomi 11 Ultra, Adreno 660, same 30-second 1080p High 10 excerpt at 23.976 fps (two GPU runs). The 20-second stable window starts four seconds after the first processed frame.

| Backend | Effect P50 / P95 / max (ms) | Frames over 41.71 ms / completed | Stable effect frames/s | ExoPlayer rendered / dropped / skipped |
|---|---|---|---:|---|
| GPU run 1 | 17.82 / 18.49 / 21.70 | 0 / 707 | 24.0 | 709 / 9 / 30 |
| GPU run 2 | 17.81 / 18.60 / 29.70 | 0 / 709 | 24.0 | 710 / 8 / 30 |

The 540p MediaCodec fixture also completed all 720 frames on the GPU, with a 7.44 ms P95 and zero ExoPlayer drops or skips. Paired 512×512 crops in a separate run differed at 37.9% of pixels (at least one RGB channel) with mean absolute channel difference 0.59 on the 0–255 scale. These figures measure the difference from bilinear scaling on one patch. Display scanout and sustained power consumption were not measured.
