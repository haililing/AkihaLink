# AkihaLink 1.2.0：sing-box eBPF 套件升级

本次修改将 App、模块、配置迁移、核心补丁和构建工具链一起升级。
本机流量继续使用 cgroup eBPF 入站；可选的 Wi-Fi 热点代理继续使用
Android 下游接口发现和 packet-rewrite 转发。

## 固定版本

| 项目 | 升级前 | 本次版本 |
| --- | --- | --- |
| sing-box 提交 | `90bb3d43634b56b38834a239f3129d3009ece9d4` | `10e9a4258e44536ef30cefc3e603e39439ebc02c` |
| 核心版本标识 | `v1.14.0-rc.1-9-g90bb3d43` | `v1.15.0-alpha.2-10e9a425` |
| AkihaLink 补丁集 | `akihalink-upstream-ebpf-v16` | `akihalink-upstream-ebpf-v17` |
| App / 模块 | `1.1` / versionCode `23` | `1.2.0` / versionCode `24` |
| 控制协议 | `16` | `16` |
| Go 工具链 | `1.26.6` | `1.26.7` |

源码固定到 [CHIZI-0618 的 eBPF 分支提交](https://github.com/CHIZI-0618/sing-box/commit/10e9a4258e44536ef30cefc3e603e39439ebc02c)，
属于 1.15 alpha 开发线。版本字符串包含提交号，用于可追溯构建；它不是上游正式发行标签。
子模块来源由尚未同步该提交的镜像切换至原始 eBPF 仓库。
NDK 保持 r29，cilium/ebpf 仍使用上游固定版本。

## 配套修改

- 配置生成与校验：移除旧的 `mode` 和 `shared.advanced` 结构。
  本机显式设置 `local.enabled=true`、`local.data_plane=cgroup`；
  热点设置 `shared.enabled=true`、`shared.data_plane=packet_rewrite`，
  `tc_priority=1` 移至入站根对象。模块探测配置和命令参数同步更新。
- 核心补丁：适配新的 LocalPolicy、接口监视器和 sharedRewrite 生命周期，
  保留 UID 排除热更新及失败回滚、netd 身份校验、就绪标记、UDP OOB 批处理。
  重新分配系统解析器标志位，避免与上游新增的端口绕过标志冲突。
- 热点管理：按 Android 报告的 `TetheredState` 选择 Wi-Fi 下游，
  保留发现失败状态，原子写入状态文件。恢复日志记录新上游临时过滤器的
  名称和句柄，并在清理前校验接口名称、ifindex 和过滤器身份。
- BPF 构建：重新生成 `tc`、`cgroup`、`cgroup_coarse`、`cgroup_storage`、
  `shared_network`、`fakeip_icmp` 六组对象。大小端对象及 Go 包装文件共 24 个，
  构建时对照锁定清单校验节和符号，并检查两次生成结果一致。
  主数据面延续移除 BTF 的构建方式，独立观测程序保留 CO-RE/BTF。
- 发布与验证：同步工具链下载 SHA-256、App/模块版本、发布门禁和配置校验器依赖。
  Linux 内核验证改用新上游测试入口；必需用例缺失或跳过会使门禁失败。

## 保存配置与回退

首次启动时，迁移器识别 AkihaLink 的 v14 平面配置或 v16 嵌套配置，
在同一次原子迁移中转换为 v17。原文件保存为
`/data/adb/akihalink/config/current.json.pre-v17.bak`。
重复启动不会覆盖备份。节点、出站凭据、订阅、路由、排除项和 App 偏好不重置。
不认识的字段和非 AkihaLink 覆盖项会中止迁移，原文件保持不变，此时由 App 重新应用配置。

App 和模块必须成套升级。若需回退，应恢复匹配的 1.1 App/模块，
再恢复升级前配置或让旧 App 重新生成；旧核心不能直接读取新配置。

## 验证交接

本次完成的是源码修改及静态核对，没有编译 App/核心、运行测试或连接设备。
旧的 1.1 性能与体积记录只作为历史基线，不代表本次版本的结果。

在 Windows 工作区按顺序执行以下构建与测试。所需 JDK 21、SDK API 36、
NDK r29 和 Go 1.26.7 路径应与构建脚本一致。当前工作区的子模块已切换到
锁定提交；在这些修改尚未提交前，不要用 `git submodule update` 将它重置到
索引中仍记录的旧提交。首次获取已提交的新版本源码时，按 README 初始化子模块。

```powershell
powershell -ExecutionPolicy Bypass -File scripts/test-module.ps1 -Core build/not-built
powershell -ExecutionPolicy Bypass -File scripts/build-core.ps1
powershell -ExecutionPolicy Bypass -File scripts/test-minimal-core.ps1
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease
powershell -ExecutionPolicy Bypass -File scripts/build-artifacts.ps1 -Configuration Release
```

Linux CI 在构建核心后调用 `scripts/verify-ebpf-kernel.sh`，验证 cgroup
程序矩阵、TC 内核执行、UID 事务和 packet-rewrite 的真实包/挂载路径。
这不代替 Android 的 TCP、UDP、DNS 和热点全链路验收。

设备重点检查：

1. 从 1.1 升级后，配置备份存在，节点与规则保留，版本配对检查正常。
2. 本机 cgroup eBPF 成功挂载，TCP/UDP、IPv4/IPv6、DNS 和常用节点协议可用。
3. 增删排除项无需重启；首个排除项、最后一个排除项及工作资料 UID 正确处理。
4. Wi-Fi 热点开关、无热点等待、接口重建、发现失败和本机代理持续可用。
5. 停止、重启、核心崩溃恢复后，仅清理本模块记录的状态，没有遗留热点过滤器。

完整清单见 [device-acceptance.md](device-acceptance.md)。发布时还需重建固定工具链镜像，
更新其不可变摘要，并在验收完成后更新当前版本的设备验收标记。
