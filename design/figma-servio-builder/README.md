# Servio Builder (plugin Figma chạy cục bộ)

Plugin dựng lại toàn bộ bản thiết kế Servio trong file Figma đang mở, dữ liệu nhúng sẵn trong `code.js`, không cần mạng và không tốn lượt gọi Figma MCP.

Kết quả gồm 3 trang:

1. **Servio (khách hàng)**: 35 khung, 5 luồng prototype.
2. **ServioPartner (đối tác)**: 31 khung, 4 luồng prototype.
3. **Admin web và design system**: 9 trang admin, bảng design system, 68 component icon, 15 biến màu và 11 kiểu chữ.

## Cách chạy

Cần dùng ứng dụng Figma desktop, vì bản web không nạp được plugin từ file manifest.

1. Mở file **Servio UI Prototype** trong team Nhóm 9.
2. Vào menu **Plugins → Development → Import plugin from manifest…**, rồi chọn `manifest.json` trong thư mục này.
3. Chạy **Plugins → Development → Servio Builder**. Chờ khoảng 1–3 phút cho đến khi cuối màn hình hiện dòng "Servio: xong…".
4. Chạy thử prototype: chọn trang, bấm **Present (▶)** rồi chọn luồng trong danh sách Flows. Ở tab Prototype, đặt thiết bị là một điện thoại Android.

## Lưu ý

- Plugin xoá và dựng lại mọi khung có tên bắt đầu bằng mã màn (CS-…, PS-…, AW-…, DesignSystem). Hãy chạy **trước** khi nhóm bắt đầu chỉnh tay. Nếu đã chỉnh, hãy nhân bản (duplicate) file trước khi chạy lại.
- Lớp nào có mũi tên trong tên, ví dụ `Button → CS-07`, là lớp đã được gắn link prototype tới màn CS-07.
- Màu và chữ được liên kết với biến `Servio` và các kiểu chữ trên trang 3. Muốn đổi màu thương hiệu, chỉ cần sửa biến.
