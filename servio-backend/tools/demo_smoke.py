"""
Servio — chạy thử luồng demo 10 bước (spec v1.4 mục 0.2.8) và #177 qua HTTP, đúng như hai app và trang quản trị gọi.

Dùng trước buổi demo để chắc backend + CSDL trên laptop chạy trọn luồng:
    python servio-backend/tools/demo_smoke.py                       # backend ở http://localhost:5080
    python servio-backend/tools/demo_smoke.py http://192.168.1.10:5080

Cần: backend chạy ở Development/Demo (OTP cố định 123456), đã chạy 02_seed.sql, tài khoản admin trong SeedAdmin.
Script tạo khách và đối tác mới (số điện thoại theo thời gian), nên chạy nhiều lần không đụng nhau.
Chỉ dùng thư viện chuẩn của Python 3.
"""
import base64
import html as htmllib
import http.cookiejar
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

sys.stdout.reconfigure(encoding="utf-8")  # Vietnamese output on a Windows console

BASE = (sys.argv[1] if len(sys.argv) > 1 else os.environ.get("SERVIO_URL", "http://localhost:5080")).rstrip("/")
ADMIN_EMAIL = os.environ.get("SERVIO_ADMIN_EMAIL", "admin@servio.local")
ADMIN_PASSWORD = os.environ.get("SERVIO_ADMIN_PASSWORD", "Servio@2026")
OTP = "123456"
UTE = (10.850632, 106.771888)  # 1 Võ Văn Ngân — địa chỉ demo trong lớp
PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")

step_no = 0


def step(title):
    global step_no
    step_no += 1
    print(f"\n[{step_no}] {title}")


def ok(message):
    print(f"    ✓ {message}")


class ApiError(Exception):
    def __init__(self, status, body):
        self.status, self.body = status, body
        errors = (body or {}).get("errors") or [{}]
        self.code = errors[0].get("code")
        super().__init__(f"HTTP {status} {self.code}: {(body or {}).get('message')}")


def api(method, path, token=None, body=None, files=None, expect=None):
    """Calls /api/v1 + path and returns `data` from the envelope (None for 204)."""
    url = f"{BASE}/api/v1{path}"
    headers = {"Accept": "application/json"}
    data = None
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if files is not None:
        boundary = uuid.uuid4().hex
        parts = []
        for name, value in files.items():
            if isinstance(value, bytes):
                parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"; filename="anh.png"\r\n'
                             f"Content-Type: image/png\r\n\r\n".encode() + value + b"\r\n")
            else:
                parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
        data = b"".join(parts) + f"--{boundary}--\r\n".encode()
        headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read()
            if expect and response.status != expect:
                raise AssertionError(f"{method} {path}: expected {expect}, got {response.status}")
            return json.loads(raw)["data"] if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            parsed = json.loads(raw) if raw else None
        except ValueError:
            parsed = {"message": raw[:300].decode(errors="replace")}
        raise ApiError(e.code, parsed) from None


def expect_error(code, fn):
    try:
        fn()
    except ApiError as e:
        assert e.code == code, f"expected {code}, got {e}"
        return e
    raise AssertionError(f"expected error {code}")


def login(phone, flavor, name):
    otp = api("POST", "/auth/otp/request", body={"phoneNumber": phone, "purpose": "LOGIN", "appFlavor": flavor})
    auth = api("POST", "/auth/otp/verify", body={"otpId": otp["otpId"], "code": OTP, "deviceId": f"smoke-{phone}", "appFlavor": flavor})
    if auth["needsProfileCompletion"]:
        api("PATCH", "/users/me", auth["accessToken"], {"fullName": name})
    return auth["accessToken"]


def consent(token, *purposes):
    """What the apps send after the user ticks the (unticked by default) consent boxes (Điều 9 Luật 91/2025)."""
    version = api("GET", "/legal")["policyVersion"]
    for purpose in purposes:
        api("POST", "/users/me/consents", token, {"purpose": purpose, "granted": True, "policyVersion": version})


def upload(token, purpose):
    return api("POST", "/files", token, files={"file": PNG, "purpose": purpose})["url"]


def iso(dt):
    return dt.astimezone(timezone.utc).isoformat()


class Admin:
    """Razor Pages admin: cookie login, then form posts with the antiforgery token of each page."""

    def __init__(self):
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def get(self, path):
        with self.opener.open(f"{BASE}{path}", timeout=30) as r:
            return r.geturl(), r.read().decode()

    def post(self, path, form, page=None):
        _, html = self.get(page or path.split("?")[0])
        token = re.search(r'name="__RequestVerificationToken" type="hidden" value="([^"]+)"', html)
        assert token, f"no antiforgery token on {page or path}"
        form = {**form, "__RequestVerificationToken": token.group(1)}
        request = urllib.request.Request(f"{BASE}{path}", data=urllib.parse.urlencode(form).encode(), method="POST")
        with self.opener.open(request, timeout=30) as r:
            html = r.read().decode()
            alert = re.search(r'class="alert"[^>]*>([^<]+)<', html)
            assert not alert, f"admin error on {path}: {htmllib.unescape(alert.group(1).strip())}"
            return r.geturl(), html

    def login(self):
        url, _ = self.post("/admin/login", {"Email": ADMIN_EMAIL, "Password": ADMIN_PASSWORD})
        assert "/admin/login" not in url.lower(), "admin login failed (check SeedAdmin)"


def main():
    started = time.time()
    suffix = datetime.now().strftime("%H%M%S")
    partner_phone, customer_phone = f"+8493{suffix}1", f"+8493{suffix}2"
    print(f"Servio demo smoke test → {BASE}\n  đối tác {partner_phone}, khách {customer_phone}")
    api("GET", "/service-categories")  # fails fast when the backend is down
    services = {c["slug"]: c["id"] for g in api("GET", "/service-categories") for c in g.get("children", [])}
    service_id = services["ve-sinh-may-lanh"]
    rules = api("GET", "/app-config")  # CS-18 numbers come from SystemConfigs
    assert (rules["partnerAcceptTimeoutMinutes"], rules["cancellationFeeAfterOnTheWay"]) == (10, 20_000), rules
    assert (rules["posting"]["windowHours"], rules["posting"]["maxLeadDays"], rules["disputeResponseHours"]) == (2, 30, 48), rules
    assert "{phone}" in rules["cancellationFeeBank"]["transferNoteTemplate"], rules
    admin = Admin()
    admin.login()
    for page in ("/chinh-sach-bao-mat", "/dieu-khoan-su-dung"):
        with urllib.request.urlopen(f"{BASE}{page}", timeout=30) as r:
            assert r.status == 200 and "Phiên bản" in r.read().decode(), f"trang {page} lỗi"

    # ---------- (1) đối tác đăng ký, nộp KYC, admin duyệt ----------
    step("Đối tác đăng ký, nộp KYC, admin duyệt")
    partner = login(partner_phone, "PARTNER", "Thợ Kiểm Thử")
    expect_error("CONSENT_REQUIRED", lambda: upload(partner, "KYC"))  # CCCD photos need their own consent first
    consent(partner, "TERMS_AND_PRIVACY", "KYC_IDENTITY", "LOCATION_SHARING")
    near = (UTE[0] + 0.01, UTE[1])
    api("PATCH", "/partners/me", partner, {"yearsOfExperience": 5, "serviceRadiusKm": 10, "anchorLatitude": near[0], "anchorLongitude": near[1]})
    for doc in ("ID_FRONT", "ID_BACK", "SELFIE_WITH_ID"):
        api("POST", "/partners/me/documents", partner, {"documentType": doc, "fileUrl": upload(partner, "KYC")})
    api("POST", "/partners/me/skills", partner, {"serviceCategoryId": service_id, "yearsOfExperience": 5})
    api("POST", "/partners/me/submit-verification", partner)
    partner_id = api("GET", "/partners/me", partner)["id"]
    admin.post(f"/admin/partners/verification/{partner_id}?handler=Approve", {"approveSkills": "true"})
    me = api("GET", "/partners/me", partner)
    assert me["verificationStatus"] == "APPROVED", me["verificationStatus"]
    ok("KYC đã duyệt")

    # ---------- (2) khách đăng bài có ảnh, đối tác thấy bài ----------
    step("Khách đăng bài có ảnh, đối tác thấy trong newsfeed")
    customer = login(customer_phone, "CUSTOMER", "Khách Kiểm Thử")
    consent(customer, "TERMS_AND_PRIVACY")
    address = api("POST", "/users/me/addresses", customer, {
        "label": "Trường", "receiverName": "Khách Kiểm Thử", "receiverPhone": customer_phone,
        "fullAddress": "1 Võ Văn Ngân, Linh Chiểu, Thủ Đức", "latitude": UTE[0], "longitude": UTE[1], "isDefault": True})

    def post(title):
        return api("POST", "/service-requests", customer, {
            "serviceCategoryId": service_id, "title": title,
            "description": "Máy lạnh Daikin 1.5HP dùng 3 năm, gần đây chảy nước và kêu to khi chạy.",
            "addressId": address["id"], "images": [{"url": upload(customer, "POST_IMAGE")}], "scheduleType": "NOW",
            "requireCertificate": False}, expect=201)

    request = post("Máy lạnh phòng ngủ chảy nước")
    api("POST", "/partners/me/online-status", partner, {"isOnline": True, "latitude": near[0], "longitude": near[1]})
    feed = api("GET", "/feed", partner)
    assert any(item["requestId"] == request["id"] for item in feed["items"]), "bài mới không có trong feed"
    ok(f"bài {request['code']} có trong feed")

    # ---------- (3) chat, báo giá 450.000, khách chọn ----------
    step("Đối tác báo giá 450.000 đ và chat, khách chọn báo giá")
    quote = api("POST", f"/service-requests/{request['id']}/quotes", partner, {
        "amount": 450_000, "estimatedDurationMinutes": 60,
        "availableFrom": iso(datetime.now(timezone.utc) + timedelta(minutes=30)), "requestRevision": 1}, expect=201)
    api("POST", f"/conversations/{quote['conversationId']}/messages", partner,
        {"clientMessageId": str(uuid.uuid4()), "type": "TEXT", "content": "Chào anh/chị, em qua kiểm tra trong 30 phút nhé."})
    quotes = api("GET", f"/service-requests/{request['id']}/quotes", customer)["items"]
    chosen = next(q for q in quotes if q["id"] == quote["id"])
    assert (chosen["partner"]["skillName"], chosen["partner"]["identityVerified"]) == ("Vệ sinh máy lạnh", True), chosen["partner"]
    order = api("POST", f"/service-requests/{request['id']}/accept-quotes", customer,
                {"quoteIds": [chosen["id"]], "quoteVersions": {chosen["id"]: chosen["rowVersion"]}, "paymentMethod": "CASH"}, expect=201)
    order_id, assignment_id = order["id"], order["assignments"][0]["id"]
    room = api("GET", f"/conversations/{quote['conversationId']}", customer)
    assert (room["orderCode"], room["price"]) == (order["code"], 450_000), room
    ok(f"đơn {order['code']} tạo, chờ đối tác xác nhận")

    # ---------- (4) xác nhận, di chuyển, khách thấy vị trí ----------
    step("Đối tác xác nhận và di chuyển, khách thấy vị trí")
    api("POST", f"/orders/{order_id}/assignments/{assignment_id}/accept", partner)
    api("POST", f"/orders/{order_id}/assignments/{assignment_id}/start-moving", partner,
        {"latitude": near[0], "longitude": near[1], "accuracy": 15})
    tracking = api("GET", f"/orders/{order_id}/tracking", customer)[0]
    assert tracking["location"] and tracking["etaMinutes"], tracking
    ok(f"khách thấy đối tác cách {tracking['distanceKm']} km, ETA {tracking['etaMinutes']} phút")

    # ---------- (5) đã đến bằng GPS ----------
    step("Đối tác check-in bằng GPS (xa quá bị chặn, đúng chỗ thì qua)")
    arrive_url = f"/orders/{order_id}/assignments/{assignment_id}/arrive"
    expect_error("TOO_FAR_FROM_ADDRESS", lambda: api("POST", arrive_url, partner, {
        "latitude": near[0], "longitude": near[1], "accuracy": 10, "capturedAt": iso(datetime.now(timezone.utc)), "isMock": False}))
    # GPS refused just now, so the partner may ask the customer (#178); the customer sees it in #63 even if they missed the push.
    ask = api("POST", f"/orders/{order_id}/assignments/{assignment_id}/arrival-confirmation-requests", partner, expect=201)
    pending = api("GET", f"/orders/{order_id}", customer)["assignments"][0]["pendingArrivalConfirmation"]
    assert pending and pending["requestId"] == ask["requestId"], pending
    arrived = api("POST", arrive_url, partner, {
        "latitude": UTE[0] + 0.0004, "longitude": UTE[1], "accuracy": 10, "capturedAt": iso(datetime.now(timezone.utc)), "isMock": False})
    ok(f"đã đến, cách địa chỉ {arrived['distanceMeters']} m")

    # ---------- (6) ảnh trước, phát sinh 180.000, ảnh sau, khách xác nhận ----------
    step("Ảnh trước → phát sinh 180.000 đ → ảnh sau → khách xác nhận đã trả 630.000 đ")
    api("POST", f"/orders/{order_id}/assignments/{assignment_id}/start-work", partner, {"beforeImages": [upload(partner, "ORDER_IMAGE")]})
    charge = api("POST", f"/orders/{order_id}/extra-charges", partner,
                 {"assignmentId": assignment_id, "description": "Thay tụ máy nén 35uF", "amount": 180_000}, expect=201)
    api("POST", f"/orders/{order_id}/extra-charges/{charge['id']}/respond", customer, {"approved": True})
    api("POST", f"/orders/{order_id}/assignments/{assignment_id}/complete", partner, {"afterImages": [upload(partner, "ORDER_IMAGE")]})
    detail = api("GET", f"/orders/{order_id}", customer)
    done = api("POST", f"/orders/{order_id}/confirm-completion", customer, {"expectedRowVersion": detail["rowVersion"]})
    assert (done["status"], done["totalAmount"]) == ("COMPLETED", 630_000), done
    ok("đơn COMPLETED, khách trả 630.000 đ")

    # ---------- (7) công nợ đối tác +94.500 ----------
    step("Công nợ đối tác tăng đúng 94.500 đ")
    wallet = api("GET", "/wallet/me", partner)
    assert wallet["debtBalance"] == 94_500, wallet
    ok("nợ hoa hồng 94.500 đ")

    # ---------- (8) hai bên đánh giá ----------
    step("Hai bên đánh giá")
    api("POST", f"/orders/{order_id}/reviews", customer,
        {"assignmentId": assignment_id, "rating": 5, "qualityRating": 5, "comment": "Thợ làm nhanh, gọn."}, expect=201)
    api("POST", f"/orders/{order_id}/reviews", partner, {"assignmentId": assignment_id, "rating": 5, "tags": ["PAID_ON_TIME"]}, expect=201)
    assert api("GET", f"/partners/{partner_id}", customer)["averageRating"] == 5
    ok("rating đối tác 5.0")

    # ---------- (9) đơn khác: khách huỷ khi đối tác đang di chuyển, phí 20.000 ----------
    step("Đơn thứ hai: khách huỷ khi đối tác đang di chuyển, phí 20.000 đ")
    request2 = post("Vệ sinh máy lạnh phòng khách")
    api("POST", "/partners/me/heartbeat", partner, {"latitude": near[0], "longitude": near[1]})
    quote2 = api("POST", f"/service-requests/{request2['id']}/quotes", partner, {
        "amount": 300_000, "estimatedDurationMinutes": 60,
        "availableFrom": iso(datetime.now(timezone.utc) + timedelta(minutes=40)), "requestRevision": 1}, expect=201)
    q2 = next(q for q in api("GET", f"/service-requests/{request2['id']}/quotes", customer)["items"] if q["id"] == quote2["id"])
    order2 = api("POST", f"/service-requests/{request2['id']}/accept-quotes", customer,
                 {"quoteIds": [q2["id"]], "quoteVersions": {q2["id"]: q2["rowVersion"]}, "paymentMethod": "CASH"}, expect=201)
    a2 = order2["assignments"][0]["id"]
    api("POST", f"/orders/{order2['id']}/assignments/{a2}/accept", partner)
    api("POST", f"/orders/{order2['id']}/assignments/{a2}/start-moving", partner, {"latitude": near[0], "longitude": near[1]})
    preview = api("GET", f"/orders/{order2['id']}/cancel-preview", customer)
    assert preview["fee"] == 20_000, preview
    cancelled = api("POST", f"/orders/{order2['id']}/cancel", customer, {"reason": "Tôi có việc đột xuất", "expectedRowVersion": preview["rowVersion"]})
    assert (cancelled["status"], cancelled["cancellationFee"]) == ("CANCELLED", 20_000), cancelled
    ok("đơn huỷ, khách nợ phí 20.000 đ")

    # ---------- (10) admin: xem đơn, xử lý khiếu nại, xác nhận trả nợ ----------
    step("Admin xem đơn, xử lý một khiếu nại và xác nhận một giao dịch trả nợ")
    _, html = admin.get(f"/admin/orders/{order_id}")
    assert order["code"] in html and "630.000" in html, "trang chi tiết đơn thiếu dữ liệu"
    dispute = api("POST", f"/orders/{order_id}/disputes", customer, {
        "reason": "WORK_QUALITY", "description": "Máy vẫn còn kêu nhẹ sau khi sửa, nhờ hỗ trợ kiểm tra.",
        "evidenceUrls": [upload(customer, "DISPUTE_EVIDENCE")]}, expect=201)
    admin.post(f"/admin/disputes/{dispute['id']}?handler=Resolve", {"upheld": "false", "note": "Đã gọi hai bên, máy chạy bình thường."})
    assert api("GET", f"/disputes/{dispute['id']}", customer)["status"] == "REJECTED"
    assert api("GET", f"/orders/{order_id}", customer)["status"] == "COMPLETED"
    ok(f"khiếu nại {dispute['code']} đã xử lý, đơn về COMPLETED")

    fee = api("GET", "/wallet/debts", customer)[0]
    payment = api("POST", "/wallet/debt-payments", customer, {
        "debtIds": [fee["id"]], "bankTransferReference": f"FT{suffix}", "evidenceUrl": upload(customer, "DISPUTE_EVIDENCE")}, expect=201)
    admin.post(f"/admin/debts?handler=Confirm&id={payment['transactionId']}&tab=Pending", {}, page="/admin/debts")
    assert api("GET", "/wallet/me", customer)["debtBalance"] == 0
    assert api("GET", "/wallet/me", partner)["debtBalance"] == 74_500  # 20.000 bù phí huỷ tự cấn trừ nợ (2.6.1)
    ok("khách hết nợ phí huỷ; đối tác được bù 20.000 đ, nợ còn 74.500 đ")

    # ---------- (11) #177: đối tác báo khách chưa trả tiền mặt ----------
    step("#177 Đối tác báo khách chưa thanh toán, admin xác nhận rồi ghi nhận khách đã trả")
    report = api("POST", f"/orders/{order_id}/assignments/{assignment_id}/report-unpaid", partner,
                 {"note": "Khách hẹn chuyển khoản sau nhưng chưa thấy tiền về."}, expect=201)
    admin.post(f"/admin/disputes/{report['id']}?handler=Resolve", {"upheld": "true", "note": "Khách xác nhận chưa trả."})
    assert api("GET", f"/orders/{order_id}", customer)["status"] == "AWAITING_PAYMENT"
    expect_error("UNPAID_ORDER", lambda: post("Bài mới khi còn đơn chưa trả"))
    admin.post(f"/admin/orders/{order_id}?handler=ConfirmCollected", {}, page=f"/admin/orders/{order_id}")
    assert api("GET", f"/orders/{order_id}", customer)["status"] == "COMPLETED"
    ok("đơn chờ thanh toán → khách trả → COMPLETED")

    # ---------- (12) quyền xoá tài khoản (Điều 14 Luật 91/2025) ----------
    step("Khách xoá tài khoản; số điện thoại đăng ký lại thành tài khoản mới")
    unread = api("GET", "/notifications/unread-count", customer)["total"]
    api("DELETE", "/users/me", customer)
    fresh = login(customer_phone, "CUSTOMER", "Khách Đăng Ký Lại")
    assert api("GET", "/orders/me", fresh)["totalCount"] == 0, "tài khoản mới không được thấy đơn cũ"
    ok("tài khoản cũ đã xoá, đăng ký lại không còn dữ liệu cũ")
    print(f"\nĐẠT cả 10 bước + #177 + quyền dữ liệu trong {time.time() - started:.1f} s (khách có {unread} thông báo trước khi xoá).")


if __name__ == "__main__":
    try:
        main()
    except (ApiError, AssertionError, urllib.error.URLError, KeyError, StopIteration) as e:
        print(f"\n✗ LỖI ở bước {step_no}: {type(e).__name__}: {e}")
        if isinstance(e, ApiError):
            print(json.dumps(e.body, ensure_ascii=False, indent=2))
        sys.exit(1)
