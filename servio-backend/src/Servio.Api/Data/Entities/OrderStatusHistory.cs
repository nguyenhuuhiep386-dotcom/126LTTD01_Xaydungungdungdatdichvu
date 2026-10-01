using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class OrderStatusHistory
{
    public Guid Id { get; set; }

    public Guid OrderId { get; set; }

    public Guid? OrderAssignmentId { get; set; }

    public byte? FromStatus { get; set; }

    public byte ToStatus { get; set; }

    public byte ChangedByType { get; set; }

    public Guid? ChangedByUserId { get; set; }

    public Guid? ChangedByAdminId { get; set; }

    public string? Note { get; set; }

    public decimal? Latitude { get; set; }

    public decimal? Longitude { get; set; }

    public DateTimeOffset CreatedAt { get; set; }

    public virtual AdminUser? ChangedByAdmin { get; set; }

    public virtual User? ChangedByUser { get; set; }

    public virtual Order Order { get; set; } = null!;

    public virtual OrderAssignment? OrderAssignment { get; set; }
}
