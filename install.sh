#!/usr/bin/env bash
# Installs build/serene-tv.apk and makes sure it is still Home afterwards: Android can reset the
# default Home when a Home app is installed or updated.
# Usage: TV=<tv ip>:5555 ./install.sh
set -euo pipefail
: "${TV:?Set TV to the address of the TV, for example TV=192.168.1.50:5555}"
A="adb -s $TV"
$A install -r "$(dirname -- "${BASH_SOURCE[0]}")/build/serene-tv.apk"
$A shell cmd package set-home-activity io.github.bavarianprayoga.serene/.HomeActivity
$A shell input keyevent HOME
sleep 2
$A shell dumpsys activity activities | grep -m1 mFocusedApp
