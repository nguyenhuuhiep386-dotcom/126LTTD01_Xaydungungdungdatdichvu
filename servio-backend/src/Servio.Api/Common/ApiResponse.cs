namespace Servio.Api.Common;

/// <summary>Uniform response envelope (spec 6.1). Every JSON endpoint returns this shape.</summary>
public sealed record ApiResponse<T>(
    bool Success,
    T? Data,
    string? Message,
    IReadOnlyList<ApiError>? Errors,
    ApiMeta Meta);

public sealed record ApiError(string Code, string? Field, string Message);

public sealed record ApiMeta(string RequestId, DateTimeOffset Timestamp);

public sealed record PagedResult<T>(IReadOnlyList<T> Items, int TotalCount, int Page, int PageSize)
{
    public int TotalPages => PageSize == 0 ? 0 : (int)Math.Ceiling(TotalCount / (double)PageSize);
}

public static class ApiResponse
{
    public static ApiResponse<T> Ok<T>(HttpContext context, T data, string? message = null) =>
        new(true, data, message, null, Meta(context));

    public static ApiResponse<object> Fail(HttpContext context, string message, IReadOnlyList<ApiError> errors) =>
        new(false, null, message, errors, Meta(context));

    private static ApiMeta Meta(HttpContext context)
    {
        var requestId = context.Request.Headers["X-Request-Id"].FirstOrDefault() ?? context.TraceIdentifier;
        return new ApiMeta(requestId, DateTimeOffset.UtcNow);
    }
}
