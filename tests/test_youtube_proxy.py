import requests, json

url = "https://ytproxy.noslop.com/youtubei/v1/search?key=dummy&prettyPrint=false"
headers = {
    "X-Proxy-Secret": "NOSLOP_SUPER_SECRET_123!"
}
payload = {
    "context": {
        "client": {
            "clientName": "WEB",
            "clientVersion": "2.20240425.00.00"
        }
    },
    "query": "cats",
    "params": "EgIIBQ=="
}

try:
    res = requests.post(url, headers=headers, json=payload)
    print("Status:", res.status_code)
except Exception as e:
    print("Error:", e)
