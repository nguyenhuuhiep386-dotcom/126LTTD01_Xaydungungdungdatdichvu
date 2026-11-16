using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;
using Servio.Api.Hubs;
using Servio.Api.Services.Files;
using Servio.Api.Services.Requests;

namespace Servio.Api.Services.Orders;

/// <summary>
/// F-ORD-09 extra charges: #73 the partner asks (ARRIVED or IN_PROGRESS), #74 the customer approves or rejects once.
/// Only approved charges count: they are added to the assignment and the order is repriced with the snapshot rate (2.6.1).
/// </summary>
public sealed class ExtraChargeService(
    ServioDbContext db,
    OrderWorkflow workflow,
    FileService files,
    IRealtimeNotifier notifier,
    TimeProvider clock)
{
    public async Task<ExtraChargeDto> CreateAsync(Guid userId, Guid orderId, CreateExtraChargeRequest input, CancellationToken ct)
    {
        var description = input.Description.Trim();
        ContentGuard.EnsureClean(description, "description");
        var (order, assignment) = await workflow.FindPartnerPartAsync(userId, orderId, input.AssignmentId, ct);
        if (assignment.Status is not ((byte)OrderStatus.Arrived or (byte)OrderStatus.InProgress))
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.InvalidStatusTransition,
                "Chỉ thêm phát sinh khi đã đến nơi hoặc đang làm việc");
        }
        var evidence = string.IsNullOrWhiteSpace(input.EvidenceImageUrl)
            ? null
            : FileService.UrlOf(await files.ResolveOwnedAsync(userId, input.EvidenceImageUrl, "evidenceImageUrl", ct, FilePurpose.OrderImage));

        var now = clock.GetUtcNow();
        var charge = new OrderExtraCharge
        {
            Id = Guid.CreateVersion7(),
            OrderId = order.Id,
            OrderAssignmentId = assignment.Id,
            Description = description,
            Amount = input.Amount,
            EvidenceImageUrl = evidence,
            Status = (byte)ExtraChargeStatus.Pending,
            CreatedAt = now,
        };
        db.OrderExtraCharges.Add(charge);
        // Touching the assignment puts its rowversion in this save, so a #70 complete running at the same moment
        // gets a 409 instead of finishing with a charge still pending.
        assignment.UpdatedAt = now;
        await db.SaveChangesAsync(ct);

        await notifier.OrderEventAsync([(AppFlavor.Customer, order.Customer.UserId)], RealtimeEvents.ExtraChargeRequested,
            new { orderId = order.Id, extraChargeId = charge.Id, amount = input.Amount, description }, ct);
        await workflow.SystemMessageAsync(order.ServiceRequestId, assignment.PartnerProfileId,
            $"Đối tác đề nghị phát sinh {Money.Vnd(input.Amount)}: {description}. Vui lòng duyệt trong màn hình đơn.", ct);
        await workflow.NotifyOrderAsync(order, [(AppFlavor.Customer, order.Customer.UserId)],
            $"Đối tác đề nghị phát sinh {Money.Vnd(input.Amount)}: {description}. Bấm để duyệt.", ct);
        return OrderService.ToDto(charge);
    }

    public async Task<ExtraChargeDto> RespondAsync(Guid userId, Guid orderId, Guid chargeId, RespondExtraChargeRequest input, CancellationToken ct)
    {
        var note = string.IsNullOrWhiteSpace(input.Note) ? null : input.Note.Trim();
        ContentGuard.EnsureClean(note, "note");
        var order = await workflow.FindAsync(orderId, ct);
        if (order is null || order.Customer.UserId != userId)
        {
            throw ApiException.NotFound("Không tìm thấy đơn");
        }
        OrderWorkflow.EnsureNotDisputed(order);
        var charge = await db.OrderExtraCharges.FirstOrDefaultAsync(e => e.Id == chargeId && e.OrderId == orderId, ct)
                     ?? throw ApiException.NotFound("Không tìm thấy khoản phát sinh");
        var assignment = order.OrderAssignments.Single(a => a.Id == charge.OrderAssignmentId);
        if (charge.Status != (byte)ExtraChargeStatus.Pending)
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.InvalidStatusTransition, "Khoản phát sinh đã được xử lý");
        }
        // Never reprice a part that is finished or cancelled.
        if (assignment.Status is not ((byte)OrderStatus.Arrived or (byte)OrderStatus.InProgress))
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.InvalidStatusTransition, "Phần việc này không còn nhận phát sinh");
        }
        var approved = input.Approved!.Value;

        var now = clock.GetUtcNow();
        charge.Status = (byte)(approved ? ExtraChargeStatus.Approved : ExtraChargeStatus.Rejected);
        charge.RespondedAt = now;
        charge.RespondedByUserId = userId;
        charge.ResponseNote = note;
        if (approved)
        {
            assignment.ExtraChargeTotal += charge.Amount;
            assignment.UpdatedAt = now;
            OrderWorkflow.Reprice(order);
            order.UpdatedAt = now;
        }
        // Two approvals at the same time both touch the assignment row: its rowversion turns the second into a 409.
        await db.SaveChangesAsync(ct);

        var dto = OrderService.ToDto(charge);
        await notifier.OrderEventAsync([(AppFlavor.Partner, assignment.PartnerProfile.UserId)], RealtimeEvents.ExtraChargeResponded, dto, ct);
        await workflow.SystemMessageAsync(order.ServiceRequestId, assignment.PartnerProfileId,
            $"Khách đã {(approved ? "duyệt" : "từ chối")} phát sinh {Money.Vnd(charge.Amount)}: {charge.Description}.", ct);
        await workflow.NotifyOrderAsync(order, [(AppFlavor.Partner, assignment.PartnerProfile.UserId)],
            $"Khách đã {(approved ? "duyệt" : "từ chối")} phát sinh {Money.Vnd(charge.Amount)}.", ct);
        return dto;
    }
}
