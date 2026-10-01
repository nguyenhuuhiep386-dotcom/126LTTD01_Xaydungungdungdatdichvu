/*
  Servio — seed data (spec v1.4, appendix B, course scope).
  Run after 01_schema.sql:
    sqlcmd -S localhost -E -i 02_seed.sql
  The admin account is NOT seeded here: the API creates it on first start
  from configuration section "SeedAdmin" (password hash needs ASP.NET Core PasswordHasher).
*/
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO
USE [Servio];
GO
SET NOCOUNT ON;

/* ---------- Service categories: 7 level-1 groups + level-2 services (spec 1.2) ---------- */
DECLARE @Categories TABLE (ParentSlug varchar(150) NULL, Slug varchar(150), Name nvarchar(150), DisplayOrder int, PriceMin decimal(18, 2) NULL, PriceMax decimal(18, 2) NULL);
INSERT INTO @Categories (ParentSlug, Slug, Name, DisplayOrder, PriceMin, PriceMax) VALUES
 (NULL, 'dien-nuoc',            N'Điện – Nước',              1, NULL, NULL),
 (NULL, 'dien-lanh',            N'Điện lạnh',                2, NULL, NULL),
 (NULL, 'dien-tu-cntt',         N'Điện tử – CNTT',           3, NULL, NULL),
 (NULL, 've-sinh-nha-cua',      N'Vệ sinh nhà cửa',          4, NULL, NULL),
 (NULL, 'noi-that-xay-dung',    N'Nội thất – Xây dựng nhỏ',  5, NULL, NULL),
 (NULL, 'lam-dep-tai-nha',      N'Làm đẹp tại nhà',          6, NULL, NULL),
 (NULL, 'cham-soc',             N'Chăm sóc',                 7, NULL, NULL),

 ('dien-nuoc', 'sua-dien-dan-dung',        N'Sửa điện dân dụng',        1, 150000, 500000),
 ('dien-nuoc', 'chong-tham',               N'Chống thấm',               2, 500000, 3000000),
 ('dien-nuoc', 'sua-ong-nuoc',             N'Sửa ống nước',             3, 150000, 600000),
 ('dien-nuoc', 'lap-thiet-bi-ve-sinh',     N'Lắp thiết bị vệ sinh',     4, 200000, 800000),

 ('dien-lanh', 've-sinh-may-lanh',         N'Vệ sinh máy lạnh',         1, 150000, 350000),
 ('dien-lanh', 'sua-may-lanh',             N'Sửa máy lạnh',             2, 200000, 1500000),
 ('dien-lanh', 'sua-tu-lanh',              N'Sửa tủ lạnh',              3, 200000, 1500000),
 ('dien-lanh', 'sua-may-giat',             N'Sửa máy giặt',             4, 200000, 1200000),

 ('dien-tu-cntt', 'sua-laptop-pc',         N'Sửa laptop, PC',           1, 150000, 1500000),
 ('dien-tu-cntt', 'cai-dat-phan-mem',      N'Cài đặt phần mềm',         2, 100000, 300000),
 ('dien-tu-cntt', 'lap-camera',            N'Lắp camera',               3, 300000, 2000000),
 ('dien-tu-cntt', 'mang-wifi',             N'Mạng wifi',                4, 150000, 600000),

 ('ve-sinh-nha-cua', 'don-nha-theo-gio',   N'Dọn nhà theo giờ',         1, 80000, 300000),
 ('ve-sinh-nha-cua', 'tong-ve-sinh',       N'Tổng vệ sinh',             2, 500000, 3000000),
 ('ve-sinh-nha-cua', 'giat-sofa-nem',      N'Giặt sofa, nệm',           3, 300000, 1500000),

 ('noi-that-xay-dung', 'tho-moc',          N'Thợ mộc',                  1, 200000, 2000000),
 ('noi-that-xay-dung', 'son-nha',          N'Sơn nhà',                  2, 500000, 5000000),
 ('noi-that-xay-dung', 'khoan-cat',        N'Khoan, cắt',               3, 100000, 500000),
 ('noi-that-xay-dung', 'lap-rap-noi-that', N'Lắp ráp nội thất',         4, 150000, 800000),

 ('lam-dep-tai-nha', 'cat-toc-goi-dau',    N'Cắt tóc, gội đầu',         1, 80000, 300000),
 ('lam-dep-tai-nha', 'trang-diem',         N'Trang điểm',               2, 200000, 1000000),
 ('lam-dep-tai-nha', 'nail',               N'Làm nail',                 3, 100000, 500000),
 ('lam-dep-tai-nha', 'cham-soc-da',        N'Chăm sóc da',              4, 200000, 800000),

 ('cham-soc', 'giup-viec-theo-gio',        N'Giúp việc theo giờ',       1, 80000, 300000),
 ('cham-soc', 'cham-nguoi-gia',            N'Chăm người già',           2, 300000, 1000000),
 ('cham-soc', 'cham-thu-cung',             N'Chăm thú cưng',            3, 100000, 500000);

INSERT INTO dbo.ServiceCategories (Id, ParentId, Name, Slug, DisplayOrder, ReferencePriceMin, ReferencePriceMax)
SELECT NEWID(), NULL, c.Name, c.Slug, c.DisplayOrder, c.PriceMin, c.PriceMax
FROM @Categories c
WHERE c.ParentSlug IS NULL AND NOT EXISTS (SELECT 1 FROM dbo.ServiceCategories s WHERE s.Slug = c.Slug);

INSERT INTO dbo.ServiceCategories (Id, ParentId, Name, Slug, DisplayOrder, ReferencePriceMin, ReferencePriceMax)
SELECT NEWID(), p.Id, c.Name, c.Slug, c.DisplayOrder, c.PriceMin, c.PriceMax
FROM @Categories c
JOIN dbo.ServiceCategories p ON p.Slug = c.ParentSlug
WHERE NOT EXISTS (SELECT 1 FROM dbo.ServiceCategories s WHERE s.Slug = c.Slug);

/* ---------- System configs in course scope (spec 5.2.7) ---------- */
DECLARE @Configs TABLE ([Key] varchar(100), Value nvarchar(max), DataType varchar(20), [Group] varchar(50), Description nvarchar(500));
INSERT INTO @Configs VALUES
 ('commission.default_rate',                    N'15',     'decimal', 'PAYMENT',   N'Tỷ lệ hoa hồng mặc định (%)'),
 ('order.partner_accept_timeout_minutes',       N'10',     'int',     'ORDER',     N'Đối tác phải xác nhận đơn trong N phút'),
 ('order.auto_confirm_hours',                   N'24',     'int',     'ORDER',     N'Tự xác nhận hoàn thành sau N giờ (rút ngắn khi demo)'),
 ('order.dispute_window_hours',                 N'72',     'int',     'ORDER',     N'Cửa sổ khiếu nại sau CompletedAt'),
 ('order.max_active_assignments_per_partner',   N'3',      'int',     'ORDER',     N'Số việc đang chạy tối đa của một đối tác'),
 ('order.unpaid_report_window_hours',           N'24',     'int',     'ORDER',     N'Hạn đối tác báo khách chưa trả tiền mặt'),
 ('matching.default_radius_km',                 N'10',     'int',     'MATCHING',  N'Bán kính mặc định của bài đăng'),
 ('matching.max_radius_km',                     N'20',     'int',     'MATCHING',  N'Bán kính tối đa khi khách mở rộng'),
 ('matching.max_broadcast_partners',            N'50',     'int',     'MATCHING',  N'Số đối tác tối đa nhận một bài'),
 ('partner.heartbeat_seconds',                  N'30',     'int',     'MATCHING',  N'Chu kỳ heartbeat của app đối tác'),
 ('partner.offline_timeout_minutes',            N'10',     'int',     'MATCHING',  N'Mất heartbeat quá N phút coi là offline'),
 ('partner.max_commission_debt',                N'200000', 'decimal', 'PAYMENT',   N'Nợ hoa hồng vượt mức này bị chặn nhận việc'),
 ('quote.max_pending_per_partner',              N'5',      'int',     'MATCHING',  N'Số báo giá PENDING tối đa của một đối tác'),
 ('post.expire_hours',                          N'24',     'int',     'ORDER',     N'Bài đăng hết hạn sau N giờ'),
 ('review.window_days',                         N'5',      'int',     'ORDER',     N'Hạn gửi đánh giá sau CompletedAt'),
 ('cancellation.fee_after_on_the_way',          N'20000',  'decimal', 'PAYMENT',   N'Phí huỷ khi đối tác đang di chuyển'),
 ('cancellation.fee_after_arrived_min',         N'30000',  'decimal', 'PAYMENT',   N'Phí huỷ tối thiểu khi đối tác đã đến'),
 ('cancellation.fee_after_arrived_percent',     N'20',     'decimal', 'PAYMENT',   N'Phí huỷ theo % giá báo khi đối tác đã đến'),
 ('cancellation.max_outstanding_amount',        N'100000', 'decimal', 'PAYMENT',   N'Tổng phí huỷ DUE vượt mức này chặn đăng bài mới'),
 ('cancellation.no_show_wait_minutes',          N'15',     'int',     'ORDER',     N'Thời gian chờ trước khi báo khách không có mặt'),
 ('checkin.max_distance_meters',                N'200',    'int',     'ORDER',     N'Khoảng cách tối đa tới địa chỉ khi check-in'),
 ('checkin.max_accuracy_meters',                N'100',    'int',     'ORDER',     N'Độ chính xác GPS tối thiểu khi check-in'),
 ('checkin.max_fix_age_seconds',                N'60',     'int',     'ORDER',     N'Tuổi tối đa của điểm GPS khi check-in'),
 ('checkin.customer_confirm_ttl_seconds',       N'300',    'int',     'ORDER',     N'Hạn khách xác nhận đối tác đã đến'),
 ('checkin.customer_confirm_max_requests',      N'3',      'int',     'ORDER',     N'Số lần tối đa nhờ khách xác nhận'),
 ('integrity.checkin_required',                 N'false',  'bool',    'ORDER',     N'Bắt buộc Play Integrity khi check-in (tắt khi demo)'),
 ('app.min_version_customer',                   N'1.0.0',  'string',  'APP',       N'Phiên bản tối thiểu app khách'),
 ('app.min_version_partner',                    N'1.0.0',  'string',  'APP',       N'Phiên bản tối thiểu app đối tác'),
 ('app.maintenance_mode',                       N'false',  'bool',    'APP',       N'Chế độ bảo trì');

INSERT INTO dbo.SystemConfigs ([Key], Value, DataType, [Group], Description)
SELECT c.[Key], c.Value, c.DataType, c.[Group], c.Description
FROM @Configs c
WHERE NOT EXISTS (SELECT 1 FROM dbo.SystemConfigs s WHERE s.[Key] = c.[Key]);

DECLARE @CategoryCount int = (SELECT COUNT(*) FROM dbo.ServiceCategories);
DECLARE @ConfigCount int = (SELECT COUNT(*) FROM dbo.SystemConfigs);
PRINT CONCAT(N'Seed done: ', @CategoryCount, N' categories, ', @ConfigCount, N' configs.');
GO
