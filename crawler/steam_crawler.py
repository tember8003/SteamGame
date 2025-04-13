import mysql.connector
import requests
from bs4 import BeautifulSoup
import time
from dotenv import load_dotenv
import os

load_dotenv()  # .env 파일 읽기

# MySQL 연결
conn = mysql.connector.connect(
    host=os.getenv("DB_HOST"),
    user=os.getenv("DB_USER"),
    password=os.getenv("DB_PASSWORD"),
    database=os.getenv("DB_NAME")
)
cursor = conn.cursor()

# 전체 앱 리스트 가져오기
def fetch_all_apps():
    url = "https://api.steampowered.com/ISteamApps/GetAppList/v2/"
    response = requests.get(url)
    data = response.json()
    return data["applist"]["apps"]

# 개별 게임 상세 정보 가져오기
def fetch_game_details(appid):
    url = f"https://store.steampowered.com/api/appdetails?appids={appid}&l=korean"
    try:
        response = requests.get(url, timeout=10)
        if response.status_code == 429:
            print(f"⚠️ 429 오류 발생: appid {appid}")
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
        print(f"❌ API 오류 appid {appid}: {e}")
        with open("failed_appids.txt", "a") as fail_log:
            fail_log.write(f"{appid}\n")
        time.sleep(1)
        return None

# 태그 크롤링 (한국어 페이지에서)
def fetch_tags_from_store(appid):
    url = f"https://store.steampowered.com/app/{appid}?l=korean"
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

# DB insert 함수 (한국어 지원 여부 및 리뷰 수 포함)
def insert_game_and_tags(appid, name, description, image_url, tags, review_count, korean_support):
    print(f"Inserting: {appid} - {name} / 리뷰수: {review_count} / 한국어: {korean_support} / 태그: {tags}")
    try:
        # 게임 삽입
        cursor.execute("""
            INSERT INTO games (appid, name, description, image_url, review_count, korean_support)
            VALUES (%s, %s, %s, %s, %s, %s)
            ON DUPLICATE KEY UPDATE name=%s
        """, (appid, name, description, image_url, review_count, korean_support, name))
        conn.commit()

        # 게임 ID 조회
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

# 크롤링 시작
log_file = open("crawler_log.txt", "a", encoding="utf-8")
apps = fetch_all_apps()
print(f"총 {len(apps)}개의 앱을 가져왔습니다.")

# 마지막 인덱스 가져오기
last_processed_index = 0
if os.path.exists("last_index.txt"):
    with open("last_index.txt", "r") as f:
        last_processed_index = int(f.read().strip() or 0)

batch_size = 100
for i in range(last_processed_index, min(len(apps), last_processed_index + 20000), batch_size):
    batch = apps[i:i + batch_size]
    print(f"\n📦 Batch {i} ~ {i + batch_size - 1}")

    for app in batch:
        appid = app["appid"]
        name = app["name"]
        if not name:
            continue

        details = fetch_game_details(appid)
        if not details or details.get("type") != "game":
            log_file.write(f"{time.ctime()} ⚠️ appid {appid} → 실패\n")
            continue

        description = details.get("short_description", "")
        image_url = details.get("header_image", "")
        review_count = details.get("recommendations", {}).get("total", 0)
        korean_supported = "korean" in details.get("supported_languages", "").lower()

        if not korean_supported:
            print(f"❌ 한국어 미지원: {name}")
            continue

        tags = fetch_tags_from_store(appid)
        insert_game_and_tags(appid, name, description, image_url, tags, review_count, korean_supported)
        log_file.write(f"{time.ctime()} ✅ 저장 완료: {name} ({appid})\n")
        time.sleep(1)

    with open("last_index.txt", "w") as f:
        f.write(str(i + batch_size))

# 실패 앱 재시도
print("📌 실패한 앱 재시도 중...")
if os.path.exists("failed_appids.txt"):
    with open("failed_appids.txt", "r") as f:
        failed_ids = [line.strip() for line in f if line.strip().isdigit()]

    for appid in failed_ids:
        appid = int(appid)
        print(f"⏳ 재시도 중: {appid}")
        details = fetch_game_details(appid)
        if not details or details.get("type") != "game":
            continue

        name = details.get("name", "")
        description = details.get("short_description", "")
        image_url = details.get("header_image", "")
        review_count = details.get("recommendations", {}).get("total", 0)
        korean_supported = "korean" in details.get("supported_languages", "").lower()
        if not korean_supported:
            continue

        tags = fetch_tags_from_store(appid)
        insert_game_and_tags(appid, name, description, image_url, tags, review_count, korean_supported)
        time.sleep(1)

log_file.close()
cursor.close()
conn.close()
