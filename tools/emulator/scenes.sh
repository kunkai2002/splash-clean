#!/bin/bash
# usage: scenes.sh [scene...]; prints TESTBED + app log lines per scene
. /d/android-dev/env.sh
TB=io.github.kunkai2002.splashclean.testbed
SCENES=${@:-PangleScene TextScene CanvasScene UpdateScene IntroScene}
for s in $SCENES; do
  adb shell input keyevent KEYCODE_HOME >/dev/null; sleep 1.5
  adb shell am force-stop $TB
  adb logcat -c
  adb shell am start -n $TB/.$s >/dev/null
  if [ "$s" = "IntroScene" ]; then sleep 9; else sleep 7; fi
  echo "== $s"; adb logcat -d -s TESTBED:I SplashFallback:V RuleEngine:V | grep -v "^---" | sed 's/^.*: //' 
done
