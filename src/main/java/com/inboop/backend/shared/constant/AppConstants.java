package com.inboop.backend.shared.constant;

public class AppConstants {

    // Pagination
    public static final String DEFAULT_PAGE_NUMBER = "0";
    public static final String DEFAULT_PAGE_SIZE = "10";
    public static final String DEFAULT_SORT_BY = "createdAt";
    public static final String DEFAULT_SORT_DIRECTION = "desc";

    // Instagram API
    public static final String INSTAGRAM_GRAPH_API_URL = "https://graph.instagram.com";
    public static final String INSTAGRAM_API_VERSION = "v21.0";

    // Meta External URLs (for frontend actions)
    public static final String META_BUSINESS_SETTINGS_URL = "https://business.facebook.com/settings";
    public static final String META_BUSINESS_SUITE_URL = "https://business.facebook.com/latest/home";
    public static final String META_PAGE_CREATE_URL = "https://www.facebook.com/pages/create";

    // OAuth paths (relative to backend base URL)
    public static final String OAUTH_FACEBOOK_PATH = "/oauth2/authorization/facebook";

    // Webhook
    public static final String WEBHOOK_VERIFY_TOKEN = "inboop_webhook_verify_token";

    // Date Format
    public static final String DATE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";

    private AppConstants() {
        // Private constructor to prevent instantiation
    }
}
