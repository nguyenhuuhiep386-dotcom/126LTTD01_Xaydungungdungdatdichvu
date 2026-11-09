using System.Globalization;
using System.Text;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;
using Servio.Api.Hubs;
using Servio.Api.Services.Feed;
using Servio.Api.Services.Files;
using Servio.Api.Services.Partners;

namespace Servio.Api.Services.Chat;

/// <summary>
/// M4 chat (#54–#59, #61, hub /hubs/chat), course scope 0.2.3: text + image, SENT/READ only (no typing, no delivered),
/// messages in the SQL table Messages. A user acts on the side of the app the token was issued for: the customer app
/// sees conversations where the user is the customer, the partner app where the user is the partner.
/// </summary>
public sealed class ChatService(
    ServioDbContext db,
    FeedService feed,
    FileService files,
    IRealtimeNotifier notifier,
    TimeProvider clock)
{
    public const int MaxPageSize = 50;
    public const int DefaultMessageLimit = 30;
    public const int MaxMessageLimit = 100;
    private const int PreviewLength = 200;

    public async Task<PagedResult<ConversationDto>> ListAsync(
        Guid userId, AppFlavor flavor, ConversationStatus? status, int page, int pageSize, CancellationToken ct)
    {
        (page, pageSize) = (Math.Max(page, 1), Math.Clamp(pageSize, 1, MaxPageSize));
        var query = Mine(userId, flavor).Where(c => status == null || c.Status == (byte)status);
        var total = await query.CountAsync(ct);
        var rows = await Project(query.OrderByDescending(c => c.LastMessageAt ?? c.CreatedAt).ThenByDescending(c => c.Id)
                .Skip((page - 1) * pageSize).Take(pageSize), userId)
            .ToListAsync(ct);
        return new PagedResult<ConversationDto>(rows.Select(r => ToDto(r, userId, flavor)).ToList(), total, page, pageSize);
    }

    public async Task<ConversationDto> GetAsync(Guid userId, AppFlavor flavor, Guid conversationId, CancellationToken ct)
    {
        var row = await Project(Mine(userId, flavor).Where(c => c.Id == conversationId), userId).FirstOrDefaultAsync(ct)
                  ?? throw ApiException.NotFound("Không tìm thấy hội thoại");
        return ToDto(row, userId, flavor);
    }

    /// <summary>
    /// #56 (F-CHAT-01): one conversation per (post, partner). A customer may only open it with a partner who quoted
    /// the post; a partner may open it for an OPEN post they are eligible for (to ask before quoting).
    /// </summary>
    public async Task<ConversationDto> OpenAsync(Guid userId, AppFlavor flavor, OpenConversationRequest input, CancellationToken ct)
    {
        var existing = await db.Conversations.AsNoTracking()
            .Where(c => c.ServiceRequestId == input.ServiceRequestId && c.PartnerProfileId == input.PartnerProfileId)
            .Select(c => new { c.Id, CustomerUserId = c.Customer.UserId, PartnerUserId = c.PartnerProfile.UserId })
            .FirstOrDefaultAsync(ct);
        if (existing is not null)
        {
            var member = flavor == AppFlavor.Customer ? existing.CustomerUserId : existing.PartnerUserId;
            return member == userId ? await GetAsync(userId, flavor, existing.Id, ct) : throw ApiException.NotFound("Không tìm thấy hội thoại");
        }

        var request = await db.ServiceRequests.AsNoTracking()
                          .Where(r => r.Id == input.ServiceRequestId)
                          .Select(r => new { r.Id, r.CustomerId, CustomerUserId = r.Customer.UserId })
                          .FirstOrDefaultAsync(ct)
                      ?? throw ApiException.NotFound("Không tìm thấy bài đăng");
        if (flavor == AppFlavor.Customer)
        {
            if (request.CustomerUserId != userId)
            {
                throw ApiException.NotFound("Không tìm thấy bài đăng");
            }
            if (!await db.Quotes.AnyAsync(q => q.ServiceRequestId == request.Id && q.PartnerProfileId == input.PartnerProfileId, ct))
            {
                throw ApiException.Forbidden("Bạn chỉ nhắn được với đối tác đã báo giá cho yêu cầu này");
            }
        }
        else
        {
            var partner = await feed.LoadPartnerAsync(userId, ct);
            var row = await feed.LoadRowAsync(request.Id, partner.Profile.Id, ct);
            var eligible = partner.Profile.Id == input.PartnerProfileId && partner.IsApproved && row is not null
                           && row.Status == (byte)ServiceRequestStatus.Open && row.ExpiresAt > clock.GetUtcNow()
                           && FeedService.Evaluate(partner, row) is not null;
            if (!eligible)
            {
                throw new ApiException(StatusCodes.Status403Forbidden, ErrorCodes.PartnerNotEligible, "Bạn không thể nhắn tin về bài đăng này");
            }
        }

        var now = clock.GetUtcNow();
        var conversation = new Conversation
        {
            Id = Guid.CreateVersion7(),
            ServiceRequestId = request.Id,
            CustomerId = request.CustomerId,
            PartnerProfileId = input.PartnerProfileId,
            Status = (byte)ConversationStatus.Active,
            CreatedAt = now,
            UpdatedAt = now,
        };
        db.Conversations.Add(conversation);
        try
        {
            await db.SaveChangesAsync(ct);
        }
        catch (DbUpdateException e) when (e is not DbUpdateConcurrencyException)
        {
            // Both sides opened it at the same time: unique (ServiceRequestId, PartnerProfileId) kept one.
            db.Entry(conversation).State = EntityState.Detached;
            var id = await db.Conversations.Where(c => c.ServiceRequestId == request.Id && c.PartnerProfileId == input.PartnerProfileId)
                .Select(c => c.Id).FirstAsync(ct);
            return await GetAsync(userId, flavor, id, ct);
        }
        return await GetAsync(userId, flavor, conversation.Id, ct);
    }

    /// <summary>#57: history (cursor, newest first) or sync (after, oldest first). Not both.</summary>
    public async Task<MessagePage> GetMessagesAsync(
        Guid userId, AppFlavor flavor, Guid conversationId, string? cursor, string? after, int? limit, CancellationToken ct)
    {
        var member = await LoadMemberAsync(userId, flavor, conversationId, track: false, ct);
        if (cursor is not null && after is not null)
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, "Chỉ dùng cursor hoặc after, không dùng cả hai", "after");
        }
        var take = Math.Clamp(limit ?? DefaultMessageLimit, 1, MaxMessageLimit);
        var messages = db.Messages.AsNoTracking().Where(m => m.ConversationId == conversationId);

        if (after is not null)
        {
            var since = DecodeCursor(after, "after");
            var newer = await messages.Where(m => m.CreatedAt > since).OrderBy(m => m.CreatedAt).Take(take + 1).ToListAsync(ct);
            var page = newer.Take(take).Select(m => ToDto(m, member.PartnerUserId)).ToList();
            return new MessagePage(page, null, page.Count > 0 ? EncodeCursor(page[^1].CreatedAt) : after, newer.Count > take);
        }

        if (cursor is not null)
        {
            var before = DecodeCursor(cursor, "cursor");
            messages = messages.Where(m => m.CreatedAt < before);
        }
        var older = await messages.OrderByDescending(m => m.CreatedAt).Take(take + 1).ToListAsync(ct);
        var items = older.Take(take).Select(m => ToDto(m, member.PartnerUserId)).ToList();
        var hasMore = older.Count > take;
        return new MessagePage(
            items,
            hasMore ? EncodeCursor(items[^1].CreatedAt) : null,
            items.Count > 0 ? EncodeCursor(items[0].CreatedAt) : null,
            hasMore);
    }

    /// <summary>
    /// #58 and hub SendMessage. Idempotent on clientMessageId: the same message sent twice returns the stored one;
    /// the same clientMessageId with different content is 409. Both members get ReceiveMessage.
    /// </summary>
    public async Task<MessageDto> SendAsync(Guid userId, AppFlavor flavor, Guid conversationId, SendMessageRequest input, CancellationToken ct)
    {
        var member = await LoadMemberAsync(userId, flavor, conversationId, track: true, ct);
        var conversation = member.Conversation;
        var content = input.Content?.Trim() ?? "";
        string? attachment = null;
        switch (input.Type)
        {
            case MessageType.Text when content.Length is > 0 and <= 2000:
                break;
            case MessageType.Image when !string.IsNullOrWhiteSpace(input.AttachmentUrl) && content.Length <= 2000:
                attachment = FileService.UrlOf(await files.ResolveOwnedAsync(userId, input.AttachmentUrl, "attachmentUrl", ct, FilePurpose.ChatImage));
                break;
            default:
                throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError,
                    "Tin nhắn văn bản cần nội dung 1–2000 ký tự; tin nhắn ảnh cần attachmentUrl", "content");
        }

        var duplicate = await db.Messages.AsNoTracking().FirstOrDefaultAsync(m =>
            m.ConversationId == conversationId && m.SenderUserId == userId && m.ClientMessageId == input.ClientMessageId, ct);
        if (duplicate is not null)
        {
            return duplicate.Content == content && duplicate.AttachmentUrl == attachment
                ? ToDto(duplicate, member.PartnerUserId)
                : throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.Conflict, "clientMessageId đã dùng cho tin nhắn khác", "clientMessageId");
        }
        if (conversation.Status != (byte)ConversationStatus.Active)
        {
            throw new ApiException(StatusCodes.Status409Conflict, ErrorCodes.ConversationReadOnly, "Hội thoại đã đóng, bạn chỉ xem được tin nhắn cũ");
        }

        var message = await AddMessageAsync(conversation, userId, input.ClientMessageId, input.Type, content, attachment, ct);
        try
        {
            await db.SaveChangesAsync(ct);
        }
        catch (DbUpdateException e) when (e is not DbUpdateConcurrencyException)
        {
            // Two retries raced past the duplicate check; UQ_Messages_Client kept the first.
            db.ChangeTracker.Clear();
            return ToDto(await db.Messages.AsNoTracking().FirstAsync(m =>
                m.ConversationId == conversationId && m.SenderUserId == userId && m.ClientMessageId == input.ClientMessageId, ct), member.PartnerUserId);
        }

        var dto = ToDto(message, member.PartnerUserId);
        await notifier.MessageReceivedAsync(member.CustomerUserId, member.PartnerUserId, dto, ct);
        return dto;
    }

    /// <summary>#59 / hub MarkRead: messages from the other person up to <paramref name="lastReadMessageId"/> become READ.</summary>
    public async Task MarkReadAsync(Guid userId, AppFlavor flavor, Guid conversationId, Guid lastReadMessageId, CancellationToken ct)
    {
        var member = await LoadMemberAsync(userId, flavor, conversationId, track: false, ct);
        var upTo = await db.Messages.AsNoTracking()
                       .Where(m => m.Id == lastReadMessageId && m.ConversationId == conversationId)
                       .Select(m => (DateTimeOffset?)m.CreatedAt)
                       .FirstOrDefaultAsync(ct)
                   ?? throw ApiException.NotFound("Không tìm thấy tin nhắn");

        var now = clock.GetUtcNow();
        var unread = await db.Messages
            .Where(m => m.ConversationId == conversationId && m.SenderUserId != null && m.SenderUserId != userId
                        && m.ReadAt == null && m.CreatedAt <= upTo)
            .ToListAsync(ct);
        if (unread.Count == 0)
        {
            return;
        }
        unread.ForEach(m => m.ReadAt = now);
        await db.SaveChangesAsync(ct);
        await notifier.MessageReadAsync(member.CustomerUserId, member.PartnerUserId,
            new MessageRead(conversationId, userId, lastReadMessageId), ct);
    }

    /// <summary>#61: unread messages from other people across my conversations of this app (tab badge).</summary>
    public async Task<UnreadCountDto> UnreadCountAsync(Guid userId, AppFlavor flavor, CancellationToken ct) =>
        new(await Mine(userId, flavor)
            .SelectMany(c => c.Messages)
            .CountAsync(m => m.SenderUserId != null && m.SenderUserId != userId && m.ReadAt == null, ct));

    /// <summary>
    /// F-CHAT-06: a SYSTEM message ("Đối tác đã xác nhận đơn"...) visible to both sides. Used by order flows (BE-4).
    /// Saved immediately; returns null when the post has no conversation with that partner.
    /// </summary>
    public async Task<MessageDto?> AddSystemMessageAsync(Guid serviceRequestId, Guid partnerProfileId, string text, CancellationToken ct)
    {
        var row = await db.Conversations
            .Where(c => c.ServiceRequestId == serviceRequestId && c.PartnerProfileId == partnerProfileId)
            .Select(c => new { Conversation = c, CustomerUserId = c.Customer.UserId, PartnerUserId = c.PartnerProfile.UserId })
            .FirstOrDefaultAsync(ct);
        if (row is null)
        {
            return null;
        }
        var message = await AddMessageAsync(row.Conversation, null, Guid.NewGuid(), MessageType.System, text, null, ct);
        await db.SaveChangesAsync(ct);
        var dto = ToDto(message, row.PartnerUserId);
        await notifier.MessageReceivedAsync(row.CustomerUserId, row.PartnerUserId, dto, ct);
        return dto;
    }

    /// <summary>
    /// Adds a message and updates the conversation preview. CreatedAt is kept strictly increasing inside a conversation
    /// so the (CreatedAt) cursor of #57 never skips or repeats a message.
    /// </summary>
    private async Task<Message> AddMessageAsync(Conversation conversation, Guid? senderUserId, Guid clientMessageId,
        MessageType type, string content, string? attachment, CancellationToken ct)
    {
        var now = clock.GetUtcNow();
        // ponytail: two messages committed in the same 100 ns could still tie; add an Id tie-break to the cursor if that ever shows up.
        var last = await db.Messages.Where(m => m.ConversationId == conversation.Id)
            .OrderByDescending(m => m.CreatedAt).Select(m => (DateTimeOffset?)m.CreatedAt).FirstOrDefaultAsync(ct);
        var createdAt = last is { } l && l >= now ? l.AddTicks(1) : now;

        var message = new Message
        {
            Id = Guid.CreateVersion7(),
            ConversationId = conversation.Id,
            SenderUserId = senderUserId,
            ClientMessageId = clientMessageId,
            Type = (byte)type,
            Content = content,
            AttachmentUrl = attachment,
            CreatedAt = createdAt,
        };
        db.Messages.Add(message);
        conversation.LastMessagePreview = Preview(type, content);
        conversation.LastMessageAt = createdAt;
        conversation.LastMessageSenderId = senderUserId;
        conversation.UpdatedAt = now;
        return message;
    }

    private sealed record Membership(Conversation Conversation, Guid CustomerUserId, Guid PartnerUserId);

    /// <summary>The conversation if the user is its member on the side of their app, otherwise 404.</summary>
    private async Task<Membership> LoadMemberAsync(Guid userId, AppFlavor flavor, Guid conversationId, bool track, CancellationToken ct)
    {
        var query = track ? db.Conversations : db.Conversations.AsNoTracking();
        var row = await query
            .Where(c => c.Id == conversationId)
            .Select(c => new Membership(c, c.Customer.UserId, c.PartnerProfile.UserId))
            .FirstOrDefaultAsync(ct);
        var memberUserId = flavor == AppFlavor.Customer ? row?.CustomerUserId : row?.PartnerUserId;
        return row is not null && memberUserId == userId ? row : throw ApiException.NotFound("Không tìm thấy hội thoại");
    }

    private IQueryable<Conversation> Mine(Guid userId, AppFlavor flavor) => flavor == AppFlavor.Customer
        ? db.Conversations.AsNoTracking().Where(c => c.Customer.UserId == userId)
        : db.Conversations.AsNoTracking().Where(c => c.PartnerProfile.UserId == userId);

    private sealed record ConversationRow(
        Guid Id, byte Status, Guid ServiceRequestId, string RequestCode, string RequestTitle, byte RequestStatus,
        Guid? OrderId, byte? OrderStatus, string? LastMessagePreview, DateTimeOffset? LastMessageAt, Guid? LastMessageSenderId,
        string CustomerName, string? CustomerAvatar, decimal? CustomerRating,
        Guid PartnerProfileId, string PartnerName, string? PartnerAvatar, decimal? PartnerRating,
        int Unread, DateTimeOffset CreatedAt);

    private static IQueryable<ConversationRow> Project(IQueryable<Conversation> query, Guid userId) =>
        query.Select(c => new ConversationRow(
            c.Id, c.Status, c.ServiceRequestId, c.ServiceRequest.Code, c.ServiceRequest.Title, c.ServiceRequest.Status,
            c.OrderId, c.Order == null ? null : c.Order.Status, c.LastMessagePreview, c.LastMessageAt, c.LastMessageSenderId,
            c.Customer.User.FullName, c.Customer.User.AvatarUrl, c.Customer.AverageRating,
            c.PartnerProfileId, c.PartnerProfile.User.FullName, c.PartnerProfile.User.AvatarUrl, c.PartnerProfile.AverageRating,
            c.Messages.Count(m => m.SenderUserId != null && m.SenderUserId != userId && m.ReadAt == null),
            c.CreatedAt));

    private static ConversationDto ToDto(ConversationRow r, Guid userId, AppFlavor flavor) => new(
        r.Id,
        (ConversationStatus)r.Status,
        r.Status == (byte)ConversationStatus.Active,
        r.ServiceRequestId,
        r.RequestCode,
        r.RequestTitle,
        (ServiceRequestStatus)r.RequestStatus,
        r.OrderId,
        (OrderStatus?)r.OrderStatus,
        flavor == AppFlavor.Customer
            ? new CounterpartDto(r.PartnerProfileId, r.PartnerName, r.PartnerAvatar, r.PartnerRating)
            : new CounterpartDto(null, PartnerPublicService.ShortName(r.CustomerName), r.CustomerAvatar, r.CustomerRating),
        r.LastMessageAt is { } at ? new LastMessageDto(r.LastMessagePreview ?? "", at, r.LastMessageSenderId == userId) : null,
        r.Unread,
        r.CreatedAt);

    /// <summary>senderRole comes from the conversation members: null sender = SYSTEM, the partner user = PARTNER, else CUSTOMER.</summary>
    private static MessageDto ToDto(Message m, Guid partnerUserId) => new(
        m.Id, m.ConversationId, m.ClientMessageId,
        m.SenderUserId is null ? ActorType.System : m.SenderUserId == partnerUserId ? ActorType.Partner : ActorType.Customer,
        (MessageType)m.Type, m.Content, m.AttachmentUrl, m.CreatedAt, m.ReadAt);

    private static string Preview(MessageType type, string content) => type switch
    {
        MessageType.Image => string.IsNullOrEmpty(content) ? "[Hình ảnh]" : $"[Hình ảnh] {Truncate(content)}",
        _ => Truncate(content),
    };

    private static string Truncate(string text) => text.Length <= PreviewLength ? text : text[..(PreviewLength - 1)] + "…";

    private static string EncodeCursor(DateTimeOffset at) =>
        Convert.ToBase64String(Encoding.ASCII.GetBytes(at.UtcTicks.ToString(CultureInfo.InvariantCulture)));

    private static DateTimeOffset DecodeCursor(string cursor, string field)
    {
        try
        {
            var ticks = long.Parse(Encoding.ASCII.GetString(Convert.FromBase64String(cursor)), CultureInfo.InvariantCulture);
            return new DateTimeOffset(ticks, TimeSpan.Zero);
        }
        catch (Exception e) when (e is FormatException or OverflowException or ArgumentOutOfRangeException)
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, "Cursor không hợp lệ", field);
        }
    }
}
