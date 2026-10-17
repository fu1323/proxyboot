package xin.chunming.down;

import jakarta.annotation.PreDestroy;
import okhttp3.*;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestBody;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
public class ActionController {

    /*
     * 这个 Set 同时表示：
     *
     * 1. 正在下载
     * 2. 已经成功下载
     *
     * 如果下载失败，会 remove(url)，这样下次允许重新下载。
     *
     * ConcurrentHashMap.newKeySet() 是线程安全的，
     * add() 本身也是原子的，可以防止两个请求同时下载同一个 URL。
     */
    private final Set<String> reservedUrls = ConcurrentHashMap.newKeySet();

    private final AtomicInteger counter = new AtomicInteger(0);

    /*
     * 限制同时最多跑 3 个 ffmpeg。
     *
     * 不再每次 new Thread()。
     */
    private final ExecutorService ffmpegExecutor =
            Executors.newFixedThreadPool(3);

    /*
     * OkHttpClient 本身就是线程安全的，
     * 没必要每次下载重新创建。
     *
     * 不再关闭 SSL 校验。
     */
    private final OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(100, TimeUnit.SECONDS)
            .proxy(java.net.Proxy.NO_PROXY)
            .build();

    /*
     * 下载目录：
     *
     * 项目运行目录/downloads/
     */
    private static final Path DOWNLOAD_DIR =
            Path.of("downloads")
                    .toAbsolutePath()
                    .normalize();

    /*
     * m3u8 正则
     */
    private static final Pattern M3U8_PATTERN =
            Pattern.compile(
                    "https?://[^\\s\"']+?\\.m3u8(?:\\?[^\\s\"']*)?"
            );

    /*
     * 不建议把这些东西硬编码在源码里。
     *
     * application.properties:
     *
     * down.jsl-api-auth=xxxxxxxx
     * down.cookie=JSESSIONID=xxxxxxxx; clientlanguage=zh_CN
     */

    @Value("${down.jsl-api-auth:}")
    private String jslApiAuth;

    @Value("${down.cookie:}")
    private String downloadCookie;


    // =========================================================
    // /dbg
    // =========================================================

    @PostMapping("/dbg")
    public String dbg(@RequestBody dbgbean bean) throws IOException {

        String doing = unidbgtool.doing(bean.data);

        if (doing == null) {
            return null;
        }

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(doing);

        /*
         * 不再：
         *
         * json.get("data").get("row").get("title")
         *
         * 防止中间某一级不存在直接 NPE。
         */
        String filename = "";

        JsonNode titleNode = jsonNode
                .path("data")
                .path("row")
                .path("title");

        if (!titleNode.isMissingNode() && !titleNode.isNull()) {
            filename = titleNode.asString();
        }

        /*
         * 提取 m3u8
         */
        Matcher matcher = M3U8_PATTERN.matcher(doing);

        String url = null;

        if (matcher.find()) {
            url = matcher.group();
            System.out.println("发现 m3u8: " + url);
        }

        if (url == null) {
            return "NO M3U8";
        }

        validateHttpUrl(url);

        /*
         * 这里 add() 同时完成：
         *
         * contains()
         * +
         * add()
         *
         * 两个动作。
         *
         * 是原子的。
         */
        if (!reservedUrls.add(url)) {
            System.out.println("has downloaded/downloading! " + url);
            return "has downloaded!";
        }

        ArrayList<HashMap<String, String>> headers = new ArrayList<>();

        HashMap<String, String> map = new HashMap<>();

        map.put("Accept-Encoding", "identity");
        map.put("Referer", "https://play.nbaidu.com");

        if (jslApiAuth != null && !jslApiAuth.isBlank()) {
            map.put(
                    "X-JSL-API-AUTH",
                    jslApiAuth
            );
        }

        headers.add(map);

        try {

            File outputBase = createOutputBase(
                    filename.isBlank()
                            ? UUID.randomUUID().toString()
                            : filename.strip(),
                    ".mp4"
            );

            ffmpegAsync(
                    url,
                    outputBase,
                    headers
            );

            /*
             * 注意：
             *
             * 这里表示“任务已经接受”
             * 并不表示 ffmpeg 已经下载完成。
             */
            return "OK";

        } catch (Exception e) {

            /*
             * 连任务都没成功提交，就释放 URL。
             */
            reservedUrls.remove(url);

            throw e;
        }
    }


    // =========================================================
    // /download
    // =========================================================

    @PostMapping("/download")
    public String download(
            @RequestBody DownloadRequestBean drb
    ) throws IOException {

        String url = drb.getUrl();

        validateHttpUrl(url);

        if (!reservedUrls.add(url)) {
            System.out.println(
                    "has downloaded/downloading! " + url
            );

            return "has downloaded!";
        }

        int id = counter.getAndIncrement();

        try {

            if (drb.isUseffmpeg()) {

                File outputBase = createOutputBase(
                        id + "_" + UUID.randomUUID(),
                        ".mp4"
                );

                ffmpegAsync(
                        url,
                        outputBase,
                        drb.getHeader()
                );

            } else {

                File outputBase = createOutputBase(
                        id + "_" + UUID.randomUUID(),
                        ".pdf"
                );

                downloadVideoAsync(
                        url,
                        outputBase,
                        drb.getHeader()
                );
            }

            return "OK!" + url;

        } catch (Exception e) {

            reservedUrls.remove(url);

            throw e;
        }
    }


    // =========================================================
    // /downloadm3u8
    // =========================================================

    @PostMapping("/downloadm3u8")
    public String downloadM3u8(
            @RequestBody DownloadRequestBean drb
    ) throws IOException {

        String url = drb.getUrl();

        validateHttpUrl(url);

        if (!reservedUrls.add(url)) {

            System.out.println(
                    "has downloaded/downloading! " + url
            );

            return "has downloaded!";
        }

        int id = counter.getAndIncrement();

        try {

            File outputBase = createOutputBase(
                    id + "_" + UUID.randomUUID(),
                    ".mp4"
            );

            ffmpegAsync(
                    url,
                    outputBase,
                    drb.getHeader()
            );

            return "OK!" + url;

        } catch (Exception e) {

            reservedUrls.remove(url);

            throw e;
        }
    }


    // =========================================================
    // 普通 HTTP 下载
    // =========================================================

    private void downloadVideoAsync(
            String url,
            File saveFile,
            ArrayList<HashMap<String, String>> header
    ) {

        System.out.println("下载: " + url);

        Map<String, String> headers =
                flattenHeaders(header);

        /*
         * 没 header 时给一个默认 UA。
         */
        if (headers.isEmpty()) {

            headers.put(
                    "User-Agent",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                            "AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                            "Version/26.4 Safari/605.1.15"
            );
        }

        Request.Builder requestBuilder =
                new Request.Builder()
                        .url(url);

        for (Map.Entry<String, String> entry :
                headers.entrySet()) {

            String name = entry.getKey();
            String value = entry.getValue();

            if (name == null || value == null) {
                continue;
            }

            checkHeader(name, value);

            /*
             * 原来的逻辑：
             *
             * Range 无论传什么，都改成 bytes=0-
             */
            if (name.equalsIgnoreCase("Range")) {
                value = "bytes=0-";
            }

            requestBuilder.header(
                    name,
                    value
            );
        }

        Request request = requestBuilder.build();

        client.newCall(request)
                .enqueue(
                        new Callback() {

                            @Override
                            public void onFailure(
                                    Call call,
                                    IOException e
                            ) {

                                /*
                                 * 下载失败，
                                 * 释放这个 URL，
                                 * 以后允许重新下载。
                                 */
                                reservedUrls.remove(url);

                                System.err.println(
                                        "下载失败: " + url
                                );

                                e.printStackTrace();
                            }

                            @Override
                            public void onResponse(
                                    Call call,
                                    Response response
                            ) {

                                try (response) {

                                    if (!response.isSuccessful()) {

                                        reservedUrls.remove(url);

                                        System.err.println(
                                                "HTTP 下载失败: "
                                                        + response.code()
                                                        + " "
                                                        + url
                                        );

                                        return;
                                    }

                                    ResponseBody body =
                                            response.body();

                                    if (body == null) {

                                        reservedUrls.remove(url);

                                        System.err.println(
                                                "响应 body 为空: "
                                                        + url
                                        );

                                        return;
                                    }

                                    File target =
                                            new File(
                                                    saveFile.getAbsolutePath()
                                                            + ".pdf"
                                            );

                                    try (
                                            InputStream is =
                                                    body.byteStream();

                                            FileOutputStream fos =
                                                    new FileOutputStream(target)
                                    ) {

                                        byte[] buffer =
                                                new byte[8192];

                                        int len;

                                        while (
                                                (len = is.read(buffer))
                                                        != -1
                                        ) {

                                            fos.write(
                                                    buffer,
                                                    0,
                                                    len
                                            );
                                        }
                                    }

                                    /*
                                     * 成功时不 remove。
                                     *
                                     * URL 留在 reservedUrls，
                                     * 表示已经下载过。
                                     */
                                    System.out.println(
                                            "下载完成: "
                                                    + target.getAbsolutePath()
                                    );

                                } catch (Exception e) {

                                    reservedUrls.remove(url);

                                    System.err.println(
                                            url + " 下载失败"
                                    );

                                    e.printStackTrace();
                                }
                            }
                        }
                );
    }


    // =========================================================
    // FFmpeg 下载
    // =========================================================

    private void ffmpegAsync(
            String url,
            File saveFile,
            ArrayList<HashMap<String, String>> header
    ) {

        /*
         * 在提交线程之前先构造 Header。
         *
         * 如果这里发现非法 header，
         * 可以直接报错，
         * 不会丢到后台线程里。
         */
        String ffmpegHeaders =
                buildFfmpegHeaders(header);

        try {

            ffmpegExecutor.submit(
                    () -> runFfmpeg(
                            url,
                            saveFile,
                            ffmpegHeaders
                    )
            );

        } catch (RejectedExecutionException e) {

            reservedUrls.remove(url);

            throw e;
        }
    }


    private void runFfmpeg(
            String url,
            File saveFile,
            String ffmpegHeaders
    ) {

        File target =
                new File(
                        saveFile.getAbsolutePath()
                                + ".mp4"
                );

        List<String> command =
                new ArrayList<>();

        command.add("ffmpeg");

        /*
         * header 不为空才传 -headers。
         */
        if (!ffmpegHeaders.isBlank()) {

            command.add("-headers");
            command.add(ffmpegHeaders);
        }

        command.add("-i");
        command.add(url);

        command.add("-c");
        command.add("copy");

        command.add(target.getAbsolutePath());

        ProcessBuilder processBuilder =
                new ProcessBuilder(command);

        processBuilder.redirectErrorStream(true);

        System.out.println(
                "启动 FFmpeg:"
        );

        command.forEach(
                s -> System.out.print(
                        s + " "
                )
        );

        System.out.println();

        Process process = null;

        try {

            process = processBuilder.start();

            /*
             * ffmpeg 大量输出 stderr。
             *
             * redirectErrorStream(true) 后，
             * stdout/stderr 合并。
             *
             * 必须持续读取，
             * 否则输出缓冲区满了以后
             * ffmpeg 可能阻塞。
             */
            try (
                    BufferedReader reader =
                            new BufferedReader(
                                    new InputStreamReader(
                                            process.getInputStream()
                                    )
                            )
            ) {

                String line;

                while ((line = reader.readLine()) != null) {

                    System.err.println(
                            line + " " + url
                    );
                }
            }

            int exitCode =
                    process.waitFor();

            if (exitCode == 0) {

                /*
                 * 成功。
                 *
                 * 不 remove(url)，
                 * URL 留在 Set 中。
                 */
                System.out.println(
                        "FFmpeg 下载完成: "
                                + target.getAbsolutePath()
                );

            } else {

                /*
                 * 最重要的修改之一：
                 *
                 * ffmpeg 非 0 就说明失败，
                 * 必须释放 URL，
                 * 下次才能重新下载。
                 */
                reservedUrls.remove(url);

                System.err.println(
                        "FFmpeg 下载失败"
                                + ", exitCode="
                                + exitCode
                                + ", url="
                                + url
                );
            }

        } catch (IOException e) {

            reservedUrls.remove(url);

            System.err.println(
                    "无法启动/运行 FFmpeg: "
                            + url
            );

            e.printStackTrace();

        } catch (InterruptedException e) {

            reservedUrls.remove(url);

            if (process != null) {
                process.destroyForcibly();
            }

            /*
             * 恢复线程中断状态。
             */
            Thread.currentThread().interrupt();

            System.err.println(
                    "FFmpeg 线程被中断: "
                            + url
            );
        }
    }


    // =========================================================
    // FFmpeg Headers
    // =========================================================

    private String buildFfmpegHeaders(
            ArrayList<HashMap<String, String>> header
    ) {

        Map<String, String> headers =
                flattenHeaders(header);

        /*
         * 如果 application.properties
         * 配置了 Cookie，
         * 用配置的 Cookie 覆盖抓包中的 Cookie。
         *
         * 没配置就保留客户端原来的 Cookie。
         */
        if (
                downloadCookie != null
                        && !downloadCookie.isBlank()
        ) {

            removeHeaderIgnoreCase(
                    headers,
                    "Cookie"
            );

            headers.put(
                    "Cookie",
                    downloadCookie
            );
        }

        StringBuilder builder =
                new StringBuilder();

        for (
                Map.Entry<String, String> entry :
                headers.entrySet()
        ) {

            String name =
                    entry.getKey();

            String value =
                    entry.getValue();

            if (
                    name == null
                            || value == null
            ) {

                continue;
            }

            /*
             * 这些 header 不适合直接复制给 ffmpeg。
             */
            if (
                    name.equalsIgnoreCase("Range")
                            ||
                            name.equalsIgnoreCase("Host")
                            ||
                            name.equalsIgnoreCase("Accept")
                            ||
                            name.equalsIgnoreCase("If-None-Match")
                            ||
                            name.equalsIgnoreCase("If-Modified-Since")
            ) {

                continue;
            }

            checkHeader(
                    name,
                    value
            );

            builder
                    .append(name)
                    .append(": ")
                    .append(value)
                    .append("\r\n");
        }

        return builder.toString();
    }


    // =========================================================
    // Header 工具
    // =========================================================

    /*
     * 原来的类型是：
     *
     * ArrayList<HashMap<String,String>>
     *
     * 我暂时不改 Bean，
     * 所以把所有 Map 合并起来。
     *
     * 不再只使用 header.get(0)。
     */
    private Map<String, String> flattenHeaders(
            ArrayList<HashMap<String, String>> header
    ) {

        Map<String, String> result =
                new LinkedHashMap<>();

        if (header == null) {
            return result;
        }

        for (
                HashMap<String, String> map :
                header
        ) {

            if (map == null) {
                continue;
            }

            result.putAll(map);
        }

        return result;
    }


    private void removeHeaderIgnoreCase(
            Map<String, String> headers,
            String name
    ) {

        headers
                .keySet()
                .removeIf(
                        key ->
                                key != null
                                        &&
                                        key.equalsIgnoreCase(name)
                );
    }


    /*
     * 防止：
     *
     * abc\r\nX-Test: xxx
     *
     * 这种 CRLF Header 注入。
     */
    private void checkHeader(
            String name,
            String value
    ) {

        if (
                name.contains("\r")
                        ||
                        name.contains("\n")
                        ||
                        value.contains("\r")
                        ||
                        value.contains("\n")
        ) {

            throw new IllegalArgumentException(
                    "非法 HTTP Header"
            );
        }
    }


    // =========================================================
    // URL 校验
    // =========================================================

    private void validateHttpUrl(
            String url
    ) {

        if (
                url == null
                        ||
                        url.isBlank()
        ) {

            throw new IllegalArgumentException(
                    "URL 不能为空"
            );
        }

        URI uri;

        try {

            uri = URI.create(url);

        } catch (Exception e) {

            throw new IllegalArgumentException(
                    "非法 URL: " + url,
                    e
            );
        }

        String scheme =
                uri.getScheme();

        if (
                !"http".equalsIgnoreCase(scheme)
                        &&
                        !"https".equalsIgnoreCase(scheme)
        ) {

            throw new IllegalArgumentException(
                    "只允许 HTTP/HTTPS URL"
            );
        }

        if (
                uri.getHost() == null
                        ||
                        uri.getHost().isBlank()
        ) {

            throw new IllegalArgumentException(
                    "URL 没有合法 host"
            );
        }
    }


    // =========================================================
    // 文件名
    // =========================================================

    /*
     * synchronized：
     *
     * 避免两个请求同时检查文件不存在，
     * 最后生成同名文件。
     */
    private synchronized File createOutputBase(
            String filename,
            String extension
    ) throws IOException {

        Files.createDirectories(
                DOWNLOAD_DIR
        );

        String safeName =
                sanitizeFilename(filename);

        if (
                safeName.isBlank()
                        ||
                        safeName.equals(".")
                        ||
                        safeName.equals("..")
        ) {

            safeName =
                    UUID.randomUUID().toString();
        }

        /*
         * 防止标题巨长。
         */
        if (safeName.length() > 150) {

            safeName =
                    safeName.substring(
                            0,
                            150
                    );
        }

        Path base =
                DOWNLOAD_DIR
                        .resolve(safeName)
                        .normalize();

        /*
         * 双重防御 Path Traversal。
         */
        if (!base.startsWith(DOWNLOAD_DIR)) {

            throw new IllegalArgumentException(
                    "非法文件名"
            );
        }

        Path target =
                Path.of(
                        base.toString()
                                + extension
                );

        /*
         * 如果已经有同名文件：
         *
         * 标题.mp4
         *
         * 自动改成：
         *
         * 标题_1.mp4
         * 标题_2.mp4
         */
        int index = 1;

        while (Files.exists(target)) {

            Path newBase =
                    DOWNLOAD_DIR.resolve(
                            safeName
                                    + "_"
                                    + index
                    );

            target =
                    Path.of(
                            newBase.toString()
                                    + extension
                    );

            base = newBase;

            index++;
        }

        return base.toFile();
    }


    private String sanitizeFilename(
            String filename
    ) {

        if (filename == null) {
            return "";
        }

        /*
         * Windows/macOS/Linux 都顺手处理。
         *
         * / 和 \ 最重要，
         * 防止目录穿越。
         */
        return filename
                .replaceAll(
                        "[\\\\/:*?\"<>|\\p{Cntrl}]",
                        "_"
                )
                .strip();
    }


    // =========================================================
    // Spring 销毁
    // =========================================================

    @PreDestroy
    public void shutdown() {

        ffmpegExecutor.shutdownNow();

        client
                .dispatcher()
                .executorService()
                .shutdown();

        client
                .connectionPool()
                .evictAll();
    }
}