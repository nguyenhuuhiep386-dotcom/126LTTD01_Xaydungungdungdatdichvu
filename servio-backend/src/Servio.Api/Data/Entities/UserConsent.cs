namespace Servio.Api.Data.Entities;

/// <summary>
/// One consent decision (Điều 9 Luật 91/2025, Điều 6 NĐ 356/2025): who, which purpose, granted or withdrawn, which policy
/// version, when and from where. Append-only: the newest row per (UserId, Purpose) is the current choice, older rows are
/// the proof. Hand-written (not scaffolded), configured in ServioDbContext.Privacy.cs.
/// </summary>
public partial class UserConsent
{
    public Guid Id { get; set; }

    public Guid UserId { get; set; }

    public byte Purpose { get; set; }

    public bool Granted { get; set; }

    public string PolicyVersion { get; set; } = null!;

    public byte AppFlavor { get; set; }

    public string? IpAddress { get; set; }

    public string? UserAgent { get; set; }

    public DateTimeOffset CreatedAt { get; set; }

    public virtual User User { get; set; } = null!;
}
