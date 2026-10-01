using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Servio.Api.Common;
using Servio.Api.Services.Catalog;

namespace Servio.Api.Controllers;

/// <summary>M2 — service catalog (#29, #30). Public.</summary>
[Route("api/v1/service-categories")]
[AllowAnonymous]
public sealed class ServiceCategoriesController(CategoryService categories) : ApiControllerBase
{
    /// <summary>#29 Category tree. Without parentId: level-1 groups with level-2 children.</summary>
    [HttpGet]
    public async Task<ActionResult<ApiResponse<IReadOnlyList<CategoryDto>>>> GetTree([FromQuery] Guid? parentId, CancellationToken ct) =>
        OkEnvelope(await categories.GetTreeAsync(parentId, ct));

    /// <summary>#30 Category detail with its children.</summary>
    [HttpGet("{id:guid}")]
    public async Task<ActionResult<ApiResponse<CategoryDto>>> GetById(Guid id, CancellationToken ct) =>
        OkEnvelope(await categories.GetByIdAsync(id, ct));
}
