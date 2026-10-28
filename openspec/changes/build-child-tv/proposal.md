## Why

家庭动画资源分散在 OpenList、AList 和 NAS 中，儿童需要简单、适合遥控器的选片方式，家长需要统一维护片库并管理实际观看时长。建设一款本地优先的 Android TV 应用，将资源整理、播放、标签与观看管理整合起来，无需单独部署本应用的管理服务器。

## What Changes

- 提供 Android TV APK，包含片库、标签播放列表、续播、历史、观看日历和统计页面。
- 在电视应用内启用局域网管理服务，家长从电脑浏览器访问电视 IP 登录；管理端不适配手机。
- 连接外部 OpenList、AList、NAS SMB/WebDAV，选择目录后自动扫描、识别和补全动画/季/集资料，不下载字幕。
- 组合 NFO、目录/文件名、OpenSubtitles moviehash 查询和 TMDB 资料获取；支持待确认、未匹配及手动/批量维护，保护人工修正。
- 支持 K/V 多值标签、整部/整季/多集批量打标；预置“庆爸路线”及“常规一、常规二、常规三”。
- 以实际播放时间记录历史，按片库、动画及当前标签统计；提供 TV 与管理端日历和每日明细。
- 提供每日默认额度、每天首次进入的可跳过设置、家长答题验证后的今日总额度调整；到时允许看完当前集，然后停止。
- 数据本地持久化，提供备份恢复；视频保留在资源源端。
- 以现有 Pencil 原型 T01—T10、A01—A06 为视觉和流程基线，原型演示内容不作为真实数据。

## Capabilities

### New Capabilities

- `source-catalog`: 外部资源连接、目录选择、增量扫描及稳定文件关联。
- `metadata-maintenance`: 自动识别、资料补全、人工校正及批量季集映射。
- `library-tags`: 动画/季/集片库与 K/V 多值标签管理。
- `tv-playback`: 遥控器导航、片库/标签选片、播放、续播与错误处理。
- `viewing-policy`: 家长验证、默认/今日额度、首次进入和本集收尾策略。
- `viewing-analytics`: 实际观看记录、日历及去重/标签统计。
- `local-administration`: 电视内置管理服务、桌面管理、凭据保护与备份恢复。

### Modified Capabilities

无。当前项目没有已有功能规格。

## Impact

- 新增 Android 客户端、内置管理 API、随 APK 分发的桌面 Web 资源和本地数据库。
- 接入外部 OpenList/AList、SMB/WebDAV、OpenSubtitles REST API 和 TMDB；第三方故障不得阻止手动入库。
- 不内置 OpenList、不直接适配百度/夸克等单独网盘、不提供云同步、多设备统一额度或转码服务器。
- 原型参考：`design/prototype-v1/export.pdf`。
