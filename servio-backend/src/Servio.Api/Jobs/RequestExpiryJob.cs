using Servio.Api.Services.Requests;

namespace Servio.Api.Jobs;

/// <summary>
/// Background job of the course scope (spec 0.2.2 row 6): every 30 seconds, expire OPEN posts past ExpiresAt.
/// The order jobs of BE-4 (10-minute acceptance timeout, auto-confirm) will be added next to this one.
/// </summary>
public sealed class RequestExpiryJob(IServiceScopeFactory scopes, ILogger<RequestExpiryJob> logger) : BackgroundService
{
    private static readonly TimeSpan Interval = TimeSpan.FromSeconds(30);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(Interval);
        do
        {
            try
            {
                using var scope = scopes.CreateScope();
                var expired = await scope.ServiceProvider.GetRequiredService<ServiceRequestService>().ExpireDueAsync(stoppingToken);
                if (expired > 0)
                {
                    logger.LogInformation("Expired {Count} service requests", expired);
                }
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                // Keep the job alive; the next tick retries.
                logger.LogError(e, "Service request expiry failed");
            }
        }
        while (await timer.WaitForNextTickAsync(stoppingToken));
    }
}
