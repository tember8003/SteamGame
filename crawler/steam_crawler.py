import mysql.connector
import requests
from bs4 import BeautifulSoup
import time
from dotenv import load_dotenv
import os

load_dotenv()  # .env 파일 읽어오기

conn = mysql.connector.connect(
    host=os.getenv("DB_HOST"),
    user=os.getenv("DB_USER"),
    password=os.getenv("DB_PASSWORD"),
    database=os.getenv("DB_NAME")
)
cursor = conn.cursor()

def fetch_all_apps():
    url = "https://api.steampowered.com/ISteamApps/GetAppList/v2/"
    response = requests.get(url)
    data = response.json()
    return data["applist"]["apps"]

def fetch_game_details(appid):
    url = f"https://store.steampowered.com/api/appdetails?appids={appid}"
    try:
        response = requests.get(url, timeout=10)
        if response.status_code == 429:
            print(f"⚠️ 429 오류 발생: appid {appid} → 재시도 불가, 실패 로그 기록")
            with open("failed_appids.txt", "a") as fail_log:
                fail_log.write(f"{appid}\n")
            time.sleep(5)
            return None
        response.raise_for_status()
        data = response.json()
        if not isinstance(data, dict) or not data.get(str(appid), {}).get("success"):
            return None
        return data[str(appid)]["data"]
    except Exception as e:
        print(f"❌ appid {appid}에서 API 오류 발생: {e}")
        with open("failed_appids.txt", "a") as fail_log:
            fail_log.write(f"{appid}\n")
        time.sleep(1)
        return None


# 태그 크롤링
def fetch_tags_from_store(appid):
    url = f"https://store.steampowered.com/app/{appid}"
    headers = {"User-Agent": "Mozilla/5.0"}
    try:
        res = requests.get(url, headers=headers, timeout=10)
        if res.status_code != 200:
            return []
        soup = BeautifulSoup(res.text, "html.parser")
        tags = [tag.text.strip() for tag in soup.select('.glance_tags.popular_tags a.app_tag')]
        return tags
    except:
        return []

#DB insert
def insert_game_and_tags(appid, name, description, image_url, tags):
    print(f"Inserting: {appid} - {name} / 태그 {tags}")
    try:
        # 게임 삽입
        cursor.execute(
            "INSERT INTO games (appid, name, description, image_url) VALUES (%s, %s, %s, %s) ON DUPLICATE KEY UPDATE name=%s",
            (appid, name, description, image_url, name)
        )
        conn.commit()

        # 게임 id
        cursor.execute("SELECT id FROM games WHERE appid = %s", (appid,))
        game_id = cursor.fetchone()[0]

        for tag in tags:
            cursor.execute("INSERT IGNORE INTO tags (name) VALUES (%s)", (tag,))
            conn.commit()

            cursor.execute("SELECT id FROM tags WHERE name = %s", (tag,))
            tag_id = cursor.fetchone()[0]

            cursor.execute("INSERT IGNORE INTO game_tags (game_id, tag_id) VALUES (%s, %s)", (game_id, tag_id))
            conn.commit()
    except Exception as e:
        print(f"❌ Error inserting {name} ({appid}):", e)

log_file = open("crawler_log.txt", "a", encoding="utf-8")

apps = fetch_all_apps()
print(f"총 {len(apps)}개의 앱을 가져왔습니다.")

last_processed_index = 0
if os.path.exists("last_index.txt"):
    with open("last_index.txt", "r") as f:
        last_processed_index = int(f.read().strip() or 0)

print(f"{last_processed_index}부터 실행하겠습니다")
# 배치 크기 설정
batch_size = 100
start_index = 0

for i in range(last_processed_index, min(len(apps), last_processed_index + 20000), batch_size):
    batch = apps[i:i + batch_size]
    print(f"\n📦 Processing batch {i} ~ {i + batch_size - 1}")

    for app in batch:
        appid = app["appid"]
        name = app["name"]
        if not name:
            continue

        details = fetch_game_details(appid)
        if not details or details.get("type") != "game":
            log_file.write(f"{time.ctime()} ⚠️ appid {appid} → 상세정보 불러오기 실패\n")
            continue

        description = details.get("short_description", "")
        image_url = details.get("header_image", "")
        tags = fetch_tags_from_store(appid)

        insert_game_and_tags(appid, name, description, image_url, tags)
        print(f"✅ 저장 완료: {name} ({appid}) with {len(tags)} tags")
        log_file.write(f"{time.ctime()} ✅ 저장 완료: {name} ({appid})\n")
        time.sleep(1)

    # ✅ 현재 인덱스를 저장해두기
    with open("last_index.txt", "w") as f:
        f.write(str(i + batch_size))

print("저장 끝! 이제 실패했던 APP ID를 재시도합니다!!")

with open("failed_appids.txt", "r") as f:
    failed_ids = [line.strip() for line in f if line.strip().isdigit()]

for appid in failed_ids:
    appid = int(appid)
    print(f"⏳ 재시도 중: appid {appid}")
    details = fetch_game_details(appid)
    if not details or details.get("type") != "game":
        continue

    name = details.get("name", "")
    description = details.get("short_description", "")
    image_url = details.get("header_image", "")
    tags = fetch_tags_from_store(appid)
    insert_game_and_tags(appid, name, description, image_url, tags)
    print(f"✅ 재시도 저장 완료: {name} ({appid})")
    time.sleep(1)

log_file.close()
cursor.close()
conn.close()
