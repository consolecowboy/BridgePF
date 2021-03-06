package org.sagebionetworks.bridge.services;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sagebionetworks.bridge.BridgeConstants.NO_CALLER_ROLES;
import static org.sagebionetworks.bridge.dao.ParticipantOption.LANGUAGES;

import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.Roles;
import org.sagebionetworks.bridge.cache.CacheProvider;
import org.sagebionetworks.bridge.config.BridgeConfig;
import org.sagebionetworks.bridge.dao.AccountDao;
import org.sagebionetworks.bridge.exceptions.AccountDisabledException;
import org.sagebionetworks.bridge.exceptions.AuthenticationFailedException;
import org.sagebionetworks.bridge.exceptions.BridgeServiceException;
import org.sagebionetworks.bridge.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.exceptions.EntityAlreadyExistsException;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.exceptions.LimitExceededException;
import org.sagebionetworks.bridge.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.models.CriteriaContext;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.accounts.AccountStatus;
import org.sagebionetworks.bridge.models.accounts.Email;
import org.sagebionetworks.bridge.models.accounts.EmailVerification;
import org.sagebionetworks.bridge.models.accounts.IdentifierHolder;
import org.sagebionetworks.bridge.models.accounts.PasswordReset;
import org.sagebionetworks.bridge.models.accounts.SignIn;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.sagebionetworks.bridge.services.email.EmailSignInEmailProvider;
import org.sagebionetworks.bridge.validators.EmailValidator;
import org.sagebionetworks.bridge.validators.EmailVerificationValidator;
import org.sagebionetworks.bridge.validators.PasswordResetValidator;
import org.sagebionetworks.bridge.validators.SignInValidator;
import org.sagebionetworks.bridge.validators.StudyParticipantValidator;
import org.sagebionetworks.bridge.validators.Validate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component("authenticationService")
public class AuthenticationService {

    private final Logger logger = LoggerFactory.getLogger(AuthenticationService.class);
    
    private static final String SESSION_SIGNIN_CACHE_KEY = "%s:%s:signInRequest";
    private static final int SESSION_SIGNIN_TIMEOUT = 60;
    
    private CacheProvider cacheProvider;
    private BridgeConfig config;
    private ConsentService consentService;
    private ParticipantOptionsService optionsService;
    private AccountDao accountDao;
    private ParticipantService participantService;
    private SendMailService sendMailService;
    private StudyService studyService;
    private PasswordResetValidator passwordResetValidator;

    @Autowired
    final void setCacheProvider(CacheProvider cache) {
        this.cacheProvider = cache;
    }
    @Autowired
    final void setBridgeConfig(BridgeConfig config) {
        this.config = config;
    }
    @Autowired
    final void setConsentService(ConsentService consentService) {
        this.consentService = consentService;
    }
    @Autowired
    final void setOptionsService(ParticipantOptionsService optionsService) {
        this.optionsService = optionsService;
    }
    @Autowired
    final void setAccountDao(AccountDao accountDao) {
        this.accountDao = accountDao;
    }
    @Autowired
    final void setPasswordResetValidator(PasswordResetValidator validator) {
        this.passwordResetValidator = validator;
    }
    @Autowired
    final void setParticipantService(ParticipantService participantService) {
        this.participantService = participantService;
    }
    @Autowired
    final void setSendMailService(SendMailService sendMailService) {
        this.sendMailService = sendMailService;
    }
    @Autowired
    final void setStudyService(StudyService studyService) {
        this.studyService = studyService;
    }

    public void requestEmailSignIn(SignIn signIn) {
        Validate.entityThrowingException(SignInValidator.EMAIL_SIGNIN_REQUEST, signIn);
        
        Study study = studyService.getStudy(signIn.getStudyId());
        if (!study.isEmailSignInEnabled()) {
            throw new UnauthorizedException("Email-based sign in not enabled for study: " + study.getName());
        }
        
        // check that email is not already locked
        String cacheKey = getEmailSignInCacheKey(study, signIn.getEmail());
        if (cacheProvider.getString(cacheKey) != null) {
            throw new LimitExceededException("Email currently pending confirmation.");
        }
        
        // check that email is in the study, if not, return quietly to prevent session enumeration attacks
        if (accountDao.getAccountWithEmail(study, signIn.getEmail()) == null) {
            return;
        }
        
        // set a time-limited token
        String token = BridgeUtils.generateGuid().replaceAll("-", "");
        cacheProvider.setString(cacheKey, token, SESSION_SIGNIN_TIMEOUT);
        
        // email the user the token
        EmailSignInEmailProvider provider = new EmailSignInEmailProvider(study, signIn.getEmail(), token);
        sendMailService.sendEmail(provider);
    }
    
    public UserSession emailSignIn(CriteriaContext context, SignIn signIn) {
        Validate.entityThrowingException(SignInValidator.EMAIL_SIGNIN, signIn);
        
        Study study = studyService.getStudy(signIn.getStudyId());
        String cacheKey = getEmailSignInCacheKey(study, signIn.getEmail());
        
        String storedToken = cacheProvider.getString(cacheKey);
        if (storedToken == null || !storedToken.equals(signIn.getToken())) {
            throw new AuthenticationFailedException();
        }
        // Consume the key regardless of what happens
        cacheProvider.removeString(cacheKey);
        
        Account account = accountDao.getAccountWithEmail(study, signIn.getEmail());
        if (account.getStatus() == AccountStatus.UNVERIFIED) {
            throw new EntityNotFoundException(Account.class);
        } else if (account.getStatus() == AccountStatus.DISABLED) {
            throw new AccountDisabledException();
        }

        UserSession session = getSessionFromAccount(study, context, account);

        if (!session.doesConsent() && !session.isInRole(Roles.ADMINISTRATIVE_ROLES)) {
            throw new ConsentRequiredException(session);
        }

        // The client can optionally change the password in order to sign in when the session expires
        if (signIn.getPassword() != null) {
            accountDao.changePassword(account, signIn.getPassword());
        }
        return session;
    }
    
    /**
     * This method returns the cached session for the user. A CriteriaContext object is not provided to the method, 
     * and the user's consent status is not re-calculated based on participation in one more more subpopulations. 
     * This only happens when calling session-constructing service methods (signIn and verifyEmail, both of which 
     * return newly constructed sessions).
     * @param sessionToken
     * @return session
     *      the persisted user session calculated on sign in or during verify email workflow
     */
    public UserSession getSession(String sessionToken) {
        if (sessionToken == null) {
            return null;
        }
        return cacheProvider.getUserSession(sessionToken);
    }
    
    /**
     * This method re-constructs the session based on potential changes to the user. It is called after a user 
     * account is updated, and takes the updated CriteriaContext to calculate the current state of the user.
     * @param study
     *      the user's study
     * @param context
     *      an updated set of criteria for calculating the user's consent status
     * @return
     *      newly created session object (not persisted)
     */
    public UserSession getSession(Study study, CriteriaContext context) {
        checkNotNull(study);
        checkNotNull(context);
        
        Account account = accountDao.getAccount(study, context.getUserId());
        return getSessionFromAccount(study, context, account);
    }

    public UserSession signIn(Study study, CriteriaContext context, SignIn signIn) throws EntityNotFoundException {
        checkNotNull(study);
        checkNotNull(context);
        checkNotNull(signIn);

        Validate.entityThrowingException(SignInValidator.PASSWORD_SIGNIN, signIn);

        Account account = accountDao.authenticate(study, signIn);

        UserSession session = getSessionFromAccount(study, context, account);
        // Do not call sessionUpdateService as we assume system is in sync with the session on sign in
        cacheProvider.setUserSession(session);
        
        return session;
    }

    public void signOut(final UserSession session) {
        if (session != null) {
            cacheProvider.removeSession(session);
        }
    }

    public IdentifierHolder signUp(Study study, StudyParticipant participant) {
        checkNotNull(study);
        checkNotNull(participant);
        
        Validate.entityThrowingException(new StudyParticipantValidator(study, true), participant);
        
        try {
            // Since caller has no roles, no roles can be assigned on sign up.
            return participantService.createParticipant(study, NO_CALLER_ROLES, participant, true);
            
        } catch(EntityAlreadyExistsException e) {
            // Suppress this. Otherwise it the response reveals that the email has already been taken, 
            // and you can infer who is in the study from the response. Instead send a reset password 
            // request to the email address in case user has forgotten password and is trying to sign 
            // up again.
            Email email = new Email(study.getIdentifier(), participant.getEmail());
            requestResetPassword(study, email);
            logger.info("Sign up attempt for existing email address in study '"+study.getIdentifier()+"'");
        }
        return null;
    }

    public void verifyEmail(EmailVerification verification) {
        checkNotNull(verification);

        Validate.entityThrowingException(EmailVerificationValidator.INSTANCE, verification);
        accountDao.verifyEmail(verification);
    }
    
    public void resendEmailVerification(StudyIdentifier studyIdentifier, Email email) {
        checkNotNull(studyIdentifier);
        checkNotNull(email);
        
        Validate.entityThrowingException(EmailValidator.INSTANCE, email);
        try {
            accountDao.resendEmailVerificationToken(studyIdentifier, email);    
        } catch(EntityNotFoundException e) {
            // Suppress this. Otherwise it reveals if the account does not exist
            logger.info("Resend email verification for unregistered email in study '"+studyIdentifier.getIdentifier()+"'");
        }
    }

    public void requestResetPassword(Study study, Email email) throws BridgeServiceException {
        checkNotNull(study);
        checkNotNull(email);
        
        Validate.entityThrowingException(EmailValidator.INSTANCE, email);
        try {
            accountDao.requestResetPassword(study, email);    
        } catch(EntityNotFoundException e) {
            // Suppress this. Otherwise it reveals if the account does not exist
            logger.info("Request reset password request for unregistered email in study '"+study.getIdentifier()+"'");
        }
    }

    public void resetPassword(PasswordReset passwordReset) throws BridgeServiceException {
        checkNotNull(passwordReset);

        Validate.entityThrowingException(passwordResetValidator, passwordReset);
        
        accountDao.resetPassword(passwordReset);
    }
    
    private UserSession getSessionFromAccount(Study study, CriteriaContext context, Account account) {
        StudyParticipant participant = participantService.getParticipant(study, account, false);

        // If the user does not have a language persisted yet, now that we have a session, we can retrieve it 
        // from the context, add it to the user/session, and persist it.
        if (participant.getLanguages().isEmpty() && !context.getLanguages().isEmpty()) {
            participant = new StudyParticipant.Builder().copyOf(participant)
                    .withLanguages(context.getLanguages()).build();
            optionsService.setOrderedStringSet(study, account.getHealthCode(), LANGUAGES, context.getLanguages());
        }
        
        UserSession session = new UserSession(participant);
        // The check for an existing session just prevents resetting the session tokens, the rest of the 
        // session is refreshed. This may change when we expire sessions correctly (currently they are held 
        // for a long time in memory), but this emulates earlier behavior.
        UserSession existingSession = cacheProvider.getUserSessionByUserId(account.getId());
        if (existingSession != null) {
            session.setSessionToken(existingSession.getSessionToken());
            session.setInternalSessionToken(existingSession.getInternalSessionToken());
        } else {
            session.setSessionToken(BridgeUtils.generateGuid());
            session.setInternalSessionToken(BridgeUtils.generateGuid());
        }
        session.setAuthenticated(true);
        session.setEnvironment(config.getEnvironment());
        session.setStudyIdentifier(study.getStudyIdentifier());
        
        CriteriaContext newContext = new CriteriaContext.Builder()
                .withContext(context)
                .withHealthCode(session.getHealthCode())
                .withUserId(session.getId())
                .withLanguages(session.getParticipant().getLanguages())
                .withUserDataGroups(session.getParticipant().getDataGroups())
                .build();
        
        session.setConsentStatuses(consentService.getConsentStatuses(newContext));
        
        return session;
    }
    
    private String getEmailSignInCacheKey(Study study, String email) {
        return String.format(SESSION_SIGNIN_CACHE_KEY, email, study.getIdentifier());
    }
}
