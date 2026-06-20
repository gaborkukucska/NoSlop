import os
import re

app_api_dir = "/home/tom/NoSlop/app/src/main/java/com/noslop/app/feeds/api"
mvp_api_dir = "/home/tom/NoSlop/mvp/composeApp/src/commonMain/kotlin/com/noslop/mvp/feeds/api"

os.makedirs(mvp_api_dir, exist_ok=True)

for filename in os.listdir(app_api_dir):
    if not filename.endswith("ApiClient.kt"):
        continue

    with open(os.path.join(app_api_dir, filename), "r") as f:
        content = f.read()

    # Packages and Imports
    content = content.replace("package com.noslop.app.feeds.api", "package com.noslop.mvp.feeds.api")
    content = content.replace("import com.noslop.app.data.FeedItem", "import com.noslop.mvp.feeds.FeedItem\nimport com.noslop.mvp.httpClientEngineFactory")
    content = content.replace("import com.google.gson.Gson", "import kotlinx.serialization.json.*\nimport io.ktor.client.HttpClient\nimport io.ktor.client.request.get\nimport io.ktor.client.request.header\nimport io.ktor.client.statement.bodyAsText")
    content = re.sub(r"import com.google.gson.Json[a-zA-Z]+(\n)?", "", content)
    content = re.sub(r"import okhttp3.*(\n)?", "", content)
    content = content.replace("import com.noslop.app.debug.Logger", "")
    content = re.sub(r"Logger\.(info|warn|error|debug)\([^)]+\)", "println(\"Logger\")", content)

    # Class / Object definition
    content = re.sub(r"object ([A-Za-z0-9]+ApiClient) \{", r"class \1(private val client: HttpClient = httpClientEngineFactory()) {", content)
    
    # Remove old client and gson
    content = re.sub(r"private val client = com\.noslop\.app\.net\.HttpClientProvider\.clearnetClient\n?", "", content)
    content = re.sub(r"private val gson = Gson\(\)\n?", "private val json = Json { ignoreUnknownKeys = true; isLenient = true }\n", content)

    # Remove Logger
    content = re.sub(r"private const val TAG = \"[^\"]+\"\n?", "", content)

    # OkHttp execution -> Ktor
    content = re.sub(r"val request = Request\.Builder\(\)\s*\.url\(([^)]+)\)(.*?)\.build\(\)", r"val response = client.get(\1) \2", content, flags=re.DOTALL)
    content = content.replace(".header(", "header(")
    content = re.sub(r"val response = client\.newCall\(request\)\.execute\(\)", "", content)
    content = content.replace("!response.isSuccessful", "response.status.value !in 200..299")
    content = content.replace("response.close()", "")
    content = content.replace("response.code", "response.status.value")
    content = content.replace("response.body?.string()", "response.bodyAsText()")

    # Gson -> kotlinx.serialization
    content = content.replace("gson.fromJson(body, JsonObject::class.java)", "json.parseToJsonElement(body).jsonObject")
    content = content.replace("gson.fromJson(body, JsonArray::class.java)", "json.parseToJsonElement(body).jsonArray")
    
    # json navigation
    content = re.sub(r"\.getAsJsonObject\(\"([^\"]+)\"\)", r"[\"\1\"]?.jsonObject", content)
    content = re.sub(r"\.getAsJsonArray\(\"([^\"]+)\"\)", r"[\"\1\"]?.jsonArray", content)
    content = re.sub(r"\.get\(\"([^\"]+)\"\)", r"[\"\1\"]", content)
    
    content = content.replace("?.asString", "?.jsonPrimitive?.contentOrNull")
    content = content.replace("?.asDouble", "?.jsonPrimitive?.doubleOrNull")
    content = content.replace("?.asLong", "?.jsonPrimitive?.longOrNull")
    content = content.replace("?.asInt", "?.jsonPrimitive?.intOrNull")
    content = content.replace("?.asBoolean", "?.jsonPrimitive?.booleanOrNull")
    content = content.replace(".asString", ".jsonPrimitive.content")
    content = content.replace(".asJsonObject", ".jsonObject")
    content = content.replace(".asJsonArray", ".jsonArray")
    
    # Write back
    # Let's write to mvp dir
    if filename not in ["RedditApiClient.kt", "HackerNewsApiClient.kt"]:
        with open(os.path.join(mvp_api_dir, filename), "w") as f:
            f.write(content)

print("Ported clients.")
