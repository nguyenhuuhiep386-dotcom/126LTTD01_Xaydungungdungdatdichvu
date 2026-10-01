using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class Message
{
    public Guid Id { get; set; }

    public Guid ConversationId { get; set; }

    public Guid? SenderUserId { get; set; }

    public Guid ClientMessageId { get; set; }

    public byte Type { get; set; }

    public string Content { get; set; } = null!;

    public string? AttachmentUrl { get; set; }

    public DateTimeOffset CreatedAt { get; set; }

    public DateTimeOffset? ReadAt { get; set; }

    public virtual Conversation Conversation { get; set; } = null!;

    public virtual User? SenderUser { get; set; }
}
