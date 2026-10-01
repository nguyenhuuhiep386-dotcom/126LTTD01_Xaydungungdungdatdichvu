using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;

namespace Servio.Api.Services.Files;

public sealed class FileStorageOptions
{
    public const string Section = "Files";

    /// <summary>Max upload size in bytes (default 5 MB).</summary>
    public long MaxBytes { get; set; } = 5 * 1024 * 1024;

    /// <summary>Folder for KYC/certificate/evidence files. Relative paths are resolved from the content root. Never under wwwroot.</summary>
    public string PrivateRoot { get; set; } = "App_Data/private-uploads";
}

/// <summary>Response of POST /files. Send <see cref="Url"/> back in other requests (avatarUrl, fileUrl, ...).</summary>
public sealed record UploadedFileDto(Guid FileId, string Url, string MimeType, long SizeBytes);

/// <summary>
/// POST /files (replaces #35 in the course scope): image upload with magic-byte check.
/// Public files are served from /uploads; private files (KYC, certificates, dispute evidence) only through
/// GET /api/v1/files/{id} for the owner, and through the admin pages.
/// </summary>
public sealed class FileService(
    ServioDbContext db,
    IWebHostEnvironment env,
    IOptions<FileStorageOptions> options,
    TimeProvider clock)
{
    public const string PublicUrlPrefix = "/uploads/";
    public const string PrivateUrlPrefix = "/api/v1/files/";

    private static readonly HashSet<FilePurpose> PrivatePurposes = [FilePurpose.Kyc, FilePurpose.Certificate, FilePurpose.DisputeEvidence];
    private readonly FileStorageOptions _options = options.Value;

    public async Task<UploadedFileDto> UploadAsync(Guid userId, IFormFile? file, FilePurpose purpose, CancellationToken ct)
    {
        if (!Enum.IsDefined(purpose))
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, "Mục đích tải lên không hợp lệ", "purpose");
        }
        if (file is null || file.Length == 0)
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.ValidationError, "Chưa chọn file", "file");
        }
        if (file.Length > _options.MaxBytes)
        {
            throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.FileTooLarge,
                $"Ảnh tối đa {_options.MaxBytes / 1024 / 1024} MB", "file");
        }

        await using var input = file.OpenReadStream();
        var header = new byte[12];
        var read = await input.ReadAtLeastAsync(header, header.Length, throwOnEndOfStream: false, ct);
        var detected = DetectImage(header.AsSpan(0, read))
                       ?? throw new ApiException(StatusCodes.Status400BadRequest, ErrorCodes.UnsupportedFileType,
                           "Chỉ nhận ảnh JPEG, PNG hoặc WebP", "file");
        input.Position = 0;

        var now = clock.GetUtcNow();
        var id = Guid.CreateVersion7();
        var isPrivate = PrivatePurposes.Contains(purpose);
        var objectKey = $"{now:yyyy}/{now:MM}/{id:N}{detected.Extension}";
        var path = Path.Combine(RootFor(isPrivate), objectKey.Replace('/', Path.DirectorySeparatorChar));
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        await using (var output = new FileStream(path, FileMode.CreateNew, FileAccess.Write))
        {
            await input.CopyToAsync(output, ct);
        }

        var entity = new UploadedFile
        {
            Id = id,
            OwnerUserId = userId,
            Purpose = (byte)purpose,
            ObjectKey = objectKey,
            MimeType = detected.MimeType,
            SizeBytes = file.Length,
            Status = 3, // READY
            IsPrivate = isPrivate,
            CreatedAt = now,
        };
        db.UploadedFiles.Add(entity);
        await db.SaveChangesAsync(ct);

        return new UploadedFileDto(id, UrlOf(entity), entity.MimeType, entity.SizeBytes);
    }

    /// <summary>
    /// Validates a file URL sent by a client (avatarUrl, fileUrl, certificateUrl...): it must be a READY file
    /// uploaded by <paramref name="userId"/> for one of <paramref name="purposes"/>. Throws FILE_NOT_READY otherwise.
    /// </summary>
    public async Task<UploadedFile> ResolveOwnedAsync(Guid userId, string url, string field, CancellationToken ct, params FilePurpose[] purposes)
    {
        var file = await FindByUrlAsync(url, ct);
        var allowed = purposes.Select(p => (byte)p).ToHashSet();
        if (file is null || file.OwnerUserId != userId || file.Status != 3 || !allowed.Contains(file.Purpose))
        {
            throw new ApiException(StatusCodes.Status422UnprocessableEntity, ErrorCodes.FileNotReady,
                "File không hợp lệ, vui lòng tải lên lại", field);
        }
        return file;
    }

    /// <summary>Opens a file for GET /files/{id}. Only the owner may read it through the app API.</summary>
    public async Task<(string Path, string MimeType)> OpenForOwnerAsync(Guid userId, Guid fileId, CancellationToken ct)
    {
        var file = await db.UploadedFiles.AsNoTracking().FirstOrDefaultAsync(f => f.Id == fileId && f.OwnerUserId == userId, ct)
                   ?? throw ApiException.NotFound("Không tìm thấy file");
        return (PhysicalPath(file), file.MimeType);
    }

    public string PhysicalPath(UploadedFile file) =>
        Path.Combine(RootFor(file.IsPrivate), file.ObjectKey.Replace('/', Path.DirectorySeparatorChar));

    public static string UrlOf(UploadedFile file) =>
        file.IsPrivate ? PrivateUrlPrefix + file.Id : PublicUrlPrefix + file.ObjectKey;

    private Task<UploadedFile?> FindByUrlAsync(string url, CancellationToken ct)
    {
        if (url.StartsWith(PublicUrlPrefix, StringComparison.Ordinal))
        {
            var key = url[PublicUrlPrefix.Length..];
            return db.UploadedFiles.FirstOrDefaultAsync(f => f.ObjectKey == key && !f.IsPrivate, ct);
        }
        if (url.StartsWith(PrivateUrlPrefix, StringComparison.Ordinal) && Guid.TryParse(url[PrivateUrlPrefix.Length..], out var id))
        {
            return db.UploadedFiles.FirstOrDefaultAsync(f => f.Id == id && f.IsPrivate, ct);
        }
        return Task.FromResult<UploadedFile?>(null);
    }

    private string RootFor(bool isPrivate) => isPrivate
        ? Path.GetFullPath(_options.PrivateRoot, env.ContentRootPath)
        : Path.Combine(env.WebRootPath ?? Path.Combine(env.ContentRootPath, "wwwroot"), "uploads");

    /// <summary>Detects JPEG, PNG and WebP from the first bytes; the client Content-Type is never trusted.</summary>
    public static (string MimeType, string Extension)? DetectImage(ReadOnlySpan<byte> header) => header switch
    {
        [0xFF, 0xD8, 0xFF, ..] => ("image/jpeg", ".jpg"),
        [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, ..] => ("image/png", ".png"),
        [0x52, 0x49, 0x46, 0x46, _, _, _, _, 0x57, 0x45, 0x42, 0x50, ..] => ("image/webp", ".webp"),
        _ => null,
    };
}
