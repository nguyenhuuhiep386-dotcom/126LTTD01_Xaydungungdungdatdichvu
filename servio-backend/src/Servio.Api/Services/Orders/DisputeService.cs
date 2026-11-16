using System.ComponentModel.DataAnnotations;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Admin;
using Servio.Api.Services.Files;
using Servio.Api.Services.Requests;
using Servio.Api.Services.Wallets;

namespace Servio.Api.Services.Orders;

/// <summary>#146 body. evidenceUrls: up to 5 photos uploaded with POST /files purpose DISPUTE_EVIDENCE.</summary>
public sealed record CreateDisputeRequest(
    DisputeReason Reason,
    [Required(ErrorMessage = "Hãy mô tả vấn đề"), StringLength(2000, MinimumLength = 10, ErrorMessage = "Mô tả dài 10–2000 ký tự")] string Description,
    [MaxLength(5, ErrorMessage = "Tối đa 5 ảnh")] string[]? EvidenceUrls);

/// <summary>#177 body. note: what happened; evidenceUrls: up to 5 photos (purpose DISPUTE_EVIDENCE), e.g. the chat.</summary>
public sealed record ReportUnpaidRequest(
    [Required(ErrorMessage = "Hãy mô tả tình huống"), StringLength(1000, MinimumLength = 10, ErrorMessage = "Mô tả dài 10–1000 ký tự")] string Note,
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
    WalletService wallets,
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
        EnsureNoOpenDispute(order);
        if (!OpenableStatuses.Contains((OrderStatus)order.Status) || assignment.Status == (byte)OrderStatus.Cancelled)
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.DisputeNotAllowed, "Đơn ở trạng thái này không mở khiếu nại được");
        }
        var window = await configs.GetIntAsync("order.dispute_window_hours", 72, ct);
        if (order.CompletedAt is { } completedAt && now > completedAt.AddHours(window))
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.DisputeNotAllowed, $"Đã quá {window} giờ kể từ khi hoàn tất đơn");
        }
        var by = flavor == AppFlavor.Customer ? ActorType.Customer : ActorType.Partner;
        return await OpenAsync(order, assignment, by, userId, input.Reason, description, input.EvidenceUrls, ct);
    }

    /// <summary>
    /// #177 the partner did not receive the cash: from COMPLETED_BY_PARTNER until UnpaidReportDeadlineAt
    /// (CompletedAt + order.unpaid_report_window_hours). Opens an UNPAID_CASH dispute; the order is frozen as for #146.
    /// </summary>
    public async Task<DisputeDto> ReportUnpaidAsync(Guid userId, Guid orderId, Guid assignmentId, ReportUnpaidRequest input, CancellationToken ct)
    {
        var note = input.Note.Trim();
        ContentGuard.EnsureClean(note, "note");
        var order = await workflow.FindAsync(orderId, ct);
        var assignment = order?.OrderAssignments.FirstOrDefault(a => a.Id == assignmentId && a.PartnerProfile.UserId == userId);
        if (order is null || assignment is null)
        {
            throw ApiException.NotFound("Không tìm thấy đơn");
        }
        EnsureNoOpenDispute(order);
        var inWindow = assignment.Status == (byte)OrderStatus.CompletedByPartner
                       || (assignment.Status == (byte)OrderStatus.Completed && clock.GetUtcNow() <= order.UnpaidReportDeadlineAt);
        if (!inWindow)
        {
            var hours = await configs.GetIntAsync("order.unpaid_report_window_hours", 24, ct);
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.UnpaidReportWindowClosed,
                $"Chỉ báo khách chưa thanh toán sau khi báo hoàn thành và trong {hours} giờ kể từ khi đơn hoàn tất");
        }
        return await OpenAsync(order, assignment, ActorType.Partner, userId, DisputeReason.UnpaidCash, note, input.EvidenceUrls, ct);
    }

    private static void EnsureNoOpenDispute(Order order)
    {
        if (order.Status == (byte)OrderStatus.Disputed)
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.DisputeAlreadyOpen, "Đơn đang có khiếu nại chưa xử lý xong");
        }
    }

    /// <summary>Common part of #146 and #177: the dispute row, DISPUTED, paused auto-confirm, then events to both sides.</summary>
    private async Task<DisputeDto> OpenAsync(Order order, OrderAssignment assignment, ActorType by, Guid userId, DisputeReason reason,
        string description, string[]? evidenceUrls, CancellationToken ct)
    {
        var now = clock.GetUtcNow();
        var evidence = new List<string>();
        foreach (var url in (evidenceUrls ?? []).Where(u => !string.IsNullOrWhiteSpace(u)).Distinct())
        {
            evidence.Add(FileService.UrlOf(await files.ResolveOwnedAsync(userId, url, "evidenceUrls", ct, FilePurpose.DisputeEvidence)));
        }

        var dispute = new Dispute
        {
            Id = Guid.CreateVersion7(),
            Code = await codes.NextAsync(CodeGenerator.Dispute, ct),
            OrderId = order.Id,
            RaisedByUserId = userId,
            RaisedByType = (byte)by,
            Reason = (byte)reason,
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
        var change = workflow.SetOrderStatus(order, assignment, OrderStatus.Disputed, by, userId, null, $"Khiếu nại {dispute.Code}");
        await db.SaveChangesAsync(ct);

        var what = reason == DisputeReason.UnpaidCash
            ? $"Đối tác báo chưa nhận được tiền mặt (khiếu nại {dispute.Code})"
            : $"{(by == ActorType.Customer ? "Khách" : "Đối tác")} đã mở khiếu nại {dispute.Code}";
        await workflow.PublishAsync(order, assignment, change, $"{what}. Đơn tạm dừng cho đến khi bộ phận hỗ trợ xử lý (trong 48 giờ).", ct);
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
        OrderStatusChangedDto change;
        if (upheld && dispute.Reason == (byte)DisputeReason.UnpaidCash)
        {
            // 2.5.3: the customer did not pay. Reverse the presumed COD settlement (if it happened) and wait for the
            // real payment in AWAITING_PAYMENT; the admin closes it from AW-07 once the customer paid the partner.
            assignment = order.OrderAssignments.First(a => a.PartnerProfile.UserId == dispute.RaisedByUserId);
            if (back == OrderStatus.Completed)
            {
                await ReverseCashAsync(order, assignment, now, ct);
            }
            order.AutoConfirmAt = null;
            change = await workflow.MoveAsync(order, assignment, OrderStatus.AwaitingPayment, ActorType.Admin, null, $"Khiếu nại {dispute.Code}: {note}", ct);
            assignment.PaymentStatus = (byte)PaymentStatus.Unpaid;
            assignment.PaidAt = null;
            order.PaymentStatus = (byte)PaymentStatus.Unpaid;
        }
        else
        {
            if (back == OrderStatus.CompletedByPartner && dispute.AutoConfirmRemainingSeconds is { } left)
            {
                order.AutoConfirmAt = now.AddSeconds(left);
            }
            change = workflow.SetOrderStatus(order, assignment, back, ActorType.Admin, null, actor.AdminId, $"Khiếu nại {dispute.Code}: {note}");
        }
        db.AddAudit(actor, upheld ? "DISPUTE_RESOLVED" : "DISPUTE_REJECTED", nameof(Dispute), dispute.Id,
            new { Status = nameof(DisputeStatus.Open) }, new { Status = upheld ? "RESOLVED" : "REJECTED", Note = note, OrderStatus = back.ToString() }, now);
        await db.SaveChangesAsync(ct);

        await workflow.PublishAsync(order, assignment, change, $"Khiếu nại {dispute.Code} đã được xử lý: {note}", ct);
    }

    /// <summary>
    /// UNPAID_CASH confirmed after COMPLETED: a PAYMENT_REVERSAL for the presumed CASH transaction and the commission debt
    /// taken back. If the partner already paid part of that debt, the paid part comes back to their Available balance.
    /// </summary>
    private async Task ReverseCashAsync(Order order, OrderAssignment a, DateTimeOffset now, CancellationToken ct)
    {
        var key = $"cod-reverse:{a.Id}";
        var reversal = wallets.NewTransaction(order.Customer.UserId, TransactionType.PaymentReversal, TransactionProvider.Cash,
            a.CustomerPayable, TransactionStatus.Success, await codes.NextAsync(CodeGenerator.Transaction, ct), now);
        reversal.OrderId = order.Id;
        reversal.BusinessEventKey = key;
        reversal.PaidAt = now;
        var commission = CompletionService.Settlement(a);
        if (commission <= 0)
        {
            return;
        }
        var wallet = await wallets.GetOrCreateAsync(a.PartnerProfile.UserId, ct);
        var fromDebt = Math.Min(commission, wallet.DebtBalance);
        if (fromDebt > 0)
        {
            wallets.Post(wallet, WalletBucket.Debt, LedgerDirection.Debit, WalletTransactionType.Adjustment, fromDebt,
                $"Đảo hoa hồng đơn {order.Code} (khách chưa thanh toán)", key, order.Id, a.Id, reversal.Id);
        }
        if (commission - fromDebt > 0)
        {
            wallets.Post(wallet, WalletBucket.Available, LedgerDirection.Credit, WalletTransactionType.Adjustment, commission - fromDebt,
                $"Hoàn hoa hồng đã trả của đơn {order.Code} (khách chưa thanh toán)", key, order.Id, a.Id, reversal.Id);
        }
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
