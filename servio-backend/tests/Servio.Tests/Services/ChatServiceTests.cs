using Microsoft.AspNetCore.Http;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Chat;
using Servio.Api.Services.Quotes;

namespace Servio.Tests.Services;

/// <summary>BE-3: conversations, messages, read state, READ_ONLY rules.</summary>
public sealed class ChatServiceTests : IAsyncLifetime
{
    private MarketplaceFixture _f = null!;
    private User _partner = null!;
    private Guid _requestId;
    private Guid _conversationId;

    /// <summary>Customer posts, a partner quotes → the conversation exists.</summary>
    public async Task InitializeAsync()
    {
        _f = await MarketplaceFixture.CreateAsync();
        _partner = await _f.AddPartnerAsync();
        var post = await _f.Requests().CreateAsync(_f.Customer.Id, _f.Post(), default);
        _requestId = post.Id;
        var quote = await _f.Quotes().SubmitAsync(_partner.Id, post.Id,
            new SubmitQuoteRequest(300_000, 60, _f.Ctx.Clock.GetUtcNow().AddMinutes(30), null, 1), default);
        _conversationId = quote.ConversationId!.Value;
    }

    public Task DisposeAsync()
    {
        _f.Dispose();
        return Task.CompletedTask;
    }

    private static SendMessageRequest Text(string content, Guid? clientId = null) =>
        new(clientId ?? Guid.NewGuid(), MessageType.Text, content, null);

    [Fact]
    public async Task Send_DeliversToBothSides_WithRoles_UnreadCounts_AndReadReceipts()
    {
        var chat = _f.Chat();

        var fromCustomer = await chat.SendAsync(_f.Customer.Id, AppFlavor.Customer, _conversationId, Text("Anh qua lúc 9h được không?"), default);
        var fromPartner = await chat.SendAsync(_partner.Id, AppFlavor.Partner, _conversationId, Text("Được ạ"), default);

        Assert.Equal(ActorType.Customer, fromCustomer.SenderRole);
        Assert.Equal(ActorType.Partner, fromPartner.SenderRole);
        Assert.Equal(2, _f.Notifier.Messages.Count);
        Assert.Equal(1, (await chat.UnreadCountAsync(_partner.Id, AppFlavor.Partner, default)).Total);
        Assert.Equal(1, (await chat.UnreadCountAsync(_f.Customer.Id, AppFlavor.Customer, default)).Total);

        await chat.MarkReadAsync(_partner.Id, AppFlavor.Partner, _conversationId, fromCustomer.Id, default);

        Assert.Equal(0, (await chat.UnreadCountAsync(_partner.Id, AppFlavor.Partner, default)).Total);
        Assert.Equal(1, (await chat.UnreadCountAsync(_f.Customer.Id, AppFlavor.Customer, default)).Total);
        Assert.Equal(fromCustomer.Id, Assert.Single(_f.Notifier.Reads).LastReadMessageId);
    }

    [Fact]
    public async Task Send_IsIdempotentOnClientMessageId()
    {
        var chat = _f.Chat();
        var clientId = Guid.NewGuid();

        var first = await chat.SendAsync(_f.Customer.Id, AppFlavor.Customer, _conversationId, Text("Xin chào", clientId), default);
        var retry = await chat.SendAsync(_f.Customer.Id, AppFlavor.Customer, _conversationId, Text("Xin chào", clientId), default);
        var reused = await Assert.ThrowsAsync<ApiException>(() =>
            chat.SendAsync(_f.Customer.Id, AppFlavor.Customer, _conversationId, Text("Nội dung khác", clientId), default));

        Assert.Equal(first.Id, retry.Id);
        Assert.Equal(1, await _f.Ctx.Db.Messages.CountAsync());
        Assert.Equal(ErrorCodes.Conflict, reused.Code);
    }

    [Fact]
    public async Task Messages_PageBackwardsWithCursor_AndSyncForwardsWithAfter()
    {
        var chat = _f.Chat();
        for (var i = 1; i <= 5; i++)
        {
            await chat.SendAsync(_f.Customer.Id, AppFlavor.Customer, _conversationId, Text($"Tin {i}"), default);
        }

        var newest = await chat.GetMessagesAsync(_f.Customer.Id, AppFlavor.Customer, _conversationId, null, null, 2, default);
        var older = await chat.GetMessagesAsync(_f.Customer.Id, AppFlavor.Customer, _conversationId, newest.NextCursor, null, 2, default);
        await chat.SendAsync(_partner.Id, AppFlavor.Partner, _conversationId, Text("Tin mới"), default);
        var sync = await chat.GetMessagesAsync(_f.Customer.Id, AppFlavor.Customer, _conversationId, null, newest.SyncCursor, 10, default);

        Assert.Equal(["Tin 5", "Tin 4"], newest.Items.Select(m => m.Content));
        Assert.True(newest.HasMore);
        Assert.Equal(["Tin 3", "Tin 2"], older.Items.Select(m => m.Content));
        Assert.Equal(["Tin mới"], sync.Items.Select(m => m.Content));
        await Assert.ThrowsAsync<ApiException>(() =>
            chat.GetMessagesAsync(_f.Customer.Id, AppFlavor.Customer, _conversationId, newest.NextCursor, newest.SyncCursor, 2, default));
    }

    [Fact]
    public async Task Conversation_IsVisibleOnlyToMembers_OnTheirAppSide()
    {
        var chat = _f.Chat();
        var stranger = await _f.AddPartnerAsync();

        var asStranger = await Assert.ThrowsAsync<ApiException>(() => chat.GetAsync(stranger.Id, AppFlavor.Partner, _conversationId, default));
        var wrongApp = await Assert.ThrowsAsync<ApiException>(() => chat.GetAsync(_partner.Id, AppFlavor.Customer, _conversationId, default));
        var customerView = await chat.GetAsync(_f.Customer.Id, AppFlavor.Customer, _conversationId, default);
        var partnerView = await chat.GetAsync(_partner.Id, AppFlavor.Partner, _conversationId, default);

        Assert.Equal(ErrorCodes.NotFound, asStranger.Code);
        Assert.Equal(ErrorCodes.NotFound, wrongApp.Code);
        Assert.Equal("Trần Thợ", customerView.Counterpart.DisplayName);
        Assert.Equal("Khách N.", partnerView.Counterpart.DisplayName);
        Assert.True(customerView.CanSend);
    }

    [Fact]
    public async Task CancelledPost_MakesConversationReadOnly()
    {
        var chat = _f.Chat();
        await chat.SendAsync(_f.Customer.Id, AppFlavor.Customer, _conversationId, Text("Anh ơi"), default);

        await _f.Requests().CancelAsync(_f.Customer.Id, _requestId, null, default);

        var view = await chat.GetAsync(_partner.Id, AppFlavor.Partner, _conversationId, default);
        var error = await Assert.ThrowsAsync<ApiException>(() =>
            chat.SendAsync(_partner.Id, AppFlavor.Partner, _conversationId, Text("Dạ"), default));
        Assert.Equal(ConversationStatus.ReadOnly, view.Status);
        Assert.False(view.CanSend);
        Assert.Equal(ErrorCodes.ConversationReadOnly, error.Code);
        Assert.Single((await chat.GetMessagesAsync(_partner.Id, AppFlavor.Partner, _conversationId, null, null, null, default)).Items);
    }

    [Fact]
    public async Task Open_CustomerNeedsAQuote_PartnerNeedsEligibility_ExistingIsReturned()
    {
        var chat = _f.Chat();
        var other = await _f.AddPartnerAsync();

        var customerNoQuote = await Assert.ThrowsAsync<ApiException>(() =>
            chat.OpenAsync(_f.Customer.Id, AppFlavor.Customer, new OpenConversationRequest(_requestId, other.PartnerProfile!.Id), default));
        var partnerOpens = await chat.OpenAsync(other.Id, AppFlavor.Partner, new OpenConversationRequest(_requestId, other.PartnerProfile!.Id), default);
        var existing = await chat.OpenAsync(_f.Customer.Id, AppFlavor.Customer, new OpenConversationRequest(_requestId, _partner.PartnerProfile!.Id), default);
        var impersonate = await Assert.ThrowsAsync<ApiException>(() =>
            chat.OpenAsync(other.Id, AppFlavor.Partner, new OpenConversationRequest(_requestId, _partner.PartnerProfile!.Id), default));

        Assert.Equal(ErrorCodes.Forbidden, customerNoQuote.Code);
        Assert.Equal(ConversationStatus.Active, partnerOpens.Status);
        Assert.Equal(_conversationId, existing.Id);
        Assert.Equal(ErrorCodes.NotFound, impersonate.Code);
    }

    [Fact]
    public async Task ImageMessage_NeedsOwnChatImage()
    {
        var chat = _f.Chat();
        byte[] png = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D];
        var upload = await _f.Ctx.Files().UploadAsync(_f.Customer.Id,
            new FormFile(new MemoryStream(png), 0, png.Length, "file", "a.png"), FilePurpose.ChatImage, default);

        var image = await chat.SendAsync(_f.Customer.Id, AppFlavor.Customer, _conversationId,
            new SendMessageRequest(Guid.NewGuid(), MessageType.Image, null, upload.Url), default);
        var foreign = await Assert.ThrowsAsync<ApiException>(() => chat.SendAsync(_partner.Id, AppFlavor.Partner, _conversationId,
            new SendMessageRequest(Guid.NewGuid(), MessageType.Image, null, upload.Url), default));

        Assert.Equal(upload.Url, image.AttachmentUrl);
        Assert.Equal("[Hình ảnh]", (await chat.GetAsync(_partner.Id, AppFlavor.Partner, _conversationId, default)).LastMessage?.Preview);
        Assert.Equal(ErrorCodes.FileNotReady, foreign.Code);
    }

    [Fact]
    public async Task SystemMessage_IsVisibleToBoth_AndNotCountedAsUnread()
    {
        var chat = _f.Chat();

        var system = await chat.AddSystemMessageAsync(_requestId, _partner.PartnerProfile!.Id, "Đối tác đã xác nhận đơn", default);

        Assert.Equal(ActorType.System, system!.SenderRole);
        Assert.Equal(0, (await chat.UnreadCountAsync(_f.Customer.Id, AppFlavor.Customer, default)).Total);
        Assert.Equal("Đối tác đã xác nhận đơn", (await chat.ListAsync(_f.Customer.Id, AppFlavor.Customer, null, 1, 20, default)).Items.Single().LastMessage?.Preview);
    }
}
