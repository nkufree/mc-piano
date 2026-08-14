# MC Piano

用于 Minecraft **26.2 / Fabric Loader 0.19.3 / Fabric API 0.157.0** 的客户端钢琴可视化模组。它会将 88 键钢琴建成真实方块，而 MIDI 音符、高亮和踏板是客户端渲染对象，因此播放时不会持续改写世界区块。

## 使用

1. 将 `.mid` / `.midi` 文件复制到游戏目录的 `midi` 文件夹。例如：
   `E:\\software\\PCL2\\.minecraft\\versions\\26.2-Fabric_0.19.3\\midi`
2. 将默认音源 `steinway_concert_piano.sf2` 放入游戏目录的 `sf2` 文件夹，例如：`E:\\software\\PCL2\\.minecraft\\versions\\26.2-Fabric_0.19.3\\sf2\\steinway_concert_piano.sf2`。若改用其他音源，在游戏中执行 `/pianoviz soundfont <SF2绝对路径>`。
3. 构建后将 `build/libs/mc-piano-0.1.0.jar` 放入同一版本目录的 `mods` 文件夹（该环境已安装 Fabric API）。
4. 进入单人存档（需要允许命令），面对琴键正面站在钢琴左下角，执行：

```text
/piano build
/piano play [Animenz]Tori_no_Uta.mid
```

`build` 从当前位置向 X 正方向建立 88 键钢琴，键盘前方是 Z 负方向；它会自动记住钢琴原点，之后的 `/pianoviz play` 会在这架钢琴上显示下落音符与按键高亮。也可显式覆盖原点：`/pianoviz play <x> <y> <z>`。客户端控制命令用 `pianoviz` 前缀，避免和服务器的 `/piano build` 冲突。

需要使用 Replay Mod 录制时，请使用服务端命令 `/piano play <文件名>`。下落音符、键盘高亮和踏板会作为真实 `BlockDisplay` 实体同步，因此会被回放记录。`/pianoviz play` 仍用于本地 SF2 声音与即时预览，不会被 Replay Mod 录制。

可用客户端命令：

```text
/pianoviz list                    # 列出 midi 文件夹内容
/pianoviz load <文件名>            # 解析标准 MIDI (SMF 0/1)
/pianoviz soundfont                # 查看 SF2 后台加载状态
/pianoviz soundfont <绝对路径>      # 换用另一个 .sf2 文件
/pianoviz dynamics                 # 查看弱音相对强音的当前百分比
/pianoviz dynamics <0–100>         # 设置最弱力度相对最强力度的音量；中间力度平滑插值
/pianoviz play [x y z]             # 播放 / 从开头重新播放
/pianoviz pause
/pianoviz resume
/pianoviz stop
/pianoviz seek <秒>
/pianoviz speed <倍率>             # 0.25–4.0
/pianoviz status
```

可用服务端命令：

```text
/piano list                        # 列出游戏目录 midi 文件夹中的曲目
/piano play <文件名>                # 服务端播放可被 Replay Mod 录制的动画
/piano stop
/piano status
```

文件名或路径中有空格时，可使用双引号，例如：

```text
/pianoviz load "My Piano Piece.mid"
/pianoviz soundfont "E:\\Studio One\\custom\\sf2\\My Piano.sf2"
```

## 已实现的 V0.1 规格

- 88 键布局：白键 2×6 方块、黑键 1×4 方块；
- 完整 MIDI 变长值、running status、跨轨合并、Tempo 元事件、Note On/Off 与 CC64 Sustain 解析；
- 预览 4 秒、速度 8 格/秒的 render-only 下落方块柱，长度按 MIDI 时值计算；
- 左/右手（按 MIDI 通道）橙色/蓝色柱；力度控制亮度与音量；
- 按键半透明高亮、CC64 踏板雕塑状态；
- 使用 OpenJDK 开源 Gervill 合成器直接加载 SF2；Note On/Off、力度和 CC64 均送入同一 MIDI 音源，真实踏板不再由循环或拼接音效模拟。
- 默认 Steinway SoundFont 约 34 MB，只加载其中一个钢琴预设，并在客户端后台加载；可用 `/pianoviz soundfont` 查看是否已经 Ready。
- `/pianoviz dynamics 0` 保留 MIDI 原始强弱差异；`/pianoviz dynamics 100` 会把所有非零力度提升到同一峰值。默认 `45`，可防止极弱段完全听不见。
- 安装 Mod Menu 后，MC Piano 会作为可识别的可选 Mod Menu 集成显示；设置仍通过 `/pianoviz soundfont` 与 `/pianoviz dynamics` 命令完成，Mod Menu 不安装也不影响 Mod 运行。

## 构建

需要 JDK 25 和 Gradle 9.7+。本机可使用 `C:\Users\user\AppData\Roaming\.minecraft\runtime\java-runtime-epsilon`。首次构建会从 Fabric Maven 下载 Loom 与映射：

```powershell
gradle build
```

Minecraft 26.1 起已经反混淆，因此 26.2 项目不应配置 Yarn 或 Mojang mappings；本工程已采用该模式。
