using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class PartnerSkill
{
    public Guid Id { get; set; }

    public Guid PartnerProfileId { get; set; }

    public Guid ServiceCategoryId { get; set; }

    public int YearsOfExperience { get; set; }

    public byte Status { get; set; }

    public string? CertificateUrl { get; set; }

    public DateTimeOffset CreatedAt { get; set; }

    public virtual PartnerProfile PartnerProfile { get; set; } = null!;

    public virtual ServiceCategory ServiceCategory { get; set; } = null!;
}
