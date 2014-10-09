package controllers;

import org.sagebionetworks.bridge.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.models.Study;
import org.sagebionetworks.bridge.models.User;
import org.sagebionetworks.bridge.services.BackfillService;

import play.mvc.Result;

public class BackfillController extends AdminController {

    private BackfillService backfillService;

    public void setBackfillService(BackfillService backfillService) {
        this.backfillService = backfillService;
    }
    
    public Result stormpathUserConsent() throws Exception {
        checkUser();
        int total = 0;
        for (Study study : studyService.getStudies()) {
            Study study = studyService.getStudyByHostname(getHostname());
            total += backfillService.stormpathUserConsent(study);
        }
        return okResult("Done. " + total + " accounts backfilled.");
    }

    private void checkUser() throws Exception {
        User user = getAuthenticatedAdminSession().getUser();
        if (!user.getRoles().contains("backfill")) {
            throw new UnauthorizedException();
        }
    }
}
