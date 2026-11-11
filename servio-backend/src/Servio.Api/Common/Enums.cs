namespace Servio.Api.Common;

// Enums are stored as tinyint in SQL and serialized as UPPER_SNAKE_CASE strings in JSON
// (JsonStringEnumConverter + SnakeCaseUpper in Program.cs). Values must match 01_schema.sql.

public enum AppFlavor : byte { Customer = 1, Partner = 2 }

public enum UserRoleType : byte { Customer = 1, Partner = 2 }

public enum UserStatus : byte { Active = 1, Suspended = 2, Banned = 3, Deleted = 4 }

public enum Gender : byte { Other = 0, Male = 1, Female = 2 }

public enum OtpPurpose : byte { Register = 1, Login = 2, ResetPassword = 3, VerifyPhone = 4 }

public enum PartnerVerificationStatus : byte { NotSubmitted = 0, Pending = 1, Approved = 2, Rejected = 3 }

/// <summary>PartnerDocuments.Status and PartnerSkills.Status.</summary>
public enum ApprovalStatus : byte { Pending = 1, Approved = 2, Rejected = 3 }

public enum DocumentType : byte { IdFront = 1, IdBack = 2, SelfieWithId = 3, Certificate = 4, CriminalRecord = 5 }

/// <summary>UploadedFiles.Purpose. POST_VIDEO (2) and PORTFOLIO (10) are out of the course scope.</summary>
public enum FilePurpose : byte
{
    PostImage = 1,
    Avatar = 3,
    Kyc = 4,
    OrderImage = 5,
    ChatImage = 6,
    ReviewImage = 7,
    DisputeEvidence = 8,
    Certificate = 9,
}

public enum DevicePlatform : byte { Android = 1, Ios = 2, Web = 3 }

public enum AdminRole : byte { SuperAdmin = 1, Operator = 2, Finance = 3, Support = 4 }

public enum ServiceRequestStatus : byte { Draft = 1, Open = 2, Matched = 3, Expired = 4, Cancelled = 5, RejectedByModeration = 6 }

public enum ScheduleType : byte { Now = 1, Scheduled = 2 }

public enum ConversationStatus : byte { Active = 1, Archived = 2, ReadOnly = 3, Blocked = 4 }

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

/// <summary>OrderExtraCharges.Status</summary>
public enum ExtraChargeStatus : byte { Pending = 1, Approved = 2, Rejected = 3 }

public enum OrderImageType : byte { Before = 1, After = 2, Issue = 3 }

public enum PaymentMethod : byte { Cash = 1 }

public enum PaymentStatus : byte { Unpaid = 1, Processing = 2, Paid = 3 }

/// <summary>Transactions.Type (course scope: COD payment and debt payment).</summary>
public enum TransactionType : byte { Payment = 1, DebtPayment = 5 }

public enum TransactionProvider : byte { Cash = 1, BankTransfer = 6, InternalOffset = 7 }

public enum TransactionStatus : byte { Pending = 1, Success = 3, Failed = 4, Cancelled = 5 }

/// <summary>WalletTransactions.Type. DEBT_PAYMENT (5) is the ledger side of a successful debt payment (2.6.1).</summary>
public enum WalletTransactionType : byte { OrderEarning = 3, Commission = 4, DebtPayment = 5, CancellationFee = 7, Adjustment = 8 }

public enum LedgerDirection : byte { Credit = 1, Debit = 2 }

public enum WalletBucket : byte { Available = 1, Debt = 3 }

/// <summary>CancellationCharges.Status. A DUE charge with CollectedTransactionId set is waiting for the admin (AW-18).</summary>
public enum CancellationChargeStatus : byte { Due = 1, Paid = 2, Waived = 3 }

public enum CancellationReason : byte { CustomerCancel = 1, CustomerNoShow = 2 }

public enum DisputeStatus : byte { Open = 1, InReview = 2, Resolved = 3, Rejected = 4 }

public enum DisputeReason : byte { WorkQuality = 1, NoShow = 2, Overcharge = 3, Damage = 4, Behavior = 5, Payment = 6, UnpaidCash = 7, Other = 99 }

/// <summary>OrderAssignments.ArrivalMethod: check-in by GPS (#68) or confirmed by the customer (#179).</summary>
public enum ArrivalMethod : byte { Gps = 1, Customer = 2 }

/// <summary>ArrivalConfirmationRequests.Status</summary>
public enum ArrivalConfirmationStatus : byte { Pending = 1, Confirmed = 2, Denied = 3, Expired = 4, Cancelled = 5 }

public enum ActorType : byte { Customer = 1, Partner = 2, System = 3, Admin = 4 }

/// <summary>Parses UPPER_SNAKE_CASE values from query strings and multipart forms (JSON bodies use the converter in Program.cs).</summary>
public static class EnumText
{
    public static TEnum Parse<TEnum>(string? value, string field) where TEnum : struct, Enum
    {
        var compact = value?.Replace("_", "", StringComparison.Ordinal);
        if (string.IsNullOrEmpty(compact) || char.IsDigit(compact[0]) ||
            !Enum.TryParse<TEnum>(compact, ignoreCase: true, out var result) || !Enum.IsDefined(result))
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, "Giá trị không hợp lệ", field);
        }
        return result;
    }
}
