using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class Conversation
{
    public Guid Id { get; set; }

    public Guid ServiceRequestId { get; set; }

    public Guid? OrderId { get; set; }

    public Guid CustomerId { get; set; }

    public Guid PartnerProfileId { get; set; }

    public string? LastMessagePreview { get; set; }

    public DateTimeOffset? LastMessageAt { get; set; }

    public Guid? LastMessageSenderId { get; set; }

    public int CustomerUnreadCount { get; set; }

    public int PartnerUnreadCount { get; set; }

    public byte Status { get; set; }

    public DateTimeOffset CreatedAt { get; set; }

    public DateTimeOffset UpdatedAt { get; set; }

    public virtual CustomerProfile Customer { get; set; } = null!;

    public virtual ICollection<Message> Messages { get; set; } = new List<Message>();

    public virtual Order? Order { get; set; }

    public virtual PartnerProfile PartnerProfile { get; set; } = null!;

    public virtual ServiceRequest ServiceRequest { get; set; } = null!;
}
