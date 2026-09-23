# usage: . taptext.sh ; tapText "<exact text>"
tapText() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb shell cat /sdcard/ui.xml > "$SP/ui.xml"
  L=$(PYTHONIOENCODING=utf-8 python "$SP/uinodes.py" "$SP/ui.xml" | grep -F "'$1'" | head -1)
  [ -z "$L" ] && { echo "NOT FOUND: $1"; return 1; }
  B=$(echo "$L" | grep -o "\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]" | head -1)
  x1=$(echo $B | sed 's/\[\([0-9]*\),.*/\1/'); y1=$(echo $B | sed 's/\[[0-9]*,\([0-9]*\)\].*/\1/')
  x2=$(echo $B | sed 's/.*\]\[\([0-9]*\),.*/\1/'); y2=$(echo $B | sed 's/.*,\([0-9]*\)\]$/\1/')
  adb shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 )); echo "tapped $1"
}
