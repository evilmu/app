#!/usr/bin/env bash
set -euo pipefail

APK="apk/ThreadsEnhancer-3.7.2-Android11.apk"
echo "Creating a Download provider entry for the exact released APK"
INSERT_RESULT=$(adb shell content insert --uri content://media/external/downloads --bind _display_name:s:ThreadsEnhancer-3.7.2.apk --bind mime_type:s:application/vnd.android.package-archive --bind relative_path:s:Download/ | tr -d '\r')
echo "MediaStore response: $INSERT_RESULT"
URI=$(printf '%s\n' "$INSERT_RESULT" | grep -oE 'content://media/[^ ]+' | head -1)
test -n "$URI"
echo "Writing APK to MediaStore URI: $URI"
adb shell content write --uri "$URI" < "$APK"

echo "Launching package installer with the read-granted content URI"
adb shell am start --grant-read-uri-permission -a android.intent.action.VIEW -d "$URI" -t application/vnd.android.package-archive | tee android11-graphical-intent.txt
sleep 5
adb shell uiautomator dump /sdcard/android11-installer.xml || true
adb pull /sdcard/android11-installer.xml android11-installer.xml || true
adb shell screencap -p /sdcard/android11-installer.png || true
adb pull /sdcard/android11-installer.png android11-installer.png || true
adb shell dumpsys activity activities > android11-graphical-activities.txt

python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
path=Path('android11-installer.xml')
assert path.exists(), 'UI hierarchy unavailable'
root=ET.parse(path).getroot()
nodes=[(n.attrib.get('package',''),n.attrib.get('text',''),n.attrib.get('content-desc','')) for n in root.iter('node')]
for package,text,description in nodes:
    if text or description:
        print(f'UI node: package={package} text={text!r} desc={description!r}')
has_installer = any('packageinstaller' in package.lower() for package,_,_ in nodes)
print('Graphical package-installer visible:',has_installer)
if not has_installer:
    raise SystemExit('Could not establish graphical installer on emulator with MediaStore content URI')
PY
