using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Files;
using Servio.Api.Services.Requests;

namespace Servio.Api.Services.Orders;

/// <summary>
/// Partner side of an assignment: #65 accept, #66 reject, #69 start work, #70 complete (2.5.1), plus the 10-minute
/// acceptance timeout run by the background job. Moving and arriving (#67, #68) are in TrackingService.
/// </summary>
public sealed class AssignmentService(
    ServioDbContext db,
    OrderWorkflow workflow,
    FileService files,
    SystemConfigService configs,
    TimeProvider clock,
    ILogger<AssignmentService> logger)
{
    public const string TimeoutReason = "Đối tác không xác nhận đúng hạn";

    /// <summary>#65 PENDING → ACCEPTED before AcceptanceDeadlineAt. Blocked when the commission debt is over the limit.</summary>
    public async Task<OrderStatusChangedDto> AcceptAsync(Guid userId, Guid orderId, Guid assignmentId, CancellationToken ct)
    {
        var (order, assignment) = await workflow.FindPartnerPartAsync(userId, orderId, assignmentId, ct);
        if (assignment.Status == (byte)OrderStatus.Pending && clock.GetUtcNow() >= assignment.AcceptanceDeadlineAt)
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.InvalidStatusTransition, "Đã quá hạn xác nhận đơn");
        }
        await EnsureDebtWithinLimitAsync(userId, ct);

        var change = await workflow.MoveAsync(order, assignment, OrderStatus.Accepted, ActorType.Partner, userId, null, ct);
        await db.SaveChangesAsync(ct);
        await workflow.PublishAsync(order, assignment, change, "Đối tác đã xác nhận đơn.", ct);
        return change;
    }

    /// <summary>
    /// #66 PENDING → CANCELLED by the partner, free of charge. The order is cancelled with it (one partner per order);
    /// the post stays MATCHED (2.5.1), the customer posts again to find someone else.
    /// Cancelling after ACCEPTED is #152 with the 2.5.2 policy (BE-6).
    /// </summary>
    public async Task RejectAsync(Guid userId, Guid orderId, Guid assignmentId, RejectAssignmentRequest input, CancellationToken ct)
    {
        var reason = input.Reason.Trim();
        ContentGuard.EnsureClean(reason, "reason");
        var (order, assignment) = await workflow.FindPartnerPartAsync(userId, orderId, assignmentId, ct);
        if (assignment.Status != (byte)OrderStatus.Pending)
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.InvalidStatusTransition, "Chỉ từ chối được đơn đang chờ xác nhận");
        }

        var change = await workflow.MoveAsync(order, assignment, OrderStatus.Cancelled, ActorType.Partner, userId, reason, ct);
        await db.SaveChangesAsync(ct);
        await workflow.PublishAsync(order, assignment, change, $"Đối tác đã từ chối đơn: {reason}", ct);
    }

    /// <summary>#69 ARRIVED → IN_PROGRESS with at least one BEFORE photo (F-ORD-08).</summary>
    public async Task<OrderStatusChangedDto> StartWorkAsync(Guid userId, Guid orderId, Guid assignmentId, StartWorkRequest input, CancellationToken ct)
    {
        var (order, assignment) = await workflow.FindPartnerPartAsync(userId, orderId, assignmentId, ct);
        OrderStateMachine.EnsureCanMove((OrderStatus)assignment.Status, OrderStatus.InProgress);
        await AddImagesAsync(userId, assignment, input.BeforeImages, OrderImageType.Before, "beforeImages", ct);

        var change = await workflow.MoveAsync(order, assignment, OrderStatus.InProgress, ActorType.Partner, userId, null, ct);
        await db.SaveChangesAsync(ct);
        await workflow.PublishAsync(order, assignment, change, "Đối tác đã bắt đầu làm việc.", ct);
        return change;
    }

    /// <summary>
    /// #70 IN_PROGRESS → COMPLETED_BY_PARTNER with at least one AFTER photo and no extra charge waiting for the customer.
    /// The order gets AutoConfirmAt = now + order.auto_confirm_hours (confirming is #71, BE-6).
    /// </summary>
    public async Task<OrderStatusChangedDto> CompleteAsync(Guid userId, Guid orderId, Guid assignmentId, CompleteWorkRequest input, CancellationToken ct)
    {
        var note = string.IsNullOrWhiteSpace(input.Note) ? null : input.Note.Trim();
        ContentGuard.EnsureClean(note, "note");
        var (order, assignment) = await workflow.FindPartnerPartAsync(userId, orderId, assignmentId, ct);
        OrderStateMachine.EnsureCanMove((OrderStatus)assignment.Status, OrderStatus.CompletedByPartner);
        if (await db.OrderExtraCharges.AnyAsync(e => e.OrderAssignmentId == assignment.Id && e.Status == (byte)ExtraChargeStatus.Pending, ct))
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.ExtraChargePending,
                "Còn khoản phát sinh chờ khách duyệt, hãy nhờ khách duyệt hoặc từ chối trước");
        }
        await AddImagesAsync(userId, assignment, input.AfterImages, OrderImageType.After, "afterImages", ct);

        var change = await workflow.MoveAsync(order, assignment, OrderStatus.CompletedByPartner, ActorType.Partner, userId, note, ct);
        await db.SaveChangesAsync(ct);
        var message = order.AutoConfirmAt is { } autoConfirm
            ? $"Đối tác đã báo hoàn thành. Hãy kiểm tra và xác nhận, đơn sẽ tự hoàn tất lúc {OrderWorkflow.LocalTime(autoConfirm)}."
            : "Đối tác đã báo hoàn thành phần việc của mình.";
        await workflow.PublishAsync(order, assignment, change, message, ct);
        return change;
    }

    /// <summary>
    /// Called by the background job: PENDING assignments past AcceptanceDeadlineAt → CANCELLED by SYSTEM.
    /// Each one is reloaded and saved on its own, so a partner accepting at the same moment wins cleanly.
    /// </summary>
    public async Task<int> ExpireUnacceptedAsync(CancellationToken ct)
    {
        var now = clock.GetUtcNow();
        var due = await db.OrderAssignments.AsNoTracking()
            .Where(a => a.Status == (byte)OrderStatus.Pending && a.AcceptanceDeadlineAt <= now)
            .OrderBy(a => a.AcceptanceDeadlineAt)
            .Select(a => new { a.Id, a.OrderId })
            .Take(100)
            .ToListAsync(ct);
        var expired = 0;
        foreach (var item in due)
        {
            var order = await workflow.FindAsync(item.OrderId, ct);
            var assignment = order?.OrderAssignments.FirstOrDefault(a => a.Id == item.Id);
            if (order is null || assignment is null || assignment.Status != (byte)OrderStatus.Pending)
            {
                continue;
            }
            var change = await workflow.MoveAsync(order, assignment, OrderStatus.Cancelled, ActorType.System, null, TimeoutReason, ct);
            try
            {
                await db.SaveChangesAsync(ct);
            }
            catch (DbUpdateConcurrencyException)
            {
                db.ChangeTracker.Clear(); // the partner accepted (or rejected) in between
                continue;
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                // One bad row must not block every later expiry on each tick.
                logger.LogError(e, "Expiring assignment {AssignmentId} failed", item.Id);
                db.ChangeTracker.Clear();
                continue;
            }
            await workflow.PublishAsync(order, assignment, change, "Đơn đã tự huỷ vì đối tác không xác nhận trong thời hạn.", ct);
            expired++;
        }
        return expired;
    }

    /// <summary>Same rule as sending a quote (0.2.7 ý 7): commission debt over partner.max_commission_debt blocks new work.</summary>
    private async Task EnsureDebtWithinLimitAsync(Guid userId, CancellationToken ct)
    {
        var maxDebt = await configs.GetDecimalAsync("partner.max_commission_debt", 200_000, ct);
        var debt = await db.Wallets.Where(w => w.UserId == userId).Select(w => w.DebtBalance).FirstOrDefaultAsync(ct);
        if (debt > maxDebt)
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.OutstandingDebt,
                $"Nợ hoa hồng vượt {Money.Vnd(maxDebt)}, hãy thanh toán để tiếp tục nhận việc");
        }
    }

    /// <summary>Photos must be READY ORDER_IMAGE files uploaded by the partner (POST /files).</summary>
    private async Task AddImagesAsync(Guid userId, OrderAssignment assignment, string[] urls, OrderImageType type, string field, CancellationToken ct)
    {
        var picked = urls.Where(u => !string.IsNullOrWhiteSpace(u)).Distinct().ToList();
        if (picked.Count == 0)
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, "Cần ít nhất 1 ảnh", field);
        }
        var now = clock.GetUtcNow();
        foreach (var url in picked)
        {
            var file = await files.ResolveOwnedAsync(userId, url, field, ct, FilePurpose.OrderImage);
            assignment.OrderImages.Add(new OrderImage
            {
                Id = Guid.CreateVersion7(),
                Url = FileService.UrlOf(file),
                Type = (byte)type,
                CreatedAt = now,
            });
        }
    }
}
