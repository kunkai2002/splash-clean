# 模擬器實測腳本

在 Windows（Git Bash）上用 Android 模擬器驗證開屏淨。工具鏈裝在 `D:\android-dev`（見 `env.sh`）。

```
. /d/android-dev/env.sh
emulator -avd sc35 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot &
adb wait-for-device
./gradlew :app:assembleDebug :testbed:assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-x86_64-debug.apk
adb install -r testbed/build/outputs/apk/debug/testbed-debug.apk
P=io.github.kunkai2002.splashclean.debug
adb shell settings put secure enabled_accessibility_services $P/io.github.kunkai2002.splashclean.service.CleanAccessibilityService
bash tools/emulator/scenes.sh            # 全部情境；也可指定：scenes.sh PangleScene CanvasScene
```

判讀：每個情境印出 `SKIP <情境> <毫秒>`（被跳過）、`WRONG`（點錯）、`TIMEOUT`（沒跳過）、`UNTOUCHED intro`（「跳过片头」沒被誤點＝正確）。

| 情境 | 模擬 | 預期 |
|---|---|---|
| PangleScene | 穿山甲 `tt_splash_skip_btn` | SKIP |
| TextScene | 「5s \| 跳过」無特殊 id | SKIP |
| CanvasScene | 畫在畫布上、無障礙看不到的按鈕 | SKIP（截圖認字，Android 11+） |
| UpdateScene | 「发现新版本」彈窗 | SKIP（按「以后再说」，不是 WRONG） |
| IntroScene | 影片「跳过片头」 | UNTOUCHED |
| CustomScene | 沒有文字的關閉圖示 | 社群規則或「教它」後 SKIP |
| ShakeScene | 每秒印出加速度感應事件數 | 防摇一摇生效時為 0 |

- `adb install -g` 會連 `WRITE_SECURE_SETTINGS` 一起授予，測「一次授權」前先 `adb shell pm revoke $P android.permission.WRITE_SECURE_SETTINGS`。
- debug 版可用 `adb shell am broadcast -n $P/io.github.kunkai2002.splashclean.capture.CaptureTrigger` 觸發「抓取畫面」（與通知按鈕同一條流程）。
- Git Bash 下 `adb shell` 的 `/sdcard/...` 路徑要先 `export MSYS_NO_PATHCONV=1`。
- `uinodes.py <ui.xml> [文字]`：列出 uiautomator dump 裡的文字與座標；`taptext.sh` 提供 `tapText "<完整文字>"`（需先設 `SP` 為放 ui.xml 的資料夾）。

模擬器與系統映像不隨工具鏈保留（約 10 GB）。需要時重裝：

```
sdkmanager emulator system-images/android-35/google_apis/x86_64
echo no | avdmanager create avd -n sc35 -k "system-images;android-35;google_apis;x86_64" -d pixel_6
```
