using Microsoft.AspNetCore.Diagnostics;
using Microsoft.AspNetCore.Mvc;

namespace Servio.Api.Common;

/// <summary>Turns exceptions thrown by API endpoints into the error envelope. Admin pages keep the default error page.</summary>
public sealed class ApiExceptionHandler(ILogger<ApiExceptionHandler> logger) : IExceptionHandler
{
    public async ValueTask<bool> TryHandleAsync(HttpContext context, Exception exception, CancellationToken cancellationToken)
    {
        if (!context.Request.Path.StartsWithSegments("/api"))
        {
            return false;
        }

        var (status, error) = exception switch
        {
            ApiException api => (api.StatusCode, new ApiError(api.Code, api.Field, api.Message)),
            Microsoft.EntityFrameworkCore.DbUpdateConcurrencyException =>
                (StatusCodes.Status409Conflict, new ApiError(ErrorCodes.Conflict, null, "Dữ liệu đã thay đổi, vui lòng tải lại")),
            _ => (StatusCodes.Status500InternalServerError, new ApiError(ErrorCodes.InternalError, null, "Có lỗi xảy ra, vui lòng thử lại")),
        };

        if (status >= 500)
        {
            logger.LogError(exception, "Unhandled error on {Method} {Path}", context.Request.Method, context.Request.Path);
        }

        context.Response.StatusCode = status;
        await context.Response.WriteAsJsonAsync(ApiResponse.Fail(context, error.Message, [error]), cancellationToken);
        return true;
    }

    /// <summary>Model validation errors ([Required], [StringLength]...) in the envelope with errors[].field.</summary>
    public static IActionResult InvalidModelState(ActionContext context)
    {
        var errors = context.ModelState
            .Where(entry => entry.Value is { Errors.Count: > 0 })
            .SelectMany(entry => entry.Value!.Errors.Select(e => new ApiError(
                ErrorCodes.ValidationError,
                ToCamelCase(entry.Key),
                SafeMessage(e.ErrorMessage))))
            .ToList();

        return new BadRequestObjectResult(ApiResponse.Fail(context.HttpContext, "Dữ liệu không hợp lệ", errors));
    }

    /// <summary>JSON parser messages contain internal type names; replace them with a neutral text.</summary>
    private static string SafeMessage(string message) => message switch
    {
        _ when string.IsNullOrWhiteSpace(message) => "Giá trị không hợp lệ",
        _ when message.Contains("JSON", StringComparison.Ordinal) => "Giá trị không hợp lệ",
        _ when message.Contains("request body is required", StringComparison.Ordinal) => "Thiếu dữ liệu gửi lên",
        _ => message,
    };

    private static string ToCamelCase(string key)
    {
        var name = key.StartsWith("$.") ? key[2..] : key;
        return string.IsNullOrEmpty(name) ? name : char.ToLowerInvariant(name[0]) + name[1..];
    }
}
