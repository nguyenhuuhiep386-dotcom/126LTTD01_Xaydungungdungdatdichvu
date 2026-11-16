using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;

namespace Servio.Api.Data;

public static class DbErrors
{
    /// <summary>
    /// SQL Server 2601/2627: a UNIQUE index stopped a duplicate (double tap, race). Other failures such as deadlocks
    /// or truncation must not be mistaken for it, so they propagate and are logged as 500.
    /// </summary>
    public static bool IsUniqueViolation(this DbUpdateException e) => e.InnerException is SqlException { Number: 2601 or 2627 };
}
