package uz.tracker.trackerproject.dto.response;

import lombok.Builder;
import lombok.Getter;
import uz.tracker.trackerproject.entity.Settings;

/**
 * Public, non-secret subset of {@link Settings} consumed by the Telegram bot at startup.
 * Exposed at {@code GET /api/v1/settings/telegram} (permitAll) so the bot can read its
 * webhook + web-view URLs before any user session exists. Neither value is a secret: the
 * webhook is additionally protected by Telegram's per-update secret token, and the web-view
 * URL is just the public frontend address.
 */
@Getter @Builder
public class TelegramConfigResponse {

    private String webhookUrl;
    private String webViewUrl;

    public static TelegramConfigResponse from(Settings s) {
        return TelegramConfigResponse.builder()
                .webhookUrl(s.getTelegramWebhookUrl())
                .webViewUrl(s.getTelegramWebViewUrl())
                .build();
    }
}
