#!/bin/bash
curl -s -X POST \
  -H "X-Proxy-Secret: NoSlopRocks2026" \
  -H "Content-Type: application/json" \
  -d '{"context":{"client":{"clientName":"WEB","clientVersion":"2.20240717.01.00","hl":"en","gl":"US","utcOffsetMinutes":-240}},"query":"android"}' \
  "https://yt-proxy.megadreamland.workers.dev/youtubei/v1/search?key=AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w&prettyPrint=false" | jq '.contents.twoColumnSearchResultsRenderer.primaryContents.sectionListRenderer.contents[0].itemSectionRenderer.contents | map(select(.videoRenderer != null)) | .[0].videoRenderer | {title, publishedTimeText}'
