namespace Servio.Api.Common;

/// <summary>
/// Throw from services to return a business error in the envelope.
/// Example: throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.OtpInvalid, "Mã OTP không đúng");
/// </summary>
public sealed class ApiException(int statusCode, string code, string message, string? field = null)
    : Exception(message)
{
    public int StatusCode { get; } = statusCode;
    public string Code { get; } = code;
    public string? Field { get; } = field;

    public static ApiException NotFound(string message) =>
        new(StatusCodes.Status404NotFound, ErrorCodes.NotFound, message);

    public static ApiException Forbidden(string message) =>
        new(StatusCodes.Status403Forbidden, ErrorCodes.Forbidden, message);
}
