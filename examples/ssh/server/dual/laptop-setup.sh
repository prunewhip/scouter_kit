#!/bin/bash
# The laptop's one-time setup for the two screens (vox-dual): asks for the laptop's password here (not shown, not
# kept) and runs ~/vox-dual-setup.sh there with sudo.
. /root/.laptop-dual.conf
read -rsp "The laptop's password (for the one-time setup): " pw; echo
printf '%s\n' "$pw" | ssh -T -o BatchMode=yes -i "$KEY" "$LOGIN@$HOST" 'sudo -S -p "" sh ~/vox-dual-setup.sh'
rc=$?
unset pw
[ $rc = 0 ] && echo "Done. You can close this." || echo "It didn't work (exit $rc): run laptop-setup again."
