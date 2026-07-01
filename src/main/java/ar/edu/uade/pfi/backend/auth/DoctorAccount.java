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
    private final List<String> roles;
    private final Instant createdAt;
    private boolean verified;

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
        boolean verified
    ) {
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
    }

    public String id() { return id; }
    public String fullName() { return fullName; }
    public String email() { return email; }
    public String passwordHash() { return passwordHash; }
    public String licenseNumber() { return licenseNumber; }
    public String specialty() { return specialty; }
    public String institution() { return institution; }
    public List<String> roles() { return roles; }
    public Instant createdAt() { return createdAt; }
    public boolean verified() { return verified; }
    public void verify() { this.verified = true; }
}
