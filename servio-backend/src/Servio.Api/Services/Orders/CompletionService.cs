using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Admin;
using Servio.Api.Services.Wallets;

namespace Servio.Api.Services.Orders;

/// <summary>
/// COD completion (0.2.7 ý 7, 2.5.1, 2.6.1): #71 the customer confirms, or the job auto-confirms at AutoConfirmAt.
/// Same SaveChanges: each assignment → COMPLETED, a presumed CASH Transaction SUCCESS (cod-settle:{assignmentId}) and
/// the partner's commission debt += N_i (= K_i in the course scope: no platform fee, voucher or carried fee).
/// </summary>
public sealed class CompletionService(
    ServioDbContext db,
    OrderWorkflow workflow,
    WalletService wallets,
    CodeGenerator codes,
    SystemConfigService configs,
    TimeProvider clock,
    ILogger<CompletionService> logger)
{
    /// <summary>#71. Fails with RESOURCE_VERSION_CONFLICT when the order changed since the customer looked at it.</summary>
    public async Task<CompletionResultDto> ConfirmAsync(Guid userId, Guid orderId, ConfirmCompletionRequest input, CancellationToken ct)
    {
        var order = await workflow.FindAsync(orderId, ct);
        if (order is null || order.Customer.UserId != userId)
        {
            throw ApiException.NotFound("Không tìm thấy đơn");
        }
        OrderWorkflow.EnsureNotDisputed(order);
        EnsureVersion(order, input.ExpectedRowVersion);
        if (order.Status != (byte)OrderStatus.CompletedByPartner)
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.InvalidStatusTransition, "Đối tác chưa báo hoàn thành");
        }
        var changes = await SettleAsync(order, ActorType.Customer, userId, ct);
        await db.SaveChangesAsync(ct);
        await PublishAsync(order, changes, "Khách đã xác nhận hoàn thành và thanh toán tiền mặt. Cảm ơn hai bạn!", ct);
        return new CompletionResultDto(order.Id, (OrderStatus)order.Status, false, (long)order.TotalAmount,
            (long)order.OrderAssignments.Where(a => a.Status == (byte)OrderStatus.Completed).Sum(a => a.CustomerPayable));
    }

    /// <summary>Job: COMPLETED_BY_PARTNER orders past AutoConfirmAt. One order per save so a failure only skips that order.</summary>
    public async Task<int> AutoConfirmDueAsync(CancellationToken ct)
    {
        var now = clock.GetUtcNow();
        var due = await db.Orders.AsNoTracking()
            .Where(o => o.Status == (byte)OrderStatus.CompletedByPartner && o.AutoConfirmAt <= now)
            .OrderBy(o => o.AutoConfirmAt).Select(o => o.Id).Take(50).ToListAsync(ct);
        var done = 0;
        foreach (var id in due)
        {
            try
            {
                var order = await workflow.FindAsync(id, ct);
                if (order is null || order.Status != (byte)OrderStatus.CompletedByPartner)
                {
                    continue;
                }
                var changes = await SettleAsync(order, ActorType.System, null, ct);
                await db.SaveChangesAsync(ct);
                await PublishAsync(order, changes, "Đơn đã tự hoàn tất theo thời hạn xác nhận.", ct);
                done++;
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                // Customer confirmed at the same moment (concurrency) or a bad row: skip, the next tick retries.
                logger.LogWarning(e, "Auto-confirm of order {OrderId} failed", id);
            }
            finally
            {
                db.ChangeTracker.Clear();
            }
        }
        return done;
    }

    /// <summary>
    /// AW-07, after a confirmed UNPAID_CASH dispute (#177): the customer paid the partner directly and the admin checked the
    /// proof. AWAITING_PAYMENT → COMPLETED with a real (not presumed) CASH transaction and the commission debt again;
    /// CompletedAt is kept (2.5.3). Audited.
    /// </summary>
    public async Task ConfirmCollectedAsync(AdminActor actor, Guid orderId, CancellationToken ct)
    {
        var order = await workflow.FindAsync(orderId, ct) ?? throw ApiException.NotFound("Không tìm thấy đơn");
        if (order.Status != (byte)OrderStatus.AwaitingPayment)
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.InvalidStatusTransition, "Đơn không ở trạng thái chờ thanh toán");
        }
        var changes = await SettleAsync(order, ActorType.Admin, null, ct);
        db.AddAudit(actor, "ORDER_PAYMENT_COLLECTED", nameof(Order), order.Id,
            new { Status = nameof(OrderStatus.AwaitingPayment) }, new { Status = nameof(OrderStatus.Completed), order.TotalAmount }, clock.GetUtcNow());
        await db.SaveChangesAsync(ct);
        await PublishAsync(order, changes, "Bộ phận hỗ trợ đã xác nhận khách thanh toán cho đối tác. Đơn đã hoàn tất.", ct);
    }

    public static void EnsureVersion(Order order, string expected)
    {
        if (Convert.ToBase64String(order.RowVersion) != expected)
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.ResourceVersionConflict, "Đơn đã thay đổi, vui lòng tải lại");
        }
    }

    private async Task<List<(OrderAssignment Assignment, OrderStatusChangedDto Change)>> SettleAsync(
        Order order, ActorType by, Guid? userId, CancellationToken ct)
    {
        var now = clock.GetUtcNow();
        var changes = new List<(OrderAssignment, OrderStatusChangedDto)>();
        foreach (var a in order.OrderAssignments
                     .Where(a => a.Status is (byte)OrderStatus.CompletedByPartner or (byte)OrderStatus.AwaitingPayment).ToList())
        {
            // AWAITING_PAYMENT = collected after an UNPAID_CASH dispute: a real payment with its own event key.
            var collected = a.Status == (byte)OrderStatus.AwaitingPayment;
            changes.Add((a, await workflow.MoveAsync(order, a, OrderStatus.Completed, by, userId, null, ct)));
            var key = collected ? $"cod-collect:{a.Id}" : $"cod-settle:{a.Id}";
            var cash = wallets.NewTransaction(order.Customer.UserId, TransactionType.Payment, TransactionProvider.Cash, a.CustomerPayable,
                TransactionStatus.Success, await codes.NextAsync(CodeGenerator.Transaction, ct), now);
            cash.OrderId = order.Id;
            cash.IsPresumed = !collected;
            cash.BusinessEventKey = key;
            cash.PaidAt = now;
            a.PaymentStatus = (byte)PaymentStatus.Paid;
            if (!collected)
            {
                a.PartnerProfile.CompletedOrders++; // counted once, at the first completion
            }
            a.PaidAt = now;
            var debt = Settlement(a);
            if (debt > 0)
            {
                var wallet = await wallets.GetOrCreateAsync(a.PartnerProfile.UserId, ct);
                wallets.Post(wallet, WalletBucket.Debt, LedgerDirection.Credit, WalletTransactionType.Commission, debt,
                    $"Hoa hồng đơn {order.Code}", key, order.Id, a.Id, cash.Id);
            }
        }
        order.PaymentStatus = (byte)PaymentStatus.Paid;
        if (by == ActorType.Customer)
        {
            order.ConfirmedAt = now;
        }
        order.CompletedAt ??= now;
        order.DisputeDeadlineAt = order.CompletedAt.Value.AddHours(72);
        order.UnpaidReportDeadlineAt = order.CompletedAt.Value.AddHours(await configs.GetIntAsync("order.unpaid_report_window_hours", 24, ct));
        return changes;
    }

    /// <summary>2.6.1 COD: the partner holds C_i in cash, keeps E_i, owes N_i = C_i − E_i (= K_i with F = D = O = 0).</summary>
    public static decimal Settlement(OrderAssignment a) => a.CustomerPayable - a.Earning;

    private async Task PublishAsync(Order order, List<(OrderAssignment Assignment, OrderStatusChangedDto Change)> changes, string message, CancellationToken ct)
    {
        foreach (var (assignment, change) in changes)
        {
            await workflow.PublishAsync(order, assignment, change, message, ct);
        }
    }
}
