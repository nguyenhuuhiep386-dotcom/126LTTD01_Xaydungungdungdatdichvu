namespace Servio.Api.Common;

// Enums are stored as tinyint in SQL and serialized as UPPER_SNAKE_CASE strings in JSON
// (JsonStringEnumConverter + SnakeCaseUpper in Program.cs). Values must match 01_schema.sql.

public enum AppFlavor : byte { Customer = 1, Partner = 2 }

public enum UserRoleType : byte { Customer = 1, Partner = 2 }

public enum UserStatus : byte { Active = 1, Suspended = 2, Banned = 3, Deleted = 4 }

public enum Gender : byte { Other = 0, Male = 1, Female = 2 }

public enum OtpPurpose : byte { Register = 1, Login = 2, ResetPassword = 3, VerifyPhone = 4 }

public enum PartnerVerificationStatus : byte { NotSubmitted = 0, Pending = 1, Approved = 2, Rejected = 3 }

public enum AdminRole : byte { SuperAdmin = 1, Operator = 2, Finance = 3, Support = 4 }

public enum ServiceRequestStatus : byte { Draft = 1, Open = 2, Matched = 3, Expired = 4, Cancelled = 5, RejectedByModeration = 6 }

public enum QuoteStatus : byte { Pending = 1, Accepted = 2, Rejected = 3, Withdrawn = 4, Expired = 5 }

public enum OrderStatus : byte
{
    Pending = 1,
    Accepted = 2,
    OnTheWay = 3,
    Arrived = 4,
    InProgress = 5,
    CompletedByPartner = 6,
    AwaitingPayment = 7,
    Completed = 8,
    Cancelled = 9,
    Disputed = 10,
}

public enum ActorType : byte { Customer = 1, Partner = 2, System = 3, Admin = 4 }
