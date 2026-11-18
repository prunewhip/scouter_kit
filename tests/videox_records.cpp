// The VIDEOX records of the emulator test `videox` (g1_firmware/emulator/tests/v5_tests.py), one hex line each,
// from the library's PlaneVideoEncoder: one 640 x 480 stream of 4 planes (16 greys): a page of grey "text" (redraw),
// the page scrolled up 37 rows plus a changed line (a move), a grey ramp under the text, the page scrolled back down
// 100 rows (a move), and the first page again (redraw).
//   g++ -std=c++20 -O2 -DSCOUTER_G1_EMULATOR tests/videox_records.cpp src/base/scouter_kit.cpp src/vm/vm.c
//       src/vm/vm_gfx.c -o videox_records -lpthread
#include "../src/g1/scouter_kit_g1.cpp"

#include <cstdio>

int main() {
  const int w = 640, h = 480, pageh = 480 + 200;
  scouter_kit::g1::PlaneVideoEncoder enc(w, h, 4);
  std::vector<uint8_t> page(size_t(w) * size_t(pageh));
  for (int y = 0; y < pageh; y++)
    for (int x = 0; x < w; x++) {
      int line = y / 16, in = y % 16, col = x / 9;
      bool ink = in >= 3 && in < 13 && (col * 7 + line * 13) % 11 < 6 && ((x + y) % 9 < 5) && (col % 12 != 11);
      page[size_t(y) * size_t(w) + size_t(x)] = uint8_t(ink ? 15 - line % 8 : 0);
    }
  auto at = [&](int top) {
    return std::vector<uint8_t>(page.begin() + std::ptrdiff_t(size_t(top) * size_t(w)),
                                page.begin() + std::ptrdiff_t(size_t(top + h) * size_t(w)));
  };
  auto emit = [](const std::vector<uint8_t> &r) {
    for (uint8_t b : r) std::printf("%02x", b);
    std::printf("\n");
  };
  std::vector<uint8_t> f = at(0);
  emit(enc.Encode(f, true));
  f = at(37);
  for (int x = 0; x < w; x++) f[size_t(200) * size_t(w) + size_t(x)] = uint8_t(x % 16);
  scouter_kit::g1::RowMove m = enc.FindMove(f);
  if (!m.rows) return 1;
  emit(enc.Encode(f, false, m));
  for (int y = 400; y < h; y++)
    for (int x = 0; x < w; x++)
      if (!f[size_t(y) * size_t(w) + size_t(x)]) f[size_t(y) * size_t(w) + size_t(x)] = uint8_t(x * 16 / w);
  emit(enc.Encode(f, false));
  std::vector<uint8_t> g = at(137);
  m = enc.FindMove(g);
  if (!m.rows) return 2;
  emit(enc.Encode(g, false, m));
  emit(enc.Encode(at(0), true));
  // 0.5.66+: a "window" (a block of the page) dragged twice: block moves found by FindBlockMove
  std::vector<uint8_t> base = at(0), win(size_t(220) * 140);
  for (int y = 0; y < 140; y++)
    for (int x = 0; x < 220; x++) win[size_t(y) * 220 + size_t(x)] = base[size_t(100 + y) * size_t(w) + size_t(60 + x)] ^ 5;
  for (int k = 1; k <= 2; k++) {
    std::vector<uint8_t> fr(size_t(w) * size_t(h), 2);
    for (int y = 0; y < 140; y++)
      for (int x = 0; x < 220; x++) fr[size_t(100 + 40 * k + y) * size_t(w) + size_t(60 + 70 * k + x)] = win[size_t(y) * 220 + size_t(x)];
    scouter_kit::g1::BlockMove b = k > 1 ? enc.FindBlockMove(fr) : scouter_kit::g1::BlockMove{};
    if (k > 1 && !b.w) return 3;
    emit(enc.Encode(fr, false, {}, SIZE_MAX, false, b));
  }
  return 0;
}
