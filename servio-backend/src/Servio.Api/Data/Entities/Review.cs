using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class Review
{
    public Guid Id { get; set; }

    public Guid OrderId { get; set; }

    public Guid OrderAssignmentId { get; set; }

    public Guid ReviewerId { get; set; }

    public Guid RevieweeId { get; set; }

    public byte ReviewerType { get; set; }

    public byte Rating { get; set; }

    public byte? PunctualityRating { get; set; }

    public byte? QualityRating { get; set; }

    public byte? AttitudeRating { get; set; }

    public byte? PriceRating { get; set; }

    public string? Comment { get; set; }

    public string? Tags { get; set; }

    public bool IsVisible { get; set; }

    public string? HiddenReason { get; set; }

    public DateTimeOffset PublishAt { get; set; }

    public DateTimeOffset CreatedAt { get; set; }

    public virtual Order Order { get; set; } = null!;

    public virtual OrderAssignment OrderAssignment { get; set; } = null!;

    public virtual User Reviewee { get; set; } = null!;

    public virtual User Reviewer { get; set; } = null!;
}
