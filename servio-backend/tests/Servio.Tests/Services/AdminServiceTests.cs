using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Admin;

namespace Servio.Tests.Services;

public sealed class AdminServiceTests : IDisposable
{
    private readonly TestContext _ctx = new();
    private readonly AdminActor _admin = new(Guid.NewGuid(), "127.0.0.1", "test");

    public void Dispose() => _ctx.Dispose();

    private PartnerVerificationService Verifications() => new(_ctx.Db, _ctx.Clock);

    private AdminCategoryService Categories() => new(_ctx.Db, _ctx.Clock);

    /// <summary>A partner that submitted 3 KYC documents and one skill.</summary>
    private async Task<PartnerProfile> PendingPartnerAsync()
    {
        var user = await _ctx.AddUserAsync(UserRoleType.Partner);
        var partner = user.PartnerProfile!;
        partner.VerificationStatus = (byte)PartnerVerificationStatus.Pending;
        foreach (var type in new[] { DocumentType.IdFront, DocumentType.IdBack, DocumentType.SelfieWithId })
        {
            partner.PartnerDocuments.Add(new PartnerDocument
            {
                Id = Guid.NewGuid(), DocumentType = (byte)type, FileUrl = $"/api/v1/files/{Guid.NewGuid()}",
                Status = (byte)ApprovalStatus.Pending, CreatedAt = _ctx.Clock.GetUtcNow(),
            });
        }
        var group = new ServiceCategory { Id = Guid.NewGuid(), Name = "Điện lạnh", Slug = "dien-lanh", IsActive = true };
        var service = new ServiceCategory { Id = Guid.NewGuid(), Parent = group, Name = "Vệ sinh máy lạnh", Slug = "ve-sinh-may-lanh", IsActive = true };
        _ctx.Db.ServiceCategories.AddRange(group, service);
        partner.PartnerSkills.Add(new PartnerSkill
        {
            Id = Guid.NewGuid(), ServiceCategory = service, Status = (byte)ApprovalStatus.Pending, CreatedAt = _ctx.Clock.GetUtcNow(),
        });
        await _ctx.Db.SaveChangesAsync();
        return partner;
    }

    [Fact]
    public async Task Approve_ApprovesProfileDocumentsAndSkills_WithAuditAndNotification()
    {
        var partner = await PendingPartnerAsync();

        await Verifications().ApproveAsync(_admin, partner.Id, approvePendingSkills: true, default);

        var saved = await _ctx.Db.PartnerProfiles.Include(p => p.PartnerDocuments).Include(p => p.PartnerSkills).SingleAsync();
        Assert.Equal((byte)PartnerVerificationStatus.Approved, saved.VerificationStatus);
        Assert.Equal(_admin.AdminId, saved.VerifiedByAdminId);
        Assert.All(saved.PartnerDocuments, d => Assert.Equal((byte)ApprovalStatus.Approved, d.Status));
        Assert.All(saved.PartnerSkills, s => Assert.Equal((byte)ApprovalStatus.Approved, s.Status));
        var audit = await _ctx.Db.AuditLogs.SingleAsync();
        Assert.Equal("PARTNER_KYC_APPROVED", audit.Action);
        Assert.Equal(_admin.AdminId, audit.ActorId);
        var notification = await _ctx.Db.Notifications.SingleAsync();
        Assert.Equal(saved.UserId, notification.UserId);
        Assert.Equal((byte)AppFlavor.Partner, notification.AppFlavor);
    }

    [Fact]
    public async Task Reject_RequiresReason_AndRejectsOnlySelectedDocuments()
    {
        var partner = await PendingPartnerAsync();
        var back = partner.PartnerDocuments.Single(d => d.DocumentType == (byte)DocumentType.IdBack);
        var service = Verifications();

        var noReason = await Assert.ThrowsAsync<ApiException>(() => service.RejectAsync(_admin, partner.Id, "  ", [back.Id], default));
        await service.RejectAsync(_admin, partner.Id, "Ảnh CCCD mặt sau bị mờ", [back.Id], default);

        Assert.Equal(ErrorCodes.ValidationError, noReason.Code);
        var docs = await _ctx.Db.PartnerDocuments.ToListAsync();
        Assert.Equal((byte)ApprovalStatus.Rejected, docs.Single(d => d.Id == back.Id).Status);
        Assert.All(docs.Where(d => d.Id != back.Id), d => Assert.Equal((byte)ApprovalStatus.Pending, d.Status));
        Assert.Equal("Ảnh CCCD mặt sau bị mờ", (await _ctx.Db.PartnerProfiles.SingleAsync()).VerificationNote);
    }

    [Fact]
    public async Task Decisions_FailWhenProfileIsNotPending()
    {
        var partner = await PendingPartnerAsync();
        var service = Verifications();
        await service.ApproveAsync(_admin, partner.Id, approvePendingSkills: false, default);

        var again = await Assert.ThrowsAsync<ApiException>(() => service.ApproveAsync(_admin, partner.Id, false, default));
        await service.DecideSkillAsync(_admin, partner.Id, partner.PartnerSkills.Single().Id, approve: false, default);
        var skillAgain = await Assert.ThrowsAsync<ApiException>(() =>
            service.DecideSkillAsync(_admin, partner.Id, partner.PartnerSkills.Single().Id, approve: true, default));

        Assert.Equal(ErrorCodes.InvalidStatusTransition, again.Code);
        Assert.Equal(ErrorCodes.InvalidStatusTransition, skillAgain.Code);
        Assert.Equal((byte)ApprovalStatus.Rejected, (await _ctx.Db.PartnerSkills.SingleAsync()).Status);
    }

    [Fact]
    public async Task SaveCategory_GeneratesSlug_AndKeepsTwoLevels()
    {
        var categories = Categories();
        var groupId = await categories.SaveAsync(_admin, null, new CategoryForm { Name = "Điện – Nước", DisplayOrder = 1 }, default);
        var serviceId = await categories.SaveAsync(_admin, null,
            new CategoryForm { Name = "Sửa ống nước", ParentId = groupId, ReferencePriceMin = 150_000, ReferencePriceMax = 600_000 }, default);

        var nested = await Assert.ThrowsAsync<ApiException>(() =>
            categories.SaveAsync(_admin, null, new CategoryForm { Name = "Cấp 3", ParentId = serviceId }, default));
        var groupToChild = await Assert.ThrowsAsync<ApiException>(() =>
            categories.SaveAsync(_admin, groupId, new CategoryForm { Name = "Điện – Nước", ParentId = serviceId }, default));
        var duplicateSlug = await Assert.ThrowsAsync<ApiException>(() =>
            categories.SaveAsync(_admin, null, new CategoryForm { Name = "Sửa ống nước" }, default));
        var badPrice = await Assert.ThrowsAsync<ApiException>(() =>
            categories.SaveAsync(_admin, null, new CategoryForm { Name = "Khác", ReferencePriceMin = 500, ReferencePriceMax = 100 }, default));

        Assert.Equal("dien-nuoc", (await _ctx.Db.ServiceCategories.SingleAsync(c => c.Id == groupId)).Slug);
        Assert.Equal("sua-ong-nuoc", (await _ctx.Db.ServiceCategories.SingleAsync(c => c.Id == serviceId)).Slug);
        Assert.Equal(nameof(CategoryForm.ParentId), nested.Field);
        Assert.Equal(nameof(CategoryForm.ParentId), groupToChild.Field);
        Assert.Equal(nameof(CategoryForm.Slug), duplicateSlug.Field);
        Assert.Equal(nameof(CategoryForm.ReferencePriceMin), badPrice.Field);
        Assert.Equal(2, await _ctx.Db.AuditLogs.CountAsync(a => a.Action == "CATEGORY_CREATED"));
    }

    [Theory]
    [InlineData("Vệ sinh máy lạnh", "ve-sinh-may-lanh")]
    [InlineData("Điện – Nước", "dien-nuoc")]
    [InlineData("  Sửa laptop, PC  ", "sua-laptop-pc")]
    public void Slug_RemovesVietnameseMarks(string name, string expected)
    {
        Assert.Equal(expected, Slug.From(name));
        Assert.True(Slug.IsValid(expected));
    }

    [Fact]
    public void PrivateFileId_ParsesOnlyPrivateUrls()
    {
        var id = Guid.NewGuid();
        Assert.Equal(id, PartnerVerificationService.PrivateFileId($"/api/v1/files/{id}"));
        Assert.Null(PartnerVerificationService.PrivateFileId("/uploads/2026/10/x.jpg"));
    }
}
