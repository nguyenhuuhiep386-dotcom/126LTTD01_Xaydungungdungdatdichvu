using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Logging.Abstractions;
using Servio.Api.Common;

namespace Servio.Tests.Common;

public sealed class ApiExceptionHandlerTests
{
    private static DefaultHttpContext Request(bool aborted)
    {
        var context = new DefaultHttpContext();
        context.Request.Path = "/api/v1/partners/me/heartbeat";
        context.Response.Body = new MemoryStream();
        if (aborted)
        {
            var cts = new CancellationTokenSource();
            cts.Cancel();
            context.RequestAborted = cts.Token;
        }
        return context;
    }

    [Fact]
    public async Task ARequestTheAppCancelled_IsNotA500_AndGetsNoBody()
    {
        var context = Request(aborted: true);

        var handled = await new ApiExceptionHandler(NullLogger<ApiExceptionHandler>.Instance)
            .TryHandleAsync(context, new InvalidOperationException("Operation cancelled by user."), default);

        Assert.True(handled);
        Assert.Equal(499, context.Response.StatusCode);
        Assert.Equal(0, context.Response.Body.Length);
    }

    [Fact]
    public async Task AnUnexpectedError_StillReturns500WithTheEnvelope()
    {
        var context = Request(aborted: false);

        await new ApiExceptionHandler(NullLogger<ApiExceptionHandler>.Instance)
            .TryHandleAsync(context, new InvalidOperationException("boom"), default);

        Assert.Equal(StatusCodes.Status500InternalServerError, context.Response.StatusCode);
        Assert.True(context.Response.Body.Length > 0);
    }
}
