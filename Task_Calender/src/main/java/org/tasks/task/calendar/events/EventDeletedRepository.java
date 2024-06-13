package org.tasks.task.calendar.events;

import org.tasks.task.calendar.model.EventsDeleted;
import org.springframework.data.jpa.repository.JpaRepository;


public interface EventDeletedRepository extends JpaRepository<EventsDeleted, Long> {

    boolean existsByEventId(String id);
}