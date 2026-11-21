# The laptop's two screens: the phone and the glasses

Vox's Laptop screen source with "Laptop screen: the phone as its main screen" (Settings, on by default):

- **Laptop** (`~/.local/bin`): `vox-screen` with `VOX_DUAL=1` runs `vox-dual on WxH`, which tells the kernel
  the unused HDMI port has a monitor (`vox-glasses-monitor`, root, through a sudoers rule for exactly
  `on`/`off`), then puts that monitor (the glasses' picture size) centred above the laptop's own screen. vox-screen
  sends only that monitor to the glasses. 20 s after the last such stream ends, `vox-dual off` removes it.
  `vox-dual-vnc` runs x11vnc on the laptop's own screen over stdin/stdout. `vox-dual-input` moves the pointer
  for the phone. One-time setup: `sudo sh ~/vox-dual-setup.sh` (from the phone: `laptop-setup` in Termux).
- **Phone, Ubuntu proot** (`/usr/local/bin`): Vox runs `laptop-dual on` on the local server when the laptop's
  screen is chosen, and brings Termux:X11 to the front. Termux:X11 runs sideways, at the laptop screen's size,
  in touchpad mode. TigerVNC's viewer shows the laptop's screen full screen. `socat` runs `laptop-dual link`
  (`ssh ... vox-dual-vnc`) for each connection, so no port is open anywhere. `laptop-edge`: past the top of the
  phone's screen, the pointer goes up onto the glasses, and comes back past their bottom edge. It works like x2x:
  it grabs the phone's pointer and re-centres it.
- **Phone, plain Termux** (`$PREFIX/bin`): `vox-native` (started by `~/.bashrc` and `computer`) reads
  `$PREFIX/tmp/vox-native.fifo` and starts Termux:X11's X server or changes its settings. The proot can't run
  Android's `app_process`, so these have to happen there.
