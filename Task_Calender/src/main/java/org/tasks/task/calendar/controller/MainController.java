package org.tasks.task.calendar.controller;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.calendar.model.CalendarList;
import com.google.api.services.calendar.model.CalendarListEntry;
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
	private static final String APPLICATION_NAME = "My First Project";
	private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
	private static com.google.api.services.calendar.Calendar client;
	@Autowired
	private EventDeletedRepository eventDeletedRepository;
	private static final SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a");
	private GoogleClientSecrets clientSecrets;
	private GoogleAuthorizationCodeFlow flow;
	private Credential credential;
	private String email;
	@Value("${google.client.client-id}")
	private String clientId;
	@Value("${google.client.client-secret}")
	private String clientSecret;
	@Value("${google.client.redirectUri}")
	private String redirectURI;
	private static boolean isAuthorised = false;
	@Autowired
	private EventService eventService;
    @RequestMapping(value = { "/login" }, method = RequestMethod.GET)
	public String login(Model model) throws IOException {
		isAuthorised = false;
		clearCredentials();
		return "login";
	}
	@RequestMapping(value = { "/logout" }, method = RequestMethod.GET)
	public String logout(HttpServletRequest request, HttpServletResponse response) throws IOException {
		request.getSession().invalidate();
		isAuthorised = false;
		return "login";
	}
	private void clearCredentials() {
		if (credential != null) {
			credential = null;
		}
		if (client != null) {
			client = null;
		}
		flow = null;  // Clear the flow to ensure it's re-initialized on the next authorization request.
	}
	private String authorize(String redirectURL) throws Exception {
		if (flow == null) {
			GoogleClientSecrets.Details web = new GoogleClientSecrets.Details();
			web.setClientId(clientId);
			web.setClientSecret(clientSecret);
			clientSecrets = new GoogleClientSecrets().setWeb(web);
			HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
			flow = new GoogleAuthorizationCodeFlow.Builder(httpTransport, JSON_FACTORY, clientSecrets,
					Collections.singleton(CalendarScopes.CALENDAR)).setAccessType("offline").build();
		}
		AuthorizationCodeRequestUrl authorizationUrl = flow.newAuthorizationUrl().setRedirectUri(redirectURL);
		isAuthorised = true;
		return authorizationUrl.build();
	}
	@RequestMapping(value = "/calendar", method = RequestMethod.GET)
	public RedirectView googleConnectionStatus(HttpServletRequest request) throws Exception {
		return new RedirectView(authorize(redirectURI));
	}
	@RequestMapping(value = "/error", method = RequestMethod.GET)
	public String accessDenied(Model model) {
		model.addAttribute("message", "Not authorised.");
		return "login";
	}
	private void ensureClientInitialization(String code, String redirectURL) throws Exception {
		if (flow == null) {
			GoogleClientSecrets.Details web = new GoogleClientSecrets.Details();
			web.setClientId(clientId);
			web.setClientSecret(clientSecret);
			clientSecrets = new GoogleClientSecrets().setWeb(web);
			HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
			flow = new GoogleAuthorizationCodeFlow.Builder(httpTransport, JSON_FACTORY, clientSecrets,
					Collections.singleton(CalendarScopes.CALENDAR)).setAccessType("offline").build();
		}

		if (credential == null || client == null) {
			if (code != null) {
				try {
					TokenResponse response = flow.newTokenRequest(code).setRedirectUri(redirectURL).execute();
					credential = flow.createAndStoreCredential(response, "userID");
				} catch (TokenResponseException e) {
					System.err.println("Error during token exchange: " + e.getDetails().getError());
					System.err.println("Error description: " + e.getDetails().getErrorDescription());
					throw e;
				}
			}

			if (credential != null) {
				HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
				client = new com.google.api.services.calendar.Calendar.Builder(httpTransport, JSON_FACTORY, credential)
						.setApplicationName(APPLICATION_NAME).build();
			} else {
				throw new Exception("Failed to initialize Google Calendar client and credentials.");
			}
		}
	}
	@RequestMapping(value = "/calendar", method = RequestMethod.GET, params = "code")
	public String oauth2Callback(@RequestParam(value = "code") String code, Model model) {
		try {
			// Initialize the client with the code
			ensureClientInitialization(code, redirectURI);
			sync();
			// Display events for the current date
			return displayEventsForDate(LocalDate.now().toString(), code, model);
		} catch (Exception e) {
			model.addAttribute("calendarObjs", new ArrayList<>());
			e.printStackTrace();
			return "agenda";
		}
	}
	@RequestMapping(value = "/calendar", method = RequestMethod.GET, params = { "date" })
	public String displayEventsForDate(@RequestParam(value = "date") String dateStr, @RequestParam(value = "code", required = false) String code, Model model) {
		try {
			if (dateStr == null || dateStr.isEmpty()) {
				dateStr = LocalDate.now().toString();
			}
			LocalDate date = LocalDate.parse(dateStr);
			if (code != null && !code.isEmpty()) {
				ensureClientInitialization(code, redirectURI);
			}
			model.addAttribute("title", "Calendar Events for " + date);
			model.addAttribute("calendarObjs", CalendarEventList(code, redirectURI, date));
		} catch (Exception e) {
			model.addAttribute("calendarObjs", new ArrayList<>());
			e.printStackTrace();
		}
		return "agenda";
	}
	private List<CalendarObj> CalendarEventList(String calendarApiCode, String redirectURL, LocalDate date) {
		try {
			LocalDateTime startOfDay = date.atStartOfDay();
			LocalDateTime endOfDay = date.atTime(LocalTime.MAX);

			DateTime date1 = new DateTime(Date.from(startOfDay.atZone(ZoneId.systemDefault()).toInstant()));
			DateTime date2 = new DateTime(Date.from(endOfDay.atZone(ZoneId.systemDefault()).toInstant()));

			HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
			client = new com.google.api.services.calendar.Calendar.Builder(httpTransport, JSON_FACTORY, credential)
					.setApplicationName(APPLICATION_NAME).build();
			Events events = client.events();
			com.google.api.services.calendar.model.Events eventList = events.list("primary")
					.setSingleEvents(true)
					.setTimeMin(date1)
					.setTimeMax(date2)
					.setOrderBy("startTime")
					.execute();

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

		} catch (TokenResponseException e) {
			System.err.println("Error during token exchange: " + e.getDetails().getError());
			System.err.println("Error description: " + e.getDetails().getErrorDescription());
			e.printStackTrace();
			return new ArrayList<>();
		} catch (Exception e) {
			e.printStackTrace();
			return new ArrayList<>();
		}
	}
	public void sync() {
		deleteAllEventsFromCalendar();
		syncDeletedEventsWithGoogle();
		syncEventsWithGoogleCalendar();
	}
	@GetMapping("/create")
	public String showCreateEventForm(Model model) {
		return "create-event";
	}
	@PostMapping("/create-event")
	public  @ResponseBody String createEvent(@RequestParam String title,
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

			return "event have been created " + createdEvent.getId();
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
	public @ResponseBody String deleteEvent(@RequestParam String eventId) {
		try {
			if (client == null || credential == null) {
				return "Error: Google Calendar client or credential not initialized";
			}

			client.events().delete("primary", eventId).execute();
			EventObj eventObj = eventService.findByEventId(eventId).get();
			eventService.deleteEvent(eventObj.getId());

			return "the event has  been deleted";
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
				// Ensure start and end times are valid
				if (event.getStartDateTime().after(event.getEndDateTime())) {
					System.out.println("Invalid time range for event: " + event.getTitle());
					continue;
				}

				Event googleEvent = new Event()
						.setSummary(event.getTitle())
						.setDescription("Event added from application");

				// Convert java.sql.Timestamp or java.time.LocalDateTime to com.google.api.client.util.DateTime
				DateTime startDateTime = convertToGoogleDateTime(event.getStartDateTime());
				EventDateTime start = new EventDateTime()
						.setDateTime(startDateTime)
						.setTimeZone("UTC"); // Set the time zone explicitly
				googleEvent.setStart(start);

				DateTime endDateTime = convertToGoogleDateTime(event.getEndDateTime());
				EventDateTime end = new EventDateTime()
						.setDateTime(endDateTime)
						.setTimeZone("UTC"); // Set the time zone explicitly
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
	private DateTime convertToGoogleDateTime(Object dateTimeObject) {
		if (dateTimeObject instanceof java.sql.Timestamp) {
			java.sql.Timestamp timestamp = (java.sql.Timestamp) dateTimeObject;
			return new DateTime(timestamp.getTime());
		} else if (dateTimeObject instanceof java.time.LocalDateTime) {
			java.time.LocalDateTime localDateTime = (java.time.LocalDateTime) dateTimeObject;
			java.time.ZonedDateTime zonedDateTime = localDateTime.atZone(ZoneId.systemDefault());
			return new DateTime(Date.from(zonedDateTime.toInstant()));
		} else {
			throw new IllegalArgumentException("Unsupported date time type: " + dateTimeObject.getClass().getName());
		}
	}
	private void syncDeletedEventsWithGoogle() {
		eventService.syncDeletedEvents();
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
	private String getCurrentUserEmail() throws IOException {
		// Retrieve calendar list
		CalendarList calendarList = client.calendarList().list().execute();
		for (CalendarListEntry calendarListEntry : calendarList.getItems()) {
			if (calendarListEntry.getPrimary() != null && calendarListEntry.getPrimary()) {
				email = calendarListEntry.getSummary(); // Assuming summary is the email
				break;
			}
		}
		return email;
	}
}