package org.tasks.task.calendar.events;

import lombok.AllArgsConstructor;
import org.tasks.task.calendar.model.EventObj;
import org.tasks.task.calendar.model.EventsDeleted;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.List;
import java.util.Optional;
import java.util.Set;


@Service
@Transactional
@AllArgsConstructor
public class EventService {

    private EventRepository eventRepository;
    private EventDeletedRepository eventDeletedRepository;


    public void syncEvents(Set<EventObj> events) {
        eventRepository.saveAll(events);
    }

    public void syncDeletedEvents() {
        eventDeletedRepository.deleteAll();
    }

    public void deleteEvent(Long id) {
        EventObj event = eventRepository.findById(id).get();
        eventRepository.deleteById(id);
        EventsDeleted eventDeleted = new EventsDeleted();
        eventDeleted.setEventId(event.getEventId());
        eventDeletedRepository.save(eventDeleted);
    }
    public Optional<EventObj> findByEventId(String eventId) {
        return eventRepository.findByEventId(eventId);
    }

    public void saveEvent(EventObj event) {
        eventRepository.save(event);
    }



    public void save(EventObj event) {
        eventRepository.save(event);
    }

    public List<EventObj> findEventsByEventIdNullOrEmpty() {
        return eventRepository.findByEventIdNullOrEmpty();
    }
}