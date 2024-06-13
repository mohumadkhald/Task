package org.tasks.task.calendar.model;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class CalendarObj {
  
	   private String title;
	   private int startHour;
	   private int startMin;
	   private int endHour;
	   private int endMin;
	   private int duration;
	   private String eventId;
	   private String startEnd;
	   private String email;
}
