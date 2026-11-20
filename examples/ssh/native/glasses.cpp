#include "glasses.hpp"

namespace g1 = scouter_kit::g1;

namespace ssh {

bool GlassesStart(const GlassesConfig &config, g1::Transport *transport, std::string *error) {
  g1::GrayConfig gray{config.dx, config.dy};
  g1::Connection connection;
  connection.left = config.left;
  connection.right = config.right;
  connection.transport = transport;
  // Both arms within a minute: an arm that connected is not let go (and connected again from scratch) because
  // the other one took longer than 30 s (out of the case later, a slow reconnection).
  connection.timeout_ms = 60000;
  if (g1::Init(&gray, error, &connection) != g1::ERROR_NONE) return false;
#ifdef SCOUTER_G1_EMULATOR
  for (int arm = 0; arm < 2; arm++) { // worn, the head down, as the sensors would say
    g1::emulator::SetProperty(arm, g1::PROP_WORN, 1);
    g1::emulator::SetProperty(arm, g1::PROP_PITCH, 0);
  }
#endif
  g1::left.SetBrightness(config.brightness);
  g1::right.SetBrightness(config.brightness);
  return true;
}

void GlassesStop() { g1::Deinit(); }

} // namespace ssh
