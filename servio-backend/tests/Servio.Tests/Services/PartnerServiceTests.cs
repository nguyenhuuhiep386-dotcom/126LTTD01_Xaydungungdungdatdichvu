using Microsoft.AspNetCore.Http;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Partners;

namespace Servio.Tests.Services;

public sealed class PartnerServiceTests : IDisposable
{
    private static readonly byte[] Jpeg = [0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10, 0x4A, 0x46, 0x49, 0x46, 0, 1];
    private readonly TestContext _ctx = new();

    public void Dispose() => _ctx.Dispose();

    private PartnerProfileService Profiles() => new(_ctx.Db, _ctx.Files(), _ctx.Configs(), _ctx.Clock);

    private PartnerSkillService Skills() => new(_ctx.Db, _ctx.Files(), _ctx.Clock);

    private async Task<Guid> AddCategoryAsync(Guid? parentId, string slug, bool requiresCertificate = false)
    {
        var category = new ServiceCategory
        {
            Id = Guid.NewGuid(), ParentId = parentId, Name = slug, Slug = slug, IsActive = true,
            RequiresCertificate = requiresCertificate, CreatedAt = _ctx.Clock.GetUtcNow(), UpdatedAt = _ctx.Clock.GetUtcNow(),
        };
        _ctx.Db.ServiceCategories.Add(category);
        await _ctx.Db.SaveChangesAsync();
        return category.Id;
    }

    private async Task<string> UploadKycAsync(Guid userId) =>
        (await _ctx.Files().UploadAsync(userId, new FormFile(new MemoryStream(Jpeg), 0, Jpeg.Length, "file", "a.jpg"), FilePurpose.Kyc, default)).Url;

    [Fact]
    public async Task Submit_ListsMissingItems_UntilProfileIsComplete()
    {
        var user = await _ctx.AddUserAsync(UserRoleType.Partner);
        var profiles = Profiles();

        var incomplete = await Assert.ThrowsAsync<ApiException>(() => profiles.SubmitAsync(user.Id, default));
        Assert.Equal(ErrorCodes.ProfileIncomplete, incomplete.Code);
        Assert.Contains("CCCD mặt trước", incomplete.Message);

        foreach (var type in new[] { DocumentType.IdFront, DocumentType.IdBack, DocumentType.SelfieWithId })
        {
            await profiles.AddDocumentAsync(user.Id, new AddDocumentRequest(type, await UploadKycAsync(user.Id)), default);
        }
        var group = await AddCategoryAsync(null, "dien-lanh");
        await Skills().AddAsync(user.Id, new AddSkillRequest(await AddCategoryAsync(group, "ve-sinh-may-lanh"), 3, null), default);
        await profiles.UpdateAsync(user.Id, new UpdatePartnerRequest(null, 3, 5, 10.85m, 106.77m), default);

        var result = await profiles.SubmitAsync(user.Id, default);

        Assert.Equal(PartnerVerificationStatus.Pending, result.VerificationStatus);
        Assert.Empty((await profiles.GetMeAsync(user.Id, default)).MissingForVerification);
        var locked = await Assert.ThrowsAsync<ApiException>(() =>
            profiles.AddDocumentAsync(user.Id, new AddDocumentRequest(DocumentType.IdFront, "/api/v1/files/x"), default));
        Assert.Equal(ErrorCodes.VerificationLocked, locked.Code);
    }

    [Fact]
    public async Task AddDocument_ReplacesPreviousDocumentOfSameType()
    {
        var user = await _ctx.AddUserAsync(UserRoleType.Partner);
        var profiles = Profiles();

        await profiles.AddDocumentAsync(user.Id, new AddDocumentRequest(DocumentType.IdFront, await UploadKycAsync(user.Id)), default);
        var second = await profiles.AddDocumentAsync(user.Id, new AddDocumentRequest(DocumentType.IdFront, await UploadKycAsync(user.Id)), default);

        Assert.Equal(second.Id, (await _ctx.Db.PartnerDocuments.SingleAsync()).Id);
    }

    [Fact]
    public async Task Online_RequiresApproval_AndExpiresWithoutHeartbeat()
    {
        var user = await _ctx.AddUserAsync(UserRoleType.Partner);
        var profiles = Profiles();

        var notVerified = await Assert.ThrowsAsync<ApiException>(() =>
            profiles.SetOnlineAsync(user.Id, new OnlineStatusRequest(true, 10.85m, 106.77m), default));
        Assert.Equal(ErrorCodes.PartnerNotVerified, notVerified.Code);

        user.PartnerProfile!.VerificationStatus = (byte)PartnerVerificationStatus.Approved;
        await _ctx.Db.SaveChangesAsync();
        Assert.True((await profiles.SetOnlineAsync(user.Id, new OnlineStatusRequest(true, 10.85m, 106.77m), default)).IsOnline);
        await profiles.HeartbeatAsync(user.Id, new HeartbeatRequest(10.86m, 106.78m), default);

        _ctx.Clock.Advance(TimeSpan.FromMinutes(11)); // partner.offline_timeout_minutes defaults to 10
        Assert.False((await profiles.GetMeAsync(user.Id, default)).IsOnline);
        var offline = await Assert.ThrowsAsync<ApiException>(() => profiles.HeartbeatAsync(user.Id, new HeartbeatRequest(10.86m, 106.78m), default));
        Assert.Equal(ErrorCodes.PartnerOffline, offline.Code);
    }

    [Fact]
    public async Task AddSkill_RejectsGroupCategory_Duplicates_AndMoreThanFive()
    {
        var user = await _ctx.AddUserAsync(UserRoleType.Partner);
        var skills = Skills();
        var group = await AddCategoryAsync(null, "dien-nuoc");
        var leaves = new List<Guid>();
        for (var i = 0; i < 6; i++)
        {
            leaves.Add(await AddCategoryAsync(group, $"dich-vu-{i}"));
        }

        var groupError = await Assert.ThrowsAsync<ApiException>(() => skills.AddAsync(user.Id, new AddSkillRequest(group, 1, null), default));
        for (var i = 0; i < PartnerSkillService.MaxSkills; i++)
        {
            await skills.AddAsync(user.Id, new AddSkillRequest(leaves[i], 1, null), default);
        }
        var duplicate = await Assert.ThrowsAsync<ApiException>(() => skills.AddAsync(user.Id, new AddSkillRequest(leaves[0], 1, null), default));
        var limit = await Assert.ThrowsAsync<ApiException>(() => skills.AddAsync(user.Id, new AddSkillRequest(leaves[5], 1, null), default));

        Assert.Equal(ErrorCodes.ValidationError, groupError.Code);
        Assert.Equal(ErrorCodes.SkillAlreadyExists, duplicate.Code);
        Assert.Equal(ErrorCodes.SkillLimitReached, limit.Code);
        Assert.All(await skills.ListAsync(user.Id, default), s => Assert.Equal(ApprovalStatus.Pending, s.Status));
    }

    [Fact]
    public async Task PublicProfile_IsHiddenFromUnrelatedUsers()
    {
        var partner = await _ctx.AddUserAsync(UserRoleType.Partner);
        var stranger = await _ctx.AddUserAsync(UserRoleType.Customer, phone: "+84909999999");
        var service = new PartnerPublicService(_ctx.Db, _ctx.Clock);

        var error = await Assert.ThrowsAsync<ApiException>(() => service.GetAsync(stranger.Id, partner.PartnerProfile!.Id, default));
        var own = await service.GetAsync(partner.Id, partner.PartnerProfile!.Id, default);

        Assert.Equal(ErrorCodes.NotFound, error.Code);
        Assert.Equal("Nguyễn Văn An", own.FullName);
    }

    [Theory]
    [InlineData("Nguyễn Văn An", "An N.")]
    [InlineData("An", "An")]
    [InlineData("", "Khách hàng")]
    public void ShortName_HidesFullName(string fullName, string expected) =>
        Assert.Equal(expected, PartnerPublicService.ShortName(fullName));
}
