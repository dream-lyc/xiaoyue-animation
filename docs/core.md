# 领域层

`core` 是 Java 17 / Kotlin 2.1.20 的纯 JVM 模块。Android 与 HTTP 端都应调用同一个领域对象，并通过 Room 事务保存 snapshot；领域对象本身不负责磁盘持久化，不内置演示片库。

## 单位与集成

策略额度/累计采用整数秒；播放器进度、单调时钟及事件采用毫秒。`PlaybackClock.update` 的 `playing` 必须来自实际播放器状态（仅正在输出且非缓冲为 true），每 5 秒和暂停/切集/退出时调用。播放器倍速、seek 位置不参与计时。分类改变前调用一次 update 刷新旧区间。`WatchInterval` 的稳定 id 用于重复检查点幂等写入。

`ViewingPolicyEngine` 的 `PlayingEpisode` 表示尚未结束的当前会话，临时暂停进入设置时保留，退出播放器时不再传入。`canStart` 用于所有手动、续播、换源和自动播放入口，`advance` 更新收尾进度下界，播放器到逻辑集边界时必须 `finish` 并停止。`ParentAuthorization` 的时钟为单调毫秒；答案只存在服务端私有状态，challenge 不包含答案。Web 管理登录与这些一次性授权相互独立。

`TagCatalog` 对一批逻辑集先验证再一次替换状态；同 K OR、跨 K AND。整部/整季通过 `LibraryCatalog` 的选择展开为本次已有集集合，再传给标签操作；新集不会继承过去操作。

扫描只在完整成功列出选定 root 后将未见文件标记不可用。错误或取消不得以空集合调用 complete=true。稳定源 ID 优先于规范化路径；moviehash 仅作资料查询证据，不作文件合并标识。

## 验证

主构建：`./gradlew :core:test`。缺少 Android SDK 时，可独立执行 `mvn -f core/pom.xml test`（本机受限 cache 可用 `-Dmaven.repo.local=/private/tmp/child-tv-m2`）。测试依赖需要 Maven Central。所有真实外部源/API 命中率、Android 生命周期及磁盘事务需要集成验证，不能由 JVM 单测代替。

## 实现和测试记录（2026-10-27）

- Maven 独立构建使用 JDK17：`JAVA_HOME=/path/to/jdk-17 mvn -Dmaven.repo.local=/private/tmp/child-tv-m2 -f core/pom.xml test`。
- 第一轮先写领域行为测试，再执行得到缺失 API 的编译失败；实现后 12 测试通过。随后默认额度恢复测试实际失败 `expected AVAILABLE but was FINISHING_CURRENT_EPISODE`，修复后通过。
- 加入官方 breakdance 首尾各 64 KiB 固定向量（文件原大小 12,909,756，期望 `8e245d9679d31e12`）及 >4GiB 64位溢出构造向量，累计 15 测试。官方另一份 4GiB dummy.rar 未解压验证，不能称完成全部官方向量验证。
- HTTP 源与资料适配器实现后 22 测试通过。测试在 `127.0.0.1` 随机端口启动真实 HTTP 服务，验证 OpenList 请求、分页、目录、属性、按需地址、Range 头/长度/Content-Range，WebDAV PROPFIND/命名空间和路径解码。测试源没有真实 NAS/云盘或用户凭据。
- 自动匹配流水线实现后累计 24 测试通过。授权/额外一集回退回归用例先实际失败（两个 assertFalse），修复快照的额外一集进度下界、创建挑战和取消时清理旧授权后累计 26 测试通过。

### 外部接口

`OpenListAdapter` 同时服务 AList/OpenList，接受服务令牌及可选目录密码；令牌必须由 Android Keystore 解密后传入，不能返回 Web。`WebDavAdapter` 用 Basic 身份及 Depth 0/1 PROPFIND；播放时必须把 `PlaybackLocation.headers` 传给播放器。HTTP 客户端为 OkHttp 4.12.0，JSON为 Gson 2.11.0；Range 必须收到严格 206，否则立即关闭，不把完整视频下载入内存。

`TmdbProvider` 接受 TMDB API Read Access Token（Bearer，不是 v3 query key），支持名称搜索、外部 ID、单集文字及图片。`OpenSubtitlesProvider` 只公开搜索；仅响应 attributes.moviehash_match=true 才是指纹证据，剧集使用 parent_tmdb_id 作为动画身份。二者共享有界缓存和最多3次重试记录，429/5xx遵守 Retry-After（秒数/HTTP日期）或指数退避；由扫描任务调度器在 retryAt 后重试，不阻塞线程睡眠。

`MetadataPipeline` 组合 NFO、文件名、moviehash及在线资料；匹配失败保留文件和诊断，强证据冲突进入待确认，人工锁定字段不被合并覆盖。NFO/本地图片实际读取与视频截帧由 Android扫描器负责。

接口依据：[AList fs](https://alistgo.com/zh/guide/api/fs)、[OpenList API](https://doc.oplist.org/api/apidocs)、[WebDAV RFC4918](https://www.rfc-editor.org/rfc/rfc4918)、[OpenSubtitles API](https://ai.opensubtitles.com/api-docs)、[moviehash官方](https://opensubtitles.github.io/oshash/)、[TMDB search](https://developer.themoviedb.org/reference/search-tv)、[TMDB find](https://developer.themoviedb.org/reference/find-by-id)、[TMDB episode images](https://developer.themoviedb.org/reference/tv-episode-images)。

### SMB2/3

`SmbAdapter("smb://host[:port]/share[/base]", username, password, domain)` 使用 SMBJ 0.13.0；范围读取从指定文件偏移读最多1MiB，不下载整片。`openRead(path):RandomAccessReader` 保持客户端、连接、认证会话、share及file handle，供 Media3 DataSource桥接连续随机读；调用 close 依次释放所有资源。`size` 是文件大小，`read(offset,buffer,bufferOffset,length)` 返回已读字节数或 EOF。SMB播放URI不包含账号密码，不能直接交给标准HTTP播放器。

新增 SMB 路径/随机读/关闭/不泄露凭据测试后，完整 **27 测试通过**（2026-10-27，Maven + JDK17，本机HTTP随机端口）。SMB真实协议后端已基于官方SMBJ API编译；SMB测试注入连接以验证适配器边界，尚未与真实SMB服务器协商认证，Android/D8和真机NAS兼容性由应用构建及发布矩阵验证。

OpenList/AList `/api/fs/get` 的 `header` 是页面元信息（见官方 `getHeader(meta, path)` 实现），不能当作 HTTP 请求头转发。对必须携带第三方 Cookie/header 的存储，应在源端开启代理，由其 `raw_url` 返回 `/p/...`；应用不索取管理员专用原始 Link API，也不把管理令牌附到第三方 raw_url。源端的特殊防盗链/代理配置仍需要真实资源验证。

策略和统计可变服务的公开读取/修改已加对象级同步；**事件+进度+策略仍必须由 Android 唯一数据库事务入口一次提交**，对象锁不替代跨表事务。默认额度修改不能清空累计；额外一集和收尾均持久保存位置下界及边界，创建新挑战或取消当前流程会使旧未消费 grant 失效。

`LibraryCatalog.previewMapping` 对指定季及文件集合生成集号预览，`applyMapping` 在完整校验后原子提交且重复确认幂等，支持 `startMs/endMs` 单文件多集映射；`renumber` 验证最终季内集号唯一后一次提交，允许交换集号，保留逻辑集 ID。新增映射及改集号测试先得到缺失 API red，完整 **29 测试通过**。

### 任务覆盖说明

独立核心完整覆盖：4.2 指纹算法/搜索及官方固定向量、4.3 TMDB资料接口、5.3 标签筛选语义、9.2 统计口径验证。3.1的HTTP适配器和认证/地址解析契约已完成；地址失效后的播放器重试需Android联动。3.2的WebDAV/SMB2/3适配器和随机读取已完成；SMB Media3桥接需Android模块编译和设备验证。4.4的自动资料流水线/缓存/退避/分类已完成，扫描任务持久化调度由应用负责。2.x、4.1、4.5、5.1/5.2、6.x、7.x、9.1提供领域模型/逻辑和单测，是否整项完成取决于Android事务保存/API与UI集成，不能仅据此勾选完整任务。

## 最终领域与仓库验收证据

领域独立命令仍为前述 Maven/JDK17 命令。Android 仓库测试采用真正 Robolectric Android 28 + SQLite/Room（包含生产 v1→v2 自动迁移）、生产 AdminApi/PolicyRepository/SourceRepository/BackupRepository，以及随机回环端口的真实 HTTP 资源/资料服务器；没有把 mock 的调用次数当作集成成功。统一命令：

```sh
JAVA_HOME=/path/to/jdk-17 \
GRADLE_USER_HOME="$PWD/.tooling/gradle-home" \
.tooling/gradle-8.13/bin/gradle :app:testDebugUnitTest --console=plain
```

2026-10-27 的核心 **36 项通过**；Android 最后一轮完整 **42 项通过**（`.tooling/retry-local-art-green.log`）。新增持续429耗尽恢复回归之后的最终数量以统一构建报告为准。首次 Robolectric 下载 Android 28/35 运行时耗时约8分钟，后续完整测试约12秒；本机受限环境启动回环端口及依赖下载需要正常网络/进程权限。

实际 red→green 修复记录：备份450ms小数恢复成100ms；候选确认丢身份和季集号；完整成功扫描不标缺失；错误 JSON 类型但有效 SHA 仍被接受；源路径唯一冲突被 Room Upsert 静默忽略；已删除扫描任务继续写回资源；取消后立刻重试旧 HTTP 误用新任务状态；自动匹配只有 matched 状态而未写动画层级；429不调度重试；本地poster未读取。测试报告保留在 `app/build/test-results/testDebugUnitTest/`，核心报告在 `core/target/surefire-reports/`。

### 任务映射（给主任务清单维护者）

| 任务 | 实现与可执行证据 | 仍需外部验收的边界 |
|---|---|---|
| 3.1 | OpenList/AList目录分页、认证错误脱敏、文件属性、按需地址、动态旧地址重新解析；`HttpAdaptersTest`、`SourceRepositoryIntegrationTest.playback addresses...` | 真实服务版本/网盘防盗链配置尚无用户资源可验证 |
| 3.2 | WebDAV真实PROPFIND/Range；SMBJ 0.13认证会话及持久随机reader；Android SmbDataSource桥接；`HttpAdaptersTest`、`SmbAdapterTest` | SMB协议测试注入连接验证读偏移/资源关闭；没有真实SMB服务器认证样本，真机NAS兼容仍待验 |
| 3.3 | 每任务持久根/待遍历目录/当前页/页内索引/已完成目录/已见asset及generation；page2失败后retry不再访问page1；recover保留断点；取消/恢复/generation屏障测试 | 安卓后台强杀、网络挂起与服务生命周期由设备验收覆盖 |
| 4.1 | 标准/中文编号解析、NFO安全XML、剧集NFO与tvshow.nfo身份分离；同名jpg/png、poster/folder与NFO相对图片通过stat+≤1MiB分块读取，cacheBytes实际解码；本地PNG扫描测试 | 本地/在线均无图时接有预算的FrameArtworkExtractor；实际解码设备样本由Android设备测试验证 |
| 4.4 | 唯一强证据才自动确认并写动画名/季集/外部身份；冲突待确认/无匹配保留；可读候选；缓存及429秒数/HTTP日期与指数退避；deadline传到持久waiting任务，ScheduledExecutor到时重派，无sleep占用扫描线程；真实429一次后自动成功 | 不承诺真实动画识别率；持续故障的有界重试/人工继续由对应回归及最终报告确认 |
| 4.5 | 字段锁在重扫/并发编辑后保留；候选确认身份+编号持久化；`LibraryCatalog.previewMapping/applyMapping/renumber`原子/幂等/回滚；AdminApi多资源区间真实持久化测试 | TV/桌面交互完整路径仍需UI/设备验收 |
| 4.6 | 下表的确定性合成命名/NFO/指纹样本评估已执行，暴露合辑与弱名称风险，未把解析成功算在线命中 | 缺少用户中文/英语启蒙实际视频、重压/配音/合辑样本及有效服务key；真实命中率未知 |
| 6.3 | 单调时钟、暂停/非playing不计时、倍速/seek不参与时长；150ms×10的SQLite累计及检查点幂等、重复观看和normal/grace拆分测试 | 周期为5秒；实际强杀/断电丢失上界不能仅由JVM测试证明，需播放器与进程设备测试 |
| 7.6 | 固定家庭时区的午夜拆分、跨两个日期Room累计、snapshot后日期回拨沿用原日用量/首启标志/已消费额外集；`PolicyRecoveryTest`与`RepositoryIntegrationTest.cross midnight...` | 系统管理员改钟无法作为强安全边界；clockAnomaly为设备时间异常信号 |
| 9.1 | 生产AdminApi按日、片库/动画/当前标签、范围、趋势、剧集数/观看天数/明细；标签联合去重、原片库归属、空日0/空列表、每日可读动画名验证 | 双端像素/遥控器呈现由UI和设备验收负责 |

### 4.6 确定性合成资料语料（不是在线识别率）

`NamingCorpusTest` 会实际输出并断言前四个文件名解析，其他行由相应领域/HTTP用例覆盖：

| 合成或官方固定样本 | 实际预期结果与限制 |
|---|---|
| `小猪佩奇 第十二集.mp4` | episode=12，season未知；不能仅凭这一结果确认动画 |
| `Alphablocks.S02E03.1080p.mkv` | season=2、episode=3；英语启蒙命名覆盖，未调用真实资料服务 |
| `Peppa.Pig.S01E01.x265.国语.mkv` | season=1、episode=1；重编码/配音后缀不会改变编号，不能据此合并文件 |
| `wrong-weekend-video.mp4` | 无编号，保留未匹配；弱名称候选不能自动确认 |
| `/Peppa Pig/Season 1/S01E02.mp4` | 名称查询取 `Peppa Pig`，跳过季目录；查询结果仍是NAME证据 |
| `/小猪佩奇/第1季/第十二集.mp4` | 名称查询取 `小猪佩奇`，保留中文编号 |
| 根目录 `Peppa.Pig.S01E02.1080p.WEB-DL.mkv` | 查询名剥离明确季集/发布标记为 `Peppa Pig`；不做模糊猜片 |
| `Peppa S01E01-E02.mp4` / 合集 | 检测为多集，唯一hash也不自动发布为单集，要求手工时间区间映射 |
| tvshow NFO TMDB=44 + episode NFO TMDB=999、S01E02 | 动画身份采用tvshow的44，单集保留S01E02和本地标题；不把999误当动画ID |
| 官方 `breakdance.avi` 首尾固定片段 | 原大小12,909,756；hash=`8e245d9679d31e12`，官方固定向量通过 |
| 构造 >4GiB 大小及0xff首尾块 | 验证64位自然溢出；不是下载并解压官方4GiB视频的替代证明 |

四个基础合成文件名中3个提取了明确编号、1个正确保留无编号；这只表示解析口径，**不计算或宣称在线匹配成功率**。中文真实标题别名、不同播出排序、合辑边界、错误命名恢复、实际重压文件的hash命中仍需要用户材料/API key验收。

最终补充回归：持续429三次后任务保持 `failed/retry_exhausted` 并保存当前文件断点，不把资料失败冒充扫描成功；家长显式重试会重新获得有界尝试预算，最终成功仍只计发现1个文件。该用例实际先失败（错误显示completed），修复后与全部仓库用例通过。取消/重试/recover/定时重派均更新generation，所有资料回写检查匹配；在线取图与截帧另外校验catalog epoch。

交统一构建时最后日志为 `.tooling/final-repositories-green.log`：**44项中43项通过，唯一未通过是TV代理新增的同名动画稳定seriesId分组用例**（`TvPresentationTest`，已转交UI代理修复并由Android统一复验）。`SourceRepositoryIntegrationTest`的13项、其他Room/备份/封面/策略/统计用例全部通过；核心36项0失败。此处不把后续代理尚未执行的最终构建写成已经通过，最终APK/设备及统一构建结果以发布记录为准。

真实视频/字幕资料没有写入仓库；官方指纹测试只保留首尾固定片段。API测试只请求搜索/资料/图片元数据接口，没有字幕下载路径。当前可执行合成语料评估足以复核任务4.6的边界与失败处理，不能代替实际中文/英语启蒙视频、重压/合辑资源的线上命中率评估。

交接后的最后专项 `.tooling/series-identity-red.log` 新增两项必要的typed回归：同名但TMDB身份不同应有不同seriesId、候选seriesTitle应在数据库重读后保留；这两项均观察到实现前失败，与已知TV同名分组共3红。Android/UI代理负责最终修复并统一build/test/lint，集成测试代理已停止构建以避免并行。此前43项非这些新red的完整仓库验证结果不受影响。
