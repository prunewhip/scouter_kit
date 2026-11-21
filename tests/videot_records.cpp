// The VIDEOT records of tools/draw_bench.py --videot (g1v0 0.5.68+), one hex line each, from the library's
// TransformVideoEncoder at 320 x 240 (drawn 640 x 480): a first picture (flat), five frames of a camera pan with a
// moving object, a still frame (nothing changed), a scene cut at a fine quantiser (many coefficients), and last
// 4000 random bytes after a reset at q 1 (the decoder's cost for hostile input; the picture is then garbage).
//   g++ -std=c++20 -O2 -DSCOUTER_G1_EMULATOR tests/videot_records.cpp src/base/scouter_kit.cpp src/vm/vm.c
//       src/vm/vm_gfx.c -o videot_records -lpthread
#include "../src/g1/scouter_kit_g1.cpp"

#include <cmath>
#include <cstdio>
#include <random>

int main() {
  const int w = 320, h = 240, sw = 480, sh = 320;
  std::vector<uint8_t> scene(size_t(sw) * size_t(sh));
  std::mt19937 rng(7);
  for (int y = 0; y < sh; y++)
    for (int x = 0; x < sw; x++) {
      double v = 90 + 60 * std::sin(x * 0.021) * std::cos(y * 0.017) + 25 * std::sin((x + 2 * y) * 0.11);
      if ((x / 40 + y / 40) % 3 == 0) v += 50;           // tiles with sharp edges
      v += int(rng() % 17) - 8;                          // sensor noise
      scene[size_t(y) * size_t(sw) + size_t(x)] = uint8_t(std::clamp(int(v), 0, 255));
    }
  auto frame = [&](int t) {
    std::vector<uint8_t> f(size_t(w) * size_t(h));
    int ox = 20 + 3 * t, oy = 10 + 2 * t;                // the camera pans
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) f[size_t(y) * size_t(w) + size_t(x)] = scene[size_t(y + oy) * size_t(sw) + size_t(x + ox)];
    int bx = 60 + 9 * t, by = 80 + 4 * t;                // a bright ball moving the other way
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int dx = x - bx, dy = y - by;
        if (dx * dx + dy * dy < 900) f[size_t(y) * size_t(w) + size_t(x)] = uint8_t(230 - (dx * dx + dy * dy) / 12);
      }
    return f;
  };
  auto emit = [](const std::vector<uint8_t> &r) {
    if (r.empty()) std::exit(1);
    for (uint8_t b : r) std::printf("%02x", b);
    std::printf("\n");
  };
  scouter_kit::g1::TransformVideoEncoder enc(w, h);
  emit(enc.Encode(frame(0), 30, true));
  for (int t = 1; t <= 5; t++) emit(enc.Encode(frame(t), 30));
  emit(enc.Encode(frame(5), 30));
  std::vector<uint8_t> cut(size_t(w) * size_t(h));
  for (int y = 0; y < h; y++)
    for (int x = 0; x < w; x++) cut[size_t(y) * size_t(w) + size_t(x)] = scene[size_t(sh - 1 - y) * size_t(sw) + size_t(sw - 1 - x)];
  emit(enc.Encode(cut, 8, true));
  std::vector<uint8_t> junk(4002);
  junk[0] = 3; // reset + redraw
  junk[1] = 1;
  for (size_t i = 2; i < junk.size(); i++) junk[i] = uint8_t(rng());
  emit(junk);
  return 0;
}
