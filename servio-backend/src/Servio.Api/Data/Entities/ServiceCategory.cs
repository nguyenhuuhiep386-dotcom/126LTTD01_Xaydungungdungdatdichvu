using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class ServiceCategory
{
    public Guid Id { get; set; }

    public Guid? ParentId { get; set; }

    public string Name { get; set; } = null!;

    public string Slug { get; set; } = null!;

    public string? Description { get; set; }

    public string? IconUrl { get; set; }

    public decimal? ReferencePriceMin { get; set; }

    public decimal? ReferencePriceMax { get; set; }

    public decimal? CommissionRate { get; set; }

    public bool RequiresCertificate { get; set; }

    public int DisplayOrder { get; set; }

    public bool IsActive { get; set; }

    public DateTimeOffset CreatedAt { get; set; }

    public DateTimeOffset UpdatedAt { get; set; }

    public virtual ICollection<ServiceCategory> InverseParent { get; set; } = new List<ServiceCategory>();

    public virtual ICollection<Order> Orders { get; set; } = new List<Order>();

    public virtual ServiceCategory? Parent { get; set; }

    public virtual ICollection<PartnerSkill> PartnerSkills { get; set; } = new List<PartnerSkill>();

    public virtual ICollection<ServiceRequest> ServiceRequests { get; set; } = new List<ServiceRequest>();
}
