using Servio.Api.Services.Orders;
using Servio.Api.Services.Requests;

namespace Servio.Api.Jobs;

/// <summary>
/// Background job of the course scope (spec 0.2.2 row 6): every 30 seconds, expire OPEN posts past ExpiresAt and cancel
/// assignments the partner did not accept within the deadline (0.2.7 ý 4). Auto-confirm (BE-6) will be added here.
/// Each step runs in its own scope so one failing step does not stop the others.
/// </summary>
public sealed class ExpiryJob(IServiceScopeFactory scopes, ILogger<ExpiryJob> logger) : BackgroundService
{
    private static readonly TimeSpan Interval = TimeSpan.FromSeconds(30);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(Interval);
        do
        {
            await RunAsync("service requests", sp => sp.GetRequiredService<ServiceRequestService>().ExpireDueAsync(stoppingToken));
            await RunAsync("unaccepted assignments", sp => sp.GetRequiredService<AssignmentService>().ExpireUnacceptedAsync(stoppingToken));
        }
        while (await timer.WaitForNextTickAsync(stoppingToken));
    }

    private async Task RunAsync(string what, Func<IServiceProvider, Task<int>> step)
    {
        try
        {
            using var scope = scopes.CreateScope();
            var count = await step(scope.ServiceProvider);
            if (count > 0)
            {
                logger.LogInformation("Expired {Count} {What}", count, what);
            }
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            // Keep the job alive; the next tick retries.
            logger.LogError(e, "Expiry of {What} failed", what);
        }
    }
}
