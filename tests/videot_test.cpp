// VIDEOT (VM.md §3.9): the library's TransformVideoEncoder against the VM's decoder.
// - Round trip: after every record the arm's frame (vm_videot_decode) is exactly the encoder's Picture(), and the
//   bands it reports cover every pixel that changed; random bytes after the checks never touch memory outside the
//   frame, the record and the state.
// - The dither: the 2 x 2 panel pixels of a frame pixel average to it (within one 16-level step / 4).
// - End to end on emulated arms: a Program with VideoT draws the picture at twice its size.
// - Bytes and quality on moving synthetic video; with a clip (FILE.gray of 512 x 480 frames, half-sized here):
//   videot_test FILE.gray [FRAMES [Q]] prints bytes per frame and PSNR.
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <thread>
#include <vector>

#include "../src/g1/scouter_kit_g1.hpp"
extern "C" {
#include "../src/vm/vm_gfx.h"
}

namespace g1 = scouter_kit::g1;

static int failures = 0;
#define CHECK(c) do { if (!(c)) { std::printf("FAIL line %d: %s\n", __LINE__, #c); failures++; } } while (0)

struct Bands {
  std::vector<std::pair<uint32_t, uint32_t>> span; // per band: x0, x1 (0, 0 none)
};
static void on_band(void *c, uint32_t b, uint32_t x0, uint32_t x1) {
  auto *s = static_cast<Bands *>(c);
  if (s->span.size() <= b) s->span.resize(b + 1);
  s->span[b] = {x0, x1};
}

constexpr uint32_t kFrame = 0x1000, kSrc = 0x20000, kState = 0x30000;

// The record decoded as the arm would: true when it passed the checks.
static bool decode(std::vector<uint8_t> &a, int w, int h, const std::vector<uint8_t> &rec, Bands *bands) {
  std::memcpy(a.data() + kSrc, rec.data(), rec.size());
  uint32_t v[8] = {kFrame, uint32_t(w), uint32_t(h), 0, 0, kSrc, uint32_t(rec.size()), kState}, detail = 0;
  if (vm_videot_check(a.data(), v, &detail)) return false;
  vm_videot_decode(a.data(), v, on_band, bands);
  return true;
}

// Moving natural-ish video: a textured background panning, a bright disc crossing, noise.
static void synth(int w, int h, int t, std::mt19937 &rng, std::vector<uint8_t> &out) {
  out.resize(size_t(w) * size_t(h));
  std::normal_distribution<double> noise(0, 3);
  for (int y = 0; y < h; y++)
    for (int x = 0; x < w; x++) {
      double u = x + 2.0 * t, v = y + 0.7 * t;
      double bg = 110 + 50 * std::sin(u * 0.07) * std::cos(v * 0.05) + 30 * std::sin((u + v) * 0.21);
      double dx = x - (20 + 4.0 * t), dy = y - h / 2.0;
      if (dx * dx + dy * dy < 400) bg = 230 - 0.2 * (dx * dx + dy * dy) / 4;
      out[size_t(y) * size_t(w) + size_t(x)] = uint8_t(std::clamp(bg + noise(rng), 0.0, 255.0));
    }
}

static double psnr(const std::vector<uint8_t> &a, const std::vector<uint8_t> &b) {
  double se = 0;
  for (size_t i = 0; i < a.size(); i++) se += (double(a[i]) - b[i]) * (double(a[i]) - b[i]);
  return se == 0 ? 99 : 10 * std::log10(255.0 * 255.0 * double(a.size()) / se);
}

static void clip_report(const char *path, int frames, int q) {
  FILE *f = std::fopen(path, "rb");
  if (!f) {
    std::printf("no %s\n", path);
    failures++;
    return;
  }
  const int W = 256, H = 240;
  g1::TransformVideoEncoder enc(W, H);
  std::vector<uint8_t> full(512 * 480), luma(size_t(W) * H), a(VM_ARENA_SIZE);
  double bytes = 0, db = 0;
  int n = 0, first = 0;
  for (int t = 0; t < frames && std::fread(full.data(), 1, full.size(), f) == full.size(); t++) {
    for (int y = 0; y < H; y++)
      for (int x = 0; x < W; x++) {
        const uint8_t *p = &full[size_t(2 * y) * 512 + size_t(2 * x)];
        luma[size_t(y) * W + size_t(x)] = uint8_t((p[0] + p[1] + p[512] + p[513] + 2) / 4);
      }
    std::vector<uint8_t> rec = enc.Encode(luma, q);
    Bands b;
    CHECK(decode(a, W, H, rec, &b));
    CHECK(std::memcmp(a.data() + kFrame, enc.Picture().data(), luma.size()) == 0);
    if (t == 0) {
      first = int(rec.size());
      continue;
    }
    bytes += double(rec.size());
    db += psnr(luma, enc.Picture());
    n++;
  }
  std::fclose(f);
  if (n) std::printf("%s q %d: first %d B, then %.0f B a frame, %.1f dB (8-bit picture)\n", path, q, first, bytes / n, db / n);
}

int main(int argc, char **argv) {
  if (argc > 1) {
    clip_report(argv[1], argc > 2 ? std::atoi(argv[2]) : 60, argc > 3 ? std::atoi(argv[3]) : 12);
    std::printf("%d failures\n", failures);
    return failures ? 1 : 0;
  }
  std::mt19937 rng(7);
  std::vector<uint8_t> a(VM_ARENA_SIZE);

  // Round trip on synthetic video at three sizes and quantisers, every band checked.
  for (auto [w, h, q] : {std::tuple{256, 240, 12}, std::tuple{320, 240, 4}, std::tuple{64, 48, 40}}) {
    g1::TransformVideoEncoder enc(w, h);
    std::vector<uint8_t> luma, before(size_t(w) * size_t(h), 0);
    double bytes = 0, db = 0;
    int frames = 24, exact = 0, bands_ok = 0;
    std::fill(a.begin(), a.end(), 0);
    for (int t = 0; t < frames; t++) {
      synth(w, h, t, rng, luma);
      std::vector<uint8_t> rec = enc.Encode(luma, q, t == 0);
      CHECK(!rec.empty());
      std::memcpy(before.data(), a.data() + kFrame, before.size());
      Bands b;
      CHECK(decode(a, w, h, rec, &b));
      bool same = std::memcmp(a.data() + kFrame, enc.Picture().data(), before.size()) == 0;
      exact += same;
      // every changed pixel lies in its band's reported span
      bool covered = int(b.span.size()) == h / 8;
      for (int y = 0; y < h && covered; y++)
        for (int x = 0; x < w; x++)
          if (a[kFrame + size_t(y) * size_t(w) + size_t(x)] != before[size_t(y) * size_t(w) + size_t(x)] &&
              !(uint32_t(x) >= b.span[size_t(y / 8)].first && uint32_t(x) < b.span[size_t(y / 8)].second))
            covered = false;
      bands_ok += covered;
      if (t) bytes += double(rec.size()), db += psnr(luma, enc.Picture());
    }
    std::printf("videot %d x %d q %d: %d of %d frames exact, %d bands right; %.0f B a frame, %.1f dB\n", w, h, q, exact,
                frames, bands_ok, bytes / (frames - 1), db / (frames - 1));
    CHECK(exact == frames);
    CHECK(bands_ok == frames);
    CHECK(db / (frames - 1) > (q < 20 ? 34 : 26));
  }

  // A still picture again: every block skipped, a tiny record, no band drawn.
  {
    g1::TransformVideoEncoder enc(128, 96);
    std::vector<uint8_t> luma;
    synth(128, 96, 3, rng, luma);
    enc.Encode(luma, 10);
    std::fill(a.begin(), a.end(), 0);
    Bands b;
    std::vector<uint8_t> first = enc.Encode(luma, 10); // the decoder's frame: the second record over the first
    g1::TransformVideoEncoder enc2(128, 96);
    std::vector<uint8_t> r1 = enc2.Encode(luma, 10), r2 = enc2.Encode(luma, 10), r3 = enc2.Encode(luma, 10);
    CHECK(decode(a, 128, 96, r1, &b));
    CHECK(decode(a, 128, 96, r2, &b));
    b.span.clear();
    CHECK(decode(a, 128, 96, r3, &b));
    int drawn = 0;
    for (auto &s : b.span) drawn += s.second > s.first;
    std::printf("videot: the same picture again: %zu bytes, %d bands drawn\n", r3.size(), drawn);
    CHECK(r3.size() <= 8);
    CHECK(drawn == 0);
    (void)first;
  }

  // The checks: bad sizes, overlaps, flags, q; nothing changes.
  {
    std::fill(a.begin(), a.end(), 0);
    a[kSrc] = 0;
    a[kSrc + 1] = 10;
    auto check = [&](uint32_t fr, uint32_t w, uint32_t h, uint32_t src, uint32_t n, uint32_t st) {
      uint32_t v[8] = {fr, w, h, 0, 0, src, n, st}, d = 0;
      return vm_videot_check(a.data(), v, &d);
    };
    CHECK(check(kFrame, 64, 64, kSrc, 2, kState) == 0);
    CHECK(check(kFrame, 60, 64, kSrc, 2, kState) == 5);
    CHECK(check(kFrame, 328, 64, kSrc, 2, kState) == 5);
    CHECK(check(kFrame, 64, 248, kSrc, 2, kState) == 5);
    CHECK(check(kFrame, 64, 0, kSrc, 2, kState) == 5);
    CHECK(check(kSrc - 100, 64, 64, kSrc, 2, kState) == 3);            // the frame over the record
    CHECK(check(kFrame, 64, 64, kSrc, 2, kFrame + 4000) == 3);          // the state over the frame
    CHECK(check(kFrame, 64, 64, kSrc, 2, VM_ARENA_SIZE - 100) == 3);    // the state past the arena
    CHECK(check(VM_ARENA_SIZE - 64 * 63, 64, 64, kSrc, 2, kState) == 3); // the frame past the arena
    CHECK(check(kFrame, 64, 64, kSrc, 1, kState) == 4);
    a[kSrc] = 8;
    CHECK(check(kFrame, 64, 64, kSrc, 2, kState) == 4);
    a[kSrc] = 0;
    a[kSrc + 1] = 0;
    CHECK(check(kFrame, 64, 64, kSrc, 2, kState) == 4);
    a[kSrc + 1] = 64;
    CHECK(check(kFrame, 64, 64, kSrc, 2, kState) == 4);
  }

  // Random records (valid headers, random bytes): only the frame and the state change.
  {
    std::vector<uint8_t> guard(VM_ARENA_SIZE);
    int runs = 3000;
    bool outside = false;
    for (int i = 0; i < runs && !outside; i++) {
      uint32_t w = 8 * (1 + rng() % 40), h = 8 * (1 + rng() % 30), n = 2 + rng() % 600;
      for (auto &x : a) x = uint8_t(rng());
      a[kSrc] = uint8_t(rng() % 8);
      a[kSrc + 1] = uint8_t(1 + rng() % 63);
      guard = a;
      uint32_t v[8] = {kFrame, w, h, uint32_t(rng()), uint32_t(rng()), kSrc, n, kState}, d = 0;
      if (vm_videot_check(a.data(), v, &d)) continue;
      Bands b;
      vm_videot_decode(a.data(), v, on_band, &b);
      for (size_t k = 0; k < a.size() && !outside; k++) {
        bool frame = k >= kFrame && k < kFrame + size_t(w) * h, state = k >= kState && k < kState + VM_VIDEOT_STATE;
        if (!frame && !state && a[k] != guard[k]) outside = true;
      }
    }
    std::printf("videot: %d random records, %s\n", runs, outside ? "a WRITE OUTSIDE" : "no write outside frame and state");
    CHECK(!outside);
  }

  // The dither: a pixel's 2 x 2 panel pixels average to it.
  {
    uint32_t v[8] = {kFrame, 8, 8, 0, 0, kSrc, 2, kState};
    std::fill(a.begin(), a.end(), 0);
    a[kSrc] = 7; // reset, redraw, flat: an all-grey-128 frame and the dither table
    a[kSrc + 1] = 1;
    Bands b;
    vm_videot_decode(a.data(), v, on_band, &b);
    double worst = 0;
    for (int val = 0; val < 256; val++) {
      a[kFrame] = uint8_t(val);
      uint8_t r0, r1;
      vm_videot_row(a.data(), v, 0, 0, 0, 1, &r0);
      vm_videot_row(a.data(), v, 0, 1, 0, 1, &r1);
      double avg = ((r0 >> 4) + (r0 & 15) + (r1 >> 4) + (r1 & 15)) * 17 / 4.0;
      worst = std::max(worst, std::fabs(avg - val));
    }
    std::printf("videot: dither, worst error %.1f of 255 (a 16-level step is 17)\n", worst);
    CHECK(worst <= 17 / 4.0 + 0.5);
  }

  // End to end: emulated arms (g1v0 0.5.68) run a Program with VIDEOT; the panel shows the picture doubled.
  {
    g1::emulator::SetFirmware(5, 68);
    std::string error;
    g1::GrayConfig gray{};
    CHECK(g1::Init(&gray, &error, nullptr) == g1::ERROR_NONE);
    for (int arm = 0; arm < 2; arm++) {
      g1::emulator::SetProperty(arm, g1::PROP_WORN, 1);
      g1::emulator::SetProperty(arm, g1::PROP_IN_CASE, 0);
    }
    const int w = 96, h = 64, px = 100, py = 60;
    g1::TransformVideoEncoder enc(w, h);
    int64_t fr = g1::Alloc(uint32_t(w * h)), st = g1::Alloc(g1::TransformVideoEncoder::kState),
            src = g1::Alloc(16384), code = g1::Alloc(256);
    CHECK(fr >= 0 && st >= 0 && src >= 0 && code >= 0);
    std::vector<uint8_t> luma;
    int wrong = 0;
    for (int t = 0; t < 4; t++) {
      synth(w, h, t, rng, luma);
      std::vector<uint8_t> rec = enc.Encode(luma, 8, t == 0);
      g1::Program p(g1::Address(code), 12);
      g1::Address at = p.Here();
      p.VideoT(p.Const(uint32_t(fr)), p.Const(w), p.Const(h), p.Const(px), p.Const(py), p.Const(uint32_t(src)),
               p.Const(uint32_t(rec.size())), p.Const(uint32_t(st)));
      p.Ret();
      for (g1::Arm *arm : {&g1::left, &g1::right}) {
        arm->WriteArena(g1::Address(src), rec);
        CHECK(arm->Load(p));
        arm->Call(at);
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(400));
    }
    const uint8_t *pl = g1::emulator::Panel(0);
    const std::vector<uint8_t> &pic = enc.Picture();
    for (int y = 0; y < 2 * h; y++)
      for (int x = 0; x < 2 * w; x++) {
        int k = y & 1, s = x & 1, v = pic[size_t(y / 2) * w + size_t(x / 2)];
        static const int bayer[4] = {0, 2, 3, 1};
        int want = std::min(15, (v * 60 + 255 * bayer[2 * k + s] + 127) / 1020);
        wrong += pl[size_t(py + y) * 640 + size_t(px + x)] != want;
      }
    std::printf("videot on emulated arms: %d of %d panel pixels differ from the picture doubled and dithered\n", wrong,
                4 * w * h);
    CHECK(wrong == 0);
    g1::Deinit();
  }

  std::printf("%d failures\n", failures);
  return failures ? 1 : 0;
}
