using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Servio.Api.Common;
using Servio.Api.Services.Admin;

namespace Servio.Api.Pages.Admin.Catalog;

/// <summary>AW-11 — create (no id) or edit a category.</summary>
[Authorize(Policy = AuthPolicies.AdminOperator)]
public sealed class CategoryEditModel(AdminCategoryService categories) : PageModel
{
    [BindProperty]
    public CategoryForm Form { get; set; } = new();

    public IReadOnlyList<(Guid Id, string Name)> Groups { get; private set; } = [];

    public bool IsNew { get; private set; }

    [TempData]
    public string? Flash { get; set; }

    public async Task<IActionResult> OnGetAsync(Guid? id, CancellationToken ct)
    {
        IsNew = id is null;
        if (id is not null)
        {
            try
            {
                Form = await categories.GetFormAsync(id.Value, ct);
            }
            catch (ApiException e) when (e.StatusCode == StatusCodes.Status404NotFound)
            {
                return NotFound();
            }
        }
        Groups = await categories.ListGroupsAsync(id, ct);
        return Page();
    }

    public async Task<IActionResult> OnPostAsync(Guid? id, CancellationToken ct)
    {
        IsNew = id is null;
        try
        {
            await categories.SaveAsync(AdminActor.From(HttpContext), id, Form, ct);
            Flash = IsNew ? $"Đã thêm danh mục \"{Form.Name.Trim()}\"." : $"Đã lưu danh mục \"{Form.Name.Trim()}\".";
            return RedirectToPage("Categories");
        }
        catch (ApiException e) when (e.StatusCode == StatusCodes.Status400BadRequest)
        {
            ModelState.AddModelError($"{nameof(Form)}.{e.Field}", e.Message);
            Groups = await categories.ListGroupsAsync(id, ct);
            return Page();
        }
    }
}
