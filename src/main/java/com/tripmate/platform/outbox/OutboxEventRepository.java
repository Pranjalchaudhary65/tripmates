package com.tripmate.platform.outbox;

import org.springframework.data.repository.Repository;

import java.util.UUID;

/** Insert only: nothing that publishes an event may change or delete one. */
interface OutboxEventRepository extends Repository<OutboxEvent, UUID> {

    OutboxEvent save(OutboxEvent event);
}
