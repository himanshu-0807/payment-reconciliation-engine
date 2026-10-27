package dev.recon.service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "app_user")
public class AppUser {

    public enum Role { OPS_ANALYST, OPS_SUPERVISOR, ADMIN }

    @Id
    private String username;
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
    @Column(nullable = false)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Role role;
    @Column(name = "display_name", nullable = false)
    private String displayName;
    @Column(nullable = false)
    private boolean enabled;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AppUser() {
    }

    public AppUser(String username, String passwordHash, Role role, String displayName) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.displayName = displayName;
        this.enabled = true;
        this.createdAt = Instant.now();
    }

    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public String getDisplayName() { return displayName; }
    public boolean isEnabled() { return enabled; }
}
