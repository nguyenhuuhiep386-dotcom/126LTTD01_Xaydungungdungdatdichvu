using Microsoft.AspNetCore.Http;
using Servio.Api.Common;
using Servio.Api.Services.Files;

namespace Servio.Tests.Services;

public sealed class FileServiceTests : IDisposable
{
    private static readonly byte[] Jpeg = [0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10, 0x4A, 0x46, 0x49, 0x46, 0, 1, 2, 3];
    private static readonly byte[] Png = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D];
    private static readonly byte[] Webp = [0x52, 0x49, 0x46, 0x46, 1, 2, 3, 4, 0x57, 0x45, 0x42, 0x50, 9];
    private readonly TestContext _ctx = new();

    public void Dispose() => _ctx.Dispose();

    private static IFormFile Form(byte[] content, string name = "photo.jpg") =>
        new FormFile(new MemoryStream(content), 0, content.Length, "file", name);

    [Fact]
    public void DetectImage_RecognizesJpegPngWebp_AndRejectsOthers()
    {
        Assert.Equal("image/jpeg", FileService.DetectImage(Jpeg)?.MimeType);
        Assert.Equal("image/png", FileService.DetectImage(Png)?.MimeType);
        Assert.Equal("image/webp", FileService.DetectImage(Webp)?.MimeType);
        Assert.Null(FileService.DetectImage("%PDF-1.7"u8));
        Assert.Null(FileService.DetectImage("GIF89a......"u8));
        Assert.Null(FileService.DetectImage([0xFF, 0xD8]));
    }

    [Fact]
    public async Task Upload_StoresAvatarPublicly_AndKycPrivately()
    {
        var user = await _ctx.AddUserAsync(UserRoleType.Partner);
        var files = _ctx.Files();

        var avatar = await files.UploadAsync(user.Id, Form(Jpeg), FilePurpose.Avatar, default);
        var kyc = await files.UploadAsync(user.Id, Form(Png), FilePurpose.Kyc, default);

        Assert.StartsWith("/uploads/2026/10/", avatar.Url);
        Assert.EndsWith(".jpg", avatar.Url);
        Assert.Equal($"/api/v1/files/{kyc.FileId}", kyc.Url);
        var (path, mime) = await files.OpenForOwnerAsync(user.Id, kyc.FileId, default);
        Assert.True(File.Exists(path));
        Assert.DoesNotContain("wwwroot", path);
        Assert.Equal("image/png", mime);
    }

    [Fact]
    public async Task Upload_RejectsNonImageContent_EvenWithImageFileName()
    {
        var user = await _ctx.AddUserAsync(UserRoleType.Customer);

        var error = await Assert.ThrowsAsync<ApiException>(() =>
            _ctx.Files().UploadAsync(user.Id, Form("<script>alert(1)</script>"u8.ToArray(), "x.jpg"), FilePurpose.PostImage, default));

        Assert.Equal(ErrorCodes.UnsupportedFileType, error.Code);
    }

    [Fact]
    public async Task ResolveOwned_RejectsFilesOfAnotherUserOrPurpose()
    {
        var owner = await _ctx.AddUserAsync(UserRoleType.Partner);
        var other = await _ctx.AddUserAsync(UserRoleType.Partner, phone: "+84909999999");
        var files = _ctx.Files();
        var kyc = await files.UploadAsync(owner.Id, Form(Jpeg), FilePurpose.Kyc, default);

        var wrongUser = await Assert.ThrowsAsync<ApiException>(() => files.ResolveOwnedAsync(other.Id, kyc.Url, "fileUrl", default, FilePurpose.Kyc));
        var wrongPurpose = await Assert.ThrowsAsync<ApiException>(() => files.ResolveOwnedAsync(owner.Id, kyc.Url, "avatarUrl", default, FilePurpose.Avatar));
        var external = await Assert.ThrowsAsync<ApiException>(() => files.ResolveOwnedAsync(owner.Id, "https://evil.example/x.jpg", "avatarUrl", default, FilePurpose.Avatar));

        Assert.All([wrongUser, wrongPurpose, external], e => Assert.Equal(ErrorCodes.FileNotReady, e.Code));
        Assert.Equal(kyc.FileId, (await files.ResolveOwnedAsync(owner.Id, kyc.Url, "fileUrl", default, FilePurpose.Kyc)).Id);
    }
}
