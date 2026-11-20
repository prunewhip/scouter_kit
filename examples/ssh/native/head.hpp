#pragma once

#include <cstdint>

// Head-turn view: a server's picture made of screens (640 x 480 each, the glasses' size) in columns and rows -- a
// panorama (glasses-panorama: three terminals) or a sphere (glasses-sphere: a 360-degree ring with rows above and
// below) -- shows the window the head points at, following it (a frame at a time). Turning: the gyroscope (VM.md
// §2.5 GYR_Y, about the head's up axis; GYRO_RATE 26 Hz on one arm while such a picture comes), summed from its
// rate with its bias learnt while the head is still. Up and down: PITCH (the accelerometer against gravity: no
// drift). Both kPxPerDeg pixels a degree. A ring (wrap) goes all the way round: its picture repeats its first column
// after the last, so the window never splits; a panorama stops at its edges.
namespace ssh {

class HeadView {
public:
  static constexpr int kScreenW = 640, kScreenH = 480;
  static HeadView &Get();
  // Each such frame: on (the gyroscope too) for a picture of cols x rows screens; wrap: a 360-degree ring (its last
  // column repeats the first). Off by itself (the gyroscope too) once frames stop for a few seconds.
  void Touch(int cols, int rows, bool wrap);
  // The window's top-left in the picture's pixels; false while off.
  bool Window(int *x, int *y);
  // Straight ahead (as the head points now) is the start again: the first screen (a ring) or the middle.
  void Center();
  // The window at x (the picture's pixels) is straight ahead now (a tab picked turns to face you).
  void Face(int x);
  // The head's pitch (degrees, + up) as last read; -1000 while off or unknown.
  int Pitch();
  // How far the window moves (x, pixels) from the last reading to `ahead_us` after now, at the turn rate read
  // then: the arms' own pan is anchored where the head will point when the anchor arrives (0 while still or off).
  int Lead(int64_t ahead_us);

private:
  void Run();
};

} // namespace ssh
