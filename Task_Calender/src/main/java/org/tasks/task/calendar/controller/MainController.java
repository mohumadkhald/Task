package org.tasks.task.calendar.controller;

import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.calendar.model.EventDateTime;
import org.tasks.task.calendar.events.EventDeletedRepository;
import org.tasks.task.calendar.events.EventService;
import org.tasks.task.calendar.model.CalendarObj;
import org.tasks.task.calendar.model.EventObj;
import org.tasks.task.calendar.model.EventsDeleted;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import com.google.api.client.auth.oauth2.AuthorizationCodeRequestUrl;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets.Details;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.Calendar.Events;
import com.google.api.services.calendar.model.Event;

@Controller
public class MainController {

	private static final String APPLICATION_NAME = "task";

	private static HttpTransport httpTransport;
	private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
	private static com.google.api.services.calendar.Calendar client;
	public String email;

	@Autowired
	private EventDeletedRepository eventDeletedRepository;
	@Autowired
	private EventService eventService;

	private static final SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a");

	GoogleClientSecrets clientSecrets;
	GoogleAuthorizationCodeFlow flow;
	Credential credential;

	@Value("${google.client.client-id}")
	private String clientId;
	@Value("${google.client.client-secret}")
	private String clientSecret;
	@Value("${google.client.redirectUri}")
	private String redirectURI;


	private static boolean isAuthorised = false;

	private String authorize(String redirectURL) throws Exception {
		AuthorizationCodeRequestUrl authorizationUrl;
		if (flow == null) {
			Details web = new Details();
			web.setClientId(clientId);
			web.setClientSecret(clientSecret);
			clientSecrets = new GoogleClientSecrets().setWeb(web);
			httpTransport = GoogleNetHttpTransport.newTrustedTransport();
			flow = new GoogleAuthorizationCodeFlow.Builder(httpTransport, JSON_FACTORY, clientSecrets,
					Collections.singleton(CalendarScopes.CALENDAR)).build();
		}
		authorizationUrl = flow.newAuthorizationUrl().setRedirectUri(redirectURL);

		isAuthorised = true;

		return authorizationUrl.build();
	}

	@RequestMapping(value = "/calendar", method = RequestMethod.GET)
	public RedirectView googleConnectionStatus(HttpServletRequest request) throws Exception {
		return new RedirectView(authorize(redirectURI));
	}



	@RequestMapping(value = "/calendar", method = RequestMethod.GET, params = "code")
	public String oauth2Callback(@RequestParam(value = "code") String code, Model model) {
		if (isAuthorised) {
			try {
				model.addAttribute("title", "Today's Calendar Events");
				model.addAttribute("calendarObjs", getTodaysCalendarEventList(code, redirectURI));
				handlePostLoginActions();
			} catch (Exception e) {
				model.addAttribute("calendarObjs", new ArrayList<CalendarObj>());
			}

			return "agenda";
		} else {
			return "/";
		}
	}





	@RequestMapping(value = "/error", method = RequestMethod.GET)
	public String accessDenied(Model model) {
		model.addAttribute("message", "Not authorised.");
		return "login";
	}

	@RequestMapping(value = {"/login", "/logout"}, method = RequestMethod.GET)
	public String login(Model model) {
		isAuthorised = false;
		return "login";
	}

	private void handlePostLoginActions() {
		deleteAllEventsFromCalendar();
		syncDeletedEventsWithGoogle();
		syncEventsWithGoogleCalendar();
	}

	private void deleteAllEventsFromCalendar() {
		try {
			if (client == null || credential == null) {
				return;
			}
			List<EventsDeleted> deletedEvents = eventDeletedRepository.findAll();
			for (EventsDeleted deletedEvent : deletedEvents) {
				try {
					client.events().delete("primary", deletedEvent.getEventId()).execute();
				} catch (GoogleJsonResponseException e) {
					if (e.getStatusCode() == 410) {
						System.out.println("Event " + deletedEvent.getEventId() + " has already been deleted.");
					} else {
						throw e;
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private List<CalendarObj> getTodaysCalendarEventList(String calendarApiCode, String redirectURL) {
		try {
			LocalDateTime localDateTime = LocalDateTime.ofInstant(new Date().toInstant(), ZoneId.systemDefault());
			LocalDateTime startOfDay = localDateTime.with(LocalTime.MIN);
			LocalDateTime endOfDay = localDateTime.with(LocalTime.MAX);

			DateTime date1 = new DateTime(Date.from(startOfDay.atZone(ZoneId.systemDefault()).toInstant()));
			DateTime date2 = new DateTime(Date.from(endOfDay.atZone(ZoneId.systemDefault()).toInstant()));

			if (calendarApiCode != null) {
				TokenResponse response = flow.newTokenRequest(calendarApiCode).setRedirectUri(redirectURL).execute();
				credential = flow.createAndStoreCredential(response, "userID");
			}

			httpTransport = GoogleNetHttpTransport.newTrustedTransport();
			client = new com.google.api.services.calendar.Calendar.Builder(httpTransport, JSON_FACTORY, credential)
					.setApplicationName(APPLICATION_NAME).build();
			Events events = client.events();
			com.google.api.services.calendar.model.Events eventList = events.list("primary").setSingleEvents(true).setTimeMin(date1).setTimeMax(date2)
					.setOrderBy("startTime").execute();

			List<Event> items = eventList.getItems();
			List<CalendarObj> calendarObjs = new ArrayList<>();

			for (Event event : items) {
				boolean isDeleted = eventDeletedRepository.existsByEventId(event.getId());
				if (!isDeleted) {
					Date startDateTime = new Date(event.getStart().getDateTime().getValue());
					Date endDateTime = new Date(event.getEnd().getDateTime().getValue());

					long diffInMillies = endDateTime.getTime() - startDateTime.getTime();
					int diffmin = (int) (diffInMillies / (60 * 1000));

					CalendarObj calendarObj = new CalendarObj();
					calendarObj.setTitle(event.getSummary() != null ? event.getSummary() : "No Title");
					calendarObj.setStartHour(startDateTime.getHours());
					calendarObj.setStartMin(startDateTime.getMinutes());
					calendarObj.setEndHour(endDateTime.getHours());
					calendarObj.setEndMin(endDateTime.getMinutes());
					calendarObj.setDuration(diffmin);
					calendarObj.setEmail(event.getCreator().getEmail());
					calendarObj.setEventId(event.getId());
					calendarObj.setStartEnd(sdf.format(startDateTime) + " - " + sdf.format(endDateTime));
					if (event.getCreator().getEmail() == null) {
						email = "Email@test.com";
					} else {
						email = event.getOrganizer().getEmail();
					}
					Optional<EventObj> existingEvent = eventService.findByEventId(event.getId());
					if (!existingEvent.isPresent()) {
						EventObj eventEntity = new EventObj();
						eventEntity.setTitle(calendarObj.getTitle());
						eventEntity.setStartDateTime(startDateTime);
						eventEntity.setEndDateTime(endDateTime);
						eventEntity.setDuration(diffmin);
						eventEntity.setEventId(event.getId());
						eventEntity.setEmail(event.getCreator().getEmail());
						eventService.syncEvents(Set.of(eventEntity));
					}

					calendarObjs.add(calendarObj);
				}
			}

			return calendarObjs;

		} catch (Exception e) {
			e.printStackTrace();
			return new ArrayList<>();
		}
	}

	@GetMapping("/create")
	public String showCreateEventForm(Model model) {
		return "create-event";
	}

	@PostMapping("/create-event")
	public  String createEvent(@RequestParam String title,
											@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
											@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
		try {
			if (client == null || credential == null) {
				return "Error: Google Calendar client or credential not initialized";
			}

			Event newEvent = new Event();
			newEvent.setSummary(title);
			DateTime startDate = new DateTime(Date.from(start.atZone(ZoneId.systemDefault()).toInstant()));
			newEvent.setStart(new EventDateTime().setDateTime(startDate));

			DateTime endDate = new DateTime(Date.from(end.atZone(ZoneId.systemDefault()).toInstant()));
			newEvent.setEnd(new EventDateTime().setDateTime(endDate));

			Event createdEvent = client.events().insert("primary", newEvent).execute();

			// Mark the event for synchronization in your database
			EventObj eventEntity = new EventObj();
			eventEntity.setTitle(title);
			eventEntity.setStartDateTime(Date.from(start.atZone(ZoneId.systemDefault()).toInstant()));
			eventEntity.setEndDateTime(Date.from(end.atZone(ZoneId.systemDefault()).toInstant()));
			eventEntity.setEventId(createdEvent.getId()); // Mark event with sync flag
			eventEntity.setEmail(createdEvent.getCreator().getEmail());
			eventService.save(eventEntity); // Save event to your database

			return "redirect:/";
		} catch (GoogleJsonResponseException e) {
			if (e.getStatusCode() == 410) {
				return "Error: Event resource has been deleted.";
			} else {
				e.printStackTrace();
				return "Error occurred while creating the event: " + e.getMessage();
			}
		} catch (Exception e) {
			e.printStackTrace();
			return "Error occurred while creating the event: " + e.getMessage();
		}
	}


	@RequestMapping(value = "/delete-event", method = RequestMethod.POST)
	public String deleteEvent(@RequestParam String eventId) {
		try {
			if (client == null || credential == null) {
				return "Error: Google Calendar client or credential not initialized";
			}

			client.events().delete("primary", eventId).execute();
			EventObj eventObj = eventService.findByEventId(eventId).get();
			eventService.deleteEvent(eventObj.getId());

			return "redirect:/";
		} catch (Exception e) {
			e.printStackTrace();
			return "Error occurred while deleting the event: " + e.getMessage();
		}
	}

	private void syncEventsWithGoogleCalendar() {
		try {
			// Fetch events from the database with eventId as null or empty
			List<EventObj> eventsToSync = eventService.findEventsByEventIdNullOrEmpty();

			// Retrieve the current user's email (you might have a method to get the current user)
			String currentUserEmail = getCurrentUserEmail();

			// Filter events for the current user
			List<EventObj> userEventsToSync = eventsToSync.stream()
					.filter(event -> currentUserEmail.equals(event.getEmail()))
					.collect(Collectors.toList());

			for (EventObj event : userEventsToSync) {
				Event googleEvent = new Event()
						.setSummary(event.getTitle())
						.setDescription("Event added from application");

				DateTime startDateTime = new DateTime(event.getStartDateTime());
				EventDateTime start = new EventDateTime()
						.setDateTime(startDateTime);
				googleEvent.setStart(start);

				DateTime endDateTime = new DateTime(event.getEndDateTime());
				EventDateTime end = new EventDateTime()
						.setDateTime(endDateTime);
				googleEvent.setEnd(end);

				// Insert event into Google Calendar
				Event createdEvent = client.events().insert("primary", googleEvent).execute();

				// Update the eventId in the database for the synced event
				event.setEventId(createdEvent.getId());
				eventService.save(event); // Update eventId in your database
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	// Assuming you have a method to get the current user's email
	private String getCurrentUserEmail() {
		return email;
	}

	// delete all event from google when login after deleted it local
	private void syncDeletedEventsWithGoogle() {
		eventService.syncDeletedEvents();
	}

}