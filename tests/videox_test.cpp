// PlaneVideoEncoder (library) against the VM core's VIDEOX decoding (src/vm): every record decodes to the
// frame that was encoded, plane by plane, with and without row moves; FindMove finds a scroll.
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <vector>

#include "../src/g1/scouter_kit_g1.hpp"
extern "C" {
#include "../src/vm/vm_gfx.h"
}

static int failures = 0;
#define CHECK(c) do { if (!(c)) { std::printf("FAIL line %d: %s\n", __LINE__, #c); failures++; } } while (0)

static void noop_span(void *, uint32_t, uint32_t, uint32_t, const uint8_t *) {}
static void noop_mark(void *, uint32_t) {}

// decodes a VIDEOX record into the arena's planes as the arm does (without the panel)
static int decode(std::vector<uint8_t> &a, uint32_t bmp, uint32_t stride, int w, int h, uint32_t lv, uint32_t src,
                  const std::vector<uint8_t> &rec, uint32_t st) {
  static vm_video vs;
  std::memcpy(a.data() + src, rec.data(), rec.size());
  const uint32_t pt = 0x3FE00, stt = 0x3FE40; // the tables: planes back to back from bmp, states from st
  for (uint32_t p = 0; p < 4; p++) {
    uint32_t pa = bmp + p * uint32_t(h) * stride, sa = st + p * scouter_kit::g1::kVideoState;
    for (int b = 0; b < 4; b++) {
      a[pt + 4 * p + uint32_t(b)] = uint8_t(pa >> (8 * b));
      a[stt + 4 * p + uint32_t(b)] = uint8_t(sa >> (8 * b));
    }
  }
  uint32_t v[10] = {pt, stride, uint32_t(w), uint32_t(h), 0, 0, lv, src, uint32_t(rec.size()), stt}, detail = 0;
  vm_videox x;
  int err = vm_videox_check(a.data(), v, &x, &detail);
  if (err) return err;
  if (x.flags & 1) vm_videox_move(a.data(), v, &x, noop_mark, nullptr);
  for (uint32_t p = 0; p < x.planes; p++) {
    uint32_t pv[10];
    vm_videox_plane(v, &x, p, pv);
    vm_video_decode(a.data(), pv, &vs, noop_span, nullptr);
  }
  return 0;
}

static bool planes_match(const std::vector<uint8_t> &a, uint32_t bmp, uint32_t stride, int w, int h, int n,
                         const std::vector<uint8_t> &idx) {
  for (int p = 0; p < n; p++)
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int bit = a[bmp + uint32_t(p) * uint32_t(h) * stride + uint32_t(y) * stride + uint32_t(x) / 8] >> (7 - x % 8) & 1;
        if (bit != (idx[size_t(y) * size_t(w) + size_t(x)] >> p & 1)) return false;
      }
  return true;
}

int main() {
  std::mt19937 rng(7);
  size_t total = 0, frames = 0, moved_bytes = 0, plain_bytes = 0;
  for (int it = 0; it < 40; it++) {
    int w = 8 + int(rng() % 200), h = 8 + int(rng() % 120), n = 1 + int(rng() % 4);
    if (it == 0) w = 640, h = 480, n = 2;
    scouter_kit::g1::PlaneVideoEncoder enc(w, h, n);
    std::vector<uint8_t> a(VM_ARENA_SIZE, 0);
    uint32_t stride = uint32_t(enc.Stride()), bmp = 0x100, st = bmp + uint32_t(n) * uint32_t(h) * stride + 4;
    uint32_t src = st + uint32_t(n) * scouter_kit::g1::kVideoState + 4, lv = 0x3FF00;
    for (int i = 0; i < 16; i++) a[lv + uint32_t(i)] = uint8_t(i);
    // a "document": text-like rows of runs, scrolled now and then
    std::vector<uint8_t> doc(size_t(w) * size_t(h) * 3);
    for (size_t r = 0; r < doc.size() / size_t(w); r++) {
      bool blank = rng() % 4 == 0;
      for (int x = 0; x < w; x++) doc[r * size_t(w) + size_t(x)] = blank ? 0 : uint8_t(((x / 3 + r) % 7 < 2) ? rng() % (1u << n) : 0);
    }
    int top = 0;
    for (int f = 0; f < 12; f++) {
      int want = f == 0 ? 0 : int(rng() % 3 == 0 ? rng() % 20 : 0), old_top = top;
      top = std::min(top + want, h * 2);
      int step = top - old_top;
      std::vector<uint8_t> idx(doc.begin() + std::ptrdiff_t(size_t(top) * size_t(w)),
                               doc.begin() + std::ptrdiff_t(size_t(top + h) * size_t(w)));
      if (rng() % 3 == 0) idx[rng() % idx.size()] ^= 1; // a small change too
      scouter_kit::g1::RowMove m = f ? enc.FindMove(idx) : scouter_kit::g1::RowMove{};
      if (step >= 1 && h - step >= 24) CHECK(m.rows > 0 && m.dy == -step); // enough overlap to be worth a move
      scouter_kit::g1::PlaneVideoEncoder copy = enc;
      std::vector<uint8_t> rec = enc.Encode(idx, f == 0, m);
      CHECK(!rec.empty());
      CHECK(decode(a, bmp, stride, w, h, lv, src, rec, st) == 0);
      CHECK(planes_match(a, bmp, stride, w, h, n, idx));
      if (m.rows) { // the same frame without the move costs more
        std::vector<uint8_t> plain = copy.Encode(idx, false, {});
        moved_bytes += rec.size();
        plain_bytes += plain.size();
      }
      total += rec.size();
      frames++;
    }
  }
  // bad moves are refused without changing anything
  scouter_kit::g1::PlaneVideoEncoder e(16, 16, 2);
  std::vector<uint8_t> z(256, 0);
  CHECK(e.Encode(z, false, {0, 16, 16}).empty());
  CHECK(e.Encode(z, false, {10, 10, 1}).empty());
  CHECK(!e.Encode(z, false, {0, 16, 3}).empty());
  std::printf("videox: %zu frames, %zu bytes; scrolled frames %zu bytes with moves vs %zu without\n", frames, total,
              moved_bytes, plain_bytes);
  std::printf("%d failures\n", failures);
  return failures != 0;
}
