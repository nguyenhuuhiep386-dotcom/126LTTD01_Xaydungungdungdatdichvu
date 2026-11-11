namespace Servio.Api.Common;

/// <summary>
/// Business error codes (spec 6.1). Android keeps a matching list to show Vietnamese messages.
/// Add new codes here first, then mirror them in servio-android core/network ErrorMessages.kt.
/// </summary>
public static class ErrorCodes
{
    // Generic
    public const string ValidationError = "VALIDATION_ERROR";
    public const string Unauthenticated = "UNAUTHENTICATED";
    public const string Forbidden = "FORBIDDEN";
    public const string NotFound = "NOT_FOUND";
    public const string Conflict = "CONFLICT";
    public const string RateLimited = "RATE_LIMITED";
    public const string InternalError = "INTERNAL_ERROR";

    // M1 Auth & Profile
    public const string OtpInvalid = "OTP_INVALID";
    public const string OtpExpired = "OTP_EXPIRED";
    public const string OtpRateLimited = "OTP_RATE_LIMITED";
    public const string RefreshTokenInvalid = "REFRESH_TOKEN_INVALID";
    public const string AccountLocked = "ACCOUNT_LOCKED";
    public const string PhoneAlreadyExists = "PHONE_ALREADY_EXISTS";
    public const string PartnerNotVerified = "PARTNER_NOT_VERIFIED";
    public const string PartnerOffline = "PARTNER_OFFLINE";
    public const string ProfileIncomplete = "PROFILE_INCOMPLETE";
    public const string VerificationLocked = "VERIFICATION_LOCKED";
    public const string SkillAlreadyExists = "SKILL_ALREADY_EXISTS";
    public const string SkillLimitReached = "SKILL_LIMIT_REACHED";
    public const string CertificateRequired = "CERTIFICATE_REQUIRED";
    public const string AddressLimitReached = "ADDRESS_LIMIT_REACHED";

    // M2 / M3 (BE-2)
    public const string ContactInfoNotAllowed = "CONTACT_INFO_NOT_ALLOWED";
    public const string PostingRestricted = "POSTING_RESTRICTED";
    public const string RequestNotOpen = "REQUEST_NOT_OPEN";
    public const string ResourceVersionConflict = "RESOURCE_VERSION_CONFLICT";
    public const string PartnerNotEligible = "PARTNER_NOT_ELIGIBLE";

    // M4 chat (BE-3)
    public const string ConversationReadOnly = "CONVERSATION_READ_ONLY";

    // M5 orders (BE-4)
    public const string PartnerCapacityExceeded = "PARTNER_CAPACITY_EXCEEDED";
    public const string ScheduleConflict = "SCHEDULE_CONFLICT";
    public const string ExtraChargePending = "EXTRA_CHARGE_PENDING";
    public const string OrderDisputed = "ORDER_DISPUTED";

    // M5 tracking and check-in (BE-5). TOO_FAR_FROM_ADDRESS / GPS_NOT_RELIABLE mean the partner may ask the customer (#178).
    public const string GpsNotReliable = "GPS_NOT_RELIABLE";
    public const string ArrivalConfirmationNotAllowed = "ARRIVAL_CONFIRMATION_NOT_ALLOWED";

    // M5/M6 COD, cancel, debts (BE-6)
    public const string NoShowNotAllowed = "NO_SHOW_NOT_ALLOWED";
    public const string NothingToPay = "NOTHING_TO_PAY";

    // M7 reviews, disputes (BE-7)
    public const string ReviewWindowClosed = "REVIEW_WINDOW_CLOSED";
    public const string DisputeNotAllowed = "DISPUTE_NOT_ALLOWED";

    // Files (POST /files)
    public const string FileNotReady = "FILE_NOT_READY";
    public const string FileTooLarge = "FILE_TOO_LARGE";
    public const string UnsupportedFileType = "UNSUPPORTED_FILE_TYPE";

    // M2–M5 (used by the next work packages)
    public const string QuoteLimitExceeded = "QUOTE_LIMIT_EXCEEDED";
    public const string QuoteAlreadyExists = "QUOTE_ALREADY_EXISTS";
    public const string QuoteAlreadyAccepted = "QUOTE_ALREADY_ACCEPTED";
    public const string RequestExpired = "REQUEST_EXPIRED";
    public const string InvalidStatusTransition = "INVALID_STATUS_TRANSITION";
    public const string TooFarFromAddress = "TOO_FAR_FROM_ADDRESS";
    public const string MockLocationDetected = "MOCK_LOCATION_DETECTED";
    public const string OrderNotCancellable = "ORDER_NOT_CANCELLABLE";
    public const string ReviewAlreadySubmitted = "REVIEW_ALREADY_SUBMITTED";
    public const string OutstandingDebt = "OUTSTANDING_DEBT";
}
