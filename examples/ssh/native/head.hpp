#pragma once

// Head-turn view: a server's picture made of several screens side by side (a panorama, glasses-panorama:
// three terminals in 1920 x 480) shows the one the head turns to. The gyroscope (VM.md §2.5 GYR_Y, the turn
// about the head's up axis) runs only while such a picture comes (GYRO_RATE 26 Hz on one arm); the turn is
// summed from its rate, its bias learnt while the head is still, and snaps: past `kSnapDeg` left or right the
// screen beside, back inside (less a little) the middle one. The turn is held within two snaps of the middle,
// so turning back always comes home.
namespace ssh {

class HeadView {
public:
  static HeadView &Get();
  // Each panorama frame: on (starting the gyroscope) with this many screens side by side. It turns itself off
  // (gyroscope too) once frames stop coming for a few seconds.
  void Touch(int screens);
  // The screen looked at, 0 (leftmost) ... screens - 1; -1 while off.
  int Slice();
  int Screens();
  // The middle screen is where the head points now.
  void Center();

private:
  void Run();
};

} // namespace ssh
