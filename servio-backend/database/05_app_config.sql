/*
  Servio — nâng cấp CSDL đang chạy cho GET /app-config (19/11/2026): tài khoản nhận phí huỷ, hotline, giờ hỗ trợ,
  hạn xử lý khiếu nại. CSDL tạo mới bằng 02_seed.sql đã có sẵn, không cần chạy file này.
    sqlcmd -S localhost -E -f 65001 -i 05_app_config.sql
  Chạy nhiều lần không sao. Điền ngân hàng và hotline ở trang quản trị AW-15 (Cấu hình hệ thống).
*/
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO
USE [Servio];
GO
SET NOCOUNT ON;

INSERT INTO dbo.SystemConfigs ([Key], Value, DataType, [Group], Description)
SELECT c.[Key], c.Value, c.DataType, c.[Group], c.Description
FROM (VALUES
 ('dispute.response_hours',         N'48',     'int',    'ORDER',   N'Bộ phận hỗ trợ xử lý khiếu nại trong N giờ'),
 ('payment.bank_name',              N'',       'string', 'PAYMENT', N'Ngân hàng nhận phí huỷ (trống thì app ẩn)'),
 ('payment.bank_account_number',    N'',       'string', 'PAYMENT', N'Số tài khoản nhận phí huỷ'),
 ('payment.bank_account_holder',    N'',       'string', 'PAYMENT', N'Chủ tài khoản nhận phí huỷ'),
 ('payment.transfer_note_template', N'SERVIO {phone}', 'string', 'PAYMENT', N'Mẫu nội dung chuyển khoản; app thay {phone} bằng số điện thoại của khách'),
 ('support.hotline',                N'',       'string', 'APP',     N'Hotline hỗ trợ (trống thì app ẩn)'),
 ('support.hours',                  N'8:00–21:00 hằng ngày', 'string', 'APP', N'Giờ hỗ trợ')
) AS c ([Key], Value, DataType, [Group], Description)
WHERE NOT EXISTS (SELECT 1 FROM dbo.SystemConfigs s WHERE s.[Key] = c.[Key]);
GO
PRINT N'05_app_config.sql done';
