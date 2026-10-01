/*
  Servio — database schema for the course project (spec v1.4, section 0.2.5).
  33 tables. Names and types follow spec section 5.2; columns for out-of-scope
  features (geography, administrative codes, online payment, promotions, P1) are omitted.

  Run on SQL Server 2017+ (SSMS or sqlcmd):
    sqlcmd -S localhost -E -i 01_schema.sql
  The script drops and recreates database [Servio].

  Enum values are stored as tinyint; the meaning of each code is written next to the column.
  Money: decimal(18,2) VND. Time: datetimeoffset (UTC). Coordinates: decimal(9,6).
*/
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULLS ON;
GO
USE master;
GO
IF DB_ID(N'Servio') IS NOT NULL
BEGIN
    ALTER DATABASE [Servio] SET SINGLE_USER WITH ROLLBACK IMMEDIATE;
    DROP DATABASE [Servio];
END
GO
CREATE DATABASE [Servio];
GO
USE [Servio];
GO

/* Business codes: SRyyMMdd0001, ODyyMMdd0001, DSyyMMdd0001, TXyyMMdd0001 */
CREATE SEQUENCE dbo.ServiceRequestCodeSeq AS int START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE dbo.OrderCodeSeq AS int START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE dbo.DisputeCodeSeq AS int START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE dbo.TransactionCodeSeq AS int START WITH 1 INCREMENT BY 1;
GO

/* =========================== Identity & Profile =========================== */

CREATE TABLE dbo.AdminUsers (
    Id                  uniqueidentifier NOT NULL CONSTRAINT PK_AdminUsers PRIMARY KEY,
    Email               nvarchar(256)    NOT NULL,
    PasswordHash        nvarchar(256)    NOT NULL,
    FullName            nvarchar(100)    NOT NULL,
    Role                tinyint          NOT NULL,           -- 1=SUPER_ADMIN, 2=OPERATOR, 3=FINANCE, 4=SUPPORT
    IsActive            bit              NOT NULL CONSTRAINT DF_AdminUsers_IsActive DEFAULT 1,
    LastLoginAt         datetimeoffset   NULL,
    MustChangePassword  bit              NOT NULL CONSTRAINT DF_AdminUsers_MustChangePassword DEFAULT 1,
    CreatedAt           datetimeoffset   NOT NULL CONSTRAINT DF_AdminUsers_CreatedAt DEFAULT SYSUTCDATETIME(),
    UpdatedAt           datetimeoffset   NOT NULL CONSTRAINT DF_AdminUsers_UpdatedAt DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_AdminUsers_Email UNIQUE (Email)
);

CREATE TABLE dbo.Users (
    Id                      uniqueidentifier NOT NULL CONSTRAINT PK_Users PRIMARY KEY,
    PhoneNumber             nvarchar(15)     NOT NULL,       -- normalized +84...
    Email                   nvarchar(256)    NULL,
    FullName                nvarchar(100)    NOT NULL CONSTRAINT DF_Users_FullName DEFAULT N'',
    AvatarUrl               nvarchar(500)    NULL,
    DateOfBirth             date             NULL,
    Gender                  tinyint          NULL,           -- 0=OTHER, 1=MALE, 2=FEMALE
    IsPhoneVerified         bit              NOT NULL CONSTRAINT DF_Users_IsPhoneVerified DEFAULT 0,
    Status                  tinyint          NOT NULL CONSTRAINT DF_Users_Status DEFAULT 1, -- 1=ACTIVE, 2=SUSPENDED, 3=BANNED, 4=DELETED
    SuspendedUntil          datetimeoffset   NULL,
    LastLoginAt             datetimeoffset   NULL,
    PostingRestrictedUntil  datetimeoffset   NULL,
    CreatedAt               datetimeoffset   NOT NULL CONSTRAINT DF_Users_CreatedAt DEFAULT SYSUTCDATETIME(),
    UpdatedAt               datetimeoffset   NOT NULL CONSTRAINT DF_Users_UpdatedAt DEFAULT SYSUTCDATETIME(),
    DeletedAt               datetimeoffset   NULL,
    CONSTRAINT UQ_Users_PhoneNumber UNIQUE (PhoneNumber)
);
CREATE UNIQUE INDEX UX_Users_Email ON dbo.Users (Email) WHERE Email IS NOT NULL;
CREATE INDEX IX_Users_Status ON dbo.Users (Status);

CREATE TABLE dbo.UserRoles (
    Id        uniqueidentifier NOT NULL CONSTRAINT PK_UserRoles PRIMARY KEY,
    UserId    uniqueidentifier NOT NULL CONSTRAINT FK_UserRoles_Users REFERENCES dbo.Users (Id),
    Role      tinyint          NOT NULL,                     -- 1=CUSTOMER, 2=PARTNER
    CreatedAt datetimeoffset   NOT NULL CONSTRAINT DF_UserRoles_CreatedAt DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_UserRoles_User_Role UNIQUE (UserId, Role)
);

CREATE TABLE dbo.CustomerProfiles (
    Id              uniqueidentifier NOT NULL CONSTRAINT PK_CustomerProfiles PRIMARY KEY,
    UserId          uniqueidentifier NOT NULL CONSTRAINT FK_CustomerProfiles_Users REFERENCES dbo.Users (Id),
    AverageRating   decimal(3, 2)    NULL,                   -- NULL until the first visible review
    TotalReviews    int              NOT NULL CONSTRAINT DF_CustomerProfiles_TotalReviews DEFAULT 0,
    TotalOrders     int              NOT NULL CONSTRAINT DF_CustomerProfiles_TotalOrders DEFAULT 0,
    CancelledOrders int              NOT NULL CONSTRAINT DF_CustomerProfiles_CancelledOrders DEFAULT 0,
    CreatedAt       datetimeoffset   NOT NULL CONSTRAINT DF_CustomerProfiles_CreatedAt DEFAULT SYSUTCDATETIME(),
    UpdatedAt       datetimeoffset   NOT NULL CONSTRAINT DF_CustomerProfiles_UpdatedAt DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_CustomerProfiles_UserId UNIQUE (UserId)
);

CREATE TABLE dbo.PartnerProfiles (
    Id                  uniqueidentifier NOT NULL CONSTRAINT PK_PartnerProfiles PRIMARY KEY,
    UserId              uniqueidentifier NOT NULL CONSTRAINT FK_PartnerProfiles_Users REFERENCES dbo.Users (Id),
    Bio                 nvarchar(1000)   NULL,
    YearsOfExperience   int              NOT NULL CONSTRAINT DF_PartnerProfiles_YearsOfExperience DEFAULT 0,
    VerificationStatus  tinyint          NOT NULL CONSTRAINT DF_PartnerProfiles_VerificationStatus DEFAULT 0, -- 0=NOT_SUBMITTED, 1=PENDING, 2=APPROVED, 3=REJECTED
    VerificationNote    nvarchar(500)    NULL,
    VerifiedAt          datetimeoffset   NULL,
    VerifiedByAdminId   uniqueidentifier NULL CONSTRAINT FK_PartnerProfiles_AdminUsers REFERENCES dbo.AdminUsers (Id),
    IsOnline            bit              NOT NULL CONSTRAINT DF_PartnerProfiles_IsOnline DEFAULT 0,
    LastHeartbeatAt     datetimeoffset   NULL,
    CurrentLatitude     decimal(9, 6)    NULL,
    CurrentLongitude    decimal(9, 6)    NULL,
    AnchorLatitude      decimal(9, 6)    NULL,
    AnchorLongitude     decimal(9, 6)    NULL,
    ServiceRadiusKm     int              NOT NULL CONSTRAINT DF_PartnerProfiles_ServiceRadiusKm DEFAULT 10, -- 3/5/10/20
    AverageRating       decimal(3, 2)    NULL,
    TotalReviews        int              NOT NULL CONSTRAINT DF_PartnerProfiles_TotalReviews DEFAULT 0,
    CompletedOrders     int              NOT NULL CONSTRAINT DF_PartnerProfiles_CompletedOrders DEFAULT 0,
    CancelledOrders     int              NOT NULL CONSTRAINT DF_PartnerProfiles_CancelledOrders DEFAULT 0,
    CreatedAt           datetimeoffset   NOT NULL CONSTRAINT DF_PartnerProfiles_CreatedAt DEFAULT SYSUTCDATETIME(),
    UpdatedAt           datetimeoffset   NOT NULL CONSTRAINT DF_PartnerProfiles_UpdatedAt DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_PartnerProfiles_UserId UNIQUE (UserId),
    CONSTRAINT CK_PartnerProfiles_ServiceRadiusKm CHECK (ServiceRadiusKm BETWEEN 1 AND 20)
);
CREATE INDEX IX_PartnerProfiles_Online_Status ON dbo.PartnerProfiles (IsOnline, VerificationStatus);

CREATE TABLE dbo.PartnerDocuments (
    Id               uniqueidentifier NOT NULL CONSTRAINT PK_PartnerDocuments PRIMARY KEY,
    PartnerProfileId uniqueidentifier NOT NULL CONSTRAINT FK_PartnerDocuments_PartnerProfiles REFERENCES dbo.PartnerProfiles (Id),
    DocumentType     tinyint          NOT NULL,              -- 1=ID_FRONT, 2=ID_BACK, 3=SELFIE_WITH_ID, 4=CERTIFICATE, 5=CRIMINAL_RECORD
    FileUrl          nvarchar(500)    NOT NULL,              -- private path, served through an authorized endpoint
    DocumentNumber   nvarchar(50)     NULL,
    Status           tinyint          NOT NULL CONSTRAINT DF_PartnerDocuments_Status DEFAULT 1, -- 1=PENDING, 2=APPROVED, 3=REJECTED
    RejectReason     nvarchar(500)    NULL,
    ExpiryDate       date             NULL,
    CreatedAt        datetimeoffset   NOT NULL CONSTRAINT DF_PartnerDocuments_CreatedAt DEFAULT SYSUTCDATETIME()
);
CREATE INDEX IX_PartnerDocuments_PartnerProfileId ON dbo.PartnerDocuments (PartnerProfileId);

CREATE TABLE dbo.ServiceCategories (
    Id                  uniqueidentifier NOT NULL CONSTRAINT PK_ServiceCategories PRIMARY KEY,
    ParentId            uniqueidentifier NULL CONSTRAINT FK_ServiceCategories_Parent REFERENCES dbo.ServiceCategories (Id),
    Name                nvarchar(150)    NOT NULL,
    Slug                varchar(150)     NOT NULL,
    Description         nvarchar(1000)   NULL,
    IconUrl             nvarchar(500)    NULL,
    ReferencePriceMin   decimal(18, 2)   NULL,
    ReferencePriceMax   decimal(18, 2)   NULL,
    CommissionRate      decimal(5, 2)    NULL,               -- NULL = commission.default_rate
    RequiresCertificate bit              NOT NULL CONSTRAINT DF_ServiceCategories_RequiresCertificate DEFAULT 0,
    DisplayOrder        int              NOT NULL CONSTRAINT DF_ServiceCategories_DisplayOrder DEFAULT 0,
    IsActive            bit              NOT NULL CONSTRAINT DF_ServiceCategories_IsActive DEFAULT 1,
    CreatedAt           datetimeoffset   NOT NULL CONSTRAINT DF_ServiceCategories_CreatedAt DEFAULT SYSUTCDATETIME(),
    UpdatedAt           datetimeoffset   NOT NULL CONSTRAINT DF_ServiceCategories_UpdatedAt DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_ServiceCategories_Slug UNIQUE (Slug),
    CONSTRAINT CK_ServiceCategories_CommissionRate CHECK (CommissionRate IS NULL OR CommissionRate BETWEEN 0 AND 100)
);
CREATE INDEX IX_ServiceCategories_ParentId ON dbo.ServiceCategories (ParentId, DisplayOrder);

CREATE TABLE dbo.PartnerSkills (
    Id                uniqueidentifier NOT NULL CONSTRAINT PK_PartnerSkills PRIMARY KEY,
    PartnerProfileId  uniqueidentifier NOT NULL CONSTRAINT FK_PartnerSkills_PartnerProfiles REFERENCES dbo.PartnerProfiles (Id),
    ServiceCategoryId uniqueidentifier NOT NULL CONSTRAINT FK_PartnerSkills_ServiceCategories REFERENCES dbo.ServiceCategories (Id),
    YearsOfExperience int              NOT NULL CONSTRAINT DF_PartnerSkills_YearsOfExperience DEFAULT 0,
    Status            tinyint          NOT NULL CONSTRAINT DF_PartnerSkills_Status DEFAULT 1, -- 1=PENDING, 2=APPROVED, 3=REJECTED
    CertificateUrl    nvarchar(500)    NULL,
    CreatedAt         datetimeoffset   NOT NULL CONSTRAINT DF_PartnerSkills_CreatedAt DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_PartnerSkills_Partner_Category UNIQUE (PartnerProfileId, ServiceCategoryId)
);

CREATE TABLE dbo.Addresses (
    Id            uniqueidentifier NOT NULL CONSTRAINT PK_Addresses PRIMARY KEY,
    UserId        uniqueidentifier NOT NULL CONSTRAINT FK_Addresses_Users REFERENCES dbo.Users (Id),
    Label         nvarchar(50)     NOT NULL,
    ReceiverName  nvarchar(100)    NOT NULL,
    ReceiverPhone nvarchar(15)     NOT NULL,
    FullAddress   nvarchar(500)    NOT NULL,
    Latitude      decimal(9, 6)    NOT NULL,
    Longitude     decimal(9, 6)    NOT NULL,
    Note          nvarchar(300)    NULL,
    IsDefault     bit              NOT NULL CONSTRAINT DF_Addresses_IsDefault DEFAULT 0,
    CreatedAt     datetimeoffset   NOT NULL CONSTRAINT DF_Addresses_CreatedAt DEFAULT SYSUTCDATETIME(),
    UpdatedAt     datetimeoffset   NOT NULL CONSTRAINT DF_Addresses_UpdatedAt DEFAULT SYSUTCDATETIME(),
    DeletedAt     datetimeoffset   NULL,                     -- soft delete; max 10 live addresses per user
    CONSTRAINT CK_Addresses_Latitude CHECK (Latitude BETWEEN -90 AND 90),
    CONSTRAINT CK_Addresses_Longitude CHECK (Longitude BETWEEN -180 AND 180)
);
CREATE INDEX IX_Addresses_UserId ON dbo.Addresses (UserId) WHERE DeletedAt IS NULL;
-- (UserId, IsDefault) instead of (UserId) so EF scaffold keeps User 1:N Addresses
CREATE UNIQUE INDEX UX_Addresses_User_Default ON dbo.Addresses (UserId, IsDefault) WHERE IsDefault = 1 AND DeletedAt IS NULL;

CREATE TABLE dbo.UserDevices (
    Id           uniqueidentifier NOT NULL CONSTRAINT PK_UserDevices PRIMARY KEY,
    UserId       uniqueidentifier NOT NULL CONSTRAINT FK_UserDevices_Users REFERENCES dbo.Users (Id),
    DeviceId     nvarchar(200)    NOT NULL,                  -- app-installation UUID generated by the client
    FcmToken     nvarchar(500)    NULL,
    Platform     tinyint          NOT NULL CONSTRAINT DF_UserDevices_Platform DEFAULT 1, -- 1=ANDROID, 2=IOS, 3=WEB
    AppVersion   nvarchar(20)     NULL,
    AppFlavor    tinyint          NOT NULL,                  -- 1=CUSTOMER, 2=PARTNER
    IsActive     bit              NOT NULL CONSTRAINT DF_UserDevices_IsActive DEFAULT 1,
    LastActiveAt datetimeoffset   NOT NULL CONSTRAINT DF_UserDevices_LastActiveAt DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_UserDevices_User_Device_Flavor UNIQUE (UserId, DeviceId, AppFlavor)
);

CREATE TABLE dbo.RefreshTokens (
    Id                uniqueidentifier NOT NULL CONSTRAINT PK_RefreshTokens PRIMARY KEY,
    UserId            uniqueidentifier NOT NULL CONSTRAINT FK_RefreshTokens_Users REFERENCES dbo.Users (Id),
    TokenHash         nvarchar(256)    NOT NULL,             -- SHA-256 hex of the token
    SessionId         uniqueidentifier NOT NULL,             -- groups rotated tokens of one login
    DeviceId          nvarchar(200)    NOT NULL,
    AppFlavor         tinyint          NOT NULL,             -- 1=CUSTOMER, 2=PARTNER
    ExpiresAt         datetimeoffset   NOT NULL,
    RevokedAt         datetimeoffset   NULL,
    ReplacedByTokenId uniqueidentifier NULL,
    CreatedAt         datetimeoffset   NOT NULL CONSTRAINT DF_RefreshTokens_CreatedAt DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_RefreshTokens_TokenHash UNIQUE (TokenHash)
);
CREATE INDEX IX_RefreshTokens_Session ON dbo.RefreshTokens (UserId, SessionId);

CREATE TABLE dbo.OtpCodes (
    Id           uniqueidentifier NOT NULL CONSTRAINT PK_OtpCodes PRIMARY KEY,
    PhoneNumber  nvarchar(15)     NOT NULL,
    CodeHash     nvarchar(256)    NOT NULL,
    Purpose      tinyint          NOT NULL,                  -- 1=REGISTER, 2=LOGIN, 3=RESET_PASSWORD, 4=VERIFY_PHONE
    AppFlavor    tinyint          NOT NULL,                  -- 1=CUSTOMER, 2=PARTNER
    ExpiresAt    datetimeoffset   NOT NULL,
    AttemptCount int              NOT NULL CONSTRAINT DF_OtpCodes_AttemptCount DEFAULT 0,
    IsUsed       bit              NOT NULL CONSTRAINT DF_OtpCodes_IsUsed DEFAULT 0,
    ConsumedAt   datetimeoffset   NULL,
    CreatedAt    datetimeoffset   NOT NULL CONSTRAINT DF_OtpCodes_CreatedAt DEFAULT SYSUTCDATETIME()
);
CREATE INDEX IX_OtpCodes_Phone_CreatedAt ON dbo.OtpCodes (PhoneNumber, CreatedAt DESC);

/* ================================ System ================================== */

CREATE TABLE dbo.UploadedFiles (
    Id          uniqueidentifier NOT NULL CONSTRAINT PK_UploadedFiles PRIMARY KEY,
    OwnerUserId uniqueidentifier NOT NULL CONSTRAINT FK_UploadedFiles_Users REFERENCES dbo.Users (Id),
    Purpose     tinyint          NOT NULL,                   -- 1=POST_IMAGE, 3=AVATAR, 4=KYC, 5=ORDER_IMAGE, 6=CHAT_IMAGE, 7=REVIEW_IMAGE, 8=DISPUTE_EVIDENCE, 9=CERTIFICATE
    ObjectKey   nvarchar(500)    NOT NULL,                   -- relative path on disk
    MimeType    varchar(100)     NOT NULL,
    SizeBytes   bigint           NOT NULL,
    Width       int              NULL,
    Height      int              NULL,
    Status      tinyint          NOT NULL CONSTRAINT DF_UploadedFiles_Status DEFAULT 3, -- 3=READY, 4=REJECTED
    IsPrivate   bit              NOT NULL CONSTRAINT DF_UploadedFiles_IsPrivate DEFAULT 0,
    CreatedAt   datetimeoffset   NOT NULL CONSTRAINT DF_UploadedFiles_CreatedAt DEFAULT SYSUTCDATETIME()
);

CREATE TABLE dbo.SystemConfigs (
    [Key]            varchar(100)     NOT NULL CONSTRAINT PK_SystemConfigs PRIMARY KEY,
    Value            nvarchar(max)    NOT NULL,
    DataType         varchar(20)      NOT NULL,              -- int, decimal, bool, json, string
    [Group]          varchar(50)      NOT NULL,
    Description      nvarchar(500)    NULL,
    UpdatedByAdminId uniqueidentifier NULL CONSTRAINT FK_SystemConfigs_AdminUsers REFERENCES dbo.AdminUsers (Id),
    UpdatedAt        datetimeoffset   NOT NULL CONSTRAINT DF_SystemConfigs_UpdatedAt DEFAULT SYSUTCDATETIME()
);

CREATE TABLE dbo.AuditLogs (
    Id         uniqueidentifier NOT NULL CONSTRAINT PK_AuditLogs PRIMARY KEY,
    ActorId    uniqueidentifier NULL,
    ActorType  tinyint          NOT NULL,                    -- 1=CUSTOMER, 2=PARTNER, 3=SYSTEM, 4=ADMIN
    Action     varchar(100)     NOT NULL,                    -- e.g. PARTNER_KYC_APPROVED
    EntityType varchar(100)     NOT NULL,
    EntityId   nvarchar(100)    NULL,
    OldValues  nvarchar(max)    NULL,                        -- JSON
    NewValues  nvarchar(max)    NULL,                        -- JSON
    IpAddress  varchar(50)      NULL,
    UserAgent  nvarchar(300)    NULL,
    CreatedAt  datetimeoffset   NOT NULL CONSTRAINT DF_AuditLogs_CreatedAt DEFAULT SYSUTCDATETIME()
);
CREATE INDEX IX_AuditLogs_Entity ON dbo.AuditLogs (EntityType, EntityId, CreatedAt DESC);

CREATE TABLE dbo.Notifications (
    Id          uniqueidentifier NOT NULL CONSTRAINT PK_Notifications PRIMARY KEY,
    UserId      uniqueidentifier NOT NULL CONSTRAINT FK_Notifications_Users REFERENCES dbo.Users (Id),
    Type        tinyint          NOT NULL,                   -- 1=NEW_POST_MATCH, 2=NEW_QUOTE, 3=QUOTE_ACCEPTED, 4=ORDER_STATUS_CHANGED, 5=NEW_MESSAGE, 6=PAYMENT, 7=REVIEW, 8=KYC_RESULT, 10=SYSTEM
    Title       nvarchar(200)    NOT NULL,
    Body        nvarchar(500)    NOT NULL,
    ImageUrl    nvarchar(500)    NULL,
    DataPayload nvarchar(max)    NULL,                       -- JSON {"orderId":"..."}
    AppFlavor   tinyint          NOT NULL,                   -- 1=CUSTOMER, 2=PARTNER
    DeepLink    nvarchar(300)    NULL,
    IsRead      bit              NOT NULL CONSTRAINT DF_Notifications_IsRead DEFAULT 0,
    ReadAt      datetimeoffset   NULL,
    SentViaPush bit              NOT NULL CONSTRAINT DF_Notifications_SentViaPush DEFAULT 0,
    CreatedAt   datetimeoffset   NOT NULL CONSTRAINT DF_Notifications_CreatedAt DEFAULT SYSUTCDATETIME()
);
CREATE INDEX IX_Notifications_User_Flavor ON dbo.Notifications (UserId, AppFlavor, IsRead, CreatedAt DESC);

/* ================================ Booking ================================= */

CREATE TABLE dbo.ServiceRequests (
    Id                     uniqueidentifier NOT NULL CONSTRAINT PK_ServiceRequests PRIMARY KEY,
    Code                   varchar(20)      NOT NULL,
    CustomerId             uniqueidentifier NOT NULL CONSTRAINT FK_ServiceRequests_CustomerProfiles REFERENCES dbo.CustomerProfiles (Id),
    ServiceCategoryId      uniqueidentifier NOT NULL CONSTRAINT FK_ServiceRequests_ServiceCategories REFERENCES dbo.ServiceCategories (Id), -- level-2 category
    Title                  nvarchar(150)    NOT NULL,
    Description            nvarchar(2000)   NOT NULL,
    AddressId              uniqueidentifier NOT NULL CONSTRAINT FK_ServiceRequests_Addresses REFERENCES dbo.Addresses (Id),
    AddressSnapshot        nvarchar(500)    NOT NULL,
    Latitude               decimal(9, 6)    NOT NULL,
    Longitude              decimal(9, 6)    NOT NULL,
    ScheduleType           tinyint          NOT NULL,        -- 1=NOW, 2=SCHEDULED
    ScheduledStartAt       datetimeoffset   NULL,
    ScheduledEndAt         datetimeoffset   NULL,
    BudgetMin              decimal(18, 2)   NULL,
    BudgetMax              decimal(18, 2)   NULL,
    RequireExperienceYears int              NULL,
    RequireCertificate     bit              NOT NULL CONSTRAINT DF_ServiceRequests_RequireCertificate DEFAULT 0,
    RequireMinRating       decimal(3, 2)    NULL,
    SearchRadiusKm         int              NOT NULL CONSTRAINT DF_ServiceRequests_SearchRadiusKm DEFAULT 10,
    Status                 tinyint          NOT NULL,        -- 1=DRAFT, 2=OPEN, 3=MATCHED, 4=EXPIRED, 5=CANCELLED, 6=REJECTED_BY_MODERATION
    QuoteCount             int              NOT NULL CONSTRAINT DF_ServiceRequests_QuoteCount DEFAULT 0,
    ViewCount              int              NOT NULL CONSTRAINT DF_ServiceRequests_ViewCount DEFAULT 0,
    Revision               int              NOT NULL CONSTRAINT DF_ServiceRequests_Revision DEFAULT 1, -- always 1 in the course scope
    ExpiresAt              datetimeoffset   NULL,
    PublishedAt            datetimeoffset   NULL,
    ModerationNote         nvarchar(500)    NULL,
    CancelReason           nvarchar(500)    NULL,
    CreatedAt              datetimeoffset   NOT NULL CONSTRAINT DF_ServiceRequests_CreatedAt DEFAULT SYSUTCDATETIME(),
    UpdatedAt              datetimeoffset   NOT NULL CONSTRAINT DF_ServiceRequests_UpdatedAt DEFAULT SYSUTCDATETIME(),
    RowVersion             rowversion       NOT NULL,
    CONSTRAINT UQ_ServiceRequests_Code UNIQUE (Code),
    CONSTRAINT CK_ServiceRequests_SearchRadiusKm CHECK (SearchRadiusKm BETWEEN 3 AND 20),
    CONSTRAINT CK_ServiceRequests_Budget CHECK (BudgetMin IS NULL OR BudgetMax IS NULL OR BudgetMin <= BudgetMax)
);
CREATE INDEX IX_ServiceRequests_Status_Category ON dbo.ServiceRequests (Status, ServiceCategoryId, CreatedAt DESC);
CREATE INDEX IX_ServiceRequests_Customer ON dbo.ServiceRequests (CustomerId, CreatedAt DESC);

CREATE TABLE dbo.ServiceRequestImages (
    Id               uniqueidentifier NOT NULL CONSTRAINT PK_ServiceRequestImages PRIMARY KEY,
    ServiceRequestId uniqueidentifier NOT NULL CONSTRAINT FK_ServiceRequestImages_ServiceRequests REFERENCES dbo.ServiceRequests (Id),
    Url              nvarchar(500)    NOT NULL,
    ThumbnailUrl     nvarchar(500)    NULL,
    MediaType        tinyint          NOT NULL CONSTRAINT DF_ServiceRequestImages_MediaType DEFAULT 1, -- 1=IMAGE
    DisplayOrder     int              NOT NULL CONSTRAINT DF_ServiceRequestImages_DisplayOrder DEFAULT 0
);
CREATE INDEX IX_ServiceRequestImages_Request ON dbo.ServiceRequestImages (ServiceRequestId, DisplayOrder);

CREATE TABLE dbo.Quotes (
    Id                       uniqueidentifier NOT NULL CONSTRAINT PK_Quotes PRIMARY KEY,
    ServiceRequestId         uniqueidentifier NOT NULL CONSTRAINT FK_Quotes_ServiceRequests REFERENCES dbo.ServiceRequests (Id),
    PartnerProfileId         uniqueidentifier NOT NULL CONSTRAINT FK_Quotes_PartnerProfiles REFERENCES dbo.PartnerProfiles (Id),
    Amount                   decimal(18, 2)   NOT NULL,
    EstimatedDurationMinutes int              NOT NULL,
    AvailableFrom            datetimeoffset   NOT NULL,
    EstimatedEndAt           datetimeoffset   NOT NULL,      -- AvailableFrom + EstimatedDurationMinutes
    Note                     nvarchar(500)    NULL,
    Status                   tinyint          NOT NULL CONSTRAINT DF_Quotes_Status DEFAULT 1, -- 1=PENDING, 2=ACCEPTED, 3=REJECTED, 4=WITHDRAWN, 5=EXPIRED
    RequestRevision          int              NOT NULL CONSTRAINT DF_Quotes_RequestRevision DEFAULT 1,
    RespondedAt              datetimeoffset   NULL,
    CreatedAt                datetimeoffset   NOT NULL CONSTRAINT DF_Quotes_CreatedAt DEFAULT SYSUTCDATETIME(),
    UpdatedAt                datetimeoffset   NOT NULL CONSTRAINT DF_Quotes_UpdatedAt DEFAULT SYSUTCDATETIME(),
    RowVersion               rowversion       NOT NULL,
    CONSTRAINT UQ_Quotes_Request_Partner_Revision UNIQUE (ServiceRequestId, PartnerProfileId, RequestRevision),
    CONSTRAINT CK_Quotes_Amount CHECK (Amount > 0)
);
CREATE INDEX IX_Quotes_Partner_Status ON dbo.Quotes (PartnerProfileId, Status);

CREATE TABLE dbo.Orders (
    Id                     uniqueidentifier NOT NULL CONSTRAINT PK_Orders PRIMARY KEY,
    Code                   varchar(20)      NOT NULL,
    ServiceRequestId       uniqueidentifier NOT NULL CONSTRAINT FK_Orders_ServiceRequests REFERENCES dbo.ServiceRequests (Id),
    CustomerId             uniqueidentifier NOT NULL CONSTRAINT FK_Orders_CustomerProfiles REFERENCES dbo.CustomerProfiles (Id),
    ServiceCategoryId      uniqueidentifier NOT NULL CONSTRAINT FK_Orders_ServiceCategories REFERENCES dbo.ServiceCategories (Id),
    Status                 tinyint          NOT NULL,        -- 1=PENDING, 2=ACCEPTED, 3=ON_THE_WAY, 4=ARRIVED, 5=IN_PROGRESS, 6=COMPLETED_BY_PARTNER, 7=AWAITING_PAYMENT, 8=COMPLETED, 9=CANCELLED, 10=DISPUTED
    SubTotal               decimal(18, 2)   NOT NULL,        -- accepted quote amount
    ExtraChargeTotal       decimal(18, 2)   NOT NULL CONSTRAINT DF_Orders_ExtraChargeTotal DEFAULT 0,
    TotalAmount            decimal(18, 2)   NOT NULL,        -- SubTotal + ExtraChargeTotal
    CommissionAmount       decimal(18, 2)   NOT NULL CONSTRAINT DF_Orders_CommissionAmount DEFAULT 0,
    PartnerEarning         decimal(18, 2)   NOT NULL CONSTRAINT DF_Orders_PartnerEarning DEFAULT 0,
    PaymentMethod          tinyint          NOT NULL CONSTRAINT DF_Orders_PaymentMethod DEFAULT 1, -- 1=CASH
    PaymentStatus          tinyint          NOT NULL CONSTRAINT DF_Orders_PaymentStatus DEFAULT 1, -- 1=UNPAID, 3=PAID
    ScheduledStartAt       datetimeoffset   NULL,
    AcceptedAt             datetimeoffset   NULL,
    StartedAt              datetimeoffset   NULL,
    CompletedByPartnerAt   datetimeoffset   NULL,
    AutoConfirmAt          datetimeoffset   NULL,
    ConfirmedAt            datetimeoffset   NULL,
    CompletedAt            datetimeoffset   NULL,            -- written once
    DisputeDeadlineAt      datetimeoffset   NULL,            -- CompletedAt + 72h
    UnpaidReportDeadlineAt datetimeoffset   NULL,            -- CompletedAt + 24h
    CancelledAt            datetimeoffset   NULL,
    CancelledBy            tinyint          NULL,            -- 1=CUSTOMER, 2=PARTNER, 3=SYSTEM, 4=ADMIN
    CancelReason           nvarchar(500)    NULL,
    CancellationFee        decimal(18, 2)   NOT NULL CONSTRAINT DF_Orders_CancellationFee DEFAULT 0, -- projection of CancellationCharges
    CreatedAt              datetimeoffset   NOT NULL CONSTRAINT DF_Orders_CreatedAt DEFAULT SYSUTCDATETIME(),
    UpdatedAt              datetimeoffset   NOT NULL CONSTRAINT DF_Orders_UpdatedAt DEFAULT SYSUTCDATETIME(),
    RowVersion             rowversion       NOT NULL,
    CONSTRAINT UQ_Orders_Code UNIQUE (Code),
    CONSTRAINT UQ_Orders_ServiceRequestId UNIQUE (ServiceRequestId)
);
CREATE INDEX IX_Orders_Customer_Status ON dbo.Orders (CustomerId, Status);
CREATE INDEX IX_Orders_Status_CreatedAt ON dbo.Orders (Status, CreatedAt DESC);

CREATE TABLE dbo.OrderAssignments (
    Id                         uniqueidentifier NOT NULL CONSTRAINT PK_OrderAssignments PRIMARY KEY,
    OrderId                    uniqueidentifier NOT NULL CONSTRAINT FK_OrderAssignments_Orders REFERENCES dbo.Orders (Id),
    PartnerProfileId           uniqueidentifier NOT NULL CONSTRAINT FK_OrderAssignments_PartnerProfiles REFERENCES dbo.PartnerProfiles (Id),
    QuoteId                    uniqueidentifier NULL CONSTRAINT FK_OrderAssignments_Quotes REFERENCES dbo.Quotes (Id),
    Status                     tinyint          NOT NULL,    -- same codes as Orders.Status (no DISPUTED)
    IsPrimary                  bit              NOT NULL CONSTRAINT DF_OrderAssignments_IsPrimary DEFAULT 1,
    Amount                     decimal(18, 2)   NOT NULL,    -- labour price from the quote
    ExtraChargeTotal           decimal(18, 2)   NOT NULL CONSTRAINT DF_OrderAssignments_ExtraChargeTotal DEFAULT 0,
    CommissionRateSnapshot     decimal(5, 2)    NOT NULL,
    CommissionAmount           decimal(18, 2)   NOT NULL CONSTRAINT DF_OrderAssignments_CommissionAmount DEFAULT 0,
    Earning                    decimal(18, 2)   NOT NULL CONSTRAINT DF_OrderAssignments_Earning DEFAULT 0,
    CustomerPayable            decimal(18, 2)   NOT NULL CONSTRAINT DF_OrderAssignments_CustomerPayable DEFAULT 0,
    PaymentStatus              tinyint          NOT NULL CONSTRAINT DF_OrderAssignments_PaymentStatus DEFAULT 1,
    AcceptanceDeadlineAt       datetimeoffset   NOT NULL,    -- CreatedAt + 10 minutes
    ReservedStartAt            datetimeoffset   NULL,
    ReservedEndAt              datetimeoffset   NULL,
    AcceptedAt                 datetimeoffset   NULL,
    OnTheWayAt                 datetimeoffset   NULL,
    ArrivedAt                  datetimeoffset   NULL,
    StartedAt                  datetimeoffset   NULL,
    CompletedAt                datetimeoffset   NULL,
    PaidAt                     datetimeoffset   NULL,
    ArrivalMethod              tinyint          NULL,        -- 1=GPS, 2=CUSTOMER
    ArrivalLatitude            decimal(9, 6)    NULL,
    ArrivalLongitude           decimal(9, 6)    NULL,
    ArrivalDistanceMeters      int              NULL,
    ArrivalAccuracyMeters      int              NULL,
    CustomerConfirmedArrivalAt datetimeoffset   NULL,
    LastLatitude               decimal(9, 6)    NULL,        -- latest point from hub UpdateLocation (course scope)
    LastLongitude              decimal(9, 6)    NULL,
    LastLocationAccuracy       int              NULL,
    LastLocationAt             datetimeoffset   NULL,
    CancelledAt                datetimeoffset   NULL,
    CancelledBy                tinyint          NULL,        -- 1=CUSTOMER, 2=PARTNER, 3=SYSTEM, 4=ADMIN
    CancelReason               nvarchar(500)    NULL,
    CreatedAt                  datetimeoffset   NOT NULL CONSTRAINT DF_OrderAssignments_CreatedAt DEFAULT SYSUTCDATETIME(),
    UpdatedAt                  datetimeoffset   NOT NULL CONSTRAINT DF_OrderAssignments_UpdatedAt DEFAULT SYSUTCDATETIME(),
    RowVersion                 rowversion       NOT NULL,
    CONSTRAINT UQ_OrderAssignments_Order_Partner UNIQUE (OrderId, PartnerProfileId),
    CONSTRAINT CK_OrderAssignments_CommissionRate CHECK (CommissionRateSnapshot BETWEEN 0 AND 100)
);
CREATE UNIQUE INDEX UX_OrderAssignments_QuoteId ON dbo.OrderAssignments (QuoteId) WHERE QuoteId IS NOT NULL;
CREATE INDEX IX_OrderAssignments_Partner_Status ON dbo.OrderAssignments (PartnerProfileId, Status);

CREATE TABLE dbo.OrderStatusHistories (
    Id                uniqueidentifier NOT NULL CONSTRAINT PK_OrderStatusHistories PRIMARY KEY,
    OrderId           uniqueidentifier NOT NULL CONSTRAINT FK_OrderStatusHistories_Orders REFERENCES dbo.Orders (Id),
    OrderAssignmentId uniqueidentifier NULL CONSTRAINT FK_OrderStatusHistories_OrderAssignments REFERENCES dbo.OrderAssignments (Id),
    FromStatus        tinyint          NULL,
    ToStatus          tinyint          NOT NULL,
    ChangedByType     tinyint          NOT NULL,             -- 1=CUSTOMER, 2=PARTNER, 3=SYSTEM, 4=ADMIN
    ChangedByUserId   uniqueidentifier NULL CONSTRAINT FK_OrderStatusHistories_Users REFERENCES dbo.Users (Id),
    ChangedByAdminId  uniqueidentifier NULL CONSTRAINT FK_OrderStatusHistories_AdminUsers REFERENCES dbo.AdminUsers (Id),
    Note              nvarchar(500)    NULL,
    Latitude          decimal(9, 6)    NULL,
    Longitude         decimal(9, 6)    NULL,
    CreatedAt         datetimeoffset   NOT NULL CONSTRAINT DF_OrderStatusHistories_CreatedAt DEFAULT SYSUTCDATETIME()
);
CREATE INDEX IX_OrderStatusHistories_Order ON dbo.OrderStatusHistories (OrderId, CreatedAt, Id);

CREATE TABLE dbo.OrderExtraCharges (
    Id                uniqueidentifier NOT NULL CONSTRAINT PK_OrderExtraCharges PRIMARY KEY,
    OrderId           uniqueidentifier NOT NULL CONSTRAINT FK_OrderExtraCharges_Orders REFERENCES dbo.Orders (Id),
    OrderAssignmentId uniqueidentifier NOT NULL CONSTRAINT FK_OrderExtraCharges_OrderAssignments REFERENCES dbo.OrderAssignments (Id),
    Description       nvarchar(300)    NOT NULL,
    Amount            decimal(18, 2)   NOT NULL,
    EvidenceImageUrl  nvarchar(500)    NULL,
    Status            tinyint          NOT NULL CONSTRAINT DF_OrderExtraCharges_Status DEFAULT 1, -- 1=PENDING, 2=APPROVED, 3=REJECTED
    RespondedAt       datetimeoffset   NULL,
    RespondedByUserId uniqueidentifier NULL CONSTRAINT FK_OrderExtraCharges_Users REFERENCES dbo.Users (Id),
    ResponseNote      nvarchar(500)    NULL,
    CreatedAt         datetimeoffset   NOT NULL CONSTRAINT DF_OrderExtraCharges_CreatedAt DEFAULT SYSUTCDATETIME(),
    RowVersion        rowversion       NOT NULL,
    CONSTRAINT CK_OrderExtraCharges_Amount CHECK (Amount > 0)
);
CREATE INDEX IX_OrderExtraCharges_Order ON dbo.OrderExtraCharges (OrderId);

CREATE TABLE dbo.OrderImages (
    Id                uniqueidentifier NOT NULL CONSTRAINT PK_OrderImages PRIMARY KEY,
    OrderAssignmentId uniqueidentifier NOT NULL CONSTRAINT FK_OrderImages_OrderAssignments REFERENCES dbo.OrderAssignments (Id),
    Url               nvarchar(500)    NOT NULL,
    Type              tinyint          NOT NULL,             -- 1=BEFORE, 2=AFTER, 3=ISSUE
    Latitude          decimal(9, 6)    NULL,
    Longitude         decimal(9, 6)    NULL,
    CreatedAt         datetimeoffset   NOT NULL CONSTRAINT DF_OrderImages_CreatedAt DEFAULT SYSUTCDATETIME()
);
CREATE INDEX IX_OrderImages_Assignment ON dbo.OrderImages (OrderAssignmentId, Type);

CREATE TABLE dbo.ArrivalConfirmationRequests (
    Id                uniqueidentifier NOT NULL CONSTRAINT PK_ArrivalConfirmationRequests PRIMARY KEY,
    OrderAssignmentId uniqueidentifier NOT NULL CONSTRAINT FK_ArrivalConfirmationRequests_OrderAssignments REFERENCES dbo.OrderAssignments (Id),
    RequestedAt       datetimeoffset   NOT NULL,
    ExpiresAt         datetimeoffset   NOT NULL,             -- RequestedAt + 300s
    Status            tinyint          NOT NULL CONSTRAINT DF_ArrivalConfirmationRequests_Status DEFAULT 1, -- 1=PENDING, 2=CONFIRMED, 3=DENIED, 4=EXPIRED, 5=CANCELLED
    RespondedAt       datetimeoffset   NULL
);
CREATE UNIQUE INDEX UX_ArrivalConfirmationRequests_Pending ON dbo.ArrivalConfirmationRequests (OrderAssignmentId, Status) WHERE Status = 1;

CREATE TABLE dbo.CancellationCharges (
    Id                uniqueidentifier NOT NULL CONSTRAINT PK_CancellationCharges PRIMARY KEY,
    OrderId           uniqueidentifier NOT NULL CONSTRAINT FK_CancellationCharges_Orders REFERENCES dbo.Orders (Id),
    OrderAssignmentId uniqueidentifier NOT NULL CONSTRAINT FK_CancellationCharges_OrderAssignments REFERENCES dbo.OrderAssignments (Id),
    CustomerId        uniqueidentifier NOT NULL CONSTRAINT FK_CancellationCharges_CustomerProfiles REFERENCES dbo.CustomerProfiles (Id),
    PartnerProfileId  uniqueidentifier NOT NULL CONSTRAINT FK_CancellationCharges_PartnerProfiles REFERENCES dbo.PartnerProfiles (Id),
    Amount            decimal(18, 2)   NOT NULL,
    Reason            tinyint          NOT NULL,             -- 1=CUSTOMER_CANCEL, 2=CUSTOMER_NO_SHOW
    Status            tinyint          NOT NULL CONSTRAINT DF_CancellationCharges_Status DEFAULT 1, -- 1=DUE, 2=PAID, 3=WAIVED
    CollectedTransactionId uniqueidentifier NULL,            -- FK added after Transactions
    CompensationCreditedAt datetimeoffset NULL,              -- partner credited once after the fee is collected
    CreatedAt         datetimeoffset   NOT NULL CONSTRAINT DF_CancellationCharges_CreatedAt DEFAULT SYSUTCDATETIME(),
    UpdatedAt         datetimeoffset   NOT NULL CONSTRAINT DF_CancellationCharges_UpdatedAt DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_CancellationCharges_Assignment UNIQUE (OrderAssignmentId),
    CONSTRAINT CK_CancellationCharges_Amount CHECK (Amount > 0)
);
CREATE INDEX IX_CancellationCharges_Customer_Status ON dbo.CancellationCharges (CustomerId, Status);

/* ================================== Chat ================================== */

CREATE TABLE dbo.Conversations (
    Id                  uniqueidentifier NOT NULL CONSTRAINT PK_Conversations PRIMARY KEY,
    ServiceRequestId    uniqueidentifier NOT NULL CONSTRAINT FK_Conversations_ServiceRequests REFERENCES dbo.ServiceRequests (Id),
    OrderId             uniqueidentifier NULL CONSTRAINT FK_Conversations_Orders REFERENCES dbo.Orders (Id),
    CustomerId          uniqueidentifier NOT NULL CONSTRAINT FK_Conversations_CustomerProfiles REFERENCES dbo.CustomerProfiles (Id),
    PartnerProfileId    uniqueidentifier NOT NULL CONSTRAINT FK_Conversations_PartnerProfiles REFERENCES dbo.PartnerProfiles (Id),
    LastMessagePreview  nvarchar(200)    NULL,
    LastMessageAt       datetimeoffset   NULL,
    LastMessageSenderId uniqueidentifier NULL,
    CustomerUnreadCount int              NOT NULL CONSTRAINT DF_Conversations_CustomerUnreadCount DEFAULT 0,
    PartnerUnreadCount  int              NOT NULL CONSTRAINT DF_Conversations_PartnerUnreadCount DEFAULT 0,
    Status              tinyint          NOT NULL CONSTRAINT DF_Conversations_Status DEFAULT 1, -- 1=ACTIVE, 2=ARCHIVED, 3=READ_ONLY, 4=BLOCKED
    CreatedAt           datetimeoffset   NOT NULL CONSTRAINT DF_Conversations_CreatedAt DEFAULT SYSUTCDATETIME(),
    UpdatedAt           datetimeoffset   NOT NULL CONSTRAINT DF_Conversations_UpdatedAt DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_Conversations_Request_Partner UNIQUE (ServiceRequestId, PartnerProfileId)
);
CREATE INDEX IX_Conversations_Customer ON dbo.Conversations (CustomerId, LastMessageAt DESC);
CREATE INDEX IX_Conversations_Partner ON dbo.Conversations (PartnerProfileId, LastMessageAt DESC);

CREATE TABLE dbo.Messages (
    Id              uniqueidentifier NOT NULL CONSTRAINT PK_Messages PRIMARY KEY,
    ConversationId  uniqueidentifier NOT NULL CONSTRAINT FK_Messages_Conversations REFERENCES dbo.Conversations (Id),
    SenderUserId    uniqueidentifier NULL CONSTRAINT FK_Messages_Users REFERENCES dbo.Users (Id), -- NULL when Type=SYSTEM
    ClientMessageId uniqueidentifier NOT NULL,               -- generated by the client for dedupe and ack
    Type            tinyint          NOT NULL,               -- 1=TEXT, 2=IMAGE, 3=SYSTEM
    Content         nvarchar(2000)   NOT NULL,
    AttachmentUrl   nvarchar(500)    NULL,
    CreatedAt       datetimeoffset   NOT NULL CONSTRAINT DF_Messages_CreatedAt DEFAULT SYSUTCDATETIME(),
    ReadAt          datetimeoffset   NULL,
    CONSTRAINT UQ_Messages_Client UNIQUE (ConversationId, SenderUserId, ClientMessageId)
);
CREATE INDEX IX_Messages_Conversation ON dbo.Messages (ConversationId, CreatedAt, Id);

/* ================================ Payment ================================= */

CREATE TABLE dbo.Wallets (
    Id               uniqueidentifier NOT NULL CONSTRAINT PK_Wallets PRIMARY KEY,
    UserId           uniqueidentifier NOT NULL CONSTRAINT FK_Wallets_Users REFERENCES dbo.Users (Id),
    AvailableBalance decimal(18, 2)   NOT NULL CONSTRAINT DF_Wallets_AvailableBalance DEFAULT 0,
    DebtBalance      decimal(18, 2)   NOT NULL CONSTRAINT DF_Wallets_DebtBalance DEFAULT 0, -- partner commission debt (COD)
    Currency         char(3)          NOT NULL CONSTRAINT DF_Wallets_Currency DEFAULT 'VND',
    IsLocked         bit              NOT NULL CONSTRAINT DF_Wallets_IsLocked DEFAULT 0,
    UpdatedAt        datetimeoffset   NOT NULL CONSTRAINT DF_Wallets_UpdatedAt DEFAULT SYSUTCDATETIME(),
    RowVersion       rowversion       NOT NULL,
    CONSTRAINT UQ_Wallets_UserId UNIQUE (UserId),
    CONSTRAINT CK_Wallets_Balances CHECK (AvailableBalance >= 0 AND DebtBalance >= 0)
);

CREATE TABLE dbo.Transactions (
    Id                    uniqueidentifier NOT NULL CONSTRAINT PK_Transactions PRIMARY KEY,
    Code                  varchar(30)      NOT NULL,
    OrderId               uniqueidentifier NULL CONSTRAINT FK_Transactions_Orders REFERENCES dbo.Orders (Id),
    UserId                uniqueidentifier NOT NULL CONSTRAINT FK_Transactions_Users REFERENCES dbo.Users (Id),
    Type                  tinyint          NOT NULL,         -- 1=PAYMENT, 5=DEBT_PAYMENT
    Provider              tinyint          NOT NULL,         -- 1=CASH, 6=BANK_TRANSFER, 7=INTERNAL_OFFSET
    Amount                decimal(18, 2)   NOT NULL,
    Currency              char(3)          NOT NULL CONSTRAINT DF_Transactions_Currency DEFAULT 'VND',
    Status                tinyint          NOT NULL,         -- 1=PENDING, 3=SUCCESS, 4=FAILED, 5=CANCELLED
    IsPresumed            bit              NOT NULL CONSTRAINT DF_Transactions_IsPresumed DEFAULT 0, -- 1 = COD presumed collected
    BankTransferReference nvarchar(100)    NULL,
    EvidenceFileId        uniqueidentifier NULL CONSTRAINT FK_Transactions_UploadedFiles REFERENCES dbo.UploadedFiles (Id),
    ConfirmedByAdminId    uniqueidentifier NULL CONSTRAINT FK_Transactions_AdminUsers REFERENCES dbo.AdminUsers (Id),
    IdempotencyKey        nvarchar(100)    NULL,
    BusinessEventKey      nvarchar(200)    NULL,             -- e.g. cod-settle:{assignmentId}
    PaidAt                datetimeoffset   NULL,
    CreatedAt             datetimeoffset   NOT NULL CONSTRAINT DF_Transactions_CreatedAt DEFAULT SYSUTCDATETIME(),
    UpdatedAt             datetimeoffset   NOT NULL CONSTRAINT DF_Transactions_UpdatedAt DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_Transactions_Code UNIQUE (Code),
    CONSTRAINT CK_Transactions_Amount CHECK (Amount > 0)
);
CREATE INDEX IX_Transactions_Status_CreatedAt ON dbo.Transactions (Status, CreatedAt DESC);
CREATE UNIQUE INDEX UX_Transactions_Idempotency ON dbo.Transactions (UserId, Type, IdempotencyKey) WHERE IdempotencyKey IS NOT NULL;
CREATE UNIQUE INDEX UX_Transactions_BusinessEventKey ON dbo.Transactions (BusinessEventKey) WHERE BusinessEventKey IS NOT NULL;

ALTER TABLE dbo.CancellationCharges
    ADD CONSTRAINT FK_CancellationCharges_Transactions FOREIGN KEY (CollectedTransactionId) REFERENCES dbo.Transactions (Id);

CREATE TABLE dbo.WalletTransactions (                          -- append-only ledger: never UPDATE or DELETE
    Id                   uniqueidentifier NOT NULL CONSTRAINT PK_WalletTransactions PRIMARY KEY,
    WalletId             uniqueidentifier NOT NULL CONSTRAINT FK_WalletTransactions_Wallets REFERENCES dbo.Wallets (Id),
    OrderId              uniqueidentifier NULL CONSTRAINT FK_WalletTransactions_Orders REFERENCES dbo.Orders (Id),
    OrderAssignmentId    uniqueidentifier NULL CONSTRAINT FK_WalletTransactions_OrderAssignments REFERENCES dbo.OrderAssignments (Id),
    TransactionId        uniqueidentifier NULL CONSTRAINT FK_WalletTransactions_Transactions REFERENCES dbo.Transactions (Id),
    CancellationChargeId uniqueidentifier NULL CONSTRAINT FK_WalletTransactions_CancellationCharges REFERENCES dbo.CancellationCharges (Id),
    Type                 tinyint          NOT NULL,          -- 3=ORDER_EARNING, 4=COMMISSION, 7=CANCELLATION_FEE, 8=ADJUSTMENT
    Direction            tinyint          NOT NULL,          -- 1=CREDIT, 2=DEBIT
    Bucket               tinyint          NOT NULL,          -- 1=AVAILABLE, 3=DEBT
    LiabilityRole        tinyint          NOT NULL,          -- 1=CUSTOMER, 2=PARTNER
    Amount               decimal(18, 2)   NOT NULL,
    BalanceBefore        decimal(18, 2)   NOT NULL,
    BalanceAfter         decimal(18, 2)   NOT NULL,
    Description          nvarchar(300)    NOT NULL,
    BusinessEventKey     nvarchar(200)    NOT NULL,
    CreatedAt            datetimeoffset   NOT NULL CONSTRAINT DF_WalletTransactions_CreatedAt DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_WalletTransactions_Event UNIQUE (WalletId, Bucket, Type, BusinessEventKey),
    CONSTRAINT CK_WalletTransactions_Amount CHECK (Amount > 0)
);
CREATE INDEX IX_WalletTransactions_Wallet ON dbo.WalletTransactions (WalletId, CreatedAt DESC);

/* ============================ Review & Dispute ============================ */

CREATE TABLE dbo.Reviews (
    Id                uniqueidentifier NOT NULL CONSTRAINT PK_Reviews PRIMARY KEY,
    OrderId           uniqueidentifier NOT NULL CONSTRAINT FK_Reviews_Orders REFERENCES dbo.Orders (Id),
    OrderAssignmentId uniqueidentifier NOT NULL CONSTRAINT FK_Reviews_OrderAssignments REFERENCES dbo.OrderAssignments (Id),
    ReviewerId        uniqueidentifier NOT NULL CONSTRAINT FK_Reviews_Reviewer REFERENCES dbo.Users (Id),
    RevieweeId        uniqueidentifier NOT NULL CONSTRAINT FK_Reviews_Reviewee REFERENCES dbo.Users (Id),
    ReviewerType      tinyint          NOT NULL,             -- 1=CUSTOMER, 2=PARTNER
    Rating            tinyint          NOT NULL,
    PunctualityRating tinyint          NULL,
    QualityRating     tinyint          NULL,
    AttitudeRating    tinyint          NULL,
    PriceRating       tinyint          NULL,
    Comment           nvarchar(500)    NULL,
    Tags              nvarchar(300)    NULL,
    IsVisible         bit              NOT NULL CONSTRAINT DF_Reviews_IsVisible DEFAULT 1,
    HiddenReason      nvarchar(300)    NULL,
    PublishAt         datetimeoffset   NOT NULL CONSTRAINT DF_Reviews_PublishAt DEFAULT SYSUTCDATETIME(),
    CreatedAt         datetimeoffset   NOT NULL CONSTRAINT DF_Reviews_CreatedAt DEFAULT SYSUTCDATETIME(),
    CONSTRAINT UQ_Reviews_Assignment_Reviewer UNIQUE (OrderAssignmentId, ReviewerId),
    CONSTRAINT CK_Reviews_Rating CHECK (Rating BETWEEN 1 AND 5)
);
CREATE INDEX IX_Reviews_Reviewee ON dbo.Reviews (RevieweeId, CreatedAt DESC);

CREATE TABLE dbo.Disputes (
    Id                          uniqueidentifier NOT NULL CONSTRAINT PK_Disputes PRIMARY KEY,
    Code                        varchar(20)      NOT NULL,
    OrderId                     uniqueidentifier NOT NULL CONSTRAINT FK_Disputes_Orders REFERENCES dbo.Orders (Id),
    RaisedByUserId              uniqueidentifier NOT NULL CONSTRAINT FK_Disputes_Users REFERENCES dbo.Users (Id),
    RaisedByType                tinyint          NOT NULL,   -- 1=CUSTOMER, 2=PARTNER
    Reason                      tinyint          NOT NULL,   -- 1=WORK_QUALITY, 2=NO_SHOW, 3=OVERCHARGE, 4=DAMAGE, 5=BEHAVIOR, 6=PAYMENT, 7=UNPAID_CASH, 99=OTHER
    Description                 nvarchar(2000)   NOT NULL,
    EvidenceUrls                nvarchar(max)    NULL,       -- JSON array
    Status                      tinyint          NOT NULL CONSTRAINT DF_Disputes_Status DEFAULT 1, -- 1=OPEN, 2=IN_REVIEW, 3=RESOLVED, 4=REJECTED
    ResolutionNote              nvarchar(1000)   NULL,
    AssignedAdminId             uniqueidentifier NULL CONSTRAINT FK_Disputes_AdminUsers REFERENCES dbo.AdminUsers (Id),
    PreviousOrderStatus         tinyint          NOT NULL,
    AutoConfirmRemainingSeconds int              NULL,
    SlaDueAt                    datetimeoffset   NOT NULL,   -- CreatedAt + 48h
    ResolvedAt                  datetimeoffset   NULL,
    CreatedAt                   datetimeoffset   NOT NULL CONSTRAINT DF_Disputes_CreatedAt DEFAULT SYSUTCDATETIME(),
    UpdatedAt                   datetimeoffset   NOT NULL CONSTRAINT DF_Disputes_UpdatedAt DEFAULT SYSUTCDATETIME(),
    RowVersion                  rowversion       NOT NULL,
    CONSTRAINT UQ_Disputes_Code UNIQUE (Code)
);
-- At most one OPEN and one IN_REVIEW per order; the service must also reject a new dispute while one is open.
CREATE UNIQUE INDEX UX_Disputes_OpenPerOrder ON dbo.Disputes (OrderId, Status) WHERE Status IN (1, 2);
GO
