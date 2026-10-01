using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;

namespace Servio.Tests;

/// <summary>EF InMemory does not generate SQL Server rowversion values; fill them so inserts succeed in tests.</summary>
public sealed class InMemoryRowVersionInterceptor : SaveChangesInterceptor
{
    public override ValueTask<InterceptionResult<int>> SavingChangesAsync(
        DbContextEventData eventData, InterceptionResult<int> result, CancellationToken cancellationToken = default)
    {
        foreach (var entry in eventData.Context!.ChangeTracker.Entries().Where(e => e.State is EntityState.Added or EntityState.Modified))
        {
            var rowVersion = entry.Metadata.FindProperty("RowVersion");
            if (rowVersion is not null)
            {
                entry.Property(rowVersion.Name).CurrentValue = Guid.NewGuid().ToByteArray()[..8];
            }
        }
        return base.SavingChangesAsync(eventData, result, cancellationToken);
    }
}
