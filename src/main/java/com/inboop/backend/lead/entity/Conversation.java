package com.inboop.backend.lead.entity;

import com.inboop.backend.business.entity.Business;
import com.inboop.backend.lead.enums.ChannelType;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "conversations", indexes = {
        @Index(name = "idx_conversation_business_id", columnList = "business_id"),
        @Index(name = "idx_conversation_instagram_id", columnList = "instagram_conversation_id"),
        @Index(name = "idx_conversation_last_message_at", columnList = "last_message_at")
})
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Conversation belongs to a business (for multi-tenant support)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Column(name = "instagram_conversation_id", unique = true)
    private String instagramConversationId;

    @Column(name = "instagram_scoped_user_id")
    private String instagramScopedUserId;

    // One conversation can have multiple leads over time
    @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL)
    private List<Lead> leads = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChannelType channel = ChannelType.INSTAGRAM;

    @Column(name = "customer_handle")
    private String customerHandle;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "profile_picture")
    private String profilePicture;

    @Column(name = "last_message")
    private String lastMessage;

    @Column(name = "unread_count")
    private Integer unreadCount = 0;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "started_at", updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Message> messages = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        startedAt = LocalDateTime.now();
        lastMessageAt = LocalDateTime.now();
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

    public List<Lead> getLeads() {
        return leads;
    }

    public void setLeads(List<Lead> leads) {
        this.leads = leads;
    }

    public String getInstagramConversationId() {
        return instagramConversationId;
    }

    public void setInstagramConversationId(String instagramConversationId) {
        this.instagramConversationId = instagramConversationId;
    }

    public String getInstagramScopedUserId() {
        return instagramScopedUserId;
    }

    public void setInstagramScopedUserId(String instagramScopedUserId) {
        this.instagramScopedUserId = instagramScopedUserId;
    }

    public ChannelType getChannel() {
        return channel;
    }

    public void setChannel(ChannelType channel) {
        this.channel = channel;
    }

    public String getCustomerHandle() {
        return customerHandle;
    }

    public void setCustomerHandle(String customerHandle) {
        this.customerHandle = customerHandle;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getProfilePicture() {
        return profilePicture;
    }

    public void setProfilePicture(String profilePicture) {
        this.profilePicture = profilePicture;
    }

    public String getLastMessage() {
        return lastMessage;
    }

    public void setLastMessage(String lastMessage) {
        this.lastMessage = lastMessage;
    }

    public Integer getUnreadCount() {
        return unreadCount;
    }

    public void setUnreadCount(Integer unreadCount) {
        this.unreadCount = unreadCount;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getLastMessageAt() {
        return lastMessageAt;
    }

    public void setLastMessageAt(LocalDateTime lastMessageAt) {
        this.lastMessageAt = lastMessageAt;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }
}
