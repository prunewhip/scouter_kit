#pragma once

// Head-turn view: a server's picture made of several screens side by side (a panorama, glasses-panorama: three
// terminals or three synthwave scenes in 1920 x 480) shows the part the head turns to, following it smoothly
// (the picture slides as the head turns, a frame at a time). The gyroscope (VM.md §2.5 GYR_Y, the turn about the
// head's up axis) runs only while such a picture comes (GYRO_RATE 26 Hz on one arm); the turn is summed from its
// rate, its bias learnt while the head is still, and held within kEdgeDeg either way: that far left shows the
// leftmost edge, that far right the rightmost, so turning back always comes home.
namespace ssh {

class HeadView {
public:
  static HeadView &Get();
  // Each panorama frame: on (starting the gyroscope) with this many screens side by side. It turns itself off
  // (gyroscope too) once frames stop coming for a few seconds.
  void Touch(int screens);
  // Where the view sits, 0 (the leftmost edge) ... 1000 (the rightmost); -1 while off.
  int Pan();
  int Screens();
  // Straight ahead is the middle again.
  void Center();

private:
  void Run();
};

} // namespace ssh
