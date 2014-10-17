package org.sagebionetworks.bridge.services;

import static org.junit.Assert.assertEquals;


import java.util.List;

import javax.annotation.Resource;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.TestConstants.TestUser;
import org.sagebionetworks.bridge.TestUserAdminHelper;
import org.sagebionetworks.bridge.dynamodb.DynamoInitializer;
import org.sagebionetworks.bridge.dynamodb.DynamoSchedule;
import org.sagebionetworks.bridge.dynamodb.DynamoScheduleDao;
import org.sagebionetworks.bridge.dynamodb.DynamoSchedulePlan;
import org.sagebionetworks.bridge.dynamodb.DynamoSchedulePlanDao;
import org.sagebionetworks.bridge.dynamodb.DynamoTestUtil;
import org.sagebionetworks.bridge.events.SchedulePlanCreatedEvent;
import org.sagebionetworks.bridge.events.SchedulePlanDeletedEvent;
import org.sagebionetworks.bridge.events.SchedulePlanUpdatedEvent;
import org.sagebionetworks.bridge.events.UserEnrolledEvent;
import org.sagebionetworks.bridge.events.UserUnenrolledEvent;
import org.sagebionetworks.bridge.json.DateUtils;
import org.sagebionetworks.bridge.models.Study;
import org.sagebionetworks.bridge.models.User;
import org.sagebionetworks.bridge.models.UserSession;
import org.sagebionetworks.bridge.models.schedules.ActivityType;
import org.sagebionetworks.bridge.models.schedules.Schedule;
import org.sagebionetworks.bridge.models.schedules.SchedulePlan;
import org.sagebionetworks.bridge.models.schedules.ScheduleType;
import org.sagebionetworks.bridge.models.schedules.SimpleScheduleStrategy;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

@ContextConfiguration("classpath:test-context.xml")
@RunWith(SpringJUnit4ClassRunner.class)
public class ScheduleChangeListenerTest {
    
    @Resource
    private TestUserAdminHelper helper;
    
    @Resource
    private ScheduleChangeListener listener;
    
    @Resource
    private DynamoScheduleDao scheduleDao;
    
    @Resource
    private DynamoSchedulePlanDao schedulePlanDao;
    
    private Study study;
    
    @Before
    public void before() {
        DynamoInitializer.init(DynamoSchedule.class, DynamoSchedulePlan.class);
        DynamoTestUtil.clearTable(DynamoSchedule.class);
        DynamoTestUtil.clearTable(DynamoSchedulePlan.class);
        
        study = helper.getTestStudy();
    }
    
    @Test
    public void addPlanThenEnrollUnenrollUser() throws Exception {
        UserSession session = null;
        try {
            session = helper.createUser(new TestUser("enrollme", "enrollme@sagebridge.org", "P4ssword"), null, study, true, false);
            User user = session.getUser();
            
            SchedulePlan plan = createSchedulePlan(user);
            schedulePlanDao.createSchedulePlan(plan);
            
            List<Schedule> schedules = scheduleDao.getSchedules(study, user);
            assertEquals("No schedules because the user hasn't joined the study", 0, schedules.size());
            
            listener.onTestEvent(new UserEnrolledEvent(user, study));
            
            schedules = scheduleDao.getSchedules(study, user);
            assertEquals("User joined study and has a schedule", 1, schedules.size());
            
            listener.onTestEvent(new UserUnenrolledEvent(user, study));
            
            schedules = scheduleDao.getSchedules(study, user);
            assertEquals("User left study and has no schedules", 0, schedules.size());
        } finally {
            helper.deleteUser(session);
        }
    }
    
    @Test
    public void addUserThenCrudPlan() throws Exception {
        UserSession session = null;
        try {
            session = helper.createUser();
            
            List<Schedule> schedules = scheduleDao.getSchedules(study, session.getUser());
            assertEquals("No schedules because there's no plan", 0, schedules.size());

            SchedulePlan plan = createSchedulePlan(session.getUser());
            listener.onTestEvent(new SchedulePlanCreatedEvent(plan));

            schedules = scheduleDao.getSchedules(study, session.getUser());
            assertEquals("There is now one schedule for the user", 1, schedules.size());

            updateSchedulePlan(plan);
            listener.onTestEvent(new SchedulePlanUpdatedEvent(plan));

            schedules = scheduleDao.getSchedules(study, session.getUser());
            assertEquals("There is still one schedule for the user", 1, schedules.size());
            assertEquals("That schedule shows an update", "* * * * * *", schedules.get(0).getCronTrigger());

            listener.onTestEvent(new SchedulePlanDeletedEvent(plan));
            schedules = scheduleDao.getSchedules(study, session.getUser());
            assertEquals("Now there is no schedule after the one plan was deleted", 0, schedules.size());
            
        } finally {
            helper.deleteUser(session);
        }
    }
    
    private void updateSchedulePlan(SchedulePlan plan) {
        SimpleScheduleStrategy strategy = (SimpleScheduleStrategy)plan.getStrategy();
        Schedule schedule = strategy.getSchedule();
        schedule.setCronTrigger("* * * * * *");
        plan.setModifiedOn(DateUtils.getCurrentMillisFromEpoch());
    }
    
    private SchedulePlan createSchedulePlan(User user) {
        String planGuid = BridgeUtils.generateGuid();
        
        Schedule schedule = new DynamoSchedule();
        schedule.setStudyAndUser(study, user);
        schedule.setSchedulePlanGuid(planGuid);
        schedule.setLabel("Task AAA");
        schedule.setActivityType(ActivityType.TASK);
        schedule.setActivityRef("task:AAA");
        schedule.setScheduleType(ScheduleType.RECURRING);
        schedule.setCronTrigger("0 0 6 ? * MON-FRI *");
        
        long oneDay = (24 * 60 * 60 * 1000); 
        schedule.setExpires(DateUtils.getCurrentMillisFromEpoch() + oneDay);
        
        SimpleScheduleStrategy strategy = new SimpleScheduleStrategy();
        strategy.setSchedule(schedule);
        
        SchedulePlan plan = new DynamoSchedulePlan();
        plan.setGuid(planGuid);
        plan.setModifiedOn(DateUtils.getCurrentMillisFromEpoch());
        plan.setStrategy(strategy);
        plan.setStudyKey(study.getKey());
        return plan;
    }

}
