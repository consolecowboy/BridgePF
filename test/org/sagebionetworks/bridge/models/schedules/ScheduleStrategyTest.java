package org.sagebionetworks.bridge.models.schedules;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.sagebionetworks.bridge.TestConstants;
import org.sagebionetworks.bridge.dynamodb.DynamoSchedulePlan;
import org.sagebionetworks.bridge.json.DateUtils;
import org.sagebionetworks.bridge.json.JsonUtils;
import org.sagebionetworks.bridge.models.User;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

public class ScheduleStrategyTest {

    private ObjectMapper mapper = new ObjectMapper();
    private ScheduleContext context;
    
    @Before
    public void before() {
        ArrayList<User> users = Lists.newArrayList();
        for (int i=0; i < 10; i++) {
            users.add(new User(Integer.toString(i), "test"+i+"@sagebridge.org"));
        }
        this.context = new ScheduleContext(TestConstants.SECOND_STUDY, users);
    }
    
    public void createContextWithRemainderUsers() {
        ArrayList<User> users = Lists.newArrayList();
        for (int i=0; i < 14; i++) {
            users.add(new User(Integer.toString(i), "test"+i+"@sagebridge.org"));
        }
        this.context = new ScheduleContext(TestConstants.SECOND_STUDY, users);
    }
    
    @Test
    public void testSimpleScheduleStrategy() {
        Schedule schedule = createSchedule("AAA");
        SimpleScheduleStrategy strategy = new SimpleScheduleStrategy();
        strategy.setSchedule(schedule);
        
        List<Schedule> schedules = strategy.scheduleExistingUsers(context);
        
        Set<String> identifiers = Sets.newHashSet();
        for (Schedule sch : schedules) {
            identifiers.add(sch.getStudyUserCompoundKey());
        }
        
        assertEquals("There are ten schedules", 10, schedules.size());
        assertEquals("Each assigned to a different person", 10, identifiers.size());
    }
    
    @Test
    public void canRountripSimplePlan() throws Exception {
        Schedule schedule = createSchedule("AAA");
        SimpleScheduleStrategy strategy = new SimpleScheduleStrategy();
        strategy.setSchedule(schedule);
        
        DynamoSchedulePlan plan = new DynamoSchedulePlan();
        plan.setModifiedOn(DateUtils.getCurrentMillisFromEpoch());
        plan.setStudyKey(TestConstants.SECOND_STUDY.getKey());
        plan.setStrategy(strategy);
        
        String output = JsonUtils.toJSON(plan);
        JsonNode node = mapper.readTree(output);
        DynamoSchedulePlan newPlan = DynamoSchedulePlan.fromJson(node);
        
        assertEquals("Plan with simple strategy was serialized/deserialized", plan, newPlan);
        
        SimpleScheduleStrategy newStrategy = (SimpleScheduleStrategy)newPlan.getStrategy();
        assertEquals("Deserialized simple testing strategy is complete", strategy.getSchedule(), newStrategy.getSchedule());
    }

    @Test
    public void canRountripABTestingPlan() throws Exception {
        DynamoSchedulePlan plan = createABSchedulePlan();
        String output = JsonUtils.toJSON(plan);
        JsonNode node = mapper.readTree(output);
        DynamoSchedulePlan newPlan = DynamoSchedulePlan.fromJson(node);
        
        assertEquals("Plan with AB testing strategy was serialized/deserialized", plan, newPlan);
        
        ABTestScheduleStrategy strategy = (ABTestScheduleStrategy)plan.getStrategy();
        ABTestScheduleStrategy newStrategy = (ABTestScheduleStrategy)newPlan.getStrategy();
        assertEquals("Deserialized AB testing strategy is complete", strategy.getScheduleGroups().get(0).getSchedule(),
                newStrategy.getScheduleGroups().get(0).getSchedule());
    }
    
    @Test
    public void verifyABTestingStrategyWorks() {
        DynamoSchedulePlan plan = createABSchedulePlan();

        List<Schedule> schedules = plan.getStrategy().scheduleExistingUsers(context);
        
        // We want 4 in A, 4 in B and 2 in C
        // and they should not be in order...
        Map<String,Integer> countsByLabel = Maps.newHashMap();
        for (Schedule schedule : schedules) {
            Integer count = countsByLabel.get(schedule.getLabel());
            if (count == null) {
                countsByLabel.put(schedule.getLabel(), 1);
            } else {
                countsByLabel.put(schedule.getLabel(), ++count);
            }
        }
        assertEquals("Four users assigned to A", 4, countsByLabel.get("A").intValue());
        assertEquals("Four users assigned to B", 4, countsByLabel.get("B").intValue());
        assertEquals("Four users assigned to C", 2, countsByLabel.get("C").intValue());

        // This fails, not all that rarely, due to actual randomness.
        /*
        List<Schedule> newSchedules = plan.getStrategy().generateSchedules(context);
        assertNotEquals("A has random users", schedules.get(0), newSchedules.get(0));
        assertNotEquals("B has random users", schedules.get(1), newSchedules.get(1));
        assertNotEquals("C has random users", schedules.get(2), newSchedules.get(2));
        */
    }
    
    @Test
    public void abScheduleStrategyAssignsRemainders() {
        DynamoSchedulePlan plan = createABSchedulePlan();
        createContextWithRemainderUsers();
        
        List<Schedule> schedules = plan.getStrategy().scheduleExistingUsers(context);
        assertEquals("All users have schedules", 14, schedules.size());
    }

    private DynamoSchedulePlan createABSchedulePlan() {
        DynamoSchedulePlan plan = new DynamoSchedulePlan();
        plan.setModifiedOn(DateUtils.getCurrentMillisFromEpoch());
        plan.setStudyKey(TestConstants.SECOND_STUDY.getKey());
        plan.setStrategy(createABTestStrategy());
        return plan;
    }

    private ABTestScheduleStrategy createABTestStrategy() {
        ABTestScheduleStrategy strategy = new ABTestScheduleStrategy();
        strategy.addGroup(40, createSchedule("A"));
        strategy.addGroup(40, createSchedule("B"));
        strategy.addGroup(20, createSchedule("C"));
        return strategy;
    }
    
    private Schedule createSchedule(String label) {
        Schedule schedule = new Schedule();
        schedule.setActivityType(Schedule.ActivityType.SURVEY);
        schedule.setLabel(label);
        schedule.setActivityRef("http://sagebridge.org/survey1");
        schedule.setScheduleType(Schedule.Type.CRON);
        schedule.setSchedule("0 */2 * * *");
        return schedule;
    }

}
