# Kế hoạch quay video nộp bài — nhóm Tam Tài (Servio)

Yêu cầu của môn: clip gồm (1) giới thiệu thành viên, (2) giới thiệu app và các chức năng, (3) màn hình chạy của app.
Phần 3 quay **đủ 3 màn hình cùng lúc**: điện thoại khách hàng, điện thoại đối tác và máy tính mở trang quản trị. Tài liệu này có kịch bản đầy đủ từng bước và lời thuyết minh để đọc.

---

## 1. Khung video

| Phần | Thời lượng | Nội dung |
|---|---|---|
| Mở đầu | 10 giây | Logo, tên đề tài "Servio – Dịch vụ sửa chữa tại nhà", nhóm Tam Tài |
| 1. Thành viên | 1–1,5 phút | Mỗi người 20–30 giây: họ tên, MSSV, vai trò, phần đã làm |
| 2. Giới thiệu app | 2–3 phút | Bài toán, ba thành phần, danh sách chức năng, công nghệ (slide) |
| 3. Màn hình chạy | 15–18 phút (bản đầy đủ) hoặc 8–10 phút (bản rút gọn: chỉ các cảnh có dấu ★) | 22 cảnh dưới đây |
| Kết | 15 giây | Tóm tắt, cảm ơn |

Gợi ý phần 1: **Hoàng**: backend, trang quản trị, CSDL, pháp lý; **Nguyên**: app khách hàng và phần đăng nhập dùng chung; **Hiệp**: app đối tác. Mỗi người tự sửa cho đúng.

---

## 2. Bố cục 3 màn hình và cách quay

```
┌──────────────┬──────────────────────────────────┬──────────────┐
│  ĐIỆN THOẠI  │                                  │  ĐIỆN THOẠI  │
│  KHÁCH HÀNG  │     MÁY TÍNH – TRANG QUẢN TRỊ    │   ĐỐI TÁC    │
│  (app Servio)│        (trình duyệt, /admin)      │(ServioPartner)│
│   480×1080   │            960×1080               │   480×1080   │
└──────────────┴──────────────────────────────────┴──────────────┘
                      Khung video 1920×1080
```

**Cách quay khuyên dùng: OBS Studio, một lần quay ra cả 3 màn hình, không phải ghép cho khớp giờ.**
1. Trên laptop mở cùng lúc hai máy ảo Android và trình duyệt. Máy ảo trái cài app khách, máy ảo phải cài app đối tác. Trình duyệt mở `http://localhost:5080/admin`.
2. OBS: tạo một Scene, thêm 3 nguồn *Window Capture* (máy ảo trái, trình duyệt, máy ảo phải), xếp theo hình trên. Settings → Video: 1920×1080, 30 FPS. Bấm *Start Recording*.
3. Có thể quay theo từng cảnh, mỗi cảnh một file, rồi ghép trong CapCut. Lời thuyết minh thu riêng và đặt khớp theo bảng ở mục 5.
4. Màn hình nào đang thao tác thì phóng to nhẹ hoặc viền vàng khi dựng, cho người xem dễ theo dõi.

Dùng điện thoại thật thay máy ảo: dùng `scrcpy` để hiện màn hình điện thoại lên laptop rồi cho OBS bắt cửa sổ đó.

**Lưu ý khi quay:**
- Gõ tiếng Việt bằng bàn phím ảo trên máy ảo hoặc điện thoại, **không** gõ bằng Unikey/EVKey trên laptop (sẽ mất chữ có dấu).
- Thao tác chậm, dừng 1–2 giây ở mỗi màn để người xem kịp nhìn.
- Mỗi khi có thông báo hoặc cập nhật theo thời gian thực, dừng lại để thấy rõ màn hình bên kia thay đổi.

---

## 3. Chuẩn bị trước khi quay (không quay phần này)

1. **Tạo lại CSDL và dữ liệu mẫu:**
   ```bash
   sqlcmd -S localhost -E -f 65001 -i servio-backend/database/01_schema.sql
   sqlcmd -S localhost -E -f 65001 -i servio-backend/database/02_seed.sql
   dotnet run --project servio-backend/src/Servio.Api --launch-profile Demo -- --seed-demo
   dotnet run --project servio-backend/src/Servio.Api --launch-profile Demo
   ```
   Lệnh thứ ba chỉ tạo dữ liệu mẫu rồi thoát; lệnh thứ tư chạy server, để nguyên cửa sổ đó.
2. **AW-15 (Tham số):** điền ngân hàng demo (`Ngân hàng Demo (đồ án)`, `0000 0000 0000`, `SERVIO DEMO`) và hotline; đặt `order.auto_confirm_hours` = 24; giữ `privacy.require_consent` = true.
3. **Vị trí máy ảo:** Extended controls → Location.
   - Máy ảo khách: ghim tại địa chỉ sẽ dùng.
   - Máy ảo đối tác: cách khoảng 1 km. Khi tới cảnh "đến nơi", đổi vị trí đối tác về sát địa chỉ khách (hoặc giữ xa để quay đường "nhờ khách xác nhận").
4. **Ảnh mẫu trong máy ảo:**
   - Máy ảo đối tác: ảnh chân dung, 2 ảnh CCCD mẫu (tự làm, ghi rõ "MẪU"), 1 ảnh selfie.
   - Máy ảo khách: 1–2 ảnh máy lạnh.
   - Ảnh "trước khi làm", "sau khi làm" và 1 ảnh biên lai chuyển khoản mẫu.
   - Kéo thả file vào cửa sổ máy ảo để chép ảnh vào máy.
5. **Tài khoản dùng khi quay:**

| Vai | Số điện thoại | Ghi chú |
|---|---|---|
| Khách hàng mới (đăng ký trong video) | `0911 111 111` – Nguyễn Văn An | OTP luôn là `123456` |
| Đối tác mới (đăng ký trong video) | `0922 222 222` – Trần Minh Tuấn | |
| Admin | `admin@servio.local` | Mật khẩu ở `appsettings.Demo.json`, mục `SeedAdmin` |
| Dữ liệu mẫu có sẵn (cảnh 21–22) | Khách `0901000001`–`0901000003`, đối tác `0902000001`–`0902000003` | Có sẵn khiếu nại, khai báo trả nợ, đơn đã xong |

---

## 4. Kịch bản phần 3 — 22 cảnh

Ký hiệu: **[K]** điện thoại khách · **[Đ]** điện thoại đối tác · **[A]** trang quản trị · ★ có trong bản rút gọn.

| # | Cảnh | Thao tác | Màn hình |
|---|---|---|---|
| 1 ★ | Admin đăng nhập | [A] AW-01 đăng nhập → AW-02 Dashboard | AW-01, AW-02 |
| 2 ★ | Khách đăng ký | [K] mở app → nhập `0911111111` → OTP `123456` → màn **Điều khoản và quyền riêng tư** (đọc tóm tắt, tích ô, *Đồng ý và tiếp tục*) → nhập họ tên → Trang chủ | CS-01, 03, 04, đồng ý, 05, 06 |
| 3 | Khách thêm địa chỉ | [K] Tài khoản → Sổ địa chỉ → *Thêm* → ghim trên bản đồ → nhập địa chỉ, người nhận → lưu, đặt mặc định | CS-29, 13, 12 |
| 4 ★ | Đối tác đăng ký (4 bước) | [Đ] nhập `0922222222` → OTP → đồng ý điều khoản → **B1** ảnh chân dung, họ tên, giới thiệu, số năm kinh nghiệm → **B2** kỹ năng *Vệ sinh máy lạnh*, *Sửa máy lạnh* → **B3** ghim khu vực, bán kính 10 km → **B4** tích đồng ý riêng cho ảnh CCCD (dữ liệu nhạy cảm) → chụp CCCD mặt trước, mặt sau, ảnh selfie cầm CCCD → *Gửi duyệt* → màn **Chờ duyệt** | PS-01→PS-06 |
| 5 ★ | Admin duyệt hồ sơ | [A] AW-04 Duyệt đối tác → mở hồ sơ Trần Minh Tuấn → xem ảnh CCCD, kỹ năng → *Duyệt hồ sơ* (kèm duyệt kỹ năng). [Đ] nhận thông báo, chuyển sang **Việc gần bạn** | AW-04, PS-06→PS-07 |
| 6 ★ | Đối tác bật nhận việc | [Đ] gạt *Đang hoạt động* → tích đồng ý chia sẻ vị trí → cho phép quyền vị trí *Khi dùng ứng dụng* | PS-07 |
| 7 ★ | Khách đăng yêu cầu | [K] *Đăng yêu cầu* → chọn **Điện lạnh › Sửa máy lạnh** → B1 tiêu đề, mô tả, 1 ảnh → B2 địa chỉ, *Càng sớm càng tốt* → B3 ngân sách 300.000–600.000 đ, từ 3 năm kinh nghiệm → *Xem lại* → *Đăng yêu cầu* | CS-07→CS-11 |
| 8 ★ | Bài đến thợ theo thời gian thực | [Đ] bài mới hiện ngay trên feed, có khoảng cách → mở chi tiết (chỉ thấy khu vực, chưa thấy địa chỉ, SĐT) → thử *Bộ lọc*. [A] AW-06 Bài đăng thấy bài mới | PS-07, 08, 10, AW-06 |
| 9 ★ | Chat và báo giá | [Đ] nhắn "Chào anh, em qua kiểm tra trong 30 phút nhé" → *Gửi báo giá* 450.000 đ, 60 phút, giờ có thể đến → gửi. [K] thấy tin nhắn và báo giá hiện ngay | PS-11, chat, CS-15 |
| 10 ★ | Khách chọn thợ | [K] Yêu cầu → mở yêu cầu → chạm báo giá → xem **hồ sơ đối tác** (đã xác minh danh tính, kỹ năng, đánh giá) → *Chọn* → màn **Xác nhận chọn** (tổng tiền, quy định phí huỷ, thanh toán tiền mặt) → xác nhận | CS-14→CS-18 |
| 11 ★ | Thợ xác nhận đơn | [Đ] thông báo "Khách đã chọn báo giá" → Đơn hàng → chi tiết → *Xác nhận nhận đơn* (đếm ngược 10 phút). [K] đơn chuyển "Đã xác nhận", hiện SĐT thợ | PS-12, 13, 14, CS-19, 20 |
| 12 ★ | Di chuyển và theo dõi | [Đ] *Bắt đầu di chuyển* → màn điều hướng, thông báo "Đang chia sẻ vị trí". [K] *Theo dõi* → bản đồ có vị trí thợ, khoảng cách, giờ đến dự kiến, cập nhật mỗi 10 giây | PS-15, CS-21 |
| 13 ★ | Đến nơi (2 cách) | [Đ] bấm *Đã đến* khi còn xa → bị chặn "cách địa chỉ … m" → *Nhờ khách xác nhận*. [K] hộp thoại "Thợ báo đã đến, đúng không?" → *Xác nhận*. (Hoặc đổi vị trí máy ảo về sát địa chỉ rồi bấm *Đã đến*: qua bằng GPS.) | PS-15, CS-20b |
| 14 ★ | Làm việc và phát sinh | [Đ] chụp ảnh trước → *Bắt đầu làm* → *Thêm phát sinh*: "Thay tụ máy nén 35uF", 180.000 đ, kèm ảnh. [K] hộp duyệt phát sinh → *Duyệt* | PS-16, 17, CS-22 |
| 15 ★ | Hoàn thành và trả tiền | [Đ] chụp ảnh sau → *Báo hoàn thành* → màn **Thu tiền mặt** hiện 630.000 đ. [K] *Xác nhận hoàn thành* → xem ảnh trước/sau, tổng 630.000 đ → *Đã thanh toán tiền mặt* | PS-18, 19, CS-23 |
| 16 ★ | Đánh giá hai chiều | [K] đánh giá 5 sao và 4 tiêu chí, nhận xét. [Đ] đánh giá khách, chọn thẻ "Trả tiền đúng hẹn" | CS-26, PS-27 |
| 17 ★ | Thu nhập và trả nợ hoa hồng | [Đ] Thu nhập → nợ hoa hồng 94.500 đ (15% của 630.000) → *Khai báo đã chuyển khoản*, mã giao dịch, ảnh biên lai. [A] AW-18 Công nợ → *Đã nhận tiền*. [Đ] nợ về 0 đ | PS-21, AW-18 |
| 18 | Đơn thứ hai: huỷ có phí | [K] đăng yêu cầu mới → [Đ] báo giá → [K] chọn → [Đ] xác nhận, *Bắt đầu di chuyển* → [K] *Huỷ đơn* → thấy **phí huỷ 20.000 đ trước khi xác nhận** → huỷ. [K] Tài khoản → Phí huỷ và giao dịch → thông tin chuyển khoản → khai báo đã chuyển. [A] AW-18 xác nhận | CS-20, 30, AW-18 |
| 19 | Khiếu nại | [K] mở đơn ở cảnh 15 → *Khiếu nại* → chọn lý do, mô tả, ảnh → gửi → đơn "Đang khiếu nại", hiện hạn phản hồi. [A] AW-08 → mở khiếu nại → xử lý (chấp nhận hoặc bác bỏ) kèm ghi chú. [K][Đ] nhận kết quả | CS-33, 20, AW-08 |
| 20 | Tin nhắn, thông báo, hỗ trợ | [K] tab Tin nhắn (có mã đơn, giá) → Thông báo (nhóm theo ngày, *Đọc hết*) → Trung tâm hỗ trợ (hotline, câu hỏi thường gặp). [Đ] Tin nhắn, Thông báo | CS-27, 28, 31, 34, PS-20, 28 |
| 21 | Quyền riêng tư | [K] Cài đặt → mở *Chính sách bảo mật* (trang web) → *Rút lại đồng ý* (chỉ mở hộp xác nhận rồi bấm Huỷ) → *Xoá tài khoản*: dùng tài khoản mẫu `0901000003` để thấy bị từ chối kèm lý do, hoặc xoá thật một tài khoản phụ. [Đ] Hồ sơ đối tác → kỹ năng, khu vực, quyền riêng tư | CS-32, PS-23, 24 |
| 22 ★ | Trang quản trị tổng quan | [A] Dashboard số liệu mới → AW-07 Đơn hàng → mở đơn cảnh 15 (tiền, ảnh, dòng thời gian) → AW-03 Người dùng (khoá/mở tài khoản) → AW-06 Bài đăng (ẩn bài vi phạm) → AW-11 Danh mục → AW-15 Tham số (phí huỷ, hoa hồng, hotline) | AW-02, 07, 03, 06, 11, 15 |

---

## 5. Lời thuyết minh phần 3 (đọc theo từng cảnh)

> Đọc chậm, rõ, khoảng 130 chữ mỗi phút. Thời gian ghi kèm là ước tính. Bản rút gọn chỉ đọc các cảnh có ★.

**Mở đầu phần 3 (10 giây)**
"Sau đây nhóm em demo Servio trên ba màn hình: bên trái là điện thoại của khách hàng, bên phải là điện thoại của thợ, ở giữa là trang quản trị trên máy tính."

**Cảnh 1 ★ – Admin đăng nhập (15 giây)**
"Đầu tiên, quản trị viên đăng nhập trang quản trị. Trang Tổng quan cho biết số người dùng, số đơn đang chạy, khiếu nại chờ xử lý và các khoản chuyển khoản chờ xác nhận."

**Cảnh 2 ★ – Khách đăng ký (40 giây)**
"Khách hàng đăng ký bằng số điện thoại và mã OTP; trong bản demo mã luôn là 123456. Sau khi xác thực, app hiện màn Điều khoản và quyền riêng tư. Ô đồng ý không được tích sẵn và app ghi rõ việc tiếp tục sử dụng không được coi là đồng ý, đúng Luật Bảo vệ dữ liệu cá nhân 2025. Khách đọc, tích đồng ý, nhập họ tên và vào trang chủ."

**Cảnh 3 – Thêm địa chỉ (25 giây)**
"Khách lưu địa chỉ nhà bằng cách ghim vị trí trên bản đồ OpenStreetMap, nhập số nhà và người nhận. Địa chỉ mặc định sẽ được dùng khi đăng yêu cầu."

**Cảnh 4 ★ – Đối tác đăng ký (60 giây)**
"Bên phải, thợ đăng ký trên app ServioPartner. Bước một là thông tin cá nhân: ảnh chân dung, họ tên, giới thiệu và số năm kinh nghiệm. Bước hai chọn kỹ năng, ở đây là vệ sinh và sửa máy lạnh. Bước ba ghim khu vực hoạt động và bán kính nhận việc. Bước bốn là xác minh danh tính. Vì ảnh căn cước là dữ liệu cá nhân nhạy cảm, app xin một sự đồng ý riêng trước khi chụp. Thợ chụp hai mặt căn cước và một ảnh selfie, gửi duyệt, rồi chờ quản trị viên xét."

**Cảnh 5 ★ – Admin duyệt hồ sơ (30 giây)**
"Ở giữa, quản trị viên mở mục Duyệt đối tác, xem ảnh căn cước, ảnh selfie và các kỹ năng đăng ký, rồi duyệt hồ sơ. Ngay lập tức app của thợ nhận thông báo và chuyển sang màn Việc gần bạn. Chỉ thợ đã được xác minh mới nhận được việc."

**Cảnh 6 ★ – Bật nhận việc (20 giây)**
"Thợ gạt sang Đang hoạt động. App hỏi đồng ý chia sẻ vị trí và xin quyền vị trí đúng lúc cần, chỉ khi đang dùng ứng dụng. Từ giờ thợ nhận yêu cầu mới theo thời gian thực."

**Cảnh 7 ★ – Đăng yêu cầu (45 giây)**
"Khách bấm Đăng yêu cầu và chọn dịch vụ Sửa máy lạnh. Bước một mô tả công việc và thêm ảnh. Bước hai chọn địa chỉ và thời gian: làm ngay trong hai giờ, hoặc hẹn lịch. Bước ba nhập ngân sách dự kiến, ở đây từ ba trăm đến sáu trăm nghìn, và yêu cầu thợ có từ ba năm kinh nghiệm. App hiện giá tham chiếu để khách ước lượng. Xem lại một lần rồi đăng."

**Cảnh 8 ★ – Bài đến thợ (30 giây)**
"Chỉ một hai giây sau, bài mới xuất hiện trên điện thoại của thợ, kèm khoảng cách. Hệ thống chỉ gửi cho thợ có đúng kỹ năng, trong bán kính và đạt yêu cầu kinh nghiệm. Trước khi được chọn, thợ chỉ thấy khu vực chứ chưa thấy địa chỉ chính xác hay số điện thoại của khách. Quản trị viên cũng thấy bài mới trong mục Bài đăng."

**Cảnh 9 ★ – Chat và báo giá (35 giây)**
"Thợ nhắn tin chào khách rồi gửi báo giá bốn trăm năm mươi nghìn đồng, làm trong khoảng một giờ và có thể đến sau ba mươi phút. Bên trái, tin nhắn và báo giá hiện ngay trên app của khách mà không cần tải lại."

**Cảnh 10 ★ – Chọn thợ (40 giây)**
"Khách mở yêu cầu để so sánh các báo giá, sắp xếp theo giá hoặc đánh giá. Khách xem hồ sơ thợ: đã xác minh danh tính, kỹ năng, số năm kinh nghiệm và đánh giá. Màn xác nhận ghi rõ tổng tiền, hình thức trả tiền mặt và quy định phí huỷ, để khách biết trước khi đồng ý."

**Cảnh 11 ★ – Thợ xác nhận (25 giây)**
"Thợ nhận thông báo được chọn và có mười phút để xác nhận, quá hạn đơn sẽ tự huỷ. Sau khi thợ xác nhận, hai bên mới thấy số điện thoại và địa chỉ chính xác của nhau."

**Cảnh 12 ★ – Di chuyển và theo dõi (30 giây)**
"Thợ bấm Bắt đầu di chuyển. App chạy dịch vụ chia sẻ vị trí, có thông báo rõ ràng và tự dừng khi tới nơi. Khách mở bản đồ theo dõi, thấy vị trí thợ, khoảng cách và giờ đến dự kiến, cập nhật khoảng mười giây một lần."

**Cảnh 13 ★ – Đến nơi (35 giây)**
"Khi tới nơi, thợ bấm Đã đến. Hệ thống kiểm tra GPS: phải cách địa chỉ dưới hai trăm mét, đủ chính xác và không dùng vị trí giả. Nếu GPS không đạt, ví dụ trong hẻm hay trong nhà, thợ bấm Nhờ khách xác nhận. Khách nhận hộp thoại và xác nhận thợ đã đến. Vị trí theo thời gian thực bị xoá ngay khi chuyến đi kết thúc."

**Cảnh 14 ★ – Làm việc và phát sinh (35 giây)**
"Thợ chụp ảnh hiện trạng trước khi làm rồi bắt đầu công việc. Phát hiện tụ máy nén hỏng, thợ thêm chi phí phát sinh một trăm tám mươi nghìn kèm ảnh. Chi phí này chỉ được tính khi khách duyệt; khách xem lý do, số tiền và bấm Duyệt."

**Cảnh 15 ★ – Hoàn thành (35 giây)**
"Xong việc, thợ chụp ảnh sau khi làm và báo hoàn thành. App thợ hiện số tiền cần thu là sáu trăm ba mươi nghìn đồng. Khách xem lại ảnh trước và sau, tổng tiền, rồi xác nhận đã thanh toán tiền mặt. Nếu khách không bấm, đơn tự hoàn tất sau hai mươi bốn giờ."

**Cảnh 16 ★ – Đánh giá (25 giây)**
"Hai bên đánh giá lẫn nhau trong vòng năm ngày. Khách chấm sao và nhận xét theo bốn tiêu chí: chất lượng, đúng giờ, thái độ, giá cả. Thợ đánh giá khách, ví dụ trả tiền đúng hẹn. Điểm đánh giá cập nhật ngay trên hồ sơ của thợ."

**Cảnh 17 ★ – Thu nhập và công nợ (40 giây)**
"Vì khách trả tiền mặt trực tiếp cho thợ, Servio ghi khoản hoa hồng mười lăm phần trăm, tức chín mươi tư nghìn năm trăm đồng, vào công nợ của thợ. Thợ chuyển khoản rồi khai báo trong app kèm ảnh biên lai. Ở giữa, quản trị viên đối chiếu và bấm Đã nhận tiền, công nợ của thợ về không. Nếu nợ vượt hai trăm nghìn, thợ tạm thời không nhận được việc mới."

**Cảnh 18 – Huỷ đơn có phí (45 giây)**
"Một đơn khác: thợ đã nhận và đang trên đường tới thì khách muốn huỷ. Trước khi xác nhận, app hiện rõ phí huỷ hai mươi nghìn đồng, vì thợ đã bắt đầu di chuyển. Nếu thợ đã đến nơi, phí là hai mươi phần trăm giá, tối thiểu ba mươi nghìn. Khoản phí nằm trong mục Phí huỷ và giao dịch, kèm thông tin chuyển khoản. Khách khai báo đã chuyển, quản trị viên xác nhận, và tiền phí được chuyển bù cho thợ."

**Cảnh 19 – Khiếu nại (40 giây)**
"Nếu có vấn đề, khách mở khiếu nại trong vòng bảy mươi hai giờ sau khi hoàn tất, chọn lý do, mô tả và đính kèm ảnh. Đơn tạm dừng và app hiện hạn phản hồi bốn mươi tám giờ. Quản trị viên mở khiếu nại, xem lịch sử đơn, tin nhắn, ảnh bằng chứng rồi ra quyết định kèm ghi chú. Cả hai bên đều nhận kết quả."

**Cảnh 20 – Tin nhắn, thông báo, hỗ trợ (25 giây)**
"Mỗi cuộc trò chuyện gắn với một yêu cầu, có mã đơn và giá, kèm các tin hệ thống cập nhật trạng thái. Mục Thông báo nhóm theo ngày, đánh dấu đã đọc. Trung tâm hỗ trợ có hotline và câu hỏi thường gặp."

**Cảnh 21 – Quyền riêng tư (35 giây)**
"Trong Cài đặt, người dùng xem lại Chính sách bảo mật và Điều khoản, rút lại sự đồng ý, hoặc tự xoá tài khoản ngay trong app. Nếu còn đơn đang chạy hay còn nợ, app báo rõ lý do cần hoàn tất trước. Khi xoá, dữ liệu cá nhân bị xoá hoặc ẩn danh, còn lịch sử đơn được giữ dưới tên Người dùng đã xoá."

**Cảnh 22 ★ – Trang quản trị (45 giây)**
"Cuối cùng là trang quản trị. Mục Đơn hàng cho xem chi tiết từng đơn: tiền công, phát sinh, hoa hồng, ảnh trước sau và toàn bộ dòng thời gian trạng thái. Mục Người dùng cho phép khoá hoặc mở tài khoản vi phạm. Mục Bài đăng dùng để ẩn bài không phù hợp. Danh mục dịch vụ quản lý các nhóm việc. Mục Tham số cho phép đổi phí huỷ, tỷ lệ hoa hồng, hotline mà không cần cập nhật app; mọi thao tác của quản trị viên đều được ghi nhật ký."

**Kết phần 3 (10 giây)**
"Đó là toàn bộ luồng của Servio, từ đăng ký, duyệt hồ sơ, đăng việc, báo giá, thực hiện, thanh toán, đánh giá đến xử lý khiếu nại và quản trị."

---

## 6. Phần 2 — lời thuyết minh giới thiệu app (khoảng 2,5 phút, đọc trên slide)

"Servio là nền tảng kết nối người cần sửa chữa, chăm sóc nhà cửa với thợ ở gần. Khách đăng việc, thợ phù hợp gửi báo giá, khách so sánh và chọn; mọi bước được theo dõi trong app.

Hệ thống gồm ba phần: app Servio cho khách hàng, app ServioPartner cho thợ và trang quản trị cho nhóm vận hành, dùng chung một máy chủ.

Các chức năng chính:
- Đăng nhập bằng số điện thoại và mã OTP, xin đồng ý xử lý dữ liệu đúng quy định.
- Thợ đăng ký bốn bước và được xác minh danh tính.
- Khách đăng yêu cầu có ảnh, địa chỉ trên bản đồ, thời gian, ngân sách; thợ phù hợp nhận bài theo thời gian thực.
- Báo giá, chat, chọn thợ, theo dõi thợ trên bản đồ, check-in bằng GPS.
- Phát sinh có khách duyệt, thanh toán tiền mặt, đánh giá hai chiều.
- Phí huỷ minh bạch, khiếu nại, công nợ hoa hồng.
- Trang quản trị: duyệt hồ sơ, quản lý đơn, khiếu nại, đối soát chuyển khoản và cấu hình.

Về công nghệ: hai app viết bằng Kotlin theo kiến trúc MVVM, dùng Hilt, Retrofit, bản đồ osmdroid. Máy chủ dùng ASP.NET Core trên .NET 10 với SQL Server; SignalR cho cập nhật thời gian thực; trang quản trị dùng Razor Pages. Dự án có kiểm thử tự động cho cả máy chủ và hai app."

---

## 7. Lộ trình

| Ngày | Việc | Ai |
|---|---|---|
| 21/11 | Đọc kịch bản, chốt bản đầy đủ hay rút gọn; chuẩn bị ảnh mẫu; cài OBS | Cả nhóm |
| 21/11 | Mỗi người quay phần giới thiệu 20–30 giây (ngang, 1080p, nơi yên tĩnh, đủ sáng) | Cả nhóm |
| 22/11 | Chuẩn bị dữ liệu (mục 3), quay cảnh 1–22 bằng OBS. Mỗi cảnh một file, đặt tên `S01_…mp4` | Một người thao tác, một người kiểm tra theo bảng |
| 22/11 | Thu lời thuyết minh phần 2 và phần 3 (mục 5, 6) | Hoàng, Nguyên |
| 23/11 | Dựng CapCut: mở đầu → phần 1 → phần 2 → phần 3 → kết; phụ đề, nhạc nền nhỏ | Hiệp |
| 23/11 | Xem bản cuối, sửa, xuất 1080p MP4 `TamTai_Servio_Demo.mp4`, nộp | Cả nhóm |

**Kiểm tra trước khi nộp:**
- Đủ 3 phần.
- Phần 3 luôn thấy đủ 3 màn hình.
- Tiếng rõ, nhạc nhỏ hơn giọng.
- Không lộ dữ liệu thật (chỉ dùng tài khoản mẫu, ngân hàng demo).
