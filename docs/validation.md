# 第一版验收记录

本记录区分自动化契约测试、Android 模拟器运行和真实家庭设备验证。验收日期：2026-10-27。

## 环境与产物

- 交付 APK：[child-tv-0.1.0-debug.apk](../releases/0.1.0/child-tv-0.1.0-debug.apk)，18,923,006 字节，调试签名。
- SHA-256：`99de292b1e1ba1e1433b3dad494454b2c25abd5a3679a16b2817b16a78970fa9`。
- 最终 APK 内 HTML/JS/CSS 已与 `web-admin/dist` 逐字节比较一致；未包含 `playback-fixture.mp4`。
- 构建：macOS ARM64、JDK 17、Gradle 8.13、Android SDK 35；Gradle 分发包已核对官方 SHA-256。
- APK 最低配置：Android 8.0 / API 26；包含 `arm64-v8a`、`armeabi-v7a`。
- 运行设备：专用 Android TV API 34 ARM64 模拟器，1920 × 1080。
- 系统 fingerprint：`google/sdk_google_atv64_arm64/emu64a:14/UTT1.240131.001.F1/11825476:user/dev-keys`。
- TV 启动、Room 和管理服务的设备测试使用 Android 实际运行时；源服务和媒体文件使用测试夹具，不代表用户的真实 OpenList/NAS 已验证。
- 生产 APK 不包含测试视频、演示片库、用户凭据或资料服务 key。短视频夹具仅在 `androidTest` 测试 APK 中。

## 已执行的检查

- OpenSpec 严格校验通过。
- 电脑管理端：25 项 Vitest 测试通过，另有 4 项 Node 契约测试通过；TypeScript 检查和 Vite 生产构建通过。
- 核心 JVM：36 项测试通过，0 失败、0 跳过。
- Android JVM/Room 集成：46 项测试通过，0 失败、0 跳过。包括指纹、源接口、事务、人工锁、同名动画身份隔离、标签、毫秒计时、数据库迁移、限流重试、取消后旧任务隔离、封面及备份。
- Android TV API 34 设备测试：5 项全部通过，耗时 19.019 秒。包括真实 Keystore 并发加解密；HTTP 登录/CSRF/打包网页/扫描/维护/统计；TV 入口与方向键；真实范围读取截帧及预算回退；实际 Media3 播放与额度端到端流程。
- Media3 端到端流程使用同一独立数据库串联目录扫描、人工校正、批量标签、实际短视频播放、暂停不计时、额度用尽后本集收尾、阻止下一集、家长授权修改及统计查询。
- 覆盖安装早期 schema 2 APK 后，schema 3 正常打开原库。实测一次冷启动 430 ms；首页、日历、统计和家长入口进行了截图检查及方向键导航。
- 首轮设备测试发现 PUT JSON 请求读取缺陷，修复后设备测试已通过。视觉检查发现统计空态裁切，局部修复后重新打包并复验。
- Android Lint：0 错误、24 警告。15 条为已锁依赖存在新版，5 条为 KTX 风格建议，其余为 TV banner 尺寸、固定横屏、非 ChromeOS ABI 和 stopService 的 SAM 检查提示。没有用 baseline 隐藏错误。
- 完整设备测试后的收尾修改仅涉及管理服务启动线程释放和统计空态布局，均重新构建及 Lint；不重复宣称整套设备用例在每个布局包上重新运行。

最终交付包已再次覆盖安装、冷启动（本次 430 ms），通过方向键进入统计页，并确认空态标题和说明完整显示。[设备测试原始结果](../releases/0.1.0/device-tests.txt)、[首页](../releases/0.1.0/screenshots/tv-home.png)、[日历](../releases/0.1.0/screenshots/tv-calendar.png)、[修复后的统计页](../releases/0.1.0/screenshots/tv-statistics.png)、[家长设置](../releases/0.1.0/screenshots/tv-parent.png)已保存。

OpenSpec 本轮完成 **43/48** 项。保留 1.2（API 26/双 ABI 运行兼容）、4.6（真实片库识别率）、6.3（强杀丢失上界专项）、11.5（真实机型验收）、11.6（实际外部源/API key 与分发条款复核）未完成。用户已确认暂时没有真机，先交付 APK 和测试结果。

## 尚需真实环境验证

| 范围 | 当前边界 |
| --- | --- |
| API 26 与 ARMv7 电视/盒子 | 已配置构建目标，尚未取得真实设备运行证据 |
| API 34 ARM64 | 专用 TV 模拟器；不等同于某品牌电视 |
| SMB2/3 | 已实现 SMBJ 随机读取与播放器桥接；真实 NAS 协商、权限和吞吐仍待验收 |
| OpenList/AList/WebDAV | 本地 HTTP 契约夹具覆盖接口；真实版本、驱动代理/防盗链配置需逐源验证 |
| OpenSubtitles / TMDB | 官方 hash 向量及接口契约已测试；没有用户有效 key 的在线端到端结果 |
| 编码兼容性 | 由设备 MediaCodec 能力决定，不能保证任意 H.265、AV1、HDR 或音频编码 |
| 系统生命周期 | 真实电视待机、强杀、后台限制及断电恢复仍需机型验收 |
| 检查点丢失上界 | 每 5 秒及暂停/退出提交已实现；强杀/断电情况下最多丢失 5 秒的实际运行保证尚未完成专项设备验证 |
| 自动识别效果 | 名称/NFO/hash 样本是可重复的功能测试，不是用户实际片库的识别率 |
| 分发 | 当前为调试签名 APK；正式分发需持久保存发布签名并按用途复核第三方条款及署名 |

## 复验命令

先构建电脑资源，然后执行项目构建：

```sh
npm --prefix web-admin ci
npm --prefix web-admin test
npm --prefix web-admin run build
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w cn.childtv.test/androidx.test.runner.AndroidJUnitRunner
```

Gradle XML 报告位于 `core/build/test-results/test/`、`app/build/test-results/testDebugUnitTest/`。设备测试数据使用独立数据库和凭据命名空间；不要将夹具写入用户正式片库来伪造空库验收。
