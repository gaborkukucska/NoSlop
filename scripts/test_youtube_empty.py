import requests, json

url = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false"
headers = {
    "User-Agent": "Mozilla/5.0",
    "Origin": "https://www.youtube.com"
}
payload = {
    "context": {
        "client": {
            "clientName": "WEB",
            "clientVersion": "2.20240425.00.00"
        }
    },
    "query": "",
    "params": "EgIIBQ=="
}

try:
    res = requests.post(url, headers=headers, json=payload)
    print("Status:", res.status_code)
    # Check if there are video results
    data = res.json()
    contents = data.get("contents", {}).get("twoColumnSearchResultsRenderer", {}).get("primaryContents", {}).get("sectionListRenderer", {}).get("contents", [])
    videos = 0
    for section in contents:
        items = section.get("itemSectionRenderer", {}).get("contents", [])
        for item in items:
            if "videoRenderer" in item:
                videos += 1
    print("Found videos:", videos)
except Exception as e:
    print("Error:", e)
