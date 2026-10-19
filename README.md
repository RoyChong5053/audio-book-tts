# 听书 · AudioBook TTS

一个 Android 端的自动朗读电子书 App：导入书 → 边合成边播放 → 逐字 KTV 高亮 → 锁屏/后台继续听。

TTS 与连续播放部分移植自 [DeskSpirit](../DeskSpirit) 的已验证实现，改成按句预取、按书缓存。

## 功能

- **导入书籍**：PDF、EPUB、TXT、Markdown、HTML。通过系统文件选择器导入，不需要存储权限。
- **KTV 高亮**：按句合成，播放时按「标点加权」的字符时长把音频进度换算成文字位置；已读变亮、当前句加背景、自动滚动。
- **点哪读哪**：长按正文任意位置，从那一句开始朗读；上一句/下一句、上一章/下一章、目录跳转。
- **主题**：暗色、纸质、夜间纯黑、护眼绿；字号、行距、页边距、衬线/无衬线可调。
- **进度与书签**：自动记录到句子级；手动书签可加备注，支持跳转/删除。
- **后台听书**：前台服务 + MediaSession + 通知栏（上一句/暂停/下一句/停止）、锁屏控制、耳机拔插暂停、音频焦点。
- **睡眠定时**：15 / 30 / 45 / 60 分钟后自动暂停。
- **音频缓存**：按 `model + voice + 文本` 缓存每一句，重听不重复合成；可设置容量上限并一键清空。
- **TTS 配置**：OpenAI 兼容 `/audio/speech`；Base URL / API Key / Model / Voice / 语速 / 超时；支持拉取 `/v1/models` 和测试朗读。
- **语速**：在本机播放端变速，不向服务端发送 `speed`（`audio.cpp` / `qwen3-tts` 会拒绝该参数）。

## 支持的格式

| 格式 | 解析方式 | 说明 |
|---|---|---|
| TXT | 自动识别 UTF-8 / UTF-16 BOM / GB18030 | 按「第 X 章」等标题切章，无标题时自动分节 |
| Markdown | 去语法标记，`#` 标题切章 | 支持 front matter、代码块、列表 |
| HTML | Jsoup 抽取正文，h1–h4 切章 | |
| EPUB | Zip + OPF spine + NAV/NCX 目录 | 提取封面、作者；每个 spine 文件一章 |
| PDF | PdfBox-Android 提取文字 + 段落重排 | 清理页眉页脚/页码，合并硬换行，英文断词拼接；扫描版 PDF 需要 OCR（未内置） |

## 使用

1. 打开 App，点「导入书籍」，选择文件。
2. 在书架点一本书，进入阅读页。
3. 点底部 ▶ 开始朗读；暂停后点 ▶ 从当前句继续。
4. 长按正文任意句子，可从该句开始读。
5. 顶栏：目录 / 书签 / 更多（上一章、下一章、主题、字号、定时、设置）。

## TTS 配置

默认值与 DeskSpirit 一样指向局域网 `audio.cpp`：

```
Base URL: http://192.168.100.200:11439/v1
Model:    Qwen3-TTS-12Hz-0.6B-CustomVoice
Voice:    serena
```

Base URL 支持三种写法：

- `https://api.example.com`
- `https://api.example.com/v1`
- 完整端点 `https://api.example.com/v1/audio/speech`

设置页的「拉取模型列表」会请求 `GET /v1/models`，选中后自动填入 Model。

## 项目结构

```
app/src/main/java/com/roychong/audiobook/
├── MainActivity.kt              书架 / 导入 / 进度
├── ReaderActivity.kt            阅读器 / KTV 高亮 / 手势 / 书签
├── SettingsActivity.kt          TTS、缓存、主题与排版设置
├── AppSettings.kt               设置模型（含 4 套主题色）
├── book/
│   ├── Book.kt                  Book / Chapter / Sentence / 进度 / 书签
│   └── BookStore.kt             书库、导入、进度、书签持久化
├── parse/
│   └── BookParser.kt            TXT / MD / HTML / EPUB / PDF 解析
├── tts/
│   ├── Http.kt                  URL 拼接与连接
│   ├── TtsClient.kt             /audio/speech，可取消
│   └── TtsCache.kt              句子级磁盘缓存 + LRU 清理
└── player/
    ├── AudioPlayer.kt           AudioTrack 连续通道、预缓冲、进度
    ├── PcmFramer.kt             跨块 16-bit 帧对齐
    ├── Spectrum.kt              FFT（保留自 DeskSpirit）
    ├── ReadingController.kt     合成/预取/自动跨章/进度/定时
    └── PlaybackService.kt       前台服务、MediaSession、通知、唤醒锁
```

## 构建

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

环境：JDK 17、Android SDK 35、minSdk 24。

## 数据位置

- 书库 / 正文 JSON / 封面：`files/audiobook/`（应用私有）
- 原始导入文件：`files/audiobook/sources/`
- 音频缓存：`Android/data/com.roychong.audiobook/files/tts_cache/segments/`
- 设置、进度、书签：SharedPreferences

## 已知限制

- 扫描版 PDF 没有文字层，导入时会提示需要 OCR。
- PDF 的复杂多栏排版只能做启发式重排；正文顺序可能不完美。
- KTV 高亮是按字符权重估算的，不是强制对齐；极快/极慢语速下可能有半句偏差。
- MOBI/AZW3/DOCX 未支持。
- 只接在线 TTS；断网时不能朗读。
- 语速在本地变速，改变速度时音高会有轻微变化。

## 参考

- 播放通道、KTV 权重算法、TTS 客户端与取消机制：DeskSpirit（`com.roychong.deskspirit`）
- PDF 解析：[PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)
- HTML/EPUB 正文提取：[jsoup](https://jsoup.org/)
