/// This func is called if the Request Checkbox is Enabled. You can modify the Request Data here before the request hits to the server
/// e.g. Add/Update/Remove: method, host, scheme, port, path, headers, queries, comment, color and body (json, form, plain-text, Uint8Array for Binary Body)
///
async function onRequest(context, url, request) {
  // console.log(request);
  console.log(url);

  // Update or Add new headers
  // request.headers["X-New-Headers"] = "My-Value";

  // Update or Add new queries
  // request.queries["name"] = "Proxyman";

  // Body
  // var body = request.body;
  // body["new-key"] = "new-value"
  // request.body = body;

  // Done
  return request;
}

/// This func is called if the Response Checkbox is Enabled. You can modify the Response Data here before it goes to the client
/// e.g. Add/Update/Remove: headers, statusCode, comment, color and body (json, plain-text, Uint8Array for Binary Body)
///
async function onResponse(context, url, request, response) {
 let json =   response.body;
 let urlvid = json.data.videoUrl;
 console.log(urlvid);


 const headerArray = Object.entries(request.headers).map(([key, value]) => {
      return {
        [key]: value
      };
    });

    const payload = {
      url: urlvid,
      header: null
    };
 try {
      const result = await $http.post("http://127.0.0.1:8080/download", {
        body: JSON.stringify(payload),
        headers: {
          "Content-Type": "application/json"
        }
      });
      console.log("post success, status:", result.statusCode);
    } catch (e) {
      console.log("post error:", e);
    }

  // console.log(response);

  // Update or Add new headers
  // response.headers["Content-Type"] = "application/json";

  // Update status Code
  // response.statusCode = 500;

  // Update Body
  // var body = response.body;
  // body["new-key"] = "Proxyman";
  // response.body = body;

  // Or map a local file as a body
  // response.bodyFilePath = "~/Desktop/myfile.json"

  // Done
  return response;
}
