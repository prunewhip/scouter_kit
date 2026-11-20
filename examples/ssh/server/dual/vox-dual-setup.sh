#!/bin/sh
# One-time setup for vox-dual (run as root: sudo sh ~/vox-dual-setup.sh). Installs vox-glasses-monitor and lets
# this user run exactly "vox-glasses-monitor on" / "off" without a password; nothing else.
set -eu
U=pruitt-colon
install -o root -g root -m 755 "/home/$U/.local/share/vox-dual/vox-glasses-monitor" /usr/local/sbin/vox-glasses-monitor
T=$(mktemp)
printf '%s ALL=(root) NOPASSWD: /usr/local/sbin/vox-glasses-monitor on, /usr/local/sbin/vox-glasses-monitor off\n' "$U" > "$T"
visudo -cf "$T"
install -o root -g root -m 440 "$T" /etc/sudoers.d/vox-glasses-monitor
rm -f "$T"
echo "vox-dual: set up"
