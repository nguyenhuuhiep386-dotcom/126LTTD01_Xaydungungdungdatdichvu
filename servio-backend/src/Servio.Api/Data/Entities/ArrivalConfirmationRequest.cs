using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class ArrivalConfirmationRequest
{
    public Guid Id { get; set; }

    public Guid OrderAssignmentId { get; set; }

    public DateTimeOffset RequestedAt { get; set; }

    public DateTimeOffset ExpiresAt { get; set; }

    public byte Status { get; set; }

    public DateTimeOffset? RespondedAt { get; set; }

    public virtual OrderAssignment OrderAssignment { get; set; } = null!;
}
