# Third-party code and data

| Component | Where | License | Source |
|---|---|---|---|
| gkd-selector (selector engine, unmodified) | `selector/` | GPL-3.0 | https://github.com/gkd-kit/gkd (commit in `selector/UPSTREAM_COMMIT`) |
| GKD subscription model, rule state machine, node adapter, actions (adapted) | `app/.../rule/RawSubscription.kt`, `rule/ResolvedRule.kt`, `engine/NodeQuery.kt`, `engine/Actions.kt`, `engine/RuleEngine.kt` | GPL-3.0 | https://github.com/gkd-kit/gkd |
| PaddleOCR PP-OCRv4 mobile det/rec models, `ppocr_keys_v1.txt` (ONNX conversion by RapidOCR) | `app/src/main/assets/ocr/` | Apache-2.0 | https://github.com/PaddlePaddle/PaddleOCR · https://www.modelscope.cn/models/RapidAI/RapidOCR |
| AWAvenue Ads Rule (DNS block list snapshot) | `app/src/main/assets/dns/awavenue-adblock.txt` | GPL-3.0 | https://github.com/TG-Twilight/AWAvenue-Ads-Rule |
| ONNX Runtime | Maven `com.microsoft.onnxruntime:onnxruntime-android` | MIT | https://github.com/microsoft/onnxruntime |
| libadb-android (+ spake2-android) | Maven/JitPack `com.github.MuntashirAkon:libadb-android` | GPL-3.0-or-later OR Apache-2.0 (spake2: LGPL) | https://github.com/MuntashirAkon/libadb-android |
| Conscrypt | Maven `org.conscrypt:conscrypt-android` | Apache-2.0 | https://github.com/google/conscrypt |
| Bouncy Castle | Maven `org.bouncycastle:bcpkix-jdk15to18` | MIT-style (Bouncy Castle License) | https://www.bouncycastle.org |
| kotlin-json5 | Maven `li.songe:json5` | Apache-2.0 | https://github.com/lisonge/kotlin-json5 |
| exp4j | Maven `net.objecthunter:exp4j` | Apache-2.0 | https://github.com/fasseg/exp4j |
| Jetpack Compose, AndroidX, Kotlin, kotlinx | Maven | Apache-2.0 | https://developer.android.com/jetpack |

Community rule subscriptions (e.g. Lin-arm/GKD_subscription) are **not** included in this repository or the APK;
the app downloads them from their authors' links only when the user adds them.

Ideas (no code copied) from: Android-Touch-Helper / 开屏跳过 (tap-position capture), madeye/ad-skipper
(OCR fallback gated on ad evidence), xjunz/AutoSkip (geometric checks), DNS66 / AdAway (DNS-only VPN).
