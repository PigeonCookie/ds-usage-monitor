# DSMonitor 1.3.0

## English

- **Token kinds are read from the export's own names again.** An extra `function_call` counter row no
  longer pushes the whole CSV into the price-based ranking, which used to file `output_tokens` under
  cache-miss: the hourly chart showed `output = 0`, cache-miss was too high, and the stacked bars no
  longer added up to the card. The price ranking is now only a fallback for vocabularies the app does
  not recognise at all.
- **New `tools/build.sh`** — aapt2 + javac + d8 + zipalign + apksigner without Gradle, with an
  automatic fallback to the distro build-tools on arm64 hosts.
- Version **1.3.0** (versionCode 130).

## 中文

- **Token 类型重新以导出里的名字为准**：多出来的一行 `function_call` 不再让整份 CSV 落入「按单价
  排序」的兜底逻辑。以前它会把 `output_tokens` 挤进「未命中」，导致逐时图里输出显示 0、未命中偏大、
  各段加起来也对不上合计。价格排序现在只在类型名完全认不出时才启用。
- **新增 `tools/build.sh`**：不用 Gradle，arm64 主机上自动改用发行版 build-tools。
- 版本 **1.3.0**（versionCode 130）。
