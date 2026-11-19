/*
  Servio — nâng cấp CSDL đang chạy cho phần dữ liệu cá nhân (15/11/2026):
  bảng UserConsents (bằng chứng đồng ý) và 2 tham số privacy.*.
  CSDL tạo mới bằng 01_schema.sql + 02_seed.sql đã có sẵn, không cần chạy file này.
    sqlcmd -S localhost -E -f 65001 -i 03_privacy.sql
  Chạy nhiều lần không sao.
*/
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO
USE [Servio];
GO
SET NOCOUNT ON;

IF OBJECT_ID(N'dbo.UserConsents', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.UserConsents (
        Id            uniqueidentifier NOT NULL CONSTRAINT PK_UserConsents PRIMARY KEY,
        UserId        uniqueidentifier NOT NULL CONSTRAINT FK_UserConsents_Users REFERENCES dbo.Users (Id),
        Purpose       tinyint          NOT NULL,             -- 1=TERMS_AND_PRIVACY, 2=KYC_IDENTITY, 3=LOCATION_SHARING
        Granted       bit              NOT NULL,
        PolicyVersion varchar(20)      NOT NULL,
        AppFlavor     tinyint          NOT NULL,
        IpAddress     varchar(45)      NULL,
        UserAgent     nvarchar(300)    NULL,
        CreatedAt     datetimeoffset   NOT NULL CONSTRAINT DF_UserConsents_CreatedAt DEFAULT SYSUTCDATETIME()
    );
    CREATE INDEX IX_UserConsents_User_Purpose ON dbo.UserConsents (UserId, Purpose, CreatedAt);
    PRINT N'Created dbo.UserConsents';
END;

INSERT INTO dbo.SystemConfigs ([Key], Value, DataType, [Group], Description)
SELECT c.[Key], c.Value, c.DataType, c.[Group], c.Description
FROM (VALUES
    ('privacy.policy_version',  N'2026-11-15', 'string', 'PRIVACY', N'Phiên bản chính sách bảo mật và điều khoản; đổi khi sửa nội dung để hỏi lại sự đồng ý'),
    ('privacy.require_consent', N'true',       'bool',   'PRIVACY', N'Bắt buộc có sự đồng ý trước khi thu dữ liệu (chỉ tắt khi app chưa có màn hình đồng ý)')
) AS c ([Key], Value, DataType, [Group], Description)
WHERE NOT EXISTS (SELECT 1 FROM dbo.SystemConfigs s WHERE s.[Key] = c.[Key]);
PRINT N'Privacy upgrade done.';
GO
