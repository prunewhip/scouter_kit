#include "screen.hpp"

#include <algorithm>
#include <bit>
#include <cmath>
#include <cstdlib>
#include <cstring>

#ifdef __ANDROID__
#include <android/log.h>
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "scouter", __VA_ARGS__)
#else
#include <cstdio>
#define LOG(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif

namespace g1 = scouter_kit::g1;
namespace sk = scouter_kit;

namespace ssh {

namespace {
constexpr uint32_t kCode = 2048;       // the VIDEO path's stored code (table, pool, the decode and the cursor)
constexpr uint32_t kPtrWords = 16;     // the cursor's words: X, Y, On (the host's), then its own
constexpr uint32_t kRecordMax = 65536; // the largest record sent; a larger change waits for a smaller one
constexpr uint32_t kCodeX = 768;        // the VIDEOX path's stored code (table, pool, three calls)
constexpr uint32_t kRecordMaxX = 49152; // VIDEOX's largest record (its planes take most of the arena)
constexpr int64_t kMotionHoldUs = 300000; // auto greys: the motion mode lasts this long after the last moving frame
constexpr size_t kMotionBudget = 4096;    // ... and a moving frame's record starts this small (about 10 fps)
constexpr size_t kTuneMin = 1536, kTuneMax = 8192; // the self-tuned budget's range
// The motion stream (0.5.64's pixel coding at half size, scale 2): measured through this mirror on real video it
// saved only ~9% on camera footage and cost ~1.8x on cartoons against the top-planes trick, so it is off until the
// encoder picks the smaller coding per frame.
constexpr bool kMotionStream = false;
constexpr uint32_t kHeader = 12;       // due time, frame number, record size
constexpr uint32_t kHeaderT = 28;      // VIDEOT's: the same, then its w, h and panel x, y
constexpr int kQueued = 4;             // commands not yet sent on an arm's link: more, and a frame is skipped

std::mutex g_spare_mutex;
std::function<void()> g_spare; // SetSpare

// Arena for the mirror: what others keep for later first (the terminal's fonts), then every font and picture.
void Spare() {
  std::function<void()> release;
  {
    std::lock_guard<std::mutex> lock(g_spare_mutex);
    release = g_spare;
  }
  if (release) release();
  sk::UnloadAll();
}

void Put32(std::vector<uint8_t> &v, size_t at, uint32_t x) {
  v[at] = uint8_t(x);
  v[at + 1] = uint8_t(x >> 8);
  v[at + 2] = uint8_t(x >> 16);
  v[at + 3] = uint8_t(x >> 24);
}
} // namespace

void SetSpare(std::function<void()> release) {
  std::lock_guard<std::mutex> lock(g_spare_mutex);
  g_spare = std::move(release);
}

void Luma(const uint8_t *rgba, int w, int h, int stride, std::vector<uint8_t> *out) {
  out->resize(size_t(w) * h);
  uint8_t *o = out->data();
  for (int y = 0; y < h; y++) {
    const uint8_t *p = rgba + size_t(y) * stride;
    for (int x = 0; x < w; x++, p += 4) *o++ = uint8_t((p[0] * 77 + p[1] * 150 + p[2] * 29) >> 8);
  }
}

void Rotate(const std::vector<uint8_t> &in, int w, int h, int degrees, std::vector<uint8_t> *out, int *ow, int *oh) {
  int d = ((degrees % 360) + 360) % 360;
  int W = (d == 90 || d == 270) ? h : w, H = (d == 90 || d == 270) ? w : h;
  *ow = W;
  *oh = H;
  out->resize(size_t(w) * h);
  uint8_t *o = out->data();
  for (int y = 0; y < H; y++) {
    uint8_t *row = o + size_t(y) * W;
    switch (d) {
    case 90:
      for (int x = 0; x < W; x++) row[x] = in[size_t(h - 1 - x) * w + y];
      break;
    case 180:
      for (int x = 0; x < W; x++) row[x] = in[size_t(h - 1 - y) * w + (w - 1 - x)];
      break;
    case 270:
      for (int x = 0; x < W; x++) row[x] = in[size_t(x) * w + (w - 1 - y)];
      break;
    default:
      std::copy_n(in.data() + size_t(y) * w, w, row);
    }
  }
}

Rect Place(int pw, int ph, const ScreenOptions &o) {
  int w = std::min(pw, std::max(8, std::clamp(o.out_w, 1, 100) * pw / 100));
  int h = std::min(ph, std::max(8, std::clamp(o.out_h, 1, 100) * ph / 100));
  int x = std::clamp(std::clamp(o.out_x, 0, 100) * pw / 100, 0, pw - w);
  int y = std::clamp(std::clamp(o.out_y, 0, 100) * ph / 100, 0, ph - h);
  return {x, y, w, h};
}

Rect Source(int sw, int sh, int dw, int dh, const ScreenOptions &o) {
  if (o.src_w > 0 && o.src_h > 0) { // an exact window (the head-turn view)
    int rw = std::min(o.src_w, sw), rh = std::min(o.src_h, sh);
    return {std::clamp(o.src_x, 0, sw - rw), std::clamp(o.src_y, 0, sh - rh), rw, rh};
  }
  int rw = sw, rh = sh;
  if (o.fit == ScreenOptions::kFill && dw > 0 && dh > 0) {
    if (int64_t(sw) * dh > int64_t(sh) * dw) rw = std::max(1, int(int64_t(sh) * dw / dh));
    else rh = std::max(1, int(int64_t(sw) * dh / dw));
  }
  if (o.zoom > 100) {
    rw = std::max(1, int(int64_t(rw) * 100 / o.zoom));
    rh = std::max(1, int(int64_t(rh) * 100 / o.zoom));
  }
  int px = std::clamp(o.pan_x, 0, 100), py = std::clamp(o.pan_y, 0, 100);
  return {int(int64_t(sw - rw) * px / 100), int(int64_t(sh - rh) * py / 100), rw, rh};
}

std::vector<uint8_t> ArrowMasks() {
  const float poly[][2] = {{1, 1}, {1, 16}, {4.6f, 12.8f}, {7.2f, 18.4f}, {9.6f, 17.3f}, {7.1f, 11.9f}, {12, 11.9f}};
  const int np = int(std::size(poly));
  auto inside = [&](float x, float y) {
    bool in = false;
    for (int a = 0, b = np - 1; a < np; b = a++) {
      if ((poly[a][1] > y) != (poly[b][1] > y) &&
          x < (poly[b][0] - poly[a][0]) * (y - poly[a][1]) / (poly[b][1] - poly[a][1]) + poly[a][0])
        in = !in;
    }
    return in;
  };
  bool body[kArrowH][kArrowW] = {};
  for (int y = 0; y < kArrowH; y++)
    for (int x = 0; x < kArrowW; x++) body[y][x] = inside(x + 0.5f, y + 0.5f);
  std::vector<uint8_t> out(size_t(2 * 2 * kArrowH), 0);
  for (int y = 0; y < kArrowH; y++)
    for (int x = 0; x < kArrowW; x++) {
      bool edge = false;
      for (int dy = -1; dy <= 1; dy++)
        for (int dx = -1; dx <= 1; dx++) {
          int yy = y + dy, xx = x + dx;
          if (yy >= 0 && yy < kArrowH && xx >= 0 && xx < kArrowW && body[yy][xx]) edge = true;
        }
      if (body[y][x]) out[size_t(y * 2 + x / 8)] |= uint8_t(0x80 >> (x % 8));
      if (edge) out[size_t(2 * kArrowH + y * 2 + x / 8)] |= uint8_t(0x80 >> (x % 8));
    }
  return out;
}

namespace {
// Base's geometry for a w x h screen: the rows cut from its top, the cut screen's size, the turn, the shown part
// (in the turned screen's pixels) and where it goes on the picture (as Fit places it).
struct Geo {
  int w = 0, h = 0, top = 0, rot = 0;
  Rect r, out;
};
Geo Geometry(int w, int h, const ScreenOptions &o, int pw, int ph) {
  Geo g;
  g.w = w;
  g.h = h;
  int top = h * std::clamp(o.crop_top, 0, 400) / 1000, bottom = h * std::clamp(o.crop_bottom, 0, 400) / 1000;
  if (top + bottom > 0 && h - top - bottom >= 16) {
    g.top = top;
    g.h = h - top - bottom;
  }
  g.rot = ((o.rotate % 360) + 360) % 360;
  if (g.rot != 90 && g.rot != 180 && g.rot != 270) g.rot = 0;
  int sw = g.rot % 180 ? g.h : g.w, sh = g.rot % 180 ? g.w : g.h;
  Rect d = Place(pw, ph, o);
  g.r = Source(sw, sh, d.w, d.h, o);
  g.out = d;
  if (o.fit == ScreenOptions::kFit && g.r.w > 0 && g.r.h > 0) {
    double k = std::min(double(d.w) / g.r.w, double(d.h) / g.r.h);
    g.out.w = std::clamp(int(g.r.w * k), 1, d.w);
    g.out.h = std::clamp(int(g.r.h * k), 1, d.h);
    g.out.x = d.x + (d.w - g.out.w) / 2;
    g.out.y = d.y + (d.h - g.out.h) / 2;
  }
  return g;
}
} // namespace

bool Locate(int cx, int cy, int w, int h, const ScreenOptions &o, int pw, int ph, int *px, int *py) {
  if (w <= 1 || h <= 1 || pw <= 0 || ph <= 0) return false;
  Geo g = Geometry(w, h, o, pw, ph);
  if (g.r.w <= 0 || g.r.h <= 0) return false;
  // As Cursor places the tip: whole pixels of the screen, then the cut rows.
  double x = double(int64_t(std::clamp(cx, 0, 10000)) * (w - 1) / 10000);
  double y = double(int64_t(std::clamp(cy, 0, 10000)) * (h - 1) / 10000) - g.top;
  double rx = x, ry = y;
  switch (g.rot) {
  case 90: rx = g.h - 1 - y, ry = x; break;
  case 180: rx = g.w - 1 - x, ry = g.h - 1 - y; break;
  case 270: rx = y, ry = g.w - 1 - x; break;
  }
  double fx = (rx + 0.5 - g.r.x) * g.out.w / g.r.w, fy = (ry + 0.5 - g.r.y) * g.out.h / g.r.h;
  *px = g.out.x + int(std::floor(fx));
  *py = g.out.y + int(std::floor(fy));
  return fx >= 0 && fx < g.out.w && fy >= 0 && fy < g.out.h;
}

Rect Shows(int w, int h, const ScreenOptions &o, int pw, int ph, int *ow, int *oh, int *ox, int *oy) {
  *ow = *oh = 0;
  if (w <= 1 || h <= 1 || pw <= 0 || ph <= 0) return {};
  Geo g = Geometry(w, h, o, pw, ph);
  if (g.r.w <= 0 || g.r.h <= 0) return {};
  *ow = g.out.w;
  *oh = g.out.h;
  if (ox) *ox = g.out.x;
  if (oy) *oy = g.out.y;
  // The shown part's corners back on the (uncut, unturned) screen.
  auto back = [&](double rx, double ry, double *x, double *y) {
    switch (g.rot) {
    case 90: *x = ry, *y = g.h - 1 - rx; break;
    case 180: *x = g.w - 1 - rx, *y = g.h - 1 - ry; break;
    case 270: *x = g.w - 1 - ry, *y = rx; break;
    default: *x = rx, *y = ry;
    }
    *y += g.top;
  };
  double ax, ay, bx, by;
  back(g.r.x, g.r.y, &ax, &ay);
  back(g.r.x + g.r.w - 1, g.r.y + g.r.h - 1, &bx, &by);
  auto fx = [&](double v) { return std::clamp(int(std::lround(v * 10000 / (w - 1))), 0, 10000); };
  auto fy = [&](double v) { return std::clamp(int(std::lround(v * 10000 / (h - 1))), 0, 10000); };
  int x0 = fx(std::min(ax, bx)), x1 = fx(std::max(ax, bx)), y0 = fy(std::min(ay, by)), y1 = fy(std::max(ay, by));
  return {x0, y0, x1 - x0, y1 - y0};
}

Rect Fit(std::vector<uint8_t> *dst, int pw, int ph, const std::vector<uint8_t> &src, int sw, Rect r, Rect d, bool keep) {
  dst->assign(size_t(pw) * ph, 0);
  if (r.w <= 0 || r.h <= 0 || d.w <= 0 || d.h <= 0) return {};
  int ow = d.w, oh = d.h, ox = d.x, oy = d.y;
  if (keep) {
    double k = std::min(double(d.w) / r.w, double(d.h) / r.h);
    ow = std::clamp(int(r.w * k), 1, d.w);
    oh = std::clamp(int(r.h * k), 1, d.h);
    ox = d.x + (d.w - ow) / 2;
    oy = d.y + (d.h - oh) / 2;
  }
  // The source columns under each picture column, worked out once.
  std::vector<int> x0s(size_t(ow), 0), x1s(size_t(ow), 0);
  for (int x = 0; x < ow; x++) {
    x0s[size_t(x)] = r.x + int(int64_t(x) * r.w / ow);
    x1s[size_t(x)] = std::max(r.x + int(int64_t(x + 1) * r.w / ow), x0s[size_t(x)] + 1);
  }
  for (int y = 0; y < oh; y++) {
    int y0 = r.y + int(int64_t(y) * r.h / oh);
    int y1 = std::max(r.y + int(int64_t(y + 1) * r.h / oh), y0 + 1);
    uint8_t *out = dst->data() + size_t(y + oy) * pw + ox;
    for (int x = 0; x < ow; x++) {
      int x0 = x0s[size_t(x)], x1 = x1s[size_t(x)];
      int sum = 0;
      for (int yy = y0; yy < y1; yy++) {
        const uint8_t *row = src.data() + size_t(yy) * sw;
        for (int xx = x0; xx < x1; xx++) sum += row[xx];
      }
      out[x] = uint8_t(sum / ((y1 - y0) * (x1 - x0)));
    }
  }
  return {ox, oy, ow, oh};
}

void Stretch(std::vector<uint8_t> *p, int pw, Rect c, int amount) {
  if (amount <= 0 || c.w <= 0 || c.h <= 0) return;
  size_t hist[256] = {};
  for (int y = c.y; y < c.y + c.h; y++) {
    const uint8_t *row = p->data() + size_t(y) * pw + c.x;
    for (int x = 0; x < c.w; x++) hist[row[x]]++;
  }
  size_t n = size_t(c.w) * c.h, seen = 0;
  int lo = 0, hi = 255;
  for (int v = 0; v < 256; v++) {
    seen += hist[v];
    if (seen > n / 50) {
      lo = v;
      break;
    }
  }
  seen = 0;
  for (int v = 0; v < 256; v++) {
    seen += hist[v];
    if (seen >= n - n / 50) {
      hi = v;
      break;
    }
  }
  // A page's background (the commonest grey, a quarter of the picture or more) goes to the end it is near: a dark
  // theme's grey to black (unlit on the glasses), a light page's to white (black once inverted), the text the other
  // way; antialiased text keeps its in-between greys.
  int mode = int(std::max_element(hist, hist + 256) - hist);
  if (hist[mode] * 4 >= n) {
    if (mode < 128) lo = std::max(lo, std::min(mode + 8, 255));
    else hi = std::min(hi, std::max(mode - 8, 0));
  }
  if (hi - lo < 16) return; // a flat picture: nothing to stretch
  int a = std::min(amount, 100);
  uint8_t lut[256];
  for (int v = 0; v < 256; v++) {
    int s = std::clamp((v - lo) * 255 / (hi - lo), 0, 255);
    lut[v] = uint8_t(v + (s - v) * a / 100);
  }
  for (int y = c.y; y < c.y + c.h; y++) {
    uint8_t *row = p->data() + size_t(y) * pw + c.x;
    for (int x = 0; x < c.w; x++) row[x] = lut[row[x]];
  }
}

void Sharpen(std::vector<uint8_t> *p, int pw, Rect c) {
  if (c.w < 3 || c.h < 3) return;
  std::vector<uint8_t> src(*p);
  for (int y = c.y + 1; y < c.y + c.h - 1; y++) {
    const uint8_t *up = &src[size_t(y - 1) * pw], *mid = &src[size_t(y) * pw], *down = &src[size_t(y + 1) * pw];
    uint8_t *out = p->data() + size_t(y) * pw;
    for (int x = c.x + 1; x < c.x + c.w - 1; x++) {
      int sum = up[x - 1] + up[x] + up[x + 1] + mid[x - 1] + mid[x] + mid[x + 1] + down[x - 1] + down[x] + down[x + 1];
      out[x] = uint8_t(std::clamp(2 * mid[x] - sum / 9, 0, 255));
    }
  }
}

void Tone(std::vector<uint8_t> *p, int pw, Rect c, int invert, int gamma, int threshold, int levels, bool dither,
          int cell) {
  int a = std::clamp(invert, 0, 100);
  double g = std::clamp(gamma, 20, 500) / 100.0;
  int n = (levels == 4 || levels == 8 ? levels : 16) - 1; // steps between black and full
  cell = std::max(1, cell);
  uint8_t lut[256];
  for (int v = 0; v < 256; v++) {
    int f = v + (255 - 2 * v) * a / 100; // a blend toward the inverse
    int q = g == 1.0 ? f : int(std::pow(f / 255.0, g) * 255.0 + 0.5);
    int k = (q * n + 127) / 255;
    lut[v] = levels == 2 ? (q >= threshold ? 255 : 0) : uint8_t((k * 15 + n / 2) / n * 17);
  }
  if (levels == 2 && dither) {
    // The cut-off moves with a 4 x 4 Bayer matrix (fixed in place, so a still picture stays still): greys come
    // out as patterns. The levels before the cut-off are the toned ones (lut without the cut).
    static const int kBayer[4][4] = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};
    uint8_t tone[256];
    for (int v = 0; v < 256; v++) {
      int f = v + (255 - 2 * v) * a / 100;
      tone[v] = uint8_t(g == 1.0 ? f : int(std::pow(f / 255.0, g) * 255.0 + 0.5));
    }
    int shift = threshold - 128; // the cut-off still biases light or dark
    for (int y = c.y; y < c.y + c.h; y++) {
      uint8_t *row = p->data() + size_t(y) * pw + c.x;
      for (int x = 0; x < c.w; x++) {
        int cut = kBayer[((y - c.y) / cell) & 3][(x / cell) & 3] * 16 + 8 + shift;
        row[x] = tone[row[x]] >= cut ? 255 : 0;
      }
    }
    return;
  }
  for (int y = c.y; y < c.y + c.h; y++) {
    uint8_t *row = p->data() + size_t(y) * pw + c.x;
    for (int x = 0; x < c.w; x++) row[x] = lut[row[x]];
  }
}

void Pixelate(std::vector<uint8_t> *p, int pw, Rect c, int n) {
  if (n <= 1) return;
  uint8_t *d = p->data();
  for (int by = c.y; by < c.y + c.h; by += n) {
    int bh = std::min(n, c.y + c.h - by);
    for (int bx = c.x; bx < c.x + c.w; bx += n) {
      int bw = std::min(n, c.x + c.w - bx), sum = 0;
      for (int y = by; y < by + bh; y++)
        for (int x = bx; x < bx + bw; x++) sum += d[size_t(y) * pw + x];
      uint8_t v = uint8_t(sum / (bw * bh));
      for (int y = by; y < by + bh; y++) std::memset(d + size_t(y) * pw + bx, v, size_t(bw));
    }
  }
}

// The pointer (o.cursor_*) on a w x h luma picture: an arrow about 13 of the glasses' pixels tall, its tip at
// the spot, white with a black rim (one of them lit, whatever the picture's inversion).
void Cursor(std::vector<uint8_t> *luma, int w, int h, const ScreenOptions &o, int pw, int ph) {
  if (o.cursor_x < 0 || o.cursor_y < 0 || w <= 0 || h <= 0 || pw <= 0 || ph <= 0) return;
  float per = std::max(float(w) / float(pw), float(h) / float(ph)) * 100.0f / float(std::max(100, o.zoom));
  float size = 13.0f * std::max(per, 0.25f); // the arrow's height, phone pixels
  float rim = std::max(1.0f, 1.3f * per);
  int tx = int(int64_t(std::clamp(o.cursor_x, 0, 10000)) * (w - 1) / 10000);
  int ty = int(int64_t(std::clamp(o.cursor_y, 0, 10000)) * (h - 1) / 10000);
  // The arrow: the tip, straight down its left side, back up to a point right of it (units of size).
  int x0 = std::max(0, int(tx - rim - 1)), x1 = std::min(w - 1, int(tx + 0.72f * size + rim + 1));
  int y0 = std::max(0, int(ty - rim - 1)), y1 = std::min(h - 1, int(ty + size + rim + 1));
  const float k = 0.7071f;
  for (int y = y0; y <= y1; y++) {
    for (int x = x0; x <= x1; x++) {
      float u = float(x - tx), v = float(y - ty);
      float d1 = u;                                         // its left side
      float d2 = (v - u) * k;                               // the side from the tip to the right point
      float d3 = -(0.3f * u + 0.7f * (v - size)) / 0.7616f; // the side from the bottom to the right point
      float d = std::min({d1, d2, d3});
      if (d < -rim) continue;
      (*luma)[size_t(y) * size_t(w) + size_t(x)] = d >= rim ? 255 : 0;
    }
  }
}

Rect Base(const uint8_t *rgba, int w, int h, int stride, const ScreenOptions &o, int pw, int ph,
          std::vector<uint8_t> *luma, std::vector<uint8_t> *rot, std::vector<uint8_t> *out) {
  Luma(rgba, w, h, stride, luma);
  Cursor(luma, w, h, o, pw, ph);
  // The bars cut away (after the cursor, which is placed on the whole screen).
  int top = h * std::clamp(o.crop_top, 0, 400) / 1000, bottom = h * std::clamp(o.crop_bottom, 0, 400) / 1000;
  if (top + bottom > 0 && h - top - bottom >= 16) {
    luma->erase(luma->begin() + ptrdiff_t(size_t(h - bottom) * size_t(w)), luma->end());
    luma->erase(luma->begin(), luma->begin() + ptrdiff_t(size_t(top) * size_t(w)));
    h -= top + bottom;
  }
  int sw = w, sh = h;
  const std::vector<uint8_t> *src = luma;
  if (o.rotate % 360 != 0) {
    Rotate(*luma, w, h, o.rotate, rot, &sw, &sh);
    src = rot;
  }
  Rect d = Place(pw, ph, o);
  Rect c = Fit(out, pw, ph, *src, sw, Source(sw, sh, d.w, d.h, o), d, o.fit == ScreenOptions::kFit);
  Stretch(out, pw, c, o.contrast);
  if (o.sharpen) Sharpen(out, pw, c);
  return c;
}

void Finish(const std::vector<uint8_t> &base, int pw, Rect c, const ScreenOptions &o, int n, std::vector<uint8_t> *out) {
  *out = base;
  n = std::clamp(n, 1, 4);
  Pixelate(out, pw, c, n);
  Tone(out, pw, c, o.invert, o.gamma, o.threshold, o.levels, o.dither, n);
  if (o.levels == 2 && o.clean && !o.dither && n == 1) Despeckle(out, pw, c);
}

void Despeckle(std::vector<uint8_t> *p, int pw, Rect c) {
  if (c.w < 3 || c.h < 3) return;
  std::vector<uint8_t> in(*p);
  auto at = [&](int x, int y) {
    return x >= c.x && x < c.x + c.w && y >= c.y && y < c.y + c.h && in[size_t(y) * size_t(pw) + size_t(x)] != 0;
  };
  for (int y = c.y; y < c.y + c.h; y++)
    for (int x = c.x; x < c.x + c.w; x++) {
      uint8_t &v = (*p)[size_t(y) * size_t(pw) + size_t(x)];
      if (v) {
        bool alone = true;
        for (int dy = -1; dy <= 1 && alone; dy++)
          for (int dx = -1; dx <= 1; dx++)
            if ((dx || dy) && at(x + dx, y + dy)) {
              alone = false;
              break;
            }
        if (alone) v = 0;
      } else if (at(x - 1, y) && at(x + 1, y) && at(x, y - 1) && at(x, y + 1)) {
        v = 255;
      }
    }
}

void Picture(const uint8_t *rgba, int w, int h, int stride, const ScreenOptions &o, int pw, int ph,
             std::vector<uint8_t> *out) {
  std::vector<uint8_t> luma, rot, base;
  Rect c = Base(rgba, w, h, stride, o, pw, ph, &luma, &rot, &base);
  Finish(base, pw, c, o, std::max(1, o.detail), out);
}

void PictureSize(int dx, int dy, int *pw, int *ph) {
  *pw = std::max(2, (640 - std::min(std::abs(dx), 600)) & ~1);
  *ph = std::max(2, 480 - std::min(std::abs(dy), 400));
}

ScreenMirror::~ScreenMirror() { Stop(); }

// 16 greys, steady: in a row that barely changed (at most 1 pixel in 16 by more than one level), a pixel changed by
// one level keeps its old one: a camera's or a video's noise costs nothing. A row with real change (new text, a
// scroll, another screen) is taken exactly, so nothing of the last screen stays behind.
void Steady(std::vector<uint8_t> *frame, const std::vector<uint8_t> &prev, int w, int h) {
  for (int y = 0; y < h; y++) {
    uint8_t *f = frame->data() + size_t(y) * size_t(w);
    const uint8_t *p = prev.data() + size_t(y) * size_t(w);
    int big = 0;
    for (int x = 0; x < w; x++) big += std::abs(int(f[x]) - int(p[x])) > 17;
    if (big * 16 > w) continue;
    for (int x = 0; x < w; x++)
      if (std::abs(int(f[x]) - int(p[x])) <= 17) f[x] = p[x];
  }
}

// frame_ at detail n. Black and white, steady: a pixel keeps what it showed unless it is clearly past the threshold
// (16 levels either way), against the last frame sent at the same detail.
void ScreenMirror::Make(const ScreenOptions &o, Rect c, int n) {
  Finish(base_, w_, c, o, n, &frame_);
  if (o.levels != 2 || !o.steady || o.dither || video_.redraw || prev_.size() != frame_.size() || prev_detail_ != n) return;
  ScreenOptions lo = o, hi = o;
  lo.threshold = std::max(1, o.threshold - 16);
  hi.threshold = std::min(254, o.threshold + 16);
  Finish(base_, w_, c, lo, n, &lo_); // lights from here: stays lit
  Finish(base_, w_, c, hi, n, &hi_); // and from here: comes on
  for (size_t i = 0; i < frame_.size(); i++) frame_[i] = prev_[i] ? lo_[i] : hi_[i];
}

void ScreenMirror::Panels(bool on) {
  for (g1::Arm *a : {&g1::left, &g1::right})
    if (a->GetState().connected) a->SetPanel(on);
}

bool ScreenMirror::Start(int dx, int dy, std::string *error, bool keep_panels) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (started_) return true;
  int dw = sk::gray::display.width, dh = sk::gray::display.height;
  if (dw <= 0 || dh <= 0) {
    if (error) *error = "the glasses are not connected";
    return false;
  }
  dx_ = dx;
  dy_ = dy;
  int adx = std::abs(dx);
  w_ = std::min(640 - adx, dw) & ~1; // even: 4-bpp bytes
  h_ = dh;
  x_ = std::clamp(adx, 0, dw - w_);
  y_ = 0;
  // The library's picture of the display goes black, then the panels power-cycle: that clears what the
  // terminal's program drew there, which the library never saw.
  int64_t t0 = g1::NowUs();
  sk::gray::Clear();
  sk::Flush();
  if (keep_panels) {
    BlackSides();
  } else {
    Panels(false);
    Panels(true);
  }
  start_us_ = g1::NowUs();
  first_logged_ = false;
  LOG("screen: panels cleared in %lld ms", (long long)((start_us_ - t0) / 1000));
  levels_ = 0;
  prev_.clear();
  last_base_.clear();
  hidden_ = false;
  paused_ = false;
  props_us_ = 0;
  error_.clear();
  seen_ = 0;
  stats_ = Stats{};
  window_us_ = 0;
  window_bytes_ = 0;
  ptr_redo_us_ = 0;
  ptr_dirty_ = true;
  started_ = true;
  LOG("screen: picture %d x %d at (%d, %d)", w_, h_, x_, y_);
  return true;
}

// The columns outside the picture's place (one eye sees them: the terminal's battery band may be there) go black:
// a zeroed 4-bpp block drawn down them a band at a time.
void ScreenMirror::BlackSides() {
  Fx::Region r = Region();
  int widest = 0;
  for (int i = 0; i < 2; i++) widest = std::max({widest, r.at[size_t(i)][0], 640 - r.at[size_t(i)][0] - w_});
  if (widest <= 0) return;
  uint32_t stride = uint32_t((widest + 1) / 2);
  int rows = std::clamp(int(6144 / stride), 1, 480);
  int64_t at = g1::Alloc(stride * uint32_t(rows));
  if (at < 0) {
    Panels(false);
    Panels(true);
    return;
  }
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    auto st = arm.GetState();
    if (!st.present || !st.connected) continue;
    arm.WriteArena(g1::Address(at), std::vector<uint8_t>(size_t(stride) * size_t(rows), 0));
    int x = r.at[size_t(i)][0];
    for (int y = 0; y < 480; y += rows) {
      uint32_t h = uint32_t(std::min(rows, 480 - y));
      if (x > 0) arm.Draw(g1::Address(at), stride, 0, 0, 0, y, uint32_t(x), h);
      if (x + w_ < 640) arm.Draw(g1::Address(at), stride, 0, 0, x + w_, y, uint32_t(640 - x - w_), h);
    }
  }
  g1::Free(g1::Address(at)); // the DRAWs go before anything written there later
}

// Frees the arena the path used; the panels are cleared (VIDEO draws past the gray display's picture), unless a
// transition covers the picture (keep_panels).
void ScreenMirror::DropPath(bool keep_panels) {
  if (levels_ == 16 && pic_) {
    sk::gray::Unload(*pic_);
    pic_ = nullptr;
    sk::gray::Clear();
    sk::Flush();
  }
  if (levels_ == 2 || levels_ == 1 || levels_ == kCanvasPath) {
    if (video_.pan_running) StopPan(); // the arms' own pan: the terminal starts its program later
    if (video_.base >= 0) g1::Free(g1::Address(video_.base));
    for (int64_t a : video_.parts) g1::Free(g1::Address(a));
    video_ = Video{};
    cprev_.clear();
    if (!keep_panels) {
      Panels(false);
      Panels(true);
    }
  }
  levels_ = 0;
  prev_.clear();
}

// Both arms that are present run g1v0 0.5.62 or later (VIDEOX, VM.md §3.8).
static bool HasVideoX() {
  bool any = false;
  for (g1::Arm *a : {&g1::left, &g1::right}) {
    auto st = a->GetState();
    if (!st.present) continue;
    if (!st.connected) return false;
    if (st.firmware_major * 10000 + st.firmware_minor * 100 + st.firmware_patch < 562) return false;
    any = true;
  }
  return any;
}

// ... and 0.5.64 or later: VIDEOX's pixel coding and scale 2 (the motion stream).
static bool HasPixelVideo() {
  bool any = false;
  for (g1::Arm *a : {&g1::left, &g1::right}) {
    auto st = a->GetState();
    if (!st.present) continue;
    if (!st.connected || st.firmware_major * 10000 + st.firmware_minor * 100 + st.firmware_patch < 564) return false;
    any = true;
  }
  return any;
}

// ... and 0.5.68 or later: VIDEOT (transform-coded video).
static bool HasVideoT() {
  bool any = false;
  for (g1::Arm *a : {&g1::left, &g1::right}) {
    auto st = a->GetState();
    if (!st.present) continue;
    if (!st.connected || st.firmware_major * 10000 + st.firmware_minor * 100 + st.firmware_patch < 568) return false;
    any = true;
  }
  return any;
}

// ... and 0.5.66 or later: VIDEOX block moves and the sprite.
static bool HasVideoX66() {
  bool any = false;
  for (g1::Arm *a : {&g1::left, &g1::right}) {
    auto st = a->GetState();
    if (!st.present) continue;
    if (!st.connected || st.firmware_major * 10000 + st.firmware_minor * 100 + st.firmware_patch < 566) return false;
    any = true;
  }
  return any;
}

// The pointer as VIDEOX's sprite: 16 rows of u16 mask, u16 ink (big-endian): the arrow of ArrowMasks at 16 x 16,
// its body lit, its outline dark.
static std::vector<uint8_t> SpriteArrow() {
  const float poly[][2] = {{1, 1}, {1, 13.6f}, {3.9f, 10.9f}, {6.1f, 15.6f}, {8.2f, 14.7f}, {6, 10.1f}, {10.2f, 10.1f}};
  const int np = int(std::size(poly));
  auto inside = [&](float x, float y) {
    bool in = false;
    for (int a = 0, b = np - 1; a < np; b = a++)
      if ((poly[a][1] > y) != (poly[b][1] > y) &&
          x < (poly[b][0] - poly[a][0]) * (y - poly[a][1]) / (poly[b][1] - poly[a][1]) + poly[a][0])
        in = !in;
    return in;
  };
  std::vector<uint8_t> out(64, 0);
  for (int y = 0; y < 16; y++) {
    uint32_t mask = 0, ink = 0;
    for (int x = 0; x < 16; x++) {
      bool body = inside(x + 0.5f, y + 0.5f), edge = false;
      for (int dy = -1; dy <= 1; dy++)
        for (int dx = -1; dx <= 1; dx++) edge |= inside(x + dx + 0.5f, y + dy + 0.5f);
      if (body || edge) mask |= 0x8000u >> x;
      if (body) ink |= 0x8000u >> x;
    }
    out[size_t(4 * y)] = uint8_t(mask >> 8);
    out[size_t(4 * y + 1)] = uint8_t(mask);
    out[size_t(4 * y + 2)] = uint8_t(ink >> 8);
    out[size_t(4 * y + 3)] = uint8_t(ink);
  }
  return out;
}

bool ScreenMirror::Path(int levels) {
  want_levels_ = levels;
  if (levels == kCanvasPath) {
    if (levels_ == kCanvasPath && video_.cw == canvas_w_) return true;
    DropPath();
    if (StartCanvas(canvas_w_)) {
      levels_ = kCanvasPath;
      error_.clear();
      return true;
    }
    LOG("screen: %s; the panorama goes window by window", error_.c_str());
    if (video_.base >= 0) g1::Free(g1::Address(video_.base));
    for (int64_t a : video_.parts) g1::Free(g1::Address(a));
    video_ = Video{};
    canvas_failed_ = true;
    return false;
  }
  int planes = levels == 4 ? 2 : levels == 8 ? 3 : 4;
  // Greys go as VIDEOX planes where the firmware has it (a fraction of the bytes), else as 4-bit uploads.
  int want = levels == 2 ? 2 : HasVideoX() ? 1 : 16;
  if (want == levels_ && (want != 1 || planes == video_.want_planes)) return true;
  DropPath();
  if (want == 1) {
    if (StartVideoX(planes)) {
      levels_ = 1;
      error_.clear();
      LOG("screen: VIDEOX path, %d planes (%d greys), record room %u, %d x %d at (%d, %d)", video_.planes,
          1 << video_.planes, video_.room, w_, h_, x_, y_);
      return true;
    }
    LOG("screen: %s; greys as uploads", error_.c_str());
    if (video_.base >= 0) g1::Free(g1::Address(video_.base));
    for (int64_t a : video_.parts) g1::Free(g1::Address(a));
    video_ = Video{};
    want = 16;
  }
  if (want == 16) {
    pic_ = &sk::gray::CreateBitmap(w_, h_);
    if (!pic_->contents.data) {
      // Fonts or pictures nobody uses now (the terminal has stopped): once more without them.
      Spare();
      pic_ = &sk::gray::CreateBitmap(w_, h_);
    }
    if (!pic_->contents.data) {
      pic_ = nullptr;
      error_ = "no room on the arms for a " + std::to_string(w_) + " x " + std::to_string(h_) + " picture";
      LOG("screen: %s (free %d of %d bytes)", error_.c_str(), sk::free_memory, sk::total_memory);
      return false;
    }
  } else if (!StartVideo()) {
    LOG("screen: %s", error_.c_str());
    if (video_.base >= 0) g1::Free(g1::Address(video_.base));
    video_ = Video{};
    return false;
  }
  levels_ = want;
  error_.clear();
  LOG("screen: %s path, %d x %d at (%d, %d)", want == 2 ? "VIDEO" : "gray", w_, h_, x_, y_);
  return true;
}

// The VIDEO path (black and white): per arm, stored code that sleeps until the frame's due time and draws the
// record's changed rows at the eye's place; the host writes the due time, the frame number, the size and the
// record, then CALLs it (VM.md §3.7). Both arms get the same due time: the eyes change together.
bool ScreenMirror::StartVideo() {
  Video &v = video_;
  v.stride = uint32_t(((w_ + 7) / 8 + 3) & ~3);
  uint32_t size = kCode + v.stride * uint32_t(h_) + g1::kVideoState + 8 + kHeader + kRecordMax + 4 +
                  4 * kPtrWords + 2 * 2 * kArrowH + kArrowW / 2 * kArrowH;
  v.base = g1::Alloc(size);
  if (v.base < 0) {
    Spare();
    v.base = g1::Alloc(size);
  }
  if (v.base < 0) {
    error_ = "no room on the arms for the fast (black and white) path";
    return false;
  }
  v.size = size;
  v.code = uint32_t(v.base);
  v.bitmap = v.code + kCode;
  v.state = (v.bitmap + v.stride * uint32_t(h_) + 3) & ~3u;
  v.buf = (v.state + g1::kVideoState + 3) & ~3u;
  v.ptr_state = (v.buf + kHeader + kRecordMax + 3) & ~3u;
  v.ptr_masks = v.ptr_state + 4 * kPtrWords;
  v.ptr_scr = v.ptr_masks + 2 * 2 * kArrowH;
  v.ptr_shown = -1;
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    if (!arm.GetState().present) continue;
    int px = i == 0 ? x_ - std::max(0, -dx_) : x_ - std::max(0, dx_);
    int py = i == 0 ? y_ + std::max(0, -dy_) : y_ + std::max(0, dy_);
    g1::Program p(v.code, 48);
    auto C = [&](int32_t x) { return p.Const(uint32_t(x)); };
    auto W = [&](int k) { return g1::ArenaWord(v.ptr_state / 4 + uint32_t(k)); };
    // The cursor's words: where the host wants it (X, Y: the arrow's box on the picture; On), where it is drawn
    // (DX, DY; Drawn), the box being drawn (HX, HY; Arrow: with the arrow, else the picture alone), working words.
    auto X = W(0), Y = W(1), On = W(2), DX = W(3), DY = W(4), Drawn = W(5), HX = W(6), HY = W(7), Arrow = W(8),
         x0 = W(9), x1 = W(10), y0 = W(11), y1 = W(12), t = W(13), t2 = W(14);
    auto over = p.NewLabel();
    g1::Address at = p.Here();
    p.SleepUntil(g1::ArenaWord(v.buf / 4));
    p.Video(p.Const(v.bitmap), p.Const(v.stride), p.Const(uint32_t(w_)), p.Const(uint32_t(h_)), p.Const(uint32_t(px)),
            p.Const(uint32_t(py)), p.Const(15), p.Const(v.buf + kHeader), g1::ArenaWord(v.buf / 4 + 2), p.Const(v.state));
    p.Call(over); // the frame's changed rows went over the arrow
    p.Ret();
    // The same with the picture off the panel: only the bitmap changes (a transition reveals it).
    g1::Address hidden = p.Here();
    p.SleepUntil(g1::ArenaWord(v.buf / 4));
    p.Video(p.Const(v.bitmap), p.Const(v.stride), p.Const(uint32_t(w_)), p.Const(uint32_t(h_)), p.Const(640),
            p.Const(uint32_t(py)), p.Const(15), p.Const(v.buf + kHeader), g1::ArenaWord(v.buf / 4 + 2), p.Const(v.state));
    p.Ret();
    // box: the picture under the box (HX, HY) from the bitmap into the scratch, the arrow over it (Arrow), and the
    // part inside the picture to the panel: one small DRAW, so the cursor moves at once and costs a few bytes.
    auto box = p.NewLabel(), l1 = p.NewLabel(), l2 = p.NewLabel(), l3 = p.NewLabel(), l4 = p.NewLabel(),
         plain = p.NewLabel(), bret = p.NewLabel();
    p.Bind(box);
    p.Fill(p.Const(v.ptr_scr), C(kArrowW / 2), C(0), C(0), C(kArrowW), C(kArrowH), C(0));
    p.Mov(x0, HX);
    p.BgeS(x0, C(0), l1);
    p.Mov(x0, C(0));
    p.Bind(l1);
    p.Add(x1, HX, C(kArrowW));
    p.BltS(x1, C(w_), l2);
    p.Mov(x1, C(w_));
    p.Bind(l2);
    p.Mov(y0, HY);
    p.BgeS(y0, C(0), l3);
    p.Mov(y0, C(0));
    p.Bind(l3);
    p.Add(y1, HY, C(kArrowH));
    p.BltS(y1, C(h_), l4);
    p.Mov(y1, C(h_));
    p.Bind(l4);
    p.BgeS(x0, x1, bret);
    p.BgeS(y0, y1, bret);
    p.Sub(x1, x1, x0); // the columns
    p.Sub(y1, y1, y0); // the rows
    p.Sub(t, x0, HX);  // in the scratch
    p.Sub(t2, y0, HY);
    p.Blit(p.Const(v.bitmap), p.Const(v.stride), x0, y0, p.Const(v.ptr_scr), C(kArrowW / 2), t, t2, x1, y1,
           C(4 | g1::MODE_COPY), C(15));
    p.Beq(Arrow, C(0), plain);
    p.Blit(p.Const(v.ptr_masks + 2 * kArrowH), C(2), C(0), C(0), p.Const(v.ptr_scr), C(kArrowW / 2), C(0), C(0),
           C(kArrowW), C(kArrowH), C(4 | g1::MODE_ERASE), C(15));
    p.Blit(p.Const(v.ptr_masks), C(2), C(0), C(0), p.Const(v.ptr_scr), C(kArrowW / 2), C(0), C(0), C(kArrowW),
           C(kArrowH), C(4 | g1::MODE_MASK), C(15));
    p.Bind(plain);
    p.Add(x0, x0, C(px));
    p.Add(y0, y0, C(py));
    p.Draw(p.Const(v.ptr_scr), C(kArrowW / 2), t, t2, x0, y0, x1, y1);
    p.Bind(bret);
    p.Ret();
    // move (the host's call after writing X, Y, On): the picture back where the arrow was, the arrow where asked.
    auto fresh = p.NewLabel(), mret = p.NewLabel(), oret = p.NewLabel();
    g1::Address move = p.Here();
    p.Beq(Drawn, C(0), fresh);
    p.Mov(HX, DX);
    p.Mov(HY, DY);
    p.Mov(Arrow, C(0));
    p.Call(box);
    p.Bind(fresh);
    p.Mov(Drawn, C(0));
    p.Beq(On, C(0), mret);
    p.Mov(DX, X);
    p.Mov(DY, Y);
    p.Mov(HX, X);
    p.Mov(HY, Y);
    p.Mov(Arrow, C(1));
    p.Call(box);
    p.Mov(Drawn, C(1));
    p.Bind(mret);
    p.Ret();
    // over: the arrow again where it is drawn.
    p.Bind(over);
    p.Beq(Drawn, C(0), oret);
    p.Mov(HX, DX);
    p.Mov(HY, DY);
    p.Mov(Arrow, C(1));
    p.Call(box);
    p.Bind(oret);
    p.Ret();
    p.Changes(v.ptr_state + 12, 4 * (kPtrWords - 3));
    p.Changes(v.ptr_scr, kArrowW / 2 * kArrowH);
    std::string why;
    if (!p.Valid(&why)) {
      error_ = "the VIDEO code: " + why;
      return false;
    }
    if (p.Here() > v.code + kCode) {
      error_ = "the VIDEO code is larger than its room";
      return false;
    }
    arm.WriteArena(v.bitmap, std::vector<uint8_t>(size_t(v.stride) * h_, 0));
    std::vector<uint8_t> ptr(4 * kPtrWords, 0);
    std::vector<uint8_t> masks = ArrowMasks();
    ptr.insert(ptr.end(), masks.begin(), masks.end());
    arm.WriteArena(v.ptr_state, ptr);
    if (!arm.Load(p)) {
      error_ = "the VIDEO code did not load (a firmware before VIDEO?)";
      return false;
    }
    v.call[size_t(i)] = at;
    v.hidden[size_t(i)] = hidden;
    v.ptr_call[size_t(i)] = move;
    v.epochs[size_t(i)] = arm.GetState().epoch_changes;
  }
  v.enc = std::make_unique<g1::VideoEncoder>(w_, h_);
  v.pix.assign(size_t(w_) * h_, 0);
  v.k = 0;
  v.redraw = true;
  return true;
}

// The grey fast path (g1v0 0.5.62+, VM.md §3.8): the picture as 2-4 bit planes, each a VIDEO stream, with a level
// map; per arm, stored code that sleeps until the frame's due time and runs VIDEOX at the eye's place. Levels are
// Gray-coded (neighbouring greys differ in one plane), so a gradual change touches fewer planes. Scrolls go as row
// moves (PlaneVideoEncoder::FindMove). The cursor is drawn into the picture (the arms' arrow needs one plane).
bool ScreenMirror::StartVideoX(int planes) {
  Video &v = video_;
  v.stride = uint32_t(((w_ + 7) / 8 + 3) & ~3);
  v.want_planes = planes;
  // Resolution first: all the planes asked for (16 greys: 4) with a smaller record room before fewer planes; the
  // terminal's kept fonts give way (Spare) before either. Each plane and the states are allocations of their own
  // (VIDEOX takes their addresses from tables), so a fragmented arena still holds 16 greys.
  auto release = [&] {
    if (v.base >= 0) g1::Free(g1::Address(v.base));
    for (int64_t a : v.parts) g1::Free(g1::Address(a));
    v.base = -1;
    v.parts.clear();
  };
  auto take = [&](int p, uint32_t room) {
    v.base = g1::Alloc(kCodeX + 32 + 16 + 64 + kHeader + room + 4);
    if (v.base < 0) return false;
    for (int k = 0; k < p; k++) {
      int64_t a = g1::Alloc(v.stride * uint32_t(h_));
      if (a < 0) return release(), false;
      v.parts.push_back(a);
    }
    int64_t st = g1::Alloc(uint32_t(p) * g1::kVideoState);
    if (st < 0) return release(), false;
    v.parts.push_back(st);
    v.size = kCodeX + 32 + 16 + 64 + kHeader + room + 4 + uint32_t(p) * (v.stride * uint32_t(h_) + g1::kVideoState);
    v.planes = p;
    v.room = room;
    return true;
  };
  // VIDEOT (0.5.68+, 16 greys): its half-size frame and state first, so moving pictures keep it; the planes' record
  // room gives way to it (a 16-grey page is ~8 KB), and without room for both the planes come first.
  int64_t tf = -1, tst = -1;
  int tw = std::min(320, ((w_ + 1) / 2 + 7) & ~7), th = std::min(240, ((h_ + 1) / 2 + 7) & ~7);
  if (planes == 4 && HasVideoT()) {
    tf = g1::Alloc(uint32_t(tw * th));
    tst = tf >= 0 ? g1::Alloc(g1::TransformVideoEncoder::kState) : -1;
    if (tst < 0 && tf >= 0) {
      g1::Free(g1::Address(tf));
      tf = -1;
    }
  }
  bool got = take(planes, kRecordMaxX);
  if (!got && tst >= 0)
    for (uint32_t room : {kRecordMaxX / 2, kRecordMaxX / 4})
      if ((got = take(planes, room))) break;
  if (!got && tst >= 0) {
    LOG("screen: no room for transform-coded video beside 16 greys (arena free %d)", sk::free_memory);
    g1::Free(g1::Address(tst));
    g1::Free(g1::Address(tf));
    tf = tst = -1;
  }
  if (!got) Spare();
  for (int p = planes; p >= 2 && !got; p--)
    for (uint32_t room : {kRecordMaxX, kRecordMaxX / 2, kRecordMaxX / 4})
      if ((got = take(p, room))) break;
  if (!got) {
    error_ = "no room on the arms for the grey fast path";
    return false;
  }
  if (v.planes < planes || v.room < kRecordMaxX)
    LOG("screen: VIDEOX with %d of %d planes, room %u (arena free %d of %d)", v.planes, planes, v.room,
        sk::free_memory, sk::total_memory);
  v.code = uint32_t(v.base);
  v.plane_tab = v.code + kCodeX;
  v.state_tab = v.plane_tab + 16;
  v.levels_at = v.state_tab + 16;
  v.sprite_at = v.levels_at + 16;
  v.buf = v.sprite_at + 64;
  v.v66 = HasVideoX66();
  v.spr_x = -32768;
  v.bitmap = uint32_t(v.parts[0]);
  v.state = uint32_t(v.parts[size_t(v.planes)]);
  std::vector<uint8_t> tabs(32, 0);
  for (int k = 0; k < v.planes; k++) {
    Put32(tabs, size_t(4 * k), uint32_t(v.parts[size_t(k)]));
    Put32(tabs, size_t(16 + 4 * k), v.state + uint32_t(k) * g1::kVideoState);
  }
  // The motion stream: 2 pixel-coded planes at half size, their states, its tables and level map (4 greys).
  std::vector<uint8_t> mtabs(48, 0), mmap(16, 0);
  if (kMotionStream && v.planes == 4 && HasPixelVideo()) {
    size_t had = v.parts.size();
    int mw = w_ / 2, mh = h_ / 2;
    uint32_t ms = uint32_t(((mw + 7) / 8 + 3) & ~3);
    int64_t p0 = g1::Alloc(ms * uint32_t(mh)), p1 = p0 >= 0 ? g1::Alloc(ms * uint32_t(mh)) : -1;
    int64_t mst = p1 >= 0 ? g1::Alloc(2 * g1::PlaneVideoEncoder::kPixelState) : -1, mt = mst >= 0 ? g1::Alloc(48) : -1;
    for (int64_t a : {p0, p1, mst, mt})
      if (a >= 0) v.parts.push_back(a);
    if (mt >= 0) {
      v.mw = mw;
      v.mh = mh;
      v.mstride = ms;
      v.mplane_tab = uint32_t(mt);
      v.mstate_tab = uint32_t(mt) + 16;
      v.mlevels = uint32_t(mt) + 32;
      Put32(mtabs, 0, uint32_t(p0));
      Put32(mtabs, 4, uint32_t(p1));
      Put32(mtabs, 16, uint32_t(mst));
      Put32(mtabs, 20, uint32_t(mst) + g1::PlaneVideoEncoder::kPixelState);
      for (int k = 0; k <= 3; k++) mtabs[size_t(32 + (k ^ (k >> 1)))] = uint8_t(k * 5);
    } else {
      for (size_t i = had; i < v.parts.size(); i++) g1::Free(g1::Address(v.parts[i]));
      v.parts.resize(had);
      LOG("screen: no room for the motion stream (arena free %d)", sk::free_memory);
    }
  }
  // VIDEOT: the half-size 8-bit frame (rounded up to whole 8 x 8 blocks) and its state, reserved above.
  if (tst >= 0 && v.planes == 4) {
    v.parts.push_back(tf);
    v.parts.push_back(tst);
    v.tw = tw;
    v.th = th;
    v.tframe = uint32_t(tf);
    v.tstate = uint32_t(tst);
  } else if (tst >= 0) {
    g1::Free(g1::Address(tst));
    g1::Free(g1::Address(tf));
  }
  const int n = (1 << v.planes) - 1; // steps between black and full
  std::vector<uint8_t> map(16, 0);
  for (int k = 0; k <= n; k++) map[size_t(k ^ (k >> 1))] = uint8_t((k * 15 + n / 2) / n);
  for (int val = 0; val < 256; val++) {
    int k = (val * n + 127) / 255;
    v.index[size_t(val)] = uint8_t(k ^ (k >> 1));
  }
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    if (!arm.GetState().present) continue;
    int px = i == 0 ? x_ - std::max(0, -dx_) : x_ - std::max(0, dx_);
    int py = i == 0 ? y_ + std::max(0, -dy_) : y_ + std::max(0, dy_);
    v.px[size_t(i)] = px;
    v.py[size_t(i)] = py;
    g1::Program p(v.code, 48);
    g1::Address at = p.Here();
    p.SleepUntil(g1::ArenaWord(v.buf / 4));
    p.VideoX(p.Const(v.plane_tab), p.Const(v.stride), p.Const(uint32_t(w_)), p.Const(uint32_t(h_)), p.Const(uint32_t(px)),
             p.Const(uint32_t(py)), p.Const(v.levels_at), p.Const(v.buf + kHeader), g1::ArenaWord(v.buf / 4 + 2),
             p.Const(v.state_tab));
    p.Ret();
    g1::Address hidden = p.Here(); // the planes change, the panel does not (a transition reveals later)
    p.SleepUntil(g1::ArenaWord(v.buf / 4));
    p.VideoX(p.Const(v.plane_tab), p.Const(v.stride), p.Const(uint32_t(w_)), p.Const(uint32_t(h_)), p.Const(640),
             p.Const(uint32_t(py)), p.Const(v.levels_at), p.Const(v.buf + kHeader), g1::ArenaWord(v.buf / 4 + 2),
             p.Const(v.state_tab));
    p.Ret();
    g1::Address motion = 0; // the motion stream at scale 2 (the record's flags say so)
    if (v.mw) {
      motion = p.Here();
      p.SleepUntil(g1::ArenaWord(v.buf / 4));
      p.VideoX(p.Const(v.mplane_tab), p.Const(v.mstride), p.Const(uint32_t(v.mw)), p.Const(uint32_t(v.mh)),
               p.Const(uint32_t(px)), p.Const(uint32_t(py)), p.Const(v.mlevels), p.Const(v.buf + kHeader),
               g1::ArenaWord(v.buf / 4 + 2), p.Const(v.mstate_tab));
      p.Ret();
    }
    g1::Address tcall = 0; // VIDEOT at half size: the whole picture, or a video's rectangle (in the record's header)
    if (v.tw) {
      tcall = p.Here();
      p.SleepUntil(g1::ArenaWord(v.buf / 4));
      p.VideoT(p.Const(v.tframe), g1::ArenaWord(v.buf / 4 + 3), g1::ArenaWord(v.buf / 4 + 4),
               g1::ArenaWord(v.buf / 4 + 5), g1::ArenaWord(v.buf / 4 + 6), p.Const(v.buf + kHeaderT),
               g1::ArenaWord(v.buf / 4 + 2), p.Const(v.tstate));
      p.Ret();
    }
    std::string why;
    if (!p.Valid(&why)) {
      error_ = "the VIDEOX code: " + why;
      return false;
    }
    if (p.Here() > v.code + kCodeX) {
      error_ = "the VIDEOX code is larger than its room";
      return false;
    }
    for (int k = 0; k < v.planes; k++)
      arm.WriteArena(uint32_t(v.parts[size_t(k)]), std::vector<uint8_t>(size_t(v.stride) * size_t(h_), 0));
    arm.WriteArena(v.plane_tab, tabs);
    arm.WriteArena(v.levels_at, map);
    if (v.v66) arm.WriteArena(v.sprite_at, SpriteArrow());
    if (v.mw) {
      arm.WriteArena(uint32_t(v.parts[size_t(v.planes) + 1]), std::vector<uint8_t>(size_t(v.mstride) * size_t(v.mh), 0));
      arm.WriteArena(uint32_t(v.parts[size_t(v.planes) + 2]), std::vector<uint8_t>(size_t(v.mstride) * size_t(v.mh), 0));
      arm.WriteArena(v.mplane_tab, mtabs);
    }
    if (!arm.Load(p)) {
      error_ = "the VIDEOX code did not load (a firmware before 0.5.62?)";
      return false;
    }
    v.call[size_t(i)] = at;
    v.hidden[size_t(i)] = hidden;
    v.callm[size_t(i)] = motion;
    v.callt[size_t(i)] = tcall;
    v.epochs[size_t(i)] = arm.GetState().epoch_changes;
  }
  if (v.mw) {
    v.menc = std::make_unique<g1::PlaneVideoEncoder>(v.mw, v.mh, 2, g1::PlaneVideoEncoder::kPixels);
    v.mpix.assign(size_t(v.mw) * size_t(v.mh), 0);
    v.mredraw = true;
    v.in_motion = false;
    LOG("screen: motion stream %d x %d, pixel coded, drawn at scale 2", v.mw, v.mh);
  }
  if (v.tw) {
    v.tenc = std::make_unique<g1::TransformVideoEncoder>(v.tw, v.th);
    v.tluma.assign(size_t(v.tw) * size_t(v.th), 0);
    v.tlast.clear();
    v.tq = 40;
    v.tredraw = true;
    v.rw = 0;
    heat_.clear();
    region_until_us_ = 0;
    LOG("screen: moving pictures as transform-coded video %d x %d (drawn at scale 2, dithered)", v.tw, v.th);
  }
  v.penc = std::make_unique<g1::PlaneVideoEncoder>(w_, h_, v.planes);
  tuned_budget_ = kMotionBudget;
  calm_frames_ = 0;
  link_busy_ = false;
  v.coarse_map = false;
  v.pix.assign(size_t(w_) * h_, 0);
  v.shown.assign(size_t(w_) * h_, 0);
  v.k = 0;
  v.moves = 0;
  v.redraw = true;
  return true;
}

// frame_ as a VIDEOX record to both arms, drawn at the same due time, with a row move when the picture scrolled;
// false (nothing sent, the encoder as it was) when larger than max_bytes.
bool ScreenMirror::SendVideoX(size_t max_bytes, g1::RowMove m, bool pix_ready, g1::BlockMove block) {
  Video &v = video_;
  if (!pix_ready)
    for (size_t i = 0; i < v.pix.size(); i++) v.pix[i] = v.index[frame_[i]];
  int sx = v.spr_x, sy = v.spr_y;
  g1::Sprite sprite = PointerSprite();
  auto over_video = [&](int x, int y, int w, int h) {
    return v.rw && x < v.rx + v.rw && x + w > v.rx && y < v.ry + v.rh && y + h > v.ry;
  };
  if (sprite.on && sprite.x != -32768 && over_video(sprite.x, sprite.y, 16, 16)) {
    sprite.x = -32768; // over the video the pointer is not drawn (VIDEOT would draw over it)
    sprite.y = 0;
    v.spr_x = -32768;
    v.spr_y = 0;
    if (!sprite.rw) sprite.on = false;
  }
  std::vector<uint8_t> rec = v.penc->Encode(v.pix, v.redraw, m, std::min<size_t>(max_bytes, v.room), false, block, sprite);
  if (rec.empty()) {
    v.spr_x = sx; // not sent: the arms still draw it where they did
    v.spr_y = sy;
    return false;
  }
  if (block.w) LOG("screen: block %d x %d moved by (%d, %d)", block.w, block.h, block.dx, block.dy);
  if (m.rows) {
    v.moves++;
    LOG("screen: scroll %+d rows (band %d..%d)", m.dy, m.y0, m.y0 + m.rows - 1);
  }
  int64_t lead = std::min<int64_t>(v.lead + int64_t(rec.size()) * 1000000 / 40000, 1000000);
  reveal_style_ = 0; // transitions reveal 1-bpp bitmaps: greys come in directly
  std::vector<uint8_t> msg(kHeader + rec.size());
  Put32(msg, 4, v.k);
  Put32(msg, 8, uint32_t(rec.size()));
  std::memcpy(msg.data() + kHeader, rec.data(), rec.size());
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    if (!arm.GetState().present) continue;
    Put32(msg, 0, arm.DeviceTime(lead));
    arm.WriteArena(v.buf, msg);
    arm.Call(v.call[size_t(i)]);
  }
  // the planes' picture is on the panel now: the next VIDEOT record draws every band. With a video rectangle, only
  // when this record drew in it: a redraw, a move, the pointer's old place, or a row changed on both sides of it.
  bool touched = !v.rw || v.redraw || m.rows || block.w || (sprite.on && sprite.rw && over_video(sprite.rx, sprite.ry, sprite.rw, sprite.rh));
  if (!touched && v.shown.size() == v.pix.size())
    for (int y = std::max(0, v.ry); y < std::min(h_, v.ry + v.rh) && !touched; y++) {
      const uint8_t *a = v.pix.data() + size_t(y) * size_t(w_), *b = v.shown.data() + size_t(y) * size_t(w_);
      int x0 = 0, x1 = w_ - 1;
      while (x0 < w_ && a[x0] == b[x0]) x0++;
      if (x0 == w_) continue;
      while (a[x1] == b[x1]) x1--;
      touched = x0 < v.rx + v.rw && x1 >= v.rx;
    }
  if (touched) v.tredraw = true;
  v.redraw = false;
  v.in_motion = false;
  v.k++;
  v.sent = int(msg.size());
  v.shown = v.pix;
  return true;
}

// frame_ (made at detail 2: 2 x 2 blocks) as a motion-stream record: half size, 4 greys, pixel coded, drawn at
// scale 2; the first after still pictures redraws every row (the panel shows the full stream's picture).
// The picture (base_, toned as Tone without its levels: the codec keeps 8 bits) at half size as a VIDEOT record to
// both arms. The quantiser follows the moving frames' budget: up when a record is over it, down while well under.
bool ScreenMirror::SendVideoT(const ScreenOptions &o, size_t budget, Rect r) {
  Video &v = video_;
  const bool whole = r.x == 0 && r.y == 0 && r.w >= w_ && r.h >= h_;
  const int ew = whole ? v.tw : std::min(v.tw, r.w / 2 & ~7), eh = whole ? v.th : std::min(v.th, r.h / 2 & ~7);
  if (ew < 8 || eh < 8 || v.room <= kHeaderT - kHeader) return false;
  if (!v.tenc || v.tenc->Width() != ew || v.tenc->Height() != eh) { // another rectangle: a fresh picture
    v.tenc = std::make_unique<g1::TransformVideoEncoder>(ew, eh);
    v.tluma.assign(size_t(ew) * size_t(eh), 0);
    v.tlast.clear();
    v.tredraw = true;
  }
  int a = std::clamp(o.invert, 0, 100);
  double g = std::clamp(o.gamma, 20, 500) / 100.0;
  uint8_t lut[256];
  for (int x = 0; x < 256; x++) {
    int f = x + (255 - 2 * x) * a / 100;
    lut[x] = uint8_t(g == 1.0 ? f : int(std::pow(f / 255.0, g) * 255.0 + 0.5));
  }
  for (int y = 0; y < eh; y++)
    for (int x = 0; x < ew; x++) {
      int sx = r.x + 2 * x, sy = r.y + 2 * y, sum = 0, cnt = 0;
      for (int k = 0; k < 4; k++) {
        int px = sx + (k & 1), py = sy + (k >> 1);
        if (px < w_ && py < h_) sum += lut[base_[size_t(py) * size_t(w_) + size_t(px)]], cnt++;
      }
      v.tluma[size_t(y) * size_t(ew) + size_t(x)] = uint8_t(cnt ? (sum + cnt / 2) / cnt : 0);
    }
  if (!v.tredraw && v.tluma == v.tlast) return false; // nothing new
  const size_t room = v.room - (kHeaderT - kHeader);
  std::vector<uint8_t> rec;
  for (int tries = 0; tries < 4 && rec.empty(); tries++) {
    rec = v.tenc->Encode(v.tluma, v.tq, v.tredraw, std::min<size_t>(room, std::max<size_t>(budget * 2, 2048)));
    if (rec.empty()) v.tq = std::min(63, v.tq + v.tq / 3 + 1);
  }
  if (rec.empty()) return false;
  if (rec.size() > budget) v.tq = std::min(63, v.tq + std::max(1, v.tq / 8));
  else if (rec.size() * 10 < budget * 7 && v.tq > 6) v.tq--;
  int64_t lead = std::min<int64_t>(v.lead + int64_t(rec.size()) * 1000000 / 40000, 1000000);
  std::vector<uint8_t> msg(kHeaderT + rec.size());
  Put32(msg, 4, v.k);
  Put32(msg, 8, uint32_t(rec.size()));
  Put32(msg, 12, uint32_t(ew));
  Put32(msg, 16, uint32_t(eh));
  std::memcpy(msg.data() + kHeaderT, rec.data(), rec.size());
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    if (!arm.GetState().present) continue;
    Put32(msg, 0, arm.DeviceTime(lead));
    Put32(msg, 20, uint32_t(v.px[size_t(i)] + r.x));
    Put32(msg, 24, uint32_t(v.py[size_t(i)] + r.y));
    arm.WriteArena(v.buf, msg);
    arm.Call(v.callt[size_t(i)]);
  }
  v.tlast = v.tluma;
  v.tredraw = false;
  // the whole picture: the planes' picture is no longer on the panel, so their next record draws every row (a
  // rectangle: the planes keep the rest, and SendVideoX says when they drew over it)
  if (whole) v.redraw = true;
  v.k++;
  v.sent = int(msg.size());
  return true;
}

// The tiles that changed in at least 4 of the last 8 frames that changed anything, as one rectangle: a video when
// they fill most of it, it is at least 64 x 48 and at most 70% of the picture (more: the whole picture moves).
bool ScreenMirror::VideoRegion(Rect *out) {
  const int tx = (w_ + 15) / 16, ty = (h_ + 15) / 16;
  if (heat_.size() != size_t(tx) * size_t(ty)) heat_.assign(size_t(tx) * size_t(ty), 0);
  if (exact_.size() != frame_.size() || frame_.size() != size_t(w_) * size_t(h_)) return false;
  if (frame_ == exact_) { // nothing changed: the history waits for the next change
  } else {
    for (int j = 0; j < ty; j++)
      for (int i = 0; i < tx; i++) {
        bool ch = false;
        for (int y = j * 16; y < std::min(h_, j * 16 + 16) && !ch; y++) {
          size_t o = size_t(y) * size_t(w_) + size_t(i * 16);
          size_t n = size_t(std::min(w_, i * 16 + 16) - i * 16);
          ch = std::memcmp(frame_.data() + o, exact_.data() + o, n) != 0;
        }
        uint8_t &hb = heat_[size_t(j) * size_t(tx) + size_t(i)];
        hb = uint8_t(hb << 1 | (ch ? 1 : 0));
      }
  }
  int x0 = tx, y0 = ty, x1 = -1, y1 = -1, hot = 0;
  for (int j = 0; j < ty; j++)
    for (int i = 0; i < tx; i++)
      if (std::popcount(heat_[size_t(j) * size_t(tx) + size_t(i)]) >= 4) {
        hot++;
        x0 = std::min(x0, i), y0 = std::min(y0, j), x1 = std::max(x1, i), y1 = std::max(y1, j);
      }
  if (!hot) return false;
  int tiles = (x1 - x0 + 1) * (y1 - y0 + 1);
  Rect r{x0 * 16, y0 * 16, std::min(w_, (x1 + 1) * 16) - x0 * 16, std::min(h_, (y1 + 1) * 16) - y0 * 16};
  if (hot * 10 < tiles * 6 || r.w < 64 || r.h < 48 || int64_t(r.w) * r.h * 10 > int64_t(w_) * h_ * 7) return false;
  *out = r;
  return true;
}

bool ScreenMirror::SendMotion(size_t max_bytes) {
  Video &v = video_;
  for (int y = 0; y < v.mh; y++)
    for (int x = 0; x < v.mw; x++) {
      int val = frame_[size_t(2 * y) * size_t(w_) + size_t(2 * x)], k = (val * 3 + 127) / 255;
      v.mpix[size_t(y) * size_t(v.mw) + size_t(x)] = uint8_t(k ^ (k >> 1));
    }
  std::vector<uint8_t> rec = v.menc->Encode(v.mpix, v.mredraw, {}, std::min<size_t>(max_bytes, v.room), true);
  if (rec.empty()) return false;
  int64_t lead = std::min<int64_t>(v.lead + int64_t(rec.size()) * 1000000 / 40000, 1000000);
  std::vector<uint8_t> msg(kHeader + rec.size());
  Put32(msg, 4, v.k);
  Put32(msg, 8, uint32_t(rec.size()));
  std::memcpy(msg.data() + kHeader, rec.data(), rec.size());
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    if (!arm.GetState().present) continue;
    Put32(msg, 0, arm.DeviceTime(lead));
    arm.WriteArena(v.buf, msg);
    arm.Call(v.callm[size_t(i)]);
  }
  v.mredraw = false;
  v.in_motion = true;
  v.redraw = true; // the full stream's picture is no longer on the panel: its next record draws every row
  v.k++;
  v.sent = int(msg.size());
  return true;
}

// The level map on both arms. Exact: Gray-coded 16 levels. Coarse (auto greys while moving): the top two planes
// only, 0 / 5 / 10 / 15, so the low planes the moving frames leave as they were show nothing (no ghosting, no
// bytes); back to exact, every row is drawn again with it (redraw).
void ScreenMirror::LevelMap(bool coarse) {
  Video &v = video_;
  if (coarse == v.coarse_map || v.planes != 4) return;
  std::vector<uint8_t> map(16, 0);
  for (int i = 0; i < 16; i++) {
    int g3 = i >> 3 & 1, g2 = i >> 2 & 1, g1 = i >> 1 & 1, g0 = i & 1;
    int b3 = g3, b2 = b3 ^ g2, b1 = b2 ^ g1, b0 = b1 ^ g0;
    map[size_t(i)] = uint8_t(coarse ? (b3 * 2 + b2) * 5 : b3 * 8 + b2 * 4 + b1 * 2 + b0);
  }
  for (g1::Arm *a : {&g1::left, &g1::right})
    if (a->GetState().present) a->WriteArena(v.levels_at, map);
  v.coarse_map = coarse;
  if (!coarse) v.redraw = true;
}

// The sprite fields for a record on 0.5.66+ (the pointer drawn by the arms over the picture): the rectangle where
// it was (drawn from the planes again) and where it goes; updates spr_x/spr_y to that. Off on older firmware.
g1::Sprite ScreenMirror::PointerSprite() {
  Video &v = video_;
  g1::Sprite s;
  if (!v.v66) return s;
  int x = 0, y = 0;
  bool on = ptr_on_ && src_w_ > 1 && Locate(ptr_cx_, ptr_cy_, src_w_, src_h_, src_o_, w_, h_, &x, &y);
  if (!on && v.spr_x == -32768) return s;
  s.on = true;
  s.address = v.sprite_at;
  if (v.spr_x != -32768) {
    s.rx = std::max(0, v.spr_x);
    s.ry = std::max(0, v.spr_y);
    s.rw = std::max(0, std::min(w_, v.spr_x + 16) - s.rx);
    s.rh = std::max(0, std::min(h_, v.spr_y + 16) - s.ry);
  }
  s.x = on ? x - 1 : -32768; // the arrow's tip is (1, 1) of its box
  s.y = on ? y - 1 : 0;
  v.spr_x = s.x;
  v.spr_y = s.y;
  return s;
}

// The canvas path (0.5.66+): the panorama (cw x h_ pixels, 1:1 with the source) as 2 planes (4 greys) when that fits
// the arena, else 1 (black and white); per arm, stored code that runs VIDEOX at x = the word at xword (the eye's
// place minus the window's left), so a head turn is that word and a record of unchanged planes with redraw.
bool ScreenMirror::StartCanvas(int cw) {
  Video &v = video_;
  if (cw < 2 || cw > 2048) return error_ = "no canvas for that width", false;
  uint32_t stride = uint32_t(((cw + 7) / 8 + 3) & ~3), room = kRecordMaxX / 2;
  Spare();
  int planes = 0;
  for (int p : {2, 1}) {
    if (p == 2 && cw > 1400) continue;
    v.base = g1::Alloc(kCodeX + 32 + 16 + 4 + kHeader + room + 4);
    if (v.base < 0) break;
    bool ok = true;
    for (int k = 0; k < p && ok; k++) {
      int64_t a = g1::Alloc(stride * uint32_t(h_));
      if (a < 0) ok = false; else v.parts.push_back(a);
    }
    int64_t st = ok ? g1::Alloc(uint32_t(p) * g1::kVideoState) : -1;
    if (st >= 0) {
      v.parts.push_back(st);
      planes = p;
      break;
    }
    g1::Free(g1::Address(v.base));
    v.base = -1;
    for (int64_t a : v.parts) g1::Free(g1::Address(a));
    v.parts.clear();
  }
  if (!planes) return error_ = "no room on the arms for the panorama canvas", false;
  v.planes = planes;
  v.stride = stride;
  v.room = room;
  v.cw = cw;
  v.cwin = -1;
  v.code = uint32_t(v.base);
  v.plane_tab = v.code + kCodeX;
  v.state_tab = v.plane_tab + 16;
  v.levels_at = v.state_tab + 16;
  v.xword = v.levels_at + 16;
  v.buf = v.xword + 4;
  v.state = uint32_t(v.parts[size_t(planes)]);
  std::vector<uint8_t> tabs(32, 0), map(16, 0);
  for (int k = 0; k < planes; k++) {
    Put32(tabs, size_t(4 * k), uint32_t(v.parts[size_t(k)]));
    Put32(tabs, size_t(16 + 4 * k), v.state + uint32_t(k) * g1::kVideoState);
  }
  const int n = (1 << planes) - 1;
  for (int k = 0; k <= n; k++) map[size_t(k ^ (k >> 1))] = uint8_t(k * 15 / n);
  for (int val = 0; val < 256; val++) {
    int k = (val * n + 127) / 255;
    v.index[size_t(val)] = uint8_t(k ^ (k >> 1));
  }
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    if (!arm.GetState().present) continue;
    v.px[size_t(i)] = i == 0 ? x_ - std::max(0, -dx_) : x_ - std::max(0, dx_);
    int py = i == 0 ? y_ + std::max(0, -dy_) : y_ + std::max(0, dy_);
    g1::Program p(v.code, 32);
    g1::Address at = p.Here();
    p.SleepUntil(g1::ArenaWord(v.buf / 4));
    p.VideoX(p.Const(v.plane_tab), p.Const(v.stride), p.Const(uint32_t(cw)), p.Const(uint32_t(h_)),
             g1::ArenaWord(v.xword / 4), p.Const(uint32_t(py)), p.Const(v.levels_at), p.Const(v.buf + kHeader),
             g1::ArenaWord(v.buf / 4 + 2), p.Const(v.state_tab));
    p.Ret();
    std::string why;
    if (!p.Valid(&why)) return error_ = "the canvas code: " + why, false;
    if (p.Here() > v.code + kCodeX) return error_ = "the canvas code is larger than its room", false;
    for (int k = 0; k < planes; k++)
      arm.WriteArena(uint32_t(v.parts[size_t(k)]), std::vector<uint8_t>(size_t(stride) * size_t(h_), 0));
    arm.WriteArena(v.plane_tab, tabs);
    arm.WriteArena(v.levels_at, map);
    if (!arm.Load(p)) return error_ = "the canvas code did not load", false;
    v.call[size_t(i)] = at;
    v.epochs[size_t(i)] = arm.GetState().epoch_changes;
  }
  v.penc = std::make_unique<g1::PlaneVideoEncoder>(cw, h_, planes);
  v.pix.assign(size_t(cw) * size_t(h_), 0);
  v.k = 0;
  v.redraw = true;
  v.v66 = true;
  LOG("screen: panorama canvas %d x %d, %d plane%s (%d greys): head turns move a window on the arms", cw, h_, planes,
      planes > 1 ? "s" : "", 1 << planes);
  return true;
}

// The arms' own pan (VM program, Antigravity's "phone-anchored" design): every 20 ms each arm reads its gyroscope's
// turn about up (GYR_Y, mdps; a 2 dps dead band against drift while still), adds it up since the phone's last anchor
// (ANCH: the window's left in the canvas; ACC: the turn since, (mdps / 16) x (us / 16)), and when the window moved
// draws the canvas again at the new place from a record of unchanged planes (its own reset states, so the phone's
// encoder never sees it). The phone stays the one tracker: each head-turn update it writes ANCH and ACC = 0 to both
// arms, so the eyes never drift apart, and the turn shows at once instead of a link round trip later.
std::atomic<bool> ScreenMirror::pan_report{false};

void ScreenMirror::StartPan() {
  Video &v = video_;
  const int P = v.planes;
  if (v.pan_blk < 0) {
    v.pan_blk = g1::Alloc(1024);
    v.pan_dummy = v.pan_blk >= 0 ? g1::Alloc(uint32_t(P) * g1::kVideoState) : -1;
    if (v.pan_blk >= 0) v.parts.push_back(v.pan_blk);
    if (v.pan_dummy >= 0) v.parts.push_back(v.pan_dummy);
  }
  if (v.pan_dummy < 0) {
    LOG("screen: no room for the arms' own pan (head turns go from the phone)");
    v.pan_failed = true;
    return;
  }
  uint32_t code = uint32_t(v.pan_blk), data = code + 768, rec = data + 64, dummy = uint32_t(v.pan_dummy);
  // the pan record: redraw, every plane's rows the same, from reset states (a fresh encoder's first record of a
  // blank picture: "same" against nothing is "same" against anything)
  std::vector<uint8_t> r;
  {
    g1::PlaneVideoEncoder fresh(v.cw, h_, P);
    r = fresh.Encode(std::vector<uint8_t>(size_t(v.cw) * size_t(h_), 0), true, {}, 96);
  }
  if (r.empty() || r.size() > 96) {
    LOG("screen: the pan record is %zu bytes (96 room): no arm pan", r.size());
    v.pan_failed = true;
    return;
  }
  std::vector<uint8_t> dt(32, 0);
  for (int k = 0; k < P; k++) {
    Put32(dt, size_t(4 * k), uint32_t(v.parts[size_t(k)]));
    Put32(dt, size_t(16 + 4 * k), dummy + uint32_t(k) * g1::kVideoState);
  }
  const int maxx = std::max(0, v.cw - w_);
  int started = 0, present = 0;
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    if (!arm.GetState().present) continue;
    present++;
    int py = i == 0 ? y_ + std::max(0, -dy_) : y_ + std::max(0, dy_);
    g1::Program p(code, 40);
    auto W = [&](int k) { return g1::ArenaWord(data / 4 + uint32_t(k)); };
    auto C = [&](int64_t x) { return p.Const(uint32_t(x)); };
    auto ANCH = W(0), ACC = W(1), LT = W(2), DX = W(3), T = W(4), R = W(5), X = W(6), D = W(7), N = W(8);
    const bool report = pan_report.load();
    auto XW = g1::ArenaWord(v.xword / 4);
    auto start = p.NewLabel(), tick = p.NewLabel(), use = p.NewLabel(), skip = p.NewLabel(), c1 = p.NewLabel(),
         c2 = p.NewLabel(), c3 = p.NewLabel(), done = p.NewLabel();
    p.Bind(start);
    p.SysRead(LT, C(g1::PROP_TIME_US));
    p.Add(T, LT, C(20000));
    p.SysWrite(C(g1::PROP_TIMER), T);
    p.End();
    p.Bind(tick);
    p.SysRead(T, C(g1::PROP_TIME_US));
    p.Sub(D, T, LT);
    p.Mov(LT, T);
    p.BltU(C(100000), D, skip);          // a gap over 100 ms (a long draw): no turn guessed across it
    p.SysRead(R, C(g1::PROP_GYR_Y));     // UNKNOWN with the gyroscope off (the head-turn view off): no pan
    p.Beq(R, C(g1::UNKNOWN), skip);
    p.BgeS(R, C(2000), use);
    p.BltS(R, C(-2000), use);
    p.Jmp(skip);
    p.Bind(use);
    p.Sar(R, R, C(4));
    p.Shr(D, D, C(4));
    p.Mul(R, R, D);
    p.Add(ACC, ACC, R);
    p.BltS(ACC, C(600000000), c3);       // ~2450 px: a long turn with no anchor (the phone away) stops there
    p.Mov(ACC, C(600000000));
    p.Bind(c3);
    p.BgeS(ACC, C(-600000000), skip);
    p.Mov(ACC, C(-600000000));
    p.Bind(skip);
    p.DivS(X, ACC, C(244141));           // 16 px a degree (HeadView's): (mdps/16)(us/16) x 256e-9 x 16
    p.Sub(X, ANCH, X);
    p.BgeS(X, C(0), c1);
    p.Mov(X, C(0));
    p.Bind(c1);
    p.BltS(X, C(maxx + 1), c2);
    p.Mov(X, C(maxx));
    p.Bind(c2);
    if (report) { // every fifth tick (100 ms): the window's x, to compare the eyes (pan_report)
      auto quiet = p.NewLabel();
      p.Add(N, N, C(1));
      p.BltU(N, C(5), quiet);
      p.Mov(N, C(0));
      p.Notify(C(kPanReport), X);
      p.Bind(quiet);
    }
    p.Sub(X, C(v.px[size_t(i)]), X);     // the panel x of the canvas's left
    p.Beq(X, DX, done);
    p.Mov(DX, X);
    p.Mov(XW, X);
    p.VideoX(C(rec + 96), C(v.stride), C(v.cw), C(h_), XW, C(py), C(v.levels_at), C(rec), C(r.size()),
             C(rec + 96 + 16));
    p.Bind(done);
    p.Add(T, T, C(20000));
    p.SysWrite(C(g1::PROP_TIMER), T);
    p.End();
    p.On(g1::TRIGGER_START, start);
    p.On(g1::TRIGGER_TIMER, tick);
    p.Changes(data, 36);
    p.Changes(v.xword, 4);
    p.Changes(dummy, uint32_t(P) * g1::kVideoState);
    std::string why;
    if (!p.Valid(&why) || p.Here() > data) {
      LOG("screen: the pan program: %s", why.empty() ? "too large" : why.c_str());
      break;
    }
    std::vector<uint8_t> words(36, 0), xw(4);
    int win = std::clamp(v.cwin, 0, maxx);
    Put32(words, 0, uint32_t(win));                         // ANCH: the window shown now
    Put32(words, 12, uint32_t(v.px[size_t(i)] - win));      // DX: where it is drawn now
    Put32(xw, 0, uint32_t(v.px[size_t(i)] - win));
    arm.WriteArena(data, words);
    arm.WriteArena(v.xword, xw);
    arm.WriteArena(rec, r);
    arm.WriteArena(rec + 96, dt);
    arm.SysWrite(g1::PROP_GYRO_RATE, 52); // both arms' (the head-turn view reads one)
    if (!arm.Load(p) || !arm.Start(code)) {
      LOG("screen: the pan program did not start on arm %d", i);
      break;
    }
    started++;
  }
  v.pan_data = data;
  v.pan_table = code;
  v.pan_running = started > 0;
  if (started < present) { // both eyes or neither
    StopPan();
    v.pan_failed = true;
  } else if (started) {
    LOG("screen: the arms pan the panorama themselves (their gyroscopes, the phone's anchors)");
  }
}

void ScreenMirror::StopPan() {
  Video &v = video_;
  for (g1::Arm *a : {&g1::left, &g1::right})
    if (a->GetState().connected) {
      a->Stop();
      a->SysWrite(g1::PROP_GYRO_RATE, 0); // the head-turn view asks again for its arm while it runs
    }
  if (v.pan_running) {
    v.pan_running = false;
    v.cwin = -1; // the phone moves the window again: written at the next frame
    v.redraw = true;
  }
}

int ScreenMirror::FrameCanvas(const uint8_t *rgba, int w, int h, int stride, const ScreenOptions &o) {
  Video &v = video_;
  int ready = VideoReady();
  if (ready != kShown) return ready;
  ScreenOptions oc = o;
  oc.src_x = 0;
  oc.src_w = w;
  oc.fit = ScreenOptions::kStretch;
  oc.zoom = 100;
  oc.cursor_x = oc.cursor_y = -1;
  Rect c = Base(rgba, w, h, stride, oc, v.cw, h_, &cluma_, &crot_, &cbase_);
  Finish(cbase_, v.cw, c, oc, 1, &cframe_);
  if (o.head && !v.pan_running && !v.pan_failed && v.cwin >= 0) StartPan();
  else if (!o.head && v.pan_running) StopPan();
  int win = std::clamp(o.src_x, 0, std::max(0, v.cw - w_));
  bool moved = win != v.cwin;
  if (!moved && !v.redraw && cframe_ == cprev_) return kSame;
  if (v.pan_running && moved) {
    // the phone's anchor: the arms' own pan moves the window there now and follows their gyroscopes until the next
    std::vector<uint8_t> anchor(8, 0);
    Put32(anchor, 0, uint32_t(std::clamp(win + std::clamp(o.pan_lead, -96, 96), 0, std::max(0, v.cw - w_))));
    for (g1::Arm *a : {&g1::left, &g1::right})
      if (a->GetState().present) a->WriteArena(v.pan_data, anchor);
    v.cwin = win;
    if (!v.redraw && cframe_ == cprev_) {
      Count(8, 1);
      return kShown;
    }
    moved = false; // the content goes as usual; the arms redraw at the new place themselves
  }
  for (size_t i = 0; i < v.pix.size(); i++) v.pix[i] = v.index[cframe_[i]];
  std::vector<uint8_t> rec = v.penc->Encode(v.pix, v.redraw || moved, {}, v.room);
  if (rec.empty()) return kBusy;
  int64_t lead = std::min<int64_t>(v.lead + int64_t(rec.size()) * 1000000 / 40000, 1000000);
  std::vector<uint8_t> msg(kHeader + rec.size());
  Put32(msg, 4, v.k);
  Put32(msg, 8, uint32_t(rec.size()));
  std::memcpy(msg.data() + kHeader, rec.data(), rec.size());
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    if (!arm.GetState().present) continue;
    if (moved && !v.pan_running) {
      std::vector<uint8_t> word(4);
      Put32(word, 0, uint32_t(v.px[size_t(i)] - win));
      arm.WriteArena(v.xword, word);
    }
    Put32(msg, 0, arm.DeviceTime(lead));
    arm.WriteArena(v.buf, msg);
    arm.Call(v.call[size_t(i)]);
  }
  v.cwin = win;
  v.redraw = false;
  v.k++;
  v.sent = int(msg.size());
  cprev_ = cframe_;
  Count(v.sent, 1);
  return kShown;
}

// Whether a record may go now: both arms connected with little queued; a rebooted arm (its bitmap and decoder
// state lost) starts the path again.
int ScreenMirror::VideoReady() {
  Video &v = video_;
  int64_t lead = 30000;
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    auto st = arm.GetState();
    if (!st.present) continue;
    if (!st.connected || st.queued > kQueued) {
      stats_.busy++;
      link_busy_ = true; // the link could not keep up: moving frames get smaller (Tune)
      return kBusy;
    }
    if (st.epoch_changes != v.epochs[size_t(i)]) {
      LOG("screen: arm %d rebooted, the VIDEO path starts again", i);
      DropPath();
      return Path(want_levels_ ? want_levels_ : 2) ? kBusy : kSame;
    }
    lead = std::max<int64_t>(lead, st.interval_us + st.clock_error_us + 10000);
  }
  v.lead = lead;
  return kShown;
}

// frame_ as a VIDEO record to both arms, drawn at the same due time; false (nothing sent, the encoder as it was)
// when the record would be larger than max_bytes.
bool ScreenMirror::SendVideo(size_t max_bytes) {
  Video &v = video_;
  for (size_t i = 0; i < v.pix.size(); i++) v.pix[i] = frame_[i] >= 128;
  std::vector<uint8_t> rec = v.enc->Encode(v.pix, v.redraw, std::min<size_t>(max_bytes, kRecordMax));
  if (rec.empty()) return false;
  // The record's time on the link at about 40 KB/s (2M PHY, short intervals); an arm that gets it later draws it
  // as soon as it is in, so a short lead only costs the eyes' sync on large changes, a long one the frame rate.
  int64_t lead = std::min<int64_t>(v.lead + int64_t(rec.size()) * 1000000 / 40000, 1000000);
  // A transition asked for: this one decoded off the panel, then revealed from the bitmap.
  bool reveal = reveal_style_ && fx_ && g1::NowUs() < reveal_until_;
  std::vector<uint8_t> msg(kHeader + rec.size());
  Put32(msg, 4, v.k);
  Put32(msg, 8, uint32_t(rec.size()));
  std::memcpy(msg.data() + kHeader, rec.data(), rec.size());
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    if (!arm.GetState().present) continue;
    Put32(msg, 0, arm.DeviceTime(lead));
    arm.WriteArena(v.buf, msg);
    arm.Call(reveal ? v.hidden[size_t(i)] : v.call[size_t(i)]);
  }
  if (reveal) {
    // The decode takes up to about 40 ms for a whole picture (VM.md §3.7).
    fx_->In(Fx::Style(reveal_style_), Region(), Fx::Source{v.bitmap, v.stride, 1}, g1::NowUs() + lead + 40000);
    LOG("screen: revealed (%d)", reveal_style_);
    // The transition draws the picture without the arrow: it comes back once that is done.
    ptr_redo_us_ = std::max<int64_t>(fx_->busy_until(), g1::NowUs() + lead + 40000) + 20000;
  }
  reveal_style_ = 0;
  v.redraw = false;
  v.k++;
  v.sent = int(msg.size());
  return true;
}

void ScreenMirror::Count(int bytes, int detail) {
  frames_++;
  stats_.bytes = bytes;
  stats_.detail = detail;
  int64_t now = g1::NowUs();
  if (!window_us_) window_us_ = now;
  window_bytes_ += bytes;
  if (now - window_us_ >= 2000000) {
    stats_.rate = int(int64_t(window_bytes_) * 1000000 / (now - window_us_));
    window_us_ = now;
    window_bytes_ = 0;
  }
}

void ScreenMirror::Reveal(int style, std::shared_ptr<Fx> fx) {
  std::lock_guard<std::mutex> lock(mutex_);
  wiped_until_ = 0;
  reveal_style_ = style;
  fx_ = std::move(fx);
  reveal_until_ = g1::NowUs() + 4000000;
}

void ScreenMirror::Wiped() {
  std::lock_guard<std::mutex> lock(mutex_);
  // No frame over the transition until its reveal (or another path takes over); 1.5 s at most.
  wiped_until_ = g1::NowUs() + 1500000;
  prev_.clear();
  last_base_.clear();
  video_.redraw = true;
  if (levels_ == 16 && pic_) { // the library's picture of the display: black, so the next frame goes whole
    sk::gray::Clear();
    sk::Flush();
  }
}

Fx::Region ScreenMirror::FxRegion() {
  std::lock_guard<std::mutex> lock(mutex_);
  return Region();
}

void ScreenMirror::Pointer(int x, int y, bool on) {
  std::lock_guard<std::mutex> lock(mutex_);
  x = std::clamp(x, 0, 10000);
  y = std::clamp(y, 0, 10000);
  if (x == ptr_cx_ && y == ptr_cy_ && on == ptr_on_ && !ptr_dirty_ && !ptr_redo_us_) return;
  ptr_cx_ = x;
  ptr_cy_ = y;
  ptr_on_ = on;
  ptr_dirty_ = true;
  SendPointer(false);
}

void ScreenMirror::SetOverlay(std::vector<uint8_t> pixels, int w, int h, int x, int y) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (w <= 0 || h <= 0 || pixels.size() < size_t(w) * size_t(h)) pixels.clear(), w = h = 0;
  over_ = std::move(pixels);
  over_w_ = w;
  over_h_ = h;
  over_x_ = x;
  over_y_ = y;
}

// mutex_ held.
void ScreenMirror::Compose() {
  if (frame_.size() != size_t(w_) * size_t(h_)) return;
  if (!over_.empty()) {
    int x0 = std::max(0, over_x_), x1 = std::min(w_, over_x_ + over_w_);
    int y0 = std::max(0, over_y_), y1 = std::min(h_, over_y_ + over_h_);
    for (int y = y0; y < y1; y++)
      for (int x = x0; x < x1; x++)
        frame_[size_t(y) * size_t(w_) + size_t(x)] = over_[size_t(y - over_y_) * size_t(over_w_) + size_t(x - over_x_)];
  }
  if (!hud_.empty()) { // see-through: 0 leaves the picture
    int x0 = std::max(0, hud_x_), x1 = std::min(w_, hud_x_ + hud_w_);
    int y0 = std::max(0, hud_y_), y1 = std::min(h_, hud_y_ + hud_h_);
    for (int y = y0; y < y1; y++) {
      const uint8_t *s = hud_.data() + size_t(y - hud_y_) * size_t(hud_w_) - size_t(hud_x_);
      uint8_t *d = frame_.data() + size_t(y) * size_t(w_);
      for (int x = x0; x < x1; x++)
        if (s[x]) d[x] = s[x];
    }
  }
}

bool ScreenMirror::PointerPending() {
  std::lock_guard<std::mutex> lock(mutex_);
  if (ptr_dirty_ || (ptr_redo_us_ && g1::NowUs() >= ptr_redo_us_)) SendPointer(false);
  return started_ && (ptr_dirty_ || ptr_redo_us_);
}

bool ScreenMirror::Shows(int out[6]) {
  int v[8] = {};
  if (!Shows8(v)) return false;
  std::copy(v, v + 6, out);
  return true;
}

bool ScreenMirror::Shows8(int out[8]) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!started_ || src_w_ <= 1 || src_h_ <= 1) return false;
  int ow = 0, oh = 0, ox = 0, oy = 0;
  Rect r = ssh::Shows(src_w_, src_h_, src_o_, w_, h_, &ow, &oh, &ox, &oy);
  if (ow <= 0 || oh <= 0) return false;
  out[0] = r.x;
  out[1] = r.y;
  out[2] = r.x + r.w;
  out[3] = r.y + r.h;
  out[4] = ow;
  out[5] = oh;
  out[6] = ox;
  out[7] = oy;
  return true;
}

void ScreenMirror::SetHud(std::vector<uint8_t> pixels, int w, int h, int x, int y) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (w <= 0 || h <= 0 || pixels.size() < size_t(w) * size_t(h)) pixels.clear(), w = h = 0;
  hud_ = std::move(pixels);
  hud_w_ = w;
  hud_h_ = h;
  hud_x_ = x;
  hud_y_ = y;
}

// The cursor to both arms: their code puts the picture back where the arrow was and draws it at its new place
// (mutex_ held). Only on the black and white path with the panels on; while a link is busy it waits (dirty) for the
// next call, so a quick stroke sends its latest place, not every step. force: drawn again even where it is.
void ScreenMirror::SendPointer(bool force) {
  Video &v = video_;
  int64_t now = g1::NowUs();
  if (ptr_redo_us_ && now >= ptr_redo_us_) {
    ptr_redo_us_ = 0;
    force = true;
  }
  if (started_ && levels_ == 1 && v.v66 && !hidden_ && !paused_ && !v.redraw && !v.shown.empty()) {
    // greys on 0.5.66+: a record of unchanged planes that moves the arms' sprite (a few bytes)
    int x = 0, y = 0;
    bool on = ptr_on_ && src_w_ > 1 && Locate(ptr_cx_, ptr_cy_, src_w_, src_h_, src_o_, w_, h_, &x, &y);
    if (!force && !ptr_dirty_ && ((on && x - 1 == v.spr_x && y - 1 == v.spr_y) || (!on && v.spr_x == -32768))) return;
    if (!force && on && v.spr_x == -32768 && v.rw && x - 1 < v.rx + v.rw && x + 15 > v.rx && y - 1 < v.ry + v.rh &&
        y + 15 > v.ry) {
      ptr_dirty_ = false; // hidden over the video, and already not drawn
      return;
    }
    if (VideoReady() != kShown) return;
    v.pix = v.shown;
    if (SendVideoX(v.room, {}, true)) ptr_dirty_ = false;
    return;
  }
  if (!started_ || levels_ != 2 || !v.ptr_state || hidden_ || paused_) return; // greys: in the next frame
  if (ptr_redo_us_ || v.redraw) return; // a transition draws the picture meanwhile, or the panels lost it
  int x = 0, y = 0;
  bool on = ptr_on_ && src_w_ > 1 && Locate(ptr_cx_, ptr_cy_, src_w_, src_h_, src_o_, w_, h_, &x, &y);
  x -= 1; // the arrow's tip is (1, 1) of its box
  y -= 1;
  if (!force && v.ptr_shown == int(on) && (!on || (x == v.ptr_x && y == v.ptr_y))) {
    ptr_dirty_ = false;
    return;
  }
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    auto st = arm.GetState();
    if (st.present && (!st.connected || st.queued > 3)) return; // later (still dirty)
  }
  std::vector<uint8_t> words(12);
  Put32(words, 0, uint32_t(x));
  Put32(words, 4, uint32_t(y));
  Put32(words, 8, on ? 1u : 0u);
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    if (!arm.GetState().present || !v.ptr_call[size_t(i)]) continue;
    arm.WriteArena(v.ptr_state, words);
    arm.Call(v.ptr_call[size_t(i)]);
  }
  v.ptr_x = x;
  v.ptr_y = y;
  v.ptr_shown = on ? 1 : 0;
  ptr_dirty_ = false;
}

// mutex_ held.
Fx::Region ScreenMirror::Region() const {
  Fx::Region r;
  r.w = w_;
  r.h = h_;
  r.at[0] = {x_ - std::max(0, -dx_), y_ + std::max(0, -dy_)};
  r.at[1] = {x_ - std::max(0, dx_), y_ + std::max(0, dy_)};
  return r;
}

ScreenMirror::Stats ScreenMirror::stats() {
  std::lock_guard<std::mutex> lock(mutex_);
  Stats s = stats_;
  s.frames = frames_;
  if (window_us_ && g1::NowUs() - window_us_ > 4000000) s.rate = 0; // nothing sent lately
  s.w = w_;
  s.h = h_;
  s.x = x_;
  s.y = y_;
  s.path = started_ ? levels_ : 0;
  s.hidden = hidden_ ? 1 : 0;
  s.pan = started_ && levels_ == kCanvasPath && video_.pan_running ? 1 : 0;
  return s;
}

bool ScreenMirror::Preview(std::vector<uint8_t> *out, int *w, int *h) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!started_ || hidden_ || prev_.size() != size_t(w_) * h_ || prev_.empty()) return false;
  *out = prev_;
  *w = w_;
  *h = h_;
  return true;
}

std::string ScreenMirror::Check() {
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!started_) return "the phone's screen is not on the glasses\n";
    if (hidden_) return "the panels are off: " + hide_why_ + "\n";
    paused_ = true; // no frames while the arms answer: the library's picture stays the one they are asked about
  }
  std::string out;
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    std::string name = i == 0 ? "left eye" : "right eye";
    auto st = arm.GetState();
    if (!st.present) continue;
    if (!st.connected) {
      out += name + ": not connected\n";
      continue;
    }
    uint32_t got = 0, want = 0;
    if (!arm.PanelCrc(0, 480, &got, &want)) out += name + ": no answer (panel off?)\n";
    else out += name + (got == want ? ": shows what was sent\n" : ": differs from what was sent\n");
  }
  {
    std::lock_guard<std::mutex> lock(mutex_);
    paused_ = false;
  }
  LOG("screen: check: %s", out.c_str());
  return out;
}

void ScreenMirror::Stop(bool keep_panels) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!started_) return;
  started_ = false;
  DropPath(keep_panels);
  sk::gray::Clear();
  sk::Flush();
  if (hidden_) Panels(true); // the terminal's program decides from here
  hidden_ = false;
}

int ScreenMirror::frames() {
  std::lock_guard<std::mutex> lock(mutex_);
  return frames_;
}

std::string ScreenMirror::why() {
  std::lock_guard<std::mutex> lock(mutex_);
  return hidden_ ? hide_why_ : error_;
}

// Heads-up on a screen: the panels go this many degrees below the angle (after kLookHoldUs), as the terminal's.
constexpr int kLookDown = 5;
constexpr int64_t kLookHoldUs = 1000000;

bool ScreenMirror::Hidden(const ScreenOptions &o) {
  int64_t now = g1::NowUs();
  if (props_us_ && now - props_us_ < (o.look_up ? 200000 : 1000000)) return props_hidden_;
  props_us_ = now;
  bool in_case = false, known = false, folded = true, up = false, pitched = false;
  for (g1::Arm *a : {&g1::left, &g1::right}) {
    g1::Arm::State s = a->GetState();
    if (!s.connected) continue;
    const uint8_t ids[3] = {g1::PROP_IN_CASE, g1::PROP_WORN, g1::PROP_PITCH};
    uint32_t v[3] = {g1::UNKNOWN, g1::UNKNOWN, g1::UNKNOWN};
    bool got = a->Props(ids, v);
    if (got && o.look_up && v[2] != g1::UNKNOWN) { // either eye up is up
      pitched = true;
      int pitch = int32_t(v[2]);
      if (pitch >= o.angle || (head_up_ && pitch >= o.angle - kLookDown)) up = true;
    }
    // The arm's IN_CASE now; State::in_case is only what its session's first message said (the glasses
    // connected in the case and taken out since: still true), so it counts only without the property.
    if (got && v[0] != g1::UNKNOWN) {
      if (v[0] == 1) in_case = true;
    } else if (s.in_case) {
      in_case = true;
    }
    if (!got) continue;
    if (v[1] != g1::UNKNOWN) {
      known = true;
      folded = folded && v[1] == 0;
    }
  }
  // Heads-up: up shows at once; down hides after the hold (a glance down at the keys keeps it a moment).
  bool by_head = false;
  if (!o.look_up || !pitched || o.hold || up) {
    head_up_ = true;
    head_down_us_ = 0;
  } else if (head_up_) {
    if (!head_down_us_) head_down_us_ = now;
    if (now - head_down_us_ >= kLookHoldUs) head_up_ = false;
  }
  by_head = o.look_up && !head_up_;
  bool by_case = o.hide_case && in_case, by_fold = o.hide_folded && known && folded;
  hide_why_ = by_case ? "in the case" : by_fold ? "folded" : by_head ? "heads-up: head down" : "";
  props_hidden_ = by_case || by_fold || by_head;
  return props_hidden_;
}

bool ScreenMirror::ApplyHidden(bool hide) {
  if (hide == hidden_) return false;
  hidden_ = hide;
  if (hide) {
    LOG("screen: panels off (%s)", hide_why_.c_str());
    sk::gray::Clear();
    sk::Flush();
    Panels(false);
    return false;
  }
  LOG("screen: panels on again");
  Panels(true);
  prev_.clear();
  video_.redraw = true; // the power-on cleared the panels: every row again
  return true;
}

int ScreenMirror::Look(const ScreenOptions &o) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!started_ || paused_) return 0;
  return ApplyHidden(Hidden(o)) ? 1 : 0;
}

int ScreenMirror::Frame(const uint8_t *rgba, int w, int h, int stride, const ScreenOptions &o) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!started_) return kStopped;
  if (paused_) return kBusy;
  if (wiped_until_ && g1::NowUs() < wiped_until_) return kBusy;
  wiped_until_ = 0;
  if (w <= 0 || h <= 0 || stride < w * 4) return kSame;
  if (seen_++ == 0) LOG("screen: first frame, %d x %d", w, h);
  bool hide = Hidden(o);
  ApplyHidden(hide);
  if (hide) return kHidden;
  // A panorama (jni.cpp gives its window at the picture's size): on 0.5.66+ the whole panorama goes to the arms
  // once and a head turn only moves the window (an arena word), a few bytes instead of a picture.
  bool canvas = !canvas_failed_ && o.src_w == w_ && o.src_h == h_ && w > o.src_w && w <= 2048 && o.src_y >= 0 &&
                o.src_y + h_ <= h && HasVideoX66();
  canvas_w_ = canvas ? w : 0;
  if (canvas && Path(kCanvasPath)) return FrameCanvas(rgba, w, h, stride, o);
  if (!Path(o.levels)) return kSame;
  // Where the cursor lands follows the screen's size and the options that place it.
  if (w != src_w_ || h != src_h_ || o.fit != src_o_.fit || o.pan_x != src_o_.pan_x || o.pan_y != src_o_.pan_y ||
      o.zoom != src_o_.zoom || o.rotate != src_o_.rotate || o.out_x != src_o_.out_x || o.out_y != src_o_.out_y ||
      o.out_w != src_o_.out_w || o.out_h != src_o_.out_h || o.crop_top != src_o_.crop_top ||
      o.crop_bottom != src_o_.crop_bottom || o.src_x != src_o_.src_x || o.src_y != src_o_.src_y ||
      o.src_w != src_o_.src_w || o.src_h != src_o_.src_h)
    ptr_dirty_ = true;
  src_w_ = w;
  src_h_ = h;
  src_o_ = o;
  // The cursor: the arms draw it themselves on the black and white path; greys get it in the picture.
  ScreenOptions oc = o;
  bool drawn_in = levels_ != 2 && ptr_on_ && !(levels_ == 1 && video_.v66); // 0.5.66 greys: the arms' sprite
  oc.cursor_x = drawn_in ? ptr_cx_ : -1;
  oc.cursor_y = drawn_in ? ptr_cy_ : -1;
  Rect c = Base(rgba, w, h, stride, oc, w_, h_, &luma_, &rot_, &base_);
  bool still = base_ == last_base_; // the same screen as last time: nothing moved
  if (!still) last_base_ = base_;

  if (levels_ == 2 || levels_ == 1) {
    int ready = VideoReady();
    if (ready != kShown) return ready;
    int r = levels_ == 2 ? FrameVideo(o, c, still) : FrameVideoX(o, c, still);
    if ((r == kShown || r == kLater) && !first_logged_) {
      first_logged_ = true;
      LOG("screen: first picture sent %lld ms after the start (%d bytes)", (long long)((g1::NowUs() - start_us_) / 1000),
          video_.sent);
    }
    // The cursor after the picture it goes over (the first one, a redraw).
    if (levels_ == 2 && r != kBusy && (ptr_dirty_ || video_.ptr_shown < 0 || (ptr_redo_us_ && g1::NowUs() >= ptr_redo_us_)))
      SendPointer(false);
    return r;
  }
  return FrameGray(o, c);
}

// A frame on the black and white path, the link ready (mutex_ held).
int ScreenMirror::FrameVideo(const ScreenOptions &o, Rect c, bool still) {
  bool redraw = video_.redraw;
  if (o.detail >= 1) {
    int n = std::clamp(o.detail, 1, 4);
    Make(o, c, n);
    Compose();
    if (frame_ == prev_ && !redraw) return kSame;
    if (!SendVideo(kRecordMax)) return kBusy; // too large for one record: the next, smaller change goes
    prev_ = frame_;
    prev_detail_ = n;
    Count(video_.sent, n);
    return kShown;
  }
  // Auto: every pixel when that fits the budget, else coarser; nothing moved: every pixel whatever the size.
  size_t budget = still ? kRecordMax : size_t(std::clamp(o.budget, 1, 64)) * 1024;
  for (int n = 1; n <= 4; n++) {
    Make(o, c, n);
    Compose();
    if (frame_ == prev_ && !redraw) return n == 1 ? kSame : kLater;
    if (SendVideo(n == 4 ? kRecordMax : budget)) {
      prev_ = frame_;
      prev_detail_ = n;
      Count(video_.sent, n);
      return n == 1 ? kShown : kLater;
    }
  }
  return kBusy;
}

// Self-tuning: a moving frame's budget follows the link. A frame that found the link still busy shrinks it by a
// fifth (down to kTuneMin), five frames in a row that did not grow it by 256 bytes (up to kTuneMax): moving frames
// stay as large as the link carries at the frame rate, so nothing waits behind them (the newest frame wins).
size_t ScreenMirror::Tune() {
  if (link_busy_) {
    tuned_budget_ = std::max(kTuneMin, tuned_budget_ * 4 / 5);
    calm_frames_ = 0;
  } else if (++calm_frames_ >= 5) {
    tuned_budget_ = std::min(kTuneMax, tuned_budget_ + 256);
    calm_frames_ = 0;
  }
  link_busy_ = false;
  return tuned_budget_;
}

// A frame on the grey fast path (VIDEOX), the link ready (mutex_ held): as FrameVideo, with 16 levels kept steady
// (a pixel changes only by more than one level) so noise does not cost planes.
int ScreenMirror::FrameVideoX(const ScreenOptions &o, Rect c, bool still) {
  bool redraw = video_.redraw;
  Video &v = video_;
  g1::RowMove move;
  bool region = false; // a video in part of the picture: that part by VIDEOT after the rest (below)
  // Auto (detail 0) at 16 greys: while most of the picture keeps changing (video, games), the top two planes at
  // half detail and the low two left as the arms hold them (each pixel within 3 of its 16 levels: about a
  // quarter of the bytes); 300 ms after the motion stops the exact picture follows (the refinement, below).
  if (o.detail == 0 && v.planes == 4 && !redraw && prev_.size() == size_t(w_) * size_t(h_)) {
    Make(o, c, 1);
    Compose();
    int64_t now = g1::NowUs();
    int rows = 0;
    if (exact_.size() == frame_.size()) {
      for (int y = 0; y < h_; y++)
        rows += std::memcmp(frame_.data() + size_t(y) * size_t(w_), exact_.data() + size_t(y) * size_t(w_), size_t(w_)) != 0;
    }
    // 0.5.68+: a part that keeps changing while the rest holds still (a video in a page) goes by VIDEOT on its own,
    // so the text around it keeps its 16 greys and full detail
    Rect vr;
    const bool seen = v.tw && hud_.empty() && VideoRegion(&vr);
    // a new rectangle: the first, or one the video outgrew (one still inside the rectangle in use keeps it)
    const bool found = seen && (!v.rw || vr.x < v.rx || vr.y < v.ry || vr.x + vr.w > v.rx + v.rw || vr.y + vr.h > v.ry + v.rh);
    if (found) {
      const int ew = std::min(v.tw, (vr.w + 15) / 16 * 8), eh = std::min(v.th, (vr.h + 15) / 16 * 8);
      v.rw = 2 * ew;
      v.rh = 2 * eh;
      v.rx = std::clamp(vr.x, 0, std::max(0, w_ - v.rw));
      v.ry = std::clamp(vr.y, 0, std::max(0, h_ - v.rh));
      LOG("screen: a video at (%d, %d) %d x %d goes as transform-coded video, the rest at 16 greys", v.rx, v.ry, v.rw, v.rh);
    }
    if (v.rw) {
      bool inside = false;
      if (exact_.size() == frame_.size()) {
        const int x0 = std::max(0, v.rx), x1 = std::min(w_, v.rx + v.rw);
        for (int y = std::max(0, v.ry); y < std::min(h_, v.ry + v.rh) && !inside && x1 > x0; y++)
          inside = std::memcmp(frame_.data() + size_t(y) * size_t(w_) + size_t(x0),
                               exact_.data() + size_t(y) * size_t(w_) + size_t(x0), size_t(x1 - x0)) != 0;
      }
      if (inside) region_until_us_ = now + kMotionHoldUs;
      const bool whole_moves = !seen && rows * 10 > h_ * 4 &&
                               std::count_if(heat_.begin(), heat_.end(), [](uint8_t hb) { return std::popcount(hb) >= 4; }) * 10 >
                                   int64_t(heat_.size()) * 7;
      if (now >= region_until_us_ || whole_moves) { // the video stopped (or everything moves): the exact picture
        LOG("screen: the video at (%d, %d) stopped: the exact picture again", v.rx, v.ry);
        v.rw = 0;
        v.redraw = redraw = true;
      } else {
        region = true;
        moving_frames_ = 0;
        moving_until_us_ = 0;
      }
    }
    if (!region && exact_.size() == frame_.size()) {
      if (rows * 10 > h_ * 4) {
        if (++moving_frames_ >= 2) moving_until_us_ = now + kMotionHoldUs;
      } else {
        moving_frames_ = 0;
      }
    }
    exact_ = frame_;
    if (!region && now < moving_until_us_ && v.tw && hud_.empty()) {
      size_t budget = std::min<size_t>(v.room, Tune());
      if (SendVideoT(o, budget, Rect{0, 0, w_, h_})) {
        Count(video_.sent, 2);
        return kLater; // the exact picture follows once it holds still
      }
      return v.tlast == v.tluma ? kLater : kBusy;
    }
    if (!region && now < moving_until_us_ && v.mw) {
      Make(o, c, 2);
      Compose();
      if (v.in_motion && frame_ == prev_ && prev_detail_ == 2) return kLater; // the refinement comes when it holds
      if (!v.in_motion) v.mredraw = true;
      if (SendMotion(v.room)) {
        prev_ = frame_;
        prev_detail_ = 2;
        Count(video_.sent, 2);
        return kLater;
      }
      return kBusy;
    }
    if (!region && now < moving_until_us_) {
      size_t budget = std::min<size_t>(v.room, Tune());
      LevelMap(true);
      for (int n = 2; n <= 4; n++) {
        Make(o, c, n);
        Compose();
        if (frame_ == prev_ && prev_detail_ == n) return kLater; // nothing new: the refinement comes when it holds
        for (size_t i = 0; i < v.pix.size(); i++) v.pix[i] = uint8_t((v.index[frame_[i]] & 0xC) | (v.shown[i] & 0x3));
        if (SendVideoX(n == 4 ? v.room : budget, {}, true)) {
          prev_ = frame_;
          prev_detail_ = n;
          Count(video_.sent, n);
          return kLater; // the exact picture follows once it holds still
        }
      }
      return kBusy;
    }
  } else if (v.rw) { // a pinned detail or a redraw: no video rectangle
    v.rw = 0;
    v.redraw = redraw = true;
  }
  // The scroll is found on the exact picture; "steady" (a pixel changed by one level keeps its old one: noise from a
  // camera or video) applies only when nothing scrolled, since it would break the shifted rows' match.
  if (v.coarse_map) {
    LevelMap(false); // the motion ended: the exact map, every row drawn again with the exact picture
    redraw = true;
  }
  g1::BlockMove block;
  auto make = [&](int n) {
    Make(o, c, n);
    Compose();
    move = {};
    block = {};
    if (region && prev_.size() == frame_.size()) { // the video's rectangle as the planes hold it: VIDEOT draws it
      const int x0 = std::max(0, v.rx), x1 = std::min(w_, v.rx + v.rw);
      for (int y = std::max(0, v.ry); y < std::min(h_, v.ry + v.rh) && x1 > x0; y++)
        std::memcpy(frame_.data() + size_t(y) * size_t(w_) + size_t(x0), prev_.data() + size_t(y) * size_t(w_) + size_t(x0),
                    size_t(x1 - x0));
    }
    if (redraw || prev_.size() != frame_.size()) return;
    if (n == 1) {
      for (size_t i = 0; i < v.pix.size(); i++) v.pix[i] = v.index[frame_[i]];
      move = v.penc->FindMove(v.pix);
      // 0.5.66+: a dragged window or a sideways scroll when nothing scrolled up or down
      if (!move.rows && v.v66) block = v.penc->FindBlockMove(v.pix);
    }
    if (!move.rows && !block.w && o.steady && v.planes == 4 && prev_detail_ == n) Steady(&frame_, prev_, w_, h_);
  };
  // The detail asked for (auto: every pixel when that fits the budget, every pixel whatever the size when nothing
  // moved), coarser only when a record would not fit the arms' room: a picture always goes.
  auto planes = [&]() -> int {
    int first = o.detail >= 1 ? std::clamp(o.detail, 1, 4) : 1, last = 4;
    size_t room = video_.room, budget = still || o.detail >= 1 ? room : std::min<size_t>(room, size_t(std::clamp(o.budget, 1, 48)) * 1024);
    for (int n = first; n <= last; n++) {
      make(n);
      if (frame_ == prev_ && !redraw) return n == 1 ? kSame : kLater;
      if (SendVideoX(n == last ? room : budget, move, false, block)) {
        prev_ = frame_;
        prev_detail_ = n;
        Count(video_.sent, n);
        return n == 1 ? kShown : kLater;
      }
    }
    return kBusy;
  };
  int res = planes();
  if (!region || res == kBusy) return res;
  // the video's rectangle after the rest (a record of the rest that drew over it asked for its every band)
  if (SendVideoT(o, std::min<size_t>(v.room, Tune()), Rect{v.rx, v.ry, v.rw, v.rh})) Count(video_.sent, 2);
  return kLater; // until the video stops: then the exact picture
}

// A frame of greys (mutex_ held): the library uploads the changed pixels (4 bits each); a transition's reveal is
// left out.
int ScreenMirror::FrameGray(const ScreenOptions &o, Rect c) {
  reveal_style_ = 0;
  int n = std::clamp(o.detail, 1, 4);
  Finish(base_, w_, c, o, n, &frame_);
  if (o.steady && o.levels != 4 && o.levels != 8 && prev_.size() == frame_.size()) Steady(&frame_, prev_, w_, h_);
  Compose();
  if (frame_ == prev_) return kSame;
  int changed = 0;
  if (prev_.size() == frame_.size()) {
    for (size_t i = 0; i < frame_.size(); i++) changed += frame_[i] != prev_[i];
  } else {
    changed = int(frame_.size());
  }
  sk::gray::Write(*pic_, sk::gray::ConstBitmapView{frame_.data(), w_, h_, w_}, 0, 0, sk::gray::DITHER_NONE);
  sk::gray::Clear();
  sk::gray::Draw(*pic_, x_, y_);
  sk::Flush();
  prev_ = frame_;
  Count((changed + 1) / 2, n);
  return kShown;
}

} // namespace ssh
