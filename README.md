# ProxyBoot

ProxyBoot 是一个基于SpringBoot的 配合 Proxyman 使用的本地下载服务。Proxyman 负责从抓包记录里取得资源 URL，Java 服务负责重新请求资源并写入 `downloads/`。也可以直接用 curl 提交任务。

| 资源类型 | 接口 | 处理方式 | 输出文件 |
| --- | --- | --- | --- |
| PDF、直接返回文件内容的 HTTP 资源 | `POST /download` | OkHttp 发起 GET，原样保存响应体 | `downloads/序号_UUID.pdf` |
| M3U8 播放列表 | `POST /downloadm3u8` | FFmpeg 读取播放列表和分片，以 `-c copy` 封装 | `downloads/序号_UUID.mp4` |

普通下载固定使用 `.pdf` 后缀。如果提交的是 MP4 直链，文件内容仍是 MP4，下载后可按实际格式改名。`/download` 也支持传入 `"useffmpeg": true`，使用与 `/downloadm3u8` 相同的处理流程。

## 启动

准备 JDK 17。下载 HLS 时，启动 Java 的环境还需要能在 PATH 中找到 `ffmpeg`。macOS 可通过 `brew install ffmpeg` 安装。

```sh
git clone -b proxyWithDBG https://github.com/fu1323/proxyboot.git
cd proxyboot
sh mvnw spring-boot:run -Dspring-boot.run.arguments=--server.address=127.0.0.1
```

上面的命令让服务监听本机 8080 端口。首次运行时 Maven Wrapper 会下载构建所需的依赖。服务接到任务后会在工作目录下自动创建 `downloads/`。

## 配合 Proxyman 使用

在 Proxyman 的 Scripting 中为目标 URL 创建规则，载入对应脚本并启用 **Response** 回调。抓 HTTPS 时，先确认能够在 Proxyman 中看到目标接口的明文响应。

| 抓包时看到的内容 | 使用的脚本 | 触发行为 |
| --- | --- | --- |
| 业务接口返回 JSON，资源地址位于 `data.videoUrl` | [proxyman.js](proxyman.js) | 读取响应里的地址并提交到 `/download`。示例发送 `header: null`。适配其他接口时，修改读取 URL 的字段。 |
| 播放器已经发出 M3U8 请求 | [iqhproxyman.js](iqhproxyman.js) | 当请求 URL 包含小写 `m3u8` 时，把该 URL 和原请求头一起提交到 `/downloadm3u8`。 |

第一种规则应匹配返回资源地址的 JSON 接口，第二种应匹配播放列表请求。重新打开文档或触发播放后，在 Proxyman 控制台查看提交状态，在 Java 服务控制台查看下载结果。

## 手动提交任务

将示例地址替换成实际资源地址。两个接口都接收 JSON，`url` 必须是带主机名的 HTTP 或 HTTPS 地址。`header` 可省略，也可传入由请求头对象组成的数组。

**下载文档**

```sh
curl http://127.0.0.1:8080/download \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.org/files/lecture.pdf"}'
```

**下载带 Referer 的 HLS 视频**

```sh
curl http://127.0.0.1:8080/downloadm3u8 \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.org/video/index.m3u8","header":[{"Referer":"https://example.org/player/"}]}'
```

服务会合并 `header` 数组中的所有对象。两条下载路径对请求头的处理略有区别。

- **OkHttp** 直连资源服务器。请求头为空时使用内置 Safari User-Agent。原请求若带有 `Range`，服务将其改为 `bytes=0-`，从文件开头下载。
- **FFmpeg** 保留 Referer、User-Agent、Cookie 等头，过滤 Range、Host、Accept、If-None-Match 和 If-Modified-Since。如果在 `application.properties` 中设置了非空的 `down.cookie`，该值会覆盖提交给 FFmpeg 的 Cookie。

## 下载状态与重试

返回 `OK!<url>` 表示任务已提交，文件会在后台写入。普通下载完成后，控制台会打印文件路径；FFmpeg 的处理日志也会输出到控制台。FFmpeg 的线程池最多同时执行 30 个任务，其余任务排队。

两个接口按完整 URL 共享去重记录。任务进行中或已经成功时，再次提交会返回 `has downloaded!`。下载失败后会释放该 URL，可以重新提交。去重记录保存在内存中，重启后清空。

## 按时长整理视频

将 [proberename.command](proberename.command) 复制到下载目录后执行：

```sh
cp proberename.command downloads/
bash downloads/proberename.command
```

脚本调用 `ffprobe` 读取同目录 MP4 的时长，并将其作为文件名前缀。例如，时长 1 小时 15 分 30 秒的 `lecture.mp4` 会改名为 `01-15-30_lecture.mp4`。

## 实验功能 · unidbg

`POST /dbg` 用于验证特定 Android 应用的数据处理流程。它接收含 `data` 字段的 JSON，借助 [unidbgtool.java](src/main/java/xin/chunming/down/unidbgtool.java) 和仓库内的 APK、SO 处理数据，从结果中提取 M3U8 地址，再交给 FFmpeg。日常下载直接使用上面的两个接口即可。
