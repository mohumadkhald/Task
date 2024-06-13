package org.tasks.task.calendar.controller;


import org.tasks.task.calendar.events.EventRepository;
import org.tasks.task.calendar.events.EventService;
import org.tasks.task.calendar.model.EventObj;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;


@Controller
public class DashboardController {

    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private EventService eventService;

    @GetMapping("/user/email")
    public String getCurrentUserEmail() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof OAuth2User) {
            OAuth2User user = (OAuth2User) authentication.getPrincipal();
            return user.getAttribute("email");
        }
        return "No authenticated user";
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        List<EventObj> events = eventRepository.findAll();
        model.addAttribute("events", events);
        return "dashboard";
    }

    @PostMapping("/delete")
    public String deleteEvent(@RequestParam("id") Long Id) {
        eventService.deleteEvent(Id);
        return "redirect:/";
    }

    @GetMapping("/create-event-local")
    public String create(Model model) {
        return "create-event-local";
    }


    @PostMapping("/create-event-local")
    public String createEvent(@RequestParam("title") String title,
                              @RequestParam("startDateTime") @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") Date startDateTime,
                              @RequestParam("endDateTime") @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") Date endDateTime,
                              @RequestParam("email") String email) {
        EventObj event = new EventObj();
        event.setTitle(title);
        event.setStartDateTime(startDateTime);
        event.setEndDateTime(endDateTime);
        event.setEmail(email);
        eventService.saveEvent(event);
        return "redirect:/";
    }
}