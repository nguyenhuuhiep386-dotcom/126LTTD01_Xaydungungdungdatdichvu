# Servio — Kế hoạch phát triển theo giai đoạn (từ 01/10/2026)

- **Mục đích:** dựng phiên bản nền (MVP kỹ thuật + demo chạy được) để push lên GitHub, sau đó nhóm chia việc theo **luồng của từng tác nhân** (Khách hàng, Đối tác, Admin).
- **Người thực hiện giai đoạn 0–4:** Claude dựng code, Hoàng review và push. Từ giai đoạn 5, mỗi thành viên làm luồng của mình.
- **Nguồn tra cứu:** `servio-ke-hoach-phat-trien-v1.4.md` (gọi tắt **v1.4**). Mã `F-…`, `CS-…`, `PS-…`, `AW-…`, `#…` tra theo tài liệu này. Phạm vi lấy theo **mục 0.2** của v1.4.
- **Stack:** theo bảng rút gọn v1.4 mục 0.2.2:
  - Backend: một project `Servio.Api` (.NET 10), kèm project kiểm thử `Servio.Tests`.
  - Trang quản trị: Razor Pages nằm trong `Servio.Api`.
  - Android: 3 module `:core`, `:customer`, `:partner`.
  - Các thư mục khung rỗng đang có (`servio-backend` 6 project, `servio-admin` React, `servio-android` 14 module) sẽ bị xoá và dựng lại.
- **Quan hệ với README.md:** README giữ lịch báo cáo của môn học. Tài liệu này thay phần việc dựng khung ở Giai đoạn 2 của README, gồm các việc: tạo solution, script SQL, API xác thực, tạo project Android và design system cơ bản. Các việc còn lại trong README được gom lại thành luồng ở mục 5.

## Tổng quan các giai đoạn

| GĐ | Tên | Thời gian | Đầu ra chính |
|---|---|---|---|
| 0 | Chốt phiên bản đầu tiên (MVP) | 01/10 | Danh sách phạm vi MVP và 3 bậc ưu tiên |
| 1 | Thiết kế kỹ thuật | 01–02/10 | Cấu trúc repo, kiến trúc 3 phần, hợp đồng API chung, quy ước code |
| 2 | Backend nền và API đầu tiên | 02–03/10 | CSDL 33 bảng, xác thực OTP/JWT, danh mục, Swagger, admin đăng nhập |
| 3 | Demo Android và trang quản trị, nối API mẫu | 03–04/10 | 2 APK có đủ khung điều hướng; luồng đăng nhập và danh mục gọi API thật |
| 4 | Đóng gói và push GitHub | 04/10 | Repo có `main`/`develop`, hướng dẫn cài đặt, thẻ Trello theo luồng |
| 5 | Phân công theo luồng tác nhân | 05/10 → 17/11 | Mỗi người làm luồng của mình theo mốc BC2, BC3, BC cuối |

---

## Giai đoạn 0 — Chốt phiên bản đầu tiên (MVP)

**MVP của đồ án** là phần đủ để chạy trọn **luồng demo 10 bước ở v1.4 mục 0.2.8** trên 2 điện thoại thật và 1 laptop. Mọi chức năng được xếp vào 3 bậc:

| Bậc | Ý nghĩa | Nội dung |
|---|---|---|
| **P0 — Lõi** | Thiếu thì không demo được | Xác thực OTP; đăng ký đối tác và KYC; admin duyệt KYC; đăng bài có ảnh; newsfeed realtime; chat; báo giá; chọn báo giá tạo đơn; state machine đơn; tracking vị trí; check-in hoặc khách xác nhận đã đến; ảnh trước/sau; phát sinh; hoàn tất COD; ghi công nợ hoa hồng |
| **P1 — Bắt buộc cho BC cuối** | Có trong 0.2.8 nhưng không chặn luồng chính | Huỷ đơn có phí; đánh giá hai chiều; khiếu nại và admin xử lý; trả nợ và admin xác nhận (AW-18); thông báo FCM; dashboard admin |
| **P2 — Nếu kịp** | Chỉ làm khi luồng 0.2.8 đã chạy trọn | #177 "Khách chưa thanh toán"; CS-02 Onboarding; PS-26; AW-15 cấu hình |

**Ngoài phạm vi:** toàn bộ phần không có trong v1.4 mục 0.2.3–0.2.6, ví dụ thanh toán online, ví nạp tiền, Instant Booking, nhiều đối tác trên một đơn, Google Maps, MongoDB, Redis.

**Việc cần làm:**
- [ ] Nhóm xác nhận 3 bậc trên (họp ngắn hoặc nhắn nhóm) — 01/10 (cả nhóm)

---

## Giai đoạn 1 — Thiết kế kỹ thuật

### 1.1. Cấu trúc repo (monorepo)

```
servio/
├── README.md                      # lịch báo cáo, phân công (giữ nguyên)
├── docs/
│   ├── servio-ke-hoach-phat-trien-v1.4.md
│   ├── ke-hoach-giai-doan.md      # tài liệu này
│   └── huong-dan-phat-trien.md    # cài đặt, chạy, quy ước, cách thêm một màn/endpoint
├── design/figma-servio-builder/   # plugin dựng Figma (đã có)
├── servio-backend/
│   ├── Servio.sln
│   ├── database/
│   │   ├── 01_schema.sql          # CREATE TABLE 33 bảng + khoá ngoại + index
│   │   └── 02_seed.sql            # danh mục, SystemConfigs, admin
│   ├── src/Servio.Api/
│   │   ├── Common/                # ApiResponse, ErrorCodes, ApiException, middleware
│   │   ├── Data/                  # ServioDbContext + Entities (scaffold Database-First)
│   │   ├── Controllers/           # mỗi module một controller: Auth, Users, Categories…
│   │   ├── Services/              # nghiệp vụ, chia thư mục theo module M1–M9
│   │   ├── Hubs/                  # FeedHub, OrderHub, ChatHub (thêm ở GĐ5)
│   │   ├── Jobs/                  # BackgroundService (thêm ở GĐ5)
│   │   ├── Pages/Admin/           # Razor Pages AW-xx
│   │   └── wwwroot/uploads/
│   └── tests/Servio.Tests/
└── servio-android/
    ├── settings.gradle.kts, gradle/libs.versions.toml
    ├── core/      # vn.servio.core: designsystem, network, auth (UI + data), utils
    ├── customer/  # vn.servio.customer: data/model, data/repository, ui/<feature>, utils
    └── partner/   # vn.servio.partner: cùng cấu trúc
```

### 1.2. Kiến trúc và luồng dữ liệu

| Thành phần | Thiết kế |
|---|---|
| Backend | ASP.NET Core .NET 10. Mỗi module một Controller, Controller gọi Service, Service dùng `ServioDbContext` (EF Core, Database-First). Không dùng MediatR và không thêm lớp Repository ở backend. |
| Xác thực API | Access token JWT 15 phút. Refresh token 30 ngày, lưu ở bảng `RefreshTokens` và đổi mới (rotate) mỗi lần refresh. Claim gồm `sub` (`Users.Id`), `roles`, `appFlavor`. Có 2 policy `Customer` và `Partner`. |
| Xác thực Admin | Cookie scheme riêng cho `AdminUsers`. Mật khẩu băm bằng `PasswordHasher` có sẵn trong ASP.NET Core. |
| Hợp đồng API | Đúng v1.4 mục 6.1: prefix `/api/v1`, mọi response bọc trong envelope `{ success, data, message, errors, meta }`, mã lỗi dạng `UPPER_SNAKE`. Bộ mã lỗi đặt trong `ErrorCodes.cs` và có bản sao tương ứng ở Android. |
| Realtime | Gồm 3 hub `/hubs/feed`, `/hubs/orders`, `/hubs/chat`, thêm vào ở GĐ5 theo luồng. Demo hiện tại chưa có hub. |
| Android | Theo MVVM: Fragment → ViewModel (StateFlow `UiState`) → Repository (interface) → `RemoteRepository` (Retrofit) hoặc `FakeRepository`. Hilt chọn implementation. Khi endpoint xong, chỉ đổi binding, không sửa UI. |
| Token trên Android | `TokenStore` trong `:core`. `AuthInterceptor` gắn Bearer token. `TokenAuthenticator` tự gọi #5 khi nhận 401 và retry một lần. Nếu refresh thất bại thì về màn nhập số điện thoại. |
| Cấu hình | Backend: `appsettings.Demo.json` chứa `Otp:FixedCode=123456`, connection string và khoá JWT lấy từ user-secrets hoặc biến môi trường. Android: `BASE_URL` đặt trong `local.properties` rồi đưa vào `BuildConfig`. Bản debug cho phép HTTP tới IP LAN qua `network_security_config`. |

### 1.3. Quy ước code và Git

- **Đặt tên:** theo v1.4 mục 0 ý 2. Tên bảng PascalCase số nhiều; khoá chính GUID sinh bằng `Guid.CreateVersion7()`. Path API dùng kebab-case, JSON dùng camelCase, enum truyền qua API là chuỗi `UPPER_SNAKE`. Tiền dùng kiểu `long` (VND), thời gian dùng ISO-8601 có offset.
- **Git:**
  - Nhánh `main` luôn chạy được; `develop` dùng để tích hợp.
  - Mỗi luồng tạo nhánh `feature/<mã-luồng>-<tên>`, ví dụ `feature/kh2-tao-yeu-cau`.
  - Gộp vào `develop` qua pull request, cần 1 người khác review.
  - Commit theo dạng `feat|fix|refactor|docs|test|chore: mô tả`.
- **Mỗi luồng là một thẻ Trello.** Thẻ ghi mã luồng, các màn, các API và hạn.

**Việc cần làm:**
- [x] Hoàng duyệt cấu trúc 1.1 và thiết kế 1.2 trước khi Claude bắt đầu code — 02/10 (Hoàng)

---

## Giai đoạn 2 — Backend nền và API đầu tiên (Claude dựng)

- [x] Viết `01_schema.sql` cho 33 bảng ở v1.4 mục 0.2.5:
  - Dùng đúng tên và kiểu ở mục 5.2, bỏ các cột ngoài phạm vi.
  - Có bảng `Messages` và 4 cột vị trí mới của `OrderAssignments`.
  - Có khoá ngoại, ràng buộc unique và index.
- [x] Viết `02_seed.sql`:
  - 7 danh mục cấp 1 kèm danh mục cấp 2 theo mục 1.2.
  - Các khoá `SystemConfigs` trong phạm vi.
  - 1 tài khoản `SUPER_ADMIN`.
- [x] Tạo `Servio.sln`, `Servio.Api` và `Servio.Tests` (xUnit). Scaffold EF Core từ CSDL vào `Data/`.
- [x] Phần chung:
  - Envelope `ApiResponse<T>`, `ApiException` và middleware đổi exception sang mã lỗi.
  - Kiểm tra dữ liệu đầu vào trả 400 kèm `errors[].field`.
  - Swagger có nút Authorize JWT.
  - `/health/live`.
- [x] API xác thực:
  - #1 `POST /auth/otp/request`: có rate limit; mã cố định ở Development/Demo.
  - #2 `POST /auth/otp/verify`: tự tạo `Users`, `UserRoles` và profile theo `appFlavor`.
  - #5 refresh, #6 logout, #9 `GET /users/me`, #10 `PATCH /users/me`.
- [x] API danh mục #29 và #30.
- [x] Admin Razor Pages:
  - AW-01 đăng nhập cookie.
  - Layout có sidebar 9 mục (mục nào chưa làm thì hiển thị "Đang phát triển").
  - AW-11 Danh mục, chỉ xem, dùng làm trang mẫu cho các trang sau.
- [x] Unit test mẫu cho OTP và JWT để nhóm theo cùng pattern.
- [x] Cấu hình chạy LAN: profile `Demo` lắng nghe `0.0.0.0:5080`.

**Hoàn thành khi:**
- `dotnet build` và `dotnet test` đều đạt.
- Chạy script trên SQL Server mới tạo được CSDL.
- Trên Swagger đăng nhập được bằng OTP `123456` rồi gọi `/users/me` thành công.
- Admin đăng nhập được và xem được danh sách danh mục.

---

## Giai đoạn 3 — Demo Android và nối API mẫu (Claude dựng)

- [x] Tạo Gradle project: version catalog, 3 module, Hilt, Retrofit + OkHttp + kotlinx.serialization, Navigation Component, ViewBinding. (Coil, osmdroid, SignalR, FCM thêm ở luồng cần dùng.)
- [x] Phần `:core/designsystem`:
  - Màu, font Be Vietnam Pro và kiểu chữ lấy từ biến và kiểu chữ trên trang Figma "Admin web và design system".
  - Theme và style cho nút, ô nhập, card, Bottom Navigation.
- [x] Phần `:core/network`:
  - Model envelope và lớp đổi mã lỗi sang câu tiếng Việt.
  - `AuthInterceptor` và `TokenAuthenticator`.
  - Header `X-Client-Platform`.
  - Lỗi mất mạng hiển thị thông báo, app không treo.
- [x] Phần `:core/auth`:
  - Các màn dùng chung CS-01/PS-01 Splash, CS-03 Nhập SĐT, CS-04 OTP, CS-05 Hoàn tất hồ sơ.
  - Các màn này **gọi API thật** #1, #2, #9, #10.
- [x] Hai app `:customer` và `:partner`:
  - Mỗi app có `MainActivity`, Bottom Navigation 5 tab và `nav_graph` theo v1.4 mục 7.6.
  - Mỗi màn trong phạm vi có một Fragment **giữ chỗ**: hiện mã màn, tên màn và các nút điều hướng đúng sơ đồ. App khách có 31 màn, app đối tác có 24 màn.
  - Mục đích: nhóm thay từng Fragment giữ chỗ bằng giao diện thật theo Figma, không phải tự dựng điều hướng.
- [x] Hai mẫu để nhóm làm theo:
  - **Mẫu API thật:** CS-06 Trang chủ tải danh mục từ #29.
  - **Mẫu dữ liệu giả:** CS-14 Yêu cầu của tôi (khách) và PS-07 Newsfeed (đối tác) dùng `FakeRepository` qua interface. Cách đổi sang repository thật được ghi trong `huong-dan-phat-trien.md`.
- [x] Cấu hình `BASE_URL` trong `local.properties`; package `vn.servio.customer` và `vn.servio.partner`.

**Hoàn thành khi:**
- `gradlew assembleDebug` tạo được 2 APK.
- Trên điện thoại thật cùng Wi-Fi với laptop, cả 2 app đăng nhập được bằng OTP `123456`.
- App khách hiện 7 danh mục lấy từ API.
- Bấm đi qua được toàn bộ màn giữ chỗ theo sơ đồ điều hướng.

---

## Giai đoạn 4 — Đóng gói và push GitHub

- [x] Viết `docs/huong-dan-phat-trien.md`:
  - Cài đặt môi trường; chạy script SQL; chạy backend.
  - Mở cổng trên tường lửa Windows; đặt `BASE_URL`.
  - Quy trình nhánh và pull request.
  - **Công thức thêm 1 endpoint** (Controller → Service → test → Swagger).
  - **Công thức làm 1 màn** (Fragment → ViewModel → Repository giả → Repository thật).
- [x] Viết `.gitignore` cho .NET, Android và Visual Studio. Không commit `local.properties`, `google-services.json`, khoá bí mật hoặc `wwwroot/uploads`.
- [x] `git init`, commit lên `main`, tạo nhánh `develop` (Claude làm ở máy). Remote là repo `nguyenhuuhiep386-dotcom/126LTTD01_Xaydungungdungdatdichvu`.
- [ ] Push `main` và `develop` lên GitHub. Chủ repo (Hiệp) thêm Hoàng và Nguyên làm collaborator, rồi bật bảo vệ nhánh `main` và `develop` (bắt buộc qua PR) — 04/10 (Hoàng, Hiệp)
- [ ] Tạo thẻ Trello theo các luồng ở mục 5 (Hiệp).
- [ ] Họp bàn giao 30 phút. Mỗi người clone repo, chạy được demo trên máy mình, rồi nhận luồng — tối 04/10 hoặc 05/10 (cả nhóm).

---

## Giai đoạn 5 — Phân công theo luồng tác nhân

Mỗi luồng là một nhánh `feature/…` kèm một thẻ Trello. Mỗi luồng có 2 hạn:
- **Hạn giao diện:** màn chạy bằng `FakeRepository`, hạn chung là **12/10** để kịp BC2.
- **Hạn nối API:** đổi sang repository thật. Hạn này lấy theo mốc trong README.

Backend làm endpoint theo thứ tự các luồng bên dưới. Hợp đồng (request, response, JSON mẫu) phải có trên Swagger **trước** hạn nối API ít nhất 3 ngày.

### 5.1. Khách hàng — Nguyên

| Luồng | Màn | API / Hub | Nối API xong |
|---|---|---|---|
| KH-1 Tài khoản và địa chỉ | CS-29, CS-32, CS-13, CS-12 (osmdroid) | #12–#16 | 19/10 |
| KH-2 Đăng yêu cầu | CS-06, CS-07, CS-08–CS-11 | #29, #30, `POST /files`, #36 | 23/10 |
| KH-3 Yêu cầu và báo giá | CS-14, CS-15, CS-16, CS-17, CS-18 | #37, #38, #40, #42, #43, #27, #28; hub `NewQuote` | 25/10 (CS-18 nối #43 trước 30/10) |
| KH-4 Chat (phía khách) | CS-27, CS-28, dùng giao diện chat chung | #54–#59, #61; `/hubs/chat` | 27/10 |
| KH-5 Đơn và theo dõi | CS-19, CS-20, CS-21; hộp thoại "Thợ đã đến?" | #62–#64, #76, #77, #179; `PartnerLocationUpdated` | 04/11 |
| KH-6 Phát sinh, hoàn tất, huỷ, đánh giá | CS-22, CS-23, CS-26; xem trước phí huỷ | #74, #71, #151, #72, #95, #96, #100 | 06/11 |
| KH-7 Thông báo, khiếu nại, công nợ, hỗ trợ | CS-31, CS-33, CS-30, CS-34; FCM | #101–#104, #146–#148, #87, #88, #155, #156 | 09/11 |

Nguyên phụ trách thêm phần hạ tầng dùng chung: client SignalR trong `:core/realtime` (tự kết nối lại và tải lại dữ liệu sau khi nối lại), cùng phần nhận FCM. Phần này cần xong trước KH-3, hạn 22/10.

### 5.2. Đối tác — Hiệp

| Luồng | Màn | API / Hub | Nối API xong |
|---|---|---|---|
| DT-1 Đăng ký và KYC | PS-02–PS-06 | #17–#23, `POST /files` (KYC) | 19/10 |
| DT-2 Newsfeed và báo giá | PS-07, PS-08, PS-10, PS-11, PS-12; bật/tắt Online | #25, #26, #46, #38, #50, #52, #53; hub `NewPost` | 25/10 |
| DT-3 Chat (giao diện chung + phía đối tác) | CS-27/CS-28 trong `:core`, PS-20 | #54–#59, #61; `/hubs/chat` | 27/10 |
| DT-4 Nhận đơn và di chuyển | PS-13, PS-14 (đếm ngược 10 phút), PS-15; foreground service vị trí | #62–#68, #178; hub `UpdateLocation` | 04/11 |
| DT-5 Thực hiện và hoàn tất | PS-16, PS-17, PS-18, PS-19; khách không có mặt; đối tác huỷ | #69, #70, #73, #180, #152 | 06/11 |
| DT-6 Thu nhập, hồ sơ, đánh giá, thông báo | PS-21, PS-23, PS-24, PS-27, PS-28 | #79, #87, #88, #155, #156, #95, #100, #101–#104 | 09/11 |

Giao diện chat dùng chung (DT-3) phải xong bằng dữ liệu giả trước **08/10** để Nguyên dùng cho KH-4.

### 5.3. Admin và Backend — Hoàng

| Luồng | Nội dung | Phục vụ luồng | Hạn |
|---|---|---|---|
| BE-1 Hồ sơ, đối tác, file | #12–#23, #25–#28, `POST /files` (magic bytes, thư mục KYC riêng) | KH-1, DT-1 | 17/10 — **xong 01/10** |
| QT-1 Duyệt và danh mục | AW-04 duyệt KYC và kỹ năng (ghi AuditLog), AW-11 thêm/sửa danh mục | DT-1 | 19/10 — **xong 01/10** |
| BE-2 Bài đăng, feed, báo giá | #36–#38, #40, #42, #46, #50, #52, #53; chặn SĐT/URL; hết hạn bài; `/hubs/feed`, `NewQuote` | KH-2, KH-3, DT-2 | 24/10 |
| BE-3 Chat | #54–#59, #61; `/hubs/chat`; hội thoại READ_ONLY | KH-4, DT-3 | 26/10 |
| BE-4 Tạo đơn và state machine | #43 (transaction, slot ≤ 3, chồng lịch), #62–#66, #69, #70, #73, #74; job hết hạn xác nhận; unit test | KH-5, DT-4 | 02/11 |
| BE-5 Tracking và check-in | `UpdateLocation`, #67, #68, #76, #77, #178, #179 | KH-5, DT-4 | 04/11 |
| BE-6 COD, huỷ, công nợ | #71 + auto-confirm, #72, #151, #152, #180, #155, #156; unit test công thức tiền 2.6.1 | KH-6, DT-5 | 06/11 |
| BE-7 Đánh giá, khiếu nại, thông báo | #95, #96, #100, #146–#148, #101–#104, FCM | KH-6, KH-7, DT-6 | 08/11 |
| QT-2 Các trang vận hành | AW-02, AW-03, AW-06, AW-07, AW-08, AW-18 | Luồng demo bước 10 | 09/11 |

### 5.4. Mốc kiểm tra chung

| Ngày | Mốc | Điều kiện đạt |
|---|---|---|
| 12/10 | Chuẩn bị BC2 | 2 app chạy đủ các flow màn hình bằng dữ liệu giả; tạo được APK |
| 13/10 | **BC2** | Trình bày Figma, demo flow màn hình, slide, Trello |
| 27/10 | Demo nội bộ | Chạy được trên 2 máy thật: đăng ký, KYC, đăng bài, feed, chat, báo giá |
| 10/11 | **BC3** | Đủ giao diện; luồng đơn hàng đã nối backend |
| 15/11 | Code freeze | Từ ngày này chỉ sửa lỗi chặn luồng |
| 17/11 | **BC cuối** | Chạy trọn luồng 10 bước ở v1.4 mục 0.2.8 |

**Định nghĩa "xong" cho mỗi luồng:**
- Màn khớp với Figma và dùng repository thật.
- Có xử lý trạng thái đang tải, rỗng, lỗi và mất mạng.
- Pull request được 1 người review và gộp vào `develop`.
- Luồng chạy được trên điện thoại thật.
- Thẻ Trello đã chuyển sang cột Xong.

---

## Rủi ro chính

| Rủi ro | Cách giảm |
|---|---|
| Backend một người làm, Android phải chờ endpoint | Hợp đồng chốt trên Swagger trước 3 ngày; Android dùng `FakeRepository` cho tới khi có endpoint thật |
| Demo do Claude dựng có chỗ nhóm chưa hiểu | Họp bàn giao; `huong-dan-phat-trien.md` có công thức từng bước; mỗi người tự chạy demo trước khi nhận luồng |
| Xung đột merge ở `nav_graph` và `:core` | Khung điều hướng đã đủ từ demo; luồng nào sửa `:core` thì PR riêng và nhỏ |
| Mạng trường chặn thiết bị gọi nhau | Dùng hotspot riêng; thử trước tại phòng học |
