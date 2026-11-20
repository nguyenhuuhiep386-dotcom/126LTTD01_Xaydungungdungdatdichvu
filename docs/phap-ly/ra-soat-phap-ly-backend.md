# Rà soát pháp lý: Servio — backend và trang quản trị

> 🤖 Báo cáo do AI tạo từ mã nguồn, chỉ để tham khảo, không phải ý kiến pháp lý. Phần lớn mục 🔴 là chỉnh sửa nhỏ trong code, làm được trong vài giờ, không cần quá lo. Việc gì quan trọng thì hỏi lại người làm luật.

**Ngày audit:** 16/11/2026 · **Công cụ:** skill [`vn-law-check`](https://github.com/mhfed/vn-law-check) v1.7.0 (dữ liệu luật cập nhật 03/11/2026)
**Phạm vi:** `servio-backend` (ASP.NET Core API, hub SignalR, trang quản trị Razor Pages, CSDL) — phần của Hoàng. Hai app Android sẽ rà ở lượt sau, khi các thành viên làm xong (mục "Lượt rà soát tiếp theo").
**Kết quả quét:** mức 3, nền tảng trung gian. `scan.sh` chỉ quét đuôi file web/mobile (bỏ qua `.cs`, `.cshtml`) nên gợi ý mức 2; xem lại bằng tay: có tài khoản (OTP, `Services/Auth/AuthService.cs:68`), đối tác bên thứ ba cung cấp dịch vụ cho khách, thu ảnh CCCD (`Controllers/PartnersController.cs:30`), theo dõi vị trí (`Hubs/Realtime.cs:74`), tiền và công nợ, gửi thông báo qua Google FCM (`Services/Notifications/FcmPushSender.cs:45`). Không có tracking, quảng cáo hay AI.
**Phạm vi checklist:** 01 Bảo vệ dữ liệu cá nhân, 02 Thương mại điện tử, 03 An ninh mạng, 04 Bảo vệ người tiêu dùng, 06 Giao dịch điện tử, 08 Mức phạt.
**Điểm làm tốt:** ảnh CCCD lưu riêng, chỉ SUPER_ADMIN/OPERATOR xem và mỗi lần xem ghi AuditLog (`Services/Admin/PartnerVerificationService.cs:176`); số điện thoại và địa chỉ chính xác chỉ hiện cho bên kia từ khi đối tác nhận đơn (`Services/Orders/OrderService.cs:175-186`).

## Tổng quan

| Lĩnh vực | 🔴 | 🟡 | 🟢 |
|---|---|---|---|
| Bảo vệ dữ liệu cá nhân | 3 (backend đã sửa; app còn 1) | 6 | 4 |
| Thương mại điện tử | 0 | 2 | 2 |
| An ninh mạng | 0 | 1 | 1 |
| Bảo vệ người tiêu dùng | 0 | 0 | 3 |
| Giao dịch điện tử | 0 | 0 | 1 |

**Không áp dụng:** quảng cáo (05: không có nội dung quảng cáo, KOL, affiliate); minh bạch AI (07: không có chatbot, nội dung AI); livestream, nền tảng nước ngoài (02 mục E, G). Chủ thể hiện là nhóm đồ án, chưa kinh doanh thật: các nghĩa vụ giấy tờ chỉ xếp 🟡.

## 🔴 Cần xử lý ngay

- **01-B. Không lưu bằng chứng đồng ý, không xin đồng ý riêng cho dữ liệu nhạy cảm** — ✅ **đã sửa trong backend (16/11)**.
  Trước khi sửa: backend thu SĐT, hồ sơ, ảnh CCCD, vị trí mà không có bản ghi ai đồng ý, lúc nào, với phiên bản chính sách nào, cho mục đích nào. Căn cứ: Điều 9, khoản 2 Điều 10 Luật 91/2025/QH15; khoản 1, 2, 3, 4 Điều 6 NĐ 356/2025/NĐ-CP.
  Đã làm: bảng `UserConsents` chỉ ghi thêm (`database/01_schema.sql`, nâng cấp CSDL cũ bằng `database/03_privacy.sql`); `GET /api/v1/legal`; `GET|POST /api/v1/users/me/consents` (đồng ý hoặc rút lại, lưu IP, User-Agent, phiên bản); kiểm tra đồng ý ngay chỗ thu dữ liệu: đăng bài (`TERMS_AND_PRIVACY`), tải và nộp ảnh CCCD (`KYC_IDENTITY`, thông báo ghi rõ là dữ liệu nhạy cảm), bật Online, di chuyển, gửi vị trí (`LOCATION_SHARING`). Thiếu thì trả 422 `CONSENT_REQUIRED`. Tham số `privacy.require_consent` (AW-15) chỉ để tạm tắt khi app chưa có màn hình đồng ý.

- **01-B. App: câu "tiếp tục là đồng ý"** — ⏳ **chưa sửa, thuộc app (`:core`, Nguyên phụ trách)**.
  `servio-android/core/src/main/res/values/strings.xml:14`: "Bằng việc tiếp tục, bạn đồng ý với Điều khoản sử dụng và Chính sách bảo mật của Servio." Căn cứ: điểm d khoản 4 Điều 9 Luật 91/2025 (im lặng, không phản hồi không là đồng ý); khoản 3 Điều 6 NĐ 356/2025.

  Prompt sửa:
  ```text
  Ở servio-android/core/src/main/res/values/strings.xml:14 có câu coi việc tiếp tục dùng app là đồng ý: "Bằng việc tiếp tục, bạn đồng ý với Điều khoản sử dụng và Chính sách bảo mật của Servio.". Xóa câu này. Ở màn nhập số điện thoại (fragment_phone_input.xml) thêm một ô đồng ý KHÔNG tích sẵn kèm link mở BASE_URL + "chinh-sach-bao-mat" và BASE_URL + "dieu-khoan-su-dung"; chưa tích thì nút Tiếp tục bị khoá. Sau khi xác thực OTP thành công, gọi GET /api/v1/legal lấy policyVersion rồi POST /api/v1/users/me/consents {purpose: "TERMS_AND_PRIVACY", granted: true, policyVersion}. Không sửa chỗ khác.
  ```

- **01-A / 09-C. Không có cách xoá tài khoản và dữ liệu** — ✅ **đã sửa trong backend (16/11)**.
  Căn cứ: Điều 14 Luật 91/2025; khoản 4 Điều 5 NĐ 356/2025 (xoá trong 20 ngày).
  Đã làm: `DELETE /api/v1/users/me` có hiệu lực ngay. Bị từ chối (409 `ACCOUNT_DELETION_BLOCKED`, nói rõ lý do) khi còn đơn chưa xong, yêu cầu đang mở, báo giá đang chờ, nợ hoặc số dư chưa thanh toán (khoản 2 Điều 14). Khi xoá: ẩn danh hồ sơ và địa chỉ, giải phóng số điện thoại, thu hồi phiên, xoá thiết bị, thông báo, OTP, xoá file ảnh CCCD, chứng chỉ, ảnh đại diện, xoá vị trí; giữ đơn, giao dịch, đánh giá, khiếu nại dưới tên "Người dùng đã xoá"; thêm bản ghi rút đồng ý.

- **01-A. Thiếu trang chính sách bảo vệ dữ liệu cá nhân và điều khoản** — ✅ **đã có bản nháp (16/11)**.
  Căn cứ: khoản 2 Điều 9 Luật 91/2025; khoản 1 Điều 11 Luật 122/2025/QH15.
  Đã làm: `/chinh-sach-bao-mat` và `/dieu-khoan-su-dung` (Razor Pages công khai, không cookie, không script). Phí, hoa hồng, thời hạn đọc trực tiếp từ SystemConfigs. **Còn các chỗ `[ĐIỀN: ...]` và `[KIỂM TRA: ...]`** (xem cuối báo cáo).

## 🟡 Nên cải thiện

1. **01-B. Rút đồng ý ảnh CCCD sau khi đã được duyệt:** backend ghi nhận và chặn nộp lại, nhưng ảnh cũ vẫn giữ tới khi xoá tài khoản. Nên quy định rõ: rút đồng ý thì tài khoản đối tác dừng nhận việc và ảnh bị xoá. Căn cứ: Điều 10 Luật 91/2025.
2. **01-F. Vị trí đối tác khi tắt app mà không bấm Offline:** vị trí dùng để ghép việc chỉ bị xoá khi bấm Offline (`Services/Partners/PartnerProfileService.cs`, đã sửa 16/11); tắt app ngang thì còn lưu đến lần sau. Nên xoá cả khi hết hạn heartbeat.
3. **01-A. Thời hạn lưu trữ** chưa được quy định (bảng `OtpCodes`, `Notifications`, hồ sơ giao dịch). Chính sách đang để `[ĐIỀN]`.
4. **01-E. Quy trình báo sự cố 72 giờ:** chưa có quy trình nội bộ; với dữ liệu vị trí phải báo cả người dùng trong 72 giờ (khoản 1 Điều 23 Luật 91/2025; điểm a khoản 1 Điều 29 NĐ 356/2025).
5. **01-D. Chuyển dữ liệu xuyên biên giới:** thông báo đẩy đi qua Google Firebase (máy chủ ở nước ngoài: mã thiết bị và nội dung thông báo). Về lý thuyết thuộc diện lập hồ sơ đánh giá tác động chuyển dữ liệu xuyên biên giới (khoản 2 Điều 20 Luật 91/2025). Nên hỏi người làm luật khi triển khai thật; có thể giảm rủi ro bằng cách chỉ gửi nội dung chung chung trong push.
6. **01-E. DPIA, nhân sự bảo vệ dữ liệu:** nếu triển khai thật, đơn vị vận hành trực tiếp xử lý dữ liệu nhạy cảm (ảnh CCCD) nên **không** thuộc diện miễn của khoản 3 Điều 38 Luật 91/2025: cần hồ sơ đánh giá tác động (Điều 21) và nhân sự bảo vệ dữ liệu (khoản 2 Điều 33). Nên hỏi người làm luật.
7. **02-B. Đăng ký nền tảng trung gian:** Servio cho đối tác bên thứ ba cung cấp dịch vụ, nên khi kinh doanh thật phải là pháp nhân và đăng ký trước khi hoạt động (khoản 2 Điều 14 Luật 122/2025). Nên hỏi người làm luật.
8. **02-C. Thông tin chủ quản** (tên, mã số doanh nghiệp, địa chỉ, email, điện thoại) chưa có; hai trang đang để `[ĐIỀN]` (Điều 11 Luật 122/2025).
9. **03-B. Nhật ký hệ thống 12 tháng:** backend chỉ ghi log ra console; AuditLogs chỉ có thao tác admin. Khi triển khai thật cần lưu log tối thiểu 12 tháng (điểm c khoản 6 Điều 16 NĐ 333/2026/NĐ-CP).

## 🟢 Đạt yêu cầu

- **01-C:** API, trang quản trị và hai trang pháp lý không gắn tracking hay quảng cáo; cookie duy nhất là cookie đăng nhập của admin.
- **01-F:** không có mua bán dữ liệu; vị trí theo thời gian thực chỉ lưu khi đối tác đang di chuyển và nay bị xoá khi chuyến đi kết thúc (`Services/Orders/OrderWorkflow.cs`, đã sửa 16/11), chỉ giữ điểm check-in làm bằng chứng.
- **01 (bảo mật):** file KYC, chứng chỉ, bằng chứng khiếu nại lưu riêng (`Services/Files/FileService.cs:37`); phân quyền admin theo vai trò, AuditLog cho mọi thao tác admin.
- **02-D (Điều 17 Luật 122/2025):** đối tác được xác minh danh tính (KYC) trước khi nhận việc; khiếu nại có người xử lý (AW-08), không chỉ tự động.
- **04-A, 04-B:** giá báo hiển thị trước khi chọn; phát sinh chỉ tính khi khách duyệt (`Controllers/OrdersController.cs:174`); phí huỷ hiện trước khi xác nhận huỷ (`Controllers/OrdersController.cs:131`); không có phí ẩn.
- **04-D (Điều 31 Luật 19/2023):** khiếu nại được xác nhận ngay khi gửi, hạn xử lý 48 giờ, kết quả báo cả hai bên.
- **03-C, 06-A, 06-D:** chấp thuận đơn rõ ràng (chọn báo giá kèm phiên bản, xác nhận hoàn thành); lưu lịch sử trạng thái, giao dịch làm chứng cứ.

## Mức phạt tham khảo

Chỉ nêu cho mục 🔴 có bằng chứng, theo checklist 08. Đây là mức tối đa theo quy định tham khảo được, không phải mức Servio sẽ bị phạt.

| Mục 🔴 | Hành vi | Tổ chức | Cá nhân, hộ KD | Căn cứ |
|---|---|---|---|---|
| Đồng ý | Xử lý dữ liệu khi chưa có đồng ý; không lưu bằng chứng đồng ý; không báo khi xử lý dữ liệu nhạy cảm | 30–50 triệu | 15–25 triệu | Điều 43 khoản 1 NĐ 330/2026/NĐ-CP |
| "Tiếp tục là đồng ý" | Coi im lặng là đồng ý | 50–70 triệu | 25–35 triệu | Điều 43 khoản 2 NĐ 330/2026/NĐ-CP |
| Xoá dữ liệu | Không đáp ứng yêu cầu xoá | CHƯA XÁC MINH | CHƯA XÁC MINH | Điều 51 NĐ 330/2026/NĐ-CP |

## Bảng dữ liệu (căn cứ soạn chính sách)

| Dữ liệu | Thu ở đâu | Mục đích | Gửi cho ai | Lưu ở đâu |
|---|---|---|---|---|
| SĐT, mã OTP (băm) | `POST /auth/otp/request`, `/verify` (`AuthService.cs:68`) | Xác thực | — | SQL Server |
| Họ tên, ảnh đại diện, email, ngày sinh, giới tính | `PATCH /users/me` | Hồ sơ, hiển thị cho bên kia | Bên kia của đơn | SQL Server, `wwwroot/uploads` |
| Mã thiết bị, FCM token, phiên bản app | `/auth/otp/verify`, `POST /users/me/devices` | Phiên đăng nhập, thông báo | Google (FCM) | SQL Server; Google ở nước ngoài |
| Địa chỉ, toạ độ, mô tả, ảnh công việc | `/users/me/addresses`, `POST /service-requests` | Báo giá, đến đúng nơi | Đối tác (khu vực trước, chính xác sau khi nhận) | SQL Server |
| Ảnh CCCD, chân dung cầm CCCD, chứng chỉ (nhạy cảm) | `POST /files` (KYC), `/partners/me/documents` | Xác minh đối tác | Admin SUPER_ADMIN/OPERATOR | Thư mục riêng `App_Data/private-uploads` |
| Vị trí đối tác | `/partners/me/online-status`, heartbeat, `start-moving`, hub `UpdateLocation`, `arrive` | Ghép việc, khách theo dõi, check-in | Khách của đơn | SQL Server |
| Chat, ảnh chat, báo giá, đơn, ảnh trước/sau, đánh giá, khiếu nại | Các API M2–M7 | Thực hiện đơn, giải quyết khiếu nại | Bên kia của đơn, admin | SQL Server |
| Giao dịch, công nợ, mã chuyển khoản, ảnh chứng từ | `#71`, `#156` | Hoa hồng, phí huỷ | Admin FINANCE | SQL Server, thư mục riêng |
| Bản ghi đồng ý (IP, User-Agent, phiên bản) | `POST /users/me/consents` | Bằng chứng đồng ý | — | SQL Server |

## Việc nhóm cần tự hoàn thiện

- **`[ĐIỀN]` trong hai trang:** tên đơn vị vận hành, mã số doanh nghiệp, địa chỉ, email tiếp nhận yêu cầu về dữ liệu cá nhân, email và số điện thoại hỗ trợ, thời hạn lưu hồ sơ giao dịch.
- **`[KIỂM TRA]`:**
  - Nơi đặt máy chủ khi triển khai.
  - App có SDK thống kê hoặc quảng cáo nào không.
- **Sau khi sửa nội dung:** nhờ người làm luật xem lại, rồi tăng `privacy.policy_version` ở AW-15 để app hỏi lại sự đồng ý.
- **App phải gọi API đồng ý trước khi demo:** sau khi merge nhánh này, `privacy.require_consent=true` sẽ chặn đăng bài, KYC và gửi vị trí cho tới khi app gọi `POST /users/me/consents`. Nếu app chưa kịp thêm màn hình đồng ý, tạm đặt `false` ở AW-15 cho buổi demo rồi bật lại sau.

## Lượt rà soát tiếp theo (khi các thành viên làm xong)

> Đã rà ngày 20/11/2026: xem [ra-soat-phap-ly-luot-3.md](ra-soat-phap-ly-luot-3.md).

Chạy lại skill `vn-law-check` trên toàn repo, thêm checklist 09-mobile-app cho hai app, và kiểm tra:

1. Màn đăng nhập: bỏ câu "tiếp tục là đồng ý", có ô đồng ý không tích sẵn kèm link hai trang, gọi `POST /users/me/consents` (`TERMS_AND_PRIVACY`).
2. App đối tác:
   - Ô đồng ý riêng, ghi rõ "dữ liệu nhạy cảm", trước khi chụp hoặc tải ảnh CCCD (`KYC_IDENTITY`).
   - Ô đồng ý chia sẻ vị trí trước khi bật Online (`LOCATION_SHARING`).
   - Xin quyền vị trí đúng lúc, có lý do; nói rõ khi nào vị trí được chia sẻ cho khách.
   - Foreground service dừng khi kết thúc chuyến.
3. Cả hai app:
   - Nút "Xoá tài khoản" gọi `DELETE /users/me`, hiện lý do khi bị từ chối.
   - Link hai trang pháp lý trong mục Tài khoản.
   - Cho rút đồng ý trong Cài đặt.
4. Quyền app xin trong `AndroidManifest.xml` chỉ gồm những gì tính năng cần. Trên `develop` hiện có: vị trí chính xác và gần đúng, Internet, trạng thái mạng. Khi app đối tác thêm foreground service vị trí (có thể cả vị trí chạy nền), camera hoặc thông báo, mỗi quyền phải có lý do rõ ràng.
5. Không có SDK thống kê hoặc quảng cáo chạy trước khi đồng ý. App khách đang dùng osmdroid tải ảnh bản đồ từ máy chủ OpenStreetMap (`customer/src/main/java/vn/servio/customer/ServioCustomerApp.kt`), nên giữ dòng OpenStreetMap ở mục 4 của chính sách và bỏ ghi chú `[KIỂM TRA]`.
6. Đổi `privacy.require_consent` về `true` nếu đã tạm tắt khi demo.

**Ngoài phạm vi, nên kiểm tra riêng:** trang quản trị tải font từ Google Fonts (`Pages/Shared/_AdminLayout.cshtml:26`), lộ IP của admin cho Google.

## Giới hạn & khuyến nghị

> Báo cáo này do AI tạo dựa trên bộ checklist tổng hợp từ văn bản pháp luật công khai, **không phải ý kiến tư vấn pháp lý chính thức**. Khung pháp lý Việt Nam về dữ liệu cá nhân, TMĐT và an ninh mạng vừa thay đổi lớn trong 2025-2026. Trước khi quyết định, hãy xác minh lại từng văn bản tại thuvienphapluat.vn/vbpl.vn và tham vấn luật sư.
