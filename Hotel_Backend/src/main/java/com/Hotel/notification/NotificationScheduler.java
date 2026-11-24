package com.Hotel.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the dispatcher periodically. Disable with HOTEL_NOTIFICATIONS_ENABLED=false. */
@Component
@ConditionalOnProperty(name = "hotel.notifications.dispatch-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationScheduler {
    private final NotificationDispatcher dispatcher;

    public NotificationScheduler(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${hotel.notifications.delay-ms:30000}",
               initialDelayString = "${hotel.notifications.delay-ms:30000}")
    public void run() {
        dispatcher.dispatchOnce();
    }
}
