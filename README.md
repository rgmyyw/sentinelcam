# 哨兵相机 SentinelCam

熄屏常开的活体探测摄像头：默认前置摄像头持续监控，检测到**陌生人**时通过蓝牙耳机播报提示音，未连接蓝牙则推送钉钉群机器人。认识的人（已登记名单）出现时只记录、不打扰。

- 仅使用 Android framework API，**零第三方依赖**，无需 gradle，离线一行脚本构建
- 已在 Redmi 24122RKC7C（HyperOS 3 / Android 16 / API 36，已 root）真机验证

## 功能

| 功能 | 说明 |
|---|---|
| 熄屏采集 | `foregroundServiceType="camera"` 前台服务 + PARTIAL_WAKE_LOCK，实测熄屏（Dozing）下 30fps 不断流 |
| 活体告警 | 硬件人脸检测（Camera2 STATISTICS_FACE_DETECT_MODE）+ 帧差运动信号辅助，仅人脸触发告警 |
| 认识名单豁免 | 对快照人脸区域计算感知哈希（aHash + 双向 dHash，192 位），告警前与名单比对，命中即豁免（汉明距离 ≤ 30） |
| 双通道通知 | 有蓝牙 → 媒体通道播放提示音（抢音频焦点，压过耳机里的音乐）；无蓝牙 → 钉钉机器人 webhook（支持加签） |
| 提示音 | 内置水滴声（默认）/ 中文语音播报，设置页选择并持久化，可试听 |
| 事件留档 | 每次告警存 640x480 JPEG 快照 + SQLite 记录，支持标记/删除/一键清空 |
| 前后摄切换 | 默认前摄（镜像预览），一键切换；采集链路整条重建，监控不中断 |
| 事件列表 | 快照缩略图、人脸/运动信号、陌生人·告警 / 已豁免徽标，点击可登记认识的人 |
| 预览 | TextureView + 变换矩阵：完整显示，绝不裁剪、绝不变形，边到边深色 UI |

## 构建与安装

需要本机有 Android SDK（build-tools 36、platform android-36）和 JDK 17+：

```bash
./build.sh                 # 产物 SentinelCam.apk
adb install -r SentinelCam.apk
# 首次安装建议直接授权（或用 root）：
adb install -r -g SentinelCam.apk
adb shell appops set com.zcode.sentinelcam CAMERA allow   # MIUI/HyperOS 常需要
```

## 使用

1. 打开「哨兵相机」，点「启动哨兵」（或打开 App 自动启动）
2. 设置页（右上角齿轮）配置钉钉机器人 webhook / 加签 secret、选择提示音、管理认识名单
3. 主界面点某条事件 → 「标记为认识的人」→ 输入名字，即完成人脸登记；此后该人出现不再告警
4. 按电源键熄屏，监控持续；「停止哨兵」可真正停止服务

## 技术要点

- **熄屏保活**：camera 类型前台服务必须在 App 可见时启动（Android 14+ 的 while-in-use 限制），息屏后由服务持有相机
- **预览与采集解耦**：服务持有检测流（320x240 YUV）+ 快照流（640x480 JPEG），Activity 的预览面挂上/摘除时动态重建会话，熄屏时自动退回纯采集
- **停止语义**：Activity 绑定了服务，`stopService()` 不会生效，必须先解绑再停
- **人脸比对**：零依赖的感知哈希方案，同一角度/光照下可靠；建议同一人多登记几张不同角度
- **音频**：`AudioFocusRequest(GAIN_TRANSIENT)` + `USAGE_MEDIA`，TTS 引擎缺失不受影响（语音为预置音频）

## 目录结构

```
src/com/zcode/sentinelcam/
├── MainActivity.java      # 主界面：状态/预览/事件列表/启停
├── SentinelService.java   # 核心：采集、检测、比对、告警路由
├── SettingsActivity.java  # 推送、提示音、认识名单
├── DbHelper.java          # SQLite（events / faces 表）
├── FaceUtil.java          # 感知哈希 + 汉明距离
├── DingTalk.java          # 钉钉机器人推送（加签）
└── build.sh               # aapt2 + javac + d8 + apksigner 离线构建
```

## 已知边界

- 感知哈希在逆光、大角度侧脸时可能漏匹配（此时按陌生人多告警一次，宁多勿漏）
- 多人同框时只对最显著人脸做识别
- MIUI/HyperOS 后台管控严格，长时间使用建议在系统设置中给本 App 设置省电策略为「无限制」
