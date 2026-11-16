#!/usr/bin/env python3
"""부하 테스트용 계정·정답 라벨 생성기.

산출물
  data/users.csv         userId,loginId,password,label,memberId   (k6가 읽음)
  data/ground_truth.csv  userId,label,memberId                    (탐지율·오탐률 계산용 정답지)

memberId 는 개발 모드(X-Member-Id 헤더로 로그인 없이 회원 구분)에서 서버가 보는 회원 ID 이다.
기본은 5000000 + 순번이며 k6 쪽 MEMBER_BASE(기본 5000000)와 같아야 한다.

label: normal | bot_rapid | bot_bulk | bot_cancel
  - 라벨은 서버로 보내지 않는다. 서버가 블라인드로 탐지해야 정답지로서 의미가 있다.

--register 를 주면 회원가입 API로 실제 등록까지 한다 (로컬/내부 주소만 허용).
"""
import argparse
import csv
import json
import os
import random
import re
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlparse

BOT_LABELS = ["bot_rapid", "bot_bulk", "bot_cancel"]
SAFE_HOST = re.compile(r"^(localhost|127\.0\.0\.1|::1|host\.docker\.internal)$|\.(local|test|localhost|internal)$")


def check_target(url: str, allow: bool) -> None:
    host = urlparse(url).hostname or ""
    if not SAFE_HOST.search(host) and not allow:
        sys.exit(f"[중단] {host} 는 로컬/내부 주소가 아닙니다. 소유한 서버라면 --i-own-this-target 을 추가하세요.")


def register(url: str, row: dict, extra: dict) -> bool:
    body = {"loginId": row["loginId"], "password": row["password"], **extra}
    req = urllib.request.Request(
        url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json", "X-Load-Test": "1"}
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return 200 <= r.status < 300
    except Exception:
        return False


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--count", type=int, default=10000)
    p.add_argument("--bot-ratio", type=float, default=0.05, help="전체 중 봇 비율 (3종 균등 분배)")
    p.add_argument("--prefix", default="lt")
    p.add_argument("--member-base", type=int, default=5000000, help="개발 모드 회원 ID 시작값 (k6의 MEMBER_BASE와 맞춘다)")
    p.add_argument("--password", default="Test!pass1234")
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--outdir", default=os.path.join(os.path.dirname(__file__), "..", "data"))
    p.add_argument("--register", metavar="SIGNUP_URL", help="예: http://localhost:8080/api/members/signup")
    p.add_argument("--extra-json", default="{}", help='가입 시 추가 필드. 예: \'{"name":"테스트","phone":"01000000000"}\'')
    p.add_argument("--i-own-this-target", action="store_true")
    a = p.parse_args()

    rnd = random.Random(a.seed)
    n_bot = int(a.count * a.bot_ratio)
    labels = [BOT_LABELS[i % 3] for i in range(n_bot)] + ["normal"] * (a.count - n_bot)
    rnd.shuffle(labels)

    rows = [
        {"userId": f"u{i:06d}", "loginId": f"{a.prefix}{i:06d}", "password": a.password, "label": labels[i - 1],
         "memberId": a.member_base + i}
        for i in range(1, a.count + 1)
    ]

    os.makedirs(a.outdir, exist_ok=True)
    with open(os.path.join(a.outdir, "users.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["userId", "loginId", "password", "label", "memberId"])
        w.writeheader()
        w.writerows(rows)
    with open(os.path.join(a.outdir, "ground_truth.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["userId", "label", "memberId"])
        w.writerows([(r["userId"], r["label"], r["memberId"]) for r in rows])

    summary = {k: labels.count(k) for k in ["normal", *BOT_LABELS]}
    print(f"생성 완료: {a.count}명 {summary} → {os.path.abspath(a.outdir)}")

    if a.register:
        check_target(a.register, a.i_own_this_target)
        extra = json.loads(a.extra_json)
        with ThreadPoolExecutor(max_workers=16) as ex:
            ok = sum(ex.map(lambda r: register(a.register, r, extra), rows))
        print(f"회원가입 성공 {ok}/{len(rows)}")


if __name__ == "__main__":
    main()
