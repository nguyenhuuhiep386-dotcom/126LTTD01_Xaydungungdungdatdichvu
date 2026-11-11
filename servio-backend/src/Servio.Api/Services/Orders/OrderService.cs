using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;
using Servio.Api.Hubs;
using Servio.Api.Services.Partners;
using Servio.Api.Services.Quotes;
using Servio.Api.Services.Requests;

namespace Servio.Api.Services.Orders;

/// <summary>
/// M5 orders, customer and read side: #43 accept a quote (spec 3.3.2 steps 3–6, 0.2.7 ý 3), #62 list, #63 detail,
/// #64 timeline. Partner actions are in <see cref="AssignmentService"/>, extra charges in <see cref="ExtraChargeService"/>.
/// </summary>
public sealed class OrderService(
    ServioDbContext db,
    OrderWorkflow workflow,
    CodeGenerator codes,
    SystemConfigService configs,
    IRealtimeNotifier notifier,
    TimeProvider clock)
{
    public const int MaxPageSize = 50;

    private static readonly List<byte> SlotStatuses = OrderStateMachine.SlotStatuses.Select(s => (byte)s).ToList();
    private static readonly List<byte> ScheduleStatuses = OrderStateMachine.ScheduleStatuses.Select(s => (byte)s).ToList();
    private static readonly List<byte> OpenDisputes = [(byte)DisputeStatus.Open, (byte)DisputeStatus.InReview];

    /// <summary>
    /// #43: one SQL transaction checks the request is OPEN, the quote is PENDING and unchanged, and the partner has a free
    /// slot and no overlapping booking; then creates the order with one PENDING assignment (10-minute deadline), accepts
    /// the quote, rejects the others, marks the request MATCHED and freezes the other conversations.
    /// Calling it again with the same quote returns the same order (double tap / retry).
    /// </summary>
    public async Task<OrderDetailDto> AcceptQuoteAsync(Guid userId, Guid requestId, AcceptQuotesRequest input, CancellationToken ct)
    {
        if (input.QuoteIds.Length != 1)
        {
            throw Invalid("Mỗi yêu cầu chỉ chọn được 1 báo giá", "quoteIds");
        }
        var quoteId = input.QuoteIds[0];
        if (!input.QuoteVersions.TryGetValue(quoteId, out var version))
        {
            throw Invalid("Thiếu phiên bản của báo giá đã chọn", "quoteVersions");
        }

        var request = await db.ServiceRequests.Include(r => r.Customer).Include(r => r.ServiceCategory)
                          .FirstOrDefaultAsync(r => r.Id == requestId && r.Customer.UserId == userId, ct)
                      ?? throw ApiException.NotFound("Không tìm thấy yêu cầu");
        if (await ExistingOrderAsync(userId, requestId, quoteId, ct) is { } done)
        {
            return done;
        }
        var now = clock.GetUtcNow();
        QuoteService.EnsureOpen(request, now);

        await using var tx = await db.Database.BeginTransactionAsync(ct);
        var partnerId = await db.Quotes.Where(q => q.Id == quoteId && q.ServiceRequestId == requestId)
                            .Select(q => (Guid?)q.PartnerProfileId).FirstOrDefaultAsync(ct)
                        ?? throw ApiException.NotFound("Không tìm thấy báo giá");
        // From here accepts for this partner run one at a time. A double tap waits for the first call to commit and then
        // finds its order, instead of failing on the slot or schedule the first call just took.
        await LockPartnerAsync(partnerId, ct);
        if (await ExistingOrderAsync(userId, requestId, quoteId, ct) is { } replay)
        {
            return replay;
        }
        var quote = await db.Quotes.Include(q => q.PartnerProfile).ThenInclude(p => p.User).FirstAsync(q => q.Id == quoteId, ct);
        if (quote.Status != (byte)QuoteStatus.Pending || quote.RequestRevision != request.Revision
            || Convert.ToBase64String(quote.RowVersion) != version)
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.ResourceVersionConflict,
                "Báo giá đã thay đổi hoặc không còn hiệu lực, vui lòng tải lại");
        }
        var partner = quote.PartnerProfile;
        if (partner.User.Status != (byte)UserStatus.Active || partner.VerificationStatus != (byte)PartnerVerificationStatus.Approved)
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.PartnerNotEligible,
                "Đối tác này hiện không nhận việc, hãy chọn báo giá khác");
        }
        await EnsurePartnerFreeAsync(partner.Id, quote, ct);

        var order = await CreateOrderAsync(request, quote, userId, now, ct);
        var rejected = await CloseOtherQuotesAsync(request, quote, order, now, ct);
        try
        {
            await db.SaveChangesAsync(ct);
        }
        catch (DbUpdateException e) when (e.IsUniqueViolation())
        {
            // UNIQUE Orders.ServiceRequestId: an accept of another quote (another partner, so no shared lock) won the race.
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.QuoteAlreadyAccepted, "Yêu cầu đã được chọn báo giá");
        }
        await tx.CommitAsync(ct);

        var assignment = order.OrderAssignments.Single();
        await notifier.OrderEventAsync([(AppFlavor.Partner, partner.UserId)], RealtimeEvents.QuoteAccepted, new { quoteId, orderId = order.Id }, ct);
        foreach (var other in rejected)
        {
            await notifier.OrderEventAsync([(AppFlavor.Partner, other.PartnerProfile.UserId)], RealtimeEvents.QuoteRejected,
                new { quoteId = other.Id, orderId = (Guid?)null }, ct);
        }
        await workflow.SystemMessageAsync(request.Id, partner.Id,
            $"Khách đã chọn báo giá của bạn. Hãy xác nhận đơn {order.Code} trước {OrderWorkflow.LocalTime(assignment.AcceptanceDeadlineAt)}.", ct);
        return await GetAsync(userId, AppFlavor.Customer, order.Id, ct);
    }

    /// <summary>#62: my orders in the app of the token, newest first.</summary>
    public async Task<PagedResult<OrderSummaryDto>> ListMineAsync(Guid userId, AppFlavor flavor, OrderListStatus? status,
        int page, int pageSize, CancellationToken ct)
    {
        (page, pageSize) = (Math.Max(page, 1), Math.Clamp(pageSize, 1, MaxPageSize));
        List<byte>? statuses = status switch
        {
            OrderListStatus.Active => OrderStateMachine.ActiveOrderStatuses.Select(s => (byte)s).ToList(),
            OrderListStatus.Completed => [(byte)OrderStatus.Completed],
            OrderListStatus.Cancelled => [(byte)OrderStatus.Cancelled],
            _ => null,
        };
        // One row per order: the customer sees it through the primary assignment, a partner through their own.
        var query = flavor == AppFlavor.Customer
            ? db.OrderAssignments.AsNoTracking().Where(a => a.Order.Customer.UserId == userId && a.IsPrimary)
            : db.OrderAssignments.AsNoTracking().Where(a => a.PartnerProfile.UserId == userId);
        query = query.Where(a => statuses == null || statuses.Contains(a.Order.Status));
        var total = await query.CountAsync(ct);
        var rows = await query
            .OrderByDescending(a => a.Order.CreatedAt).ThenByDescending(a => a.OrderId)
            .Skip((page - 1) * pageSize).Take(pageSize)
            .Select(a => new
            {
                a.Order, a.Order.ServiceRequest.Title, CategoryName = a.Order.ServiceCategory.Name,
                a.Id, a.Status, a.AcceptedAt, a.Earning, a.PartnerProfileId,
                PartnerName = a.PartnerProfile.User.FullName, PartnerAvatar = a.PartnerProfile.User.AvatarUrl, PartnerRating = a.PartnerProfile.AverageRating,
                CustomerName = a.Order.Customer.User.FullName, CustomerAvatar = a.Order.Customer.User.AvatarUrl, CustomerRating = a.Order.Customer.AverageRating,
            })
            .ToListAsync(ct);
        var items = rows.Select(r => new OrderSummaryDto(
            r.Order.Id, r.Order.Code, (OrderStatus)r.Order.Status, r.Order.ServiceRequestId, r.Title, r.CategoryName,
            r.Order.ScheduledStartAt, (long)r.Order.TotalAmount,
            flavor == AppFlavor.Partner ? (long)r.Earning : null,
            flavor == AppFlavor.Customer
                ? new OrderPartyDto(r.PartnerProfileId, r.PartnerName, r.PartnerAvatar, r.PartnerRating, null)
                : new OrderPartyDto(null, CustomerName(r.CustomerName, r.AcceptedAt), r.CustomerAvatar, r.CustomerRating, null),
            r.Id, (OrderStatus)r.Status, r.Order.CreatedAt, r.Order.UpdatedAt)).ToList();
        return new PagedResult<OrderSummaryDto>(items, total, page, pageSize);
    }

    /// <summary>#63: the customer sees the whole order; a partner only their own assignment (404 for anyone else).</summary>
    public async Task<OrderDetailDto> GetAsync(Guid userId, AppFlavor flavor, Guid orderId, CancellationToken ct)
    {
        var order = await db.Orders.AsNoTracking().AsSplitQuery()
                        .Include(o => o.ServiceRequest)
                        .Include(o => o.ServiceCategory)
                        .Include(o => o.Customer).ThenInclude(c => c.User)
                        .Include(o => o.OrderAssignments).ThenInclude(a => a.PartnerProfile).ThenInclude(p => p.User)
                        .Include(o => o.OrderAssignments).ThenInclude(a => a.OrderImages)
                        .Include(o => o.OrderExtraCharges)
                        .Where(o => o.Id == orderId)
                        .OrderBy(o => o.Id) // split queries need a stable order even for one row (EF warning 10102)
                        .FirstOrDefaultAsync(ct)
                    ?? throw ApiException.NotFound("Không tìm thấy đơn");
        var visible = flavor == AppFlavor.Customer
            ? order.Customer.UserId == userId ? order.OrderAssignments.ToList() : []
            : order.OrderAssignments.Where(a => a.PartnerProfile.UserId == userId).ToList();
        if (visible.Count == 0)
        {
            throw ApiException.NotFound("Không tìm thấy đơn");
        }

        var mine = flavor == AppFlavor.Partner ? visible[0] : null;
        var primary = mine ?? visible.FirstOrDefault(a => a.IsPrimary) ?? visible[0];
        var contact = OrderStateMachine.ContactVisible((OrderStatus)primary.Status);
        var conversationId = await db.Conversations.AsNoTracking()
            .Where(c => c.ServiceRequestId == order.ServiceRequestId && c.PartnerProfileId == primary.PartnerProfileId)
            .Select(c => (Guid?)c.Id).FirstOrDefaultAsync(ct);
        var showAddress = mine is null || mine.AcceptedAt is not null;
        var customerUser = order.Customer.User;
        var assignmentIds = visible.Select(a => a.Id).ToHashSet();

        return new OrderDetailDto(
            order.Id, order.Code, (OrderStatus)order.Status, (PaymentMethod)order.PaymentMethod, (PaymentStatus)order.PaymentStatus,
            order.ServiceRequestId, order.ServiceRequest.Title, order.ServiceRequest.Description, order.ServiceCategoryId, order.ServiceCategory.Name,
            showAddress ? order.ServiceRequest.AddressSnapshot : Matching.AreaLabel(order.ServiceRequest.AddressSnapshot),
            showAddress ? order.ServiceRequest.Latitude : null,
            showAddress ? order.ServiceRequest.Longitude : null,
            order.ScheduledStartAt,
            mine is null
                ? new OrderPricingDto((long)order.SubTotal, (long)order.ExtraChargeTotal, (long)order.TotalAmount, null, null, null)
                : new OrderPricingDto((long)mine.Amount, (long)mine.ExtraChargeTotal, (long)mine.CustomerPayable,
                    mine.CommissionRateSnapshot, (long)mine.CommissionAmount, (long)mine.Earning),
            new OrderPartyDto(null,
                mine is null ? customerUser.FullName : CustomerName(customerUser.FullName, mine.AcceptedAt),
                customerUser.AvatarUrl, order.Customer.AverageRating,
                mine is not null && contact ? customerUser.PhoneNumber : null),
            visible.Select(a => ToDto(a, showPhone: mine is null && OrderStateMachine.ContactVisible((OrderStatus)a.Status))).ToList(),
            order.OrderExtraCharges.Where(e => assignmentIds.Contains(e.OrderAssignmentId)).OrderBy(e => e.CreatedAt).Select(ToDto).ToList(),
            conversationId,
            order.AcceptedAt, order.StartedAt, order.CompletedByPartnerAt, order.AutoConfirmAt, order.CompletedAt, order.CancelledAt,
            (ActorType?)order.CancelledBy, order.CancelReason,
            Convert.ToBase64String(order.RowVersion), order.CreatedAt, order.UpdatedAt);
    }

    /// <summary>#64: status history, oldest first. A partner sees the order rows and their own assignment rows.</summary>
    public async Task<IReadOnlyList<TimelineEntryDto>> TimelineAsync(Guid userId, AppFlavor flavor, Guid orderId, CancellationToken ct)
    {
        var member = await db.Orders.AsNoTracking()
            .Where(o => o.Id == orderId)
            .Select(o => new
            {
                IsCustomer = o.Customer.UserId == userId,
                MyAssignmentId = o.OrderAssignments.Where(a => a.PartnerProfile.UserId == userId).Select(a => (Guid?)a.Id).FirstOrDefault(),
            })
            .FirstOrDefaultAsync(ct);
        var allowed = flavor == AppFlavor.Customer ? member?.IsCustomer == true : member?.MyAssignmentId is not null;
        if (!allowed)
        {
            throw ApiException.NotFound("Không tìm thấy đơn");
        }
        var mine = flavor == AppFlavor.Partner ? member!.MyAssignmentId : null;
        return await db.OrderStatusHistories.AsNoTracking()
            .Where(h => h.OrderId == orderId && (mine == null || h.OrderAssignmentId == null || h.OrderAssignmentId == mine))
            .OrderBy(h => h.CreatedAt).ThenBy(h => h.Id)
            .Select(h => new TimelineEntryDto((OrderStatus)h.ToStatus, (OrderStatus?)h.FromStatus, h.OrderAssignmentId,
                (ActorType)h.ChangedByType, h.Note, h.CreatedAt))
            .ToListAsync(ct);
    }

    public static AssignmentDto ToDto(OrderAssignment a, bool showPhone) => new(
        a.Id, (OrderStatus)a.Status, a.IsPrimary,
        new OrderPartyDto(a.PartnerProfileId, a.PartnerProfile.User.FullName, a.PartnerProfile.User.AvatarUrl, a.PartnerProfile.AverageRating,
            showPhone ? a.PartnerProfile.User.PhoneNumber : null),
        (long)a.Amount, (long)a.ExtraChargeTotal, a.AcceptanceDeadlineAt, a.ReservedStartAt, a.ReservedEndAt,
        a.AcceptedAt, a.OnTheWayAt, a.ArrivedAt, a.StartedAt, a.CompletedAt, a.CancelledAt, a.CancelReason,
        Images(a, OrderImageType.Before), Images(a, OrderImageType.After));

    public static ExtraChargeDto ToDto(OrderExtraCharge e) => new(
        e.Id, e.OrderId, e.OrderAssignmentId, e.Description, (long)e.Amount, e.EvidenceImageUrl, (ExtraChargeStatus)e.Status,
        e.ResponseNote, e.RespondedAt, e.CreatedAt);

    /// <summary>The partner sees "An N." like in the feed until they have accepted the order.</summary>
    private static string CustomerName(string fullName, DateTimeOffset? acceptedAt) =>
        acceptedAt is null ? PartnerPublicService.ShortName(fullName) : fullName;

    private static List<string> Images(OrderAssignment a, OrderImageType type) =>
        a.OrderImages.Where(i => i.Type == (byte)type).OrderBy(i => i.CreatedAt).Select(i => i.Url).ToList();

    /// <summary>The order already made from this request: same quote → that order (retry), another quote → 409.</summary>
    private async Task<OrderDetailDto?> ExistingOrderAsync(Guid userId, Guid requestId, Guid quoteId, CancellationToken ct)
    {
        var existing = await db.Orders.AsNoTracking().Where(o => o.ServiceRequestId == requestId)
            .Select(o => new { o.Id, QuoteId = o.OrderAssignments.Select(a => a.QuoteId).FirstOrDefault() })
            .FirstOrDefaultAsync(ct);
        if (existing is null)
        {
            return null;
        }
        return existing.QuoteId == quoteId
            ? await GetAsync(userId, AppFlavor.Customer, existing.Id, ct)
            : throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.QuoteAlreadyAccepted, "Bạn đã chọn một báo giá khác cho yêu cầu này");
    }

    /// <summary>
    /// Serializes accepts for the same partner so two customers cannot both take the last slot. The row lock lives until
    /// the transaction ends. InMemory tests have no locks and run one call at a time.
    /// </summary>
    private async Task LockPartnerAsync(Guid partnerProfileId, CancellationToken ct)
    {
        if (db.Database.IsRelational())
        {
            await db.Database.ExecuteSqlInterpolatedAsync(
                $"SELECT Id FROM dbo.PartnerProfiles WITH (UPDLOCK, ROWLOCK) WHERE Id = {partnerProfileId}", ct);
        }
    }

    /// <summary>F-FEED-10: at most N slot-holding assignments (not counting open UNPAID_CASH disputes) and no overlap.</summary>
    private async Task EnsurePartnerFreeAsync(Guid partnerProfileId, Quote quote, CancellationToken ct)
    {
        var max = await configs.GetIntAsync("order.max_active_assignments_per_partner", 3, ct);
        var busy = await db.OrderAssignments.AsNoTracking()
            .Where(a => a.PartnerProfileId == partnerProfileId && SlotStatuses.Contains(a.Status)
                        && !a.Order.Disputes.Any(d => d.Reason == (byte)DisputeReason.UnpaidCash && OpenDisputes.Contains(d.Status)))
            .Select(a => new { a.Status, a.ReservedStartAt, a.ReservedEndAt })
            .ToListAsync(ct);
        if (busy.Count >= max)
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.PartnerCapacityExceeded,
                $"Đối tác đang nhận đủ {max} việc, hãy chọn báo giá khác");
        }
        if (busy.Any(b => ScheduleStatuses.Contains(b.Status) && b.ReservedStartAt is { } start && b.ReservedEndAt is { } end
                          && OrderStateMachine.Overlaps(start, end, quote.AvailableFrom, quote.EstimatedEndAt)))
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.ScheduleConflict,
                "Đối tác đã có lịch trùng khung giờ này, hãy chọn báo giá khác");
        }
    }

    private async Task<Order> CreateOrderAsync(ServiceRequest request, Quote quote, Guid userId, DateTimeOffset now, CancellationToken ct)
    {
        var rate = request.ServiceCategory.CommissionRate ?? await configs.GetDecimalAsync("commission.default_rate", 15, ct);
        var timeout = await configs.GetIntAsync("order.partner_accept_timeout_minutes", 10, ct);
        var order = new Order
        {
            Id = Guid.CreateVersion7(),
            Code = await codes.NextAsync(CodeGenerator.Order, ct),
            ServiceRequestId = request.Id,
            CustomerId = request.CustomerId,
            ServiceCategoryId = request.ServiceCategoryId,
            Status = (byte)OrderStatus.Pending,
            PaymentMethod = (byte)PaymentMethod.Cash,
            PaymentStatus = (byte)PaymentStatus.Unpaid,
            ScheduledStartAt = quote.AvailableFrom,
            CreatedAt = now,
            UpdatedAt = now,
        };
        var assignment = new OrderAssignment
        {
            Id = Guid.CreateVersion7(),
            PartnerProfileId = quote.PartnerProfileId,
            QuoteId = quote.Id,
            Status = (byte)OrderStatus.Pending,
            IsPrimary = true,
            Amount = quote.Amount,
            CommissionRateSnapshot = rate,
            PaymentStatus = (byte)PaymentStatus.Unpaid,
            AcceptanceDeadlineAt = now.AddMinutes(timeout),
            ReservedStartAt = quote.AvailableFrom,
            ReservedEndAt = quote.EstimatedEndAt,
            CreatedAt = now,
            UpdatedAt = now,
        };
        order.OrderAssignments.Add(assignment);
        OrderWorkflow.Reprice(order);
        order.OrderStatusHistories.Add(new OrderStatusHistory
        {
            Id = Guid.CreateVersion7(),
            OrderAssignment = assignment,
            ToStatus = (byte)OrderStatus.Pending,
            ChangedByType = (byte)ActorType.Customer,
            ChangedByUserId = userId,
            Note = "Khách chọn báo giá",
            CreatedAt = now,
        });
        db.Orders.Add(order);
        return order;
    }

    /// <summary>Quote → ACCEPTED, other pending quotes → REJECTED, request → MATCHED, chats: chosen one linked, others READ_ONLY.</summary>
    private async Task<List<Quote>> CloseOtherQuotesAsync(ServiceRequest request, Quote chosen, Order order, DateTimeOffset now, CancellationToken ct)
    {
        chosen.Status = (byte)QuoteStatus.Accepted;
        chosen.RespondedAt = now;
        chosen.UpdatedAt = now;
        var others = await db.Quotes.Include(q => q.PartnerProfile)
            .Where(q => q.ServiceRequestId == request.Id && q.Id != chosen.Id && q.Status == (byte)QuoteStatus.Pending)
            .ToListAsync(ct);
        foreach (var q in others)
        {
            q.Status = (byte)QuoteStatus.Rejected;
            q.RespondedAt = now;
            q.UpdatedAt = now;
        }
        request.Status = (byte)ServiceRequestStatus.Matched;
        request.UpdatedAt = now;

        var conversations = await db.Conversations.Where(c => c.ServiceRequestId == request.Id).ToListAsync(ct);
        foreach (var c in conversations)
        {
            if (c.PartnerProfileId == chosen.PartnerProfileId)
            {
                c.Order = order;
            }
            else if (c.Status == (byte)ConversationStatus.Active)
            {
                c.Status = (byte)ConversationStatus.ReadOnly;
            }
            c.UpdatedAt = now;
        }
        return others;
    }

    private static ApiException Invalid(string message, string field) =>
        new(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, message, field);
}
