package org.tasks.task.calendar.events;

import org.tasks.task.calendar.model.EventObj;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface EventRepository extends JpaRepository<EventObj, Long> {
    Optional<EventObj> findByEventId(String eventId);

    void deleteByEventId(String id);

    List<EventObj> findByEventIdNot(String id);


    @Query("SELECT e.eventId FROM EventObj e")
    Set<String> findAllEventIds();

    @Modifying
    @Query("DELETE FROM EventObj e WHERE e.eventId NOT IN :eventIds")
    void deleteByEventIdsNotIn(@Param("eventIds") Set<String> eventIds);


    @Query("SELECT e FROM EventObj e WHERE e.eventId IS NULL OR e.eventId = ''")
    List<EventObj> findByEventIdNullOrEmpty();


}