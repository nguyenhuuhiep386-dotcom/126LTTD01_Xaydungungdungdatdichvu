# Servio — Hướng dẫn phát triển

Tài liệu này giúp mỗi thành viên clone repo, chạy được demo trên máy mình và làm luồng được giao theo cùng một cách. Phân công và hạn của từng luồng nằm ở [ke-hoach-giai-doan.md](ke-hoach-giai-doan.md), mục 5.

## 1. Cấu trúc repo

```
├── README.md                     lịch báo cáo, phân công, quy ước chung
├── docs/
│   ├── servio-ke-hoach-phat-trien-v1.4.md   đặc tả (mục 0.2 = phạm vi đồ án)
│   ├── ke-hoach-giai-doan.md                kế hoạch và các luồng theo tác nhân
│   └── huong-dan-phat-trien.md              tài liệu này
├── design/figma-servio-builder/  plugin dựng bản Figma
├── servio-backend/
│   ├── database/01_schema.sql    33 bảng (tạo lại toàn bộ CSDL Servio)
│   ├── database/02_seed.sql      danh mục, SystemConfigs
│   ├── src/Servio.Api/           API + SignalR + trang quản trị Razor Pages
│   └── tests/Servio.Tests/       xUnit
└── servio-android/
    ├── core/      vn.servio.core: design system, network, token, màn xác thực, màn giữ chỗ
    ├── customer/  vn.servio.customer: app Servio
    └── partner/   vn.servio.partner: app ServioPartner
```

## 2. Cài đặt môi trường

| Công cụ | Phiên bản | Ghi chú |
|---|---|---|
| .NET SDK | 10.0 | `dotnet --list-sdks` |
| SQL Server | 2017 trở lên (Developer/Express) + SSMS | Kết nối bằng Windows Authentication |
| Android Studio | bản mới nhất có AGP 9.3 | SDK Platform 37; JDK đi kèm Android Studio |
| Công cụ EF | `dotnet tool install -g dotnet-ef` | Chỉ cần khi scaffold lại entity |

## 3. Chạy backend

1. Tạo CSDL. Chạy trong SSMS hoặc bằng lệnh dưới. **Script 01 xoá và tạo lại CSDL `Servio`**, mọi dữ liệu thử sẽ mất.

   ```bash
   sqlcmd -S localhost -E -f 65001 -i servio-backend/database/01_schema.sql
   ```

   ```bash
   sqlcmd -S localhost -E -f 65001 -i servio-backend/database/02_seed.sql
   ```

2. Nếu SQL Server không phải instance mặc định, sửa `ConnectionStrings:Servio` trong `src/Servio.Api/appsettings.Development.json`, ví dụ `Server=localhost\\SQLEXPRESS;...`.
3. Chạy API:

   ```bash
   dotnet run --project servio-backend/src/Servio.Api --launch-profile Development
   ```

4. Kiểm tra:
   - Swagger: <http://localhost:5080/swagger>. Gọi `POST /api/v1/auth/otp/request`, sau đó gọi `/otp/verify` với mã `123456`. Lấy `accessToken` dán vào nút **Authorize** để gọi các API cần đăng nhập.
   - Trang quản trị: <http://localhost:5080/admin>. Tài khoản tạo tự động lần chạy đầu lấy từ mục `SeedAdmin` trong `appsettings.Development.json`.
5. Chạy test:

   ```bash
   dotnet test servio-backend
   ```

Profile `Demo` (`--launch-profile Demo`) giống Development nhưng không mở trình duyệt; dùng khi demo trên lớp.

## 4. Chạy app Android

1. Mở thư mục `servio-android` bằng Android Studio.
2. Copy `local.properties.example` thành `local.properties`, rồi đặt `servio.baseUrl`:
   - Máy ảo: `http://10.0.2.2:5080/`
   - Điện thoại thật: `http://<IP LAN của laptop chạy backend>:5080/`. Xem IP bằng `ipconfig`. Điện thoại và laptop phải cùng Wi-Fi hoặc hotspot.
3. Mở cổng 5080 trên tường lửa Windows của laptop chạy backend. Chạy một lần trong PowerShell **Run as administrator**:

   ```powershell
   netsh advfirewall firewall add rule name="Servio API 5080" dir=in action=allow protocol=TCP localport=5080
   ```

4. Chọn cấu hình `customer` hoặc `partner` rồi bấm Run. Muốn build APK bằng dòng lệnh thì chạy lệnh dưới; file nằm trong `customer/build/outputs/apk/debug/` và `partner/build/outputs/apk/debug/`:

   ```bash
   ./gradlew assembleDebug
   ```

5. Đăng nhập bằng số điện thoại bất kỳ dạng `09xxxxxxxx` và mã `123456`.
   - **App khách:** tài khoản mới → CS-05 nhập tên → Trang chủ (danh mục lấy từ API).
   - **App đối tác:** tài khoản mới → PS-02. Để vào Newsfeed khi AW-04 chưa làm, duyệt tay trong SSMS:

     ```sql
     UPDATE p SET VerificationStatus = 2
     FROM PartnerProfiles p JOIN Users u ON u.Id = p.UserId
     WHERE u.PhoneNumber = '+84987654321';
     ```

6. Đăng xuất: app khách ở Tài khoản → Cài đặt (CS-32); app đối tác ở Thu nhập → Hồ sơ đối tác (PS-23).

## 5. Những gì đã có trong demo

| Phần | Trạng thái |
|---|---|
| CSDL 33 bảng + seed | Xong |
| API #1, #2, #5, #6, #9, #10, #29, #30 | Xong, có trên Swagger |
| Envelope, mã lỗi, validation, rate limit OTP | Xong |
| Admin: AW-01 đăng nhập, layout, AW-02 số liệu cơ bản, AW-11 xem danh mục | Xong |
| Android `:core`: design system, Retrofit, token + tự refresh, xử lý mất mạng | Xong |
| Màn CS-01, CS-03, CS-04, CS-05 (dùng chung, gọi API thật) | Xong |
| CS-06 Trang chủ (API thật), CS-14 Yêu cầu của tôi (dữ liệu giả), PS-07 Newsfeed (dữ liệu giả) | Xong, làm mẫu |
| Các màn còn lại của 2 app | Fragment giữ chỗ, đã nối đủ điều hướng |
| SignalR, FCM, osmdroid, upload file | Chưa có, thuộc các luồng ở mục 5 của kế hoạch |

## 6. Quy ước

- Theo v1.4 mục 0:
  - Bảng PascalCase số nhiều; path API kebab-case, bắt đầu bằng `/api/v1`.
  - JSON camelCase; enum truyền dạng chuỗi `UPPER_SNAKE_CASE`.
  - Tiền là số nguyên VND; thời gian là ISO-8601 có offset.
- Tên biến, hàm, lớp và comment viết bằng tiếng Anh. Chữ hiển thị cho người dùng viết bằng tiếng Việt, đặt trong `strings.xml` hoặc trong service.
- Backend:
  - Controller chỉ nhận request và gọi Service.
  - Lỗi nghiệp vụ: `throw new ApiException(status, ErrorCodes.X, "thông báo")`.
  - Mã lỗi mới thêm vào `Common/ErrorCodes.cs` **và** `core/.../network/ErrorMessages.kt`.
- Android:
  - Theo MVVM: Fragment → ViewModel (`StateFlow`) → Repository (interface).
  - Gọi API luôn qua `apiCall { }`; không bắt lỗi Retrofit trong ViewModel.
  - Màu, khoảng cách và kiểu chữ dùng token trong `core/src/main/res/values` (`servio_*`, `space_*`, `TextAppearance.Servio.*`); không viết mã hex trong layout.

## 7. Công thức: thêm một endpoint (backend)

1. Xem hợp đồng của endpoint trong v1.4 mục 6 (số `#n`), gồm input, output và mã lỗi.
2. Viết Service trong `Services/<Module>/`, gồm DTO dạng `record` và kiểm tra dữ liệu bằng DataAnnotations. Đăng ký Service trong `Program.cs` (`AddScoped`).
3. Thêm action vào Controller, kế thừa `ApiControllerBase`, trả về `OkEnvelope(...)`. Phân quyền: `[Authorize(Policy = AuthPolicies.Customer)]` hoặc `AuthPolicies.Partner`.
4. Viết test trong `tests/Servio.Tests/Services/`. Mẫu: `AuthServiceTests` dùng EF InMemory và `FakeTimeProvider`.
5. Chạy API, thử trên Swagger, rồi báo người làm Android rằng hợp đồng đã chốt.

Khi sửa CSDL:
1. Sửa `01_schema.sql` và chạy lại script.
2. Scaffold lại entity bằng lệnh dưới (chạy trong `servio-backend/src/Servio.Api`).
3. Không sửa tay các file trong `Data/Entities`.

```bash
dotnet ef dbcontext scaffold "Server=localhost;Database=Servio;Trusted_Connection=True;TrustServerCertificate=True" Microsoft.EntityFrameworkCore.SqlServer -o Data/Entities --context-dir Data -c ServioDbContext --context-namespace Servio.Api.Data --namespace Servio.Api.Data.Entities --no-onconfiguring --force
```

## 8. Công thức: làm một màn hình (Android)

Ví dụ làm CS-07:

1. **Giao diện.** Tạo `ui/<feature>/CategoryListFragment.kt` và `fragment_category_list.xml` theo Figma. Mẫu tham khảo: `customer/ui/home/` (API thật) và `customer/ui/requests/` (dữ liệu giả).
2. **Điều hướng.** Trong `res/navigation/nav_root.xml`, ở destination `cs07`:
   - Đổi `android:name` sang class mới.
   - Xoá các `<argument>` của màn giữ chỗ.
   - **Giữ nguyên id `cs07`** để mọi nút đang trỏ tới vẫn hoạt động.
3. **Dữ liệu giả.** Khai báo `interface XxxRepository` và `FakeXxxRepository` trong `data/repository/`, rồi bind trong `di/AppModule.kt` (`RepositoryBindings`).
4. **Nối API thật.**
   - Thêm interface Retrofit trong `data/remote/` và `@Provides` của nó.
   - Viết `RemoteXxxRepository` gọi qua `apiCall { }`.
   - Đổi binding từ Fake sang Remote. Không phải sửa Fragment hay ViewModel.
5. Mỗi màn phải có đủ các trạng thái: đang tải, rỗng, lỗi (kèm nút Thử lại) và mất mạng.

Mã lỗi từ server được `apiCall` đổi sang câu tiếng Việt; ViewModel chỉ cần đưa `Failure.message` lên giao diện.

## 9. Quy trình Git

1. Lấy code mới nhất từ nhánh `develop`:

   ```bash
   git checkout develop
   ```

   ```bash
   git pull
   ```

2. Tạo nhánh cho luồng của mình, ví dụ:

   ```bash
   git checkout -b feature/kh2-tao-yeu-cau
   ```

3. Commit nhỏ theo dạng `feat: CS-08 form mô tả yêu cầu`. Các loại commit: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`.
4. Push nhánh và tạo pull request vào `develop`. Cần 1 người review. Trước khi tạo PR, `dotnet test` hoặc `./gradlew assembleDebug` phải chạy đạt.
5. Gộp `develop` vào `main` trước mỗi buổi báo cáo, sau khi đã demo thử.

Không commit các file sau (đã có trong `.gitignore`):
- `local.properties`
- `google-services.json`
- APK
- ảnh trong `wwwroot/uploads`
- mật khẩu, khoá thật

## 10. Lỗi hay gặp

| Hiện tượng | Cách xử lý |
|---|---|
| App báo "Không có kết nối mạng" | Kiểm tra `servio.baseUrl` (có dấu `/` ở cuối), cổng tường lửa, điện thoại và laptop cùng mạng; thử mở `http://<IP>:5080/health/live` bằng trình duyệt điện thoại |
| API không kết nối được SQL Server | Sửa connection string theo tên instance; bật TCP/IP trong SQL Server Configuration Manager nếu cần |
| `Invalid object name` sau khi đổi script | Chạy lại `01_schema.sql` rồi `02_seed.sql`, sau đó scaffold lại entity |
| Gradle báo `Unable to establish loopback connection` | Đường dẫn biến môi trường `TEMP` quá dài; đặt `TEMP`/`TMP` sang thư mục ngắn, ví dụ `D:\tmp` |
| Đăng nhập lại liên tục | Refresh token đã bị thu hồi (đăng xuất ở thiết bị khác hoặc đã tạo lại CSDL); đăng nhập lại là đúng |
