// ActionController.java
package xin.chunming.down;

import okhttp3.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestBody;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

@RestController
public class action {
    AtomicInteger ai = new AtomicInteger(0);
    ArrayList<String> oldurl = new ArrayList<>();


    @PostMapping("/download")
    public String action(@RequestBody DownloadRequestBean drb) throws NoSuchAlgorithmException, KeyManagementException {
        String url = drb.getUrl();
        if (oldurl.contains(url)) {
            System.out.println("has downloaded!" + url);
            return "has downloaded!";
        } else {
            if (drb.isUseffmpeg()) {
                ffmpeg(url, new File(ai + "_" + UUID.randomUUID() + url.substring(url.length() - 6, url.length())), drb.getHeader());
            } else {

                downloadVideo(url, new File(ai + "_" + UUID.randomUUID() + url.substring(url.length() - 6, url.length())), drb.getHeader());

            }
            oldurl.add(url);
            return "OK!" + url;
        }
    }

    @PostMapping("/downloadm3u8")
    public String m3u(@RequestBody DownloadRequestBean drb) {
        String url = drb.getUrl();
        if (oldurl.contains(url)) {
            System.out.println("has downloaded!" + url);
            return "has downloaded!";
        } else {
            ffmpeg(url, new File(ai + "_" + UUID.randomUUID() + url.substring(url.length() - 6, url.length())), drb.getHeader());
        }
        oldurl.add(url);
        return "OK!" + url;
    }

    public static void downloadVideo(String url, File saveFile, ArrayList<HashMap<String, String>> header) throws
            NoSuchAlgorithmException, KeyManagementException {

        // 1. 创建信任所有证书的 TrustManager
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[]{};
                    }
                }
        };

// 2. 初始化 SSLContext
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

// 3. 构建 OkHttpClient
        OkHttpClient client = new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0])
                .hostnameVerifier((hostname, session) -> true) // 总是返回 true 表示接受所有域名
                .build();


        System.out.println("下载" + url);
        //  https://x1.ow365.cn/print/web/print.html?file=KHlwZmlsZS53cWtldGFuZy5jb20uODBcNjcxMGNlNzk2YjRmMjEyN2EzMDYxOTA1LnBkZg--
//        OkHttpClient client = new OkHttpClient();
        Request.Builder requestprep = new Request.Builder()
                .url(url);
        if (header == null) {
header=new ArrayList<HashMap<String,String>>();
            HashMap<String, String> stringStringHashMap = new HashMap<>();
            stringStringHashMap.put("User-agent","Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.4 Safari/605.1.15");
            header.add(stringStringHashMap);
        }
            for (HashMap<String, String> stringStringHashMap : header) {
                stringStringHashMap.forEach(new BiConsumer<String, String>() {
                    @Override
                    public void accept(String s, String s2) {
                        if (s.equalsIgnoreCase("Range")) {
                            s2 = "0-";
                        }
                        //System.out.println(s);
                        // System.out.println(s2);
                        requestprep.header(s, s2);
                    }
                });
            }

        Request request = null;
        request = requestprep.build();
/*sec-ch-ua
"Google Chrome";v="147", "Not.A/Brand";v="8", "Chromium";v="147"
sec-ch-ua-mobile
?0
sec-ch-ua-platform
"macOS"
sec-fetch-dest
video
sec-fetch-mode
no-cors
sec-fetch-site
same-site*/
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

                // 使用 try-with-resources 自动关闭流
                try (InputStream is = response.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(saveFile)) {

                    byte[] buffer = new byte[8192]; // 8KB 缓冲区
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, len);
                    }
                    fos.flush();
                    fos.close();
                    System.out.println("下载完成！");
                }
            }
        });
    }

    public static void ffmpeg(String url, File saveFile, ArrayList<HashMap<String, String>> header) {
        final String[] head = {""};
        for (HashMap<String, String> stringStringHashMap : header) {
            stringStringHashMap.forEach(new BiConsumer<String, String>() {
                @Override
                public void accept(String s, String s2) {

                    if (!s.equalsIgnoreCase("range")) {
                        head[0] += "\"" + s + "\"" + ":" + "\"" + s2 + "\"";
                    }

                }
            });
        }


        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                ProcessBuilder processBuilder = new ProcessBuilder("ffmpeg", "-i", url, "-headers", head[0], "-c", "copy", saveFile.getAbsolutePath() + ".mp4");
                processBuilder.redirectErrorStream(true);
                try {
                    Process start = processBuilder.start();
                    String a;
                    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(start.getInputStream()));
                    while ((a = bufferedReader.readLine()) != null) {
                        System.out.println(a + " " + url);
                    }
                    start.waitFor();
                    System.out.println(start.exitValue() + url);

                } catch (IOException e) {
                    throw new RuntimeException(e);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

            }
        });
        thread.start();

    }
}