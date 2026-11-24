using Microsoft.EntityFrameworkCore;

namespace CadField.Api.Data;

public class Project
{
    public Guid Id { get; set; }
    public required string Name { get; set; }
    public required string OrganizationId { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
}

public class Document
{
    public Guid Id { get; set; }
    public Guid ProjectId { get; set; }
    public required string DisplayName { get; set; }
    public string? Discipline { get; set; }
    public string? Front { get; set; }
    public string? Tunnel { get; set; }
    public required string OriginalHash { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
}

/// <summary>Cabeza de rama por documento. Concurrencia optimista (compare-and-swap).</summary>
public class BranchRef
{
    public Guid DocumentId { get; set; }
    public required string Branch { get; set; }
    public required string CommitId { get; set; }
    public required string UpdatedBy { get; set; }
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;
    public uint Version { get; set; } // xmin de PostgreSQL
}

/// <summary>Objeto direccionado por contenido (SHA-256). Inmutable.</summary>
public class StoredObject
{
    public required string Hash { get; set; }
    public long Size { get; set; }
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
}

/// <summary>Qué documentos referencian un objeto (autorización de descarga).</summary>
public class ObjectLink
{
    public Guid DocumentId { get; set; }
    public required string Hash { get; set; }
}

public class Membership
{
    public Guid ProjectId { get; set; }
    public required string UserId { get; set; }
    public required string Role { get; set; } // Viewer | Editor | Reviewer | Admin
}

/// <summary>Auditoría inmutable (sólo INSERT; el usuario de BD de la API no tiene UPDATE/DELETE).</summary>
public class AuditEntry
{
    public long Id { get; set; }
    public DateTimeOffset Timestamp { get; set; } = DateTimeOffset.UtcNow;
    public required string UserId { get; set; }
    public required string Action { get; set; }
    public Guid? DocumentId { get; set; }
    public required string Detail { get; set; }
    public string? Ip { get; set; }
}

public class AppDbContext(DbContextOptions<AppDbContext> options) : DbContext(options)
{
    public DbSet<Project> Projects => Set<Project>();
    public DbSet<Document> Documents => Set<Document>();
    public DbSet<BranchRef> Refs => Set<BranchRef>();
    public DbSet<StoredObject> Objects => Set<StoredObject>();
    public DbSet<ObjectLink> ObjectLinks => Set<ObjectLink>();
    public DbSet<Membership> Memberships => Set<Membership>();
    public DbSet<AuditEntry> Audit => Set<AuditEntry>();

    protected override void OnModelCreating(ModelBuilder b)
    {
        b.Entity<BranchRef>().HasKey(r => new { r.DocumentId, r.Branch });
        b.Entity<BranchRef>().Property(r => r.Version).IsRowVersion(); // xmin → concurrencia optimista
        b.Entity<StoredObject>().HasKey(o => o.Hash);
        b.Entity<StoredObject>().Property(o => o.Hash).HasMaxLength(64).IsFixedLength();
        b.Entity<ObjectLink>().HasKey(l => new { l.DocumentId, l.Hash });
        b.Entity<ObjectLink>().HasIndex(l => l.Hash);
        b.Entity<Membership>().HasKey(m => new { m.ProjectId, m.UserId });
        b.Entity<Document>().HasIndex(d => d.ProjectId);
        b.Entity<AuditEntry>().HasIndex(a => new { a.DocumentId, a.Timestamp });
    }
}
