# Kế hoạch thực hiện: Servio — Ứng dụng đặt dịch vụ sửa chữa & chăm sóc tại nhà

- **Bối cảnh:** đồ án môn Lập trình di động. Hai app Android dùng chung backend: **Servio** cho khách hàng đăng yêu cầu, **ServioPartner** cho đối tác xem newsfeed, báo giá và nhận việc. Ngoài ra có trang quản trị web.
- **Hạn chót cuối cùng:** 17/11/2026 (BC cuối: chạy app hoàn chỉnh)
- **Đầu ra cuối:**
  - 2 app Android chạy trên điện thoại thật.
  - Backend ASP.NET Core (.NET 10) và CSDL SQL Server.
  - Trang quản trị web (Razor Pages).
  - Slide và bảng Trello cho từng buổi báo cáo.
- **Thành viên & vai trò:**
  - Hà Cảnh Minh Hoàng (2415053122219) – backend ASP.NET Core, CSDL SQL Server, SignalR/FCM phía server, trang quản trị web.
  - Trần Đình Nguyên (2415053122225) – app Servio (khách hàng); phần Android dùng chung: network, lưu token, SignalR client, màn xác thực.
  - Nguyễn Hữu Hiệp (2415053122218) – app ServioPartner (đối tác); design system và giao diện chat dùng chung; báo cáo, slide, Trello.
- **Tài liệu đặc tả:** `servio-ke-hoach-phat-trien-v1.4.md`. **Mục 0.2** là phạm vi đồ án: chức năng, API, bảng, màn hình và tiêu chí nghiệm thu. Mã `F-…`, `CS-…`, `PS-…`, `AW-…`, `#…` dưới đây tra theo tài liệu này.

**Các mốc báo cáo của môn học** (đều vào thứ Ba):

| Mốc | Ngày | Yêu cầu |
|---|---|---|
| BC1 | 29/09 | Giới thiệu nhóm, đề tài, mô tả nội dung xây dựng; slide + Trello phân công |
| BC2 | 13/10 | Danh sách màn hình: sử dụng Figma để thiết kế, demo chạy thử các flow màn hình; slide + Trello |
| BC3 | 10/11 | Chạy app phần giao diện; Trello |
| BC cuối | 17/11 | Chạy app hoàn chỉnh (2 app + trang quản trị) |

**Quy ước làm việc:**
- Mỗi việc trong checklist là một thẻ Trello. Mỗi người tự cập nhật thẻ của mình. Hiệp rà bảng trước mỗi buổi báo cáo.
- Git: nhánh `main` luôn chạy được, `develop` để tích hợp, mỗi việc một nhánh `feature/<tên-việc>`. Merge qua pull request, cần một thành viên khác xem qua.
- Hợp đồng API chốt trên Swagger trước khi Android nối. Khi endpoint chưa có, Android dùng `FakeRepository` trả dữ liệu mẫu, sau đó thay bằng repository thật mà không sửa giao diện.
- Tên biến, hàm, lớp và comment trong code bằng tiếng Anh. Kotlin theo convention MVVM trong Mau.md mục 3.1.
- Đề xuất họp ngắn tối thứ Hai trước mỗi buổi báo cáo để rà Trello và chốt phần demo.

## Giai đoạn 1: Khởi động và chốt đề tài (hạn: 29/09 — BC1)
**Mục tiêu:** chốt phạm vi đồ án, phân công, chuẩn bị môi trường và trình bày đề tài ở BC1.
**Việc cần làm:**
- [ ] Đọc mục 0.1–0.2 bản v1.4, thống nhất phạm vi, stack và phân công — hạn 26/09 (cả nhóm)
- [ ] Tạo bảng Trello gồm các cột Backlog / Đang làm / Chờ review / Xong; nhập việc của Giai đoạn 1–2 từ README này — hạn 27/09 (Hiệp)
- [ ] Tạo repo GitHub gồm `servio-backend/`, `servio-android/`, `docs/` (chứa v1.4 và README), nhánh `main`/`develop`, `.gitignore` cho .NET và Android — hạn 27/09 (Hoàng)
- [ ] Cài môi trường: Android Studio, .NET 10 SDK, SQL Server 2022 Developer/Express + SSMS, Git — hạn 27/09 (mỗi người)
- [ ] Tạo Firebase project (gói Spark) dùng cho FCM; đăng ký 2 app `vn.servio.customer` và `vn.servio.partner`; tải `google-services.json` — hạn 28/09 (Nguyên)
- [ ] Chuẩn bị nội dung kỹ thuật cho slide BC1:
  - Kiến trúc tổng thể và stack rút gọn (Hoàng).
  - Luồng người dùng của 2 app (Nguyên).
- [ ] Soạn slide BC1 — hạn 28/09 (Hiệp). Nội dung:
  - Giới thiệu nhóm.
  - Bài toán và mô hình Post & Quote.
  - Tác nhân và danh sách chức năng theo module (mục 0.2.3).
  - Công nghệ.
  - Các mốc thực hiện.
- [ ] Tập thuyết trình BC1 — 28/09 (cả nhóm)
**Đầu ra:** slide BC1 (.pptx), bảng Trello đã phân công, repo GitHub, README.md và bản v1.4 trong repo.
**Rủi ro / ghi chú:**
- Chỉ có 4 ngày. Slide lấy nội dung từ v1.4 mục 1, 2 và 0.2, chưa cần thiết kế màn hình.
- Nếu giảng viên có mẫu slide riêng thì dùng mẫu đó.

## Giai đoạn 2: Thiết kế màn hình, CSDL và khung dự án (hạn: 13/10 — BC2)
**Mục tiêu:** 2 app chạy được toàn bộ flow màn hình bằng dữ liệu giả; CSDL và API xác thực chạy được trên laptop.
**Việc cần làm:**
- [ ] Viết script SQL `CREATE TABLE` cho 33 bảng thuộc phạm vi (mục 0.2.5) gồm khóa chính và khóa ngoại; chạy trên SSMS và dựng Database Diagram — hạn 03/10 (Hoàng)
- [ ] Tạo solution .NET 10 `Servio.Api` + `Servio.Tests` — hạn 05/10 (Hoàng):
  - Scaffold EF Core từ CSDL (Database-First).
  - Swagger; envelope response và mã lỗi theo mục 6.1.
  - Cấu hình `appsettings.Demo.json`.
- [ ] Làm API xác thực #1, #2 (OTP cố định `123456` ở Development/Demo), #5, #6, #9, #10; JWT access 15 phút, refresh 30 ngày — hạn 08/10 (Hoàng)
- [ ] Làm API danh mục #29, #30; seed 7 danh mục cấp 1 kèm danh mục cấp 2, SystemConfigs và 1 tài khoản admin — hạn 09/10 (Hoàng)
- [ ] Trang quản trị: đăng nhập cookie (AW-01) và layout chung — hạn 11/10 (Hoàng)
- [ ] Chốt hợp đồng API của Giai đoạn 3 trên Swagger, có JSON mẫu, để Android làm song song — hạn 12/10 (Hoàng)
- [ ] Tạo project `servio-android` gồm 3 module `:core`, `:customer`, `:partner`; cài Hilt, Retrofit, Navigation Component, ViewBinding và osmdroid — hạn 02/10 (Nguyên)
- [ ] Design system trong `:core`, theo mục 7.1 — hạn 03/10 (Hiệp):
  - Màu và font Be Vietnam Pro.
  - Style nút, ô nhập, card và Bottom Navigation.
- [ ] Làm các màn xác thực dùng chung CS-01, CS-03, CS-04, CS-05 bằng dữ liệu giả — hạn 05/10 (Nguyên)
- [ ] Làm giao diện chat dùng chung CS-27, CS-28 bằng dữ liệu giả — hạn 07/10 (Hiệp)
- [ ] App khách: làm khung các màn trong phạm vi bằng `FakeRepository`, chạy đủ flow — hạn 12/10 (Nguyên). Gồm:
  - CS-06 Trang chủ kèm nút theo dõi đơn.
  - CS-07–CS-13 tạo yêu cầu, chọn vị trí trên osmdroid.
  - CS-14–CS-23 yêu cầu, báo giá, đơn hàng, theo dõi, hoàn thành.
  - CS-26, CS-29–CS-34 đánh giá, tài khoản, thông báo, khiếu nại, hỗ trợ.
- [ ] App đối tác: làm khung các màn trong phạm vi bằng `FakeRepository` — hạn 12/10 (Hiệp). Gồm:
  - PS-02–PS-06 đăng ký và KYC.
  - PS-07, PS-08, PS-10, PS-11 newsfeed và báo giá.
  - PS-12–PS-21 đơn hàng, thực hiện việc, thu tiền, thu nhập.
  - PS-23, PS-24, PS-27 hồ sơ, kỹ năng, đánh giá khách.
- [ ] Chuẩn bị tài liệu cho BC2 — hạn 11/10 (Hiệp):
  - Bảng danh sách màn hình gồm mã, tên, app và chức năng.
  - Sơ đồ điều hướng của 2 app (mục 7.6).
- [ ] Soạn slide BC2, quay video dự phòng các flow màn hình và cập nhật Trello — hạn 12/10 (Hiệp)
**Đầu ra:**
- 2 APK chạy flow màn hình bằng dữ liệu giả.
- Script SQL và Database Diagram.
- Swagger có API xác thực và danh mục.
- Slide BC2.
**Rủi ro / ghi chú:**
- Khoảng 55 màn hình làm trong 10 ngày. Ở giai đoạn này làm bố cục và điều hướng trước, chưa chăm chi tiết.
- Bản đồ và nút theo dõi đơn làm dạng tĩnh.
- Giữ một `FakeRepository` cho mỗi feature để Giai đoạn 3 chỉ thay lớp dữ liệu.

## Giai đoạn 3: Tích hợp luồng đăng bài → báo giá → chat (hạn: 27/10 — mốc nội bộ)
**Mục tiêu:** 2 điện thoại thật chạy được luồng từ đăng ký, duyệt KYC, đăng bài, newsfeed, báo giá đến chat, dùng dữ liệu thật qua backend.
**Việc cần làm:**
- [ ] Làm `POST /files` (multipart) — hạn 15/10 (Hoàng):
  - Kiểm tra MIME bằng magic bytes và giới hạn kích thước.
  - Ảnh thường lưu `wwwroot/uploads`. Ảnh KYC lưu thư mục riêng, tải qua endpoint có kiểm quyền.
- [ ] Làm API hồ sơ, địa chỉ và thiết bị FCM #12–#16; API đối tác #17–#23, #25–#28 — hạn 17/10 (Hoàng)
- [ ] Trang quản trị: AW-04 duyệt KYC và kỹ năng (ghi AuditLog), AW-11 danh mục — hạn 19/10 (Hoàng)
- [ ] Làm API yêu cầu dịch vụ #36–#38, #40; chặn SĐT/URL trong nội dung; `BackgroundService` tự hết hạn bài — hạn 21/10 (Hoàng)
- [ ] Làm feed và báo giá — hạn 24/10 (Hoàng):
  - Feed #46: lọc kỹ năng APPROVED và khoảng cách Haversine.
  - Báo giá #42, #50, #52, #53.
  - Hub `/hubs/feed` phát `NewPost`; hub `/hubs/orders` phát `NewQuote`.
- [ ] Làm chat #54–#59, #61 và hub `/hubs/chat`; lưu bảng `Messages`; hội thoại người không được chọn chuyển READ_ONLY — hạn 26/10 (Hoàng)
- [ ] Viết unit test cho điều kiện matching và giới hạn 5 quote PENDING — hạn 26/10 (Hoàng)
- [ ] App khách: nối API xác thực, lưu token, tự refresh token khi hết hạn — hạn 17/10 (Nguyên)
- [ ] App khách: hồ sơ, sổ địa chỉ, chọn vị trí trên osmdroid (User-Agent riêng, ghi công OSM) — hạn 19/10 (Nguyên)
- [ ] App khách: tạo yêu cầu 3 bước có upload ảnh; Yêu cầu của tôi; huỷ bài — hạn 23/10 (Nguyên)
- [ ] App khách: chi tiết yêu cầu, danh sách báo giá realtime qua SignalR, hồ sơ đối tác CS-17 — hạn 25/10 (Nguyên)
- [ ] App khách: chat realtime dùng giao diện chat chung — hạn 27/10 (Nguyên)
- [ ] App đối tác: nối xác thực, đăng ký 4 bước, upload KYC, màn chờ duyệt — hạn 19/10 (Hiệp)
- [ ] App đối tác: bật/tắt Online có gửi vị trí, newsfeed realtime kèm bộ lọc, chi tiết bài — hạn 23/10 (Hiệp)
- [ ] App đối tác: gửi và rút báo giá; màn Báo giá của tôi — hạn 25/10 (Hiệp)
- [ ] App đối tác: nối chat realtime qua SignalR — hạn 27/10 (Hiệp)
- [ ] Ghi chú nội dung đã làm để dùng cho báo cáo cuối; cập nhật Trello — hàng tuần (Hiệp)
- [ ] Demo nội bộ trên 2 điện thoại và laptop, lập danh sách lỗi — 27/10 (cả nhóm)
**Đầu ra:** luồng đăng bài → newsfeed → báo giá → chat chạy thật trên 2 máy; admin duyệt được đối tác.
**Rủi ro / ghi chú:**
- Điện thoại gọi backend qua IP LAN của laptop. Cần mở cổng API trên tường lửa Windows và đặt `BASE_URL` trong `local.properties`.
- SignalR mất kết nối khi đổi mạng: bật tự kết nối lại và tải lại dữ liệu sau khi kết nối lại.

## Giai đoạn 4: Đơn hàng, theo dõi vị trí, hoàn tất COD (hạn: 10/11 — BC3)
**Mục tiêu:** toàn bộ giao diện chạy được (đúng yêu cầu BC3) và luồng đơn hàng đã nối backend. Không để việc nối API dồn sang tuần cuối.
**Việc cần làm:**
- [ ] Chọn báo giá tạo đơn #43 — hạn 30/10 (Hoàng):
  - Một transaction; kiểm tra slot ≤ 3, không tính assignment UNPAID_CASH.
  - Kiểm tra không chồng lịch.
  - Job hết hạn xác nhận sau 10 phút.
- [ ] State machine và lịch sử đơn — hạn 02/11 (Hoàng):
  - #62–#66.
  - #69, #70 kèm ảnh trước/sau.
  - Phát sinh chi phí #73, #74.
- [ ] Di chuyển, vị trí và check-in — hạn 04/11 (Hoàng):
  - #67; hub method `UpdateLocation` nhận vị trí 10 giây/lần; #76, #77 tính ETA bằng Haversine.
  - Check-in #68: ≤ 200 m, accuracy ≤ 100 m, từ chối `isMock`.
  - Nhờ khách xác nhận đã đến #178, #179.
- [ ] COD, huỷ và công nợ — hạn 06/11 (Hoàng):
  - Hoàn tất COD #71 và auto-confirm: ghi Transaction CASH và công nợ hoa hồng; chặn nhận việc khi nợ > 200.000 đ; cấn trừ tự động.
  - Huỷ #72, #151, #152 và khách không có mặt #180, ghi CancellationCharges.
  - Trả nợ #155, #156.
- [ ] Đánh giá #95, #96, #100; khiếu nại #146–#148; thông báo FCM và #101–#104 — hạn 08/11 (Hoàng)
- [ ] Trang quản trị: AW-02 dashboard, AW-03 người dùng, AW-06 bài đăng, AW-07 đơn hàng, AW-08 khiếu nại, AW-18 công nợ — hạn 09/11 (Hoàng)
- [ ] Viết unit test cho state machine và công thức tiền: báo giá 450.000 + phát sinh 180.000 → hoa hồng 94.500, đối tác thực nhận 535.500, cấn trừ nợ — hạn 09/11 (Hoàng)
- [ ] App khách: CS-18 xác nhận chọn đối tác; CS-19, CS-20 đơn hàng, chi tiết và timeline; nút theo dõi đơn ở Trang chủ — hạn 01/11 (Nguyên)
- [ ] App khách: CS-21 bản đồ theo dõi (marker cập nhật qua SignalR, nhãn "Chưa cập nhật" khi dữ liệu cũ); hộp thoại xác nhận "thợ đã đến" — hạn 04/11 (Nguyên)
- [ ] App khách: duyệt phát sinh CS-22; xác nhận hoàn thành COD CS-23; huỷ có xem trước phí — hạn 06/11 (Nguyên)
- [ ] App khách: đánh giá CS-26, khiếu nại CS-33, thông báo (FCM và CS-31), CS-30 phí huỷ còn nợ và khai báo chuyển khoản — hạn 09/11 (Nguyên)
- [ ] App đối tác: đơn hàng PS-13, chi tiết đơn theo trạng thái PS-14, xác nhận nhận đơn có đếm ngược 10 phút — hạn 01/11 (Hiệp)
- [ ] App đối tác: bắt đầu di chuyển — hạn 04/11 (Hiệp):
  - Foreground service gửi vị trí, `foregroundServiceType="location"`.
  - Màn điều hướng PS-15; nút "Đã đến" và "Nhờ khách xác nhận".
- [ ] App đối tác: chụp ảnh trước/sau PS-16, PS-18; phát sinh PS-17; thu tiền mặt PS-19; khách không có mặt — hạn 06/11 (Hiệp)
- [ ] App đối tác: thu nhập và công nợ PS-21, khai báo trả nợ, đánh giá khách PS-27, thông báo — hạn 09/11 (Hiệp)
- [ ] Soạn slide BC3 và cập nhật Trello — hạn 09/11 (Hiệp)
**Đầu ra:** 2 app đủ giao diện, phần lớn đã nối API; trang quản trị đủ 9 trang; slide BC3.
**Rủi ro / ghi chú:**
- Máy Xiaomi/Oppo/Vivo hay tắt foreground service. Hướng dẫn tắt tối ưu pin cho app trước mỗi buổi test.
- Trong lớp học, điện thoại đối tác không ở gần địa chỉ đơn. Khi đó dùng đường "nhờ khách xác nhận" hoặc tạo địa chỉ demo ngay tại trường.
- Auto-confirm 24 giờ: khi demo, rút ngắn qua SystemConfigs.

## Giai đoạn 5: Kiểm thử, hoàn thiện và demo cuối (hạn: 17/11 — BC cuối)
**Mục tiêu:** chạy app hoàn chỉnh, ổn định trên 2 điện thoại thật theo tiêu chí nghiệm thu ở mục 0.2.8.
**Việc cần làm:**
- [ ] Chạy trọn luồng demo 10 bước ở mục 0.2.8, ghi lỗi lên Trello kèm mức độ — hạn 12/11 (cả nhóm)
- [ ] Sửa lỗi. Ưu tiên lỗi chặn luồng demo, sau đó đến lỗi hiển thị — hạn 14/11 (người phụ trách từng phần)
- [ ] Seed dữ liệu demo: 3 khách, 3 đối tác đã duyệt, danh mục có icon, vài bài và đơn mẫu ở các trạng thái — hạn 14/11 (Hoàng)
- [ ] Nếu còn thời gian: #177 "Khách chưa thanh toán", CS-02 onboarding, PS-26, AW-15 cấu hình — chỉ làm sau khi luồng 0.2.8 chạy trọn (người phụ trách từng phần)
- [ ] Code freeze (đóng băng code): từ ngày này chỉ sửa lỗi chặn — 15/11 (cả nhóm)
- [ ] Viết kịch bản demo từng bước, phân vai người cầm máy khách, máy đối tác và laptop admin — hạn 15/11 (Hiệp)
- [ ] Cài APK lên 2 điện thoại; chuẩn bị hotspot dự phòng; ghi sẵn IP backend; tắt tối ưu pin — hạn 16/11 (Nguyên, Hiệp)
- [ ] Soạn slide BC cuối và quay video demo dự phòng — hạn 16/11 (Hiệp)
- [ ] Tập demo toàn bộ, bấm giờ — 16/11 (cả nhóm)
- [ ] Demo trước giảng viên — 17/11 (cả nhóm)
**Đầu ra:** app hoàn chỉnh (2 app + trang quản trị), slide BC cuối, video demo dự phòng.
**Rủi ro / ghi chú:**
- Wi-Fi của trường có thể chặn kết nối giữa các thiết bị. Nên dùng hotspot riêng và thử trước tại phòng học nếu được.
- Báo cáo cuối (.docx) chưa có hạn nộp. Khi giảng viên thông báo, Hiệp bổ sung giai đoạn viết báo cáo vào kế hoạch này, dựa trên v1.4 và ghi chú hàng tuần.
