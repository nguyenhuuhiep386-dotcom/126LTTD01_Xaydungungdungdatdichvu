using System.ComponentModel.DataAnnotations;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Admin;
using Servio.Api.Services.Files;
using Servio.Api.Services.Requests;

namespace Servio.Api.Services.Orders;

/// <summary>#146 body. evidenceUrls: up to 5 photos uploaded with POST /files purpose DISPUTE_EVIDENCE.</summary>
public sealed record CreateDisputeRequest(
    DisputeReason Reason,
    [Required(ErrorMessage = "Hãy mô tả vấn đề"), StringLength(2000, MinimumLength = 10, ErrorMessage = "Mô tả dài 10–2000 ký tự")] string Description,
    [MaxLength(5, ErrorMessage = "Tối đa 5 ảnh")] string[]? EvidenceUrls);

/// <summary>#146–#148. resolutionNote is the admin's decision shown to both sides (no internal notes in scope).</summary>
public sealed record DisputeDto(
    Guid Id,
    string Code,
    Guid OrderId,
    DisputeReason Reason,
    string Description,
    IReadOnlyList<string> EvidenceUrls,
    DisputeStatus Status,
    ActorType RaisedByType,
    string? ResolutionNote,
    DateTimeOffset SlaDueAt,
    DateTimeOffset? ResolvedAt,
    DateTimeOffset CreatedAt);

/// <summary>
/// Disputes, course scope (0.2.7 ý 10): open during the work or ≤ order.dispute_window_hours after CompletedAt; the order
/// becomes DISPUTED and every action stops, auto-confirm is paused with its remaining time. The admin (AW-08) closes it
/// RESOLVED/REJECTED with a note; the order returns to PreviousOrderStatus and auto-confirm resumes. No refund in the app.
/// </summary>
public sealed class DisputeService(
    ServioDbContext db,
    OrderWorkflow workflow,
    FileService files,
    CodeGenerator codes,
    SystemConfigService configs,
    TimeProvider clock)
{
    private static readonly OrderStatus[] OpenableStatuses =
    [
        OrderStatus.Accepted, OrderStatus.OnTheWay, OrderStatus.Arrived, OrderStatus.InProgress,
        OrderStatus.CompletedByPartner, OrderStatus.Completed,
    ];

    /// <summary>#146</summary>
    public async Task<DisputeDto> CreateAsync(Guid userId, AppFlavor flavor, Guid orderId, CreateDisputeRequest input, CancellationToken ct)
    {
        var description = input.Description.Trim();
        ContentGuard.EnsureClean(description, "description");
        if (input.Reason == DisputeReason.UnpaidCash || !Enum.IsDefined(input.Reason))
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, "Lý do không hợp lệ", "reason");
        }
        var (order, assignment) = await MemberAsync(userId, flavor, orderId, ct);
        var now = clock.GetUtcNow();
        if (order.Status == (byte)OrderStatus.Disputed)
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.DisputeNotAllowed, "Đơn đang có khiếu nại chưa xử lý xong");
        }
        if (!OpenableStatuses.Contains((OrderStatus)order.Status) || assignment.Status == (byte)OrderStatus.Cancelled)
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.DisputeNotAllowed, "Đơn ở trạng thái này không mở khiếu nại được");
        }
        var window = await configs.GetIntAsync("order.dispute_window_hours", 72, ct);
        if (order.CompletedAt is { } completedAt && now > completedAt.AddHours(window))
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.DisputeNotAllowed, $"Đã quá {window} giờ kể từ khi hoàn tất đơn");
        }
        var evidence = new List<string>();
        foreach (var url in (input.EvidenceUrls ?? []).Where(u => !string.IsNullOrWhiteSpace(u)).Distinct())
        {
            evidence.Add(FileService.UrlOf(await files.ResolveOwnedAsync(userId, url, "evidenceUrls", ct, FilePurpose.DisputeEvidence)));
        }

        var dispute = new Dispute
        {
            Id = Guid.CreateVersion7(),
            Code = await codes.NextAsync(CodeGenerator.Dispute, ct),
            OrderId = order.Id,
            RaisedByUserId = userId,
            RaisedByType = (byte)(flavor == AppFlavor.Customer ? ActorType.Customer : ActorType.Partner),
            Reason = (byte)input.Reason,
            Description = description,
            EvidenceUrls = evidence.Count == 0 ? null : JsonSerializer.Serialize(evidence),
            Status = (byte)DisputeStatus.Open,
            PreviousOrderStatus = order.Status,
            SlaDueAt = now.AddHours(48),
            CreatedAt = now,
            UpdatedAt = now,
        };
        if (order.Status == (byte)OrderStatus.CompletedByPartner && order.AutoConfirmAt is { } autoConfirm)
        {
            dispute.AutoConfirmRemainingSeconds = (int)Math.Max(0, (autoConfirm - now).TotalSeconds);
            order.AutoConfirmAt = null; // paused (2.5.3)
        }
        db.Disputes.Add(dispute);
        var by = (ActorType)dispute.RaisedByType;
        var change = workflow.SetOrderStatus(order, assignment, OrderStatus.Disputed, by, userId, null, $"Khiếu nại {dispute.Code}");
        await db.SaveChangesAsync(ct);

        await workflow.PublishAsync(order, assignment, change,
            $"{(by == ActorType.Customer ? "Khách" : "Đối tác")} đã mở khiếu nại {dispute.Code}. Đơn tạm dừng cho đến khi bộ phận hỗ trợ xử lý (trong 48 giờ).", ct);
        return ToDto(dispute);
    }

    /// <summary>#147 Disputes of the order, newest first.</summary>
    public async Task<IReadOnlyList<DisputeDto>> ListForOrderAsync(Guid userId, AppFlavor flavor, Guid orderId, CancellationToken ct)
    {
        await MemberAsync(userId, flavor, orderId, ct);
        return (await db.Disputes.AsNoTracking().Where(d => d.OrderId == orderId).OrderByDescending(d => d.CreatedAt).ToListAsync(ct))
            .Select(ToDto).ToList();
    }

    /// <summary>#148</summary>
    public async Task<DisputeDto> GetAsync(Guid userId, AppFlavor flavor, Guid disputeId, CancellationToken ct)
    {
        var dispute = await db.Disputes.AsNoTracking().FirstOrDefaultAsync(d => d.Id == disputeId, ct)
                      ?? throw ApiException.NotFound("Không tìm thấy khiếu nại");
        await MemberAsync(userId, flavor, dispute.OrderId, ct);
        return ToDto(dispute);
    }

    /// <summary>
    /// AW-08: close the case. The order goes back to PreviousOrderStatus; from COMPLETED_BY_PARTNER the auto-confirm
    /// resumes with the time that was left. Both sides are notified with the note; AuditLog in the same save.
    /// </summary>
    public async Task ResolveAsync(AdminActor actor, Guid disputeId, bool upheld, string note, CancellationToken ct)
    {
        note = note.Trim();
        if (note.Length < 3)
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, "Hãy ghi kết quả xử lý", "note");
        }
        var dispute = await db.Disputes.FirstOrDefaultAsync(d => d.Id == disputeId, ct) ?? throw ApiException.NotFound("Không tìm thấy khiếu nại");
        if (dispute.Status is not ((byte)DisputeStatus.Open or (byte)DisputeStatus.InReview))
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.Conflict, "Khiếu nại đã được xử lý");
        }
        var order = await workflow.FindAsync(dispute.OrderId, ct) ?? throw ApiException.NotFound("Không tìm thấy đơn");
        var assignment = order.OrderAssignments.OrderByDescending(a => a.IsPrimary).First();
        var now = clock.GetUtcNow();

        dispute.Status = (byte)(upheld ? DisputeStatus.Resolved : DisputeStatus.Rejected);
        dispute.ResolutionNote = note;
        dispute.ResolvedAt = now;
        dispute.AssignedAdminId = actor.AdminId;
        dispute.UpdatedAt = now;
        var back = (OrderStatus)dispute.PreviousOrderStatus;
        if (back == OrderStatus.CompletedByPartner && dispute.AutoConfirmRemainingSeconds is { } left)
        {
            order.AutoConfirmAt = now.AddSeconds(left);
        }
        var change = workflow.SetOrderStatus(order, assignment, back, ActorType.Admin, null, actor.AdminId, $"Khiếu nại {dispute.Code}: {note}");
        db.AddAudit(actor, upheld ? "DISPUTE_RESOLVED" : "DISPUTE_REJECTED", nameof(Dispute), dispute.Id,
            new { Status = nameof(DisputeStatus.Open) }, new { Status = upheld ? "RESOLVED" : "REJECTED", Note = note, OrderStatus = back.ToString() }, now);
        await db.SaveChangesAsync(ct);

        await workflow.PublishAsync(order, assignment, change, $"Khiếu nại {dispute.Code} đã được xử lý: {note}", ct);
    }

    /// <summary>The order and the caller's assignment (customer: the primary one). Disputed orders are allowed here.</summary>
    private async Task<(Order Order, OrderAssignment Assignment)> MemberAsync(Guid userId, AppFlavor flavor, Guid orderId, CancellationToken ct)
    {
        var order = await workflow.FindAsync(orderId, ct);
        var assignment = order is null ? null : flavor == AppFlavor.Customer
            ? order.Customer.UserId == userId ? order.OrderAssignments.OrderByDescending(a => a.IsPrimary).FirstOrDefault() : null
            : order.OrderAssignments.FirstOrDefault(a => a.PartnerProfile.UserId == userId);
        return order is not null && assignment is not null ? (order, assignment) : throw ApiException.NotFound("Không tìm thấy đơn");
    }

    private static DisputeDto ToDto(Dispute d) => new(
        d.Id, d.Code, d.OrderId, (DisputeReason)d.Reason, d.Description,
        d.EvidenceUrls is null ? [] : JsonSerializer.Deserialize<string[]>(d.EvidenceUrls) ?? [],
        (DisputeStatus)d.Status, (ActorType)d.RaisedByType, d.ResolutionNote, d.SlaDueAt, d.ResolvedAt, d.CreatedAt);
}
