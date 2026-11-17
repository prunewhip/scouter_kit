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

constexpr double kEdgeDeg = 40;     // a turn this far either way reaches that edge of the panorama
constexpr double kStillDps = 3;     // slower than this (after the bias) the head counts as still
constexpr uint32_t kRateHz = 26;    // GYRO_RATE while on
constexpr int kPollMs = 40;         // the gyroscope read this often (a request over Bluetooth each)
constexpr int64_t kIdleUs = 4000000; // no panorama frame for this long: off

std::mutex g_mutex;
std::atomic<bool> g_running{false};
std::atomic<int64_t> g_touched{0};
std::atomic<int> g_screens{0};
std::atomic<int> g_pan{-1};
std::atomic<bool> g_center{false};

g1::Arm *Pick() {
  if (g1::right.GetState().connected) return &g1::right;
  if (g1::left.GetState().connected) return &g1::left;
  return nullptr;
}

} // namespace

HeadView &HeadView::Get() {
  static HeadView v;
  return v;
}

void HeadView::Touch(int screens) {
  g_screens = std::max(1, screens);
  g_touched = g1::NowUs();
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_running) return;
  g_running = true;
  g_pan = 500;
  std::thread([this] { Run(); }).detach();
}

int HeadView::Pan() { return g_running ? g_pan.load() : -1; }
int HeadView::Screens() { return g_screens; }
void HeadView::Center() { g_center = true; }

void HeadView::Run() {
  g1::Arm *arm = Pick();
  if (arm) arm->SysWrite(g1::PROP_GYRO_RATE, kRateHz);
  LOG("head: on (%d screens, gyroscope %u Hz)", g_screens.load(), kRateHz);
  double yaw = 0, bias = 0;
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
    if (g_center.exchange(false)) yaw = 0;
    const uint8_t ids[1] = {g1::PROP_GYR_Y};
    uint32_t v[1] = {g1::UNKNOWN};
    if (!arm->Props(ids, v) || v[0] == g1::UNKNOWN) continue;
    int64_t t = g1::NowUs();
    double rate = int32_t(v[0]) / 1000.0; // degrees a second, + turning left (right-hand rule about up)
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
      yaw = std::clamp(yaw, -kEdgeDeg, kEdgeDeg);
      // Left is +: turning left slides toward the leftmost edge.
      g_pan = std::clamp(int(std::lround(500 - yaw * 500 / kEdgeDeg)), 0, 1000);
    }
    last = t;
  }
  if (arm) arm->SysWrite(g1::PROP_GYRO_RATE, 0);
  g_running = false;
  g_pan = -1;
  LOG("head: off");
}

} // namespace ssh
