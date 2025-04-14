import mysql.connector
import requests
from bs4 import BeautifulSoup
import time
from dotenv import load_dotenv
import os
from bs4 import BeautifulSoup
import random

load_dotenv()  # .env 파일 읽기

# MySQL 연결
conn = mysql.connector.connect(
    host=os.getenv("DB_HOST"),
    user=os.getenv("DB_USER"),
    password=os.getenv("DB_PASSWORD"),
    database=os.getenv("DB_NAME")
)
cursor = conn.cursor()

def is_korean_supported(supported_languages_raw):
    try:
        # HTML 태그 제거 + 소문자화
        text = BeautifulSoup(supported_languages_raw, "html.parser").text.lower()
        
        # 'korean' 또는 '한국어' 포함 여부 확인
        return "korean" in text or "한국어" in text
    except:
        return False

# 전체 앱 리스트 가져오기
def fetch_all_apps():
    url = "https://api.steampowered.com/ISteamApps/GetAppList/v2/"
    response = requests.get(url)
    data = response.json()
    return data["applist"]["apps"]

# 개별 게임 상세 정보 가져오기
def fetch_game_details(appid, retries=3):
    url = f"https://store.steampowered.com/api/appdetails?appids={appid}&l=korean"
    for attempt in range(retries):
        try:
            response = requests.get(url, timeout=10)
            if response.status_code == 429:
                print(f"⚠️ 429 Too Many Requests: appid {appid} → 재시도 ({attempt+1}/{retries})")
                time.sleep(5 + attempt * 3)  # 점진적으로 대기시간 증가
                continue
            response.raise_for_status()
            data = response.json()
            if not isinstance(data, dict) or not data.get(str(appid), {}).get("success"):
                return None
            return data[str(appid)]["data"]
        except Exception as e:
            print(f"❌ appid {appid}에서 오류 발생 (재시도 {attempt+1}): {e}")
            time.sleep(2)
    # 3회 실패 시
    print(f"❌ appid {appid} → 3회 재시도 실패")
    with open("failed_appids.txt", "a") as fail_log:
        fail_log.write(f"{appid}\n")
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
for i in range(last_processed_index, min(len(apps), last_processed_index + 30000), batch_size):
    batch = apps[i:i + batch_size]
    print(f"\n📦 Batch {i} ~ {i + batch_size - 1}")

    for app in batch:
        appid = app["appid"]
        name = app["name"]

        if not name:
            print(f"❌ 이름 없는 앱 건너뜀: appid {appid}")
            time.sleep(random.uniform(1, 2.5))
            continue

        print(f"🔍 앱 처리 시작: {appid} - {name}")

        details = fetch_game_details(appid)
        if not details:
            print(f"⚠️ 세부 정보 없음: appid {appid}")
            log_file.write(f"{time.ctime()} ⚠️ appid {appid} → 세부 정보 없음\n")
            time.sleep(random.uniform(1, 2.5))
            continue

        if details.get("type") != "game":
            print(f"⛔ type이 'game'이 아님: {appid} → {details.get('type')}")
            log_file.write(f"{time.ctime()} ⛔ appid {appid} → type: {details.get('type')}\n")
            time.sleep(random.uniform(1, 2.5))
            continue

        description = details.get("short_description", "")
        image_url = details.get("header_image", "")
        review_count = details.get("recommendations", {}).get("total", 0)
        lang_html = details.get("supported_languages", "")

        print(f"🌍 지원 언어(raw): {lang_html}")

        korean_supported = is_korean_supported(lang_html)
        print(f"📝 리뷰: {review_count} / 한국어 지원: {korean_supported}")

        tags = fetch_tags_from_store(appid)
        print(f"🏷️ 태그 {len(tags)}개 크롤링됨")

        insert_game_and_tags(appid, name, description, image_url, tags, review_count, korean_supported)
        log_file.write(f"{time.ctime()} ✅ 저장 완료: {name} ({appid})\n")
        print(f"✅ DB 저장 완료: {appid} - {name}")

        time.sleep(random.uniform(1, 2.5))


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

        lang_html = details.get("supported_languages", "")
        print(f"🌍 지원 언어(raw): {lang_html}")

        name = details.get("name", "")
        description = details.get("short_description", "")
        image_url = details.get("header_image", "")
        review_count = details.get("recommendations", {}).get("total", 0)
        lang_html = details.get("supported_languages", "")
        korean_supported = is_korean_supported(lang_html)

        tags = fetch_tags_from_store(appid)
        insert_game_and_tags(appid, name, description, image_url, tags, review_count, korean_supported)
        time.sleep(random.uniform(1, 2.5))

log_file.close()
cursor.close()
conn.close()
