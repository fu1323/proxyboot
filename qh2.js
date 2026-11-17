
async function onRequest(context, url, request) {
    return request;
}


async function onResponse(context, url, request, response) {
    try {
        let body = response.body;

        if (typeof body === "string") {
            // 去掉 JSONP 外层 null(...)
            const match = body.match(/^null\s*\(([\s\S]*)\)\s*;?\s*$/);
            body = JSON.parse(match ? match[1] : body);
        }

        // 只获取第一个高清地址
        const urlvid = body.copies?.[1]?.playurl;

        if (!urlvid) {
            console.log("未找到 playurl");
            return response;
        }

        console.log("高清地址:", urlvid);

        // 保持原来的接口格式
        const payload = {
            url: urlvid,
            header: null
        };

        const result = await $http.post(
            "http://127.0.0.1:8080/downloadm3u8",
            {
                body: JSON.stringify(payload),
                headers: {
                    "Content-Type": "application/json"
                }
            }
        );

        console.log("POST 状态:", result.statusCode);
        console.log("POST 响应:", result.body);

    } catch (e) {
        console.log("错误:", String(e));
    }

    return response;
}
