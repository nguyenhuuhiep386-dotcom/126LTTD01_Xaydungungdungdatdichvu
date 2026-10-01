using Microsoft.EntityFrameworkCore;
using Servio.Api.Data;

namespace Servio.Api.Services;

/// <summary>
/// Business codes like SR2610010001 / OD2610010001: prefix + Vietnam date + number from a DB sequence
/// (01_schema.sql), never MAX+1. The number has 4 digits and grows when it passes 9999.
/// </summary>
public sealed class CodeGenerator(ServioDbContext db, TimeProvider clock)
{
    public const string ServiceRequest = "SR";
    public const string Order = "OD";
    public const string Dispute = "DS";
    public const string Transaction = "TX";

    private static readonly TimeSpan VietnamOffset = TimeSpan.FromHours(7);
    private static int _inMemoryCounter;

    public async Task<string> NextAsync(string prefix, CancellationToken ct)
    {
        var sequence = prefix switch
        {
            ServiceRequest => "ServiceRequestCodeSeq",
            Order => "OrderCodeSeq",
            Dispute => "DisputeCodeSeq",
            Transaction => "TransactionCodeSeq",
            _ => throw new ArgumentOutOfRangeException(nameof(prefix), prefix, "Unknown code prefix"),
        };

        // Unit tests use EF InMemory, which has no sequences.
        var number = db.Database.IsRelational()
            // ToListAsync, not SingleAsync: composing would wrap NEXT VALUE FOR in a sub-query, which SQL Server rejects.
            ? (await db.Database.SqlQueryRaw<int>($"SELECT NEXT VALUE FOR dbo.{sequence} AS [Value]").ToListAsync(ct)).Single()
            : Interlocked.Increment(ref _inMemoryCounter);

        return $"{prefix}{clock.GetUtcNow().ToOffset(VietnamOffset):yyMMdd}{number:D4}";
    }
}
