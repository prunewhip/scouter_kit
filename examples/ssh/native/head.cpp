#include "head.hpp"

#include "g1/scouter_kit_g1.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <mutex>
#include <thread>

#ifdef __ANDROID__
#include <android/log.h>
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "scouter", __VA_ARGS__)
#else
#include <cstdio>
#define LOG(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif

namespace g1 = scouter_kit::g1;

namespace ssh {
namespace {

constexpr double kPxPerDeg = 16;     // the picture moves this many pixels a degree (a screen = 40 degrees across)
constexpr double kStillDps = 3;      // slower than this (after the bias) the head counts as still
constexpr uint32_t kRateHz = 26;     // GYRO_RATE while on
constexpr int kPollMs = 40;          // the sensors read this often (a request over Bluetooth each)
constexpr int64_t kIdleUs = 4000000; // no such frame for this long: off

std::mutex g_mutex;
std::atomic<bool> g_running{false};
std::atomic<int64_t> g_touched{0};
std::atomic<int> g_cols{1}, g_rows{1};
std::atomic<bool> g_wrap{false};
std::atomic<int> g_x{0}, g_y{0};
std::atomic<bool> g_center{false};
std::atomic<int> g_face{-1};

g1::Arm *Pick() {
  if (g1::right.GetState().connected) return &g1::right;
  if (g1::left.GetState().connected) return &g1::left;
  return nullptr;
}

// The start: a ring's first screen straight ahead, else the middle screen; the middle row either way.
void Home(int *x, int *y) {
  int cols = g_cols, rows = g_rows;
  *x = g_wrap ? 0 : (cols - 1) * HeadView::kScreenW / 2;
  *y = (rows - 1) * HeadView::kScreenH / 2;
}

} // namespace

HeadView &HeadView::Get() {
  static HeadView v;
  return v;
}

void HeadView::Touch(int cols, int rows, bool wrap) {
  bool changed = cols != g_cols || rows != g_rows || wrap != g_wrap;
  g_cols = std::max(1, cols);
  g_rows = std::max(1, rows);
  g_wrap = wrap;
  g_touched = g1::NowUs();
  if (changed) g_center = true;
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_running) return;
  int x, y;
  Home(&x, &y);
  g_x = x;
  g_y = y;
  g_running = true;
  std::thread([this] { Run(); }).detach();
}

bool HeadView::Window(int *x, int *y) {
  if (!g_running) return false;
  *x = g_x;
  *y = g_y;
  return true;
}

void HeadView::Center() { g_center = true; }
void HeadView::Face(int x) { g_face = std::max(0, x); }

void HeadView::Run() {
  g1::Arm *arm = Pick();
  if (arm) arm->SysWrite(g1::PROP_GYRO_RATE, kRateHz);
  LOG("head: on (%d x %d screens%s, gyroscope %u Hz)", g_cols.load(), g_rows.load(), g_wrap ? ", 360" : "", kRateHz);
  double yaw = 0, bias = 0, pitch0 = 0;
  bool pitch0_set = false;
  int reads = 0, misses = 0, logged_x = -1;
  int64_t log_at = 0;
  int still_n = 0;
  int64_t last = 0;
  while (g1::NowUs() - g_touched < kIdleUs) {
    std::this_thread::sleep_for(std::chrono::milliseconds(kPollMs));
    g1::Arm *now = Pick();
    if (now != arm) { // the other arm took over (one disconnected)
      arm = now;
      if (arm) arm->SysWrite(g1::PROP_GYRO_RATE, kRateHz);
      last = 0;
    }
    if (!arm) continue;
    const uint8_t ids[2] = {g1::PROP_GYR_Y, g1::PROP_PITCH};
    uint32_t v[2] = {g1::UNKNOWN, g1::UNKNOWN};
    if (!arm->Props(ids, v) || v[0] == g1::UNKNOWN) {
      misses++;
      continue;
    }
    reads++;
    int64_t t = g1::NowUs();
    double rate = int32_t(v[0]) / 1000.0; // degrees a second, + turning left (right-hand rule about up)
    bool has_pitch = v[1] != g1::UNKNOWN;
    double pitch = has_pitch ? double(int32_t(v[1])) : 0; // degrees, + up
    if (g_center.exchange(false) || !pitch0_set) {
      yaw = 0;
      pitch0 = pitch;
      pitch0_set = has_pitch;
    }
    int face = g_face.exchange(-1);
    if (face >= 0) { // that window straight ahead: the turn that puts it there
      int fx, fy;
      Home(&fx, &fy);
      yaw = (fx - face) / kPxPerDeg;
    }
    if (last) {
      double dt = std::min((t - last) / 1e6, 0.25);
      double r = rate - bias;
      if (std::fabs(r) < kStillDps) {
        // Still: the bias drifts toward what the gyroscope reads now (slowly, after a moment).
        if (++still_n > 10) bias += (rate - bias) * 0.05;
      } else {
        still_n = 0;
        yaw += r * dt;
      }
    }
    last = t;
    int cols = g_cols, rows = g_rows, hx, hy;
    Home(&hx, &hy);
    // Left is +: turning left shows what lies to the left (smaller x).
    double fx = hx - yaw * kPxPerDeg;
    int x;
    if (g_wrap) {
      double ring = double(cols - 1) * kScreenW; // the last column repeats the first
      fx = std::fmod(fx, ring);
      if (fx < 0) fx += ring;
      x = int(std::lround(fx)) % int(ring);
    } else {
      double edge = double(cols - 1) * kScreenW / 2 / kPxPerDeg;
      yaw = std::clamp(yaw, -edge, edge); // turning back always comes home
      x = std::clamp(int(std::lround(hx - yaw * kPxPerDeg)), 0, (cols - 1) * kScreenW);
    }
    int y = hy;
    if (rows > 1 && has_pitch) y = std::clamp(int(std::lround(hy - (pitch - pitch0) * kPxPerDeg)), 0, (rows - 1) * kScreenH);
    g_x = x;
    g_y = y;
    // A line a second while the window moves (how the head is followed: turn, bias, reads that failed).
    if (t - log_at >= 1000000 && std::abs(x - logged_x) >= 8) {
      log_at = t;
      logged_x = x;
      LOG("head: turn %.0f deg, window x %d y %d (bias %.1f dps; %d reads, %d failed)", yaw, x, y, bias, reads, misses);
    }
  }
  if (arm) arm->SysWrite(g1::PROP_GYRO_RATE, 0);
  g_running = false;
  LOG("head: off");
}

} // namespace ssh
