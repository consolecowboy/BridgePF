package org.sagebionetworks.bridge.models.schedules;

import org.sagebionetworks.bridge.validators.ScheduleValidator;
import org.sagebionetworks.bridge.validators.Validate;

public class SchedulerFactory {

    public static TaskScheduler getScheduler(String schedulePlanGuid, Schedule schedule) {
        // TODO: This validation will be moved to create/update, not the time of use.
        Validate.entityThrowingException(new ScheduleValidator(), schedule);
        
        if (schedule.getCronTrigger() != null) {
            return new CronTaskScheduler(schedulePlanGuid, schedule);
        }
        // This has to handle one-time event-based tasks as well (that don't need an interval).
        return new IntervalTaskScheduler(schedulePlanGuid, schedule);
    };
    
}
