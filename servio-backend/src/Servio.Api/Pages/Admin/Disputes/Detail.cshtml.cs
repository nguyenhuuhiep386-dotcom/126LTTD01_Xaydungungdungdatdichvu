using System.Text.Json;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Common;
using Servio.Api.Data;
using Servio.Api.Data.Entities;
using Servio.Api.Services.Admin;
using Servio.Api.Services.Orders;

namespace Servio.Api.Pages.Admin.Disputes;

/// <summary>AW-08 — one case: description, evidence, the order and its chat, then RESOLVED/REJECTED with a note.</summary>
[Authorize(Policy = AuthPolicies.AdminSupport)]
public sealed class DetailModel(ServioDbContext db, DisputeService disputes) : PageModel
{
    public Dispute Dispute { get; private set; } = null!;
    public IReadOnlyList<string> Evidence { get; private set; } = [];
    public IReadOnlyList<(string Sender, string Content, string? Image, DateTimeOffset At)> Chat { get; private set; } = [];

    [TempData] public string? Flash { get; set; }
    public string? Error { get; private set; }

    public Task<IActionResult> OnGetAsync(Guid id, CancellationToken ct) => LoadAsync(id, ct);

    public async Task<IActionResult> OnPostResolveAsync(Guid id, bool upheld, string? note, CancellationToken ct)
    {
        try
        {
            await disputes.ResolveAsync(AdminActor.From(HttpContext), id, upheld, note ?? "", ct);
            Flash = upheld ? "Đã chấp nhận khiếu nại và báo hai bên." : "Đã bác bỏ khiếu nại và báo hai bên.";
            return RedirectToPage(new { id });
        }
        catch (ApiException e) when (e.StatusCode != StatusCodes.Status404NotFound)
        {
            Error = e.Message;
            return await LoadAsync(id, ct);
        }
    }

    private async Task<IActionResult> LoadAsync(Guid id, CancellationToken ct)
    {
        var dispute = await db.Disputes.AsNoTracking()
            .Include(d => d.RaisedByUser)
            .Include(d => d.Order).ThenInclude(o => o.ServiceRequest)
            .Include(d => d.Order).ThenInclude(o => o.Customer).ThenInclude(c => c.User)
            .Include(d => d.Order).ThenInclude(o => o.OrderAssignments).ThenInclude(a => a.PartnerProfile).ThenInclude(p => p.User)
            .FirstOrDefaultAsync(d => d.Id == id, ct);
        if (dispute is null)
        {
            return NotFound();
        }
        Dispute = dispute;
        Evidence = dispute.EvidenceUrls is null ? [] : JsonSerializer.Deserialize<string[]>(dispute.EvidenceUrls) ?? [];
        // The chat of the order is the main evidence (2.5.3); last 50 messages, oldest first.
        var messages = await db.Messages.AsNoTracking()
            .Where(m => m.Conversation.OrderId == dispute.OrderId)
            .OrderByDescending(m => m.CreatedAt).Take(50)
            .Select(m => new { Sender = m.SenderUser != null ? m.SenderUser.FullName : "Hệ thống", m.Content, m.AttachmentUrl, m.CreatedAt })
            .ToListAsync(ct);
        Chat = messages.OrderBy(m => m.CreatedAt).Select(m => (m.Sender, m.Content, m.AttachmentUrl, m.CreatedAt)).ToList();
        return Page();
    }
}
