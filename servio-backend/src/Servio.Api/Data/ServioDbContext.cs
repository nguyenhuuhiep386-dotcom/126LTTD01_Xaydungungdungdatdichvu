using System;
using System.Collections.Generic;
using Microsoft.EntityFrameworkCore;
using Servio.Api.Data.Entities;

namespace Servio.Api.Data;

public partial class ServioDbContext : DbContext
{
    public ServioDbContext(DbContextOptions<ServioDbContext> options)
        : base(options)
    {
    }

    public virtual DbSet<Address> Addresses { get; set; }

    public virtual DbSet<AdminUser> AdminUsers { get; set; }

    public virtual DbSet<ArrivalConfirmationRequest> ArrivalConfirmationRequests { get; set; }

    public virtual DbSet<AuditLog> AuditLogs { get; set; }

    public virtual DbSet<CancellationCharge> CancellationCharges { get; set; }

    public virtual DbSet<Conversation> Conversations { get; set; }

    public virtual DbSet<CustomerProfile> CustomerProfiles { get; set; }

    public virtual DbSet<Dispute> Disputes { get; set; }

    public virtual DbSet<Message> Messages { get; set; }

    public virtual DbSet<Notification> Notifications { get; set; }

    public virtual DbSet<Order> Orders { get; set; }

    public virtual DbSet<OrderAssignment> OrderAssignments { get; set; }

    public virtual DbSet<OrderExtraCharge> OrderExtraCharges { get; set; }

    public virtual DbSet<OrderImage> OrderImages { get; set; }

    public virtual DbSet<OrderStatusHistory> OrderStatusHistories { get; set; }

    public virtual DbSet<OtpCode> OtpCodes { get; set; }

    public virtual DbSet<PartnerDocument> PartnerDocuments { get; set; }

    public virtual DbSet<PartnerProfile> PartnerProfiles { get; set; }

    public virtual DbSet<PartnerSkill> PartnerSkills { get; set; }

    public virtual DbSet<Quote> Quotes { get; set; }

    public virtual DbSet<RefreshToken> RefreshTokens { get; set; }

    public virtual DbSet<Review> Reviews { get; set; }

    public virtual DbSet<ServiceCategory> ServiceCategories { get; set; }

    public virtual DbSet<ServiceRequest> ServiceRequests { get; set; }

    public virtual DbSet<ServiceRequestImage> ServiceRequestImages { get; set; }

    public virtual DbSet<SystemConfig> SystemConfigs { get; set; }

    public virtual DbSet<Transaction> Transactions { get; set; }

    public virtual DbSet<UploadedFile> UploadedFiles { get; set; }

    public virtual DbSet<User> Users { get; set; }

    public virtual DbSet<UserDevice> UserDevices { get; set; }

    public virtual DbSet<UserRole> UserRoles { get; set; }

    public virtual DbSet<Wallet> Wallets { get; set; }

    public virtual DbSet<WalletTransaction> WalletTransactions { get; set; }

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<Address>(entity =>
        {
            entity.HasIndex(e => e.UserId, "IX_Addresses_UserId").HasFilter("([DeletedAt] IS NULL)");

            entity.HasIndex(e => new { e.UserId, e.IsDefault }, "UX_Addresses_User_Default")
                .IsUnique()
                .HasFilter("([IsDefault]=(1) AND [DeletedAt] IS NULL)");

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_Addresses_CreatedAt");
            entity.Property(e => e.FullAddress).HasMaxLength(500);
            entity.Property(e => e.Label).HasMaxLength(50);
            entity.Property(e => e.Latitude).HasColumnType("decimal(9, 6)");
            entity.Property(e => e.Longitude).HasColumnType("decimal(9, 6)");
            entity.Property(e => e.Note).HasMaxLength(300);
            entity.Property(e => e.ReceiverName).HasMaxLength(100);
            entity.Property(e => e.ReceiverPhone).HasMaxLength(15);
            entity.Property(e => e.UpdatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_Addresses_UpdatedAt");

            entity.HasOne(d => d.User).WithMany(p => p.Addresses)
                .HasForeignKey(d => d.UserId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_Addresses_Users");
        });

        modelBuilder.Entity<AdminUser>(entity =>
        {
            entity.HasIndex(e => e.Email, "UQ_AdminUsers_Email").IsUnique();

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_AdminUsers_CreatedAt");
            entity.Property(e => e.Email).HasMaxLength(256);
            entity.Property(e => e.FullName).HasMaxLength(100);
            entity.Property(e => e.IsActive).HasDefaultValue(true, "DF_AdminUsers_IsActive");
            entity.Property(e => e.MustChangePassword).HasDefaultValue(true, "DF_AdminUsers_MustChangePassword");
            entity.Property(e => e.PasswordHash).HasMaxLength(256);
            entity.Property(e => e.UpdatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_AdminUsers_UpdatedAt");
        });

        modelBuilder.Entity<ArrivalConfirmationRequest>(entity =>
        {
            entity.HasIndex(e => new { e.OrderAssignmentId, e.Status }, "UX_ArrivalConfirmationRequests_Pending")
                .IsUnique()
                .HasFilter("([Status]=(1))");

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.Status).HasDefaultValue((byte)1, "DF_ArrivalConfirmationRequests_Status");

            entity.HasOne(d => d.OrderAssignment).WithMany(p => p.ArrivalConfirmationRequests)
                .HasForeignKey(d => d.OrderAssignmentId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_ArrivalConfirmationRequests_OrderAssignments");
        });

        modelBuilder.Entity<AuditLog>(entity =>
        {
            entity.HasIndex(e => new { e.EntityType, e.EntityId, e.CreatedAt }, "IX_AuditLogs_Entity").IsDescending(false, false, true);

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.Action)
                .HasMaxLength(100)
                .IsUnicode(false);
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_AuditLogs_CreatedAt");
            entity.Property(e => e.EntityId).HasMaxLength(100);
            entity.Property(e => e.EntityType)
                .HasMaxLength(100)
                .IsUnicode(false);
            entity.Property(e => e.IpAddress)
                .HasMaxLength(50)
                .IsUnicode(false);
            entity.Property(e => e.UserAgent).HasMaxLength(300);
        });

        modelBuilder.Entity<CancellationCharge>(entity =>
        {
            entity.HasIndex(e => new { e.CustomerId, e.Status }, "IX_CancellationCharges_Customer_Status");

            entity.HasIndex(e => e.OrderAssignmentId, "UQ_CancellationCharges_Assignment").IsUnique();

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.Amount).HasColumnType("decimal(18, 2)");
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_CancellationCharges_CreatedAt");
            entity.Property(e => e.Status).HasDefaultValue((byte)1, "DF_CancellationCharges_Status");
            entity.Property(e => e.UpdatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_CancellationCharges_UpdatedAt");

            entity.HasOne(d => d.CollectedTransaction).WithMany(p => p.CancellationCharges)
                .HasForeignKey(d => d.CollectedTransactionId)
                .HasConstraintName("FK_CancellationCharges_Transactions");

            entity.HasOne(d => d.Customer).WithMany(p => p.CancellationCharges)
                .HasForeignKey(d => d.CustomerId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_CancellationCharges_CustomerProfiles");

            entity.HasOne(d => d.OrderAssignment).WithOne(p => p.CancellationCharge)
                .HasForeignKey<CancellationCharge>(d => d.OrderAssignmentId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_CancellationCharges_OrderAssignments");

            entity.HasOne(d => d.Order).WithMany(p => p.CancellationCharges)
                .HasForeignKey(d => d.OrderId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_CancellationCharges_Orders");

            entity.HasOne(d => d.PartnerProfile).WithMany(p => p.CancellationCharges)
                .HasForeignKey(d => d.PartnerProfileId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_CancellationCharges_PartnerProfiles");
        });

        modelBuilder.Entity<Conversation>(entity =>
        {
            entity.HasIndex(e => new { e.CustomerId, e.LastMessageAt }, "IX_Conversations_Customer").IsDescending(false, true);

            entity.HasIndex(e => new { e.PartnerProfileId, e.LastMessageAt }, "IX_Conversations_Partner").IsDescending(false, true);

            entity.HasIndex(e => new { e.ServiceRequestId, e.PartnerProfileId }, "UQ_Conversations_Request_Partner").IsUnique();

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_Conversations_CreatedAt");
            entity.Property(e => e.LastMessagePreview).HasMaxLength(200);
            entity.Property(e => e.Status).HasDefaultValue((byte)1, "DF_Conversations_Status");
            entity.Property(e => e.UpdatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_Conversations_UpdatedAt");

            entity.HasOne(d => d.Customer).WithMany(p => p.Conversations)
                .HasForeignKey(d => d.CustomerId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_Conversations_CustomerProfiles");

            entity.HasOne(d => d.Order).WithMany(p => p.Conversations)
                .HasForeignKey(d => d.OrderId)
                .HasConstraintName("FK_Conversations_Orders");

            entity.HasOne(d => d.PartnerProfile).WithMany(p => p.Conversations)
                .HasForeignKey(d => d.PartnerProfileId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_Conversations_PartnerProfiles");

            entity.HasOne(d => d.ServiceRequest).WithMany(p => p.Conversations)
                .HasForeignKey(d => d.ServiceRequestId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_Conversations_ServiceRequests");
        });

        modelBuilder.Entity<CustomerProfile>(entity =>
        {
            entity.HasIndex(e => e.UserId, "UQ_CustomerProfiles_UserId").IsUnique();

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.AverageRating).HasColumnType("decimal(3, 2)");
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_CustomerProfiles_CreatedAt");
            entity.Property(e => e.UpdatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_CustomerProfiles_UpdatedAt");

            entity.HasOne(d => d.User).WithOne(p => p.CustomerProfile)
                .HasForeignKey<CustomerProfile>(d => d.UserId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_CustomerProfiles_Users");
        });

        modelBuilder.Entity<Dispute>(entity =>
        {
            entity.HasIndex(e => e.Code, "UQ_Disputes_Code").IsUnique();

            entity.HasIndex(e => new { e.OrderId, e.Status }, "UX_Disputes_OpenPerOrder")
                .IsUnique()
                .HasFilter("([Status] IN ((1), (2)))");

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.Code)
                .HasMaxLength(20)
                .IsUnicode(false);
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_Disputes_CreatedAt");
            entity.Property(e => e.Description).HasMaxLength(2000);
            entity.Property(e => e.ResolutionNote).HasMaxLength(1000);
            entity.Property(e => e.RowVersion)
                .IsRowVersion()
                .IsConcurrencyToken();
            entity.Property(e => e.Status).HasDefaultValue((byte)1, "DF_Disputes_Status");
            entity.Property(e => e.UpdatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_Disputes_UpdatedAt");

            entity.HasOne(d => d.AssignedAdmin).WithMany(p => p.Disputes)
                .HasForeignKey(d => d.AssignedAdminId)
                .HasConstraintName("FK_Disputes_AdminUsers");

            entity.HasOne(d => d.Order).WithMany(p => p.Disputes)
                .HasForeignKey(d => d.OrderId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_Disputes_Orders");

            entity.HasOne(d => d.RaisedByUser).WithMany(p => p.Disputes)
                .HasForeignKey(d => d.RaisedByUserId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_Disputes_Users");
        });

        modelBuilder.Entity<Message>(entity =>
        {
            entity.HasIndex(e => new { e.ConversationId, e.CreatedAt, e.Id }, "IX_Messages_Conversation");

            entity.HasIndex(e => new { e.ConversationId, e.SenderUserId, e.ClientMessageId }, "UQ_Messages_Client").IsUnique();

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.AttachmentUrl).HasMaxLength(500);
            entity.Property(e => e.Content).HasMaxLength(2000);
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_Messages_CreatedAt");

            entity.HasOne(d => d.Conversation).WithMany(p => p.Messages)
                .HasForeignKey(d => d.ConversationId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_Messages_Conversations");

            entity.HasOne(d => d.SenderUser).WithMany(p => p.Messages)
                .HasForeignKey(d => d.SenderUserId)
                .HasConstraintName("FK_Messages_Users");
        });

        modelBuilder.Entity<Notification>(entity =>
        {
            entity.HasIndex(e => new { e.UserId, e.AppFlavor, e.IsRead, e.CreatedAt }, "IX_Notifications_User_Flavor").IsDescending(false, false, false, true);

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.Body).HasMaxLength(500);
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_Notifications_CreatedAt");
            entity.Property(e => e.DeepLink).HasMaxLength(300);
            entity.Property(e => e.ImageUrl).HasMaxLength(500);
            entity.Property(e => e.Title).HasMaxLength(200);

            entity.HasOne(d => d.User).WithMany(p => p.Notifications)
                .HasForeignKey(d => d.UserId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_Notifications_Users");
        });

        modelBuilder.Entity<Order>(entity =>
        {
            entity.HasIndex(e => new { e.CustomerId, e.Status }, "IX_Orders_Customer_Status");

            entity.HasIndex(e => new { e.Status, e.CreatedAt }, "IX_Orders_Status_CreatedAt").IsDescending(false, true);

            entity.HasIndex(e => e.Code, "UQ_Orders_Code").IsUnique();

            entity.HasIndex(e => e.ServiceRequestId, "UQ_Orders_ServiceRequestId").IsUnique();

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.CancelReason).HasMaxLength(500);
            entity.Property(e => e.CancellationFee).HasColumnType("decimal(18, 2)");
            entity.Property(e => e.Code)
                .HasMaxLength(20)
                .IsUnicode(false);
            entity.Property(e => e.CommissionAmount).HasColumnType("decimal(18, 2)");
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_Orders_CreatedAt");
            entity.Property(e => e.ExtraChargeTotal).HasColumnType("decimal(18, 2)");
            entity.Property(e => e.PartnerEarning).HasColumnType("decimal(18, 2)");
            entity.Property(e => e.PaymentMethod).HasDefaultValue((byte)1, "DF_Orders_PaymentMethod");
            entity.Property(e => e.PaymentStatus).HasDefaultValue((byte)1, "DF_Orders_PaymentStatus");
            entity.Property(e => e.RowVersion)
                .IsRowVersion()
                .IsConcurrencyToken();
            entity.Property(e => e.SubTotal).HasColumnType("decimal(18, 2)");
            entity.Property(e => e.TotalAmount).HasColumnType("decimal(18, 2)");
            entity.Property(e => e.UpdatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_Orders_UpdatedAt");

            entity.HasOne(d => d.Customer).WithMany(p => p.Orders)
                .HasForeignKey(d => d.CustomerId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_Orders_CustomerProfiles");

            entity.HasOne(d => d.ServiceCategory).WithMany(p => p.Orders)
                .HasForeignKey(d => d.ServiceCategoryId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_Orders_ServiceCategories");

            entity.HasOne(d => d.ServiceRequest).WithOne(p => p.Order)
                .HasForeignKey<Order>(d => d.ServiceRequestId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_Orders_ServiceRequests");
        });

        modelBuilder.Entity<OrderAssignment>(entity =>
        {
            entity.HasIndex(e => new { e.PartnerProfileId, e.Status }, "IX_OrderAssignments_Partner_Status");

            entity.HasIndex(e => new { e.OrderId, e.PartnerProfileId }, "UQ_OrderAssignments_Order_Partner").IsUnique();

            entity.HasIndex(e => e.QuoteId, "UX_OrderAssignments_QuoteId")
                .IsUnique()
                .HasFilter("([QuoteId] IS NOT NULL)");

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.Amount).HasColumnType("decimal(18, 2)");
            entity.Property(e => e.ArrivalLatitude).HasColumnType("decimal(9, 6)");
            entity.Property(e => e.ArrivalLongitude).HasColumnType("decimal(9, 6)");
            entity.Property(e => e.CancelReason).HasMaxLength(500);
            entity.Property(e => e.CommissionAmount).HasColumnType("decimal(18, 2)");
            entity.Property(e => e.CommissionRateSnapshot).HasColumnType("decimal(5, 2)");
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_OrderAssignments_CreatedAt");
            entity.Property(e => e.CustomerPayable).HasColumnType("decimal(18, 2)");
            entity.Property(e => e.Earning).HasColumnType("decimal(18, 2)");
            entity.Property(e => e.ExtraChargeTotal).HasColumnType("decimal(18, 2)");
            entity.Property(e => e.IsPrimary).HasDefaultValue(true, "DF_OrderAssignments_IsPrimary");
            entity.Property(e => e.LastLatitude).HasColumnType("decimal(9, 6)");
            entity.Property(e => e.LastLongitude).HasColumnType("decimal(9, 6)");
            entity.Property(e => e.PaymentStatus).HasDefaultValue((byte)1, "DF_OrderAssignments_PaymentStatus");
            entity.Property(e => e.RowVersion)
                .IsRowVersion()
                .IsConcurrencyToken();
            entity.Property(e => e.UpdatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_OrderAssignments_UpdatedAt");

            entity.HasOne(d => d.Order).WithMany(p => p.OrderAssignments)
                .HasForeignKey(d => d.OrderId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_OrderAssignments_Orders");

            entity.HasOne(d => d.PartnerProfile).WithMany(p => p.OrderAssignments)
                .HasForeignKey(d => d.PartnerProfileId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_OrderAssignments_PartnerProfiles");

            entity.HasOne(d => d.Quote).WithOne(p => p.OrderAssignment)
                .HasForeignKey<OrderAssignment>(d => d.QuoteId)
                .HasConstraintName("FK_OrderAssignments_Quotes");
        });

        modelBuilder.Entity<OrderExtraCharge>(entity =>
        {
            entity.HasIndex(e => e.OrderId, "IX_OrderExtraCharges_Order");

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.Amount).HasColumnType("decimal(18, 2)");
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_OrderExtraCharges_CreatedAt");
            entity.Property(e => e.Description).HasMaxLength(300);
            entity.Property(e => e.EvidenceImageUrl).HasMaxLength(500);
            entity.Property(e => e.ResponseNote).HasMaxLength(500);
            entity.Property(e => e.RowVersion)
                .IsRowVersion()
                .IsConcurrencyToken();
            entity.Property(e => e.Status).HasDefaultValue((byte)1, "DF_OrderExtraCharges_Status");

            entity.HasOne(d => d.OrderAssignment).WithMany(p => p.OrderExtraCharges)
                .HasForeignKey(d => d.OrderAssignmentId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_OrderExtraCharges_OrderAssignments");

            entity.HasOne(d => d.Order).WithMany(p => p.OrderExtraCharges)
                .HasForeignKey(d => d.OrderId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_OrderExtraCharges_Orders");

            entity.HasOne(d => d.RespondedByUser).WithMany(p => p.OrderExtraCharges)
                .HasForeignKey(d => d.RespondedByUserId)
                .HasConstraintName("FK_OrderExtraCharges_Users");
        });

        modelBuilder.Entity<OrderImage>(entity =>
        {
            entity.HasIndex(e => new { e.OrderAssignmentId, e.Type }, "IX_OrderImages_Assignment");

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_OrderImages_CreatedAt");
            entity.Property(e => e.Latitude).HasColumnType("decimal(9, 6)");
            entity.Property(e => e.Longitude).HasColumnType("decimal(9, 6)");
            entity.Property(e => e.Url).HasMaxLength(500);

            entity.HasOne(d => d.OrderAssignment).WithMany(p => p.OrderImages)
                .HasForeignKey(d => d.OrderAssignmentId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_OrderImages_OrderAssignments");
        });

        modelBuilder.Entity<OrderStatusHistory>(entity =>
        {
            entity.HasIndex(e => new { e.OrderId, e.CreatedAt, e.Id }, "IX_OrderStatusHistories_Order");

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_OrderStatusHistories_CreatedAt");
            entity.Property(e => e.Latitude).HasColumnType("decimal(9, 6)");
            entity.Property(e => e.Longitude).HasColumnType("decimal(9, 6)");
            entity.Property(e => e.Note).HasMaxLength(500);

            entity.HasOne(d => d.ChangedByAdmin).WithMany(p => p.OrderStatusHistories)
                .HasForeignKey(d => d.ChangedByAdminId)
                .HasConstraintName("FK_OrderStatusHistories_AdminUsers");

            entity.HasOne(d => d.ChangedByUser).WithMany(p => p.OrderStatusHistories)
                .HasForeignKey(d => d.ChangedByUserId)
                .HasConstraintName("FK_OrderStatusHistories_Users");

            entity.HasOne(d => d.OrderAssignment).WithMany(p => p.OrderStatusHistories)
                .HasForeignKey(d => d.OrderAssignmentId)
                .HasConstraintName("FK_OrderStatusHistories_OrderAssignments");

            entity.HasOne(d => d.Order).WithMany(p => p.OrderStatusHistories)
                .HasForeignKey(d => d.OrderId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_OrderStatusHistories_Orders");
        });

        modelBuilder.Entity<OtpCode>(entity =>
        {
            entity.HasIndex(e => new { e.PhoneNumber, e.CreatedAt }, "IX_OtpCodes_Phone_CreatedAt").IsDescending(false, true);

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.CodeHash).HasMaxLength(256);
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_OtpCodes_CreatedAt");
            entity.Property(e => e.PhoneNumber).HasMaxLength(15);
        });

        modelBuilder.Entity<PartnerDocument>(entity =>
        {
            entity.HasIndex(e => e.PartnerProfileId, "IX_PartnerDocuments_PartnerProfileId");

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_PartnerDocuments_CreatedAt");
            entity.Property(e => e.DocumentNumber).HasMaxLength(50);
            entity.Property(e => e.FileUrl).HasMaxLength(500);
            entity.Property(e => e.RejectReason).HasMaxLength(500);
            entity.Property(e => e.Status).HasDefaultValue((byte)1, "DF_PartnerDocuments_Status");

            entity.HasOne(d => d.PartnerProfile).WithMany(p => p.PartnerDocuments)
                .HasForeignKey(d => d.PartnerProfileId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_PartnerDocuments_PartnerProfiles");
        });

        modelBuilder.Entity<PartnerProfile>(entity =>
        {
            entity.HasIndex(e => new { e.IsOnline, e.VerificationStatus }, "IX_PartnerProfiles_Online_Status");

            entity.HasIndex(e => e.UserId, "UQ_PartnerProfiles_UserId").IsUnique();

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.AnchorLatitude).HasColumnType("decimal(9, 6)");
            entity.Property(e => e.AnchorLongitude).HasColumnType("decimal(9, 6)");
            entity.Property(e => e.AverageRating).HasColumnType("decimal(3, 2)");
            entity.Property(e => e.Bio).HasMaxLength(1000);
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_PartnerProfiles_CreatedAt");
            entity.Property(e => e.CurrentLatitude).HasColumnType("decimal(9, 6)");
            entity.Property(e => e.CurrentLongitude).HasColumnType("decimal(9, 6)");
            entity.Property(e => e.ServiceRadiusKm).HasDefaultValue(10, "DF_PartnerProfiles_ServiceRadiusKm");
            entity.Property(e => e.UpdatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_PartnerProfiles_UpdatedAt");
            entity.Property(e => e.VerificationNote).HasMaxLength(500);

            entity.HasOne(d => d.User).WithOne(p => p.PartnerProfile)
                .HasForeignKey<PartnerProfile>(d => d.UserId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_PartnerProfiles_Users");

            entity.HasOne(d => d.VerifiedByAdmin).WithMany(p => p.PartnerProfiles)
                .HasForeignKey(d => d.VerifiedByAdminId)
                .HasConstraintName("FK_PartnerProfiles_AdminUsers");
        });

        modelBuilder.Entity<PartnerSkill>(entity =>
        {
            entity.HasIndex(e => new { e.PartnerProfileId, e.ServiceCategoryId }, "UQ_PartnerSkills_Partner_Category").IsUnique();

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.CertificateUrl).HasMaxLength(500);
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_PartnerSkills_CreatedAt");
            entity.Property(e => e.Status).HasDefaultValue((byte)1, "DF_PartnerSkills_Status");

            entity.HasOne(d => d.PartnerProfile).WithMany(p => p.PartnerSkills)
                .HasForeignKey(d => d.PartnerProfileId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_PartnerSkills_PartnerProfiles");

            entity.HasOne(d => d.ServiceCategory).WithMany(p => p.PartnerSkills)
                .HasForeignKey(d => d.ServiceCategoryId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_PartnerSkills_ServiceCategories");
        });

        modelBuilder.Entity<Quote>(entity =>
        {
            entity.HasIndex(e => new { e.PartnerProfileId, e.Status }, "IX_Quotes_Partner_Status");

            entity.HasIndex(e => new { e.ServiceRequestId, e.PartnerProfileId, e.RequestRevision }, "UQ_Quotes_Request_Partner_Revision").IsUnique();

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.Amount).HasColumnType("decimal(18, 2)");
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_Quotes_CreatedAt");
            entity.Property(e => e.Note).HasMaxLength(500);
            entity.Property(e => e.RequestRevision).HasDefaultValue(1, "DF_Quotes_RequestRevision");
            entity.Property(e => e.RowVersion)
                .IsRowVersion()
                .IsConcurrencyToken();
            entity.Property(e => e.Status).HasDefaultValue((byte)1, "DF_Quotes_Status");
            entity.Property(e => e.UpdatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_Quotes_UpdatedAt");

            entity.HasOne(d => d.PartnerProfile).WithMany(p => p.Quotes)
                .HasForeignKey(d => d.PartnerProfileId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_Quotes_PartnerProfiles");

            entity.HasOne(d => d.ServiceRequest).WithMany(p => p.Quotes)
                .HasForeignKey(d => d.ServiceRequestId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_Quotes_ServiceRequests");
        });

        modelBuilder.Entity<RefreshToken>(entity =>
        {
            entity.HasIndex(e => new { e.UserId, e.SessionId }, "IX_RefreshTokens_Session");

            entity.HasIndex(e => e.TokenHash, "UQ_RefreshTokens_TokenHash").IsUnique();

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_RefreshTokens_CreatedAt");
            entity.Property(e => e.DeviceId).HasMaxLength(200);
            entity.Property(e => e.TokenHash).HasMaxLength(256);

            entity.HasOne(d => d.User).WithMany(p => p.RefreshTokens)
                .HasForeignKey(d => d.UserId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_RefreshTokens_Users");
        });

        modelBuilder.Entity<Review>(entity =>
        {
            entity.HasIndex(e => new { e.RevieweeId, e.CreatedAt }, "IX_Reviews_Reviewee").IsDescending(false, true);

            entity.HasIndex(e => new { e.OrderAssignmentId, e.ReviewerId }, "UQ_Reviews_Assignment_Reviewer").IsUnique();

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.Comment).HasMaxLength(500);
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_Reviews_CreatedAt");
            entity.Property(e => e.HiddenReason).HasMaxLength(300);
            entity.Property(e => e.IsVisible).HasDefaultValue(true, "DF_Reviews_IsVisible");
            entity.Property(e => e.PublishAt).HasDefaultValueSql("(sysutcdatetime())", "DF_Reviews_PublishAt");
            entity.Property(e => e.Tags).HasMaxLength(300);

            entity.HasOne(d => d.OrderAssignment).WithMany(p => p.Reviews)
                .HasForeignKey(d => d.OrderAssignmentId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_Reviews_OrderAssignments");

            entity.HasOne(d => d.Order).WithMany(p => p.Reviews)
                .HasForeignKey(d => d.OrderId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_Reviews_Orders");

            entity.HasOne(d => d.Reviewee).WithMany(p => p.ReviewReviewees)
                .HasForeignKey(d => d.RevieweeId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_Reviews_Reviewee");

            entity.HasOne(d => d.Reviewer).WithMany(p => p.ReviewReviewers)
                .HasForeignKey(d => d.ReviewerId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_Reviews_Reviewer");
        });

        modelBuilder.Entity<ServiceCategory>(entity =>
        {
            entity.HasIndex(e => new { e.ParentId, e.DisplayOrder }, "IX_ServiceCategories_ParentId");

            entity.HasIndex(e => e.Slug, "UQ_ServiceCategories_Slug").IsUnique();

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.CommissionRate).HasColumnType("decimal(5, 2)");
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_ServiceCategories_CreatedAt");
            entity.Property(e => e.Description).HasMaxLength(1000);
            entity.Property(e => e.IconUrl).HasMaxLength(500);
            entity.Property(e => e.IsActive).HasDefaultValue(true, "DF_ServiceCategories_IsActive");
            entity.Property(e => e.Name).HasMaxLength(150);
            entity.Property(e => e.ReferencePriceMax).HasColumnType("decimal(18, 2)");
            entity.Property(e => e.ReferencePriceMin).HasColumnType("decimal(18, 2)");
            entity.Property(e => e.Slug)
                .HasMaxLength(150)
                .IsUnicode(false);
            entity.Property(e => e.UpdatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_ServiceCategories_UpdatedAt");

            entity.HasOne(d => d.Parent).WithMany(p => p.InverseParent)
                .HasForeignKey(d => d.ParentId)
                .HasConstraintName("FK_ServiceCategories_Parent");
        });

        modelBuilder.Entity<ServiceRequest>(entity =>
        {
            entity.HasIndex(e => new { e.CustomerId, e.CreatedAt }, "IX_ServiceRequests_Customer").IsDescending(false, true);

            entity.HasIndex(e => new { e.Status, e.ServiceCategoryId, e.CreatedAt }, "IX_ServiceRequests_Status_Category").IsDescending(false, false, true);

            entity.HasIndex(e => e.Code, "UQ_ServiceRequests_Code").IsUnique();

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.AddressSnapshot).HasMaxLength(500);
            entity.Property(e => e.BudgetMax).HasColumnType("decimal(18, 2)");
            entity.Property(e => e.BudgetMin).HasColumnType("decimal(18, 2)");
            entity.Property(e => e.CancelReason).HasMaxLength(500);
            entity.Property(e => e.Code)
                .HasMaxLength(20)
                .IsUnicode(false);
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_ServiceRequests_CreatedAt");
            entity.Property(e => e.Description).HasMaxLength(2000);
            entity.Property(e => e.Latitude).HasColumnType("decimal(9, 6)");
            entity.Property(e => e.Longitude).HasColumnType("decimal(9, 6)");
            entity.Property(e => e.ModerationNote).HasMaxLength(500);
            entity.Property(e => e.RequireMinRating).HasColumnType("decimal(3, 2)");
            entity.Property(e => e.Revision).HasDefaultValue(1, "DF_ServiceRequests_Revision");
            entity.Property(e => e.RowVersion)
                .IsRowVersion()
                .IsConcurrencyToken();
            entity.Property(e => e.SearchRadiusKm).HasDefaultValue(10, "DF_ServiceRequests_SearchRadiusKm");
            entity.Property(e => e.Title).HasMaxLength(150);
            entity.Property(e => e.UpdatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_ServiceRequests_UpdatedAt");

            entity.HasOne(d => d.Address).WithMany(p => p.ServiceRequests)
                .HasForeignKey(d => d.AddressId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_ServiceRequests_Addresses");

            entity.HasOne(d => d.Customer).WithMany(p => p.ServiceRequests)
                .HasForeignKey(d => d.CustomerId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_ServiceRequests_CustomerProfiles");

            entity.HasOne(d => d.ServiceCategory).WithMany(p => p.ServiceRequests)
                .HasForeignKey(d => d.ServiceCategoryId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_ServiceRequests_ServiceCategories");
        });

        modelBuilder.Entity<ServiceRequestImage>(entity =>
        {
            entity.HasIndex(e => new { e.ServiceRequestId, e.DisplayOrder }, "IX_ServiceRequestImages_Request");

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.MediaType).HasDefaultValue((byte)1, "DF_ServiceRequestImages_MediaType");
            entity.Property(e => e.ThumbnailUrl).HasMaxLength(500);
            entity.Property(e => e.Url).HasMaxLength(500);

            entity.HasOne(d => d.ServiceRequest).WithMany(p => p.ServiceRequestImages)
                .HasForeignKey(d => d.ServiceRequestId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_ServiceRequestImages_ServiceRequests");
        });

        modelBuilder.Entity<SystemConfig>(entity =>
        {
            entity.HasKey(e => e.Key);

            entity.Property(e => e.Key)
                .HasMaxLength(100)
                .IsUnicode(false);
            entity.Property(e => e.DataType)
                .HasMaxLength(20)
                .IsUnicode(false);
            entity.Property(e => e.Description).HasMaxLength(500);
            entity.Property(e => e.Group)
                .HasMaxLength(50)
                .IsUnicode(false);
            entity.Property(e => e.UpdatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_SystemConfigs_UpdatedAt");

            entity.HasOne(d => d.UpdatedByAdmin).WithMany(p => p.SystemConfigs)
                .HasForeignKey(d => d.UpdatedByAdminId)
                .HasConstraintName("FK_SystemConfigs_AdminUsers");
        });

        modelBuilder.Entity<Transaction>(entity =>
        {
            entity.HasIndex(e => new { e.Status, e.CreatedAt }, "IX_Transactions_Status_CreatedAt").IsDescending(false, true);

            entity.HasIndex(e => e.Code, "UQ_Transactions_Code").IsUnique();

            entity.HasIndex(e => e.BusinessEventKey, "UX_Transactions_BusinessEventKey")
                .IsUnique()
                .HasFilter("([BusinessEventKey] IS NOT NULL)");

            entity.HasIndex(e => new { e.UserId, e.Type, e.IdempotencyKey }, "UX_Transactions_Idempotency")
                .IsUnique()
                .HasFilter("([IdempotencyKey] IS NOT NULL)");

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.Amount).HasColumnType("decimal(18, 2)");
            entity.Property(e => e.BankTransferReference).HasMaxLength(100);
            entity.Property(e => e.BusinessEventKey).HasMaxLength(200);
            entity.Property(e => e.Code)
                .HasMaxLength(30)
                .IsUnicode(false);
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_Transactions_CreatedAt");
            entity.Property(e => e.Currency)
                .HasMaxLength(3)
                .IsUnicode(false)
                .IsFixedLength()
                .HasDefaultValue("VND", "DF_Transactions_Currency");
            entity.Property(e => e.IdempotencyKey).HasMaxLength(100);
            entity.Property(e => e.UpdatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_Transactions_UpdatedAt");

            entity.HasOne(d => d.ConfirmedByAdmin).WithMany(p => p.Transactions)
                .HasForeignKey(d => d.ConfirmedByAdminId)
                .HasConstraintName("FK_Transactions_AdminUsers");

            entity.HasOne(d => d.EvidenceFile).WithMany(p => p.Transactions)
                .HasForeignKey(d => d.EvidenceFileId)
                .HasConstraintName("FK_Transactions_UploadedFiles");

            entity.HasOne(d => d.Order).WithMany(p => p.Transactions)
                .HasForeignKey(d => d.OrderId)
                .HasConstraintName("FK_Transactions_Orders");

            entity.HasOne(d => d.User).WithMany(p => p.Transactions)
                .HasForeignKey(d => d.UserId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_Transactions_Users");
        });

        modelBuilder.Entity<UploadedFile>(entity =>
        {
            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_UploadedFiles_CreatedAt");
            entity.Property(e => e.MimeType)
                .HasMaxLength(100)
                .IsUnicode(false);
            entity.Property(e => e.ObjectKey).HasMaxLength(500);
            entity.Property(e => e.Status).HasDefaultValue((byte)3, "DF_UploadedFiles_Status");

            entity.HasOne(d => d.OwnerUser).WithMany(p => p.UploadedFiles)
                .HasForeignKey(d => d.OwnerUserId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_UploadedFiles_Users");
        });

        modelBuilder.Entity<User>(entity =>
        {
            entity.HasIndex(e => e.Status, "IX_Users_Status");

            entity.HasIndex(e => e.PhoneNumber, "UQ_Users_PhoneNumber").IsUnique();

            entity.HasIndex(e => e.Email, "UX_Users_Email")
                .IsUnique()
                .HasFilter("([Email] IS NOT NULL)");

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.AvatarUrl).HasMaxLength(500);
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_Users_CreatedAt");
            entity.Property(e => e.Email).HasMaxLength(256);
            entity.Property(e => e.FullName)
                .HasMaxLength(100)
                .HasDefaultValue("", "DF_Users_FullName");
            entity.Property(e => e.PhoneNumber).HasMaxLength(15);
            entity.Property(e => e.Status).HasDefaultValue((byte)1, "DF_Users_Status");
            entity.Property(e => e.UpdatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_Users_UpdatedAt");
        });

        modelBuilder.Entity<UserDevice>(entity =>
        {
            entity.HasIndex(e => new { e.UserId, e.DeviceId, e.AppFlavor }, "UQ_UserDevices_User_Device_Flavor").IsUnique();

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.AppVersion).HasMaxLength(20);
            entity.Property(e => e.DeviceId).HasMaxLength(200);
            entity.Property(e => e.FcmToken).HasMaxLength(500);
            entity.Property(e => e.IsActive).HasDefaultValue(true, "DF_UserDevices_IsActive");
            entity.Property(e => e.LastActiveAt).HasDefaultValueSql("(sysutcdatetime())", "DF_UserDevices_LastActiveAt");
            entity.Property(e => e.Platform).HasDefaultValue((byte)1, "DF_UserDevices_Platform");

            entity.HasOne(d => d.User).WithMany(p => p.UserDevices)
                .HasForeignKey(d => d.UserId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_UserDevices_Users");
        });

        modelBuilder.Entity<UserRole>(entity =>
        {
            entity.HasIndex(e => new { e.UserId, e.Role }, "UQ_UserRoles_User_Role").IsUnique();

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_UserRoles_CreatedAt");

            entity.HasOne(d => d.User).WithMany(p => p.UserRoles)
                .HasForeignKey(d => d.UserId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_UserRoles_Users");
        });

        modelBuilder.Entity<Wallet>(entity =>
        {
            entity.HasIndex(e => e.UserId, "UQ_Wallets_UserId").IsUnique();

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.AvailableBalance).HasColumnType("decimal(18, 2)");
            entity.Property(e => e.Currency)
                .HasMaxLength(3)
                .IsUnicode(false)
                .IsFixedLength()
                .HasDefaultValue("VND", "DF_Wallets_Currency");
            entity.Property(e => e.DebtBalance).HasColumnType("decimal(18, 2)");
            entity.Property(e => e.RowVersion)
                .IsRowVersion()
                .IsConcurrencyToken();
            entity.Property(e => e.UpdatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_Wallets_UpdatedAt");

            entity.HasOne(d => d.User).WithOne(p => p.Wallet)
                .HasForeignKey<Wallet>(d => d.UserId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_Wallets_Users");
        });

        modelBuilder.Entity<WalletTransaction>(entity =>
        {
            entity.HasIndex(e => new { e.WalletId, e.CreatedAt }, "IX_WalletTransactions_Wallet").IsDescending(false, true);

            entity.HasIndex(e => new { e.WalletId, e.Bucket, e.Type, e.BusinessEventKey }, "UQ_WalletTransactions_Event").IsUnique();

            entity.Property(e => e.Id).ValueGeneratedNever();
            entity.Property(e => e.Amount).HasColumnType("decimal(18, 2)");
            entity.Property(e => e.BalanceAfter).HasColumnType("decimal(18, 2)");
            entity.Property(e => e.BalanceBefore).HasColumnType("decimal(18, 2)");
            entity.Property(e => e.BusinessEventKey).HasMaxLength(200);
            entity.Property(e => e.CreatedAt).HasDefaultValueSql("(sysutcdatetime())", "DF_WalletTransactions_CreatedAt");
            entity.Property(e => e.Description).HasMaxLength(300);

            entity.HasOne(d => d.CancellationCharge).WithMany(p => p.WalletTransactions)
                .HasForeignKey(d => d.CancellationChargeId)
                .HasConstraintName("FK_WalletTransactions_CancellationCharges");

            entity.HasOne(d => d.OrderAssignment).WithMany(p => p.WalletTransactions)
                .HasForeignKey(d => d.OrderAssignmentId)
                .HasConstraintName("FK_WalletTransactions_OrderAssignments");

            entity.HasOne(d => d.Order).WithMany(p => p.WalletTransactions)
                .HasForeignKey(d => d.OrderId)
                .HasConstraintName("FK_WalletTransactions_Orders");

            entity.HasOne(d => d.Transaction).WithMany(p => p.WalletTransactions)
                .HasForeignKey(d => d.TransactionId)
                .HasConstraintName("FK_WalletTransactions_Transactions");

            entity.HasOne(d => d.Wallet).WithMany(p => p.WalletTransactions)
                .HasForeignKey(d => d.WalletId)
                .OnDelete(DeleteBehavior.ClientSetNull)
                .HasConstraintName("FK_WalletTransactions_Wallets");
        });
        modelBuilder.HasSequence<int>("DisputeCodeSeq");
        modelBuilder.HasSequence<int>("OrderCodeSeq");
        modelBuilder.HasSequence<int>("ServiceRequestCodeSeq");
        modelBuilder.HasSequence<int>("TransactionCodeSeq");

        OnModelCreatingPartial(modelBuilder);
    }

    partial void OnModelCreatingPartial(ModelBuilder modelBuilder);
}
