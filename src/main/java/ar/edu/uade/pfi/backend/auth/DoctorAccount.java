package ar.edu.uade.pfi.backend.auth;

import java.time.Instant;
import java.util.List;

public class DoctorAccount {
  private final String id;
  private final String fullName;
  private final String email;
  private final String passwordHash;
  private final String licenseNumber;
  private final String specialty;
  private final String institution;
  private List<String> roles;
  private final Instant createdAt;
  private boolean verified;
  private boolean approved;
  private boolean twoFactorEnabled;
  private boolean onboardingCompleted;

  public DoctorAccount(
      String id,
      String fullName,
      String email,
      String passwordHash,
      String licenseNumber,
      String specialty,
      String institution,
      List<String> roles,
      Instant createdAt,
      boolean verified,
      boolean approved,
      boolean twoFactorEnabled,
      boolean onboardingCompleted) {
    this.id = id;
    this.fullName = fullName;
    this.email = email;
    this.passwordHash = passwordHash;
    this.licenseNumber = licenseNumber;
    this.specialty = specialty;
    this.institution = institution;
    this.roles = roles;
    this.createdAt = createdAt;
    this.verified = verified;
    this.approved = approved;
    this.twoFactorEnabled = twoFactorEnabled;
    this.onboardingCompleted = onboardingCompleted;
  }

  public String id() {
    return id;
  }

  public String fullName() {
    return fullName;
  }

  public String email() {
    return email;
  }

  public String passwordHash() {
    return passwordHash;
  }

  public String licenseNumber() {
    return licenseNumber;
  }

  public String specialty() {
    return specialty;
  }

  public String institution() {
    return institution;
  }

  public List<String> roles() {
    return roles;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public boolean verified() {
    return verified;
  }

  public boolean approved() {
    return approved;
  }

  public boolean twoFactorEnabled() {
    return twoFactorEnabled;
  }

  public boolean onboardingCompleted() {
    return onboardingCompleted;
  }

  public void verify() {
    this.verified = true;
  }

  /**
   * P10-B.2: sets ONLY the approval flag — never touches roles. Before this fix, this method
   * silently overwrote roles to a hardcoded DOCTOR+REVIEWER/PENDING_APPROVAL pair as a side effect,
   * which meant every activation/deactivation replaced whatever role set the account actually had
   * (a single-role account, an account missing REVIEWER, etc.) with that fixed pair. Callers that
   * need to change roles must call {@link #setRoles(List)} explicitly and separately — account
   * status and account roles are two different concerns.
   */
  public void approve(boolean approved) {
    this.approved = approved;
  }

  public void setRoles(List<String> roles) {
    this.roles = roles;
  }

  public void setTwoFactorEnabled(boolean enabled) {
    this.twoFactorEnabled = enabled;
  }

  public void setOnboardingCompleted(boolean completed) {
    this.onboardingCompleted = completed;
  }
}
