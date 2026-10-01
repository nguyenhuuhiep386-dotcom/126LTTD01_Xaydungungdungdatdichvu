using System.ComponentModel.DataAnnotations;
using Servio.Api.Common;

namespace Servio.Api.Services.Partners;

// #17 GET /partners/me
public sealed record PartnerMeDto(
    Guid Id,
    Guid UserId,
    string FullName,
    string? AvatarUrl,
    string PhoneNumber,
    string? Bio,
    int YearsOfExperience,
    PartnerVerificationStatus VerificationStatus,
    string? VerificationNote,
    bool IsOnline,
    DateTimeOffset? LastHeartbeatAt,
    int ServiceRadiusKm,
    decimal? AnchorLatitude,
    decimal? AnchorLongitude,
    decimal? AverageRating,
    int TotalReviews,
    int CompletedOrders,
    int CancelledOrders,
    IReadOnlyList<PartnerDocumentDto> Documents,
    IReadOnlyList<PartnerSkillDto> Skills,
    IReadOnlyList<string> MissingForVerification);

public sealed record PartnerDocumentDto(
    Guid Id,
    DocumentType DocumentType,
    string FileUrl,
    ApprovalStatus Status,
    string? RejectReason,
    DateTimeOffset CreatedAt);

public sealed record PartnerSkillDto(
    Guid Id,
    Guid CategoryId,
    string CategoryName,
    string? ParentCategoryName,
    ApprovalStatus Status,
    int YearsOfExperience,
    string? CertificateUrl);

/// <summary>#18 PATCH /partners/me — null fields are not changed. Bank info is out of the course scope.</summary>
public sealed record UpdatePartnerRequest(
    [StringLength(1000)] string? Bio,
    [Range(0, 60, ErrorMessage = "Số năm kinh nghiệm từ 0 đến 60")] int? YearsOfExperience,
    int? ServiceRadiusKm,
    decimal? AnchorLatitude,
    decimal? AnchorLongitude);

/// <summary>#19 POST /partners/me/documents. fileUrl comes from POST /files (purpose KYC, or CERTIFICATE for certificates).</summary>
public sealed record AddDocumentRequest(
    DocumentType DocumentType,
    [Required] string FileUrl);

public sealed record VerificationStatusDto(PartnerVerificationStatus VerificationStatus);

/// <summary>#22 POST /partners/me/skills. serviceCategoryId must be an active level-2 category.</summary>
public sealed record AddSkillRequest(
    Guid ServiceCategoryId,
    [Range(0, 60, ErrorMessage = "Số năm kinh nghiệm từ 0 đến 60")] int YearsOfExperience,
    string? CertificateUrl);

// #25 POST /partners/me/online-status
public sealed record OnlineStatusRequest(bool IsOnline, decimal? Latitude, decimal? Longitude);

public sealed record OnlineStatusDto(bool IsOnline);

// #26 POST /partners/me/heartbeat
public sealed record HeartbeatRequest(decimal? Latitude, decimal? Longitude);

// #27 GET /partners/{id} — public profile, no phone/KYC/address
public sealed record PartnerPublicDto(
    Guid Id,
    string FullName,
    string? AvatarUrl,
    string? Bio,
    int YearsOfExperience,
    decimal? AverageRating,
    int TotalReviews,
    int CompletedOrders,
    IReadOnlyList<string> Skills,
    DateTimeOffset MemberSince);

// #28 GET /partners/{id}/reviews
public sealed record PartnerReviewDto(
    Guid Id,
    int Rating,
    string? Comment,
    IReadOnlyList<string> Tags,
    string ReviewerName,
    DateTimeOffset CreatedAt);
