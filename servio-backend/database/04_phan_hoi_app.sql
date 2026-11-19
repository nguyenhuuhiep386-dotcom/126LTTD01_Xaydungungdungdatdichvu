/*
  Servio — nâng cấp CSDL đang chạy theo phản hồi của app (19/11/2026):
  ServiceRequests.BroadcastCount (#37/#38) và lần check-in GPS bị từ chối gần nhất của OrderAssignments (#178).
  CSDL tạo mới bằng 01_schema.sql đã có sẵn, không cần chạy file này.
    sqlcmd -S localhost -E -f 65001 -i 04_phan_hoi_app.sql
  Chạy nhiều lần không sao.
*/
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO
USE [Servio];
GO
SET NOCOUNT ON;

IF COL_LENGTH(N'dbo.ServiceRequests', N'BroadcastCount') IS NULL
    ALTER TABLE dbo.ServiceRequests ADD BroadcastCount int NOT NULL CONSTRAINT DF_ServiceRequests_BroadcastCount DEFAULT 0;
IF COL_LENGTH(N'dbo.OrderAssignments', N'ArrivalRejectedAt') IS NULL
    ALTER TABLE dbo.OrderAssignments ADD ArrivalRejectedAt datetimeoffset NULL;
IF COL_LENGTH(N'dbo.OrderAssignments', N'ArrivalRejectCode') IS NULL
    ALTER TABLE dbo.OrderAssignments ADD ArrivalRejectCode varchar(40) NULL;
GO
PRINT N'04_phan_hoi_app.sql done';
