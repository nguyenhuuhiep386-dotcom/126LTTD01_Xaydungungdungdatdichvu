using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class UploadedFile
{
    public Guid Id { get; set; }

    public Guid OwnerUserId { get; set; }

    public byte Purpose { get; set; }

    public string ObjectKey { get; set; } = null!;

    public string MimeType { get; set; } = null!;

    public long SizeBytes { get; set; }

    public int? Width { get; set; }

    public int? Height { get; set; }

    public byte Status { get; set; }

    public bool IsPrivate { get; set; }

    public DateTimeOffset CreatedAt { get; set; }

    public virtual User OwnerUser { get; set; } = null!;

    public virtual ICollection<Transaction> Transactions { get; set; } = new List<Transaction>();
}
