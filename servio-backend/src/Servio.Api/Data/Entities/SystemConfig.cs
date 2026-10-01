using System;
using System.Collections.Generic;

namespace Servio.Api.Data.Entities;

public partial class SystemConfig
{
    public string Key { get; set; } = null!;

    public string Value { get; set; } = null!;

    public string DataType { get; set; } = null!;

    public string Group { get; set; } = null!;

    public string? Description { get; set; }

    public Guid? UpdatedByAdminId { get; set; }

    public DateTimeOffset UpdatedAt { get; set; }

    public virtual AdminUser? UpdatedByAdmin { get; set; }
}
