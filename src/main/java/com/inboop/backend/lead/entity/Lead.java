package com.inboop.backend.lead.entity;

import com.inboop.backend.auth.entity.User;
import com.inboop.backend.business.entity.Business;
import com.inboop.backend.lead.enums.ChannelType;
import com.inboop.backend.lead.enums.LeadStatus;
import com.inboop.backend.lead.enums.LeadType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "leads", indexes = {
        @Index(name = "idx_lead_business_id", columnList = "business_id"),
        @Index(name = "idx_lead_conversation_id", columnList = "conversation_id"),
        @Index(name = "idx_lead_status", columnList = "status"),
        @Index(name = "idx_lead_created_at", columnList = "created_at")
})
public class Lead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Column(name = "instagram_user_id", nullable = false)
    private String instagramUserId;

    @Column(name = "instagram_username")
    private String instagramUsername;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "profile_picture")
    private String profilePicture;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChannelType channel = ChannelType.INSTAGRAM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeadStatus status = LeadStatus.NEW;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeadType type = LeadType.OTHER;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private User assignedTo;

    @ElementCollection
    @CollectionTable(name = "lead_labels", joinColumns = @JoinColumn(name = "lead_id"))
    @Column(name = "label")
    private Set<String> labels = new HashSet<>();

    @Column(name = "detected_language")
    private String detectedLanguage;

    @Column(name = "\"value\"", precision = 10, scale = 2)
    private BigDecimal value;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "last_message_snippet")
    private String lastMessageSnippet;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // A lead belongs to one conversation (same customer can have multiple leads
    // over time from same conversation)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id")
    private Conversation conversation;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Business getBusiness() {
        return business;
    }

    public void setBusiness(Business business) {
        this.business = business;
    }

    public String getInstagramUserId() {
        return instagramUserId;
    }

    public void setInstagramUserId(String instagramUserId) {
        this.instagramUserId = instagramUserId;
    }

    public String getInstagramUsername() {
        return instagramUsername;
    }

    public void setInstagramUsername(String instagramUsername) {
        this.instagramUsername = instagramUsername;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public LeadStatus getStatus() {
        return status;
    }

    public void setStatus(LeadStatus status) {
        this.status = status;
    }

    public LeadType getType() {
        return type;
    }

    public void setType(LeadType type) {
        this.type = type;
    }

    public User getAssignedTo() {
        return assignedTo;
    }

    public void setAssignedTo(User assignedTo) {
        this.assignedTo = assignedTo;
    }

    public Set<String> getLabels() {
        return labels;
    }

    public void setLabels(Set<String> labels) {
        this.labels = labels;
    }

    public String getDetectedLanguage() {
        return detectedLanguage;
    }

    public void setDetectedLanguage(String detectedLanguage) {
        this.detectedLanguage = detectedLanguage;
    }

    public String getProfilePicture() {
        return profilePicture;
    }

    public void setProfilePicture(String profilePicture) {
        this.profilePicture = profilePicture;
    }

    public ChannelType getChannel() {
        return channel;
    }

    public void setChannel(ChannelType channel) {
        this.channel = channel;
    }

    public BigDecimal getValue() {
        return value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getLastMessageSnippet() {
        return lastMessageSnippet;
    }

    public void setLastMessageSnippet(String lastMessageSnippet) {
        this.lastMessageSnippet = lastMessageSnippet;
    }

    public LocalDateTime getLastMessageAt() {
        return lastMessageAt;
    }

    public void setLastMessageAt(LocalDateTime lastMessageAt) {
        this.lastMessageAt = lastMessageAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public void setConversation(Conversation conversation) {
        this.conversation = conversation;
    }
}
