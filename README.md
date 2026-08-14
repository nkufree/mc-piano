# MC Piano

用于 Minecraft **26.2 / Fabric Loader 0.19.3 / Fabric API 0.157.0** 的 MIDI 钢琴可视化模组。它会将 88 键钢琴建成真实方块，并提供本地 SF2 音频预览与可被 Replay Mod 录制的服务端动画两种播放方式。

## 演示

![MC Piano 演示：下落音符、键盘高亮与踏板](assets/demo.gif)

## 使用

1. 将 `.mid` / `.midi` 文件复制到游戏目录的 `midi` 文件夹。例如：
   `E:\\software\\PCL2\\.minecraft\\versions\\26.2-Fabric_0.19.3\\midi`
2. 将默认音源 `steinway_concert_piano.sf2` 放入游戏目录的 `sf2` 文件夹，例如：`E:\\software\\PCL2\\.minecraft\\versions\\26.2-Fabric_0.19.3\\sf2\\steinway_concert_piano.sf2`。若改用其他音源，可在 Mod Menu 的 Config 页面或使用 `/pianoviz soundfont <路径>` 选择；相对路径以游戏目录为基准。
3. 构建后将 `build/libs/mc-piano-0.1.0.jar` 放入同一版本目录的 `mods` 文件夹（该环境已安装 Fabric API）。
4. 进入单人存档（需要允许命令），面对琴键正面站在钢琴左下角，执行：

```text
/piano build
/piano play [Animenz]Tori_no_Uta.mid
```

`build` 从当前位置向 X 正方向建立 88 键钢琴，键盘前方是 Z 负方向；它会自动记住钢琴原点。客户端控制命令使用 `pianoviz` 前缀，避免与服务器的 `/piano build` 冲突。

需要使用 Replay Mod 录制时，请使用服务端命令 `/piano play <文件名>`。下落音符、键盘高亮、背景高亮和踏板会作为真实 `BlockDisplay` 实体同步，因此会被回放记录。服务端动画会先预滚动 7 秒，让开场音符从背板顶部下落到琴键；`/pianoviz play` 则用于本地 SF2 音频与即时预览，不会被 Replay Mod 录制。

可用客户端命令：

```text
/pianoviz list                    # 列出 midi 文件夹内容
/pianoviz load <文件名>            # 解析标准 MIDI (SMF 0/1)
/pianoviz soundfont                # 查看 SF2 后台加载状态
/pianoviz soundfont <路径>          # 换用另一个 .sf2 文件并保存设置
/pianoviz dynamics                 # 查看弱音相对强音的当前百分比
/pianoviz dynamics <0–100>         # 设置最弱力度相对最强力度的音量；中间力度平滑插值
/pianoviz export                   # 用当前 SF2 导出至 exports/<曲名>.wav
/pianoviz export <WAV路径>          # 导出至指定 .wav 路径（支持双引号路径）
/pianoviz play [x y z]             # 播放 / 从开头重新播放
/pianoviz pause
/pianoviz resume
/pianoviz stop
/pianoviz reset                   # 停止本地 SF2、移除本地预览对象
/pianoviz seek <秒>
/pianoviz speed <倍率>             # 0.25–4.0
/pianoviz status
```

可用服务端命令：

```text
/piano list                        # 列出游戏目录 midi 文件夹中的曲目
/piano play <文件名>                # 服务端播放可被 Replay Mod 录制的动画
/piano stop
/piano reset                       # 按运行时标签扫描并清空服务端钢琴面板动画状态
/piano status
```

文件名或路径中有空格时，可使用双引号，例如：

```text
/pianoviz load "My Piano Piece.mid"
/pianoviz soundfont "E:\\Studio One\\custom\\sf2\\My Piano.sf2"
```

## V0.1 功能

- 88 键布局：白键 2×6 方块、黑键 1×4 方块；黑键、下落音符和按键高亮共用同一套精确几何坐标。
- 完整 MIDI 变长值、running status、跨轨 Tempo 元事件、Note On/Off 与 CC64 Sustain 解析；同音重叠声部会保留独立时值。
- 7 秒预滚动、8 格/秒的下落音符；白键音符宽度为琴键宽度的 70%，带发光边缘和渐变填充。
- 多声部时按声部配色；单声部时按黑白键使用紫色/黄色视觉方案。
- 真实按住状态驱动的键盘与背景高亮：Note Off 后立即清除，不遗留玻璃颜色；CC64 踏板雕塑会同步状态。
- 使用 OpenJDK 开源 Gervill 直接加载 SF2。Note On/Off、力度和 CC64 均送入同一 MIDI 音源；缺少原始踏板信息的曲目会按小节自动补全踏板循环。
- 默认 Steinway SoundFont 位于游戏目录的 `sf2/steinway_concert_piano.sf2`，后台加载；可用 `/pianoviz soundfont` 查看状态。
- `/pianoviz dynamics 0` 保留 MIDI 原始强弱差异；`/pianoviz dynamics 100` 使所有非零力度达到同一峰值。默认值为 `45`，中间力度连续平滑插值。
- `/pianoviz export` 会在后台使用当前 SF2 和力度设置离线渲染已加载 MIDI，输出标准 44.1 kHz 立体声 WAV，不影响游戏内播放。
- 安装 Mod Menu 后，MC Piano 条目的 Config 页面可保存 MIDI 文件夹、SF2 路径和弱音动态百分比到 `config/mcpiano-client.properties`；Mod Menu 不安装也不影响命令使用。

## 构建

需要 JDK 25 和 Gradle 9.7+。本机可使用 `C:\Users\user\AppData\Roaming\.minecraft\runtime\java-runtime-epsilon`。首次构建会从 Fabric Maven 下载 Loom 与映射：

```powershell
.\gradlew.bat build
```

## GitHub Actions

- 推送到 `main` 会自动构建和测试，并在对应的 Actions 运行中提供可下载的 `mc-piano-test-<commit>` JAR 工件。
- 推送版本标签会自动创建正式 Release、上传非 sources 的 Mod JAR，并让 GitHub 自动生成 Release Notes。例如：

```powershell
git tag v0.1.0
git push origin v0.1.0
```

Minecraft 26.1 起已经反混淆，因此 26.2 项目不应配置 Yarn 或 Mojang mappings；本工程已采用该模式。
