# Checklist demo — buổi báo cáo cuối (17/11/2026)

Tài liệu dùng trong 2 ngày 16/11 và 17/11. In ra hoặc mở trên laptop, tích từng ô khi xong. Lệnh cài đặt chi tiết xem [`huong-dan-phat-trien.md`](huong-dan-phat-trien.md); luồng nghiệm thu xem v1.4 mục 0.2.8.

## Phân vai

| Người | Thiết bị | Phụ trách |
|---|---|---|
| Hoàng | Laptop | Backend, CSDL, trang quản trị; thao tác bước 1 (duyệt KYC) và bước 10 |
| Nguyên | Điện thoại cài **Servio** (khách) | Bước 2, 3, 4, 5 (xác nhận đã đến nếu cần), 6, 8, 9 |
| Hiệp | Điện thoại cài **ServioPartner** (đối tác) | Bước 1, 3, 4, 5, 6, 7, 8, 9; dẫn chuyện và mở slide |

## Thông tin cần có sẵn

| Mục | Giá trị |
|---|---|
| Trang quản trị | `http://<IP laptop>:5080/admin`; tài khoản trong mục `SeedAdmin` của `appsettings.Demo.json` |
| Swagger (dự phòng) | `http://<IP laptop>:5080/swagger` |
| Kiểm tra backend sống | `http://<IP laptop>:5080/health/live` (mở được trên trình duyệt điện thoại là mạng đã thông) |
| OTP | `123456` cho mọi số |
| Số mới cho bước 1 và 2 | Đối tác `0903000001`, khách `0903000002` (dự phòng `0903000011`, `0903000012` nếu phải làm lại) |
| Tài khoản có sẵn (seed) | Khách `0901000001`–`0901000003`, đối tác `0902000001`–`0902000003`, chi tiết ở `huong-dan-phat-trien.md` |
| Địa chỉ demo | Trường ĐH Sư phạm Kỹ thuật, 1 Võ Văn Ngân (phòng học), để check-in GPS chạy được trong lớp |

---

## A. Ngày 16/11 — chuẩn bị

### Mạng (cả nhóm, làm đầu tiên)

IP của laptop nằm sẵn trong APK (`servio.baseUrl`), nên phải chốt mạng **trước khi build APK**.

- [ ] Chọn một trong hai cách, thử thật tại phòng học nếu được:
  - **Cách 1 — laptop phát hotspot** (khuyên dùng): laptop vẫn nối Wi-Fi trường, bật *Settings → Network → Mobile hotspot*; hai điện thoại kết nối vào. IP laptop luôn là `192.168.137.1`, không đổi giữa các lần.
  - **Cách 2 — điện thoại thứ ba phát hotspot**: laptop và hai máy demo cùng kết nối; xem IP laptop bằng `ipconfig` mỗi lần, IP có thể đổi.
- [ ] Không dùng Wi-Fi trường cho demo: thường chặn các thiết bị gọi nhau.
- [ ] Điện thoại có Internet (bản đồ OpenStreetMap tải tile qua mạng). Với cách 1, kiểm tra Internet qua hotspot laptop.
- [ ] Laptop: mở cổng 5080 (PowerShell *Run as administrator*, chỉ một lần):

  ```powershell
  netsh advfirewall firewall add rule name="Servio API 5080" dir=in action=allow protocol=TCP localport=5080
  ```

- [ ] Trên điện thoại mở `http://<IP laptop>:5080/health/live` → thấy trang trả về là mạng đã thông.

### Laptop (Hoàng)

- [ ] `git pull` nhánh `main` (đã gắn tag bản demo); `dotnet build servio-backend` và `dotnet test servio-backend` đạt.
- [ ] SQL Server chạy. Nếu là instance có tên, sửa `ConnectionStrings:Servio` trong `appsettings.Demo.json` (ví dụ `Server=localhost\\TÊN_INSTANCE;...`) và thêm `-S localhost\TÊN_INSTANCE` cho `sqlcmd` bên dưới.
- [ ] Tạo CSDL sạch, chạy backend profile Demo, rồi chạy thử trọn luồng:

  ```bash
  sqlcmd -S localhost -E -f 65001 -i servio-backend/database/01_schema.sql
  ```

  ```bash
  sqlcmd -S localhost -E -f 65001 -i servio-backend/database/02_seed.sql
  ```

  ```bash
  dotnet run --project servio-backend/src/Servio.Api --launch-profile Demo
  ```

  ```bash
  python servio-backend/tools/demo_smoke.py http://localhost:5080
  ```

  Phải in `ĐẠT cả 10 bước + #177`. Script ghi dữ liệu thử, sáng 17/11 sẽ tạo lại CSDL.
- [ ] (Tuỳ chọn) Push thật: điền đường dẫn file service account Firebase vào `Fcm:ServiceAccountFile` trong `appsettings.Demo.json`. Để trống thì vẫn có hộp thông báo trong app, chỉ không có push.
- [ ] Power: cắm sạc, tắt Sleep khi cắm điện, tắt cập nhật Windows tự khởi động lại trong ngày 17/11.
- [ ] Đăng nhập trang quản trị một lần, ghim các tab: Dashboard, AW-04 Duyệt KYC, AW-07 Đơn hàng, AW-08 Khiếu nại, AW-18 Công nợ, AW-15 Tham số.

### Hai điện thoại (Nguyên, Hiệp)

- [ ] `local.properties`: `servio.baseUrl=http://<IP laptop>:5080/` theo mạng đã chốt; build `./gradlew assembleDebug`; cài APK `customer` lên máy khách, `partner` lên máy đối tác.
- [ ] Bật Vị trí, chế độ chính xác cao; cấp quyền vị trí **Luôn cho phép** cho app đối tác (foreground service gửi vị trí).
- [ ] Cho phép thông báo cho cả hai app.
- [ ] Tắt tối ưu pin cho app đối tác (Xiaomi/Oppo/Vivo: *Pin → Không hạn chế* và *Tự khởi chạy*).
- [ ] Tắt ứng dụng giả lập vị trí (mock location) nếu có: server chặn check-in khi phát hiện vị trí giả.
- [ ] Độ sáng màn hình cao, tắt khoá màn hình tự động, bật *Không làm phiền*; sạc đầy, mang sạc dự phòng.
- [ ] Nếu chiếu màn hình điện thoại: thử scrcpy hoặc ứng dụng chiếu trước.

### Tập dượt (cả nhóm, 16/11)

- [ ] Chạy trọn phần C trên hai điện thoại thật, bấm giờ (mục tiêu ≤ 15 phút cho 10 bước).
- [ ] Thử cả đường dự phòng bước 5 ("Nhờ khách xác nhận").
- [ ] Quay video dự phòng toàn bộ luồng (Hiệp), lưu sẵn trên laptop và một điện thoại.
- [ ] Sau buổi tập: ghi lỗi gặp phải; chỉ sửa lỗi chặn luồng (đang code freeze).

---

## B. Sáng 17/11 — trước giờ demo

**T − 60 phút (Hoàng)**

- [ ] Bật mạng theo cách đã chốt, kiểm tra IP laptop đúng với IP trong APK.
- [ ] Tạo lại CSDL sạch và seed dữ liệu demo (seed vào sáng nay vì bài đang mở hết hạn sau 24 giờ):

  ```bash
  sqlcmd -S localhost -E -f 65001 -i servio-backend/database/01_schema.sql
  ```

  ```bash
  sqlcmd -S localhost -E -f 65001 -i servio-backend/database/02_seed.sql
  ```

  ```bash
  dotnet run --project servio-backend/src/Servio.Api --launch-profile Demo -- --seed-demo
  ```

- [ ] Chạy backend `dotnet run --project servio-backend/src/Servio.Api --launch-profile Demo`, để cửa sổ này mở suốt buổi.
- [ ] Trang quản trị: đăng nhập; Dashboard có dữ liệu seed (1 khiếu nại chờ xử lý, 1 khai báo trả nợ chờ xác nhận).
- [ ] **Không chạy `demo_smoke.py` sau khi seed** (sẽ thêm dữ liệu thử vào CSDL demo).

**T − 30 phút (cả nhóm)**

- [ ] Hai điện thoại mở `http://<IP laptop>:5080/health/live` được.
- [ ] Mở hai app, chưa đăng nhập (bước 1 và 2 đăng ký số mới trước lớp).
- [ ] Hiệp chuẩn bị giấy tờ để chụp KYC (CCCD mẫu hoặc ảnh in sẵn).
- [ ] Đứng trong phòng học khi chọn vị trí địa chỉ của khách ở bước 2, để bước 5 check-in GPS gần đúng chỗ.

---

## C. Kịch bản 10 bước (v1.4 mục 0.2.8)

| Bước | Thao tác | Kết quả cần thấy | Nếu trục trặc |
|---|---|---|---|
| 1 | **Đối tác** đăng ký số mới bằng OTP, nhập hồ sơ, chọn vị trí hoạt động, thêm kỹ năng *Vệ sinh máy lạnh*, chụp 3 ảnh KYC, gửi duyệt. **Laptop** mở AW-04, xem ảnh, bấm *Duyệt hồ sơ*. | App đối tác chuyển từ màn chờ duyệt sang Newsfeed; có thông báo "Hồ sơ đã được duyệt" | Không có thông báo: kéo làm mới hoặc mở lại app. Dùng tạm đối tác seed `0902000001` nếu đăng ký bị kẹt |
| 2 | **Khách** đăng ký số mới, nhập tên, thêm địa chỉ tại phòng học (ghim bản đồ), đăng bài *Vệ sinh máy lạnh* có ảnh. **Đối tác** bật Online. | Bài mới hiện trong Newsfeed đối tác trong ≤ 3 giây | Đối tác phải Online **trước** khi khách đăng; nếu bài không hiện, kéo làm mới feed |
| 3 | **Đối tác** mở bài, chat chào khách, báo giá **450.000 đ**. **Khách** thấy báo giá ngay, xem hồ sơ đối tác, chọn. **Đối tác** xác nhận đơn (trong 10 phút). | Khách thấy báo giá realtime; đơn chuyển "Đã xác nhận" ở cả hai máy | Báo giá chưa hiện: khách mở lại màn chi tiết yêu cầu |
| 4 | **Đối tác** bấm *Bắt đầu di chuyển*. **Khách** mở màn theo dõi đơn. | Marker đối tác trên bản đồ, khoảng cách và ETA; cập nhật mỗi ~10 giây | Bản đồ trắng: kiểm tra Internet điện thoại. Vị trí "Chưa cập nhật": kiểm tra quyền vị trí và tối ưu pin |
| 5 | **Đối tác** bấm *Đã đến*. | Đơn chuyển "Đã đến nơi" | Bị chặn "cách địa chỉ quá 200 m" hoặc "GPS chưa đủ chính xác": bấm **Nhờ khách xác nhận**, **khách** bấm *Xác nhận* trong hộp thoại (hạn 5 phút) |
| 6 | **Đối tác** chụp ảnh trước → *Bắt đầu làm* → thêm phát sinh **180.000 đ** (thay tụ). **Khách** duyệt phát sinh. **Đối tác** chụp ảnh sau → *Báo hoàn thành*. **Khách** bấm *Xác nhận hoàn thành, đã trả tiền mặt*. | Tổng **630.000 đ**; đơn "Hoàn tất" ở cả hai máy | Không bấm được hoàn thành vì còn phát sinh chờ duyệt: khách duyệt trước |
| 7 | **Đối tác** mở màn Thu nhập/Công nợ. (Laptop có thể mở AW-18 tab *Nợ hoa hồng đối tác*.) | Nợ hoa hồng tăng đúng **94.500 đ** (15% của 630.000) | — |
| 8 | **Khách** đánh giá 5 sao + nhận xét; **đối tác** đánh giá khách. | Rating đối tác cập nhật | — |
| 9 | Đơn thứ hai: **khách** đăng bài mới → **đối tác** báo giá → **khách** chọn → **đối tác** xác nhận và *Bắt đầu di chuyển* → **khách** bấm *Huỷ đơn*, xem phí. | Phí huỷ **20.000 đ**; đơn "Đã huỷ"; khách thấy khoản phí trong mục công nợ | Phí 0 đ nghĩa là đối tác chưa bấm *Bắt đầu di chuyển* |
| 10 | **Laptop**: AW-07 mở đơn bước 6 (tiền, ảnh, timeline); AW-08 xử lý khiếu nại có sẵn (khách Minh – đối tác Tâm) bằng *Bác bỏ* kèm ghi chú; AW-18 bấm *Đã nhận tiền* cho khai báo trả nợ của đối tác Hùng. | Khiếu nại "Bác bỏ", đơn về "Hoàn tất"; nợ của Hùng về 0 đ; Dashboard cập nhật | Hàng đợi trống: CSDL chưa seed, xem mục D |

Thời gian dự kiến: bước 1–2 khoảng 4 phút, 3–6 khoảng 6 phút, 7–9 khoảng 3 phút, 10 khoảng 2 phút.

---

## D. Sự cố thường gặp

| Hiện tượng | Xử lý nhanh |
|---|---|
| App báo mất mạng / không gọi được server | Mở `http://<IP laptop>:5080/health/live` trên điện thoại. Không mở được: sai mạng, sai IP (APK build với IP khác) hoặc tường lửa chưa mở cổng 5080 |
| IP laptop đổi so với APK | Đổi lại mạng đúng như lúc build. Không được thì build lại APK với IP mới (mất ~3 phút), hoặc chuyển sang video dự phòng |
| Đăng nhập OTP báo sai mã | Backend phải chạy profile **Demo** hoặc Development (mã cố định `123456`) |
| Màn hình không tự cập nhật (báo giá, trạng thái, vị trí) | Kéo làm mới / mở lại màn; dữ liệu đã đúng trên server, chỉ là sự kiện realtime bị lỡ |
| Check-in GPS bị chặn | Dùng "Nhờ khách xác nhận" (tối đa 3 lần cho một đơn) |
| Đối tác không nhận được việc mới, báo nợ hoa hồng | Nợ > 200.000 đ bị chặn. Laptop: AW-18 xác nhận một khoản trả nợ, hoặc dùng đối tác khác |
| Khách không đăng bài được, báo còn nợ | Còn phí huỷ > 100.000 đ hoặc đơn chờ thanh toán. Dùng khách khác hoặc xác nhận trả nợ ở AW-18 |
| Cần đơn tự hoàn tất nhanh | AW-15: đặt `order.auto_confirm_hours` = 0, đơn chờ khách xác nhận tự hoàn tất trong ~30 giây; đặt lại 24 sau buổi demo |
| Backend dừng hoặc treo | Chạy lại lệnh `dotnet run ... --launch-profile Demo`; dữ liệu vẫn còn trong SQL Server |
| Dữ liệu demo bị rối | Tạo lại CSDL (01, 02) và seed lại: khoảng 2 phút. Hai app phải đăng nhập lại |
| Mọi cách đều không được | Chiếu video dự phòng, vừa chiếu vừa giải thích từng bước |

---

## E. Sau buổi demo

- [ ] Đặt lại các tham số đã đổi ở AW-15 (nếu có).
- [ ] Ghi nhận xét của giảng viên vào Trello và README (nhật ký thay đổi).
- [ ] Giữ lại CSDL buổi demo (backup trong SSMS) để chụp ảnh minh hoạ cho báo cáo cuối.
