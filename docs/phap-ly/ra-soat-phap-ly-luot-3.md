# Rà soát pháp lý lượt 3: Servio — toàn bộ ứng dụng

> 🤖 Báo cáo do AI tạo từ mã nguồn, chỉ để tham khảo, không phải ý kiến pháp lý. Lượt này không còn lỗi 🔴 nào trong code. Các mục 🟡 còn lại phần lớn là giấy tờ và thủ tục khi triển khai thật. Việc gì quan trọng thì hỏi lại người làm luật.

**Ngày audit:** 20/11/2026 · **Công cụ:** skill [`vn-law-check`](https://github.com/mhfed/vn-law-check) (dữ liệu luật cập nhật 03/11/2026)
**Mã nguồn được rà:** `develop` sau PR #41 (gồm #36–#40: màn đồng ý trong `:core`, PS-23 Quyền riêng tư, CS-31 Thông báo, CS-33 Khiếu nại, CS-34 Hỗ trợ). Các lượt trước: [lượt 1, backend](ra-soat-phap-ly-backend.md); lượt 2 (hai app, 17/11) nằm trên nhánh `feature/phap-ly-ung-dung`. Nhánh này không merge, vì phần đồng ý đã được làm lại trong `:core`.
**Kết quả quét:** `scan.sh servio-android`: app Android, xin quyền vị trí; app đối tác có foreground service vị trí và quyền thông báo; chụp ảnh qua FileProvider; có xoá tài khoản. Không có SDK thống kê, quảng cáo hay Firebase, không gửi dữ liệu tới máy chủ nước ngoài ngoài ảnh bản đồ OpenStreetMap. Cùng backend, Servio là **mức 3** (nền tảng trung gian, có dữ liệu nhạy cảm: ảnh CCCD, vị trí).
**Phạm vi checklist:** 01 Bảo vệ dữ liệu cá nhân, 02 Thương mại điện tử (phần nền tảng trung gian), 04 Bảo vệ người tiêu dùng, 09 Mobile app (mục B, C, D, F).

## Tổng quan

| Lĩnh vực | 🔴 | 🟡 | 🟢 |
|---|---|---|---|
| Bảo vệ dữ liệu cá nhân (01) | 0 | 4 | 7 |
| Thương mại điện tử (02) | 0 | 2 | 2 |
| Bảo vệ người tiêu dùng (04) | 0 | 0 | 3 |
| Mobile app (09) | 0 | 1 | 5 |

**Không áp dụng:** quảng cáo, SDK tracking (09-D, 09-G); mạng xã hội (09-E: chat chỉ phục vụ đơn hàng); trẻ em, game (09-H); AI (07).

## Đã sửa trong lượt này (20/11)

- **01-F. Vị trí ghép việc còn lưu khi đối tác tắt app mà không bấm Offline** (lượt 1, 🟡 2). Job 30 giây giờ đặt Offline và xoá vị trí của đối tác ngừng gửi heartbeat quá `partner.offline_timeout_minutes` (`Services/Partners/PartnerProfileService.cs` `DropStaleLocationsAsync`, `Jobs/ExpiryJob.cs`). Vị trí chuyến đi trong `OrderAssignments` không bị đụng tới. Trang chính sách ghi rõ thời điểm xoá này.
- **01-A. Thời hạn lưu mã OTP** (lượt 1, 🟡 3, một phần). Mã OTP bị xoá một ngày sau khi hết hạn (`Services/Auth/AuthService.cs` `PurgeExpiredOtpsAsync`); trang chính sách ghi thời hạn này.
- **Trang pháp lý:**
  - Bỏ ghi chú `[KIỂM TRA]` ở mục cookie: lượt này đã xác nhận hai app không có công cụ thống kê hay quảng cáo.
  - Mục Liên hệ của Điều khoản lấy hotline từ `support.hotline` (AW-15); thời hạn xử lý khiếu nại lấy từ `dispute.response_hours`.

Kiểm tra: 159 unit test backend đạt (thêm test cho hai việc dọn dữ liệu trên).

## 🟡 Nên cải thiện

1. **01-B. App đối tác chưa rút được riêng đồng ý ảnh CCCD và đồng ý chia sẻ vị trí.**
   - Hiện trạng: PS-23 chỉ rút `TERMS_AND_PRIVACY` (kèm đăng xuất) hoặc xoá tài khoản. Hai đồng ý `KYC_IDENTITY` và `LOCATION_SHARING` vẫn ở trạng thái đã đồng ý.
   - Backend đã sẵn sàng: `POST /users/me/consents` với `granted: false`. Sau khi rút, server tự chặn bật Online và chặn nộp ảnh CCCD.
   - Căn cứ: khoản 2 Điều 10 Luật 91/2025/QH15 (rút đồng ý dễ như khi đồng ý).
   - Mức phạt: CHƯA XÁC MINH (Điều 45 NĐ 330/2026/NĐ-CP, checklist 08).
   - Phần của Hiệp (`partner/.../ui/profile/ProfileFragment.kt`):

   ```
   Trong PS-23 (partner/src/main/java/vn/servio/partner/ui/profile/ProfileFragment.kt), khối "Quyền riêng tư",
   thêm hai dòng "Rút đồng ý chia sẻ vị trí" và "Rút đồng ý xử lý ảnh CCCD". Bấm thì hỏi xác nhận, nói rõ hệ quả
   (không bật Online được / không nộp lại hồ sơ được cho tới khi đồng ý lại), rồi gọi POST /users/me/consents
   { purpose: "LOCATION_SHARING" | "KYC_IDENTITY", granted: false, policyVersion } và tắt Online, dừng
   LocationTrackingService nếu đang chạy. Lần sau cần, ConsentGate sẽ hỏi lại như bình thường.
   ```

2. **01-B. Rút đồng ý ảnh CCCD sau khi đã duyệt** (lượt 1, 🟡 1): ảnh cũ vẫn giữ tới khi xoá tài khoản. Nên quy định trong chính sách: rút thì tài khoản dừng nhận việc và ảnh bị xoá.
3. **01-A, 01-E. Thời hạn lưu trữ còn lại, quy trình báo sự cố 72 giờ, chuyển dữ liệu xuyên biên giới (thông báo đẩy qua Google), DPIA và nhân sự bảo vệ dữ liệu** (lượt 1, 🟡 3–6). Đây là việc của đơn vị vận hành khi triển khai thật, không sửa được trong code.
   - Ghi chú: hai app hiện chưa tích hợp Firebase Cloud Messaging. Màn đồng ý và chính sách vẫn nói thông báo đẩy đi qua Google. Nói thừa thì không vi phạm, nhưng nếu không tích hợp FCM thì nên bỏ câu đó cho đúng thực tế.
4. **02-B, 02-C, 09-F. Thông tin chủ quản và đăng ký nền tảng trung gian** (lượt 1, 🟡 7–8): ngày 21/11 hai trang pháp lý đã điền đơn vị vận hành (nhóm Tam Tài, đồ án môn học, không có mã số doanh nghiệp), địa chỉ và email. Khi kinh doanh thật cần pháp nhân và đăng ký nền tảng; app chưa hiện tên chủ sở hữu ngoài hai trang này. Căn cứ: Điều 11, khoản 2 Điều 14 Luật 122/2025/QH15; Điều 5 khoản 2 của 42/VBHN-BCT.
5. **03-B. Lưu nhật ký hệ thống 12 tháng** (lượt 1, 🟡 9): khi triển khai thật.

## 🟢 Đạt yêu cầu

- **01-B (cả hai app, `:core`):**
  - Màn "Điều khoản và quyền riêng tư" sau OTP: ô đồng ý không tích sẵn, có link hai trang, ghi rõ "Việc tiếp tục sử dụng ứng dụng không được coi là đồng ý".
  - Chỉ hỏi khi chưa đồng ý bản hiện tại; chính sách đổi (409) thì bỏ tích và yêu cầu đọc lại.
  - Server trả `CONSENT_REQUIRED` thì app mở lại màn này. Mục 🟡 2 của lượt 2 đã đóng.
  - File: `core/.../ui/auth/LegalConsentFragment.kt`, `LegalConsentViewModel.kt`, `fragment_legal_consent.xml:73`.
  - Câu "Bằng việc tiếp tục, bạn đồng ý…" của Figma đã bị bỏ có chủ ý (`fragment_phone_input.xml:74`).
- **01-B (app đối tác):** `ConsentGate` hỏi riêng `KYC_IDENTITY` (ghi rõ dữ liệu nhạy cảm) và `LOCATION_SHARING` đúng lúc cần; `TERMS` đã chuyển về `:core`.
- **01-B, 09-C (rút đồng ý, xoá tài khoản):**
  - CS-32 (app khách) và PS-23 (app đối tác) đều có link hai trang pháp lý, "Rút lại đồng ý" và "Xoá tài khoản" hai bước.
  - Server từ chối xoá thì app hiện đúng lý do. Mục 🟡 1 của lượt 2 đã đóng phần điều khoản; còn mục 🟡 1 ở trên.
- **09-B (quyền):**
  - App khách chỉ xin vị trí khi bấm "vị trí hiện tại" ở màn ghim địa chỉ; từ chối vẫn kéo bản đồ ghim được (`PickLocationFragment.kt:80`, `strings.xml:229`).
  - App đối tác không có `ACCESS_BACKGROUND_LOCATION`. Foreground service vị trí có thông báo và dừng khi hết chuyến, khi đăng xuất hoặc khi xoá tài khoản (`ProfileFragment.kt:198`). Quyền thông báo xin lúc bắt đầu di chuyển (`OrderDetailFragment.kt:260`).
- **09-D:** không có SDK thống kê, quảng cáo hay Advertising ID.
- **01-F:** vị trí chuyến đi bị xoá khi hết chuyến (lượt 1); vị trí ghép việc nay bị xoá cả khi mất heartbeat.
- **04-A, 04-B, 09-F (giá, phí):** giá báo, phát sinh, phí huỷ do server tính và hiện trước khi xác nhận. Quy định phí huỷ, hạn khiếu nại, hạn đánh giá trong app khách lấy từ `GET /app-config`, khớp với trang Điều khoản.
- **04-D (khiếu nại, Điều 31 Luật 19/2023):** CS-33 gửi khiếu nại kèm ảnh, CS-20 hiện hạn phản hồi trong lúc chờ xử lý và kết quả sau khi xử lý.
- **02-D:** đối tác được xác minh danh tính trước khi nhận việc; khiếu nại có người xử lý (AW-08).

## Mức phạt tham khảo

Không còn lỗi 🔴. Mục 🟡 1 thuộc nhóm "không đáp ứng yêu cầu rút đồng ý" (Điều 45 NĐ 330/2026/NĐ-CP), mức phạt **CHƯA XÁC MINH** theo checklist 08.

## Ngoài phạm vi, nên kiểm tra riêng

- Trang quản trị tải font từ Google Fonts (`Pages/Shared/_AdminLayout.cshtml`), lộ IP của admin cho Google.

## Việc nhóm cần làm trước khi nộp

- ~~Điền `[ĐIỀN]` trong hai trang pháp lý~~: đã điền 21/11 (nhóm Tam Tài, đồ án môn học).
- AW-15: điền `support.hotline` và tài khoản nhận phí huỷ; giữ `privacy.require_consent = true`.
- Hiệp làm mục 🟡 1 nếu kịp.

## Giới hạn & khuyến nghị

- Báo cáo dựa trên mã nguồn `develop` ngày 20/11/2026, không chạy thử trên thiết bị thật, không xem cấu hình máy chủ khi triển khai.
- Dữ liệu luật của skill cập nhật 03/11/2026. Nhiều văn bản mới có hiệu lực trong 2026 và mức phạt có thể thay đổi; hãy kiểm tra lại tại [thuvienphapluat.vn](https://thuvienphapluat.vn) hoặc [vbpl.vn](https://vbpl.vn).
- Đây là công cụ hỗ trợ rà soát ban đầu, không thay thế tư vấn của luật sư. Trước khi triển khai thật, đặc biệt các mục về dữ liệu nhạy cảm và chuyển dữ liệu ra nước ngoài, nên hỏi người làm luật.
